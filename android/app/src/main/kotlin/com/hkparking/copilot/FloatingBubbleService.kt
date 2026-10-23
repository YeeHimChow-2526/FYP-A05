package com.hkparking.copilot

import com.hkparking.copilot.di.NetworkStatusProvider
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.voice.CapabilitySnapshot
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.CommandCode
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.RecommendationCard
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.TtsService
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
import com.hkparking.copilot.voice.createTtsEngine
import com.hkparking.copilot.voice.AndroidSpeechProbe
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
  val ttsService: TtsService
  private val networkStatus: NetworkStatusProvider
  private val apiClient: com.hkparking.copilot.net.ApiClient

  constructor() : this(defaultDelayMs())

  constructor(delayMs: suspend (Long) -> Unit) : this(
    VoiceCapabilityChecker(AndroidSpeechProbe()),
    TripStateMachine(),
    delayMs,
  )

  constructor(
    capabilityChecker: VoiceCapabilityChecker,
    tripStateMachine: TripStateMachine = TripStateMachine(),
    clockDelay: suspend (Long) -> Unit = defaultDelayMs(),
    networkStatus: NetworkStatusProvider = com.hkparking.copilot.di.Bindings.networkStatus,
  ) : super() {
    this.capabilityChecker = capabilityChecker
    this.tripStateMachine = tripStateMachine
    this.delayMs = clockDelay
    this.networkStatus = networkStatus
    this.ttsService = TtsService(null, capabilityChecker)
    this.apiClient = com.hkparking.copilot.net.ApiClient(
      com.hkparking.copilot.di.Bindings.apiTransport,
      tripStateMachine,
      this::onAttemptFailed,
      delayMs,
    )
    capabilityChecker.attachContext(this)
    tripStateMachine.onStateChanged = { updateCapsuleVisual() }
    tripStateMachine.applyVoiceCapability(capabilityChecker)
  }

  private var capsuleView: android.view.View? = null

  inner class LocalBinder : android.os.Binder() {
    val service: FloatingBubbleService
      get() = this@FloatingBubbleService
  }

  override fun onBind(intent: android.content.Intent?): android.os.IBinder = LocalBinder()

  override fun onCreate() {
    super.onCreate()
    if (ttsEngineReady) return
    val engine = createTtsEngine(this, this::onTtsInitFailed)
    if (engine != null && ttsService.attachEngine(engine)) {
      ttsEngineReady = true
    }
  }

  override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
    val entryName = intent?.getStringExtra(EXTRA_ENTRY)
    val entry = entryName?.let { runCatching { StartupEntry.valueOf(it) }.getOrNull() } ?: StartupEntry.A_1
    val hotwordEnabled = intent?.getBooleanExtra(EXTRA_HOTWORD, true) ?: true
    restartTripFromEntry(entry, hotwordEnabled)
    return START_NOT_STICKY
  }

  internal fun restartTripFromEntry(entry: StartupEntry, hotwordEnabled: Boolean = true): Boolean {
    val recordAudioGranted = runCatching {
      checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    startInForeground(
      entry = entry,
      recordAudioGranted = recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
    )
    if (!enterForeground()) {
      onBootstrapTimedOut()
      com.hkparking.copilot.di.Bindings.clearPendingStart()
      runCatching {
        android.util.Log.i("SimEventReceiver", "START_TRIP rejected reason=enter_foreground_failed")
      }
      return false
    }
    reportFgsStarted()
    attachCapsuleWindow()
    updateCapsuleVisual()
    return true
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
  var listenStopReason: String? = null
    private set
  var listenLimitS: Double? = null
    private set
  var bootstrapTimeoutReason: String? = null
    private set

  private var hasPendingFgsStart: Boolean = false
  private var speechHeard: Boolean = false
  internal val launchedRecommendModes = mutableListOf<QueryTriggerMode>()
  internal var capsuleSnapshot: String = ""
    private set
  private var lastDistanceMeters: Int = 0
  private var hasDistanceFix: Boolean = false
  private var pendingFgsStartedCallback: (() -> Unit)? = null
  private var pendingInitialDistanceMeters: Int? = null
  private var lastLoggedSnapshot: String? = null
  internal var portsWired: Boolean = false
    private set
  internal var enterForegroundGate: (() -> Boolean)? = null
  private var voiceApplyEpoch: Int = 0
  private var ttsEngineReady: Boolean = false
  private val spokenDriverCues = mutableSetOf<String>()
  private var cachedCards: List<RecommendationCard> = emptyList()
  private var cachedSourceStatus: String = "ok"
  private var cachedSpokenCue: String? = null
  var parkedCandidateSessionId: String? = null
    private set
  var parkedCandidateName: String? = null
    private set

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
    launchedRecommendModes.clear()
    if (!isFgsRunning) {
      lastDistanceMeters = 0
      hasDistanceFix = false
    }
    val initial = capabilityChecker.startupSnapshot(recordAudioGranted, hotwordEnabled, entry)
    activeFgsType = if (entry == StartupEntry.B_2) {
      VoiceCapabilityChecker.FGS_LOCATION
    } else {
      capabilityChecker.fgsType
    }
    tripStateMachine.syncVoiceCapability(initial, capabilityChecker.hasConfirmed)
    val epochAtStart = voiceApplyEpoch
    val worker = Thread {
      val updated = capabilityChecker.recheckSilently(recordAudioGranted, hotwordEnabled, entry)
      if (epochAtStart != voiceApplyEpoch) return@Thread
      activeFgsType = if (entry == StartupEntry.B_2) {
        VoiceCapabilityChecker.FGS_LOCATION
      } else {
        capabilityChecker.fgsType
      }
      tripStateMachine.syncVoiceCapability(updated, capabilityChecker.hasConfirmed)
      logVoiceCapability("recheck")
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
    bootstrapTimeoutReason = "FGS_STARTED_TIMEOUT_${NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS}"
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
    enterForegroundGate?.let { return it() }
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
    com.hkparking.copilot.di.Bindings.locationSource.setMotionListener { distanceMeters, speedKmh, fixAgeSeconds, lowSpeedHoldS, exitHoldS ->
      onMotionSample(distanceMeters, speedKmh, fixAgeSeconds, lowSpeedHoldS, exitHoldS)
    }
    com.hkparking.copilot.di.Bindings.voiceInput.setUtteranceListener { text, confidence ->
      onUtterance(text, confidence)
    }
  }

  private fun onMotionSample(
    distanceMeters: Int,
    speedKmh: Double,
    fixAgeSeconds: Int,
    lowSpeedHoldS: Int,
    exitHoldS: Int,
  ) {
    if (!isFgsRunning) return
    tripStateMachine.onApproachSample(distanceMeters, speedKmh, lowSpeedHoldS, fixAgeSeconds, exitHoldS)
    updateCapsuleVisual()
    if (
      tripStateMachine.state == TripState.APPROACH_MUTED ||
      tripStateMachine.state == TripState.PARKED_CONFIRMED
    ) {
      return
    }
    if (exitHoldS > 0) return
    onLocationFix(distanceMeters)
  }

  fun onEngineShutdown(withinTargetOrBasementLoss: Boolean) {
    val sessionId = tripStateMachine.sessionId
    val targetCarparkId = cachedCards.firstOrNull()?.carparkId ?: tripStateMachine.destinationName.orEmpty()
    val targetName = cachedCards.firstOrNull()?.name ?: tripStateMachine.destinationName
    tripStateMachine.onEngineShutdown(withinTargetOrBasementLoss)
    tripEndedReason = if (withinTargetOrBasementLoss) "PARKED_CONFIRMED" else "ENGINE_SHUTDOWN"
    if (withinTargetOrBasementLoss && sessionId != null) {
      parkedCandidateSessionId = sessionId
      parkedCandidateName = targetName
      runCatching {
        android.util.Log.i("FloatingBubbleService", "Parked candidate written: sessionId=$sessionId")
      }
      if (networkStatus.isOnline()) {
        launchApi { apiClient.releaseSession(sessionId, targetCarparkId, isOnline = true) }
        runCatching {
          android.util.Log.i("FloatingBubbleService", "Release session sent: sessionId=$sessionId")
        }
      } else {
        runCatching {
          android.util.Log.i("FloatingBubbleService", "Release session skipped: sessionId=$sessionId")
        }
      }
    } else {
      runCatching {
        android.util.Log.i("FloatingBubbleService", "Release session skipped: sessionId=${sessionId ?: ""}")
      }
    }
    val reason = tripEndedReason ?: "ENGINE_SHUTDOWN"
    launchApi {
      delayMs(NativeConfig.TEARDOWN_FADE_MS.toLong())
      finishTeardown(reason)
    }
  }

  fun onVoiceCapabilityApplied(snapshot: CapabilitySnapshot, micFgsActive: Boolean) {
    voiceApplyEpoch += 1
    val previous = tripStateMachine.tripVoiceMode
    capabilityChecker.applyExternalSnapshot(snapshot)
    tripStateMachine.syncVoiceCapability(snapshot, hasConfirmed = true)
    tripStateMachine.onMicFgsActive(micFgsActive)
    val fullVoice = snapshot.mode == TripVoiceMode.FULL_VOICE
    isMicActive = micFgsActive && fullVoice
    isAudioRecordRunning = isMicActive
    activeFgsType = if (isMicActive) VoiceCapabilityChecker.FGS_LOCATION_MIC else VoiceCapabilityChecker.FGS_LOCATION
    manualCardVisible = !fullVoice
    if (isMicActive) persistentNotificationText = LISTENING
    val reason = if (previous != tripStateMachine.tripVoiceMode) "mode_changed" else "applied"
    logVoiceCapability(reason)
  }

  fun onListenSilence(elapsedS: Double?) {
    val limit = if (speechHeard) NativeConfig.LISTEN_END_SILENCE_S else NativeConfig.LISTEN_OPENING_WAIT_S
    if (elapsedS != null && elapsedS < limit) return
    com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
    isAudioRecordRunning = false
    listenLimitS = limit
    listenStopReason = if (speechHeard) "END_SILENCE" else "OPENING_WAIT"
  }

  fun onListenUtterance(elapsedS: Double?, sttTimedOut: Boolean) {
    if (sttTimedOut) {
      com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
      isAudioRecordRunning = false
      listenLimitS = NativeConfig.STT_RESULT_TIMEOUT_S
      listenStopReason = "STT_TIMEOUT"
      return
    }
    speechHeard = true
    if (elapsedS != null && elapsedS < NativeConfig.LISTEN_MAX_UTTERANCE_S) return
    com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
    isAudioRecordRunning = false
    listenLimitS = NativeConfig.LISTEN_MAX_UTTERANCE_S
    listenStopReason = "MAX_UTTERANCE"
  }

  fun lastDistanceMeters(): Int = lastDistanceMeters

  private fun onLocationFix(distanceMeters: Int) {
    if (!isFgsRunning) return
    if (
      tripStateMachine.state == TripState.APPROACH_MUTED ||
      tripStateMachine.state == TripState.PARKED_CONFIRMED
    ) {
      return
    }
    lastDistanceMeters = distanceMeters
    hasDistanceFix = true
    val wasJitTriggered = tripStateMachine.jitTriggered
    tripStateMachine.onDistanceToDestination(distanceMeters)
    publishJitEdge(wasJitTriggered)
  }

  fun lockDestination(destinationName: String) {
    val wasJitTriggered = tripStateMachine.jitTriggered
    tripStateMachine.onDestinationSet(
      distanceToDestinationM = distanceForDestinationLock(),
      destinationName = destinationName,
    )
    publishJitEdge(wasJitTriggered)
  }

  private fun distanceForDestinationLock(): Int? = if (hasDistanceFix) lastDistanceMeters else null

  private fun publishJitEdge(wasJitTriggered: Boolean) {
    updateCapsuleVisual()
    if (!wasJitTriggered && tripStateMachine.jitTriggered && tripStateMachine.recommend) {
      launchRecommend(QueryTriggerMode.BACKGROUND_PREFETCH)
    }
  }

  private fun onUtterance(text: String, confidence: Double?) {
    val parsed = CommandCodes.parse(text, ParseContext(confidence = confidence))
    if (parsed.code == CommandCode.SET_DESTINATION && !parsed.destinationName.isNullOrBlank()) {
      lockDestination(parsed.destinationName)
    }
    if (parsed.code == CommandCode.FIND_PARKING) {
      launchRecommend(QueryTriggerMode.DRIVER_ACTIVE)
      updateCapsuleVisual()
    }
  }

  fun confirmParkedCandidate(sessionId: String) {
    launchApi { apiClient.syncDriverConfirmed(sessionId, 0L) }
  }

  private fun launchRecommend(mode: QueryTriggerMode) {
    launchedRecommendModes += mode
    spokenDriverCues.clear()
    launchApi {
      val outcome = apiClient.recommendAndRoute(buildRecommendRequest(), mode)
      if (outcome.succeeded) {
        val cards = outcome.response?.cards.orEmpty()
        if (cards.isNotEmpty()) {
          cachedCards = cards
          cachedSourceStatus = outcome.response?.sourceStatus ?: "ok"
          cachedSpokenCue = cards.first().spokenCue
        }
        if (mode == QueryTriggerMode.DRIVER_ACTIVE) {
          ttsService.speakFirstChoice(
            cards,
            cachedSourceStatus,
            tripStateMachine.tripVoiceMode,
            tripStateMachine.state == TripState.APPROACH_MUTED,
            driverAsked = true,
          )
        }
      }
      updateCapsuleVisual()
    }
  }

  private fun speakDriverFailureCues(cues: List<String>) {
    val mode = tripStateMachine.tripVoiceMode
    cues.forEach { cue ->
      if (spokenDriverCues.add(cue)) {
        ttsService.speak(cue, mode, isApproachMuted = false, driverAsked = true)
      }
    }
  }

  fun onTtsInitFailed(reason: String) {
    ttsEngineReady = false
    runCatching {
      android.util.Log.i("TtsService", "tts init failed reason=$reason")
    }
    val current = capabilityChecker.currentSnapshot(timedOut = false)
    capabilityChecker.applyExternalSnapshot(
      current.copy(
        tts = com.hkparking.copilot.voice.CapabilityStatus.UNKNOWN,
        mode = TripVoiceMode.MANUAL_ONLY,
        voiceInputEnabled = false,
        hotwordActive = false,
        fgsType = VoiceCapabilityChecker.FGS_LOCATION,
        ttsUsable = false,
      ),
    )
    tripStateMachine.syncVoiceCapability(capabilityChecker.currentSnapshot(timedOut = false), hasConfirmed = true)
  }

  private fun onAttemptFailed(attempt: Int, mode: QueryTriggerMode?, timedOut: Boolean) {
    if (mode != QueryTriggerMode.DRIVER_ACTIVE) return
    if (attempt == 1 && timedOut) {
      speakDriverFailureCues(listOf(ZhHkTemplates.NETWORK_SLOW))
    }
    if (attempt >= NativeConfig.API_MAX_ATTEMPTS) {
      speakDriverFailureCues(listOf(ZhHkTemplates.NETWORK_FAILED))
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
    capsuleSnapshot = snapshot
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
    val wm = runCatching { getSystemService(android.view.WindowManager::class.java) }.getOrNull() ?: return
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
      tripStateMachine.onRecordAudioRevoked(capabilityChecker.ttsStatus == CapabilityStatus.AVAILABLE)
      tripStateMachine.applyVoiceCapability(capabilityChecker)
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

  private fun finishTeardown(reason: String) {
    detachCapsuleWindow()
    isCapsuleVisible = false
    isFgsRunning = false
    isMicActive = false
    isAudioRecordRunning = false
    portsWired = false
    com.hkparking.copilot.di.Bindings.clearPorts()
    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
      .onFailure { e -> logInfo("stopForeground failed reason=${e::class.java.simpleName}") }
    runCatching { stopSelf() }
      .onFailure { e -> logInfo("stopSelf failed reason=${e::class.java.simpleName}") }
    logInfo("FGS stopped after teardown fade (${NativeConfig.TEARDOWN_FADE_MS}ms): reason=$reason")
  }

  private fun logInfo(message: String) {
    runCatching {
      android.util.Log.i("FloatingBubbleService", message)
    }
  }

  private fun logVoiceCapability(reason: String) {
    val snapshot = capabilityChecker.currentSnapshot(timedOut = false)
    runCatching {
      android.util.Log.i(
        "VoiceCapability",
        "mode=${snapshot.mode} stt=${snapshot.stt} tts=${snapshot.tts} ttsUsable=${snapshot.ttsUsable} reason=$reason",
      )
    }
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
    private val bootstrapTimeoutListeners = mutableListOf<() -> Unit>()

    fun addFgsStartedListener(listener: (FloatingBubbleService) -> Unit) {
      synchronized(fgsStartedListeners) { fgsStartedListeners += listener }
    }

    fun removeFgsStartedListener(listener: (FloatingBubbleService) -> Unit) {
      synchronized(fgsStartedListeners) { fgsStartedListeners -= listener }
    }

    internal fun addBootstrapTimeoutListener(listener: () -> Unit) {
      synchronized(bootstrapTimeoutListeners) { bootstrapTimeoutListeners += listener }
    }

    internal fun removeBootstrapTimeoutListener(listener: () -> Unit) {
      synchronized(bootstrapTimeoutListeners) { bootstrapTimeoutListeners -= listener }
    }

    internal fun fireBootstrapTimeout(): Int {
      val snapshot = synchronized(bootstrapTimeoutListeners) { bootstrapTimeoutListeners.toList() }
      snapshot.forEach { it() }
      return snapshot.size
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


