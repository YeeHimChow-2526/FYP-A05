package com.hkparking.copilot.sim

import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult
import com.hkparking.copilot.net.RecommendResponse
import com.hkparking.copilot.voice.RecommendationCard

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
