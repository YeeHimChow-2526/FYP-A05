package com.hkparking.copilot

import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.net.RecommendResponse
import com.hkparking.copilot.voice.RecommendationCard
import com.hkparking.copilot.voice.ZhHkTemplates
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {
  private val calls = mutableListOf<CapturedCall>()
  private val queued = ArrayDeque<HttpResult>()
  private val waits = mutableListOf<Long>()
  private val machine = startedMachine()
  private val transport = object : ApiTransport {
    override suspend fun post(path: String, bodyJson: String): HttpResult {
      calls += CapturedCall(path, bodyJson)
      return queued.removeFirst()
    }
  }
  private val client = ApiClient(transport, machine) { waits += it }

  @Test
  fun recommendSuccessAndServerFreshness() {
    queued += ok(meterResponse(sourceStatus = "degraded", vacancies = 6, isStale = false, spokenCue = "咪錶剩6個位"))
    val degraded = runSuspend { client.recommendAndRoute(sampleRequest()) }
    assertEquals(ApiClient.RECOMMEND, calls.single().path)
    assertTrue(calls.single().bodyJson.contains("\"speech_locale\":\"${NativeConfig.SPEECH_LOCALE_DEFAULT}\""))
    assertTrue(degraded.succeeded)
    assertEquals(TripState.NEARBY_READY, degraded.state)
    assertEquals(TripState.NEARBY_READY, machine.state)
    assertFalse(degraded.stateTimeline.contains(TripState.ALERT_BREATHING))
    assertEquals(90, degraded.response!!.dataAgeSeconds)
    val degradedCards = degraded.response.sanitizedCards()
    assertEquals(-1, degradedCards.single { it.type == "METER" }.vacancies)
    assertEquals(ZhHkTemplates.METER_STALE_SPOKEN, degradedCards.single { it.type == "METER" }.spokenCue)
    assertEquals(4, degradedCards.single { it.type == "INDOOR" }.vacancies)
  }

  @Test
  fun backgroundPrefetchTimeoutSilentRetry() {
    repeat(NativeConfig.API_MAX_ATTEMPTS) { queued += HttpResult(timedOut = true, statusCode = 0) }
    val outcome = runSuspend { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.BACKGROUND_PREFETCH) }
    assertFalse(outcome.succeeded)
    assertEquals(TripState.GREY_BREATHING, outcome.state)
    assertEquals(CapsuleLight.GREY_BREATHING, outcome.capsuleLight)
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertFalse(outcome.stateTimeline.contains(TripState.ALERT_BREATHING))
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, outcome.attempts)
    assertEquals(List(NativeConfig.API_MAX_ATTEMPTS - 1) { NativeConfig.API_RETRY_INTERVAL_MS }, outcome.waitIntervalsMs)
    assertTrue(outcome.spokenCues.isEmpty())
  }

  @Test
  fun driverActiveTimeoutSpokenRetryAndB2Resolution() {
    repeat(NativeConfig.API_MAX_ATTEMPTS) { queued += HttpResult(timedOut = true, statusCode = 0) }
    val driver = runSuspend { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE) }
    assertEquals(listOf(ZhHkTemplates.NETWORK_SLOW, ZhHkTemplates.NETWORK_FAILED), driver.spokenCues)
    assertEquals(TripState.GREY_BREATHING, driver.state)
    assertFalse(driver.stateTimeline.contains(TripState.ALERT_BREATHING))

    calls.clear()
    waits.clear()
    repeat(NativeConfig.API_MAX_ATTEMPTS) { queued += HttpResult(networkError = true, statusCode = 0) }
    val b2 = runSuspend { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.B2_RESOLUTION) }
    assertEquals(TripState.GREY_BREATHING, b2.state)
    assertEquals(listOf(ZhHkTemplates.NETWORK_FAILED), b2.spokenCues)
    assertFalse(b2.stateTimeline.contains(TripState.ALERT_BREATHING))
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, b2.attempts)
  }

  @Test
  fun http429SeparateHandlingWithAndWithoutRetryAfter() {
    queued += HttpResult(statusCode = 429, retryAfterSeconds = null)
    queued += ok(meterResponse(sourceStatus = "ok", vacancies = 3, isStale = false, spokenCue = "咪錶剩3個位"))
    val noHeader = runSuspend { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE) }
    assertEquals(2, noHeader.attempts)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_RETRY_DELAY_MS), noHeader.waitIntervalsMs)
    assertEquals(TripState.NEARBY_READY, noHeader.state)
    assertTrue(noHeader.spokenCues.isEmpty())

    calls.clear()
    waits.clear()
    queued += HttpResult(statusCode = 429, retryAfterSeconds = 30L)
    queued += HttpResult(statusCode = 429, retryAfterSeconds = 1L)
    val capped = runSuspend { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE) }
    assertEquals(2, capped.attempts)
    assertEquals(listOf(NativeConfig.RATE_LIMIT_MAX_WAIT_MS), capped.waitIntervalsMs)
    assertEquals(TripState.GREY_BREATHING, capped.state)
    assertEquals(CapsuleLight.GREY_BREATHING, machine.capsuleLight)
    assertNotEqualsAlert(capped.state)
    assertEquals(listOf(ZhHkTemplates.NETWORK_FAILED), capped.spokenCues)
  }

  @Test
  fun timeoutThen429ThenOkAndTimeoutThen429Then429() {
    queued += HttpResult(timedOut = true, statusCode = 0)
    queued += HttpResult(statusCode = 429)
    queued += ok(meterResponse(sourceStatus = "ok", vacancies = 2, isStale = false, spokenCue = "咪錶剩2個位"))
    val recovered = runSuspend { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE) }
    assertEquals(3, recovered.attempts)
    assertEquals(listOf(NativeConfig.API_RETRY_INTERVAL_MS, NativeConfig.RATE_LIMIT_RETRY_DELAY_MS), recovered.waitIntervalsMs)
    assertEquals(TripState.NEARBY_READY, recovered.state)
    assertEquals(TripState.NEARBY_READY, machine.state)
    assertEquals(listOf(ZhHkTemplates.NETWORK_SLOW), recovered.spokenCues)
    assertFalse(recovered.spokenCues.contains(ZhHkTemplates.NETWORK_FAILED))

    val before = calls.size
    queued += HttpResult(timedOut = true, statusCode = 0)
    queued += HttpResult(statusCode = 429, headers = mapOf("Retry-After" to "30"))
    queued += HttpResult(statusCode = 429)
    val failed = runSuspend { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE) }
    assertEquals(3, failed.attempts)
    assertEquals(3, calls.size - before)
    assertEquals(listOf(NativeConfig.API_RETRY_INTERVAL_MS, NativeConfig.RATE_LIMIT_MAX_WAIT_MS), failed.waitIntervalsMs)
    assertEquals(TripState.GREY_BREATHING, failed.state)
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, failed.capsuleLight)
    assertNotEqualsAlert(failed.state)
    assertEquals(1, failed.spokenCues.count { it == ZhHkTemplates.NETWORK_FAILED })
  }

  @Test
  fun productionDefaultDelayActuallyWaits() {
    val local = ArrayDeque<HttpResult>()
    local += HttpResult(timedOut = true, statusCode = 0)
    local += HttpResult(statusCode = 200, recommendResponse = RecommendResponse(status = "SUCCESS"))
    val blocking = object : ApiTransport {
      override suspend fun post(path: String, bodyJson: String): HttpResult = local.removeFirst()
    }
    val production = ApiClient(blocking)
    val started = System.currentTimeMillis()
    runSuspend { production.recommendAndRoute(sampleRequest()) }
    val elapsed = System.currentTimeMillis() - started
    assertTrue(elapsed >= 900L)
  }

  @Test
  fun commit409ReleaseOfflineAndTripReport409Silent() {
    queued += HttpResult(statusCode = 409, spokenCue = "該區已滿")
    val commit = runSuspend { client.commitSession("sess", "CP1") }
    assertFalse(commit.succeeded)
    assertEquals(409, commit.httpStatus)
    assertEquals("該區已滿", commit.actionSpokenCue)
    assertEquals(ApiClient.COMMIT, calls.single().path)
    assertTrue(commit.spokenCues.isEmpty())

    calls.clear()
    val release = runSuspend { client.releaseSession("sess", "CP1", isOnline = false) }
    assertFalse(release.succeeded)
    assertEquals(0, release.attempts)
    assertTrue(calls.isEmpty())
    assertNull(release.httpStatus)

    queued += HttpResult(statusCode = 409, spokenCue = "未確認")
    val trip = runSuspend {
      client.submitTripReport(sessionId = "sess", carparkId = "CP1", ratingTag = "GOOD", perkStatusTag = "VALID", tripTimestampSec = 1L)
    }
    assertFalse(trip.succeeded)
    assertTrue(trip.spokenCues.isEmpty())
    assertNull(trip.actionSpokenCue)
    assertEquals(ApiClient.TRIP, calls.single().path)
    assertFalse(calls.single().bodyJson.contains("photo_url"))
  }

  private fun assertNotEqualsAlert(state: TripState) {
    assertFalse(state == TripState.ALERT_BREATHING)
  }

  private fun sampleRequest(): RecommendRequest = RecommendRequest(
    sessionId = "sess", originLat = 22.3, originLng = 114.1, destLat = 22.31, destLng = 114.17, destName = "朗豪坊", preference = "SMART_HYBRID",
  )

  private fun ok(response: RecommendResponse): HttpResult = HttpResult(statusCode = 200, recommendResponse = response)

  private fun meterResponse(sourceStatus: String, vacancies: Int, isStale: Boolean, spokenCue: String): RecommendResponse = RecommendResponse(
    status = "SUCCESS",
    speechLocale = NativeConfig.SPEECH_LOCALE_DEFAULT,
    sourceStatus = sourceStatus,
    dataAgeSeconds = 90,
    cards = listOf(
      RecommendationCard("M1", "錶", "METER", vacancies, 40, "", spokenCue, isStale),
      RecommendationCard("CP1", "室內", "INDOOR", 4, 80, "", "室內剩四個位", false),
    ),
  )

  private fun startedMachine(): TripStateMachine {
    val started = TripStateMachine()
    started.requestStart(StartupEntry.A_2)
    started.onFgsStarted()
    return started
  }

  private data class CapturedCall(val path: String, val bodyJson: String)
}

private fun <T> runSuspend(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

