package com.hkparking.copilot

import com.hkparking.copilot.di.NetworkStatusProvider
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.voice.CapabilitySnapshot
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.CommandCode
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.PendingInvalidation
import com.hkparking.copilot.voice.PendingQuestion
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
    tripStateMachine.onStateChanged = { onTripStateChanged() }
    tripStateMachine.applyVoiceCapability(capabilityChecker)
  }

  val floatingCard: FloatingCardView = FloatingCardView(
    onConfirm = { confirmCurrentCandidate() },
    onNext = { handleFollowUp(CommandCode.NEXT_OPTION) },
    onDismiss = { dismissCardFromUser() },
  )

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
    shutdownInProgress = false
    greyRetryInFlight = false
    invalidatePendingQuestion(PendingInvalidation.DIALOGUE_INTERRUPTED)
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
    abandonCardOverlay()
    if (portsWired) { portsWired = false; com.hkparking.copilot.di.Bindings.clearPorts() }
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
  private var pendingQuestion: PendingQuestion = PendingQuestion.NONE
  private var listenEpoch: Int = 0
  internal val currentPendingQuestion: PendingQuestion
    get() = pendingQuestion
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
  var selectedCardIndex: Int = 0
    private set
  private var cardTransitionEpoch: Int = 0
  private var observedTripState: TripState = TripState.PRE_FGS
  private var pendingFollowUp: CommandCode? = null
  private var showManualCardAfterRecommend: Boolean = false
  private var cachedSourceStatus: String = "ok"
  private var cachedSpokenCue: String? = null
  private val fullCarparkIds = mutableSetOf<String>()
  private var commitInFlight = false
  private var confirmListenArmed = false
  var parkedCandidateSessionId: String? = null
    private set
  var parkedCandidateName: String? = null
    private set
  internal var shutdownInProgress: Boolean = false
  internal var greyRetryInFlight: Boolean = false
  internal var lastPendingInvalidation: PendingInvalidation? = null
  internal var shutdownResultDropped: Boolean = false
  private var lastSpokenAlertCount: Int = 0
  private var alertCountSessionId: String? = null

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
    shutdownInProgress = false
    greyRetryInFlight = false
    shutdownResultDropped = false
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
      val outcome = tripStateMachine.requestStart(
        entry = entry,
        distanceToDestinationM = initialDistanceMeters,
      )
      if (!outcome.accepted) {
        logInfo("Start rejected: entry=$entry cue=${outcome.spokenCue ?: ""} path=service_start")
      }
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
    abandonCardOverlay()
    fullCarparkIds.clear()
    selectedCardIndex = 0
    commitInFlight = false
    confirmListenArmed = false
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
    tripStateMachine.onAlertCueReady = { mutedBefore -> speakNewAlertCue(mutedBefore) }
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
    shutdownInProgress = true
    greyRetryInFlight = false
    abandonCardOverlay()
    invalidatePendingQuestion(PendingInvalidation.DIALOGUE_INTERRUPTED)
    com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
    ttsService.interrupt()
    fullCarparkIds.clear()
    commitInFlight = false
    confirmListenArmed = false
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
      tripStateMachine.state == TripState.PARKED_CONFIRMED ||
      tripStateMachine.state == TripState.NAV_TO_GATE
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
    if (shutdownInProgress) return
    if (!isFgsRunning) { runCatching { android.util.Log.i("FloatingBubbleService", "Utterance ignored: service not running") }; return }
    val cardBound = pendingQuestion == PendingQuestion.NONE && cardConfirmBound()
    val effectivePending = if (cardBound) PendingQuestion.RECOMMENDATION_CONFIRM else pendingQuestion
    val parsed = CommandCodes.parse(
      text,
      ParseContext(
        pendingQuestion = effectivePending,
        isTtsSpeaking = ttsService.isSpeaking,
        confidence = confidence,
      ),
    )
    if (parsed.code == CommandCode.CANCEL) {
      ttsService.interrupt()
      speechHeard = true
      com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
      invalidatePendingQuestion(PendingInvalidation.CANCEL)
      dismissCardFromUser()
      return
    }
    if (parsed.lowConfidence) {
      if (pendingQuestion != PendingQuestion.NONE) {
        invalidatePendingQuestion(PendingInvalidation.DIALOGUE_INTERRUPTED)
      }
      return
    }
    if (effectivePending == PendingQuestion.RECOMMENDATION_CONFIRM) {
      if (parsed.blockedAsSelfHearing) {
        runCatching {
          android.util.Log.i(
            "FloatingBubbleService",
            "Pending kept: self-hearing question=RECOMMENDATION_CONFIRM",
          )
        }
        return
      }
      if (parsed.code == CommandCode.CONFIRM_TARGET && parsed.confirmed) {
        speechHeard = true
        com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
        pendingQuestion = PendingQuestion.NONE
        listenEpoch += 1
        confirmListenArmed = false
        confirmCurrentCandidate()
        return
      }
      if (parsed.code == CommandCode.SHOW_DETAILS || parsed.code == CommandCode.NEXT_OPTION) {
        if (pendingQuestion == PendingQuestion.RECOMMENDATION_CONFIRM) {
          speechHeard = true
          com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
          invalidatePendingQuestion(PendingInvalidation.DIALOGUE_INTERRUPTED)
        }
        handleFollowUp(parsed.code)
        updateCapsuleVisual()
        return
      }
      if (!cardBound && pendingQuestion == PendingQuestion.RECOMMENDATION_CONFIRM) {
        speechHeard = true
        com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
        when {
          parsed.code == CommandCode.SET_DESTINATION && !parsed.destinationName.isNullOrBlank() -> {
            invalidatePendingQuestion(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED)
          }
          parsed.code == CommandCode.FIND_PARKING -> {
            invalidatePendingQuestion(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED)
          }
          else -> {
            invalidatePendingQuestion(PendingInvalidation.DIALOGUE_INTERRUPTED)
            return
          }
        }
      }
    }
    if (pendingQuestion == PendingQuestion.SCARCITY_EXPAND) {
      if (parsed.blockedAsSelfHearing) {
        runCatching {
          android.util.Log.i(
            "FloatingBubbleService",
            "Pending kept: self-hearing question=SCARCITY_EXPAND",
          )
        }
        return
      }
      speechHeard = true
      com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
      when {
        parsed.code == CommandCode.CONFIRM_TARGET && parsed.confirmed -> {
          pendingQuestion = PendingQuestion.NONE
          listenEpoch += 1
          launchRecommend(QueryTriggerMode.DRIVER_ACTIVE, expandScarcity = true)
          return
        }
        parsed.code == CommandCode.CANCEL -> {
          invalidatePendingQuestion(PendingInvalidation.CANCEL)
          return
        }
        parsed.code == CommandCode.SET_DESTINATION && !parsed.destinationName.isNullOrBlank() -> {
          invalidatePendingQuestion(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED)
        }
        parsed.code == CommandCode.FIND_PARKING -> {
          invalidatePendingQuestion(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED)
        }
        else -> invalidatePendingQuestion(PendingInvalidation.DIALOGUE_INTERRUPTED)
      }
    }
    if (parsed.code == CommandCode.SET_DESTINATION && !parsed.destinationName.isNullOrBlank()) {
      lockDestination(parsed.destinationName)
    }
    if (parsed.code == CommandCode.FIND_PARKING) {
      launchRecommend(QueryTriggerMode.DRIVER_ACTIVE)
      updateCapsuleVisual()
    }
    if (parsed.code == CommandCode.SHOW_DETAILS || parsed.code == CommandCode.NEXT_OPTION) {
      handleFollowUp(parsed.code)
      updateCapsuleVisual()
    }
  }

  fun confirmParkedCandidate(sessionId: String) {
    launchApi { apiClient.syncDriverConfirmed(sessionId, 0L) }
  }

  private fun onTripStateChanged() {
    val next = tripStateMachine.state
    val previous = observedTripState
    observedTripState = next
    if (
      next == TripState.APPROACH_MUTED &&
      previous != TripState.EXPANDED &&
      previous != TripState.APPROACH_MUTED
    ) {
      abandonCardOverlay()
    }
    updateCapsuleVisual()
  }

  private fun cardConfirmBound(): Boolean {
    return tripStateMachine.state == TripState.EXPANDED || floatingCard.uiState.visible
  }

  private fun followUpAllowed(): Boolean {
    if (!isFgsRunning) return false
    return tripStateMachine.state != TripState.NAV_TO_GATE &&
      tripStateMachine.state != TripState.ENDED &&
      tripStateMachine.state != TripState.PARKED_CONFIRMED
  }

  private fun handleFollowUp(code: CommandCode) {
    if (!followUpAllowed()) return
    if (pendingQuestion == PendingQuestion.RECOMMENDATION_CONFIRM) {
      invalidatePendingQuestion(PendingInvalidation.DIALOGUE_INTERRUPTED)
    }
    if (cachedCards.isEmpty()) {
      pendingFollowUp = code
      launchRecommend(QueryTriggerMode.DRIVER_ACTIVE)
      return
    }
    executeStoredFollowUp(code)
  }

  private fun executeStoredFollowUp(code: CommandCode) {
    if (code == CommandCode.SHOW_DETAILS) showDetailsNow() else handleNext()
  }

  private fun showDetailsNow() {
    val card = cachedCards.getOrNull(selectedCardIndex) ?: return
    tripStateMachine.enterExpanded()
    presentCard()
    armConfirmAfterSpeech(detailsText(card))
  }

  private fun handleNext() {
    if (fullCarparkIds.isNotEmpty()) {
      launchRecommend(QueryTriggerMode.DRIVER_ACTIVE, filterFull = true)
      return
    }
    val next = selectedCardIndex + 1
    if (next >= cachedCards.size) {
      speakNoMore(
        reopenConfirm = floatingCard.uiState.visible &&
          tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE,
      )
      return
    }
    val epoch = cardTransitionEpoch
    val pageDelay = NativeConfig.CARD_PAGE_TRANSITION_MS.toLong()
    launchApi {
      if (!transitionLive(epoch)) return@launchApi
      delayMs(pageDelay)
      if (!transitionLive(epoch)) return@launchApi
      val card = cachedCards.getOrNull(next) ?: return@launchApi
      selectedCardIndex = next
      tripStateMachine.enterExpanded()
      presentCard()
      armConfirmAfterSpeech(spokenCueFor(card))
    }
  }

  private fun speakNoMore(reopenConfirm: Boolean) {
    speakIfActive(
      ZhHkTemplates.NO_MORE_OPTIONS,
      tripStateMachine.tripVoiceMode,
      isApproachMuted = false,
      driverAsked = true,
      onFinished = { played ->
        if (!reopenConfirm || !played || !isMicActive || !floatingCard.uiState.visible) return@speakIfActive
        if (tripStateMachine.tripVoiceMode != TripVoiceMode.FULL_VOICE) return@speakIfActive
        pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM
        confirmListenArmed = false
        listenEpoch += 1
        openConfirmListenWindow()
      },
    )
  }

  private fun presentCard(forceManualButtons: Boolean? = null) {
    val card = cachedCards.getOrNull(selectedCardIndex) ?: return
    val manual = forceManualButtons ?: !(tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE && isMicActive)
    floatingCard.show(
      CardUiState(
        visible = true,
        carparkId = card.carparkId,
        title = card.name,
        subtitle = detailsText(card),
        showManualButtons = manual,
        manualActions = if (manual) FloatingCardView.MANUAL_ACTIONS else emptyList(),
        transitionMs = NativeConfig.CARD_PAGE_TRANSITION_MS,
        backgroundColor = FloatingCardView.CARD_BACKGROUND,
        blurRadius = FloatingCardView.CARD_BLUR_RADIUS,
      ),
      this,
    )
  }

  private fun presentManualCardFromTap(announce: Boolean) {
    if (!followUpAllowed()) return
    if (cachedCards.isNotEmpty()) {
      selectedCardIndex = 0
      tripStateMachine.enterExpanded()
      presentCard(forceManualButtons = true)
      if (announce) speakDriverFirstChoice(cachedCards)
      return
    }
    showManualCardAfterRecommend = true
    launchRecommend(QueryTriggerMode.DRIVER_ACTIVE)
  }

  private fun detailsText(card: RecommendationCard): String {
    if (card.isStale || (card.type == CARD_METER && cachedSourceStatus == "degraded")) {
      return ZhHkTemplates.METER_STALE_SPOKEN
    }
    return ZhHkTemplates.cardDetails(card)
  }

  private fun spokenCueFor(card: RecommendationCard): String {
    if (card.type == CARD_METER && (card.isStale || cachedSourceStatus == "degraded")) {
      return ZhHkTemplates.METER_STALE_SPOKEN
    }
    return card.spokenCue
  }

  private fun armConfirmAfterSpeech(text: String) {
    val fullVoice = tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE
    val askEpoch = if (fullVoice) {
      pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM
      confirmListenArmed = false
      listenEpoch += 1
      listenEpoch
    } else {
      null
    }
    val spoken = speakIfActive(
      text,
      tripStateMachine.tripVoiceMode,
      tripStateMachine.state == TripState.APPROACH_MUTED,
      driverAsked = true,
      onFinished = { played ->
        if (askEpoch == null) return@speakIfActive
        if (!played) {
          invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
          return@speakIfActive
        }
        if (listenEpoch != askEpoch || pendingQuestion != PendingQuestion.RECOMMENDATION_CONFIRM) return@speakIfActive
        if (!isMicActive) return@speakIfActive
        openConfirmListenWindow()
      },
    )
    if (spoken == null && fullVoice) invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
  }

  private fun transitionLive(epoch: Int): Boolean {
    return epoch == cardTransitionEpoch && isFgsRunning
  }

  private fun dismissCardFromUser() {
    val shouldRestore = floatingCard.uiState.visible || tripStateMachine.state == TripState.EXPANDED
    abandonCardOverlay()
    ttsService.interrupt()
    invalidatePendingQuestion(PendingInvalidation.CANCEL)
    if (shouldRestore) tripStateMachine.onCardDismissed(cachedCards.isNotEmpty())
  }

  private fun abandonCardOverlay() {
    cardTransitionEpoch += 1
    pendingFollowUp = null
    showManualCardAfterRecommend = false
    floatingCard.hide()
  }

  private fun dropShutdownResult(): Boolean {
    if (!shutdownInProgress) return false
    shutdownResultDropped = true
    logInfo("Result dropped: shutdown")
    return true
  }

  private fun speakIfActive(
    text: String,
    voiceMode: TripVoiceMode,
    isApproachMuted: Boolean,
    driverAsked: Boolean = false,
    onFinished: ((Boolean) -> Unit)? = null,
  ): String? {
    if (shutdownInProgress) {
      shutdownResultDropped = true
      logInfo("Result dropped: shutdown")
      return null
    }
    return ttsService.speak(text, voiceMode, isApproachMuted, driverAsked, onFinished)
  }

  private fun speakFirstChoiceIfActive(
    cards: List<RecommendationCard>,
    sourceStatus: String,
    voiceMode: TripVoiceMode,
    isApproachMuted: Boolean,
    driverAsked: Boolean = false,
    onFinished: ((Boolean) -> Unit)? = null,
  ): String? {
    if (shutdownInProgress) {
      shutdownResultDropped = true
      logInfo("Result dropped: shutdown")
      return null
    }
    return ttsService.speakFirstChoice(cards, sourceStatus, voiceMode, isApproachMuted, driverAsked, onFinished)
  }

  private fun launchRecommend(mode: QueryTriggerMode, expandScarcity: Boolean = false, filterFull: Boolean = false) {
    launchedRecommendModes += mode
    spokenDriverCues.clear()
    launchApi {
      try {
      val outcome = apiClient.recommendAndRoute(buildRecommendRequest(expandScarcity), mode)
      if (dropShutdownResult()) return@launchApi
      if (!outcome.succeeded) {
        pendingFollowUp = null
        showManualCardAfterRecommend = false
        updateCapsuleVisual()
        return@launchApi
      }
      val response = outcome.response
      val cards = response?.cards.orEmpty()
      if (filterFull) {
        val refresh = floatingCard.uiState.visible || tripStateMachine.state == TripState.EXPANDED
        pendingFollowUp = null
        showManualCardAfterRecommend = false
        val filtered = cards.filter { it.carparkId !in fullCarparkIds }
        if (filtered.isEmpty()) {
          if (mode == QueryTriggerMode.DRIVER_ACTIVE) {
            speakIfActive(
              ZhHkTemplates.NO_MORE_OPTIONS,
              tripStateMachine.tripVoiceMode,
              isApproachMuted = false,
              driverAsked = true,
            )
          }
        } else {
          cacheRecommendation(filtered, response?.sourceStatus ?: "ok")
          if (refresh) {
            selectedCardIndex = 0
            tripStateMachine.enterExpanded()
            presentCard()
          }
          if (mode == QueryTriggerMode.DRIVER_ACTIVE) speakDriverFirstChoice(filtered)
        }
        updateCapsuleVisual()
        return@launchApi
      }
      val awaitReply = response?.awaitVoiceReply == true
      if (expandScarcity) {
        pendingFollowUp = null
        showManualCardAfterRecommend = false
        if (cards.isNotEmpty() && !awaitReply) {
          cacheRecommendation(cards, response?.sourceStatus ?: "ok")
          if (mode == QueryTriggerMode.DRIVER_ACTIVE) speakDriverFirstChoice(cards)
        } else if (mode == QueryTriggerMode.DRIVER_ACTIVE) {
          speakIfActive(
            ZhHkTemplates.NO_MORE_OPTIONS,
            tripStateMachine.tripVoiceMode,
            isApproachMuted = false,
            driverAsked = true,
          )
          runCatching {
            android.util.Log.i("FloatingBubbleService", "Scarcity expand result: none")
          }
        }
      } else if (
        mode == QueryTriggerMode.DRIVER_ACTIVE &&
        awaitReply &&
        cards.isNotEmpty()
      ) {
        cacheRecommendation(cards, response?.sourceStatus ?: "ok")
        pendingFollowUp = null
        showManualCardAfterRecommend = false
        askScarcityExpand(cards)
      } else {
        if (cards.isNotEmpty()) {
          cacheRecommendation(cards, response?.sourceStatus ?: "ok")
        }
        val follow = pendingFollowUp
        pendingFollowUp = null
        val showManual = showManualCardAfterRecommend
        showManualCardAfterRecommend = false
        if (mode == QueryTriggerMode.DRIVER_ACTIVE && follow == null) speakDriverFirstChoice(cards)
        if (showManual && cachedCards.isNotEmpty() && tripStateMachine.state != TripState.GREY_BREATHING) {
          selectedCardIndex = 0
          tripStateMachine.enterExpanded()
          presentCard(forceManualButtons = true)
        }
        if (follow != null && cachedCards.isNotEmpty()) executeStoredFollowUp(follow)
      }
      updateCapsuleVisual()
      } finally {
        greyRetryInFlight = false
      }
    }
  }

  internal fun confirmCurrentCandidate() {
    if (commitInFlight || tripStateMachine.state == TripState.NAV_TO_GATE) return
    val sessionId = tripStateMachine.sessionId ?: return
    val card = cachedCards.getOrNull(selectedCardIndex) ?: return
    commitInFlight = true
    launchApi {
      try {
        val outcome = apiClient.commitSession(sessionId, card.carparkId)
        if (dropShutdownResult()) return@launchApi
        if (outcome.succeeded) {
          abandonCardOverlay()
          tripStateMachine.onCommitSucceeded()
          com.hkparking.copilot.di.Bindings.navigation.launchGateNavigation(card.name.ifBlank { card.carparkId })
          updateCapsuleVisual()
        } else if (outcome.httpStatus == 409) {
          fullCarparkIds += card.carparkId
          val cue = outcome.actionSpokenCue
          if (!cue.isNullOrBlank()) {
            speakIfActive(cue, tripStateMachine.tripVoiceMode, isApproachMuted = false, driverAsked = true)
          }
        } else {
          speakIfActive(
            ZhHkTemplates.NETWORK_FAILED,
            tripStateMachine.tripVoiceMode,
            isApproachMuted = false,
            driverAsked = true,
          )
        }
        updateCapsuleVisual()
      } finally {
        commitInFlight = false
      }
    }
  }

  private fun cacheRecommendation(cards: List<RecommendationCard>, sourceStatus: String) {
    cachedCards = cards
    selectedCardIndex = 0
    cachedSourceStatus = sourceStatus
    cachedSpokenCue = cards.first().spokenCue
  }

  private fun speakDriverFirstChoice(cards: List<RecommendationCard>) {
    if (shutdownInProgress) {
      dropShutdownResult()
      return
    }
    val fullVoice = tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE
    val askEpoch = if (fullVoice) {
      pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM
      confirmListenArmed = false
      listenEpoch += 1
      runCatching {
        android.util.Log.i(
          "FloatingBubbleService",
          "Pending set: question=RECOMMENDATION_CONFIRM mic=$isMicActive",
        )
      }
      listenEpoch
    } else {
      null
    }
    val spoken = speakFirstChoiceIfActive(
      cards,
      cachedSourceStatus,
      tripStateMachine.tripVoiceMode,
      tripStateMachine.state == TripState.APPROACH_MUTED,
      driverAsked = true,
      onFinished = { played ->
        if (askEpoch == null) return@speakFirstChoiceIfActive
        if (!played) {
          invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
          return@speakFirstChoiceIfActive
        }
        if (listenEpoch != askEpoch || pendingQuestion != PendingQuestion.RECOMMENDATION_CONFIRM) return@speakFirstChoiceIfActive
        if (!isMicActive) return@speakFirstChoiceIfActive
        openConfirmListenWindow()
      },
    )
    if (spoken == null && fullVoice) invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
  }

  private fun openConfirmListenWindow() {
    if (shutdownInProgress || confirmListenArmed || !isMicActive || pendingQuestion != PendingQuestion.RECOMMENDATION_CONFIRM) return
    confirmListenArmed = true
    speechHeard = false
    com.hkparking.copilot.di.Bindings.voiceInput.startListening(hotword = false)
    val epoch = listenEpoch
    runCatching {
      android.util.Log.i(
        "FloatingBubbleService",
        "Listen opened: question=RECOMMENDATION_CONFIRM waitS=${NativeConfig.LISTEN_OPENING_WAIT_S}",
      )
    }
    launchApi {
      delayMs((NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong())
      if (
        listenEpoch == epoch &&
        pendingQuestion == PendingQuestion.RECOMMENDATION_CONFIRM &&
        !speechHeard
      ) {
        onListenSilence(NativeConfig.LISTEN_OPENING_WAIT_S)
        invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
      }
    }
  }

  private fun askScarcityExpand(cards: List<RecommendationCard>) {
    when (tripStateMachine.tripVoiceMode) {
      TripVoiceMode.FULL_VOICE -> {
        val cue = ZhHkTemplates.withVoiceReplyPrompt(
          cards.first().spokenCue,
          TripVoiceMode.FULL_VOICE,
          isMicActive,
        )
        pendingQuestion = PendingQuestion.SCARCITY_EXPAND
        listenEpoch += 1
        val askEpoch = listenEpoch
        runCatching {
          android.util.Log.i(
            "FloatingBubbleService",
            "Pending set: question=SCARCITY_EXPAND mic=$isMicActive",
          )
        }
        val spoken = speakIfActive(
          cue,
          tripStateMachine.tripVoiceMode,
          isApproachMuted = false,
          driverAsked = true,
          onFinished = { played ->
            if (listenEpoch != askEpoch || pendingQuestion != PendingQuestion.SCARCITY_EXPAND) return@speakIfActive
            if (!played) {
              invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
            } else if (isMicActive) {
              startScarcityOpeningWaitTimer()
            }
          },
        )
        if (spoken == null) {
          invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
        }
      }
      TripVoiceMode.ANNOUNCE_MANUAL -> speakDriverFirstChoice(cards)
      TripVoiceMode.MANUAL_ONLY -> Unit
    }
  }

  private fun startScarcityOpeningWaitTimer() {
    if (shutdownInProgress) return
    com.hkparking.copilot.di.Bindings.voiceInput.startListening()
    speechHeard = false
    listenEpoch += 1
    val epoch = listenEpoch
    runCatching {
      android.util.Log.i(
        "FloatingBubbleService",
        "Listen opened: question=SCARCITY_EXPAND waitS=${NativeConfig.LISTEN_OPENING_WAIT_S}",
      )
    }
    launchApi {
      delayMs((NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong())
      if (
        listenEpoch == epoch &&
        pendingQuestion == PendingQuestion.SCARCITY_EXPAND &&
        !speechHeard
      ) {
        onListenSilence(NativeConfig.LISTEN_OPENING_WAIT_S)
        invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
      }
    }
  }

  private fun invalidatePendingQuestion(reason: PendingInvalidation) {
    if (pendingQuestion == PendingQuestion.NONE) return
    val question = pendingQuestion
    listenEpoch += 1
    confirmListenArmed = false
    pendingQuestion = CommandCodes.invalidatePending(reason)
    lastPendingInvalidation = reason
    runCatching {
      android.util.Log.i(
        "FloatingBubbleService",
        "Pending invalidated: question=$question reason=$reason",
      )
    }
  }

  private fun speakDriverFailureCues(cues: List<String>) {
    val mode = tripStateMachine.tripVoiceMode
    cues.forEach { cue ->
      if (spokenDriverCues.add(cue)) {
        speakIfActive(cue, mode, isApproachMuted = false, driverAsked = true)
      }
    }
  }

  private fun speakNewAlertCue(mutedBefore: Boolean) {
    val sessionId = tripStateMachine.sessionId
    if (sessionId != alertCountSessionId) {
      alertCountSessionId = sessionId
      lastSpokenAlertCount = 0
    }
    val count = tripStateMachine.alertSpeakCount
    if (count <= lastSpokenAlertCount) return
    lastSpokenAlertCount = count
    val cue = tripStateMachine.lastSpokenCue
    if (cue.isNullOrBlank()) return
    logInfo("Alert cue speak: count=$count muted=$mutedBefore")
    speakIfActive(cue, tripStateMachine.tripVoiceMode, isApproachMuted = mutedBefore)
  }

  internal fun speakStartRejection(entry: StartupEntry, cue: String?) {
    if (cue.isNullOrBlank()) return
    logInfo("Start rejected: entry=$entry cue=$cue path=hot")
    speakIfActive(cue, tripStateMachine.tripVoiceMode, isApproachMuted = false, driverAsked = true)
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

  private fun buildRecommendRequest(expandScarcity: Boolean = false): RecommendRequest {
    return RecommendRequest(
      sessionId = tripStateMachine.sessionId ?: "",
      originLat = 0.0,
      originLng = 0.0,
      destLat = 0.0,
      destLng = 0.0,
      destName = tripStateMachine.destinationName ?: "",
      preference = "SMART_HYBRID",
      expandScarcity = expandScarcity,
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
    val grey = tripStateMachine.state == TripState.GREY_BREATHING
    if (grey) {
      manualRetryRequested = true
      if (!greyRetryInFlight && !shutdownInProgress) {
        greyRetryInFlight = true
        launchRecommend(QueryTriggerMode.DRIVER_ACTIVE)
      }
    }
    val fullVoice = capabilityChecker.tripVoiceMode == TripVoiceMode.FULL_VOICE
    val micWasActive = isMicActive
    if (fullVoice) {
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
    if (
      tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE &&
      pendingQuestion == PendingQuestion.SCARCITY_EXPAND &&
      !ttsService.isSpeaking
    ) {
      startScarcityOpeningWaitTimer()
    }
    if (
      tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE &&
      pendingQuestion == PendingQuestion.RECOMMENDATION_CONFIRM &&
      !ttsService.isSpeaking &&
      !confirmListenArmed
    ) {
      openConfirmListenWindow()
    }
    if (!grey && pendingQuestion != PendingQuestion.SCARCITY_EXPAND && (!fullVoice || !micWasActive)) {
      presentManualCardFromTap(announce = tripStateMachine.tripVoiceMode == TripVoiceMode.ANNOUNCE_MANUAL)
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
    abandonCardOverlay()
    fullCarparkIds.clear()
    commitInFlight = false
    confirmListenArmed = false
    invalidatePendingQuestion(PendingInvalidation.DIALOGUE_INTERRUPTED)
    ttsService.interrupt()
    detachCapsuleWindow()
    isCapsuleVisible = false
    isFgsRunning = false
    isMicActive = false
    isAudioRecordRunning = false
    portsWired = false
    tripStateMachine.onAlertCueReady = null
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
    private const val CARD_METER = "METER"
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


