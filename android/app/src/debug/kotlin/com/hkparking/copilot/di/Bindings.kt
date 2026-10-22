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
  private var listening = false
  var lastParseResult: ParseResult? = null
    private set

  override fun setUtteranceListener(listener: ((String, Double?) -> Unit)?) {
    this.listener = listener
  }

  override fun startListening(hotword: Boolean) {
    listening = true
  }

  override fun stopListening() {
    listening = false
  }

  fun injectUtterance(text: String, confidence: Double? = null) {
    lastParseResult = CommandCodes.parse(text, ParseContext(confidence = confidence))
    if (!listening) return
    listener?.invoke(text, confidence)
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

  private var listener: ((Int, Double, Int) -> Unit)? = null
  private var started = false
  private var fixAgeSeconds: Int = 0

  override fun setLocationListener(listener: ((Int, Double, Int) -> Unit)?) {
    this.listener = listener
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
    started = false
    listener = null
  }

  private fun dispatch() {
    if (!started || isPaused) return
    listener?.invoke(distanceMeters, speedKmh, fixAgeSeconds)
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
  private var networkFail = false
  private var remaining429Count: Int = 0

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

  fun reset() {
    fixtureName = DEFAULT_FIXTURE
    networkFail = false
    remaining429Count = 0
    postCount = 0
    delayMs = {}
  }

  override suspend fun post(path: String, bodyJson: String): HttpResult {
    postCount += 1
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
    val raw = fixtureLoader(fixtureName)
    return HttpResult(
      statusCode = 200,
      bodyJson = raw ?: LANGHAM_BODY,
      recommendResponse = langhamResponse(),
    )
  }

  companion object {
    const val DEFAULT_FIXTURE = "recommend_success_langham.json"
    const val RATE_LIMIT_FIXTURE = "recommend_rate_limited_429.json"
    private const val RATE_LIMIT_BODY = "{\"status\":\"RATE_LIMITED\"}"
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
