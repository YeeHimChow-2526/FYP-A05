package com.hkparking.copilot

import com.hkparking.copilot.share.ParsedShare
import com.hkparking.copilot.share.ShareIntake
import com.hkparking.copilot.share.ShareTextParser
import com.hkparking.copilot.voice.ZhHkTemplates

class ShareIntentReceiverActivity : android.app.Activity {
  private val service: FloatingBubbleService?
  private val clockMs: () -> Long
  private var launchStartedAtMs: Long = 0L
  private var timeoutHandler: android.os.Handler? = null
  private var timeoutRunnable: Runnable? = null
  private var fgsStartedListener: ((FloatingBubbleService) -> Unit)? = null
  private var bootstrapTimeoutListener: (() -> Unit)? = null

  constructor() : super() {
    service = null
    clockMs = { System.currentTimeMillis() }
  }

  constructor(
    service: FloatingBubbleService,
    clockMs: () -> Long = { System.currentTimeMillis() },
  ) : super() {
    this.service = service
    this.clockMs = clockMs
  }

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    val action = intent?.action
    val type = intent?.type
    val text = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
    val subject = intent?.getStringExtra(android.content.Intent.EXTRA_SUBJECT)
    if (action != android.content.Intent.ACTION_SEND || type == null || !type.startsWith("text/plain")) {
      failureReason = SHARE_EMPTY
      isFinished = true
      finish()
      return
    }
    val parsed = ShareTextParser.parse(text, subject)
    lastParsed = parsed
    if (parsed.isEmpty) {
      failureReason = SHARE_EMPTY
      isFinished = true
      finish()
      return
    }
    val permissions = readPermissions()
    if (!acceptBridgeShare(parsed, permissions)) {
      if (permissions.postNotificationsGranted && lastBridgeMissing.isNotEmpty()) {
        postRepairNotification(ZhHkTemplates.permissionRepair(lastBridgeMissing.joinToString("、")))
      }
      finish()
      return
    }
    startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    isFinished = true
    finish()
  }

  override fun onDestroy() {
    timeoutRunnable?.let { runnable -> timeoutHandler?.removeCallbacks(runnable) }
    clearHandshake()
    super.onDestroy()
  }

  var isFinished: Boolean = false
    private set
  var fgsStartedReceived: Boolean = false
    private set
  var bootstrapTimedOut: Boolean = false
    private set
  var failureReason: String? = null
    private set
  var lastPermissionGate: PermissionGateResult? = null
    private set
  var lastParsed: ParsedShare? = null
    private set
  var lastBridgeMissing: List<String> = emptyList()
    private set

  fun acceptBridgeShare(parsed: ParsedShare, permissions: PermissionCheckInput): Boolean {
    val missing = mutableListOf<String>()
    if (!permissions.fineLocationGranted || permissions.coarseLocationOnly) {
      missing += StartupPermissionChecker.FINE_LOCATION
    }
    if (!permissions.postNotificationsGranted) missing += StartupPermissionChecker.NOTIFICATIONS
    lastBridgeMissing = missing
    lastPermissionGate = PermissionGateResult(
      allowedToStartFgs = missing.isEmpty(),
      missingItems = missing,
      activityFinished = missing.isNotEmpty(),
    )
    if (missing.isNotEmpty()) {
      failureReason = MISSING_PERMISSION
      isFinished = true
      com.hkparking.copilot.bridge.TripPlatformEvents.emit(
        MISSING_PERMISSION,
        mapOf("missingItems" to missing),
      )
      return false
    }
    com.hkparking.copilot.bridge.TripPlatformEvents.emit(
      "onShareIntake",
      mapOf("title" to parsed.title, "lat" to parsed.lat, "lng" to parsed.lng, "hasUrl" to parsed.hasUrl),
    )
    return true
  }

  fun launchFromShare(
    text: String?,
    subject: String?,
    permissions: PermissionCheckInput,
    hotwordEnabled: Boolean = true,
  ): Boolean {
    val parsed = ShareTextParser.parse(text, subject)
    lastParsed = parsed
    if (parsed.isEmpty) {
      failureReason = SHARE_EMPTY
      isFinished = true
      return false
    }
    val gate = StartupPermissionChecker.checkBeforeStart(StartupEntry.B_2, permissions)
    lastPermissionGate = gate
    if (!gate.allowedToStartFgs) {
      isFinished = gate.activityFinished
      if (gate.missingItems.isNotEmpty()) failureReason = MISSING_PERMISSION
      return false
    }
    isFinished = false
    val host = service ?: return false
    val intake = ShareIntake(parsed.title, parsed.lat, parsed.lng, parsed.hasUrl)
    if (host.isFgsRunning) {
      // D1 mid-trip JVM path: apply share without restarting the trip.
      host.onShareIntake(intake)
      fgsStartedReceived = true
      isFinished = true
      return true
    }
    launchStartedAtMs = clockMs()
    host.startInForeground(
      entry = StartupEntry.B_2,
      recordAudioGranted = permissions.recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
      onFgsStarted = {
        if (!bootstrapTimedOut) {
          fgsStartedReceived = true
          isFinished = true
          host.onShareIntake(intake)
        }
        clearHandshake()
      },
    )
    val timeoutListener: () -> Unit = {
      markBootstrapTimedOut()
      clearHandshake()
    }
    bootstrapTimeoutListener = timeoutListener
    FloatingBubbleService.addBootstrapTimeoutListener(timeoutListener)
    return true
  }

  fun onHandshakeTick() {
    if (fgsStartedReceived || lastPermissionGate?.allowedToStartFgs == false) return
    val elapsed = clockMs() - launchStartedAtMs
    if (!fgsStartedReceived && elapsed >= NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS) {
      markBootstrapTimedOut()
      clearHandshake()
    }
  }

  private fun putShareExtras(intent: android.content.Intent, parsed: ParsedShare) {
    if (parsed.title != null) {
      intent.putExtra(FloatingBubbleService.EXTRA_SHARE_TITLE, parsed.title)
    }
    if (parsed.lat != null && parsed.lng != null) {
      intent.putExtra(FloatingBubbleService.EXTRA_SHARE_LAT, parsed.lat)
      intent.putExtra(FloatingBubbleService.EXTRA_SHARE_LNG, parsed.lng)
    }
    intent.putExtra(FloatingBubbleService.EXTRA_SHARE_HAS_URL, parsed.hasUrl)
  }

  private fun markBootstrapTimedOut(): Boolean {
    if (fgsStartedReceived || bootstrapTimedOut) return false
    bootstrapTimedOut = true
    failureReason = FGS_BOOTSTRAP_TIMEOUT
    runCatching {
      android.util.Log.w(
        "ShareIntentReceiverActivity",
        "bootstrap timeout reason=FGS_BOOTSTRAP_TIMEOUT limitMs=${NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS} service=${if (service != null) "bound" else "none"}",
      )
    }
    isFinished = true
    service?.onBootstrapTimedOut()
    return true
  }

  // D15: copy of FgsBootstrapActivity readPermissions / postRepairNotification (no refactor).
  private fun readPermissions(): PermissionCheckInput {
    val fine = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    return PermissionCheckInput(
      mode = OperatingMode.MODE_B,
      fineLocationGranted = fine,
      coarseLocationOnly = !fine && coarse,
      postNotificationsGranted = granted(android.Manifest.permission.POST_NOTIFICATIONS),
      systemAlertWindowGranted = android.provider.Settings.canDrawOverlays(this),
      notificationListenerGranted = notificationListenerEnabled(),
      recordAudioGranted = granted(android.Manifest.permission.RECORD_AUDIO),
    )
  }

  private fun granted(permission: String): Boolean {
    return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
  }

  private fun notificationListenerEnabled(): Boolean {
    val flat = android.provider.Settings.Secure.getString(
      contentResolver,
      ENABLED_NOTIFICATION_LISTENERS,
    ) ?: return false
    return flat.contains(packageName)
  }

  private fun postRepairNotification(text: String) {
    val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
    val channel = android.app.NotificationChannel(
      FloatingBubbleService.CHANNEL_ID,
      FloatingBubbleService.CHANNEL_NAME,
      android.app.NotificationManager.IMPORTANCE_DEFAULT,
    )
    manager.createNotificationChannel(channel)
    val notification = android.app.Notification.Builder(this, FloatingBubbleService.CHANNEL_ID)
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle(FloatingBubbleService.CHANNEL_NAME)
      .setContentText(text)
      .build()
    manager.notify(REPAIR_NOTIFICATION_ID, notification)
  }

  private fun clearHandshake() {
    fgsStartedListener?.let { FloatingBubbleService.removeFgsStartedListener(it) }
    fgsStartedListener = null
    bootstrapTimeoutListener?.let { FloatingBubbleService.removeBootstrapTimeoutListener(it) }
    bootstrapTimeoutListener = null
  }

  companion object {
    const val MISSING_PERMISSION = "MISSING_PERMISSION"
    const val FGS_BOOTSTRAP_TIMEOUT = "FGS_BOOTSTRAP_TIMEOUT"
    const val SHARE_EMPTY = "SHARE_EMPTY"
    const val REPAIR_NOTIFICATION_ID = 2
    const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
  }
}
