package com.hkparking.copilot

import com.hkparking.copilot.di.NetworkStatusProvider
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.poi.AssetPoiTrieProvider
import com.hkparking.copilot.poi.LocalPoiTrie
import com.hkparking.copilot.poi.Poi
import com.hkparking.copilot.poi.PoiTrieProvider
import com.hkparking.copilot.poi.TrieResult
import com.hkparking.copilot.share.B2Begin
import com.hkparking.copilot.share.B2MissResolver
import com.hkparking.copilot.share.B2MissResolvers
import com.hkparking.copilot.share.ShareIntake
import com.hkparking.copilot.share.ShareOutcome
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
import com.hkparking.copilot.trip.ApiClientSyncApi
import com.hkparking.copilot.trip.SharedPrefsPort
import com.hkparking.copilot.trip.TodayTripStore
import com.hkparking.copilot.trip.TodayTripSync
import kotlin.coroutines.suspendCoroutine

data class MicSessionEvent(val isActive: Boolean, val reason: String)

class FloatingBubbleService : android.app.Service {
  val capabilityChecker: VoiceCapabilityChecker
  val tripStateMachine: TripStateMachine
  val delayMs: suspend (Long) -> Unit
  val ttsService: TtsService
  private val networkStatus: NetworkStatusProvider
  private val apiClient: com.hkparking.copilot.net.ApiClient

  constructor() : this(defaultDelayMs()) {
    poiTrieProvider = AssetPoiTrieProvider {
      assets.open("hk_poi_master.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
  }

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
      com.hkparking.copilot.di.Bindings.activeApiTransport,
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
    val hasShare = intent?.hasExtra(EXTRA_SHARE_HAS_URL) == true
    if (entry == StartupEntry.B_2 && hasShare) {
      val intake = ShareIntake(
        title = intent?.getStringExtra(EXTRA_SHARE_TITLE),
        lat = if (intent?.hasExtra(EXTRA_SHARE_LAT) == true) intent.getDoubleExtra(EXTRA_SHARE_LAT, 0.0) else null,
        lng = if (intent?.hasExtra(EXTRA_SHARE_LNG) == true) intent.getDoubleExtra(EXTRA_SHARE_LNG, 0.0) else null,
        hasUrl = intent?.getBooleanExtra(EXTRA_SHARE_HAS_URL, false) ?: false,
      )
      if (isFgsRunning) {
        // D1 / RT-6: mid-trip share — re-enterForeground keeping activeFgsType; no restartTripFromEntry.
        if (!enterForeground()) {
          logInfo("SHARE_DROPPED reason=fgs_timeout")
          return START_NOT_STICKY
        }
        dispatchFgsStarted(this)
        onShareIntake(intake)
      } else {
        if (restartTripFromEntry(entry, hotwordEnabled)) {
          onShareIntake(intake)
        } else {
          logInfo("SHARE_DROPPED reason=fgs_timeout")
        }
      }
      return START_NOT_STICKY
    }
    restartTripFromEntry(entry, hotwordEnabled)
    return START_NOT_STICKY
  }

  internal fun restartTripFromEntry(entry: StartupEntry, hotwordEnabled: Boolean = true): Boolean {
    shutdownInProgress = false
    greyRetryInFlight = false
    committedCarparkId = null
    committedCarparkName = null
    latestDistanceM = null
    distanceTracker.reset()
    engineShutdownDetector.reset()
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
    com.hkparking.copilot.di.Bindings.locationSource.applySamplingTier(distanceTracker.tier)
    attachCapsuleWindow()
    updateCapsuleVisual()
    return true
  }

  override fun onDestroy() {
    unregisterEngineReceiver()
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
  var poiTrieProvider: PoiTrieProvider? = null
  private var clarifyCandidates: List<Poi> = emptyList()
  internal val currentPendingQuestion: PendingQuestion
    get() = pendingQuestion
  internal val currentClarifyCandidates: List<Poi>
    get() = clarifyCandidates
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
  internal var todayTripStore: TodayTripStore? = null
  internal var todayTripSync: TodayTripSync? = null
  internal var shutdownInProgress: Boolean = false
  /** RT-1：終點變更計數；launchApi 捕捉，MainLooperResume 落地時核對，防舊請求覆蓋。 */
  private var destinationEpoch: Int = 0
  internal var greyRetryInFlight: Boolean = false
  internal var lastPendingInvalidation: PendingInvalidation? = null
  internal var shutdownResultDropped: Boolean = false
  internal val distanceTracker: DistanceTracker = DistanceTracker(
    object : DistanceTracker.Listener {
      override fun onSample(sample: DistanceSample) {
        onTrackerSample(sample)
      }
      override fun onTierChanged(tier: SamplingTier) {
        onSamplingTierChanged(tier)
      }
    },
  )
  internal val engineShutdownDetector: EngineShutdownDetector = EngineShutdownDetector()
  private val engineShutdownReceiver: EngineShutdownReceiver = EngineShutdownReceiver(
    EngineShutdownReceiver.Sink { signal, disconnected -> onEngineSignal(signal, disconnected) },
  )
  internal var bluetoothConnectGranted: () -> Boolean = {
    runCatching {
      checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
  }
  var engineReceiverRegistered: Boolean = false
    private set
  var lastEngineSignalOutcome: String? = null
    private set
  private var committedCarparkId: String? = null
  private var committedCarparkName: String? = null
  private var latestDistanceM: Int? = null
  private var lastSpokenAlertCount: Int = 0
  private var alertCountSessionId: String? = null

  var b2MissResolver: B2MissResolver = com.hkparking.copilot.di.Bindings.b2MissResolver
  var lastShareOutcome: ShareOutcome? = null
    private set
  val pendingShareTitleForTest: String?
    get() = pendingShareTitle
  private var pendingShareTitle: String? = null
  private var b2NetworkCueSpoken: Boolean = false

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
      pendingShareTitle = null
      b2NetworkCueSpoken = false
      lastShareOutcome = null
      committedCarparkId = null
      committedCarparkName = null
      latestDistanceM = null
      distanceTracker.reset()
      engineShutdownDetector.reset()
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
    (poiTrieProvider as? AssetPoiTrieProvider)?.prewarm()
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
    registerEngineReceiver()
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
    com.hkparking.copilot.di.Bindings.locationSource.setFixListener { fix ->
      distanceTracker.onFix(fix)
    }
    com.hkparking.copilot.di.Bindings.locationSource.setTickListener { nowMs ->
      distanceTracker.onTick(nowMs)
    }
    com.hkparking.copilot.di.Bindings.locationSource.applySamplingTier(distanceTracker.tier)
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
    latestDistanceM = distanceMeters
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
    if (shutdownInProgress) return
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
    val targetCarparkId = committedCarparkId
      ?: cachedCards.firstOrNull()?.carparkId
      ?: tripStateMachine.destinationName.orEmpty()
    val targetName = committedCarparkName
      ?: cachedCards.firstOrNull()?.name
      ?: tripStateMachine.destinationName
    tripStateMachine.onEngineShutdown(withinTargetOrBasementLoss)
    tripEndedReason = if (withinTargetOrBasementLoss) "PARKED_CONFIRMED" else "ENGINE_SHUTDOWN"
    if (withinTargetOrBasementLoss && sessionId != null) {
      parkedCandidateSessionId = sessionId
      parkedCandidateName = targetName
      writeParkedCandidateToStore(
        sessionId = sessionId,
        carparkId = targetCarparkId,
        carparkName = targetName.orEmpty(),
        parkedAtEpochMs = System.currentTimeMillis(),
      )
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
    latestDistanceM = distanceMeters
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

  fun lockDestination(destinationName: String, poi: Poi? = null) {
    destinationEpoch += 1
    committedCarparkId = null
    committedCarparkName = null
    distanceTracker.setDestination(poi?.lat, poi?.lng)
    val wasJitTriggered = tripStateMachine.jitTriggered
    tripStateMachine.onDestinationSet(
      distanceToDestinationM = distanceTracker.distanceToDestinationM() ?: distanceForDestinationLock(),
      destinationName = destinationName,
      destLat = poi?.lat,
      destLng = poi?.lng,
      destinationPoiId = poi?.id,
    )
    publishJitEdge(wasJitTriggered)
  }

  fun lockDestinationAt(destinationName: String, lat: Double, lng: Double, poiId: String? = null) {
    destinationEpoch += 1
    committedCarparkId = null
    committedCarparkName = null
    distanceTracker.setDestination(lat, lng)
    val wasJitTriggered = tripStateMachine.jitTriggered
    tripStateMachine.onDestinationSet(
      distanceToDestinationM = distanceTracker.distanceToDestinationM() ?: distanceForDestinationLock(),
      destinationName = destinationName,
      destLat = lat,
      destLng = lng,
      destinationPoiId = poiId,
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
    if (pendingQuestion == PendingQuestion.CLARIFY_DESTINATION) {
      if (handleClarifyAnswer(text, parsed)) return
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
      resolveSpokenDestination(parsed.destinationName)
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
    val sync = ensureTodayTripSync()
    sync.confirm(sessionId)
  }

  private fun writeParkedCandidateToStore(
    sessionId: String,
    carparkId: String,
    carparkName: String,
    parkedAtEpochMs: Long,
  ) {
    ensureTodayTripStore().upsertCandidate(sessionId, carparkId, carparkName, parkedAtEpochMs)
  }

  private fun ensureTodayTripStore(): TodayTripStore {
    todayTripStore?.let { return it }
    val sp = runCatching {
      getSharedPreferences(NativeConfig.TODAY_TRIPS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }.getOrNull()
    val prefs = if (sp != null) {
      SharedPrefsPort(sp)
    } else {
      com.hkparking.copilot.trip.MemoryPrefsPort()
    }
    val store = TodayTripStore(prefs = prefs)
    todayTripStore = store
    return store
  }

  private fun ensureTodayTripSync(): TodayTripSync {
    todayTripSync?.let { return it }
    val store = ensureTodayTripStore()
    val sync = TodayTripSync(
      store = store,
      api = ApiClientSyncApi(apiClient),
      network = networkStatus,
      networkExecutor = java.util.concurrent.Executor { runnable ->
        Thread(runnable, "today-trip-sync").start()
      },
    )
    todayTripSync = sync
    return sync
  }

  private fun onTripStateChanged() {
    val next = tripStateMachine.state
    val previous = observedTripState
    observedTripState = next
    distanceTracker.setApproachMuted(next == TripState.APPROACH_MUTED)
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

  private suspend fun dropShutdownResult(): Boolean {
    if (shutdownInProgress) {
      shutdownResultDropped = true
      logInfo("Result dropped: shutdown")
      return true
    }
    // RT-1：主線落地時若 FGS 已停、session 已換、或終點已改 → 丟棄，嚴禁改狀態。
    val guard = kotlin.coroutines.coroutineContext[ApiLaunchGuard]
    val sessionChanged = guard != null && tripStateMachine.sessionId != guard.sessionId
    val destChanged = guard != null && destinationEpoch != guard.destEpoch
    if (!isFgsRunning || sessionChanged || destChanged) {
      logInfo("Result dropped: stale_session_or_dest")
      return true
    }
    return false
  }


  private fun resolveSpokenDestination(name: String) {
    // RT-2: clear prior B-2 pending residue before voice resolve.
    clearPendingShareResidue()
    val trie = runCatching { poiTrieProvider?.get() }.getOrNull()
    if (trie == null) {
      clearClarifyState()
      lockDestination(name)
      return
    }
    val startedNs = System.nanoTime()
    val result = trie.lookup(name)
    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
    if (elapsedMs > NativeConfig.TRIE_LOOKUP_BUDGET_MS) {
      runCatching {
        android.util.Log.i("FloatingBubbleService", "TRIE_SLOW name=$name ms=$elapsedMs")
      }
    }
    when (result) {
      is TrieResult.Hit -> {
        clearClarifyState()
        lockDestination(name, result.poi)
        speakDestinationLock(name, result.poi, fullKey = isFullKey(name, result.poi))
      }
      is TrieResult.Ambiguous -> {
        beginDestinationClarify(result.candidates)
      }
      TrieResult.Miss -> {
        clearClarifyState()
        lockDestination(name)
        runCatching {
          android.util.Log.i("FloatingBubbleService", "TRIE_MISS name=$name")
        }
        if (tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE) {
          speakIfActive(
            ZhHkTemplates.directLock(name),
            tripStateMachine.tripVoiceMode,
            isApproachMuted = tripStateMachine.state == TripState.APPROACH_MUTED,
          )
        }
      }
    }
  }

  private fun beginDestinationClarify(candidates: List<Poi>) {
    clarifyCandidates = candidates
    val mode = tripStateMachine.tripVoiceMode
    if (mode == TripVoiceMode.FULL_VOICE) {
      pendingQuestion = PendingQuestion.CLARIFY_DESTINATION
      listenEpoch += 1
      val spokenCountBefore = lastSpokenAlertCount
      tripStateMachine.onPlaceClarificationNeeded()
      // RT-1: distinguish mute/GREY clear vs duplicate PLACE_CLARIFICATION suppress (T15)
      when {
        tripStateMachine.state != TripState.ALERT_BREATHING -> {
          pendingQuestion = PendingQuestion.NONE
          clarifyCandidates = emptyList()
        }
        pendingQuestion == PendingQuestion.NONE -> {
          // already cleared by speakNewAlertCue mute path
        }
        lastSpokenAlertCount == spokenCountBefore -> {
          // suppressed: keep pending + new candidates; reopen timer
          if (isMicActive && !ttsService.isSpeaking) {
            startOpeningWaitTimer(PendingQuestion.CLARIFY_DESTINATION)
          }
        }
      }
    } else {
      tripStateMachine.onPlaceClarificationNeeded()
    }
  }

  private fun handleClarifyAnswer(text: String, parsed: com.hkparking.copilot.voice.ParseResult): Boolean {
    if (parsed.blockedAsSelfHearing) {
      // RT-4: ignore without speechHeard / mic / listenEpoch
      return true
    }
    when {
      parsed.code == CommandCode.FIND_PARKING -> {
        speechHeard = true
        com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
        invalidatePendingQuestion(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED)
        return false
      }
      parsed.code == CommandCode.SET_DESTINATION && !parsed.destinationName.isNullOrBlank() -> {
        speechHeard = true
        com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
        invalidatePendingQuestion(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED)
        return false
      }
      parsed.code == CommandCode.CONFIRM_TARGET -> {
        // 「好」 never locks (DP2 / RT-4)
        return true
      }
      parsed.code == CommandCode.SHOW_DETAILS || parsed.code == CommandCode.NEXT_OPTION -> {
        return true
      }
      else -> {
        val match = clarifyDistrictMatch(text)
        if (match == null) {
          // unmatched / UNKNOWN — RT-4 ignore
          return true
        }
        speechHeard = true
        com.hkparking.copilot.di.Bindings.voiceInput.stopListening()
        pendingQuestion = PendingQuestion.NONE
        listenEpoch += 1
        clarifyCandidates = emptyList()
        lockDestination(match.zh, match)
        val districtShort = if (match.type == 'D') null else match.district
        if (tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE) {
          speakIfActive(
            ZhHkTemplates.implicitLockEcho(districtShort, match.zh),
            tripStateMachine.tripVoiceMode,
            isApproachMuted = tripStateMachine.state == TripState.APPROACH_MUTED,
          )
        }
        return true
      }
    }
  }

  private fun clarifyDistrictMatch(answer: String): Poi? {
    var a = LocalPoiTrie.normalize(answer)
    if (a.length > 1 && a.endsWith("區")) {
      a = a.dropLast(1)
    }
    if (a.isNotEmpty()) {
      val byDistrict = clarifyCandidates.singleOrNull { it.district == a }
      if (byDistrict != null) return byDistrict
    }
    val trie = runCatching { poiTrieProvider?.get() }.getOrNull() ?: return null
    val hit = trie.lookup(answer) as? TrieResult.Hit ?: return null
    // RT-2: same uniqueness as step (a)
    return clarifyCandidates.singleOrNull { it.district == hit.poi.district }
  }

  private fun isFullKey(query: String, poi: Poi): Boolean {
    val nq = LocalPoiTrie.normalize(query)
    val keys = (listOf(poi.zh, poi.en) + poi.aliases).map { LocalPoiTrie.normalize(it) }.filter { it.isNotEmpty() }
    return nq in keys
  }

  private fun speakDestinationLock(spokenName: String, poi: Poi, fullKey: Boolean) {
    if (tripStateMachine.tripVoiceMode != TripVoiceMode.FULL_VOICE) return
    val cue = if (fullKey) {
      ZhHkTemplates.directLock(spokenName)
    } else {
      val districtShort = if (poi.type == 'D') null else poi.district
      ZhHkTemplates.implicitLockEcho(districtShort, poi.zh)
    }
    speakIfActive(
      cue,
      tripStateMachine.tripVoiceMode,
      isApproachMuted = tripStateMachine.state == TripState.APPROACH_MUTED,
    )
  }

  private fun clearClarifyState() {
    if (pendingQuestion == PendingQuestion.CLARIFY_DESTINATION) {
      pendingQuestion = PendingQuestion.NONE
      listenEpoch += 1
    }
    clarifyCandidates = emptyList()
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
          committedCarparkId = card.carparkId
          committedCarparkName = card.name
          abandonCardOverlay()
          tripStateMachine.onCommitSucceeded()
          com.hkparking.copilot.di.Bindings.activeNavigation.launchGateNavigation(card.name.ifBlank { card.carparkId })
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
      shutdownResultDropped = true
      logInfo("Result dropped: shutdown")
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
    startOpeningWaitTimer(PendingQuestion.SCARCITY_EXPAND)
  }

  private fun startOpeningWaitTimer(question: PendingQuestion) {
    if (shutdownInProgress) return
    com.hkparking.copilot.di.Bindings.voiceInput.startListening()
    speechHeard = false
    listenEpoch += 1
    val epoch = listenEpoch
    runCatching {
      android.util.Log.i(
        "FloatingBubbleService",
        "Listen opened: question=$question waitS=${NativeConfig.LISTEN_OPENING_WAIT_S}",
      )
    }
    launchApi {
      delayMs((NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong())
      if (
        listenEpoch == epoch &&
        pendingQuestion == question &&
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
    if (question == PendingQuestion.CLARIFY_DESTINATION) {
      clarifyCandidates = emptyList()
    }
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
    val clarifyPending = pendingQuestion == PendingQuestion.CLARIFY_DESTINATION
    val onFinished: ((Boolean) -> Unit)? = if (clarifyPending) {
      { played ->
        if (played && isMicActive) {
          startOpeningWaitTimer(PendingQuestion.CLARIFY_DESTINATION)
        } else if (!played) {
          invalidatePendingQuestion(PendingInvalidation.TIMEOUT)
        }
      }
    } else {
      null
    }
    val spoken = speakIfActive(
      cue,
      tripStateMachine.tripVoiceMode,
      isApproachMuted = mutedBefore,
      onFinished = onFinished,
    )
    // RT-1: muted / speakIfActive null → cannot ask; clear pending
    if (clarifyPending && spoken == null) {
      pendingQuestion = PendingQuestion.NONE
      clarifyCandidates = emptyList()
    }
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
      destLat = tripStateMachine.destLat ?: 0.0,
      destLng = tripStateMachine.destLng ?: 0.0,
      destName = tripStateMachine.destinationName ?: "",
      preference = "SMART_HYBRID",
      expandScarcity = expandScarcity,
    )
  }

  private fun launchApi(block: suspend () -> Unit) {
    // RT-1：捕捉發起時 session／終點 epoch；MainLooperResume 落地時經 dropShutdownResult 核對。
    val guard = ApiLaunchGuard(
      sessionId = tripStateMachine.sessionId,
      destEpoch = destinationEpoch,
    )
    val context = MainLooperResume + guard
    val start = {
      block.startCoroutine(Continuation(context) { })
    }
    // RT-1：真 Android（有 MainLooper）整段 ApiClient（含重試 sleep）放背景執行緒，完成後先經 MainLooperResume 返主線改狀態；
    // 純 JVM 單測（getMainLooper()==null）維持呼叫執行緒同步直行，現有 ~277 測試零改動。
    val main = runCatching { android.os.Looper.getMainLooper() }.getOrNull()
    if (main != null) {
      API_LAUNCH_EXECUTOR.execute(start)
    } else {
      start()
    }
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
        if (pendingShareTitle != null) {
          // D11: retry B-2 share resolve instead of recommend.
          retryShareResolve()
        } else {
          greyRetryInFlight = true
          launchRecommend(QueryTriggerMode.DRIVER_ACTIVE)
        }
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
      pendingQuestion == PendingQuestion.CLARIFY_DESTINATION &&
      !ttsService.isSpeaking
    ) {
      startOpeningWaitTimer(PendingQuestion.CLARIFY_DESTINATION)
    }
    if (
      tripStateMachine.tripVoiceMode == TripVoiceMode.FULL_VOICE &&
      pendingQuestion == PendingQuestion.RECOMMENDATION_CONFIRM &&
      !ttsService.isSpeaking &&
      !confirmListenArmed
    ) {
      openConfirmListenWindow()
    }
    if (
      !grey &&
      pendingQuestion != PendingQuestion.SCARCITY_EXPAND &&
      pendingQuestion != PendingQuestion.CLARIFY_DESTINATION &&
      (!fullVoice || !micWasActive)
    ) {
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

  internal fun onEngineSignal(signal: EngineShutdownDetector.Signal, disconnected: Boolean) {
    if (!isFgsRunning || shutdownInProgress) {
      lastEngineSignalOutcome = "IGNORED_NOT_RUNNING"
      return
    }
    if (disconnected) {
      val token = engineShutdownDetector.onDisconnected(signal) ?: return
      launchApi {
        delayMs(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
        confirmEngineOff(token)
      }
    } else {
      engineShutdownDetector.onReconnected(signal)
      if (!engineShutdownDetector.isPending) {
        lastEngineSignalOutcome = "CANCELLED"
      }
    }
  }

  internal fun shutdownVerdict(): Boolean {
    if (tripStateMachine.state == TripState.APPROACH_MUTED) return true
    val distance = distanceTracker.lastDistanceM ?: latestDistanceM ?: return false
    return distance <= NativeConfig.APPROACH_RADIUS_M
  }

  private fun confirmEngineOff(token: Long) {
    if (!isFgsRunning || shutdownInProgress) {
      lastEngineSignalOutcome = "IGNORED_NOT_RUNNING"
      return
    }
    if (!engineShutdownDetector.confirm(token)) return
    val speed = distanceTracker.recentSpeedKmh()
    if (speed != null && speed >= NativeConfig.ENGINE_OFF_MAX_SPEED_KMH) {
      lastEngineSignalOutcome = "IGNORED_MOVING"
      runCatching {
        android.util.Log.i("FloatingBubbleService", "ENGINE_OFF_IGNORED reason=moving")
      }
      engineShutdownDetector.reset()
      return
    }
    lastEngineSignalOutcome = "CONFIRMED"
    onEngineShutdown(shutdownVerdict())
  }

  private fun onTrackerSample(sample: DistanceSample) {
    if (!isFgsRunning) return
    latestDistanceM = sample.distanceM
    tripStateMachine.onApproachSample(
      sample.distanceM,
      sample.speedKmh,
      sample.lowSpeedHoldS,
      sample.fixAgeS,
      sample.exitHoldS,
    )
    updateCapsuleVisual()
    if (
      tripStateMachine.state == TripState.APPROACH_MUTED ||
      tripStateMachine.state == TripState.PARKED_CONFIRMED
    ) {
      return
    }
    if (sample.fresh) {
      onLocationFix(sample.distanceM)
    }
  }

  private fun onSamplingTierChanged(tier: SamplingTier) {
    com.hkparking.copilot.di.Bindings.locationSource.applySamplingTier(tier)
  }

  private fun registerEngineReceiver() {
    if (engineReceiverRegistered) return
    val btGranted = bluetoothConnectGranted()
    val actions = EngineShutdownReceiver.actionsFor(btGranted)
    runCatching {
      android.util.Log.i(
        "FloatingBubbleService",
        "ENGINE_RECEIVER bt=${if (btGranted) "on" else "off"}",
      )
    }
    val ctx = runCatching { applicationContext }.getOrNull() ?: this
    val result = runCatching {
      EngineShutdownReceiver.registrar.register(ctx, engineShutdownReceiver, actions)
    }
    if (result.isSuccess) {
      engineReceiverRegistered = true
    } else {
      runCatching {
        android.util.Log.w(
          "FloatingBubbleService",
          "ENGINE_RECEIVER register failed reason=${result.exceptionOrNull()?.javaClass?.simpleName}",
        )
      }
    }
  }

  private fun unregisterEngineReceiver() {
    if (!engineReceiverRegistered) return
    val ctx = runCatching { applicationContext }.getOrNull() ?: this
    runCatching { EngineShutdownReceiver.registrar.unregister(ctx, engineShutdownReceiver) }
    engineReceiverRegistered = false
  }

  private fun finishTeardown(reason: String) {
    unregisterEngineReceiver()
    engineShutdownDetector.reset()
    distanceTracker.reset()
    committedCarparkId = null
    committedCarparkName = null
    latestDistanceM = null
    abandonCardOverlay()
    fullCarparkIds.clear()
    commitInFlight = false
    confirmListenArmed = false
    clarifyCandidates = emptyList()
    pendingShareTitle = null
    b2NetworkCueSpoken = false
    lastShareOutcome = null
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


  fun onShareIntake(intake: ShareIntake) {
    if (!isFgsRunning) {
      logInfo("SHARE_DROPPED reason=not_running")
      return
    }
    // RT-2: clear prior B-2 residue before handling the new share.
    clearPendingShareResidue()
    b2NetworkCueSpoken = false

    val title = intake.title?.takeIf { it.isNotBlank() }
    val hasPin = intake.hasPin
    val validShare = title != null || hasPin || intake.hasUrl
    if (validShare && pendingQuestion != PendingQuestion.NONE) {
      // RT-3: destination/candidate changed — invalidate old voice question.
      invalidatePendingQuestion(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED)
    }

    if (hasPin) {
      clearClarifyState()
      val lat = intake.lat!!
      val lng = intake.lng!!
      val name = title ?: "$lat,$lng"
      var poiId: String? = null
      if (title != null) {
        val trie = runCatching { poiTrieProvider?.get() }.getOrNull()
        if (trie != null) {
          val startedNs = System.nanoTime()
          val result = trie.lookup(title)
          val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
          if (elapsedMs > NativeConfig.TRIE_LOOKUP_BUDGET_MS) {
            logInfo("TRIE_SLOW name=$title ms=$elapsedMs")
          }
          if (result is TrieResult.Hit) {
            poiId = result.poi.id
          }
        }
      }
      lockDestinationAt(name, lat, lng, poiId)
      lastShareOutcome = ShareOutcome.PIN
      updateCapsuleVisual()
      return
    }

    if (title == null) {
      // #6 pure URL / no title / unresolvable
      tripStateMachine.onPlaceClarificationNeeded()
      lastShareOutcome = ShareOutcome.NO_TITLE
      logInfo("SHARE_NO_TITLE hasUrl=${intake.hasUrl}")
      updateCapsuleVisual()
      return
    }

    val trie = runCatching { poiTrieProvider?.get() }.getOrNull()
    if (trie == null) {
      startShareMiss(title)
      return
    }
    val startedNs = System.nanoTime()
    val result = trie.lookup(title)
    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
    if (elapsedMs > NativeConfig.TRIE_LOOKUP_BUDGET_MS) {
      logInfo("TRIE_SLOW name=$title ms=$elapsedMs")
    }
    when (result) {
      is TrieResult.Hit -> {
        clearClarifyState()
        lockDestination(title, result.poi)
        lastShareOutcome = ShareOutcome.HIT
        speakDestinationLock(title, result.poi, fullKey = isFullKey(title, result.poi))
        updateCapsuleVisual()
      }
      is TrieResult.Ambiguous -> {
        lastShareOutcome = ShareOutcome.AMBIGUOUS
        beginDestinationClarify(result.candidates)
        updateCapsuleVisual()
      }
      TrieResult.Miss -> {
        startShareMiss(title)
      }
    }
  }

  private fun startShareMiss(title: String) {
    when (b2MissResolver.begin(title)) {
      B2Begin.NAME_LOCK_NOW -> {
        tripStateMachine.onBackgroundResolveStarted()
        clearClarifyState()
        lockDestination(title)
        logInfo("TRIE_MISS name=$title source=B_2")
        tripStateMachine.onBackgroundResolveEnded()
        pendingShareTitle = null
        lastShareOutcome = ShareOutcome.MISS_NAME_LOCKED
        updateCapsuleVisual()
      }
      B2Begin.AWAIT_EVENT -> {
        tripStateMachine.onBackgroundResolveStarted()
        pendingShareTitle = title
        lastShareOutcome = ShareOutcome.MISS_PENDING
        logInfo("B2_PENDING title=$title")
        updateCapsuleVisual()
      }
    }
  }

  private fun retryShareResolve() {
    val title = pendingShareTitle ?: return
    logInfo("B2_RETRY title=$title")
    when (b2MissResolver.begin(title)) {
      B2Begin.AWAIT_EVENT -> {
        // Keep GREY; wait for the next TRIE_MISS_* event.
        updateCapsuleVisual()
      }
      B2Begin.NAME_LOCK_NOW -> {
        clearClarifyState()
        lockDestination(title)
        logInfo("TRIE_MISS name=$title source=B_2")
        pendingShareTitle = null
        lastShareOutcome = ShareOutcome.MISS_NAME_LOCKED
        tripStateMachine.onBackgroundResolveEnded()
        if (tripStateMachine.networkDegraded) {
          tripStateMachine.onRequestSuccess()
        }
        updateCapsuleVisual()
      }
    }
  }

  fun onB2ResolveOk(name: String?) {
    val query = name?.takeIf { it.isNotBlank() }
      ?: pendingShareTitle
      ?: tripStateMachine.destinationName
      ?: "朗豪坊"
    val trie = runCatching { poiTrieProvider?.get() }.getOrNull()
    var locked = false
    if (trie != null) {
      val startedNs = System.nanoTime()
      val result = trie.lookup(query)
      val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
      if (elapsedMs > NativeConfig.TRIE_LOOKUP_BUDGET_MS) {
        logInfo("TRIE_SLOW name=$query ms=$elapsedMs")
      }
      when (result) {
        is TrieResult.Hit -> {
          clearClarifyState()
          lockDestination(query, result.poi)
          locked = true
        }
        is TrieResult.Ambiguous, TrieResult.Miss -> {
          // D9: Ambiguous under RESOLVE_OK treated as Miss name-lock.
          clearClarifyState()
          lockDestination(query)
          locked = true
        }
      }
    }
    if (!locked) {
      clearClarifyState()
      lockDestination(query)
    }
    pendingShareTitle = null
    // RT-4: end background resolve before onRequestSuccess so GREY recovers in one hop.
    tripStateMachine.onBackgroundResolveEnded()
    if (tripStateMachine.networkDegraded) {
      tripStateMachine.onRequestSuccess()
    }
    updateCapsuleVisual()
  }

  fun onB2NetworkFail() {
    val mutedBefore = tripStateMachine.state == TripState.APPROACH_MUTED
    tripStateMachine.onNetworkFailure()
    if (tripStateMachine.fgsRunning && !b2NetworkCueSpoken) {
      speakIfActive(
        ZhHkTemplates.NETWORK_FAILED,
        tripStateMachine.tripVoiceMode,
        isApproachMuted = mutedBefore,
        driverAsked = false,
      )
      b2NetworkCueSpoken = true
    }
    // Keep pendingShareTitle + backgroundResolvePending for D11 capsule-tap retry.
    updateCapsuleVisual()
  }

  fun onB2Ambiguous() {
    tripStateMachine.onPlaceClarificationNeeded()
    pendingShareTitle = null
    tripStateMachine.onBackgroundResolveEnded()
    updateCapsuleVisual()
  }

  private fun clearPendingShareResidue() {
    pendingShareTitle = null
    b2NetworkCueSpoken = false
    if (tripStateMachine.backgroundResolvePending) {
      tripStateMachine.onBackgroundResolveEnded()
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
    const val EXTRA_SHARE_TITLE = "shareTitle"
    const val EXTRA_SHARE_LAT = "shareLat"
    const val EXTRA_SHARE_LNG = "shareLng"
    const val EXTRA_SHARE_HAS_URL = "shareHasUrl"
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

/**
 * RT-1：launchApi 發起時捕捉嘅 session／終點 epoch，經 CoroutineContext 帶到 MainLooperResume 落地檢查。
 */
private data class ApiLaunchGuard(
  val sessionId: String?,
  val destEpoch: Int,
) : kotlin.coroutines.AbstractCoroutineContextElement(ApiLaunchGuard) {
  companion object Key : kotlin.coroutines.CoroutineContext.Key<ApiLaunchGuard>
}

/** RT-1：真機 launchApi 背景池（daemon）；JVM 測試唔用。 */
private val API_LAUNCH_EXECUTOR: java.util.concurrent.Executor =
  java.util.concurrent.Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "hkp-api-launch").apply { isDaemon = true }
  }

/**
 * Step 3.5／RT-1: resumes FBS coroutines on the main looper when they are resumed from another thread
 * (HttpApiTransport worker / hkp-api-launch). No looper (JVM tests) or already on main → resume inline.
 */
private object MainLooperResume :
  kotlin.coroutines.AbstractCoroutineContextElement(kotlin.coroutines.ContinuationInterceptor),
  kotlin.coroutines.ContinuationInterceptor {
  override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
    object : Continuation<T> {
      override val context = continuation.context

      override fun resumeWith(result: Result<T>) {
        val main = runCatching { android.os.Looper.getMainLooper() }.getOrNull()
        val current = runCatching { android.os.Looper.myLooper() }.getOrNull()
        if (main == null || current === main) {
          continuation.resumeWith(result)
        } else {
          android.os.Handler(main).post { continuation.resumeWith(result) }
        }
      }
    }
}
