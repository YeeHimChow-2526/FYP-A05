package com.hkparking.copilot

import com.hkparking.copilot.voice.ZhHkTemplates

enum class OperatingMode { MODE_A, MODE_B }

data class PermissionCheckInput(
  val mode: OperatingMode = OperatingMode.MODE_B,
  val fineLocationGranted: Boolean = true,
  val coarseLocationOnly: Boolean = false,
  val postNotificationsGranted: Boolean = true,
  val systemAlertWindowGranted: Boolean = true,
  val notificationListenerGranted: Boolean = true,
  val recordAudioGranted: Boolean = true,
)

data class PermissionGateResult(
  val allowedToStartFgs: Boolean,
  val missingItems: List<String>,
  val activityFinished: Boolean = false,
  val repairNotificationText: String? = null,
  val redirectToPermissionBanner: Boolean = false,
)

object StartupPermissionChecker {
  const val FINE_LOCATION = "需要精確位置"
  const val NOTIFICATIONS = "通知"
  const val OVERLAY = "懸浮窗"
  const val LISTENER = "通知存取權"

  fun checkBeforeStart(entry: StartupEntry, input: PermissionCheckInput): PermissionGateResult {
    val missing = mutableListOf<String>()
    if (!input.fineLocationGranted || input.coarseLocationOnly) missing += FINE_LOCATION
    if (!input.postNotificationsGranted) missing += NOTIFICATIONS
    if (!input.systemAlertWindowGranted) missing += OVERLAY
    if (input.mode == OperatingMode.MODE_A && !input.notificationListenerGranted) missing += LISTENER
    if (missing.isEmpty()) {
      return PermissionGateResult(allowedToStartFgs = true, missingItems = emptyList())
    }
    val external = entry == StartupEntry.A_1 || entry == StartupEntry.A_3 || entry == StartupEntry.B_1 || entry == StartupEntry.B_2
    val repair = if (external && input.postNotificationsGranted) {
      ZhHkTemplates.permissionRepair(missing.joinToString("、"))
    } else {
      null
    }
    return PermissionGateResult(
      allowedToStartFgs = false,
      missingItems = missing,
      activityFinished = external,
      repairNotificationText = repair,
      redirectToPermissionBanner = !external,
    )
  }
}

class FgsBootstrapActivity : android.app.Activity {
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
    val entry = readEntry()
    val permissions = readPermissions()
    val gate = StartupPermissionChecker.checkBeforeStart(entry, permissions)
    lastPermissionGate = gate
    if (!gate.allowedToStartFgs) {
      failureReason = MISSING_PERMISSION
      isFinished = gate.activityFinished
      if (gate.repairNotificationText != null) postRepairNotification(gate.repairNotificationText)
      finish()
      return
    }
    val listener: (FloatingBubbleService) -> Unit = listener@{
      if (bootstrapTimedOut) return@listener
      timeoutRunnable?.let { runnable -> timeoutHandler?.removeCallbacks(runnable) }
      fgsStartedReceived = true
      isFinished = true
      clearHandshake()
      finish()
    }
    fgsStartedListener = listener
    FloatingBubbleService.addFgsStartedListener(listener)
    val timeoutListener: () -> Unit = {
      if (markBootstrapTimedOut()) {
        timeoutRunnable?.let { runnable -> timeoutHandler?.removeCallbacks(runnable) }
        clearHandshake()
        finish()
      }
    }
    bootstrapTimeoutListener = timeoutListener
    FloatingBubbleService.addBootstrapTimeoutListener(timeoutListener)
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    timeoutHandler = handler
    val timeout = Runnable {
      if (markBootstrapTimedOut()) {
        clearHandshake()
        finish()
      }
    }
    timeoutRunnable = timeout
    handler.postDelayed(timeout, NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS)
    val start = android.content.Intent(this, FloatingBubbleService::class.java)
    start.putExtra(FloatingBubbleService.EXTRA_ENTRY, entry.name)
    startForegroundService(start)
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

  fun launchFromEntry(
    entry: StartupEntry,
    permissions: PermissionCheckInput,
    hotwordEnabled: Boolean = true,
    initialDistanceMeters: Int? = null,
  ): Boolean {
    val gate = StartupPermissionChecker.checkBeforeStart(entry, permissions)
    lastPermissionGate = gate
    if (!gate.allowedToStartFgs) {
      isFinished = gate.activityFinished
      if (gate.missingItems.isNotEmpty()) failureReason = MISSING_PERMISSION
      return false
    }
    isFinished = false
    val host = service ?: return false
    launchStartedAtMs = clockMs()
    host.startInForeground(
      entry = entry,
      recordAudioGranted = permissions.recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
      initialDistanceMeters = initialDistanceMeters,
      onFgsStarted = {
        if (!bootstrapTimedOut) {
          fgsStartedReceived = true
          isFinished = true
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

  private fun markBootstrapTimedOut(): Boolean {
    if (fgsStartedReceived || bootstrapTimedOut) return false
    bootstrapTimedOut = true
    failureReason = FGS_BOOTSTRAP_TIMEOUT
    runCatching {
      android.util.Log.w(
        "FgsBootstrapActivity",
        "bootstrap timeout reason=FGS_BOOTSTRAP_TIMEOUT limitMs=${NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS} service=${if (service != null) "bound" else "none"}",
      )
    }
    isFinished = true
    service?.onBootstrapTimedOut()
    return true
  }

  private fun readEntry(): StartupEntry {
    val name = intent?.getStringExtra(FloatingBubbleService.EXTRA_ENTRY) ?: return StartupEntry.A_1
    return runCatching { StartupEntry.valueOf(name) }.getOrDefault(StartupEntry.A_1)
  }

  private fun readPermissions(): PermissionCheckInput {
    val fine = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    val modeName = intent?.getStringExtra(EXTRA_MODE)
    return PermissionCheckInput(
      mode = if (modeName == OperatingMode.MODE_A.name) OperatingMode.MODE_A else OperatingMode.MODE_B,
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
    const val EXTRA_MODE = "operatingMode"
    const val REPAIR_NOTIFICATION_ID = 2
    const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
  }
}
