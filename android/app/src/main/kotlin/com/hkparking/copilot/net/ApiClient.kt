package com.hkparking.copilot.net

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.voice.RecommendationCard
import com.hkparking.copilot.voice.ZhHkTemplates

enum class NetworkLightState {
  AMBER_STEADY,
  GREY_BREATHING,
  NEARBY_READY,
  ALERT_BREATHING,
}

enum class QueryTriggerMode {
  BACKGROUND_PREFETCH,
  DRIVER_ACTIVE,
  B2_RESOLUTION,
}

data class RecommendRequest(
  val sessionId: String,
  val originLat: Double,
  val originLng: Double,
  val destLat: Double,
  val destLng: Double,
  val destName: String,
  val preference: String,
  val expandScarcity: Boolean = false,
  val speechLocale: String = NativeConfig.SPEECH_LOCALE_DEFAULT,
)

data class RecommendResponse(
  val status: String,
  val speechLocale: String = NativeConfig.SPEECH_LOCALE_DEFAULT,
  val speechLocaleFallback: Boolean = false,
  val sourceStatus: String = "ok",
  val sourceSnapshotTs: String = "",
  val processedAt: String = "",
  val serverNow: String = "",
  val dataAgeSeconds: Int = 0,
  val cards: List<RecommendationCard> = emptyList(),
  val awaitVoiceReply: Boolean = false,
) {
  fun sanitizedCards(): List<RecommendationCard> {
    return cards.map { card ->
      if (card.type == METER_TYPE && (card.isStale || sourceStatus == SOURCE_DEGRADED)) {
        card.copy(vacancies = UNKNOWN_VACANCIES, spokenCue = ZhHkTemplates.METER_STALE_SPOKEN)
      } else {
        card
      }
    }
  }
}

data class TransportResult(
  val httpStatus: Int = 200,
  val retryAfterSeconds: Long? = null,
  val timedOut: Boolean = false,
  val networkError: Boolean = false,
  val recommendResponse: RecommendResponse? = null,
  val spokenCue: String? = null,
)

interface ApiTransport {
  fun post(
    endpoint: String,
    headers: Map<String, String>,
    payload: Map<String, Any?>,
    timeoutMs: Long,
  ): TransportResult
}

data class CallOutcome(
  val succeeded: Boolean,
  val httpStatus: Int?,
  val finalLightState: NetworkLightState,
  val stateTimeline: List<NetworkLightState>,
  val spokenCues: List<String>,
  val attempts: Int,
  val waitIntervalsMs: List<Long>,
  val response: RecommendResponse? = null,
  val actionSpokenCue: String? = null,
)

class ApiClient(
  private val transport: ApiTransport,
  private val sleepMs: (Long) -> Unit = {},
) {
  var currentLightState: NetworkLightState = NetworkLightState.AMBER_STEADY
    private set

  fun defaultHeaders(): Map<String, String> {
    return mapOf(
      HEADER_API_KEY to INTERNAL_API_KEY,
      HEADER_CONTENT_TYPE to JSON_CONTENT_TYPE,
    )
  }

  fun recommendAndRoute(
    request: RecommendRequest,
    mode: QueryTriggerMode = QueryTriggerMode.BACKGROUND_PREFETCH,
  ): CallOutcome {
    return dispatch(
      endpoint = RECOMMEND,
      payload = recommendPayload(request),
      mode = mode,
      gradedRetries = true,
      readyOnSuccess = true,
      conflictCue = false,
      silentConflict = false,
    )
  }

  fun commitSession(sessionId: String, targetCarparkId: String): CallOutcome {
    return dispatch(
      endpoint = COMMIT,
      payload = mapOf(
        "session_id" to sessionId,
        "target_carpark_id" to targetCarparkId,
      ),
      mode = null,
      gradedRetries = true,
      readyOnSuccess = false,
      conflictCue = true,
      silentConflict = false,
    )
  }

  fun releaseSession(
    sessionId: String,
    targetCarparkId: String,
    isOnline: Boolean = true,
  ): CallOutcome {
    if (!isOnline) {
      return abandoned()
    }
    return dispatch(
      endpoint = RELEASE,
      payload = mapOf(
        "session_id" to sessionId,
        "target_carpark_id" to targetCarparkId,
        "reason" to PARKED_CONFIRMED,
      ),
      mode = null,
      gradedRetries = false,
      readyOnSuccess = false,
      conflictCue = false,
      silentConflict = false,
    )
  }

  fun syncDriverConfirmed(sessionId: String, timestampSec: Long): CallOutcome {
    return dispatch(
      endpoint = SYNC,
      payload = mapOf(
        "session_id" to sessionId,
        "event_type" to DRIVER_CONFIRMED,
        "timestamp" to timestampSec,
      ),
      mode = null,
      gradedRetries = true,
      readyOnSuccess = false,
      conflictCue = false,
      silentConflict = false,
    )
  }

  fun submitCrowdReport(
    sessionId: String,
    carparkId: String,
    reportType: String,
    clientTimestampSec: Long,
  ): CallOutcome {
    return dispatch(
      endpoint = CROWD,
      payload = mapOf(
        "session_id" to sessionId,
        "carpark_id" to carparkId,
        "report_type" to reportType,
        "client_timestamp" to clientTimestampSec,
      ),
      mode = null,
      gradedRetries = true,
      readyOnSuccess = false,
      conflictCue = false,
      silentConflict = false,
    )
  }

  fun submitTripReport(
    sessionId: String,
    carparkId: String,
    ratingTag: String,
    perkStatusTag: String,
    perkNotes: String = "",
    tripTimestampSec: Long,
    photoUrl: String? = null,
  ): CallOutcome {
    val payload = linkedMapOf<String, Any?>(
      "carpark_id" to carparkId,
      "rating_tag" to ratingTag,
      "perk_status_tag" to perkStatusTag,
      "perk_notes" to perkNotes,
      "session_id" to sessionId,
      "trip_timestamp" to tripTimestampSec,
    )
    if (photoUrl != null) {
      payload["photo_url"] = photoUrl
    }
    return dispatch(
      endpoint = TRIP,
      payload = payload,
      mode = null,
      gradedRetries = true,
      readyOnSuccess = false,
      conflictCue = false,
      silentConflict = true,
    )
  }

  private fun dispatch(
    endpoint: String,
    payload: Map<String, Any?>,
    mode: QueryTriggerMode?,
    gradedRetries: Boolean,
    readyOnSuccess: Boolean,
    conflictCue: Boolean,
    silentConflict: Boolean,
  ): CallOutcome {
    val timeline = mutableListOf<NetworkLightState>()
    val cues = mutableListOf<String>()
    val waits = mutableListOf<Long>()
    if (mode == QueryTriggerMode.BACKGROUND_PREFETCH || mode == QueryTriggerMode.B2_RESOLUTION) {
      mark(NetworkLightState.AMBER_STEADY, timeline)
    }
    var attempts = 1
    var result = post(endpoint, payload)
    if (result.httpStatus == HTTP_TOO_MANY) {
      return finishRateLimit(
        endpoint = endpoint,
        payload = payload,
        mode = mode,
        readyOnSuccess = readyOnSuccess,
        conflictCue = conflictCue,
        silentConflict = silentConflict,
        timeline = timeline,
        cues = cues,
        waits = waits,
        attempts = attempts,
      )
    }
    if (isConflict(result, conflictCue, silentConflict)) {
      return conflictOutcome(result, timeline, cues, waits, attempts, conflictCue)
    }
    while (true) {
      if (isOk(result)) {
        if (readyOnSuccess) {
          mark(NetworkLightState.NEARBY_READY, timeline)
        }
        return successOutcome(result, timeline, cues, waits, attempts)
      }
      if (attempts == 1 && isTransportFailure(result)) {
        when (mode) {
          QueryTriggerMode.BACKGROUND_PREFETCH -> mark(NetworkLightState.GREY_BREATHING, timeline)
          QueryTriggerMode.DRIVER_ACTIVE -> {
            mark(NetworkLightState.GREY_BREATHING, timeline)
            cues += ZhHkTemplates.NETWORK_SLOW
          }
          QueryTriggerMode.B2_RESOLUTION, null -> Unit
        }
      }
      val retryable = gradedRetries && attempts < NativeConfig.API_MAX_ATTEMPTS && isGradedFailure(result)
      if (!retryable) {
        return exhausted(result, mode, timeline, cues, waits, attempts)
      }
      sleepMs(NativeConfig.API_RETRY_INTERVAL_MS)
      waits += NativeConfig.API_RETRY_INTERVAL_MS
      attempts += 1
      result = post(endpoint, payload)
      if (result.httpStatus == HTTP_TOO_MANY) {
        return exhausted(result, mode, timeline, cues, waits, attempts)
      }
      if (isConflict(result, conflictCue, silentConflict)) {
        return conflictOutcome(result, timeline, cues, waits, attempts, conflictCue)
      }
    }
  }

  private fun finishRateLimit(
    endpoint: String,
    payload: Map<String, Any?>,
    mode: QueryTriggerMode?,
    readyOnSuccess: Boolean,
    conflictCue: Boolean,
    silentConflict: Boolean,
    timeline: MutableList<NetworkLightState>,
    cues: MutableList<String>,
    waits: MutableList<Long>,
    attempts: Int,
  ): CallOutcome {
    val waitMs = rateLimitWaitMs(lastRetryAfterSeconds)
    sleepMs(waitMs)
    waits += waitMs
    val retry = post(endpoint, payload)
    val totalAttempts = attempts + 1
    if (isConflict(retry, conflictCue, silentConflict)) {
      return conflictOutcome(retry, timeline, cues, waits, totalAttempts, conflictCue)
    }
    if (isOk(retry)) {
      if (readyOnSuccess) {
        mark(NetworkLightState.NEARBY_READY, timeline)
      }
      return successOutcome(retry, timeline, cues, waits, totalAttempts)
    }
    return exhausted(retry, mode, timeline, cues, waits, totalAttempts)
  }

  private fun exhausted(
    result: TransportResult,
    mode: QueryTriggerMode?,
    timeline: MutableList<NetworkLightState>,
    cues: MutableList<String>,
    waits: MutableList<Long>,
    attempts: Int,
  ): CallOutcome {
    mark(NetworkLightState.GREY_BREATHING, timeline)
    if (mode == QueryTriggerMode.DRIVER_ACTIVE || mode == QueryTriggerMode.B2_RESOLUTION) {
      cues += ZhHkTemplates.NETWORK_FAILED
    }
    return CallOutcome(
      succeeded = false,
      httpStatus = result.httpStatus,
      finalLightState = currentLightState,
      stateTimeline = timeline.toList(),
      spokenCues = cues.toList(),
      attempts = attempts,
      waitIntervalsMs = waits.toList(),
      response = sanitize(result.recommendResponse),
    )
  }

  private fun successOutcome(
    result: TransportResult,
    timeline: MutableList<NetworkLightState>,
    cues: MutableList<String>,
    waits: MutableList<Long>,
    attempts: Int,
  ): CallOutcome {
    return CallOutcome(
      succeeded = true,
      httpStatus = result.httpStatus,
      finalLightState = currentLightState,
      stateTimeline = timeline.toList(),
      spokenCues = cues.toList(),
      attempts = attempts,
      waitIntervalsMs = waits.toList(),
      response = sanitize(result.recommendResponse),
    )
  }

  private fun conflictOutcome(
    result: TransportResult,
    timeline: MutableList<NetworkLightState>,
    cues: MutableList<String>,
    waits: MutableList<Long>,
    attempts: Int,
    conflictCue: Boolean,
  ): CallOutcome {
    return CallOutcome(
      succeeded = false,
      httpStatus = result.httpStatus,
      finalLightState = currentLightState,
      stateTimeline = timeline.toList(),
      spokenCues = cues.toList(),
      attempts = attempts,
      waitIntervalsMs = waits.toList(),
      actionSpokenCue = if (conflictCue) result.spokenCue else null,
    )
  }

  private fun abandoned(): CallOutcome {
    return CallOutcome(
      succeeded = false,
      httpStatus = null,
      finalLightState = currentLightState,
      stateTimeline = emptyList(),
      spokenCues = emptyList(),
      attempts = 0,
      waitIntervalsMs = emptyList(),
    )
  }

  private fun post(endpoint: String, payload: Map<String, Any?>): TransportResult {
    val result = transport.post(endpoint, defaultHeaders(), payload, NativeConfig.API_TIMEOUT_MS)
    lastRetryAfterSeconds = result.retryAfterSeconds
    return result
  }

  private var lastRetryAfterSeconds: Long? = null

  private fun rateLimitWaitMs(retryAfterSeconds: Long?): Long {
    if (retryAfterSeconds != null && retryAfterSeconds > 0L) {
      return minOf(retryAfterSeconds * 1000L, NativeConfig.RATE_LIMIT_MAX_WAIT_MS)
    }
    return NativeConfig.RATE_LIMIT_RETRY_DELAY_MS
  }

  private fun mark(state: NetworkLightState, timeline: MutableList<NetworkLightState>) {
    currentLightState = state
    timeline += state
  }

  private fun recommendPayload(request: RecommendRequest): Map<String, Any?> {
    return mapOf(
      "session_id" to request.sessionId,
      "origin_lat" to request.originLat,
      "origin_lng" to request.originLng,
      "dest_lat" to request.destLat,
      "dest_lng" to request.destLng,
      "dest_name" to request.destName,
      "preference" to request.preference,
      "expand_scarcity" to request.expandScarcity,
      "speech_locale" to request.speechLocale,
    )
  }

  private fun sanitize(response: RecommendResponse?): RecommendResponse? {
    if (response == null) {
      return null
    }
    return response.copy(cards = response.sanitizedCards())
  }

  private fun isOk(result: TransportResult): Boolean {
    return result.httpStatus == HTTP_OK && !result.timedOut && !result.networkError
  }

  private fun isTransportFailure(result: TransportResult): Boolean {
    return result.timedOut || result.networkError
  }

  private fun isGradedFailure(result: TransportResult): Boolean {
    return isTransportFailure(result) || result.httpStatus >= HTTP_SERVER_ERROR
  }

  private fun isConflict(result: TransportResult, conflictCue: Boolean, silentConflict: Boolean): Boolean {
    return result.httpStatus == HTTP_CONFLICT && (conflictCue || silentConflict)
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
    private const val HTTP_OK = 200
    private const val HTTP_CONFLICT = 409
    private const val HTTP_TOO_MANY = 429
    private const val HTTP_SERVER_ERROR = 500
  }
}

private const val METER_TYPE = "METER"
private const val SOURCE_DEGRADED = "degraded"
private const val UNKNOWN_VACANCIES = -1
