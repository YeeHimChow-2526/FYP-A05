package com.hkparking.copilot.di

import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult
import com.hkparking.copilot.net.RecommendResponse
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.ParseResult
import com.hkparking.copilot.voice.RecommendationCard

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

  override fun setSimControlListener(listener: ((event: String, intValue: Int?, stringValue: String?) -> Unit)?) {
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
    dispatch()
  }

  override fun stop() {
    started = false
  }

  fun injectDistanceMeters(meters: Int) {
    distanceMeters = meters
    dispatch()
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
    dispatch()
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

  fun reset() {
    distanceMeters = 0
    speedKmh = 0.0
    isPaused = false
    fixAgeSeconds = 0
    lowSpeedHoldS = 0
    exitHoldS = 0
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
  fixtureLoader: (String) -> String? = { null },
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
      val raw = fixtureLoader(LOCK_FULL_FIXTURE)
      return HttpResult(statusCode = 409, bodyJson = raw ?: LOCK_FULL_BODY, spokenCue = LOCK_FULL_CUE)
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
    val raw = fixtureLoader(name)
    val response = fallbackResponse(name)
    return HttpResult(
      statusCode = 200,
      bodyJson = raw ?: fallbackBody(name),
      recommendResponse = response,
      spokenCue = response.cards.firstOrNull()?.spokenCue,
    )
  }

  private fun fallbackBody(name: String): String {
    return when (name) {
      STALE_FIXTURE -> "{\"status\":\"SUCCESS\",\"cards\":[{\"type\":\"METER\",\"is_stale\":true,\"vacancies\":-1}]}"
      DEGRADED_FIXTURE -> "{\"status\":\"SUCCESS\",\"source_status\":\"degraded\"}"
      LOW_WATERMARK_FIXTURE -> "{\"status\":\"SUCCESS\",\"spoken_cue\":\"$LOW_WATERMARK_CUE\"}"
      CROWD_FLAG_FIXTURE -> "{\"status\":\"SUCCESS\",\"spoken_cue\":\"${com.hkparking.copilot.voice.ZhHkTemplates.CROWD_YELLOW_FLAG}\"}"
      else -> LANGHAM_BODY
    }
  }

  private fun fallbackResponse(name: String): RecommendResponse {
    return when (name) {
      STALE_FIXTURE -> RecommendResponse(
        status = "SUCCESS",
        cards = listOf(
          RecommendationCard(
            carparkId = "meter-1",
            name = "咪錶",
            type = "METER",
            vacancies = -1,
            distanceMeters = 0,
            feeSummary = "",
            spokenCue = com.hkparking.copilot.voice.ZhHkTemplates.METER_STALE_SPOKEN,
            isStale = true,
          ),
        ),
      )
      DEGRADED_FIXTURE -> RecommendResponse(
        status = "SUCCESS",
        sourceStatus = "degraded",
        cards = listOf(
          RecommendationCard(
            carparkId = "meter-1",
            name = "咪錶",
            type = "METER",
            vacancies = 4,
            distanceMeters = 0,
            feeSummary = "",
            spokenCue = com.hkparking.copilot.voice.ZhHkTemplates.METER_STALE_SPOKEN,
            isStale = false,
          ),
        ),
      )
      LOW_WATERMARK_FIXTURE -> langhamResponse().copy(
        cards = listOf(langhamResponse().cards.first().copy(spokenCue = LOW_WATERMARK_CUE, vacancies = 2)),
      )
      CROWD_FLAG_FIXTURE -> langhamResponse().copy(
        cards = listOf(
          langhamResponse().cards.first().copy(
            spokenCue = com.hkparking.copilot.voice.ZhHkTemplates.CROWD_YELLOW_FLAG,
          ),
        ),
      )
      else -> langhamResponse()
    }
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
    private const val LOCK_FULL_BODY = "{\"status\":\"LOCK_FULL\"}"
    private const val LOCK_FULL_CUE = "呢度滿咗"
    private const val LOW_WATERMARK_CUE = "APM 只剩兩個位，隨時會滿，後備可以去 XX 停車場。"
    private const val LANGHAM_BODY =
      "{\"status\":\"SUCCESS\",\"cards\":[{\"carpark_id\":\"langham\",\"name\":\"朗豪坊\",\"spoken_cue\":\"前方首選朗豪坊停車場\"}]}"
    private val RETRY_AFTER_PATTERN = Regex("\"retry_after_seconds\"\\s*:\\s*(\\d+)")

    fun parseRetryAfterSeconds(body: String): Long? {
      return RETRY_AFTER_PATTERN.find(body)?.groupValues?.get(1)?.toLongOrNull()
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

object Bindings {
  val voiceInput: SimVoiceInput = SimVoiceInput()
  val locationSource: SimLocationSource = SimLocationSource()
  val apiTransport: FixtureApiTransport = FixtureApiTransport()
  var activeReceiver: SimEventReceiver? = null

  fun attachService(service: com.hkparking.copilot.FloatingBubbleService) {
    if (activeReceiver != null) {
      detachService(service)
    }
    apiTransport.delayMs = service.delayMs
    apiTransport.setFixtureLoader { name ->
      runCatching {
        service.assets.open("mock/$name").bufferedReader().use { it.readText() }
      }.getOrNull()
    }
    val receiver = SimEventReceiver(
      onEventHandled = { service.updateCapsuleVisual() },
    )
    activeReceiver = receiver
    if (runCatching { service.applicationContext }.isSuccess) {
      runCatching { SimEventReceiver.register(service, receiver) }
    }
    service.updateCapsuleVisual()
  }

  fun detachService(service: com.hkparking.copilot.FloatingBubbleService) {
    val receiver = activeReceiver ?: return
    runCatching { service.unregisterReceiver(receiver) }
    activeReceiver = null
  }
}
