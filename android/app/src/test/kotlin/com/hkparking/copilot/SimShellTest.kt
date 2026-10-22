package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.FixtureApiTransport
import com.hkparking.copilot.di.LocationSource
import com.hkparking.copilot.di.VoiceInput
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.CommandCode
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimShellTest {
  @Test
  fun bindingsExposeSimPorts() {
    val voice: VoiceInput = Bindings.voiceInput
    val location: LocationSource = Bindings.locationSource
    val transport: ApiTransport = Bindings.apiTransport
    assertTrue(voice === Bindings.voiceInput)
    assertTrue(location === Bindings.locationSource)
    assertTrue(transport === Bindings.apiTransport)
  }

  @Test
  fun fixtureTransportDrivesGreyThenNearbyAndRateLimitRetriesOnce() {
    val machine = startedMachine()
    val transport = Bindings.apiTransport
    transport.reset()
    val client = ApiClient(transport, machine) { }
    transport.setNetworkFail(true)
    val failed = runSuspend { client.recommendAndRoute(sampleRequest()) }
    assertEquals(TripState.GREY_BREATHING, failed.state)
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, machine.capsuleLight)
    assertNotEquals(TripState.ALERT_BREATHING, machine.state)
    assertFalse(failed.stateTimeline.contains(TripState.ALERT_BREATHING))

    transport.setNetworkFail(false)
    val recovered = runSuspend { client.recommendAndRoute(sampleRequest()) }
    assertTrue(recovered.succeeded)
    assertEquals(TripState.NEARBY_READY, recovered.state)
    assertEquals(TripState.NEARBY_READY, machine.state)

    transport.forceRateLimit(2)
    val limited = runSuspend { client.recommendAndRoute(sampleRequest()) }
    assertEquals(2, limited.attempts)
    assertNotEquals(NativeConfig.API_MAX_ATTEMPTS, limited.attempts)
    assertEquals(TripState.GREY_BREATHING, limited.state)
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, machine.capsuleLight)
    assertNotEquals(TripState.ALERT_BREATHING, machine.state)
    transport.reset()
  }

  @Test
  fun pausedLocationDoesNotJitUntilResumeAndVoiceInjectionParses() {
    val machine = startedMachine(destinationName = "朗豪坊")
    assertEquals(TripState.CRUISING, machine.state)
    assertFalse(machine.jitTriggered)
    val source = Bindings.locationSource
    source.reset()
    source.setLocationListener { distance, _, _ ->
      machine.onDistanceToDestination(distance)
    }
    source.pause()
    source.start()
    source.injectDistanceMeters(NativeConfig.TRIGGER_DISTANCE_M)
    source.injectSpeedKmh(30.0)
    assertTrue(source.isPaused)
    assertEquals(NativeConfig.TRIGGER_DISTANCE_M, source.distanceMeters)
    assertFalse(machine.jitTriggered)
    assertEquals(TripState.CRUISING, machine.state)

    source.resume()
    source.injectDistanceMeters(NativeConfig.TRIGGER_DISTANCE_M)
    assertFalse(source.isPaused)
    assertTrue(machine.jitTriggered)
    assertEquals(TripState.AMBER_STEADY, machine.state)
    source.reset()

    val voice = Bindings.voiceInput
    voice.stopListening()
    var heard: String? = null
    voice.setUtteranceListener { text, _ -> heard = text }
    voice.injectUtterance("取消", confidence = 0.9)
    assertEquals(null, heard)
    voice.startListening()
    voice.injectUtterance("取消", confidence = 0.9)
    assertEquals("取消", heard)
    assertEquals(CommandCode.CANCEL, voice.lastParseResult?.code)
    assertTrue(voice.lastParseResult?.accepted == true)
    voice.stopListening()
  }

  @Test
  fun simEventsDriveDistanceNetworkAndRateLimit() {
    val machine = startedMachine(destinationName = "朗豪坊")
    val logs = mutableListOf<String>()
    val receiver = SimEventReceiver(logger = { logs += it })
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = NativeConfig.TRIGGER_DISTANCE_M))
    assertEquals(NativeConfig.TRIGGER_DISTANCE_M, Bindings.locationSource.distanceMeters)
    assertEquals(TripState.CRUISING, machine.state)

    assertTrue(receiver.dispatchEvent("NETWORK_FAIL"))
    assertEquals(TripState.CRUISING, machine.state)
    assertEquals(CapsuleLight.GREY_STEADY, machine.capsuleLight)

    assertTrue(receiver.dispatchEvent("NETWORK_OK"))
    assertFalse(machine.networkDegraded)

    assertTrue(receiver.dispatchEvent("FORCE_RATE_LIMIT", intValue = 2))
    assertEquals(FixtureApiTransport.RATE_LIMIT_FIXTURE, Bindings.apiTransport.fixtureName)
    val limited = runSuspend {
      ApiClient(Bindings.apiTransport, machine) { }.recommendAndRoute(sampleRequest())
    }
    assertEquals(429, limited.httpStatus)
    Bindings.apiTransport.reset()
    Bindings.locationSource.reset()
    assertTrue(logs.isEmpty())
  }

  @Test
  fun unknownOrInvalidSimEventsAreLoggedAndIgnored() {
    val machine = startedMachine(destinationName = "朗豪坊")
    val logs = mutableListOf<String>()
    val receiver = SimEventReceiver(logger = { logs += it })
    Bindings.locationSource.reset()
    val beforeDistance = Bindings.locationSource.distanceMeters
    val beforeState = machine.state
    assertFalse(receiver.dispatchEvent("NO_SUCH_EVENT", intValue = 100))
    assertFalse(receiver.dispatchEvent("SET_DISTANCE_M", intValue = null, stringValue = "abc"))
    assertFalse(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "   "))
    assertEquals(3, logs.size)
    assertTrue(logs.all { it.startsWith("Ignored unknown or invalid sim event:") })
    assertEquals(beforeDistance, Bindings.locationSource.distanceMeters)
    assertEquals(beforeState, machine.state)
    Bindings.locationSource.reset()
  }

  @Test
  fun fileFixtureSuccessLanghamBecomesNearbyReady() {
    val file = mockFixture(FixtureApiTransport.DEFAULT_FIXTURE)
    assertTrue(file.isFile)
    assertTrue(file.length() > 0L)
    val transport = fileTransport()
    val posted = runSuspend { transport.post(ApiClient.RECOMMEND, "{}") }
    assertEquals(200, posted.statusCode)
    assertTrue(posted.bodyJson.contains("CP_LANGHAM"))
    assertTrue(posted.bodyJson.contains("\"speech_locale\": \"zh-HK\""))
    val machine = startedMachine()
    val outcome = runSuspend { ApiClient(transport, machine) { }.recommendAndRoute(sampleRequest()) }
    assertTrue(outcome.succeeded)
    assertEquals(TripState.NEARBY_READY, outcome.state)
    assertEquals(TripState.NEARBY_READY, machine.state)
  }

  @Test
  fun fileFixtureRateLimitRetriesOnceAndTurnsGrey() {
    val file = mockFixture(FixtureApiTransport.RATE_LIMIT_FIXTURE)
    assertTrue(file.isFile)
    assertTrue(file.length() > 0L)
    val machine = startedMachine()
    val logs = mutableListOf<String>()
    val receiver = SimEventReceiver(logger = { logs += it })
    Bindings.apiTransport.reset()
    assertTrue(receiver.dispatchEvent("FORCE_RATE_LIMIT", intValue = 5))
    assertEquals(FixtureApiTransport.RATE_LIMIT_FIXTURE, Bindings.apiTransport.fixtureName)
    val transport = fileTransport()
    assertTrue(file.readText().contains("RATE_LIMITED"))
    assertTrue(file.readText().contains("429"))
    transport.forceRateLimit(2)
    val outcome = runSuspend { ApiClient(transport, machine) { }.recommendAndRoute(sampleRequest()) }
    assertEquals(429, outcome.httpStatus)
    assertEquals(2, outcome.attempts)
    assertNotEquals(NativeConfig.API_MAX_ATTEMPTS, outcome.attempts)
    assertEquals(TripState.GREY_BREATHING, outcome.state)
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, machine.capsuleLight)
    Bindings.apiTransport.reset()
  }

  private fun fileTransport(): FixtureApiTransport {
    return FixtureApiTransport { name ->
      val file = mockFixture(name)
      if (!file.isFile) null else file.readText()
    }
  }

  private fun mockFixture(name: String): java.io.File {
    val relative = java.io.File("src/debug/assets/mock/$name")
    val candidates = listOf(
      relative,
      java.io.File("app/src/debug/assets/mock/$name"),
      java.io.File("android/app/src/debug/assets/mock/$name"),
    )
    return candidates.firstOrNull { it.isFile } ?: relative
  }

  @Test
  fun networkFailAloneDoesNotChangeCruiseLight() {
    val service = openService(FakeClock())
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertEquals("朗豪坊", service.tripStateMachine.destinationName)
    assertTrue(receiver.dispatchEvent("NETWORK_FAIL"))
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, service.tripStateMachine.capsuleLight)
    assertEquals(0, Bindings.apiTransport.postCount)
    Bindings.detachService(service)
  }

  @Test
  fun networkFailDistanceRetriesThenStopsAtMaxAttempts() {
    val clock = FakeClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("NETWORK_FAIL"))
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(TripState.AMBER_STEADY, service.tripStateMachine.state)
    assertEquals(CapsuleLight.AMBER_STEADY, service.tripStateMachine.capsuleLight)
    assertEquals(1, Bindings.apiTransport.postCount)

    clock.advance(NativeConfig.API_TIMEOUT_MS)
    assertEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, service.tripStateMachine.capsuleLight)

    var guard = 0
    while (Bindings.apiTransport.postCount < NativeConfig.API_MAX_ATTEMPTS && guard < 30) {
      clock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
      guard += 1
    }
    assertEquals(NativeConfig.API_MAX_ATTEMPTS, Bindings.apiTransport.postCount)
    val stopped = Bindings.apiTransport.postCount
    clock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
    clock.advance(NativeConfig.API_TIMEOUT_MS)
    assertEquals(stopped, Bindings.apiTransport.postCount)
    assertEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)
    Bindings.detachService(service)
  }

  @Test
  fun greyStaysUntilDriverAsksAgain() {
    val clock = FakeClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("NETWORK_FAIL"))
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    clock.advance(NativeConfig.API_TIMEOUT_MS)
    var guard = 0
    while (Bindings.apiTransport.postCount < NativeConfig.API_MAX_ATTEMPTS && guard < 30) {
      clock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
      guard += 1
    }
    assertEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)
    val before = Bindings.apiTransport.postCount

    assertTrue(receiver.dispatchEvent("NETWORK_OK"))
    assertEquals(CapsuleLight.GREY_BREATHING, service.tripStateMachine.capsuleLight)
    assertEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)

    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    clock.advance(NativeConfig.API_TIMEOUT_MS)
    clock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
    assertEquals(before, Bindings.apiTransport.postCount)
    assertEquals(CapsuleLight.GREY_BREATHING, service.tripStateMachine.capsuleLight)

    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(TripState.NEARBY_READY, service.tripStateMachine.state)
    assertTrue(Bindings.apiTransport.postCount > before)
    Bindings.detachService(service)
  }

  @Test
  fun forceRateLimitRetriesOnceWithFakeClock() {
    val limitedClock = FakeClock()
    val limited = openService(limitedClock)
    val limitedReceiver = Bindings.activeReceiver!!
    assertTrue(limitedReceiver.dispatchEvent("START_TRIP"))
    assertTrue(limitedReceiver.dispatchEvent("FORCE_RATE_LIMIT", intValue = 2))
    assertTrue(limitedReceiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(1, Bindings.apiTransport.postCount)
    assertEquals(TripState.AMBER_STEADY, limited.tripStateMachine.state)
    limitedClock.advance(NativeConfig.RATE_LIMIT_RETRY_DELAY_MS - 1)
    assertEquals(1, Bindings.apiTransport.postCount)
    limitedClock.advance(1)
    assertEquals(2, Bindings.apiTransport.postCount)
    assertEquals(TripState.GREY_BREATHING, limited.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, limited.tripStateMachine.capsuleLight)
    Bindings.detachService(limited)

    val recoveredClock = FakeClock()
    val recovered = openService(recoveredClock)
    val recoveredReceiver = Bindings.activeReceiver!!
    assertTrue(recoveredReceiver.dispatchEvent("START_TRIP"))
    assertTrue(recoveredReceiver.dispatchEvent("FORCE_RATE_LIMIT"))
    assertTrue(recoveredReceiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(1, Bindings.apiTransport.postCount)
    recoveredClock.advance(NativeConfig.RATE_LIMIT_RETRY_DELAY_MS)
    assertEquals(2, Bindings.apiTransport.postCount)
    assertEquals(TripState.NEARBY_READY, recovered.tripStateMachine.state)
    Bindings.detachService(recovered)
  }

  private fun openService(clock: FakeClock): FloatingBubbleService {
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    Bindings.voiceInput.stopListening()
    val service = FloatingBubbleService(clock.delayMs)
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    service.reportFgsStarted()
    return service
  }

  private fun startedMachine(destinationName: String? = null): TripStateMachine {
    val machine = TripStateMachine()
    machine.requestStart(StartupEntry.A_2, destinationName = destinationName)
    machine.onFgsStarted()
    return machine
  }

  private fun sampleRequest(): RecommendRequest = RecommendRequest(
    sessionId = "sess",
    originLat = 22.3,
    originLng = 114.1,
    destLat = 22.31,
    destLng = 114.17,
    destName = "朗豪坊",
    preference = "SMART_HYBRID",
  )
}

private class FakeClock {
  private var now: Long = 0L
  private val waiters = mutableListOf<Pair<Long, Continuation<Unit>>>()
  val delayMs: suspend (Long) -> Unit = { ms ->
    if (ms > 0L) {
      suspendCoroutine { cont ->
        waiters += (now + ms) to cont
      }
    }
  }

  fun advance(ms: Long) {
    now += ms
    val due = waiters.filter { it.first <= now }.sortedBy { it.first }
    waiters.removeAll(due)
    due.forEach { it.second.resume(Unit) }
  }
}

private fun <T> runSuspend(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}
