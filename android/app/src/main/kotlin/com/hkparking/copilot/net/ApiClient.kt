package com.hkparking.copilot.net

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.TripState
import com.hkparking.copilot.voice.ZhHkTemplates

const val INTERNAL_API_KEY_HEADER = "X-Internal-API-Key"
const val INTERNAL_API_KEY_PLACEHOLDER = "<INTERNAL_API_KEY>"
const val JSON_CONTENT_TYPE = "application/json"

data class ApiRequest(
  val method: String,
  val path: String,
  val headers: Map<String, String>,
  val body: String,
  val timeoutMs: Long,
)

data class ApiResponse(
  val statusCode: Int = 0,
  val headers: Map<String, String> = emptyMap(),
  val body: String = "",
  val timedOut: Boolean = false,
  val networkError: Boolean = false,
)

interface ApiTransport {
  fun execute(request: ApiRequest): ApiResponse
}

interface ApiCapsuleSignals {
  fun onState(state: TripState)
  fun speak(cue: String)
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
)

data class RecommendCard(
  val carparkId: String,
  val name: String,
  val type: String,
  val vacancies: Int,
  val distanceMeters: Int,
  val feeSummary: String?,
  val spokenCue: String,
  val isStale: Boolean,
)

data class RecommendResponse(
  val status: String,
  val speechLocale: String,
  val speechLocaleFallback: Boolean,
  val sourceStatus: String,
  val sourceSnapshotTs: Long,
  val processedAt: Long,
  val serverNow: Long,
  val dataAgeSeconds: Int,
  val awaitVoiceReply: Boolean,
  val cards: List<RecommendCard>,
)

data class CommitResult(
  val ok: Boolean,
  val lockFull: Boolean = false,
  val spokenCue: String? = null,
)

data class TripReportResult(
  val accepted: Boolean,
  val silentConflict: Boolean = false,
)

class ApiClient(
  private val transport: ApiTransport,
  private val signals: ApiCapsuleSignals,
  private val apiKey: String = INTERNAL_API_KEY_PLACEHOLDER,
  private val delayMs: (Long) -> Unit = {},
  @Suppress("unused") private val nowMs: () -> Long = { 0L },
) {
  fun recommendAndRoute(
    request: RecommendRequest,
    isUserInitiated: Boolean,
    isB2Resolution: Boolean = false,
  ): RecommendResponse? {
    val response = execute(
      path = "/v1/recommend-and-route",
      body = recommendBody(request),
      mode = recommendMode(isUserInitiated, isB2Resolution),
    ) ?: return null
    if (!isSuccess(response)) {
      return null
    }
    return parseRecommend(response.body)
  }

  fun commitSession(sessionId: String, targetCarparkId: String): CommitResult {
    val response = execute(
      path = "/v1/session/commit",
      body = obj(
        "session_id" to jsonString(sessionId),
        "target_carpark_id" to jsonString(targetCarparkId),
      ),
      mode = silentGraded(),
    ) ?: return CommitResult(ok = false)
    if (response.statusCode == 409) {
      val cue = parseObject(response.body).string("spoken_cue")
      if (!cue.isNullOrEmpty()) {
        signals.speak(cue)
      }
      return CommitResult(ok = false, lockFull = statusOf(response) == "LOCK_FULL", spokenCue = cue)
    }
    return CommitResult(ok = isSuccess(response))
  }

  fun releaseSession(sessionId: String, targetCarparkId: String): Boolean {
    val response = execute(
      path = "/v1/session/release",
      body = obj(
        "session_id" to jsonString(sessionId),
        "target_carpark_id" to jsonString(targetCarparkId),
        "reason" to jsonString("PARKED_CONFIRMED"),
      ),
      mode = abandon(),
    ) ?: return false
    return isSuccess(response)
  }

  fun syncDriverConfirmed(sessionId: String, timestamp: Long): Boolean {
    val response = execute(
      path = "/v1/sync/outbox",
      body = obj(
        "session_id" to jsonString(sessionId),
        "event_type" to jsonString("DRIVER_CONFIRMED"),
        "timestamp" to timestamp.toString(),
      ),
      mode = silentGraded(),
    ) ?: return false
    return isSuccess(response)
  }

  fun submitCrowdReport(
    sessionId: String,
    carparkId: String,
    reportType: String,
    clientTimestamp: Long,
  ): Boolean {
    val response = execute(
      path = "/v1/feedback/crowd-report",
      body = obj(
        "session_id" to jsonString(sessionId),
        "carpark_id" to jsonString(carparkId),
        "report_type" to jsonString(reportType),
        "client_timestamp" to clientTimestamp.toString(),
      ),
      mode = silentGraded(),
    ) ?: return false
    return isSuccess(response)
  }

  fun submitTripReport(
    sessionId: String,
    carparkId: String,
    ratingTag: String,
    perkStatusTag: String,
    perkNotes: String,
    tripTimestamp: Long,
    photoUrl: String? = null,
  ): TripReportResult {
    val fields = linkedMapOf(
      "carpark_id" to jsonString(carparkId),
      "rating_tag" to jsonString(ratingTag),
      "perk_status_tag" to jsonString(perkStatusTag),
      "perk_notes" to jsonString(perkNotes),
      "session_id" to jsonString(sessionId),
      "trip_timestamp" to tripTimestamp.toString(),
    )
    if (photoUrl != null) {
      fields["photo_url"] = jsonString(photoUrl)
    }
    val response = execute(
      path = "/v1/feedback/trip-report",
      body = obj(fields),
      mode = silentGraded(),
    ) ?: return TripReportResult(accepted = false)
    if (response.statusCode == 409) {
      return TripReportResult(accepted = false, silentConflict = true)
    }
    return TripReportResult(accepted = isSuccess(response))
  }

  private fun recommendMode(isUserInitiated: Boolean, isB2Resolution: Boolean): CallMode {
    return CallMode(
      amberWhilePending = isB2Resolution || !isUserInitiated,
      announceSlow = isUserInitiated && !isB2Resolution,
      announceFailed = isUserInitiated || isB2Resolution,
      readyOnSuccess = true,
      gradedRetries = true,
    )
  }

  private fun silentGraded(): CallMode {
    return CallMode(
      amberWhilePending = false,
      announceSlow = false,
      announceFailed = false,
      readyOnSuccess = false,
      gradedRetries = true,
    )
  }

  private fun abandon(): CallMode {
    return CallMode(
      amberWhilePending = false,
      announceSlow = false,
      announceFailed = false,
      readyOnSuccess = false,
      gradedRetries = false,
    )
  }

  private fun execute(path: String, body: String, mode: CallMode): ApiResponse? {
    if (mode.amberWhilePending) {
      signals.onState(TripState.AMBER_STEADY)
    }
    var graded = 0
    while (true) {
      val response = post(path, body)
      if (response.statusCode == 429) {
        return finishRateLimit(path, body, mode)
      }
      if (response.statusCode == 409) {
        return response
      }
      if (isSuccess(response)) {
        if (mode.readyOnSuccess) {
          signals.onState(TripState.NEARBY_READY)
        }
        return response
      }
      if (!isRetryable(response)) {
        return response
      }
      signals.onState(TripState.GREY_BREATHING)
      graded++
      if (mode.announceSlow && graded == 1) {
        signals.speak(ZhHkTemplates.NETWORK_SLOW)
      }
      val exhausted = !mode.gradedRetries || graded >= NativeConfig.API_MAX_ATTEMPTS
      if (exhausted) {
        if (mode.announceFailed) {
          signals.speak(ZhHkTemplates.NETWORK_FAILED)
        }
        return null
      }
      delayMs(NativeConfig.API_RETRY_INTERVAL_MS)
    }
  }

  private fun finishRateLimit(path: String, body: String, mode: CallMode): ApiResponse? {
    delayMs(rateLimitWaitMs(lastHeaders))
    val retry = post(path, body)
    if (retry.statusCode == 409) {
      return retry
    }
    if (isSuccess(retry)) {
      if (mode.readyOnSuccess) {
        signals.onState(TripState.NEARBY_READY)
      }
      return retry
    }
    signals.onState(TripState.GREY_BREATHING)
    if (mode.announceFailed) {
      signals.speak(ZhHkTemplates.NETWORK_FAILED)
    }
    return null
  }

  private var lastHeaders: Map<String, String> = emptyMap()

  private fun post(path: String, body: String): ApiResponse {
    val response = transport.execute(
      ApiRequest(
        method = "POST",
        path = path,
        headers = mapOf(
          INTERNAL_API_KEY_HEADER to apiKey,
          "Content-Type" to JSON_CONTENT_TYPE,
        ),
        body = body,
        timeoutMs = NativeConfig.API_TIMEOUT_MS,
      ),
    )
    lastHeaders = response.headers
    return response
  }

  private fun rateLimitWaitMs(headers: Map<String, String>): Long {
    val raw = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value
    val seconds = raw?.trim()?.toLongOrNull()
    if (seconds == null || seconds < 0L) {
      return NativeConfig.RATE_LIMIT_RETRY_DELAY_MS
    }
    val requested = seconds * 1000L
    return minOf(requested, NativeConfig.RATE_LIMIT_MAX_WAIT_MS)
  }

  private fun isSuccess(response: ApiResponse): Boolean {
    return !response.timedOut && !response.networkError && response.statusCode in 200..299
  }

  private fun isRetryable(response: ApiResponse): Boolean {
    return response.timedOut || response.networkError || response.statusCode in 500..599
  }

  private fun statusOf(response: ApiResponse): String? {
    return parseObject(response.body).string("status")
  }

  private fun recommendBody(request: RecommendRequest): String {
    return obj(
      "session_id" to jsonString(request.sessionId),
      "origin_lat" to request.originLat.toString(),
      "origin_lng" to request.originLng.toString(),
      "dest_lat" to request.destLat.toString(),
      "dest_lng" to request.destLng.toString(),
      "dest_name" to jsonString(request.destName),
      "preference" to jsonString(request.preference),
      "expand_scarcity" to request.expandScarcity.toString(),
      "speech_locale" to jsonString(NativeConfig.SPEECH_LOCALE_DEFAULT),
    )
  }

  private fun parseRecommend(body: String): RecommendResponse {
    val root = parseObject(body)
    val sourceStatus = root.string("source_status") ?: ""
    val cards = root.array("cards").map { card ->
      val type = card.string("type") ?: ""
      val stale = card.boolean("is_stale") ?: false
      val rawVacancies = card.int("vacancies")
      val vacancies = if (type == "METER" && (stale || sourceStatus == "degraded")) {
        -1
      } else {
        rawVacancies ?: -1
      }
      RecommendCard(
        carparkId = card.string("carpark_id") ?: "",
        name = card.string("name") ?: "",
        type = type,
        vacancies = vacancies,
        distanceMeters = card.int("distance_meters") ?: 0,
        feeSummary = card.string("fee_summary"),
        spokenCue = card.string("spoken_cue") ?: "",
        isStale = stale,
      )
    }
    return RecommendResponse(
      status = root.string("status") ?: "",
      speechLocale = root.string("speech_locale") ?: NativeConfig.SPEECH_LOCALE_DEFAULT,
      speechLocaleFallback = root.boolean("speech_locale_fallback") ?: false,
      sourceStatus = sourceStatus,
      sourceSnapshotTs = root.long("source_snapshot_ts") ?: 0L,
      processedAt = root.long("processed_at") ?: 0L,
      serverNow = root.long("server_now") ?: 0L,
      dataAgeSeconds = root.int("data_age_seconds") ?: 0,
      awaitVoiceReply = root.boolean("await_voice_reply") ?: false,
      cards = cards,
    )
  }
}

private data class CallMode(
  val amberWhilePending: Boolean,
  val announceSlow: Boolean,
  val announceFailed: Boolean,
  val readyOnSuccess: Boolean,
  val gradedRetries: Boolean,
)

private class JsonObject(private val fields: Map<String, JsonValue>) {
  fun string(key: String): String? {
    val value = fields[key] ?: return null
    return if (value is JsonString) value.value else null
  }

  fun boolean(key: String): Boolean? {
    val value = fields[key] ?: return null
    return if (value is JsonBool) value.value else null
  }

  fun int(key: String): Int? = long(key)?.toInt()

  fun long(key: String): Long? {
    val value = fields[key] ?: return null
    return when (value) {
      is JsonNumber -> value.raw.toLong()
      is JsonString -> value.value.toLongOrNull()
      else -> null
    }
  }

  fun array(key: String): List<JsonObject> {
    val value = fields[key] as? JsonArray ?: return emptyList()
    return value.items.mapNotNull { item ->
      if (item is JsonObjectValue) JsonObject(item.fields) else null
    }
  }
}

private sealed interface JsonValue
private data class JsonString(val value: String) : JsonValue
private data class JsonNumber(val raw: Double) : JsonValue
private data class JsonBool(val value: Boolean) : JsonValue
private data object JsonNull : JsonValue
private data class JsonObjectValue(val fields: Map<String, JsonValue>) : JsonValue
private data class JsonArray(val items: List<JsonValue>) : JsonValue

private fun parseObject(body: String): JsonObject {
  if (body.isBlank()) {
    return JsonObject(emptyMap())
  }
  val value = JsonReader(body).parseValue()
  val fields = (value as? JsonObjectValue)?.fields ?: emptyMap()
  return JsonObject(fields)
}

private class JsonReader(private val source: String) {
  private var index = 0

  fun parseValue(): JsonValue {
    skip()
    if (index >= source.length) {
      return JsonNull
    }
    return when (source[index]) {
      '{' -> readObject()
      '[' -> readArray()
      '"' -> JsonString(readString())
      't' -> literal("true", JsonBool(true))
      'f' -> literal("false", JsonBool(false))
      'n' -> literal("null", JsonNull)
      else -> readNumber()
    }
  }

  private fun readObject(): JsonValue {
    index++
    val fields = linkedMapOf<String, JsonValue>()
    skip()
    if (peek('}')) {
      index++
      return JsonObjectValue(fields)
    }
    while (index < source.length) {
      skip()
      val key = readString()
      skip()
      if (peek(':')) {
        index++
      }
      fields[key] = parseValue()
      skip()
      when {
        peek(',') -> index++
        peek('}') -> {
          index++
          return JsonObjectValue(fields)
        }
        else -> return JsonObjectValue(fields)
      }
    }
    return JsonObjectValue(fields)
  }

  private fun readArray(): JsonValue {
    index++
    val items = mutableListOf<JsonValue>()
    skip()
    if (peek(']')) {
      index++
      return JsonArray(items)
    }
    while (index < source.length) {
      items += parseValue()
      skip()
      when {
        peek(',') -> index++
        peek(']') -> {
          index++
          return JsonArray(items)
        }
        else -> return JsonArray(items)
      }
    }
    return JsonArray(items)
  }

  private fun readString(): String {
    if (peek('"')) {
      index++
    }
    val out = StringBuilder()
    while (index < source.length) {
      val ch = source[index++]
      when (ch) {
        '"' -> return out.toString()
        '\\' -> {
          if (index >= source.length) {
            return out.toString()
          }
          when (val esc = source[index++]) {
            '"', '\\', '/' -> out.append(esc)
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
              val hex = source.substring(index, minOf(index + 4, source.length))
              index += hex.length
              out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
            }
            else -> out.append(esc)
          }
        }
        else -> out.append(ch)
      }
    }
    return out.toString()
  }

  private fun readNumber(): JsonValue {
    val start = index
    if (peek('-')) {
      index++
    }
    while (index < source.length && source[index].isDigit()) {
      index++
    }
    if (peek('.')) {
      index++
      while (index < source.length && source[index].isDigit()) {
        index++
      }
    }
    return JsonNumber(source.substring(start, index).toDoubleOrNull() ?: 0.0)
  }

  private fun literal(token: String, value: JsonValue): JsonValue {
    index += token.length
    return value
  }

  private fun skip() {
    while (index < source.length && source[index].isWhitespace()) {
      index++
    }
  }

  private fun peek(expected: Char): Boolean {
    return index < source.length && source[index] == expected
  }
}

private fun jsonString(value: String): String {
  val escaped = buildString {
    value.forEach { ch ->
      when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(ch)
      }
    }
  }
  return "\"$escaped\""
}

private fun obj(vararg fields: Pair<String, String>): String {
  return fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
    "\"$key\":$value"
  }
}

private fun obj(fields: Map<String, String>): String {
  return fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
    "\"$key\":$value"
  }
}
