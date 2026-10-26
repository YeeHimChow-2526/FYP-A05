package com.hkparking.copilot.trip

import com.hkparking.copilot.NativeConfig
import java.time.Instant
import java.time.ZoneId

/** Thin SharedPreferences / memory map seam for TodayTripStore. */
interface PrefsPort {
  fun getString(key: String): String?
  fun putString(key: String, value: String)
  fun remove(key: String)
}

class MemoryPrefsPort : PrefsPort {
  private val map = linkedMapOf<String, String>()
  override fun getString(key: String): String? = map[key]
  override fun putString(key: String, value: String) {
    map[key] = value
  }
  override fun remove(key: String) {
    map.remove(key)
  }
}

class SharedPrefsPort(private val prefs: android.content.SharedPreferences) : PrefsPort {
  override fun getString(key: String): String? = prefs.getString(key, null)
  override fun putString(key: String, value: String) {
    prefs.edit().putString(key, value).apply()
  }
  override fun remove(key: String) {
    prefs.edit().remove(key).apply()
  }
}

data class TodayTripRecord(
  val sessionId: String,
  val carparkId: String,
  val carparkName: String,
  val parkedAtEpochMs: Long,
  val status: String,
  val confirmSyncPending: Boolean,
  val feedbackSubmitted: Boolean,
)

class TodayTripStore(
  private val prefs: PrefsPort,
  private val hktDateOf: (Long) -> String = Companion::defaultHktDateOf,
  private val listener: Listener? = null,
) {
  fun interface Listener {
    fun onChanged()
  }

  fun list(nowMs: Long): List<TodayTripRecord> {
    purgeIfNewHktDay(nowMs)
    return load()
  }

  fun asBridgeMaps(nowMs: Long): List<Map<String, Any?>> {
    return list(nowMs).map { it.toBridgeMap() }
  }

  fun upsertCandidate(
    sessionId: String,
    carparkId: String,
    carparkName: String,
    parkedAtEpochMs: Long,
  ) {
    purgeIfNewHktDay(parkedAtEpochMs)
    val records = load().toMutableList()
    val idx = records.indexOfFirst { it.sessionId == sessionId }
    if (idx >= 0) {
      val existing = records[idx]
      if (existing.status == STATUS_CONFIRMED) {
        // RT-5: preserve CONFIRMED / pending / feedback; only refresh name/id.
        records[idx] = existing.copy(
          carparkId = carparkId.ifBlank { existing.carparkId },
          carparkName = carparkName.ifBlank { existing.carparkName },
        )
      } else {
        records[idx] = existing.copy(
          carparkId = carparkId,
          carparkName = carparkName,
          parkedAtEpochMs = parkedAtEpochMs,
          status = STATUS_CANDIDATE,
          confirmSyncPending = false,
          feedbackSubmitted = false,
        )
        // Move updated candidate to front (newest-first).
        val updated = records.removeAt(idx)
        records.add(0, updated)
      }
    } else {
      records.add(
        0,
        TodayTripRecord(
          sessionId = sessionId,
          carparkId = carparkId,
          carparkName = carparkName,
          parkedAtEpochMs = parkedAtEpochMs,
          status = STATUS_CANDIDATE,
          confirmSyncPending = false,
          feedbackSubmitted = false,
        ),
      )
    }
    save(records, hktDateOf(parkedAtEpochMs))
    notifyChanged()
  }

  fun markConfirmed(sessionId: String, syncPending: Boolean): Boolean {
    purgeIfNewHktDay(System.currentTimeMillis())
    val records = load().toMutableList()
    val idx = records.indexOfFirst { it.sessionId == sessionId }
    if (idx < 0) return false
    records[idx] = records[idx].copy(
      status = STATUS_CONFIRMED,
      confirmSyncPending = syncPending,
    )
    save(records, prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY))
    notifyChanged()
    return true
  }

  fun markSyncSent(sessionId: String) {
    val records = load().toMutableList()
    val idx = records.indexOfFirst { it.sessionId == sessionId }
    if (idx < 0) return
    records[idx] = records[idx].copy(confirmSyncPending = false)
    save(records, prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY))
    notifyChanged()
  }

  fun markFeedbackSubmitted(sessionId: String): Boolean {
    val records = load().toMutableList()
    val idx = records.indexOfFirst { it.sessionId == sessionId }
    if (idx < 0) return false
    records[idx] = records[idx].copy(feedbackSubmitted = true)
    save(records, prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY))
    notifyChanged()
    return true
  }

  fun pendingSyncSessionIds(): List<String> {
    return load().filter { it.confirmSyncPending }.map { it.sessionId }
  }

  fun find(sessionId: String): TodayTripRecord? = load().firstOrNull { it.sessionId == sessionId }

  fun clearAll() {
    prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, "[]")
    // Keep / clear date: clear date so next write sets fresh day without mistaking rollover.
    prefs.remove(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY)
    notifyChanged()
  }

  /**
   * RT-6: if storedDate exists and differs from today → clear JSON and set today;
   * if storedDate is null → only set date, do not clear.
   */
  fun purgeIfNewHktDay(nowMs: Long) {
    val today = hktDateOf(nowMs)
    val stored = prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY)
    if (stored == null) {
      prefs.putString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, today)
      return
    }
    if (stored != today) {
      prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, "[]")
      prefs.putString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, today)
      notifyChanged()
    }
  }

  private fun load(): List<TodayTripRecord> {
    val raw = prefs.getString(NativeConfig.TODAY_TRIPS_JSON_KEY) ?: return emptyList()
    return decodeRecords(raw)
  }

  private fun save(records: List<TodayTripRecord>, hktDate: String?) {
    prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, encodeRecords(records))
    if (hktDate != null) {
      prefs.putString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, hktDate)
    }
  }

  private fun notifyChanged() {
    runCatching { listener?.onChanged() }
  }

  companion object {
    const val STATUS_CANDIDATE = "CANDIDATE"
    const val STATUS_CONFIRMED = "CONFIRMED"
    private val HKT: ZoneId = ZoneId.of("Asia/Hong_Kong")

    fun defaultHktDateOf(epochMs: Long): String =
      Instant.ofEpochMilli(epochMs).atZone(HKT).toLocalDate().toString()

    fun TodayTripRecord.toBridgeMap(): Map<String, Any?> = mapOf(
      "sessionId" to sessionId,
      "carparkId" to carparkId,
      "carparkName" to carparkName,
      "parkedAtEpochMs" to parkedAtEpochMs,
      "status" to status,
      "confirmSyncPending" to confirmSyncPending,
      "feedbackSubmitted" to feedbackSubmitted,
    )

    fun encodeRecords(records: List<TodayTripRecord>): String {
      if (records.isEmpty()) return "[]"
      return records.joinToString(prefix = "[", postfix = "]") { r ->
        "{" +
          "\"sessionId\":${jsonStr(r.sessionId)}," +
          "\"carparkId\":${jsonStr(r.carparkId)}," +
          "\"carparkName\":${jsonStr(r.carparkName)}," +
          "\"parkedAtEpochMs\":${r.parkedAtEpochMs}," +
          "\"status\":${jsonStr(r.status)}," +
          "\"confirmSyncPending\":${r.confirmSyncPending}," +
          "\"feedbackSubmitted\":${r.feedbackSubmitted}" +
          "}"
      }
    }

    fun decodeRecords(raw: String): List<TodayTripRecord> {
      val trimmed = raw.trim()
      if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
      val objects = splitJsonObjects(trimmed) ?: return emptyList()
      return objects.mapNotNull { obj ->
        val sessionId = stringField(obj, "sessionId") ?: return@mapNotNull null
        TodayTripRecord(
          sessionId = sessionId,
          carparkId = stringField(obj, "carparkId").orEmpty(),
          carparkName = stringField(obj, "carparkName").orEmpty(),
          parkedAtEpochMs = longField(obj, "parkedAtEpochMs") ?: 0L,
          status = stringField(obj, "status") ?: STATUS_CANDIDATE,
          confirmSyncPending = boolField(obj, "confirmSyncPending") ?: false,
          feedbackSubmitted = boolField(obj, "feedbackSubmitted") ?: false,
        )
      }
    }

    private fun jsonStr(value: String): String =
      "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun splitJsonObjects(arrayJson: String): List<String>? {
      val start = arrayJson.indexOf('[')
      val end = arrayJson.lastIndexOf(']')
      if (start < 0 || end <= start) return null
      val body = arrayJson.substring(start + 1, end).trim()
      if (body.isEmpty()) return emptyList()
      val out = mutableListOf<String>()
      var depth = 0
      var begin = -1
      var inString = false
      var escape = false
      for (i in body.indices) {
        val c = body[i]
        if (inString) {
          if (escape) {
            escape = false
          } else if (c == '\\') {
            escape = true
          } else if (c == '"') {
            inString = false
          }
          continue
        }
        when (c) {
          '"' -> inString = true
          '{' -> {
            if (depth == 0) begin = i
            depth += 1
          }
          '}' -> {
            depth -= 1
            if (depth == 0 && begin >= 0) {
              out += body.substring(begin, i + 1)
              begin = -1
            }
          }
        }
      }
      return out
    }

    private fun stringField(json: String, key: String): String? =
      Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json)?.groupValues?.get(1)
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")

    private fun longField(json: String, key: String): Long? =
      Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun boolField(json: String, key: String): Boolean? =
      Regex(""""$key"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
  }
}
