package com.hkparking.copilot.di

import com.hkparking.copilot.FgsBootstrapActivity
import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.PermissionCheckInput
import com.hkparking.copilot.StartupEntry
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult
import com.hkparking.copilot.net.RecommendResponse
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.CapabilitySnapshot
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.ParseResult
import com.hkparking.copilot.voice.PendingQuestion
import com.hkparking.copilot.voice.RecommendationCard
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker

// SIM: debug source set only
class SimVoiceInput : VoiceInput {
  private var listener: ((String, Double?) -> Unit)? = null
  private var controlListener: ((String, Int?, String?) -> Unit)? = null
  var isListening: Boolean = false
    private set
  var lastParseResult: ParseResult? = null
    private set

  override fun setUtteranceListener(listener: ((String, Double?) -> Unit)?) {
    this.listener = listener
  }

  fun setSimControlListener(listener: ((event: String, intValue: Int?, stringValue: String?) -> Unit)?) {
    controlListener = listener
  }

  override fun startListening(hotword: Boolean) {
    isListening = true
  }

  override fun stopListening() {
    isListening = false
  }

  fun injectUtterance(text: String, confidence: Double? = null) {
    lastParseResult = CommandCodes.parse(text, ParseContext(confidence = confidence))
    if (!isListening) return
    listener?.invoke(text, confidence)
  }

  fun emitSimControl(event: String, intValue: Int? = null, stringValue: String? = null) {
    controlListener?.invoke(event, intValue, stringValue)
  }

  fun recordParse(result: ParseResult) {
    lastParseResult = result
  }
}

// SIM: debug source set only
class SimLocationSource : LocationSource {
  var distanceMeters: Int = 0
    private set
  var speedKmh: Double = 0.0
    private set
  var isPaused: Boolean = false
    private set
  var fixAgeSeconds: Int = 0
    private set
  var lowSpeedHoldS: Int = 0
    private set
  var exitHoldS: Int = 0
    private set

  private var listener: ((Int, Double, Int) -> Unit)? = null
  private var motionListener: ((Int, Double, Int, Int, Int) -> Unit)? = null
  private var started = false
  private var hasSample = false

  override fun setLocationListener(listener: ((Int, Double, Int) -> Unit)?) {
    this.listener = listener
  }

  override fun setMotionListener(
    listener: ((distanceMeters: Int, speedKmh: Double, fixAgeSeconds: Int, lowSpeedHoldS: Int, exitHoldS: Int) -> Unit)?,
  ) {
    motionListener = listener
  }

  override fun start() {
    started = true
    if (hasSample) dispatch()
  }

  override fun stop() {
    started = false
  }

  fun injectDistanceMeters(meters: Int) {
    distanceMeters = meters
    hasSample = true
    if (!started || isPaused) return
    listener?.invoke(distanceMeters, speedKmh, fixAgeSeconds)
  }

  fun injectSpeedKmh(kmh: Double) {
    speedKmh = kmh
    dispatch()
  }

  fun injectFixAgeSeconds(seconds: Int) {
    fixAgeSeconds = seconds
    val stale = seconds > com.hkparking.copilot.NativeConfig.GPS_FIX_STALE_S
    if (!stale) return
    if (distanceMeters == 0 || distanceMeters > com.hkparking.copilot.NativeConfig.APPROACH_RADIUS_M) {
      distanceMeters = com.hkparking.copilot.NativeConfig.APPROACH_RADIUS_M
    }
    if (speedKmh >= com.hkparking.copilot.NativeConfig.DEAD_RECKONING_SPEED_KMH) {
      speedKmh = (com.hkparking.copilot.NativeConfig.DEAD_RECKONING_SPEED_KMH - 1).toDouble()
    }
    dispatch()
  }

  fun injectArriveCore() {
    distanceMeters = com.hkparking.copilot.NativeConfig.APPROACH_CORE_RADIUS_M
    speedKmh = (com.hkparking.copilot.NativeConfig.APPROACH_SPEED_THRESHOLD_KMH - 1).toDouble().coerceAtLeast(0.0)
    lowSpeedHoldS = com.hkparking.copilot.NativeConfig.APPROACH_CONSECUTIVE_S
    exitHoldS = 0
    dispatchMotion()
  }

  fun injectLeaveTarget(holdS: Int) {
    distanceMeters = com.hkparking.copilot.NativeConfig.APPROACH_EXIT_DISTANCE_M + 1
    exitHoldS = holdS
    dispatchMotion()
  }

  fun pause() {
    isPaused = true
  }

  fun resume() {
    isPaused = false
  }

  fun resetDistance() {
    distanceMeters = 0
    speedKmh = 0.0
    fixAgeSeconds = 0
    lowSpeedHoldS = 0
    exitHoldS = 0
    hasSample = false
  }

  fun reset() {
    resetDistance()
    isPaused = false
    started = false
    listener = null
    motionListener = null
  }

  private fun dispatch() {
    if (!started || isPaused) return
    listener?.invoke(distanceMeters, speedKmh, fixAgeSeconds)
    dispatchMotion()
  }

  private fun dispatchMotion() {
    if (!started || isPaused) return
    motionListener?.invoke(distanceMeters, speedKmh, fixAgeSeconds, lowSpeedHoldS, exitHoldS)
  }
}

// SIM: debug source set only
class FixtureApiTransport(
  fixtureLoader: (String) -> String? = { name -> loadMockFile(name) },
) : ApiTransport {
  private var fixtureLoader: (String) -> String? = fixtureLoader
  var fixtureName: String = DEFAULT_FIXTURE
    private set
  var delayMs: suspend (Long) -> Unit = {}
  var postCount: Int = 0
    private set
  var lastPath: String = ""
    private set
  private var networkFail = false
  private var remaining429Count: Int = 0
  private var recommendOverride: String? = null
  private var lockFullArmed: Boolean = false

  fun setFixtureName(name: String) {
    fixtureName = name
  }

  fun setFixtureLoader(loader: (String) -> String?) {
    val fallback = fixtureLoader
    fixtureLoader = { name -> loader(name) ?: fallback(name) }
  }

  fun setNetworkFail(failed: Boolean) {
    networkFail = failed
  }

  fun forceRateLimit(count: Int = 1) {
    remaining429Count = if (count > 0) count else 0
    if (remaining429Count > 0) fixtureName = RATE_LIMIT_FIXTURE
  }

  fun armRecommendFixture(name: String) {
    recommendOverride = name
    fixtureName = name
  }

  fun armLockFull() {
    lockFullArmed = true
    fixtureName = LOCK_FULL_FIXTURE
  }

  fun reset() {
    fixtureName = DEFAULT_FIXTURE
    networkFail = false
    remaining429Count = 0
    postCount = 0
    lastPath = ""
    recommendOverride = null
    lockFullArmed = false
    delayMs = {}
  }

  override suspend fun post(path: String, bodyJson: String): HttpResult {
    postCount += 1
    lastPath = path
    if (networkFail) {
      delayMs(com.hkparking.copilot.NativeConfig.API_TIMEOUT_MS)
      return HttpResult(statusCode = 0, timedOut = true, networkError = true)
    }
    if (remaining429Count > 0) {
      remaining429Count -= 1
      val raw = fixtureLoader(RATE_LIMIT_FIXTURE)
      val body = raw ?: RATE_LIMIT_BODY
      return HttpResult(statusCode = 429, bodyJson = body, retryAfterSeconds = parseRetryAfterSeconds(body))
    }
    if (lockFullArmed && path.contains("commit")) {
      lockFullArmed = false
      val raw = fixtureLoader(LOCK_FULL_FIXTURE).orEmpty()
      return HttpResult(statusCode = 409, bodyJson = raw, spokenCue = stringField(raw, "spoken_cue"))
    }
    val override = recommendOverride
    if (override != null && path.contains("recommend")) {
      recommendOverride = null
      fixtureName = DEFAULT_FIXTURE
      return responseFor(override)
    }
    val raw = fixtureLoader(fixtureName)
    return HttpResult(
      statusCode = 200,
      bodyJson = raw ?: LANGHAM_BODY,
      recommendResponse = langhamResponse(),
    )
  }

  private fun responseFor(name: String): HttpResult {
    val raw = fixtureLoader(name).orEmpty()
    val response = parseRecommend(raw)
    val cue = response.cards.firstOrNull()?.spokenCue ?: stringField(raw, "spoken_cue")
    return HttpResult(statusCode = 200, bodyJson = raw, recommendResponse = response, spokenCue = cue)
  }

  companion object {
    const val DEFAULT_FIXTURE = "recommend_success_langham.json"
    const val RATE_LIMIT_FIXTURE = "recommend_rate_limited_429.json"
    const val STALE_FIXTURE = "recommend_meter_stale.json"
    const val DEGRADED_FIXTURE = "recommend_source_degraded.json"
    const val LOW_WATERMARK_FIXTURE = "recommend_low_watermark.json"
    const val CROWD_FLAG_FIXTURE = "recommend_crowd_yellow_flag.json"
    const val LOCK_FULL_FIXTURE = "commit_lock_full_409.json"
    private const val RATE_LIMIT_BODY = "{\"status\":\"RATE_LIMITED\"}"
    private const val LANGHAM_BODY =
      "{\"status\":\"SUCCESS\",\"cards\":[{\"carpark_id\":\"langham\",\"name\":\"朗豪坊\",\"spoken_cue\":\"前方首選朗豪坊停車場\"}]}"
    private val RETRY_AFTER_PATTERN = Regex("\"retry_after_seconds\"\\s*:\\s*(\\d+)")

    fun parseRetryAfterSeconds(body: String): Long? {
      return RETRY_AFTER_PATTERN.find(body)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun loadMockFile(name: String): String? {
      val candidates = listOf(
        java.io.File("src/debug/assets/mock/$name"),
        java.io.File("app/src/debug/assets/mock/$name"),
        java.io.File("android/app/src/debug/assets/mock/$name"),
      )
      return candidates.firstOrNull { it.isFile }?.readText()
    }

    fun stringField(json: String, key: String): String? {
      return Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
    }

    fun intField(json: String, key: String): Int? {
      return Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun parseRecommend(body: String): RecommendResponse {
      val cards = Regex("""\{[^{}]*\}""").findAll(body).map { it.value }.filter {
        it.contains("\"type\"") || it.contains("\"carpark_id\"")
      }.map { card ->
        RecommendationCard(
          carparkId = stringField(card, "carpark_id") ?: "",
          name = stringField(card, "name") ?: "",
          type = stringField(card, "type") ?: "",
          vacancies = intField(card, "vacancies") ?: 0,
          distanceMeters = intField(card, "distance_meters") ?: 0,
          feeSummary = stringField(card, "fee_summary") ?: "",
          spokenCue = stringField(card, "spoken_cue") ?: stringField(body, "spoken_cue") ?: "",
          isStale = Regex(""""is_stale"\s*:\s*true""").containsMatchIn(card),
        )
      }.toList()
      return RecommendResponse(
        status = stringField(body, "status") ?: "SUCCESS",
        sourceStatus = stringField(body, "source_status") ?: "ok",
        cards = cards,
      )
    }

    fun langhamResponse(): RecommendResponse = RecommendResponse(
      status = "SUCCESS",
      cards = listOf(
        RecommendationCard(
          carparkId = "langham",
          name = "朗豪坊",
          type = "INDOOR",
          vacancies = 12,
          distanceMeters = 180,
          feeSummary = "首小時三十二蚊",
          spokenCue = "前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。",
          isStale = false,
        ),
      ),
    )
  }
}

class FixtureNetworkStatus : com.hkparking.copilot.di.NetworkStatusProvider {
  var online: Boolean = true
  override fun isOnline(): Boolean = online
}

object Bindings {
  val voiceInput: SimVoiceInput = SimVoiceInput()
  val locationSource: SimLocationSource = SimLocationSource()
  val apiTransport: FixtureApiTransport = FixtureApiTransport()
  val networkStatus: FixtureNetworkStatus = FixtureNetworkStatus()
  var activeReceiver: SimEventReceiver? = null
  private var activeService: com.hkparking.copilot.FloatingBubbleService? = null
  private var retainedHost: com.hkparking.copilot.FloatingBubbleService? = null
  private var receiverRegistered = false
  var capabilityCheckTimedOut: Boolean = false
    private set
  var todayTripsCleared: Boolean = false
    private set
  var pendingDriverConfirm: Boolean = false
    private set
  var driverConfirmSent: Boolean = false
    private set
  var onSiteRejectedCue: String? = null
    private set
  private var pendingConfirmSessionId: String? = null
  private var networkFailed: Boolean = false

  fun attachService(service: com.hkparking.copilot.FloatingBubbleService) {
    activeService = service
    retainedHost = service
    capabilityCheckTimedOut = false
    todayTripsCleared = false
    pendingDriverConfirm = false
    driverConfirmSent = false
    onSiteRejectedCue = null
    pendingConfirmSessionId = null
    networkFailed = false
    networkStatus.online = true
    voiceInput.setSimControlListener { event, intValue, stringValue ->
      dispatchSim(service, event, intValue, stringValue)
    }
    apiTransport.delayMs = service.delayMs
    apiTransport.setFixtureLoader { name ->
      runCatching {
        service.assets.open("mock/$name").bufferedReader().use { it.readText() }
      }.getOrNull()
    }
    if (activeReceiver == null) {
      activeReceiver = SimEventReceiver(
        onEventHandled = { (activeService ?: retainedHost)?.updateCapsuleVisual() },
      )
    }
    if (!receiverRegistered) {
      val appContext = runCatching { service.applicationContext }.getOrNull()
      if (appContext != null && activeReceiver != null) {
        val registered = runCatching {
          SimEventReceiver.register(appContext, activeReceiver!!)
        }.isSuccess
        if (registered) receiverRegistered = true
      }
    }
    service.updateCapsuleVisual()
  }

  fun detachService(service: com.hkparking.copilot.FloatingBubbleService) {
    if (activeService !== service) return
    activeService = null
    voiceInput.setSimControlListener(null)
  }

  fun lockOrStartTrip(destinationName: String): Boolean {
    val host = activeService ?: retainedHost ?: return false
    if (!host.isFgsRunning) {
      locationSource.resetDistance()
      val handshake = FgsBootstrapActivity(host) { 0L }
      val launched = handshake.launchFromEntry(
        StartupEntry.A_2,
        PermissionCheckInput(recordAudioGranted = false),
      )
      if (!launched) return false
      host.reportFgsStarted()
      if (!host.isFgsRunning || !handshake.fgsStartedReceived) return false
    }
    host.lockDestination(destinationName)
    return host.tripStateMachine.destinationLocked &&
      host.tripStateMachine.destinationName == destinationName
  }

  private fun dispatchSim(
    service: com.hkparking.copilot.FloatingBubbleService,
    event: String,
    intValue: Int?,
    stringValue: String?,
  ) {
    when (event) {
      "ENGINE_SHUTDOWN" -> {
        val within = when (intValue) {
          1 -> true
          0 -> false
          else -> service.lastDistanceMeters() <= NativeConfig.APPROACH_RADIUS_M
        }
        val sessionId = service.tripStateMachine.sessionId
        service.onEngineShutdown(within)
        pendingDriverConfirm = within && sessionId != null
        pendingConfirmSessionId = if (pendingDriverConfirm) sessionId else null
      }
      "DRIVER_CONFIRM" -> flushDriverConfirm(service)
      "NETWORK_FAIL" -> {
        networkFailed = true
        networkStatus.online = false
      }
      "NETWORK_OK" -> {
        networkFailed = false
        networkStatus.online = true
        flushDriverConfirm(service)
      }
      "CLOCK_HKT_MIDNIGHT" -> {
        todayTripsCleared = true
        pendingDriverConfirm = false
        pendingConfirmSessionId = null
      }
      "NO_MIC" -> {
        service.onVoiceCapabilityApplied(voiceSnapshot(TripVoiceMode.FULL_VOICE), micFgsActive = false)
        service.tripStateMachine.onPlaceClarificationNeeded()
        service.updateCapsuleVisual()
      }
      "MIC_FAIL" -> service.reportMicReady(succeeded = false)
      "SET_VOICE_MODE" -> {
        val mode = runCatching { TripVoiceMode.valueOf(stringValue ?: return) }.getOrNull() ?: return
        service.onVoiceCapabilityApplied(voiceSnapshot(mode), micFgsActive = mode == TripVoiceMode.FULL_VOICE)
      }
      "CAPABILITY_CHECK_TIMEOUT" -> {
        capabilityCheckTimedOut = true
        service.onVoiceCapabilityApplied(
          voiceSnapshot(service.tripStateMachine.tripVoiceMode, timedOut = true),
          micFgsActive = service.isMicActive,
        )
      }
      "REVOKE_RECORD_AUDIO" -> service.onRuntimePermissionRevoked(com.hkparking.copilot.FloatingBubbleService.RECORD_AUDIO)
      "REVOKE_REQUIRED" -> service.onRuntimePermissionRevoked("ACCESS_FINE_LOCATION")
      "INJECT_SILENCE" -> service.onListenSilence(stringValue?.toDoubleOrNull())
      "INJECT_LONG_UTTERANCE" -> service.onListenUtterance(
        elapsedS = if (stringValue == "stt") null else stringValue?.toDoubleOrNull(),
        sttTimedOut = stringValue == "stt",
      )
      "INJECT_CONFIDENCE" -> voiceInput.recordParse(
        CommandCodes.parse("詳細啲", ParseContext(confidence = confidenceOf(intValue, stringValue))),
      )
      "INJECT_TTS_ECHO" -> voiceInput.recordParse(
        CommandCodes.parse(
          "好",
          ParseContext(
            pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM,
            isTtsSpeaking = true,
            confidence = NativeConfig.STT_CONFIDENCE_THRESHOLD,
          ),
        ),
      )
      "FGS_START_TIMEOUT" -> if (!service.isFgsRunning) service.onBootstrapTimedOut()
      "BUBBLE_TAP" -> service.onActiveBubbleTap()
      "TRIE_MISS_RESOLVE_OK" -> {
        val name = stringValue?.takeIf { it.isNotBlank() } ?: service.tripStateMachine.destinationName ?: "朗豪坊"
        service.lockDestination(name)
      }
      "TRIE_MISS_NETWORK_FAIL" -> {
        service.tripStateMachine.onNetworkFailure()
        service.updateCapsuleVisual()
      }
      "TRIE_MISS_AMBIGUOUS" -> {
        service.tripStateMachine.onPlaceClarificationNeeded()
        service.updateCapsuleVisual()
      }
      "GPS_BAD_ACCURACY" -> {
        val accuracy = intValue?.toDouble()
          ?: stringValue?.toDoubleOrNull()
          ?: (NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M + 1).toDouble()
        if (accuracy <= NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M) return
        val outcome = service.tripStateMachine.requestStart(
          entry = StartupEntry.ON_SITE,
          gpsAvailable = true,
          gpsAccuracyM = accuracy,
        )
        if (!outcome.accepted) onSiteRejectedCue = outcome.spokenCue
      }
    }
  }

  private fun flushDriverConfirm(service: com.hkparking.copilot.FloatingBubbleService) {
    val sessionId = pendingConfirmSessionId
    if (!pendingDriverConfirm || networkFailed || todayTripsCleared || sessionId == null) return
    pendingDriverConfirm = false
    driverConfirmSent = true
    service.confirmParkedCandidate(sessionId)
  }

  private fun confidenceOf(intValue: Int?, stringValue: String?): Double? {
    return when {
      stringValue == "missing" || (intValue == null && stringValue.isNullOrBlank()) -> null
      intValue != null -> intValue.toDouble()
      else -> stringValue?.toDoubleOrNull()
    }
  }

  private fun voiceSnapshot(mode: TripVoiceMode, timedOut: Boolean = false): CapabilitySnapshot {
    val fullVoice = mode == TripVoiceMode.FULL_VOICE
    return CapabilitySnapshot(
      stt = if (fullVoice) CapabilityStatus.AVAILABLE else CapabilityStatus.UNSUPPORTED,
      tts = if (mode == TripVoiceMode.MANUAL_ONLY) CapabilityStatus.UNSUPPORTED else CapabilityStatus.AVAILABLE,
      mode = mode,
      voiceInputEnabled = fullVoice,
      hotwordActive = fullVoice,
      fgsType = if (fullVoice) VoiceCapabilityChecker.FGS_LOCATION_MIC else VoiceCapabilityChecker.FGS_LOCATION,
      downloadPrompt = false,
      blocksFgsStarted = false,
      timedOut = timedOut,
      ttsUsable = mode != TripVoiceMode.MANUAL_ONLY,
    )
  }
}
