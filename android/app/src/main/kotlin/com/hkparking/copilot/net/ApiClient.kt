package com.hkparking.copilot.net

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.voice.RecommendationCard
import com.hkparking.copilot.voice.ZhHkTemplates

enum class NetworkLightState { AMBER_STEADY, GREY_BREATHING, NEARBY_READY, ALERT_BREATHING }
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
    if (card.type == METER && (card.isStale || sourceStatus == DEGRADED)) card.copy(vacancies = UNKNOWN_VACANCIES, spokenCue = ZhHkTemplates.METER_STALE_SPOKEN) else card
  }
}

data class TransportResult(
  val httpStatus: Int = HTTP_OK, val retryAfterSeconds: Long? = null, val timedOut: Boolean = false, val networkError: Boolean = false,
  val recommendResponse: RecommendResponse? = null, val spokenCue: String? = null,
)

interface ApiTransport {
  fun post(endpoint: String, headers: Map<String, String>, payload: Map<String, Any?>, timeoutMs: Long): TransportResult
}

data class CallOutcome(
  val succeeded: Boolean, val httpStatus: Int?, val finalLightState: NetworkLightState, val stateTimeline: List<NetworkLightState>,
  val spokenCues: List<String>, val attempts: Int, val waitIntervalsMs: List<Long>, val response: RecommendResponse? = null, val actionSpokenCue: String? = null,
)

class ApiClient(private val transport: ApiTransport, private val sleepMs: (Long) -> Unit = {}) {
  var currentLightState: NetworkLightState = NetworkLightState.AMBER_STEADY
    private set

  fun defaultHeaders(): Map<String, String> = mapOf(HEADER_API_KEY to INTERNAL_API_KEY, HEADER_CONTENT_TYPE to JSON_CONTENT_TYPE)

  fun recommendAndRoute(request: RecommendRequest, mode: QueryTriggerMode = QueryTriggerMode.BACKGROUND_PREFETCH): CallOutcome = dispatch(RECOMMEND, mapOf(
    "session_id" to request.sessionId, "origin_lat" to request.originLat, "origin_lng" to request.originLng, "dest_lat" to request.destLat,
    "dest_lng" to request.destLng, "dest_name" to request.destName, "preference" to request.preference, "expand_scarcity" to request.expandScarcity, "speech_locale" to request.speechLocale,
  ), mode, graded = true, ready = true)

  fun commitSession(sessionId: String, targetCarparkId: String): CallOutcome =
    dispatch(COMMIT, mapOf("session_id" to sessionId, "target_carpark_id" to targetCarparkId), null, true, false, conflictCue = true)

  fun releaseSession(sessionId: String, targetCarparkId: String, isOnline: Boolean = true): CallOutcome {
    if (!isOnline) return outcome(false, null, attempts = 0)
    return dispatch(RELEASE, mapOf("session_id" to sessionId, "target_carpark_id" to targetCarparkId, "reason" to PARKED_CONFIRMED), null, false, false)
  }

  fun syncDriverConfirmed(sessionId: String, timestampSec: Long): CallOutcome =
    dispatch(SYNC, mapOf("session_id" to sessionId, "event_type" to DRIVER_CONFIRMED, "timestamp" to timestampSec), null, true, false)

  fun submitCrowdReport(sessionId: String, carparkId: String, reportType: String, clientTimestampSec: Long): CallOutcome =
    dispatch(CROWD, mapOf("session_id" to sessionId, "carpark_id" to carparkId, "report_type" to reportType, "client_timestamp" to clientTimestampSec), null, true, false)

  fun submitTripReport(sessionId: String, carparkId: String, ratingTag: String, perkStatusTag: String, perkNotes: String = "", tripTimestampSec: Long, photoUrl: String? = null): CallOutcome {
    val payload = linkedMapOf<String, Any?>("carpark_id" to carparkId, "rating_tag" to ratingTag, "perk_status_tag" to perkStatusTag, "perk_notes" to perkNotes, "session_id" to sessionId, "trip_timestamp" to tripTimestampSec)
    if (photoUrl != null) payload["photo_url"] = photoUrl
    return dispatch(TRIP, payload, null, true, false, silentConflict = true)
  }

  private fun dispatch(endpoint: String, payload: Map<String, Any?>, mode: QueryTriggerMode?, graded: Boolean, ready: Boolean, conflictCue: Boolean = false, silentConflict: Boolean = false): CallOutcome {
    val timeline = mutableListOf<NetworkLightState>()
    val cues = mutableListOf<String>()
    val waits = mutableListOf<Long>()
    if (mode == QueryTriggerMode.BACKGROUND_PREFETCH || mode == QueryTriggerMode.B2_RESOLUTION) mark(NetworkLightState.AMBER_STEADY, timeline)
    var attempts = 1
    var result = post(endpoint, payload)
    if (result.httpStatus == HTTP_TOO_MANY) {
      val waitMs = rateLimitWaitMs(result.retryAfterSeconds)
      sleepMs(waitMs)
      waits += waitMs
      return settle(post(endpoint, payload), mode, ready, conflictCue, silentConflict, timeline, cues, waits, attempts + 1)
    }
    while (true) {
      if (isConflict(result, conflictCue, silentConflict)) return conflictOutcome(result, conflictCue, timeline, cues, waits, attempts)
      if (isOk(result)) {
        if (ready) mark(NetworkLightState.NEARBY_READY, timeline)
        return outcome(true, result.httpStatus, timeline, cues, waits, attempts, sanitize(result.recommendResponse))
      }
      if (attempts == 1 && (result.timedOut || result.networkError)) noteFirstFailure(mode, timeline, cues)
      if (!(graded && attempts < NativeConfig.API_MAX_ATTEMPTS && isGradedFailure(result))) return fail(result, mode, timeline, cues, waits, attempts)
      sleepMs(NativeConfig.API_RETRY_INTERVAL_MS)
      waits += NativeConfig.API_RETRY_INTERVAL_MS
      attempts += 1
      result = post(endpoint, payload)
      if (result.httpStatus == HTTP_TOO_MANY) return fail(result, mode, timeline, cues, waits, attempts)
    }
  }

  private fun settle(result: TransportResult, mode: QueryTriggerMode?, ready: Boolean, conflictCue: Boolean, silentConflict: Boolean, timeline: MutableList<NetworkLightState>, cues: MutableList<String>, waits: MutableList<Long>, attempts: Int): CallOutcome {
    if (isConflict(result, conflictCue, silentConflict)) return conflictOutcome(result, conflictCue, timeline, cues, waits, attempts)
    if (isOk(result)) {
      if (ready) mark(NetworkLightState.NEARBY_READY, timeline)
      return outcome(true, result.httpStatus, timeline, cues, waits, attempts, sanitize(result.recommendResponse))
    }
    return fail(result, mode, timeline, cues, waits, attempts)
  }

  private fun noteFirstFailure(mode: QueryTriggerMode?, timeline: MutableList<NetworkLightState>, cues: MutableList<String>) {
    when (mode) {
      QueryTriggerMode.BACKGROUND_PREFETCH -> mark(NetworkLightState.GREY_BREATHING, timeline)
      QueryTriggerMode.DRIVER_ACTIVE -> {
        mark(NetworkLightState.GREY_BREATHING, timeline)
        cues += ZhHkTemplates.NETWORK_SLOW
      }
      else -> Unit
    }
  }

  private fun fail(result: TransportResult, mode: QueryTriggerMode?, timeline: MutableList<NetworkLightState>, cues: MutableList<String>, waits: MutableList<Long>, attempts: Int): CallOutcome {
    mark(NetworkLightState.GREY_BREATHING, timeline)
    if (mode == QueryTriggerMode.DRIVER_ACTIVE || mode == QueryTriggerMode.B2_RESOLUTION) cues += ZhHkTemplates.NETWORK_FAILED
    return outcome(false, result.httpStatus, timeline, cues, waits, attempts, sanitize(result.recommendResponse))
  }

  private fun conflictOutcome(result: TransportResult, conflictCue: Boolean, timeline: MutableList<NetworkLightState>, cues: MutableList<String>, waits: MutableList<Long>, attempts: Int): CallOutcome =
    outcome(false, result.httpStatus, timeline, cues, waits, attempts, actionSpokenCue = if (conflictCue) result.spokenCue else null)

  private fun outcome(succeeded: Boolean, httpStatus: Int?, timeline: List<NetworkLightState> = emptyList(), cues: List<String> = emptyList(), waits: List<Long> = emptyList(), attempts: Int, response: RecommendResponse? = null, actionSpokenCue: String? = null): CallOutcome =
    CallOutcome(succeeded, httpStatus, currentLightState, timeline.toList(), cues.toList(), attempts, waits.toList(), response, actionSpokenCue)

  private fun post(endpoint: String, payload: Map<String, Any?>): TransportResult = transport.post(endpoint, defaultHeaders(), payload, NativeConfig.API_TIMEOUT_MS)

  private fun rateLimitWaitMs(retryAfterSeconds: Long?): Long =
    if (retryAfterSeconds != null && retryAfterSeconds > 0L) minOf(retryAfterSeconds * MS_PER_SECOND, NativeConfig.RATE_LIMIT_MAX_WAIT_MS) else NativeConfig.RATE_LIMIT_RETRY_DELAY_MS

  private fun mark(state: NetworkLightState, timeline: MutableList<NetworkLightState>) {
    currentLightState = state
    timeline += state
  }

  private fun sanitize(response: RecommendResponse?): RecommendResponse? = response?.copy(cards = response.sanitizedCards())
  private fun isOk(result: TransportResult): Boolean = result.httpStatus == HTTP_OK && !result.timedOut && !result.networkError
  private fun isGradedFailure(result: TransportResult): Boolean = result.timedOut || result.networkError || result.httpStatus >= HTTP_SERVER_ERROR
  private fun isConflict(result: TransportResult, conflictCue: Boolean, silentConflict: Boolean): Boolean = result.httpStatus == HTTP_CONFLICT && (conflictCue || silentConflict)

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
