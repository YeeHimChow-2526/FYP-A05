package com.hkparking.copilot.net

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.TripState
import com.hkparking.copilot.voice.ZhHkTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {
  private val states = mutableListOf<TripState>()
  private val cues = mutableListOf<String>()
  private val delays = mutableListOf<Long>()
  private val requests = mutableListOf<ApiRequest>()
  private val queued = ArrayDeque<ApiResponse>()

  private val transport = object : ApiTransport {
    override fun execute(request: ApiRequest): ApiResponse {
      requests += request
      return queued.removeFirst()
    }
  }

  private val client = ApiClient(
    transport = transport,
    signals = object : ApiCapsuleSignals {
      override fun onState(state: TripState) {
        states += state
      }

      override fun speak(cue: String) {
        cues += cue
      }
    },
    delayMs = { delays += it },
    nowMs = { 0L },
  )

  @Test
  fun successParsesFreshnessContractAndLocaleHeader() {
    queued += ok(recommendJson(sourceStatus = "degraded", vacancies = -1, stale = true))
    val result = client.recommendAndRoute(sampleRequest(), isUserInitiated = false)
    assertEquals(TripState.NEARBY_READY, states.last())
    assertFalse(states.contains(TripState.ALERT_BREATHING))
    assertEquals(NativeConfig.SPEECH_LOCALE_DEFAULT, result!!.speechLocale)
    assertEquals("degraded", result.sourceStatus)
    assertEquals(90, result.dataAgeSeconds)
    assertEquals(-1, result.cards.single().vacancies)
    assertTrue(result.cards.single().isStale)
    assertEquals(ZhHkTemplates.METER_STALE_SPOKEN, result.cards.single().spokenCue)
    assertEquals(INTERNAL_API_KEY_PLACEHOLDER, requests.single().headers[INTERNAL_API_KEY_HEADER])
    assertEquals(JSON_CONTENT_TYPE, requests.single().headers["Content-Type"])
    assertTrue(requests.single().body.contains("\"speech_locale\":\"${NativeConfig.SPEECH_LOCALE_DEFAULT}\""))
    assertEquals(NativeConfig.API_TIMEOUT_MS, requests.single().timeoutMs)
  }

  @Test
  fun backgroundTimeoutRetriesSilentlyWithoutAlert() {
    repeat(NativeConfig.API_MAX_ATTEMPTS) { queued += timeout() }
    val result = client.recommendAndRoute(sampleRequest(), isUserInitiated = false)
    assertNull(result)
    assertEquals(TripState.AMBER_STEADY, states.first())
    assertEquals(TripState.GREY_BREATHING, states.last())
    assertFalse(states.contains(TripState.ALERT_BREATHING))
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, requests.size)
    assertEquals(List(NativeConfig.API_MAX_ATTEMPTS - 1) { NativeConfig.API_RETRY_INTERVAL_MS }, delays)
    assertTrue(cues.isEmpty())
  }

  @Test
  fun userInitiatedTimeoutSpeaksSlowThenFailedAndStaysGrey() {
    repeat(NativeConfig.API_MAX_ATTEMPTS) { queued += timeout() }
    val result = client.recommendAndRoute(sampleRequest(), isUserInitiated = true)
    assertNull(result)
    assertEquals(TripState.GREY_BREATHING, states.first())
    assertTrue(states.all { it == TripState.GREY_BREATHING })
    assertFalse(states.contains(TripState.ALERT_BREATHING))
    assertEquals(listOf(ZhHkTemplates.NETWORK_SLOW, ZhHkTemplates.NETWORK_FAILED), cues)
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, requests.size)
    assertEquals(List(NativeConfig.API_MAX_ATTEMPTS - 1) { NativeConfig.API_RETRY_INTERVAL_MS }, delays)
  }

  @Test
  fun rateLimitWithoutRetryAfterWaitsDefaultThenSucceedsOnce() {
    queued += rateLimited(retryAfter = null)
    queued += ok(recommendJson(sourceStatus = "ok", vacancies = 3, stale = false))
    val result = client.recommendAndRoute(sampleRequest(), isUserInitiated = true)
    assertEquals(2, requests.size)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_RETRY_DELAY_MS), delays)
    assertEquals(TripState.NEARBY_READY, states.last())
    assertEquals("ok", result!!.sourceStatus)
    assertTrue(cues.isEmpty())
  }

  @Test
  fun rateLimitCapsRetryAfterAndStopsAfterSingleFailedRetry() {
    queued += rateLimited(retryAfter = "30")
    queued += rateLimited(retryAfter = "1")
    val result = client.recommendAndRoute(sampleRequest(), isUserInitiated = true, isB2Resolution = false)
    assertNull(result)
    assertEquals(2, requests.size)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_MAX_WAIT_MS), delays)
    assertEquals(TripState.GREY_BREATHING, states.last())
    assertFalse(states.contains(TripState.ALERT_BREATHING))
    assertEquals(listOf(ZhHkTemplates.NETWORK_FAILED), cues)
  }

  @Test
  fun backgroundRateLimitFailureStaysSilent() {
    queued += rateLimited(retryAfter = "30")
    queued += timeout()
    val result = client.recommendAndRoute(sampleRequest(), isUserInitiated = false)
    assertNull(result)
    assertEquals(2, requests.size)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_MAX_WAIT_MS), delays)
    assertEquals(TripState.GREY_BREATHING, states.last())
    assertTrue(cues.isEmpty())
  }

  @Test
  fun commitLockFullReturnsServerCueAndTripConflictIsSilent() {
    queued += ApiResponse(
      statusCode = 409,
      body = """{"status":"LOCK_FULL","spoken_cue":"該區已滿"}""",
    )
    val commit = client.commitSession("sess", "CP1")
    assertFalse(commit.ok)
    assertTrue(commit.lockFull)
    assertEquals("該區已滿", commit.spokenCue)
    assertEquals(listOf("該區已滿"), cues)

    cues.clear()
    queued += ApiResponse(statusCode = 409, body = """{"status":"CONFLICT"}""")
    val trip = client.submitTripReport(
      sessionId = "sess",
      carparkId = "CP1",
      ratingTag = "GOOD",
      perkStatusTag = "VALID",
      perkNotes = "",
      tripTimestamp = 1L,
    )
    assertFalse(trip.accepted)
    assertTrue(trip.silentConflict)
    assertTrue(cues.isEmpty())
  }

  private fun sampleRequest(): RecommendRequest {
    return RecommendRequest(
      sessionId = "sess",
      originLat = 22.3,
      originLng = 114.1,
      destLat = 22.31,
      destLng = 114.17,
      destName = "朗豪坊",
      preference = "SMART_HYBRID",
    )
  }

  private fun timeout(): ApiResponse = ApiResponse(timedOut = true)

  private fun rateLimited(retryAfter: String?): ApiResponse {
    val headers = if (retryAfter == null) emptyMap() else mapOf("Retry-After" to retryAfter)
    return ApiResponse(statusCode = 429, headers = headers)
  }

  private fun ok(body: String): ApiResponse = ApiResponse(statusCode = 200, body = body)

  private fun recommendJson(sourceStatus: String, vacancies: Int, stale: Boolean): String {
    return """
      {
        "status":"SUCCESS",
        "speech_locale":"${NativeConfig.SPEECH_LOCALE_DEFAULT}",
        "speech_locale_fallback":false,
        "source_status":"$sourceStatus",
        "source_snapshot_ts":100,
        "processed_at":150,
        "server_now":190,
        "data_age_seconds":90,
        "await_voice_reply":false,
        "cards":[{
          "carpark_id":"M1",
          "name":"錶",
          "type":"METER",
          "vacancies":$vacancies,
          "distance_meters":40,
          "fee_summary":null,
          "spoken_cue":"${ZhHkTemplates.METER_STALE_SPOKEN}",
          "is_stale":$stale
        }]
      }
    """.trimIndent()
  }
}
