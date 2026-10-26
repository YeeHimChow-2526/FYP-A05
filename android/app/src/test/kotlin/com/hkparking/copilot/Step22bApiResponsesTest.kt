package com.hkparking.copilot

import com.hkparking.copilot.di.HttpApiTransport
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiResponses
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.sim.FixtureApiTransport
import com.hkparking.copilot.voice.ZhHkTemplates
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step22bApiResponsesTest {
  @Test
  fun step22b_allRecommendFixturesParseWithExpectedFields() {
    val dir = mockDir()
    val jsonFiles = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList().orEmpty()
    // Step 3.4 added sync_driver_confirmed / trip_report_409_unconfirmed / today_trips_sample (+3).
    assertEquals(14, jsonFiles.size)
    val recommend = jsonFiles.map { it.name }.filter { it.startsWith("recommend_") && !it.contains("429") }.sorted()
    assertEquals(9, recommend.size)
    val counts = mapOf(
      "recommend_success_langham.json" to 1,
      "recommend_success_two_indoor.json" to 2,
      "recommend_crowd_yellow_flag.json" to 1,
      "recommend_empty_expand.json" to 0,
      "recommend_low_watermark.json" to 1,
      "recommend_meter_stale.json" to 1,
      "recommend_scarcity_await_reply.json" to 1,
      "recommend_scarcity_expanded.json" to 1,
      "recommend_source_degraded.json" to 2,
    )
    assertEquals(counts.keys.sorted(), recommend)
    recommend.forEach { name ->
      val parsed = ApiResponses.parseRecommend(readMock(name))
      assertNotNull(name, parsed)
      assertEquals("SUCCESS", parsed!!.status)
      assertEquals(counts.getValue(name), parsed.cards.size)
    }
    val langham = ApiResponses.parseRecommend(readMock("recommend_success_langham.json"))!!
    assertEquals("SUCCESS", langham.status)
    assertEquals(NativeConfig.SPEECH_LOCALE_DEFAULT, langham.speechLocale)
    assertFalse(langham.speechLocaleFallback)
    assertEquals("ok", langham.sourceStatus)
    assertEquals("2026-10-18T14:20:00+08:00", langham.sourceSnapshotTs)
    assertEquals("2026-10-18T14:20:41+08:00", langham.processedAt)
    assertEquals("2026-10-18T14:21:30+08:00", langham.serverNow)
    assertEquals(90, langham.dataAgeSeconds)
    assertFalse(langham.awaitVoiceReply)
    val card = langham.cards.single()
    assertEquals("CP_LANGHAM", card.carparkId)
    assertEquals("朗豪坊商場停車場", card.name)
    assertEquals("INDOOR", card.type)
    assertEquals(12, card.vacancies)
    assertEquals(180, card.distanceMeters)
    assertEquals("首小時 $32 / 消費滿 $200 免 1 小時", card.feeSummary)
    assertEquals("前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。", card.spokenCue)
    assertFalse(card.isStale)
    val stale = ApiResponses.parseRecommend(readMock("recommend_meter_stale.json"))!!
    assertEquals(-1, stale.cards.single().vacancies)
    assertTrue(stale.cards.single().isStale)
    assertEquals("degraded", ApiResponses.parseRecommend(readMock("recommend_source_degraded.json"))!!.sourceStatus)
    assertTrue(ApiResponses.parseRecommend(readMock("recommend_scarcity_await_reply.json"))!!.awaitVoiceReply)
    assertFalse(ApiResponses.parseRecommend(readMock("recommend_scarcity_expanded.json"))!!.awaitVoiceReply)
    assertFalse(ApiResponses.parseRecommend(readMock("recommend_empty_expand.json"))!!.awaitVoiceReply)
  }

  @Test
  fun step22b_minifiedReorderedBackendSampleParses() {
    val card = """{"carpark_id":"CP_LANGHAM","name":"朗豪坊商場停車場","type":"INDOOR","vacancies":12,"distance_meters":180,"fee_summary":"首小時 $32 / 消費滿 $200 免 1 小時","spoken_cue":"前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。","is_stale":false}"""
    val body = """{"speech_locale_fallback":true,"cards":[$card],"data_age_seconds":90,"server_now":"2026-10-18T14:21:30+08:00","processed_at":"2026-10-18T14:20:41+08:00","source_snapshot_ts":"2026-10-18T14:20:00+08:00","source_status":"ok","speech_locale":"zh-HK","status":"SUCCESS"}"""
    val parsed = ApiResponses.parseRecommend(body)
    assertNotNull(parsed)
    assertEquals("SUCCESS", parsed!!.status)
    assertEquals("zh-HK", parsed.speechLocale)
    assertTrue(parsed.speechLocaleFallback)
    assertEquals("ok", parsed.sourceStatus)
    assertEquals("2026-10-18T14:20:00+08:00", parsed.sourceSnapshotTs)
    assertEquals("2026-10-18T14:20:41+08:00", parsed.processedAt)
    assertEquals("2026-10-18T14:21:30+08:00", parsed.serverNow)
    assertEquals(90, parsed.dataAgeSeconds)
    assertFalse(parsed.awaitVoiceReply)
    val item = parsed.cards.single()
    assertEquals("CP_LANGHAM", item.carparkId)
    assertEquals("朗豪坊商場停車場", item.name)
    assertEquals("INDOOR", item.type)
    assertEquals(12, item.vacancies)
    assertEquals(180, item.distanceMeters)
    assertEquals("首小時 $32 / 消費滿 $200 免 1 小時", item.feeSummary)
    assertEquals("前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。", item.spokenCue)
    assertFalse(item.isStale)
  }

  @Test
  fun step22b_nestedUnknownFieldsAndEscapesDoNotDropCards() {
    val body = """{"status":"SUCCESS","cards":[{"carpark_id":"A1","name":"A\"{x}\\B","spoken_cue":"\u6717豪","extra":{"a":[1,{"b":"}"}],"c":null}},{"carpark_id":"A2","name":"Second"}]}"""
    val parsed = ApiResponses.parseRecommend(body)
    assertNotNull(parsed)
    assertEquals(2, parsed!!.cards.size)
    assertEquals("A\"{x}\\B", parsed.cards[0].name)
    assertEquals("朗豪", parsed.cards[0].spokenCue)
    assertEquals("A1", parsed.cards[0].carparkId)
    assertEquals("A2", parsed.cards[1].carparkId)
    assertEquals("Second", parsed.cards[1].name)
  }

  @Test
  fun step22b_missingOptionalFieldsUseSpecDefaults() {
    val parsed = ApiResponses.parseRecommend("""{"status":"SUCCESS","cards":[{"carpark_id":"X"}]}""")
    assertNotNull(parsed)
    assertEquals("SUCCESS", parsed!!.status)
    assertEquals(NativeConfig.SPEECH_LOCALE_DEFAULT, parsed.speechLocale)
    assertFalse(parsed.speechLocaleFallback)
    assertEquals("ok", parsed.sourceStatus)
    assertEquals("", parsed.sourceSnapshotTs)
    assertEquals("", parsed.processedAt)
    assertEquals("", parsed.serverNow)
    assertEquals(0, parsed.dataAgeSeconds)
    assertFalse(parsed.awaitVoiceReply)
    val card = parsed.cards.single()
    assertEquals("X", card.carparkId)
    assertEquals("", card.name)
    assertEquals("", card.type)
    assertEquals(-1, card.vacancies)
    assertEquals(0, card.distanceMeters)
    assertEquals("", card.feeSummary)
    assertEquals("", card.spokenCue)
    assertFalse(card.isStale)
    assertFalse(ApiResponses.parseRecommend("""{"status":"SUCCESS","await_voice_reply":"true","cards":[]}""")!!.awaitVoiceReply)
    assertEquals(3, ApiResponses.parseRecommend("""{"status":"SUCCESS","cards":[{"vacancies":3.0}]}""")!!.cards.single().vacancies)
    assertEquals(-1, ApiResponses.parseRecommend("""{"status":"SUCCESS","cards":[{"vacancies":3.5}]}""")!!.cards.single().vacancies)
  }

  @Test
  fun step22b_cardCueFallsBackToTopLevelCue() {
    val body = """{"status":"SUCCESS","spoken_cue":"T","cards":[{"carpark_id":"C1","spoken_cue":""},{"carpark_id":"C2","spoken_cue":"C2"}]}"""
    val parsed = ApiResponses.parseRecommend(body)!!
    assertEquals("T", parsed.cards[0].spokenCue)
    assertEquals("C2", parsed.cards[1].spokenCue)
    assertEquals("T", ApiResponses.buildResult(ApiClient.RECOMMEND, 200, body, emptyMap()).spokenCue)
    val overridden = """{"status":"SUCCESS","spoken_cue":"T","cards":[{"carpark_id":"C1","spoken_cue":"C1"},{"carpark_id":"C2","spoken_cue":"C2"}]}"""
    assertEquals("C1", ApiResponses.buildResult(ApiClient.RECOMMEND, 200, overridden, emptyMap()).spokenCue)
  }

  @Test
  fun step22b_invalidBodiesReturnNull() {
    val langham = readMock("recommend_success_langham.json")
    val inputs = listOf(
      "",
      "   ",
      "<html><body>Login</body></html>",
      "{}",
      """{"status":1}""",
      """{"status":""}""",
      """{"status":"SUCCESS","cards":{}}""",
      """{"status":"SUCCESS","cards":[1]}""",
      langham.dropLast(5),
      langham + "x",
      "[]",
      """{"status":"SUCCESS"""",
    )
    assertEquals(12, inputs.size)
    inputs.forEach { input ->
      assertNull(input, ApiResponses.parseRecommend(input))
    }
  }

  @Test
  fun step22b_recommend200UnparseableBecomesNetworkError() {
    val html = "<html><body>Login</body></html>"
    val failed = ApiResponses.buildResult(ApiClient.RECOMMEND, 200, html, emptyMap())
    assertEquals(0, failed.statusCode)
    assertTrue(failed.networkError)
    assertFalse(failed.timedOut)
    assertNull(failed.recommendResponse)
    assertEquals(html, failed.bodyJson)
    val empty = ApiResponses.buildResult(ApiClient.RECOMMEND, 200, readMock("recommend_empty_expand.json"), emptyMap())
    assertEquals(200, empty.statusCode)
    assertTrue(empty.recommendResponse!!.cards.isEmpty())
    assertFalse(empty.networkError)
  }

  @Test
  fun step22b_lockFull409CarriesTopLevelCue() {
    val raw = readMock("commit_lock_full_409.json")
    val locked = ApiResponses.buildResult(ApiClient.COMMIT, 409, raw, emptyMap())
    assertEquals(409, locked.statusCode)
    assertEquals("呢度滿咗", locked.spokenCue)
    val html = ApiResponses.buildResult(ApiClient.COMMIT, 409, "<html><body>Login</body></html>", emptyMap())
    assertNull(html.spokenCue)
    val committed = ApiResponses.buildResult(ApiClient.COMMIT, 200, "{}", emptyMap())
    assertEquals(200, committed.statusCode)
    assertFalse(committed.networkError)
    assertNull(committed.recommendResponse)
  }

  @Test
  fun step22b_rateLimit429HeaderThenBody() {
    val bodyFive = """{"retry_after_seconds":5}"""
    assertEquals(7L, ApiResponses.buildResult(ApiClient.RECOMMEND, 429, bodyFive, mapOf("Retry-After" to "7")).retryAfterSeconds)
    assertEquals(7L, ApiResponses.buildResult(ApiClient.RECOMMEND, 429, bodyFive, mapOf("retry-after" to "7")).retryAfterSeconds)
    assertEquals(5L, ApiResponses.buildResult(ApiClient.RECOMMEND, 429, bodyFive, emptyMap()).retryAfterSeconds)
    assertEquals(5L, ApiResponses.buildResult(ApiClient.RECOMMEND, 429, bodyFive, mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT")).retryAfterSeconds)
    assertNull(ApiResponses.buildResult(ApiClient.RECOMMEND, 429, """{"status":"RATE_LIMITED"}""", emptyMap()).retryAfterSeconds)
    assertNull(ApiResponses.buildResult(ApiClient.RECOMMEND, 429, """{"retry_after_seconds":-3}""", emptyMap()).retryAfterSeconds)
  }

  @Test
  fun step22b_fixtureTransportMatchesSharedBuilder() {
    val transport = FixtureApiTransport { name -> readMock(name) }
    transport.forceRateLimit(1)
    val limited = runSuspend { transport.post(ApiClient.RECOMMEND, "{}") }
    assertEquals(429, limited.statusCode)
    assertEquals(5L, limited.retryAfterSeconds)
    assertEquals(ApiResponses.buildResult(ApiClient.RECOMMEND, 429, readMock(FixtureApiTransport.RATE_LIMIT_FIXTURE), emptyMap()), limited)
    transport.armLockFull()
    val locked = runSuspend { transport.post(ApiClient.COMMIT, "{}") }
    assertEquals(409, locked.statusCode)
    assertEquals("呢度滿咗", locked.spokenCue)
    assertEquals(ApiResponses.buildResult(ApiClient.COMMIT, 409, readMock(FixtureApiTransport.LOCK_FULL_FIXTURE), emptyMap()), locked)
    transport.armScarcity(FixtureApiTransport.SCARCITY_EXPANDED_FIXTURE)
    val first = runSuspend { transport.post(ApiClient.RECOMMEND, """{"expand_scarcity":false}""") }
    assertTrue(first.recommendResponse!!.awaitVoiceReply)
    assertEquals(first.recommendResponse!!.cards.single().spokenCue, first.spokenCue)
    assertEquals(ApiResponses.buildResult(ApiClient.RECOMMEND, 200, readMock(FixtureApiTransport.SCARCITY_AWAIT_FIXTURE), emptyMap()), first)
    val expanded = runSuspend { transport.post(ApiClient.RECOMMEND, """{"expand_scarcity":true}""") }
    assertEquals("CP_SCARCE_EXPANDED", expanded.recommendResponse!!.cards.single().carparkId)
    assertEquals(ApiResponses.buildResult(ApiClient.RECOMMEND, 200, readMock(FixtureApiTransport.SCARCITY_EXPANDED_FIXTURE), emptyMap()), expanded)
  }

  @Test
  fun step22b_apiClientGradedRetryOnCaptivePortalBody() {
    val html = "<html><body>Login</body></html>"
    val transport = object : ApiTransport {
      override suspend fun post(path: String, bodyJson: String): HttpResult {
        return ApiResponses.buildResult(ApiClient.RECOMMEND, 200, html, emptyMap())
      }
    }
    val client = ApiClient(transport, TripStateMachine(), { _, _, _ -> }, {})
    val outcome = runSuspend {
      client.recommendAndRoute(
        RecommendRequest("sess", 22.3, 114.1, 22.31, 114.17, "朗豪坊", "SMART_HYBRID"),
        QueryTriggerMode.DRIVER_ACTIVE,
      )
    }
    assertFalse(outcome.succeeded)
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, outcome.attempts)
    assertTrue(outcome.spokenCues.contains(ZhHkTemplates.NETWORK_SLOW))
    assertTrue(outcome.spokenCues.contains(ZhHkTemplates.NETWORK_FAILED))
    assertNull(outcome.response)
  }

  @Test
  fun step22b_httpTransportAgainstLoopbackServer() {
    val server = LoopbackHttpServer()
    val langham = readMock("recommend_success_langham.json").toByteArray(Charsets.UTF_8)
    val lockFull = readMock("commit_lock_full_409.json").toByteArray(Charsets.UTF_8)
    val limitedBody = """{"retry_after_seconds":5}""".toByteArray(Charsets.UTF_8)
    val keys = mutableListOf<String?>()
    val types = mutableListOf<String?>()
    val bodies = mutableListOf<String>()
    var recommendHits = 0
    server.handle { exchange ->
      val requestText = exchange.requestBytes().toString(Charsets.UTF_8)
      keys += exchange.header(ApiClient.HEADER_API_KEY)
      types += exchange.header(ApiClient.HEADER_CONTENT_TYPE)
      bodies += requestText
      when {
        exchange.path() == ApiClient.COMMIT -> exchange.respond(409, lockFull, null)
        exchange.path() == ApiClient.RECOMMEND && recommendHits == 0 -> {
          recommendHits += 1
          exchange.respond(200, langham, null)
        }
        else -> exchange.respond(429, limitedBody, "3")
      }
    }
    server.start()
    try {
      val transport = HttpApiTransport("http://127.0.0.1:${server.port}")
      val sent = """{"dest_name":"朗豪坊"}"""
      val ok = runSuspend { transport.post(ApiClient.RECOMMEND, sent) }
      val conflict = runSuspend { transport.post(ApiClient.COMMIT, sent) }
      val limited = runSuspend { transport.post(ApiClient.RECOMMEND, sent) }
      assertEquals("朗豪坊商場停車場", ok.recommendResponse!!.cards.single().name)
      assertTrue(ok.recommendResponse!!.cards.single().feeSummary.contains("$32"))
      assertEquals(ApiClient.INTERNAL_API_KEY, keys.first())
      assertEquals("application/json", types.first())
      assertEquals(sent, bodies.first())
      assertTrue(bodies.first().contains("朗豪坊"))
      assertEquals("呢度滿咗", conflict.spokenCue)
      assertEquals(3L, limited.retryAfterSeconds)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun step22b_httpTransportBlankBaseUrlStillNetworkError() {
    val result = runSuspend { HttpApiTransport().post(ApiClient.RECOMMEND, "{}") }
    assertEquals(0, result.statusCode)
    assertTrue(result.networkError)
  }

  private fun mockDir(): java.io.File {
    val candidates = listOf(
      java.io.File("src/debug/assets/mock"),
      java.io.File("app/src/debug/assets/mock"),
      java.io.File("android/app/src/debug/assets/mock"),
    )
    return candidates.first { it.isDirectory }
  }

  private fun readMock(name: String): String = java.io.File(mockDir(), name).readText(Charsets.UTF_8)
}

private class LoopbackHttpServer {
  private val serverClass = Class.forName("com.sun.net.httpserver.HttpServer")
  private val exchangeClass = Class.forName("com.sun.net.httpserver.HttpExchange")
  private val server = serverClass.getMethod("create", java.net.InetSocketAddress::class.java, Int::class.javaPrimitiveType)
    .invoke(null, java.net.InetSocketAddress("127.0.0.1", 0), 0)

  fun handle(block: (Exchange) -> Unit) {
    val handlerClass = Class.forName("com.sun.net.httpserver.HttpHandler")
    val handler = java.lang.reflect.Proxy.newProxyInstance(handlerClass.classLoader, arrayOf(handlerClass)) { _, method, args ->
      if (method.name == "handle") block(Exchange(args[0]))
      null
    }
    serverClass.getMethod("createContext", String::class.java, handlerClass).invoke(server, "/", handler)
  }

  fun start() {
    serverClass.getMethod("start").invoke(server)
  }

  fun stop(delay: Int) {
    serverClass.getMethod("stop", Int::class.javaPrimitiveType).invoke(server, delay)
  }

  val port: Int
    get() {
      val address = serverClass.getMethod("getAddress").invoke(server) as java.net.InetSocketAddress
      return address.port
    }

  class Exchange(private val raw: Any) {
    private val type = Class.forName("com.sun.net.httpserver.HttpExchange")
    private val headersType = Class.forName("com.sun.net.httpserver.Headers")

    fun requestBytes(): ByteArray {
      val stream = type.getMethod("getRequestBody").invoke(raw) as java.io.InputStream
      return stream.readBytes()
    }

    fun header(name: String): String? {
      val headers = type.getMethod("getRequestHeaders").invoke(raw)
      return headersType.getMethod("getFirst", String::class.java).invoke(headers, name) as String?
    }

    fun path(): String {
      val uri = type.getMethod("getRequestURI").invoke(raw) as java.net.URI
      return uri.path
    }

    fun respond(status: Int, body: ByteArray, retryAfter: String?) {
      if (retryAfter != null) {
        val headers = type.getMethod("getResponseHeaders").invoke(raw)
        headersType.getMethod("add", String::class.java, String::class.java).invoke(headers, "Retry-After", retryAfter)
      }
      type.getMethod("sendResponseHeaders", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(raw, status, body.size.toLong())
      val stream = type.getMethod("getResponseBody").invoke(raw) as java.io.OutputStream
      stream.use { it.write(body) }
      type.getMethod("close").invoke(raw)
    }
  }
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
