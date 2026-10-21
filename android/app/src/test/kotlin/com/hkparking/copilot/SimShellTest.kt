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
import kotlin.coroutines.startCoroutine
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

    transport.forceRateLimit(enabled = true, retryAfterSeconds = null)
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
    val receiver = SimEventReceiver(stateMachineProvider = { machine }, logger = { logs += it })
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = NativeConfig.TRIGGER_DISTANCE_M))
    assertEquals(NativeConfig.TRIGGER_DISTANCE_M, Bindings.locationSource.distanceMeters)
    assertTrue(machine.jitTriggered)
    assertEquals(TripState.AMBER_STEADY, machine.state)

    assertTrue(receiver.dispatchEvent("NETWORK_FAIL"))
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, machine.capsuleLight)

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
    val receiver = SimEventReceiver(stateMachineProvider = { machine }, logger = { logs += it })
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

private fun <T> runSuspend(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}
