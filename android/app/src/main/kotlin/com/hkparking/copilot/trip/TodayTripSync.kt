package com.hkparking.copilot.trip

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.di.NetworkStatusProvider
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.CallOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

data class ConfirmOutcome(
  val ok: Boolean,
  val syncPosted: Boolean,
  val reason: String? = null,
)

data class ReportOutcome(
  val ok: Boolean,
  val posted: Boolean,
  val reason: String? = null,
)

/**
 * Non-suspend sync API (RT-1). Mirrors ApiClient endpoints without kotlinx-coroutines.
 * Implementations that wrap ApiClient use [runSuspendBlocking] (stdlib startCoroutine + latch).
 */
interface SyncApi {
  fun syncDriverConfirmed(sessionId: String, timestampSec: Long): CallOutcome
  fun submitTripReport(
    sessionId: String,
    carparkId: String,
    ratingTag: String,
    perkStatusTag: String,
    perkNotes: String,
    tripTimestampSec: Long,
  ): CallOutcome
}

class ApiClientSyncApi(private val client: ApiClient) : SyncApi {
  override fun syncDriverConfirmed(sessionId: String, timestampSec: Long): CallOutcome =
    runSuspendBlocking { client.syncDriverConfirmed(sessionId, timestampSec) }

  override fun submitTripReport(
    sessionId: String,
    carparkId: String,
    ratingTag: String,
    perkStatusTag: String,
    perkNotes: String,
    tripTimestampSec: Long,
  ): CallOutcome = runSuspendBlocking {
    client.submitTripReport(
      sessionId = sessionId,
      carparkId = carparkId,
      ratingTag = ratingTag,
      perkStatusTag = perkStatusTag,
      perkNotes = perkNotes,
      tripTimestampSec = tripTimestampSec,
      photoUrl = null,
    )
  }
}

class TodayTripSync(
  private val store: TodayTripStore,
  private val api: SyncApi,
  private val network: NetworkStatusProvider,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  private val networkExecutor: Executor = Executor { it.run() },
) {
  fun confirm(sessionId: String): ConfirmOutcome {
    store.purgeIfNewHktDay(nowMs())
    if (sessionId.isBlank()) return ConfirmOutcome(ok = false, syncPosted = false, reason = "BLANK_SESSION")
    val existing = store.find(sessionId)
      ?: return ConfirmOutcome(ok = false, syncPosted = false, reason = "NOT_FOUND")

    // Already confirmed and sync already sent → idempotent, optional re-flush if still pending.
    if (existing.status == TodayTripStore.STATUS_CONFIRMED && !existing.confirmSyncPending) {
      return ConfirmOutcome(ok = true, syncPosted = false, reason = "ALREADY_CONFIRMED")
    }

    // RT-1: mark confirmed+pending on calling thread first (triggers onChanged).
    store.markConfirmed(sessionId, syncPending = true)

    if (!network.isOnline()) {
      return ConfirmOutcome(ok = true, syncPosted = false, reason = "OFFLINE")
    }

    var posted = false
    networkExecutor.execute {
      val record = store.find(sessionId) ?: return@execute
      val ts = if (record.parkedAtEpochMs > 0L) {
        record.parkedAtEpochMs / 1000L
      } else {
        nowMs() / 1000L
      }
      val outcome = runCatching { api.syncDriverConfirmed(sessionId, ts) }.getOrNull()
      if (outcome != null && isHttp2xx(outcome)) {
        store.markSyncSent(sessionId)
        posted = true
      }
      // Failure → leave confirmSyncPending=true for later flush.
    }
    return ConfirmOutcome(ok = true, syncPosted = posted, reason = null)
  }

  fun flushPendingConfirms() {
    store.purgeIfNewHktDay(nowMs())
    if (!network.isOnline()) return
    val ids = store.pendingSyncSessionIds()
    for (id in ids) {
      syncOne(id)
    }
  }

  fun submitReport(payloadJson: String): ReportOutcome {
    store.purgeIfNewHktDay(nowMs())
    val parsed = parseTripReportPayload(payloadJson)
      ?: return ReportOutcome(ok = false, posted = false, reason = "BAD_JSON")

    val sessionId = parsed.sessionId
    if (sessionId.isBlank()) {
      return ReportOutcome(ok = false, posted = false, reason = "MISSING_SESSION")
    }

    val record = store.find(sessionId)
      ?: return ReportOutcome(ok = false, posted = false, reason = "NOT_FOUND")

    if (record.feedbackSubmitted) {
      return ReportOutcome(ok = true, posted = false, reason = "ALREADY_SUBMITTED")
    }

    if (record.status != TodayTripStore.STATUS_CONFIRMED) {
      return ReportOutcome(ok = false, posted = false, reason = "NOT_CONFIRMED")
    }

    // RT-2: if still pending, try flush first; abort without trip-report if still pending.
    if (record.confirmSyncPending) {
      if (network.isOnline()) {
        syncOne(sessionId)
      }
      val after = store.find(sessionId)
      if (after == null || after.confirmSyncPending) {
        return ReportOutcome(ok = false, posted = false, reason = "CONFIRM_SYNC_PENDING")
      }
    }

    val carparkId = parsed.carparkId.ifBlank { record.carparkId }
    val tripTs = if (parsed.tripTimestampSec > 0L) {
      parsed.tripTimestampSec
    } else {
      record.parkedAtEpochMs / 1000L
    }
    val notes = parsed.perkNotes.take(NativeConfig.TODAY_TRIP_NOTES_MAX_CHARS)

    val outcome = runCatching {
      api.submitTripReport(
        sessionId = sessionId,
        carparkId = carparkId,
        ratingTag = parsed.ratingTag,
        perkStatusTag = parsed.perkStatusTag,
        perkNotes = notes,
        tripTimestampSec = tripTs,
      )
    }.getOrNull()

    // RT-2: only HTTP 2xx marks feedback; 409/404/network keep feedbackSubmitted=false.
    if (outcome != null && isHttp2xx(outcome)) {
      store.markFeedbackSubmitted(sessionId)
      return ReportOutcome(ok = true, posted = true, reason = null)
    }
    val reason = when (outcome?.httpStatus) {
      409 -> "CONFLICT"
      404 -> "NOT_FOUND_REMOTE"
      else -> "NETWORK_OR_HTTP"
    }
    return ReportOutcome(ok = false, posted = false, reason = reason)
  }

  private fun syncOne(sessionId: String) {
    if (!network.isOnline()) return
    val record = store.find(sessionId) ?: return
    if (!record.confirmSyncPending) return
    val ts = if (record.parkedAtEpochMs > 0L) record.parkedAtEpochMs / 1000L else nowMs() / 1000L
    // Run on calling thread for flush (tests assert order immediately).
    val outcome = runCatching { api.syncDriverConfirmed(sessionId, ts) }.getOrNull()
    if (outcome != null && isHttp2xx(outcome)) {
      store.markSyncSent(sessionId)
    }
  }

  private fun isHttp2xx(outcome: CallOutcome): Boolean {
    val code = outcome.httpStatus
    return when {
      code != null -> code in 200..299
      else -> outcome.succeeded
    }
  }

  companion object {
    data class ParsedReport(
      val sessionId: String,
      val carparkId: String,
      val ratingTag: String,
      val perkStatusTag: String,
      val perkNotes: String,
      val tripTimestampSec: Long,
    )

    fun parseTripReportPayload(payloadJson: String): ParsedReport? {
      val raw = payloadJson.trim()
      if (raw.isEmpty() || (!raw.startsWith("{") && !raw.startsWith("["))) return null
      return runCatching {
        ParsedReport(
          sessionId = stringField(raw, "session_id")
            ?: stringField(raw, "sessionId")
            ?: "",
          carparkId = stringField(raw, "carpark_id")
            ?: stringField(raw, "carparkId")
            ?: "",
          ratingTag = stringField(raw, "rating_tag")
            ?: stringField(raw, "ratingTag")
            ?: "",
          perkStatusTag = stringField(raw, "perk_status_tag")
            ?: stringField(raw, "perkStatusTag")
            ?: "",
          perkNotes = stringField(raw, "perk_notes")
            ?: stringField(raw, "perkNotes")
            ?: "",
          tripTimestampSec = longField(raw, "trip_timestamp")
            ?: longField(raw, "tripTimestamp")
            ?: 0L,
        )
      }.getOrNull()
    }

    private fun stringField(json: String, key: String): String? =
      Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json)?.groupValues?.get(1)
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")

    private fun longField(json: String, key: String): Long? =
      Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
  }
}

/** Run a suspend block to completion without kotlinx-coroutines (mirror FBS launchApi). */
fun <T> runSuspendBlocking(block: suspend () -> T): T {
  val latch = CountDownLatch(1)
  var result: Result<T>? = null
  block.startCoroutine(
    Continuation(EmptyCoroutineContext) {
      result = it
      latch.countDown()
    },
  )
  latch.await(60, TimeUnit.SECONDS)
  return result!!.getOrThrow()
}
