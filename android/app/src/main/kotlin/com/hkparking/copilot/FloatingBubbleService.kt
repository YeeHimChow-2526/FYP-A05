package com.hkparking.copilot

import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.CommandCode
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

data class MicSessionEvent(val isActive: Boolean, val reason: String)

class FloatingBubbleService : android.app.Service {
  val capabilityChecker: VoiceCapabilityChecker
  val tripStateMachine: TripStateMachine
  val delayMs: suspend (Long) -> Unit
  private val apiClient: com.hkparking.copilot.net.ApiClient

  constructor() : this(VoiceCapabilityChecker(SilentSpeechProbe()), TripStateMachine(), defaultDelayMs())

  constructor(delayMs: suspend (Long) -> Unit) : this(
    VoiceCapabilityChecker(SilentSpeechProbe()),
    TripStateMachine(),
    delayMs,
  )

  constructor(
    capabilityChecker: VoiceCapabilityChecker,
    tripStateMachine: TripStateMachine = TripStateMachine(),
    clockDelay: suspend (Long) -> Unit = defaultDelayMs(),
  ) : super() {
    this.capabilityChecker = capabilityChecker
    this.tripStateMachine = tripStateMachine
    this.delayMs = clockDelay
    this.apiClient = com.hkparking.copilot.net.ApiClient(
      com.hkparking.copilot.di.Bindings.apiTransport,
      tripStateMachine,
      this.delayMs,
    )
    tripStateMachine.onStateChanged = { updateCapsuleVisual() }
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
    )
    if (!enterForeground()) {
      onBootstrapTimedOut()
      return START_NOT_STICKY
    }
    reportFgsStarted()
    attachCapsuleWindow()
    updateCapsuleVisual()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    com.hkparking.copilot.di.Bindings.detachService(this)
    detachCapsuleWindow()
    isFgsRunning = false
    isCapsuleVisible = false
    super.onDestroy()
  }
  var isFgsRunning: Boolean = false
    private set
  var isCapsuleVisible: Boolean = false
    private set
  var capsuleVisualLabel: String = ""
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
  var manualCardVisible: Boolean = false
    private set
  var manualRetryRequested: Boolean = false
    private set
  var abandonedBySystemKill: Boolean = false
    private set

  private var hasPendingFgsStart: Boolean = false
  private var pendingFgsStartedCallback: (() -> Unit)? = null
  private var pendingInitialDistanceMeters: Int? = null
  private var lastLoggedSnapshot: String? = null
  private var portsWired: Boolean = false

  fun onColdBubbleTap(): Boolean {
    if (!isFgsRunning) return false
    return false
  }

  fun startInForeground(
    entry: StartupEntry,
    recordAudioGranted: Boolean,
    hotwordEnabled: Boolean = true,
    initialDistanceMeters: Int? = null,
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
        distanceToDestinationM = initialDistanceMeters,
      )
    }
    tripStateMachine.onMicFgsActive(activeFgsType == VoiceCapabilityChecker.FGS_LOCATION_MIC)
    wirePorts()
    hasPendingFgsStart = true
    pendingFgsStartedCallback = onFgsStarted
    pendingInitialDistanceMeters = initialDistanceMeters
  }

  fun onBootstrapTimedOut() {
    hasPendingFgsStart = false
    pendingFgsStartedCallback = null
    pendingInitialDistanceMeters = null
  }

  fun reportFgsStarted(initialDistanceMeters: Int? = null, onFgsStarted: () -> Unit = {}) {
    if (!hasPendingFgsStart && !isFgsRunning) return
    val distance = initialDistanceMeters ?: pendingInitialDistanceMeters
    val saved = pendingFgsStartedCallback
    hasPendingFgsStart = false
    pendingFgsStartedCallback = null
    pendingInitialDistanceMeters = null
    isFgsRunning = true
    isCapsuleVisible = true
    abandonedBySystemKill = false
    tripStateMachine.applyVoiceCapability(capabilityChecker)
    tripStateMachine.onFgsStarted()
    if (distance != null && distance <= NativeConfig.TRIGGER_DISTANCE_M) immediateJitTriggered = true
    if (saved != null && saved !== onFgsStarted) saved.invoke()
    onFgsStarted()
    dispatchFgsStarted(this)
    wirePorts()
    updateCapsuleVisual()
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

  private fun wirePorts() {
    com.hkparking.copilot.di.Bindings.attachService(this)
    if (portsWired) return
    portsWired = true
    com.hkparking.copilot.di.Bindings.locationSource.setLocationListener { distanceMeters, _, _ ->
      onLocationFix(distanceMeters)
    }
    com.hkparking.copilot.di.Bindings.voiceInput.setUtteranceListener { text, confidence ->
      onUtterance(text, confidence)
    }
  }

  private fun onLocationFix(distanceMeters: Int) {
    if (!isFgsRunning) return
    val wasJitTriggered = tripStateMachine.jitTriggered
    tripStateMachine.onDistanceToDestination(distanceMeters)
    updateCapsuleVisual()
    if (!wasJitTriggered && tripStateMachine.jitTriggered && tripStateMachine.recommend) {
      launchRecommend(QueryTriggerMode.BACKGROUND_PREFETCH)
    }
  }

  private fun onUtterance(text: String, confidence: Double?) {
    val parsed = CommandCodes.parse(text, ParseContext(confidence = confidence))
    if (parsed.code == CommandCode.SET_DESTINATION && !parsed.destinationName.isNullOrBlank()) {
      tripStateMachine.onDestinationSet(destinationName = parsed.destinationName)
      updateCapsuleVisual()
      if (tripStateMachine.recommend) {
        launchRecommend(QueryTriggerMode.BACKGROUND_PREFETCH)
      }
    }
    if (parsed.code == CommandCode.FIND_PARKING) {
      launchRecommend(QueryTriggerMode.DRIVER_ACTIVE)
      updateCapsuleVisual()
    }
  }

  private fun launchRecommend(mode: QueryTriggerMode) {
    launchApi {
      apiClient.recommendAndRoute(buildRecommendRequest(), mode)
      updateCapsuleVisual()
    }
  }

  private fun buildRecommendRequest(): RecommendRequest {
    return RecommendRequest(
      sessionId = tripStateMachine.sessionId ?: "",
      originLat = 0.0,
      originLng = 0.0,
      destLat = 0.0,
      destLng = 0.0,
      destName = tripStateMachine.destinationName ?: "",
      preference = "SMART_HYBRID",
    )
  }

  private fun launchApi(block: suspend () -> Unit) {
    block.startCoroutine(Continuation(EmptyCoroutineContext) { })
  }

  fun updateCapsuleVisual() {
    val light = tripStateMachine.capsuleLight
    val pip = tripStateMachine.statusPip
    capsuleVisualLabel = "P [${light.name}]"
    val snapshot = "light=$light, pip=$pip, state=${tripStateMachine.state}"
    if (snapshot != lastLoggedSnapshot) {
      lastLoggedSnapshot = snapshot
      runCatching {
        android.util.Log.i(
          "FloatingBubbleService",
          "Capsule visual updated: $snapshot",
        )
      }
    }
    val view = capsuleView ?: return
    runCatching {
      if (view is android.widget.TextView) {
        view.text = "P · ${light.name}"
        view.setBackgroundColor(0xCC121212.toInt())
        view.setTextColor(android.graphics.Color.WHITE)
        view.setPadding(24, 16, 24, 16)
      }
      view.invalidate()
    }
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
      tripStateMachine.onMicFgsActive(true)
      tripStateMachine.applyVoiceCapability(capabilityChecker)
      return
    }
    if (!succeeded) {
      capabilityChecker.onMicReadyFailed()
      isMicActive = false
      isAudioRecordRunning = false
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION
      tripStateMachine.onMicFgsActive(false)
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
      tripStateMachine.onMicFgsActive(false)
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
      tripStateMachine.onMicFgsActive(true)
    } else {
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION
      isMicActive = false
      tripStateMachine.onMicFgsActive(false)
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

private fun defaultDelayMs(): suspend (Long) -> Unit = { ms ->
  if (ms > 0L) {
    suspendCoroutine { cont ->
      val looper = runCatching { android.os.Looper.getMainLooper() }.getOrNull()
      if (looper != null) {
        android.os.Handler(looper).postDelayed({ cont.resume(Unit) }, ms)
      } else {
        java.util.concurrent.CompletableFuture.delayedExecutor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
          .execute { cont.resume(Unit) }
      }
    }
  }
}

private class SilentSpeechProbe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = LocaleProbe()
  override fun probeTts(locale: String): LocaleProbe = LocaleProbe()
}
