package com.hkparking.copilot

import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReportWireTest {
  private val calls = mutableListOf<String>()
  private val queued = ArrayDeque<HttpResult>()
  private val transport = object : ApiTransport {
    override suspend fun post(path: String, bodyJson: String): HttpResult {
      calls += bodyJson
      return queued.removeFirst()
    }
  }
  private val client = ApiClient(transport, delayMs = {})

  @Test
  fun wireIsNestedAndDropsCarparkAndPhoto() {
    queued += HttpResult(statusCode = 200, bodyJson = "{}")
    val outcome = runSuspend {
      client.submitTripReport(
        sessionId = "sess-fake-0001",
        carparkId = "CP_FAKE_001",
        ratingTag = "GOOD",
        perkStatusTag = "VALID",
        perkNotes = "",
        tripTimestampSec = 1792995600L,
      )
    }
    assertTrue(outcome.succeeded)
    assertEquals(
      """{"session_id":"sess-fake-0001", "trip_timestamp":1792995600, "feedback":{"rating_tag":"GOOD", "perk_status_tag":"VALID", "perk_notes":""}}""",
      calls.single(),
    )
  }

  @Test
  fun illegalEnumAndPhotoAreNotSent() {
    val bad = runSuspend {
      client.submitTripReport("sess-fake-0001", "CP_FAKE_001", "NOPE", "VALID", "", 1L)
    }
    val photo = runSuspend {
      client.submitTripReport("sess-fake-0001", "CP_FAKE_001", "GOOD", "VALID", "", 1L, photoUrl = "gs://fake/photo")
    }
    assertFalse(bad.succeeded)
    assertEquals(0, bad.attempts)
    assertFalse(photo.succeeded)
    assertEquals(0, photo.attempts)
    assertTrue(calls.isEmpty())
  }

  @Test
  fun notesKeepChineseBoundaryAndWholeEmoji() {
    val chinese = "測".repeat(141)
    val emoji = "\uD83D\uDE00"
    val odd = "測" + emoji.repeat(70)
    queued += HttpResult(statusCode = 200, bodyJson = "{}")
    queued += HttpResult(statusCode = 200, bodyJson = "{}")
    runSuspend { client.submitTripReport("s", "c", "OK", "NONE", chinese, 1L) }
    runSuspend { client.submitTripReport("s", "c", "BAD", "INVALID", odd, 1L) }
    val chineseNotes = notesOf(calls[0])
    val emojiNotes = notesOf(calls[1])
    assertEquals(140, chineseNotes.length)
    assertEquals("測".repeat(140), chineseNotes)
    assertTrue(emojiNotes.length <= 140)
    assertFalse(emojiNotes.last().code in 0xD800..0xDBFF)
    assertTrue(emojiNotes.endsWith(emoji))
  }
}

private fun notesOf(body: String): String {
  val key = "\"perk_notes\":\""
  val start = body.indexOf(key) + key.length
  return body.substring(start, body.indexOf("\"}", start))
}

private fun <T> runSuspend(block: suspend () -> T): T {
  val done = java.util.concurrent.CountDownLatch(1)
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) {
    result = it
    done.countDown()
  })
  done.await()
  return result!!.getOrThrow()
}
