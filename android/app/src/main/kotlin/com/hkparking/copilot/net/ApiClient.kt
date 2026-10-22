package com.hkparking.copilot.net

import com.hkparking.copilot.CapsuleLight
import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.StatusPip
import com.hkparking.copilot.TripState
import com.hkparking.copilot.TripStateMachine
import com.hkparking.copilot.voice.RecommendationCard
import com.hkparking.copilot.voice.ZhHkTemplates
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class QueryTriggerMode { BACKGROUND_PREFETCH, DRIVER_ACTIVE, B2_RESOLUTION }

data class RecommendRequest(
  val sessionId: String, val originLat: Double, val originLng: Double, val destLat: Double, val destLng: Double,
  val destName: String, val preference: String, val expandScarcity: Boolean = false,
  val speechLocale: String = NativeConfig.SPEECH_LOCALE_DEFAULT,
)

data class RecommendResponse(
  val status: String, val speechLocale: String = NativeConfig.SPEECH_LOCALE_DEFAULT, val speechLocaleFallback: Boolean = false,
  val sourceStatus: String = "ok", val sourceSnapshotTs: String = "", val processedAt: String = "", val serverNow: String = "",
  val dataAgeSeconds: Int = 0, val cards: List<RecommendationCard> = emptyList(), val awaitVoiceReply: Boolean = false,
) {
  fun sanitizedCards(): List<RecommendationCard> = cards.map { card ->
    if (card.type == METER && (card.isStale || sourceStatus == DEGRADED)) {
      card.copy(vacancies = UNKNOWN_VACANCIES, spokenCue = ZhHkTemplates.METER_STALE_SPOKEN)
    } else {
      card
    }
  }
}

data class HttpResult(
  val statusCode: Int = HTTP_OK,
  val bodyJson: String = "",
  val headers: Map<String, String> = emptyMap(),
  val retryAfterSeconds: Long? = null,
  val timedOut: Boolean = false,
  val networkError: Boolean = false,
  val recommendResponse: RecommendResponse? = null,
  val spokenCue: String? = null,
) {
  fun resolvedRetryAfterSeconds(): Long? {
    if (retryAfterSeconds != null) return retryAfterSeconds
    val raw = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value
    return raw?.trim()?.toLongOrNull()
  }
}

interface ApiTransport {
  suspend fun post(path: String, bodyJson: String): HttpResult
}

data class CallOutcome(
  val succeeded: Boolean,
  val httpStatus: Int?,
  val state: TripState,
  val capsuleLight: CapsuleLight,
  val stateTimeline: List<TripState>,
  val spokenCues: List<String>,
  val attempts: Int,
  val waitIntervalsMs: List<Long>,
  val response: RecommendResponse? = null,
  val actionSpokenCue: String? = null,
)

class ApiClient(
  private val transport: ApiTransport,
  private val stateMachine: TripStateMachine = TripStateMachine(),
  private val delayMs: suspend (Long) -> Unit = { ms ->
    if (ms > 0L) {
      suspendCoroutine { cont ->
        java.util.concurrent.CompletableFuture.delayedExecutor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
          .execute { cont.resume(Unit) }
      }
    }
  },
) {
  fun defaultHeaders(): Map<String, String> = mapOf(HEADER_API_KEY to INTERNAL_API_KEY, HEADER_CONTENT_TYPE to JSON_CONTENT_TYPE)

  suspend fun recommendAndRoute(request: RecommendRequest, mode: QueryTriggerMode = QueryTriggerMode.BACKGROUND_PREFETCH): CallOutcome = dispatch(RECOMMEND, mapOf(
    "session_id" to request.sessionId, "origin_lat" to request.originLat, "origin_lng" to request.originLng, "dest_lat" to request.destLat,
    "dest_lng" to request.destLng, "dest_name" to request.destName, "preference" to request.preference, "expand_scarcity" to request.expandScarcity, "speech_locale" to request.speechLocale,
  ), mode, graded = true, ready = true)

  suspend fun commitSession(sessionId: String, targetCarparkId: String): CallOutcome =
    dispatch(COMMIT, mapOf("session_id" to sessionId, "target_carpark_id" to targetCarparkId), null, true, false, conflictCue = true)

  suspend fun releaseSession(sessionId: String, targetCarparkId: String, isOnline: Boolean = true): CallOutcome {
    if (!isOnline) {
      runCatching {
        android.util.Log.i("ApiClient", "Release session skipped: sessionId=$sessionId")
      }
      return outcome(false, null, attempts = 0)
    }
    runCatching {
      android.util.Log.i("ApiClient", "Release session sent: sessionId=$sessionId")
    }
    return dispatch(RELEASE, mapOf("session_id" to sessionId, "target_carpark_id" to targetCarparkId, "reason" to PARKED_CONFIRMED), null, false, false)
  }

  suspend fun syncDriverConfirmed(sessionId: String, timestampSec: Long): CallOutcome =
    dispatch(SYNC, mapOf("session_id" to sessionId, "event_type" to DRIVER_CONFIRMED, "timestamp" to timestampSec), null, true, false)

  suspend fun submitCrowdReport(sessionId: String, carparkId: String, reportType: String, clientTimestampSec: Long): CallOutcome =
    dispatch(CROWD, mapOf("session_id" to sessionId, "carpark_id" to carparkId, "report_type" to reportType, "client_timestamp" to clientTimestampSec), null, true, false)

  suspend fun submitTripReport(sessionId: String, carparkId: String, ratingTag: String, perkStatusTag: String, perkNotes: String = "", tripTimestampSec: Long, photoUrl: String? = null): CallOutcome {
    val payload = linkedMapOf<String, Any?>("carpark_id" to carparkId, "rating_tag" to ratingTag, "perk_status_tag" to perkStatusTag, "perk_notes" to perkNotes, "session_id" to sessionId, "trip_timestamp" to tripTimestampSec)
    if (photoUrl != null) payload["photo_url"] = photoUrl
    return dispatch(TRIP, payload, null, true, false, silentConflict = true)
  }

  private suspend fun dispatch(endpoint: String, payload: Map<String, Any?>, mode: QueryTriggerMode?, graded: Boolean, ready: Boolean, conflictCue: Boolean = false, silentConflict: Boolean = false): CallOutcome {
    val timeline = mutableListOf<TripState>()
    val cues = mutableListOf<String>()
    val waits = mutableListOf<Long>()
    val body = bodyJson(payload)
    var attempts = 0
    while (true) {
      attempts += 1
      logRequest(endpoint, attempts, mode, rateLimitRetry = false)
      val result = transport.post(endpoint, body)
      val waitMs = if (result.statusCode == HTTP_TOO_MANY) rateLimitWaitMs(result.resolvedRetryAfterSeconds()) else null
      logResult(result, waitMs)
      if (result.statusCode == HTTP_TOO_MANY) {
        return finishRateLimit(endpoint, body, mode, ready, conflictCue, silentConflict, timeline, cues, waits, attempts, result)
      }
      if (isConflict(result, conflictCue, silentConflict)) return conflictOutcome(result, conflictCue, timeline, cues, waits, attempts)
      if (isOk(result)) {
        if (ready) markReady(timeline)
        return success(result, timeline, cues, waits, attempts)
      }
      if (attempts == 1 && (result.timedOut || result.networkError)) noteFirstFailure(mode, timeline, cues)
      if (!(graded && attempts < NativeConfig.API_MAX_ATTEMPTS && isGradedFailure(result))) return fail(result, mode, timeline, cues, waits, attempts)
      delayMs(NativeConfig.API_RETRY_INTERVAL_MS)
      waits += NativeConfig.API_RETRY_INTERVAL_MS
    }
  }

  private suspend fun finishRateLimit(
    endpoint: String,
    body: String,
    mode: QueryTriggerMode?,
    ready: Boolean,
    conflictCue: Boolean,
    silentConflict: Boolean,
    timeline: MutableList<TripState>,
    cues: MutableList<String>,
    waits: MutableList<Long>,
    attempts: Int,
    limited: HttpResult,
  ): CallOutcome {
    val waitMs = rateLimitWaitMs(limited.resolvedRetryAfterSeconds())
    delayMs(waitMs)
    waits += waitMs
    val total = attempts + 1
    logRequest(endpoint, total, mode, rateLimitRetry = true)
    val retry = transport.post(endpoint, body)
    val retryWaitMs = if (retry.statusCode == HTTP_TOO_MANY) rateLimitWaitMs(retry.resolvedRetryAfterSeconds()) else null
    logResult(retry, retryWaitMs)
    if (isConflict(retry, conflictCue, silentConflict)) return conflictOutcome(retry, conflictCue, timeline, cues, waits, total)
    if (isOk(retry)) {
      if (ready) markReady(timeline)
      return success(retry, timeline, cues, waits, total)
    }
    return fail(retry, mode, timeline, cues, waits, total)
  }

  private fun noteFirstFailure(mode: QueryTriggerMode?, timeline: MutableList<TripState>, cues: MutableList<String>) {
    markGrey(timeline)
    if (mode == QueryTriggerMode.DRIVER_ACTIVE) cues += ZhHkTemplates.NETWORK_SLOW
  }

  private fun fail(result: HttpResult, mode: QueryTriggerMode?, timeline: MutableList<TripState>, cues: MutableList<String>, waits: MutableList<Long>, attempts: Int): CallOutcome {
    markGrey(timeline)
    if (mode == QueryTriggerMode.DRIVER_ACTIVE || mode == QueryTriggerMode.B2_RESOLUTION) cues += ZhHkTemplates.NETWORK_FAILED
    return outcome(false, result.statusCode, timeline, cues, waits, attempts, sanitize(result.recommendResponse))
  }

  private fun success(result: HttpResult, timeline: MutableList<TripState>, cues: MutableList<String>, waits: MutableList<Long>, attempts: Int): CallOutcome =
    outcome(true, result.statusCode, timeline, cues, waits, attempts, sanitize(result.recommendResponse))

  private fun conflictOutcome(result: HttpResult, conflictCue: Boolean, timeline: MutableList<TripState>, cues: MutableList<String>, waits: MutableList<Long>, attempts: Int): CallOutcome =
    outcome(false, result.statusCode, timeline, cues, waits, attempts, actionSpokenCue = if (conflictCue) result.spokenCue else null)

  private fun outcome(succeeded: Boolean, httpStatus: Int?, timeline: List<TripState> = emptyList(), cues: List<String> = emptyList(), waits: List<Long> = emptyList(), attempts: Int, response: RecommendResponse? = null, actionSpokenCue: String? = null): CallOutcome =
    CallOutcome(succeeded, httpStatus, stateMachine.state, stateMachine.capsuleLight, timeline.toList(), cues.toList(), attempts, waits.toList(), response, actionSpokenCue)

  private fun markGrey(timeline: MutableList<TripState>) {
    stateMachine.onNetworkFailure()
    timeline += stateMachine.state
  }

  private fun markReady(timeline: MutableList<TripState>) {
    if (stateMachine.networkDegraded) stateMachine.onRequestSuccess()
    if (stateMachine.fgsRunning && stateMachine.state != TripState.NEARBY_READY) {
      stateMachine.onJitSucceeded(StatusPip.NONE)
    }
    timeline += stateMachine.state
  }

  private fun logRequest(endpoint: String, attempts: Int, mode: QueryTriggerMode?, rateLimitRetry: Boolean) {
    val attemptLabel = if (rateLimitRetry) {
      "rate-limit retry total=$attempts/${NativeConfig.API_MAX_ATTEMPTS}"
    } else {
      "attempt $attempts/${NativeConfig.API_MAX_ATTEMPTS}"
    }
    val modeName = mode?.name ?: "DIRECT"
    runCatching {
      android.util.Log.i("ApiClient", "request path=$endpoint $attemptLabel mode=$modeName")
    }
  }

  private fun logResult(result: HttpResult, waitMs: Long?) {
    val status = when {
      result.statusCode == HTTP_TOO_MANY -> "RATE_LIMITED"
      result.statusCode == HTTP_CONFLICT -> "CONFLICT"
      result.timedOut -> "TIMEOUT"
      result.networkError -> "NETWORK_ERROR"
      isOk(result) -> "SUCCESS"
      else -> "FAILED"
    }
    val waitLabel = if (result.statusCode == HTTP_TOO_MANY && waitMs != null) " waitMs=$waitMs" else ""
    runCatching {
      android.util.Log.i("ApiClient", "result status=$status statusCode=${result.statusCode}$waitLabel")
    }
  }

  private fun rateLimitWaitMs(retryAfterSeconds: Long?): Long {
    if (retryAfterSeconds != null && retryAfterSeconds > 0L) return minOf(retryAfterSeconds * MS_PER_SECOND, NativeConfig.RATE_LIMIT_MAX_WAIT_MS)
    return NativeConfig.RATE_LIMIT_RETRY_DELAY_MS
  }

  private fun sanitize(response: RecommendResponse?): RecommendResponse? = response?.copy(cards = response.sanitizedCards())
  private fun isOk(result: HttpResult): Boolean = result.statusCode == HTTP_OK && !result.timedOut && !result.networkError
  private fun isGradedFailure(result: HttpResult): Boolean = result.timedOut || result.networkError || result.statusCode >= HTTP_SERVER_ERROR
  private fun isConflict(result: HttpResult, conflictCue: Boolean, silentConflict: Boolean): Boolean = result.statusCode == HTTP_CONFLICT && (conflictCue || silentConflict)

  private fun bodyJson(payload: Map<String, Any?>): String = payload.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
    "\"$key\":${jsonValue(value)}"
  }

  private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Number -> value.toString()
    else -> "\"${value.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
  }

  companion object {
    const val HEADER_API_KEY = "X-Internal-API-Key"
    const val INTERNAL_API_KEY = "<INTERNAL_API_KEY>"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val JSON_CONTENT_TYPE = "application/json"
    const val RECOMMEND = "/v1/recommend-and-route"
    const val COMMIT = "/v1/session/commit"
    const val RELEASE = "/v1/session/release"
    const val SYNC = "/v1/sync/outbox"
    const val CROWD = "/v1/feedback/crowd-report"
    const val TRIP = "/v1/feedback/trip-report"
    val ENDPOINTS = listOf(RECOMMEND, COMMIT, RELEASE, SYNC, CROWD, TRIP)
    private const val PARKED_CONFIRMED = "PARKED_CONFIRMED"
    private const val DRIVER_CONFIRMED = "DRIVER_CONFIRMED"
  }
}

private const val METER = "METER"
private const val DEGRADED = "degraded"
private const val UNKNOWN_VACANCIES = -1
private const val HTTP_OK = 200
private const val HTTP_CONFLICT = 409
private const val HTTP_TOO_MANY = 429
private const val HTTP_SERVER_ERROR = 500
private const val MS_PER_SECOND = 1000L
