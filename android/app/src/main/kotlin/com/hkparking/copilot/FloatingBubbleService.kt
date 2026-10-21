package com.hkparking.copilot

import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker

data class MicSessionEvent(val isActive: Boolean, val reason: String)

class FloatingBubbleService : android.app.Service {
  val capabilityChecker: VoiceCapabilityChecker
  val tripStateMachine: TripStateMachine

  constructor() : this(VoiceCapabilityChecker(SilentSpeechProbe()), TripStateMachine())

  constructor(
    capabilityChecker: VoiceCapabilityChecker,
    tripStateMachine: TripStateMachine = TripStateMachine(),
  ) : super() {
    this.capabilityChecker = capabilityChecker
    this.tripStateMachine = tripStateMachine
    tripStateMachine.applyVoiceCapability(capabilityChecker)
  }

  private var capsuleView: android.view.View? = null

  inner class LocalBinder : android.os.Binder() {
    val service: FloatingBubbleService
      get() = this@FloatingBubbleService
  }

  override fun onBind(intent: android.content.Intent?): android.os.IBinder = LocalBinder()

  override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
    val entryName = intent?.getStringExtra(EXTRA_ENTRY)
    val entry = entryName?.let { runCatching { StartupEntry.valueOf(it) }.getOrNull() } ?: StartupEntry.A_1
    val hotwordEnabled = intent?.getBooleanExtra(EXTRA_HOTWORD, true) ?: true
    val recordAudioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED
    startInForeground(
      entry = entry,
      recordAudioGranted = recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
      emitFgsStartedImmediately = false,
    )
    if (!enterForeground()) return START_NOT_STICKY
    reportFgsStarted()
    attachCapsuleWindow()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    detachCapsuleWindow()
    isFgsRunning = false
    isCapsuleVisible = false
    super.onDestroy()
  }
  var isFgsRunning: Boolean = false
    private set
  var isCapsuleVisible: Boolean = false
    private set
  var activeFgsType: String = VoiceCapabilityChecker.FGS_LOCATION
    private set
  var isMicActive: Boolean = false
    private set
  var isAudioRecordRunning: Boolean = false
    private set
  var isAudioRecordPausedByFocus: Boolean = false
    private set
  var persistentNotificationText: String? = null
    private set
  var immediateJitTriggered: Boolean = false
    private set
  var tripEndedReason: String? = null
    private set
  val micSessionEvents = mutableListOf<MicSessionEvent>()
  val autoCardPopupShown: Boolean = false
  var manualCardVisible: Boolean = false
    private set
  var manualRetryRequested: Boolean = false
    private set
  var abandonedBySystemKill: Boolean = false
    private set
  val autoRestartScheduled: Boolean = false

  private var pendingFgsStartedCallback: (() -> Unit)? = null
  private var pendingInitialDistanceMeters: Int? = null

  fun onColdBubbleTap(): Boolean {
    if (!isFgsRunning) return false
    return false
  }

  fun startInForeground(
    entry: StartupEntry,
    recordAudioGranted: Boolean,
    hotwordEnabled: Boolean = true,
    initialDistanceMeters: Int? = null,
    emitFgsStartedImmediately: Boolean = false,
    onFgsStarted: () -> Unit = {},
  ) {
    val initial = capabilityChecker.startupSnapshot(recordAudioGranted, hotwordEnabled, entry)
    activeFgsType = if (entry == StartupEntry.B_2) {
      VoiceCapabilityChecker.FGS_LOCATION
    } else {
      capabilityChecker.fgsType
    }
    tripStateMachine.syncVoiceCapability(initial, capabilityChecker.hasConfirmed)
    val worker = Thread {
      val updated = capabilityChecker.recheckSilently(recordAudioGranted, hotwordEnabled, entry)
      activeFgsType = if (entry == StartupEntry.B_2) {
        VoiceCapabilityChecker.FGS_LOCATION
      } else {
        capabilityChecker.fgsType
      }
      tripStateMachine.syncVoiceCapability(updated, capabilityChecker.hasConfirmed)
    }
    worker.isDaemon = true
    worker.start()
    if (!tripStateMachine.fgsStartAccepted) {
      tripStateMachine.requestStart(
        entry = entry,
        destinationLocked = initialDistanceMeters != null,
        distanceToDestinationM = initialDistanceMeters,
      )
    }
    pendingFgsStartedCallback = onFgsStarted
    pendingInitialDistanceMeters = initialDistanceMeters
    if (emitFgsStartedImmediately) reportFgsStarted(initialDistanceMeters, onFgsStarted)
  }

  fun reportFgsStarted(initialDistanceMeters: Int? = null, onFgsStarted: () -> Unit = {}) {
    val distance = initialDistanceMeters ?: pendingInitialDistanceMeters
    isFgsRunning = true
    isCapsuleVisible = true
    abandonedBySystemKill = false
    tripStateMachine.applyVoiceCapability(capabilityChecker)
    tripStateMachine.onFgsStarted()
    if (distance != null && distance <= NativeConfig.TRIGGER_DISTANCE_M) immediateJitTriggered = true
    val saved = pendingFgsStartedCallback
    pendingFgsStartedCallback = null
    if (saved != null && saved !== onFgsStarted) saved.invoke()
    onFgsStarted()
    dispatchFgsStarted(this)
  }

  private fun enterForeground(): Boolean {
    val posted = runCatching {
      val manager = getSystemService(android.app.NotificationManager::class.java) ?: return false
      val channel = android.app.NotificationChannel(
        CHANNEL_ID,
        CHANNEL_NAME,
        android.app.NotificationManager.IMPORTANCE_LOW,
      )
      manager.createNotificationChannel(channel)
      val listening = activeFgsType == VoiceCapabilityChecker.FGS_LOCATION_MIC
      val body = if (listening) LISTENING else CHANNEL_NAME
      persistentNotificationText = body
      val notification = android.app.Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(applicationInfo.icon)
        .setContentTitle(CHANNEL_NAME)
        .setContentText(body)
        .build()
      var fgType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
      if (listening) {
        fgType = fgType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      }
      startForeground(NOTIFICATION_ID, notification, fgType)
    }
    return posted.isSuccess
  }

  private fun attachCapsuleWindow() {
    if (capsuleView != null || !isFgsRunning) return
    val wm = getSystemService(android.view.WindowManager::class.java) ?: return
    val view = android.widget.TextView(this).apply { text = CAPSULE_MARK }
    val params = android.view.WindowManager.LayoutParams(
      android.view.WindowManager.LayoutParams.WRAP_CONTENT,
      android.view.WindowManager.LayoutParams.WRAP_CONTENT,
      android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      android.graphics.PixelFormat.TRANSLUCENT,
    )
    params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
    runCatching { wm.addView(view, params) }.onSuccess { capsuleView = view }
  }

  private fun detachCapsuleWindow() {
    val view = capsuleView ?: return
    val wm = getSystemService(android.view.WindowManager::class.java)
    runCatching { wm?.removeView(view) }
    capsuleView = null
  }

  fun reportMicReady(succeeded: Boolean, reasonOnSuccess: String = NOTIFICATION_TAP) {
    if (succeeded && capabilityChecker.tripVoiceMode == TripVoiceMode.FULL_VOICE) {
      isMicActive = true
      isAudioRecordRunning = true
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION_MIC
      persistentNotificationText = LISTENING
      micSessionEvents += MicSessionEvent(isActive = true, reason = reasonOnSuccess)
      tripStateMachine.applyVoiceCapability(capabilityChecker)
      return
    }
    if (!succeeded) {
      capabilityChecker.onMicReadyFailed()
      isMicActive = false
      isAudioRecordRunning = false
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION
      micSessionEvents += MicSessionEvent(isActive = false, reason = MIC_FAILED)
    }
    tripStateMachine.onRecordAudioRevoked(capabilityChecker.ttsStatus == CapabilityStatus.AVAILABLE)
    tripStateMachine.applyVoiceCapability(capabilityChecker)
  }

  fun onAudioFocusChanged(focusLostToMapsOrCall: Boolean) {
    if (!isMicActive) return
    if (focusLostToMapsOrCall) {
      isAudioRecordPausedByFocus = true
      isAudioRecordRunning = false
      return
    }
    isAudioRecordPausedByFocus = false
    isAudioRecordRunning = true
  }

  fun onRuntimePermissionRevoked(revokedPermission: String) {
    if (revokedPermission == RECORD_AUDIO) {
      capabilityChecker.onRecordAudioRevoked()
      isMicActive = false
      isAudioRecordRunning = false
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION
      return
    }
    isFgsRunning = false
    isCapsuleVisible = false
    isMicActive = false
    isAudioRecordRunning = false
    manualCardVisible = false
    tripEndedReason = PERMISSION_REVOKED
    tripStateMachine.onRequiredPermissionRevoked()
  }

  fun onActiveBubbleTap(): Boolean {
    if (!isFgsRunning || !isCapsuleVisible) return onColdBubbleTap()
    if (tripStateMachine.state == TripState.GREY_BREATHING) manualRetryRequested = true
    if (capabilityChecker.tripVoiceMode == TripVoiceMode.FULL_VOICE) {
      if (!isMicActive) {
        activeFgsType = VoiceCapabilityChecker.FGS_LOCATION_MIC
        isMicActive = true
        isAudioRecordRunning = true
        persistentNotificationText = LISTENING
        micSessionEvents += MicSessionEvent(isActive = true, reason = BUBBLE_TAP)
      }
    } else {
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION
      isMicActive = false
      manualCardVisible = true
    }
    return true
  }

  fun onVoiceStartCommand(viaHeyGoogleColdStart: Boolean = false): Boolean {
    return viaHeyGoogleColdStart || (isFgsRunning && isMicActive)
  }

  fun onOemKilledOrTaskRemoved() {
    isFgsRunning = false
    isCapsuleVisible = false
    isMicActive = false
    isAudioRecordRunning = false
    manualCardVisible = false
    abandonedBySystemKill = true
    tripEndedReason = ABANDONED_OEM_KILL
    tripStateMachine.onOemKilled()
  }

  companion object {
    const val NOTIFICATION_TAP = "NOTIFICATION_TAP"
    const val MIC_FAILED = "MIC_FAILED"
    const val LISTENING = "正在聽"
    const val RECORD_AUDIO = "RECORD_AUDIO"
    const val PERMISSION_REVOKED = "PERMISSION_REVOKED"
    const val BUBBLE_TAP = "BUBBLE_TAP"
    const val ABANDONED_OEM_KILL = "ABANDONED_OEM_KILL"
    const val EXTRA_ENTRY = "entry"
    const val EXTRA_HOTWORD = "hotwordEnabled"
    const val CHANNEL_ID = "hkparking_copilot_fgs"
    const val CHANNEL_NAME = "泊車副駕"
    const val CAPSULE_MARK = "P"
    const val NOTIFICATION_ID = 1
    private val fgsStartedListeners = mutableListOf<(FloatingBubbleService) -> Unit>()

    fun addFgsStartedListener(listener: (FloatingBubbleService) -> Unit) {
      synchronized(fgsStartedListeners) { fgsStartedListeners += listener }
    }

    fun removeFgsStartedListener(listener: (FloatingBubbleService) -> Unit) {
      synchronized(fgsStartedListeners) { fgsStartedListeners -= listener }
    }

    fun dispatchFgsStarted(service: FloatingBubbleService) {
      val snapshot = synchronized(fgsStartedListeners) { fgsStartedListeners.toList() }
      snapshot.forEach { it(service) }
    }
  }
}

private class SilentSpeechProbe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = LocaleProbe()
  override fun probeTts(locale: String): LocaleProbe = LocaleProbe()
}
