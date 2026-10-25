package com.hkparking.copilot.sim

import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiResponses
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
  internal val recommendBodies = mutableListOf<String>()
  private var networkFail = false
  private var remaining429Count: Int = 0
  private var recommendOverride: String? = null
  private var lockFullArmed: Boolean = false
  private var scarcityArmed: Boolean = false
  private var scarcityExpandFixture: String? = null

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
    scarcityArmed = false
    scarcityExpandFixture = null
    fixtureName = name
  }

  fun armScarcity(expandFixture: String) {
    recommendOverride = null
    scarcityArmed = true
    scarcityExpandFixture = expandFixture
    fixtureName = SCARCITY_AWAIT_FIXTURE
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
    scarcityArmed = false
    scarcityExpandFixture = null
    recommendBodies.clear()
    delayMs = {}
  }

  override suspend fun post(path: String, bodyJson: String): HttpResult {
    postCount += 1
    lastPath = path
    if (path.contains("recommend")) recommendBodies += bodyJson
    if (networkFail) {
      delayMs(com.hkparking.copilot.NativeConfig.API_TIMEOUT_MS)
      return HttpResult(statusCode = 0, timedOut = true, networkError = true)
    }
    if (remaining429Count > 0) {
      remaining429Count -= 1
      val raw = fixtureLoader(RATE_LIMIT_FIXTURE)
      val body = raw ?: RATE_LIMIT_BODY
      return ApiResponses.buildResult(path, 429, body, emptyMap())
    }
    if (lockFullArmed && path.contains("commit")) {
      lockFullArmed = false
      val raw = fixtureLoader(LOCK_FULL_FIXTURE).orEmpty()
      return ApiResponses.buildResult(path, 409, raw, emptyMap())
    }
    val override = recommendOverride
    if (override != null && path.contains("recommend")) {
      recommendOverride = null
      fixtureName = DEFAULT_FIXTURE
      return responseFor(override)
    }
    if (scarcityArmed && path.contains("recommend")) {
      if (!bodyJson.contains("\"expand_scarcity\":true")) {
        return responseFor(SCARCITY_AWAIT_FIXTURE)
      }
      val expandFixture = scarcityExpandFixture ?: SCARCITY_EMPTY_FIXTURE
      scarcityArmed = false
      scarcityExpandFixture = null
      fixtureName = DEFAULT_FIXTURE
      return responseFor(expandFixture)
    }
    val raw = fixtureLoader(fixtureName)
    return HttpResult(
      statusCode = 200,
      bodyJson = raw ?: LANGHAM_BODY,
      recommendResponse = langhamResponse(),
    )
  }

  private fun responseFor(name: String): HttpResult {
    return ApiResponses.buildResult(ApiClient.RECOMMEND, 200, fixtureLoader(name).orEmpty(), emptyMap())
  }

  companion object {
    const val DEFAULT_FIXTURE = "recommend_success_langham.json"
    const val RATE_LIMIT_FIXTURE = "recommend_rate_limited_429.json"
    const val STALE_FIXTURE = "recommend_meter_stale.json"
    const val DEGRADED_FIXTURE = "recommend_source_degraded.json"
    const val LOW_WATERMARK_FIXTURE = "recommend_low_watermark.json"
    const val CROWD_FLAG_FIXTURE = "recommend_crowd_yellow_flag.json"
    const val SCARCITY_AWAIT_FIXTURE = "recommend_scarcity_await_reply.json"
    const val SCARCITY_EXPANDED_FIXTURE = "recommend_scarcity_expanded.json"
    const val SCARCITY_EMPTY_FIXTURE = "recommend_empty_expand.json"
    const val LOCK_FULL_FIXTURE = "commit_lock_full_409.json"
    const val TWO_INDOOR_FIXTURE = "recommend_success_two_indoor.json"
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
