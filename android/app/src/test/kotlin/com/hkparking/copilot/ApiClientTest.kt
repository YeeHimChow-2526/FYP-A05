package com.hkparking.copilot

import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.NetworkLightState
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.net.RecommendResponse
import com.hkparking.copilot.net.TransportResult
import com.hkparking.copilot.voice.RecommendationCard
import com.hkparking.copilot.voice.ZhHkTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {
  private val calls = mutableListOf<CapturedCall>()
  private val queued = ArrayDeque<TransportResult>()

  private val transport = object : ApiTransport {
    override fun post(
      endpoint: String,
      headers: Map<String, String>,
      payload: Map<String, Any?>,
      timeoutMs: Long,
    ): TransportResult {
      calls += CapturedCall(endpoint, headers, payload, timeoutMs)
      return queued.removeFirst()
    }
  }

  private val client = ApiClient(transport) { waits += it }
  private val waits = mutableListOf<Long>()

  @Test
  fun recommendSuccessAndServerFreshness() {
    queued += ok(meterResponse(sourceStatus = "degraded", vacancies = 6, isStale = false, spokenCue = "咪錶剩6個位"))
    val degraded = client.recommendAndRoute(sampleRequest())
    assertEquals(ApiClient.INTERNAL_API_KEY, calls.single().headers[ApiClient.HEADER_API_KEY])
    assertEquals(ApiClient.JSON_CONTENT_TYPE, calls.single().headers[ApiClient.HEADER_CONTENT_TYPE])
    assertEquals(NativeConfig.SPEECH_LOCALE_DEFAULT, calls.single().payload["speech_locale"])
    assertEquals(NativeConfig.API_TIMEOUT_MS, calls.single().timeoutMs)
    assertEquals(ApiClient.RECOMMEND, calls.single().endpoint)
    assertTrue(degraded.succeeded)
    assertEquals(NetworkLightState.NEARBY_READY, degraded.finalLightState)
    assertEquals(NetworkLightState.NEARBY_READY, client.currentLightState)
    assertFalse(degraded.stateTimeline.contains(NetworkLightState.ALERT_BREATHING))
    assertEquals(90, degraded.response!!.dataAgeSeconds)
    val degradedCards = degraded.response.sanitizedCards()
    assertEquals(-1, degradedCards.single { it.type == "METER" }.vacancies)
    assertEquals(ZhHkTemplates.METER_STALE_SPOKEN, degradedCards.single { it.type == "METER" }.spokenCue)
    assertEquals(4, degradedCards.single { it.type == "INDOOR" }.vacancies)

    calls.clear()
    queued += ok(meterResponse(sourceStatus = "ok", vacancies = 8, isStale = true, spokenCue = "咪錶剩8個位"))
    val stale = client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE)
    val staleMeter = stale.response!!.sanitizedCards().single { it.type == "METER" }
    assertEquals(-1, staleMeter.vacancies)
    assertEquals(ZhHkTemplates.METER_STALE_SPOKEN, staleMeter.spokenCue)
    assertEquals(NetworkLightState.NEARBY_READY, stale.finalLightState)
  }

  @Test
  fun backgroundPrefetchTimeoutSilentRetry() {
    repeat(NativeConfig.API_MAX_ATTEMPTS) { queued += TransportResult(timedOut = true, httpStatus = 0) }
    val outcome = client.recommendAndRoute(sampleRequest(), QueryTriggerMode.BACKGROUND_PREFETCH)
    assertFalse(outcome.succeeded)
    assertEquals(NetworkLightState.AMBER_STEADY, outcome.stateTimeline.first())
    assertEquals(NetworkLightState.GREY_BREATHING, outcome.stateTimeline.last())
    assertEquals(NetworkLightState.GREY_BREATHING, outcome.finalLightState)
    assertFalse(outcome.stateTimeline.contains(NetworkLightState.ALERT_BREATHING))
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, outcome.attempts)
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, calls.size)
    assertEquals(List(NativeConfig.API_MAX_ATTEMPTS - 1) { NativeConfig.API_RETRY_INTERVAL_MS }, outcome.waitIntervalsMs)
    assertEquals(outcome.waitIntervalsMs, waits)
    assertTrue(outcome.spokenCues.isEmpty())
  }

  @Test
  fun driverActiveTimeoutSpokenRetryAndB2Resolution() {
    repeat(NativeConfig.API_MAX_ATTEMPTS) { queued += TransportResult(timedOut = true, httpStatus = 0) }
    val driver = client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE)
    assertFalse(driver.succeeded)
    assertEquals(listOf(ZhHkTemplates.NETWORK_SLOW, ZhHkTemplates.NETWORK_FAILED), driver.spokenCues)
    assertEquals(NetworkLightState.GREY_BREATHING, driver.finalLightState)
    assertFalse(driver.stateTimeline.contains(NetworkLightState.ALERT_BREATHING))
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, driver.attempts)
    assertEquals(List(NativeConfig.API_MAX_ATTEMPTS - 1) { NativeConfig.API_RETRY_INTERVAL_MS }, driver.waitIntervalsMs)

    calls.clear()
    waits.clear()
    repeat(NativeConfig.API_MAX_ATTEMPTS) { queued += TransportResult(networkError = true, httpStatus = 0) }
    val b2 = client.recommendAndRoute(sampleRequest(), QueryTriggerMode.B2_RESOLUTION)
    assertFalse(b2.succeeded)
    assertEquals(NetworkLightState.AMBER_STEADY, b2.stateTimeline.first())
    assertEquals(NetworkLightState.GREY_BREATHING, b2.finalLightState)
    assertEquals(listOf(ZhHkTemplates.NETWORK_FAILED), b2.spokenCues)
    assertFalse(b2.stateTimeline.contains(NetworkLightState.ALERT_BREATHING))
    assertFalse(b2.stateTimeline.dropLast(1).contains(NetworkLightState.GREY_BREATHING))
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, b2.attempts)
  }

  @Test
  fun http429SeparateHandlingWithAndWithoutRetryAfter() {
    queued += TransportResult(httpStatus = 429, retryAfterSeconds = null)
    queued += ok(meterResponse(sourceStatus = "ok", vacancies = 3, isStale = false, spokenCue = "咪錶剩3個位"))
    val noHeader = client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE)
    assertEquals(2, noHeader.attempts)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_RETRY_DELAY_MS), noHeader.waitIntervalsMs)
    assertTrue(noHeader.succeeded)
    assertEquals(NetworkLightState.NEARBY_READY, noHeader.finalLightState)
    assertTrue(noHeader.spokenCues.isEmpty())
    assertEquals(3, noHeader.response!!.sanitizedCards().single { it.type == "METER" }.vacancies)

    calls.clear()
    waits.clear()
    queued += TransportResult(httpStatus = 429, retryAfterSeconds = 30L)
    queued += TransportResult(httpStatus = 429, retryAfterSeconds = 1L)
    val capped = client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE)
    assertEquals(2, capped.attempts)
    assertEquals(2, calls.size)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_MAX_WAIT_MS), capped.waitIntervalsMs)
    assertFalse(capped.waitIntervalsMs.contains(NativeConfig.API_RETRY_INTERVAL_MS))
    assertFalse(capped.succeeded)
    assertEquals(NetworkLightState.GREY_BREATHING, capped.finalLightState)
    assertFalse(capped.stateTimeline.contains(NetworkLightState.ALERT_BREATHING))
    assertEquals(listOf(ZhHkTemplates.NETWORK_FAILED), capped.spokenCues)

    calls.clear()
    waits.clear()
    queued += TransportResult(httpStatus = 429, retryAfterSeconds = 99L)
    queued += TransportResult(timedOut = true, httpStatus = 0)
    val background = client.recommendAndRoute(sampleRequest(), QueryTriggerMode.BACKGROUND_PREFETCH)
    assertEquals(2, background.attempts)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_MAX_WAIT_MS), background.waitIntervalsMs)
    assertEquals(NetworkLightState.GREY_BREATHING, background.finalLightState)
    assertTrue(background.spokenCues.isEmpty())

    calls.clear()
    waits.clear()
    queued += TransportResult(httpStatus = 429, retryAfterSeconds = 30L)
    queued += TransportResult(networkError = true, httpStatus = 0)
    val b2 = client.recommendAndRoute(sampleRequest(), QueryTriggerMode.B2_RESOLUTION)
    assertEquals(2, b2.attempts)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_MAX_WAIT_MS), b2.waitIntervalsMs)
    assertEquals(NetworkLightState.GREY_BREATHING, b2.finalLightState)
    assertEquals(listOf(ZhHkTemplates.NETWORK_FAILED), b2.spokenCues)
    assertFalse(b2.stateTimeline.contains(NetworkLightState.ALERT_BREATHING))
  }

  @Test
  fun commit409ReleaseOfflineAndTripReport409Silent() {
    queued += TransportResult(httpStatus = 409, spokenCue = "該區已滿")
    val commit = client.commitSession("sess", "CP1")
    assertFalse(commit.succeeded)
    assertEquals(409, commit.httpStatus)
    assertEquals("該區已滿", commit.actionSpokenCue)
    assertEquals(ApiClient.COMMIT, calls.single().endpoint)
    assertEquals("sess", calls.single().payload["session_id"])
    assertEquals("CP1", calls.single().payload["target_carpark_id"])
    assertTrue(commit.spokenCues.isEmpty())

    calls.clear()
    val release = client.releaseSession("sess", "CP1", isOnline = false)
    assertFalse(release.succeeded)
    assertEquals(0, release.attempts)
    assertTrue(calls.isEmpty())
    assertTrue(release.spokenCues.isEmpty())
    assertNull(release.httpStatus)

    queued += TransportResult(httpStatus = 409, spokenCue = "未確認")
    val trip = client.submitTripReport(
      sessionId = "sess",
      carparkId = "CP1",
      ratingTag = "GOOD",
      perkStatusTag = "VALID",
      tripTimestampSec = 1L,
    )
    assertFalse(trip.succeeded)
    assertEquals(409, trip.httpStatus)
    assertTrue(trip.spokenCues.isEmpty())
    assertNull(trip.actionSpokenCue)
    assertEquals(ApiClient.TRIP, calls.single().endpoint)
    assertEquals("GOOD", calls.single().payload["rating_tag"])
    assertFalse(calls.single().payload.containsKey("photo_url"))
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

  private fun ok(response: RecommendResponse): TransportResult {
    return TransportResult(httpStatus = 200, recommendResponse = response)
  }

  private fun meterResponse(
    sourceStatus: String,
    vacancies: Int,
    isStale: Boolean,
    spokenCue: String,
  ): RecommendResponse {
    return RecommendResponse(
      status = "SUCCESS",
      speechLocale = NativeConfig.SPEECH_LOCALE_DEFAULT,
      sourceStatus = sourceStatus,
      sourceSnapshotTs = "2026-10-18T14:20:00+08:00",
      processedAt = "2026-10-18T14:20:41+08:00",
      serverNow = "2026-10-18T14:21:30+08:00",
      dataAgeSeconds = 90,
      cards = listOf(
        RecommendationCard(
          carparkId = "M1",
          name = "錶",
          type = "METER",
          vacancies = vacancies,
          distanceMeters = 40,
          feeSummary = "",
          spokenCue = spokenCue,
          isStale = isStale,
        ),
        RecommendationCard(
          carparkId = "CP1",
          name = "室內",
          type = "INDOOR",
          vacancies = 4,
          distanceMeters = 80,
          feeSummary = "",
          spokenCue = "室內剩四個位",
          isStale = false,
        ),
      ),
    )
  }

  private data class CapturedCall(
    val endpoint: String,
    val headers: Map<String, String>,
    val payload: Map<String, Any?>,
    val timeoutMs: Long,
  )
}
