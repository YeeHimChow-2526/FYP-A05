package com.hkparking.copilot

import com.hkparking.copilot.di.HttpApiTransport
import com.hkparking.copilot.di.NetworkStatusProvider
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiEndpointPolicy
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.trip.ApiClientSyncApi
import com.hkparking.copilot.trip.MemoryPrefsPort
import com.hkparking.copilot.trip.TodayTripStore
import com.hkparking.copilot.trip.TodayTripSync
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Step 3.5 (SC-018): HttpApiTransport against a JDK loopback server (no new deps).
 * Covers headers/key, JSON round-trip, status mapping per spec error tables, timeout, refused, blank config.
 */
class Step35HttpTransportTest {
  private val key = "test-key-35"

  @Test
  fun step35_headersKeyAndJsonRoundTrip() {
    val server = Step35Server()
    val langham = readMock("recommend_success_langham.json")
    val seen = CopyOnWriteArrayList<Step35Server.Seen>()
    server.handle { ex ->
      seen += ex.seen()
      ex.respond(200, langham)
    }
    server.start()
    try {
      val transport = HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true)
      val sent = """{"session_id":"s-35","dest_name":"朗豪坊","origin_lat":22.3,"expand_scarcity":false}"""
      val result = runBlocking35 { transport.post(ApiClient.RECOMMEND, sent) }
      val first = seen.single()
      assertEquals("POST", first.method)
      assertEquals(ApiClient.RECOMMEND, first.path)
      assertEquals(key, first.headers[ApiClient.HEADER_API_KEY])
      assertEquals("application/json", first.headers[ApiClient.HEADER_CONTENT_TYPE])
      assertEquals("application/json", first.headers["Accept"])
      assertEquals(sent, first.body)
      assertEquals(200, result.statusCode)
      val card = result.recommendResponse!!.cards.single()
      assertEquals("CP_LANGHAM", card.carparkId)
      assertEquals("朗豪坊商場停車場", card.name)
      assertEquals("2026-10-18T14:21:30+08:00", result.recommendResponse!!.serverNow)
      assertEquals(90, result.recommendResponse!!.dataAgeSeconds)
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_apiClientBodiesReachServerWithSpecFields() {
    val server = Step35Server()
    val seen = CopyOnWriteArrayList<Step35Server.Seen>()
    server.handle { ex ->
      seen += ex.seen()
      ex.respond(200, """{"status":"OK"}""")
    }
    server.start()
    try {
      val client = ApiClient(HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true), delayMs = {})
      assertTrue(runBlocking35 { client.commitSession("s-1", "CP_LANGHAM") }.succeeded)
      assertTrue(runBlocking35 { client.releaseSession("s-1", "CP_LANGHAM") }.succeeded)
      assertTrue(runBlocking35 { client.syncDriverConfirmed("s-1", 1761444000L) }.succeeded)
      assertTrue(runBlocking35 { client.submitCrowdReport("s-1", "CP_LANGHAM", "SPOT_TAKEN", 1761444000L) }.succeeded)
      assertTrue(runBlocking35 { client.submitTripReport("s-1", "CP_LANGHAM", "GOOD", "VALID", "", 1761444000L) }.succeeded)
      val byPath = seen.associateBy { it.path }
      assertEquals(
        listOf(ApiClient.COMMIT, ApiClient.RELEASE, ApiClient.SYNC, ApiClient.CROWD, ApiClient.TRIP),
        seen.map { it.path },
      )
      assertTrue(byPath.getValue(ApiClient.COMMIT).body.contains("\"target_carpark_id\":\"CP_LANGHAM\""))
      assertTrue(byPath.getValue(ApiClient.RELEASE).body.contains("\"reason\":\"PARKED_CONFIRMED\""))
      assertTrue(byPath.getValue(ApiClient.SYNC).body.contains("\"event_type\":\"DRIVER_CONFIRMED\""))
      assertTrue(byPath.getValue(ApiClient.SYNC).body.contains("\"timestamp\":1761444000"))
      assertTrue(byPath.getValue(ApiClient.CROWD).body.contains("\"report_type\":\"SPOT_TAKEN\""))
      assertTrue(byPath.getValue(ApiClient.TRIP).body.contains("\"rating_tag\":\"GOOD\""))
      assertFalse(byPath.getValue(ApiClient.TRIP).body.contains("photo_url"))
      assertTrue(seen.all { it.headers[ApiClient.HEADER_API_KEY] == key })
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_commit409LockFullReadsSpokenCueNoRetry() {
    val server = Step35Server()
    val hits = AtomicInteger()
    server.handle { ex ->
      hits.incrementAndGet()
      ex.respond(409, readMock("commit_lock_full_409.json"))
    }
    server.start()
    try {
      val client = ApiClient(HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true), delayMs = {})
      val outcome = runBlocking35 { client.commitSession("s-1", "CP_LANGHAM") }
      assertFalse(outcome.succeeded)
      assertEquals(409, outcome.httpStatus)
      assertEquals("呢度滿咗", outcome.actionSpokenCue)
      assertEquals(1, outcome.attempts)
      assertEquals(1, hits.get())
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_tripReport409And404AreSilentNoRetry() {
    val server = Step35Server()
    val hits = AtomicInteger()
    val codes = ArrayDeque(listOf(409, 404))
    server.handle { ex ->
      hits.incrementAndGet()
      val code = synchronized(codes) { codes.removeFirst() }
      ex.respond(code, if (code == 409) readMock("trip_report_409_unconfirmed.json") else """{"status":"NOT_FOUND"}""")
    }
    server.start()
    try {
      val client = ApiClient(HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true), delayMs = {})
      val conflict = runBlocking35 { client.submitTripReport("s-1", "CP", "OK", "NONE", "", 1L) }
      assertEquals(409, conflict.httpStatus)
      assertFalse(conflict.succeeded)
      assertEquals(1, conflict.attempts)
      assertNull(conflict.actionSpokenCue)
      assertTrue(conflict.spokenCues.isEmpty())
      val missing = runBlocking35 { client.submitTripReport("s-1", "CP", "OK", "NONE", "", 1L) }
      assertEquals(404, missing.httpStatus)
      assertFalse(missing.succeeded)
      assertEquals(1, missing.attempts)
      assertEquals(2, hits.get())
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_429RetryAfterHeaderWaitsThenRetriesOnce() {
    val server = Step35Server()
    val hits = AtomicInteger()
    server.handle { ex ->
      if (hits.incrementAndGet() == 1) {
        ex.respond(429, readMock("recommend_rate_limited_429.json"), retryAfter = "3")
      } else {
        ex.respond(200, readMock("recommend_success_langham.json"))
      }
    }
    server.start()
    try {
      val waits = mutableListOf<Long>()
      val client = ApiClient(
        HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true),
        delayMs = { waits += it },
      )
      val outcome = runBlocking35 { client.recommendAndRoute(sampleRequest()) }
      assertTrue(outcome.succeeded)
      assertEquals(listOf(3000L), waits)
      assertEquals(2, hits.get())
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_429WithoutHeaderUsesBodyRetryAfter() {
    val server = Step35Server()
    server.handle { ex -> ex.respond(429, readMock("recommend_rate_limited_429.json")) }
    server.start()
    try {
      val result = runBlocking35 {
        HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true).post(ApiClient.RECOMMEND, "{}")
      }
      assertEquals(429, result.statusCode)
      assertEquals(5L, result.retryAfterSeconds)
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_5xxIsGradedRetryThenGrey() {
    val server = Step35Server()
    val hits = AtomicInteger()
    server.handle { ex ->
      hits.incrementAndGet()
      ex.respond(503, """{"error":"UNAVAILABLE"}""")
    }
    server.start()
    try {
      val waits = mutableListOf<Long>()
      val client = ApiClient(
        HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true),
        delayMs = { waits += it },
      )
      val outcome = runBlocking35 { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE) }
      assertFalse(outcome.succeeded)
      assertEquals(NativeConfig.API_MAX_ATTEMPTS, hits.get())
      assertEquals(List(NativeConfig.API_MAX_ATTEMPTS - 1) { NativeConfig.API_RETRY_INTERVAL_MS }, waits)
      assertEquals(503, outcome.httpStatus)
      assertTrue(outcome.spokenCues.contains(com.hkparking.copilot.voice.ZhHkTemplates.NETWORK_FAILED))
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_malformedRecommendJsonIsNetworkError() {
    val server = Step35Server()
    server.handle { ex -> ex.respond(200, "{not json") }
    server.start()
    try {
      val result = runBlocking35 {
        HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true).post(ApiClient.RECOMMEND, "{}")
      }
      assertEquals(0, result.statusCode)
      assertTrue(result.networkError)
      assertNull(result.recommendResponse)
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_slowServerTimesOutWithinApiTimeout() {
    val server = Step35Server()
    server.handle { ex ->
      Thread.sleep(NativeConfig.API_TIMEOUT_MS + 2000L)
      runCatching { ex.respond(200, readMock("recommend_success_langham.json")) }
    }
    server.start()
    try {
      val started = System.nanoTime()
      val result = runBlocking35 {
        HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true).post(ApiClient.RECOMMEND, "{}")
      }
      val elapsedMs = (System.nanoTime() - started) / 1_000_000
      assertTrue(result.timedOut)
      assertEquals(0, result.statusCode)
      assertTrue(elapsedMs >= NativeConfig.API_TIMEOUT_MS - 100, "elapsed=$elapsedMs")
      assertTrue(elapsedMs < NativeConfig.API_TIMEOUT_MS + 1000, "elapsed=$elapsedMs")
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_connectionRefusedIsNetworkError() {
    val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val result = runBlocking35 {
      HttpApiTransport("http://127.0.0.1:$port", apiKey = key, allowLocalCleartext = true).post(ApiClient.SYNC, "{}")
    }
    assertEquals(0, result.statusCode)
    assertTrue(result.networkError)
    assertFalse(result.timedOut)
  }

  @Test
  fun step35_blankOrInvalidConfigIsOfflineWithoutHittingServer() {
    val server = Step35Server()
    val hits = AtomicInteger()
    server.handle { ex ->
      hits.incrementAndGet()
      ex.respond(200, "{}")
    }
    server.start()
    try {
      val cases = listOf(
        HttpApiTransport("", apiKey = key, allowLocalCleartext = true),
        HttpApiTransport("   ", apiKey = key, allowLocalCleartext = true),
        HttpApiTransport(server.url(), apiKey = "", allowLocalCleartext = true),
        // release: http to loopback is still refused.
        HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = false),
        HttpApiTransport("not a url", apiKey = key, allowLocalCleartext = true),
      )
      for (transport in cases) {
        val result = runBlocking35 { transport.post(ApiClient.RECOMMEND, "{}") }
        assertEquals(0, result.statusCode)
        assertTrue(result.networkError)
      }
      assertEquals(0, hits.get())
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_endpointPolicyHttpsOnlyExceptDebugLoopback() {
    fun reason(url: String, debug: Boolean, k: String = key) = ApiEndpointPolicy.check(url, k, debug).reason
    assertEquals(ApiEndpointPolicy.Reason.OK, reason("https://api.example.hk", debug = false))
    assertEquals(ApiEndpointPolicy.Reason.OK, reason("https://api.example.hk/base/", debug = true))
    assertEquals("https://api.example.hk/base", ApiEndpointPolicy.check("https://api.example.hk/base/", key, false).normalizedBaseUrl)
    assertEquals(ApiEndpointPolicy.Reason.CLEARTEXT_NOT_ALLOWED, reason("http://api.example.hk", debug = true))
    assertEquals(ApiEndpointPolicy.Reason.CLEARTEXT_NOT_ALLOWED, reason("http://192.168.1.5:8080", debug = true))
    assertEquals(ApiEndpointPolicy.Reason.CLEARTEXT_NOT_ALLOWED, reason("http://127.0.0.1:8080", debug = false))
    assertEquals(ApiEndpointPolicy.Reason.OK, reason("http://127.0.0.1:8080", debug = true))
    assertEquals(ApiEndpointPolicy.Reason.OK, reason("http://localhost:8080", debug = true))
    assertEquals(ApiEndpointPolicy.Reason.OK, reason("http://10.0.2.2:8080", debug = true))
    assertEquals(ApiEndpointPolicy.Reason.UNSUPPORTED_SCHEME, reason("ftp://api.example.hk", debug = true))
    assertEquals(ApiEndpointPolicy.Reason.BAD_URL, reason("https://api.example.hk?x=1", debug = false))
    assertEquals(ApiEndpointPolicy.Reason.BAD_URL, reason("https://user:pw@api.example.hk", debug = false))
    assertEquals(ApiEndpointPolicy.Reason.BLANK_URL, reason("", debug = true))
    assertEquals(ApiEndpointPolicy.Reason.BLANK_KEY, reason("https://api.example.hk", debug = false, k = " "))
  }

  @Test
  fun step35_redirectIsNotFollowed() {
    val server = Step35Server()
    val hits = AtomicInteger()
    server.handle { ex ->
      hits.incrementAndGet()
      ex.respond(302, "", location = "http://127.0.0.1:1/elsewhere")
    }
    server.start()
    try {
      val client = ApiClient(HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true), delayMs = {})
      val outcome = runBlocking35 { client.syncDriverConfirmed("s-1", 1L) }
      assertFalse(outcome.succeeded)
      assertEquals(302, outcome.httpStatus)
      assertEquals(1, hits.get())
    } finally {
      server.stop()
    }
  }

  @Test
  fun step35_outboxConfirmStaysPendingUntilServerOkThenPurgedAtHktMidnight() {
    val server = Step35Server()
    val serverUp = java.util.concurrent.atomic.AtomicBoolean(false)
    val syncBodies = CopyOnWriteArrayList<String>()
    server.handle { ex ->
      val seen = ex.seen()
      if (!serverUp.get()) {
        ex.respond(503, "{}")
      } else {
        syncBodies += seen.body
        ex.respond(200, readMock("sync_driver_confirmed.json"))
      }
    }
    server.start()
    try {
      var day = "2026-10-26"
      val store = TodayTripStore(MemoryPrefsPort(), hktDateOf = { day })
      store.upsertCandidate("sess-35", "CP_LANGHAM", "朗豪坊", 1761444000000L)
      val client = ApiClient(HttpApiTransport(server.url(), apiKey = key, allowLocalCleartext = true), delayMs = {})
      val sync = TodayTripSync(store, ApiClientSyncApi(client), NetworkStatusProvider { true }, nowMs = { 1761444000000L })
      sync.confirm("sess-35")
      assertTrue(store.find("sess-35")!!.confirmSyncPending)
      serverUp.set(true)
      sync.flushPendingConfirms()
      assertFalse(store.find("sess-35")!!.confirmSyncPending)
      assertTrue(syncBodies.single().contains("\"session_id\":\"sess-35\""))
      assertTrue(syncBodies.single().contains("\"timestamp\":1761444000"))
      day = "2026-10-27"
      assertTrue(store.list(1761530400000L).isEmpty())
    } finally {
      server.stop()
    }
  }

  private fun sampleRequest() = com.hkparking.copilot.net.RecommendRequest(
    sessionId = "s-35",
    originLat = 22.318,
    originLng = 114.169,
    destLat = 22.318,
    destLng = 114.168,
    destName = "朗豪坊",
    preference = "SMART_HYBRID",
  )

  private fun readMock(name: String): String {
    val dir = listOf(
      java.io.File("src/debug/assets/mock"),
      java.io.File("app/src/debug/assets/mock"),
      java.io.File("android/app/src/debug/assets/mock"),
    ).first { it.isDirectory }
    return java.io.File(dir, name).readText(Charsets.UTF_8)
  }
}

/** JDK built-in HttpServer via reflection (same approach as Step22b; no new dependency). */
private class Step35Server {
  private val serverClass = Class.forName("com.sun.net.httpserver.HttpServer")
  private val server = serverClass.getMethod("create", java.net.InetSocketAddress::class.java, Int::class.javaPrimitiveType)
    .invoke(null, java.net.InetSocketAddress("127.0.0.1", 0), 0)

  data class Seen(val method: String, val path: String, val headers: Map<String, String?>, val body: String)

  fun handle(block: (Exchange) -> Unit) {
    val handlerClass = Class.forName("com.sun.net.httpserver.HttpHandler")
    val handler = java.lang.reflect.Proxy.newProxyInstance(handlerClass.classLoader, arrayOf(handlerClass)) { _, method, args ->
      if (method.name == "handle") block(Exchange(args[0]))
      null
    }
    serverClass.getMethod("createContext", String::class.java, handlerClass).invoke(server, "/", handler)
    serverClass.getMethod("setExecutor", java.util.concurrent.Executor::class.java)
      .invoke(server, java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } })
  }

  fun start() {
    serverClass.getMethod("start").invoke(server)
  }

  fun stop() {
    serverClass.getMethod("stop", Int::class.javaPrimitiveType).invoke(server, 0)
  }

  fun url(): String {
    val address = serverClass.getMethod("getAddress").invoke(server) as java.net.InetSocketAddress
    return "http://127.0.0.1:${address.port}"
  }

  class Exchange(private val raw: Any) {
    private val type = Class.forName("com.sun.net.httpserver.HttpExchange")
    private val headersType = Class.forName("com.sun.net.httpserver.Headers")

    fun seen(): Seen {
      val stream = type.getMethod("getRequestBody").invoke(raw) as java.io.InputStream
      val body = stream.readBytes().toString(Charsets.UTF_8)
      val headers = type.getMethod("getRequestHeaders").invoke(raw)
      fun h(name: String) = headersType.getMethod("getFirst", String::class.java).invoke(headers, name) as String?
      val uri = type.getMethod("getRequestURI").invoke(raw) as java.net.URI
      val method = type.getMethod("getRequestMethod").invoke(raw) as String
      return Seen(
        method = method,
        path = uri.path,
        headers = listOf(ApiClient.HEADER_API_KEY, ApiClient.HEADER_CONTENT_TYPE, "Accept").associateWith { h(it) },
        body = body,
      )
    }

    fun respond(status: Int, body: String, retryAfter: String? = null, location: String? = null) {
      val bytes = body.toByteArray(Charsets.UTF_8)
      val headers = type.getMethod("getResponseHeaders").invoke(raw)
      val add = headersType.getMethod("add", String::class.java, String::class.java)
      add.invoke(headers, "Content-Type", "application/json; charset=utf-8")
      if (retryAfter != null) add.invoke(headers, "Retry-After", retryAfter)
      if (location != null) add.invoke(headers, "Location", location)
      val length = if (bytes.isEmpty()) -1L else bytes.size.toLong()
      type.getMethod("sendResponseHeaders", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(raw, status, length)
      if (bytes.isNotEmpty()) {
        val out = type.getMethod("getResponseBody").invoke(raw) as java.io.OutputStream
        out.use { it.write(bytes) }
      }
      type.getMethod("close").invoke(raw)
    }
  }
}

private fun <T> runBlocking35(block: suspend () -> T): T {
  val done = java.util.concurrent.CountDownLatch(1)
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) {
    result = it
    done.countDown()
  })
  assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "coroutine did not finish")
  return result!!.getOrThrow()
}
