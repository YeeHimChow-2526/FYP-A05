package com.hkparking.copilot

import androidx.core.content.ContextCompat
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.FgsStarter
import com.hkparking.copilot.di.FixtureApiTransport
import com.hkparking.copilot.di.LocationSource
import com.hkparking.copilot.di.VoiceInput
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.RecommendRequest
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.CommandCode
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
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

  private fun expectedRateLimitWaitMs(): Long {
    val text = mockFixture(FixtureApiTransport.RATE_LIMIT_FIXTURE).readText()
    val seconds = FixtureApiTransport.parseRetryAfterSeconds(text)
    return if (seconds != null) {
      minOf(seconds * 1000L, NativeConfig.RATE_LIMIT_MAX_WAIT_MS)
    } else {
      NativeConfig.RATE_LIMIT_RETRY_DELAY_MS
    }
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
  fun backgroundJitTimeoutAndNearbyRateLimitTurnGreyImmediately() {
    val jitClock = FakeClock()
    val jitService = openService(jitClock)
    val jitReceiver = Bindings.activeReceiver!!
    assertTrue(jitReceiver.dispatchEvent("START_TRIP"))
    assertTrue(jitReceiver.dispatchEvent("NETWORK_FAIL"))
    assertTrue(jitReceiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals("P [AMBER_STEADY]", jitService.capsuleVisualLabel)
    jitClock.advance(NativeConfig.API_TIMEOUT_MS)
    assertEquals(TripState.GREY_BREATHING, jitService.tripStateMachine.state)
    assertEquals("P [GREY_BREATHING]", jitService.capsuleVisualLabel)
    Bindings.detachService(jitService)

    val limitedClock = FakeClock()
    val limited = openService(limitedClock)
    val limitedReceiver = Bindings.activeReceiver!!
    assertTrue(limitedReceiver.dispatchEvent("START_TRIP"))
    assertTrue(limitedReceiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(TripState.NEARBY_READY, limited.tripStateMachine.state)
    val beforeCount = Bindings.apiTransport.postCount
    assertTrue(limitedReceiver.dispatchEvent("FORCE_RATE_LIMIT", intValue = 2))
    assertTrue(limitedReceiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(1, Bindings.apiTransport.postCount - beforeCount)
    val expectedWaitMs = expectedRateLimitWaitMs()
    limitedClock.advance(expectedWaitMs - 1)
    assertEquals(1, Bindings.apiTransport.postCount - beforeCount)
    limitedClock.advance(1)
    assertEquals(2, Bindings.apiTransport.postCount - beforeCount)
    assertEquals(TripState.GREY_BREATHING, limited.tripStateMachine.state)
    assertEquals("P [GREY_BREATHING]", limited.capsuleVisualLabel)
    Bindings.detachService(limited)
  }

  @Test
  fun repeatedAttachStillPostsOneUtterance() {
    val service = openService(FakeClock())
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    service.reportFgsStarted()
    Bindings.attachService(service)
    Bindings.attachService(service)
    service.startInForeground(StartupEntry.B_3, recordAudioGranted = false)
    service.reportFgsStarted()
    val receiver = Bindings.activeReceiver!!
    val before = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(1, Bindings.apiTransport.postCount - before)
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

  @Test
  fun arriveCoreAndStaleFixEnterApproachMuted() {
    val clock = FakeClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertFalse(receiver.dispatchEvent("GPS_FIX_STALE", stringValue = "abc"))
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertTrue(receiver.dispatchEvent("GPS_FIX_STALE", intValue = 1))
    assertEquals(1, Bindings.locationSource.fixAgeSeconds)
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertTrue(receiver.dispatchEvent("GPS_FIX_STALE"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    Bindings.detachService(service)

    val arrive = openService(FakeClock())
    val arriveReceiver = Bindings.activeReceiver!!
    assertTrue(arriveReceiver.dispatchEvent("START_TRIP"))
    assertNotEquals(TripState.APPROACH_MUTED, arrive.tripStateMachine.state)
    assertTrue(arriveReceiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, arrive.tripStateMachine.state)
    assertFalse(arriveReceiver.dispatchEvent("LEAVE_TARGET", stringValue = "nope"))
    assertEquals(TripState.APPROACH_MUTED, arrive.tripStateMachine.state)
    assertTrue(arriveReceiver.dispatchEvent("LEAVE_TARGET", intValue = NativeConfig.APPROACH_EXIT_HOLD_S - 1))
    assertEquals(TripState.APPROACH_MUTED, arrive.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, arrive.tripStateMachine.capsuleLight)
    assertTrue(arriveReceiver.dispatchEvent("LEAVE_TARGET"))
    assertTrue(Bindings.locationSource.distanceMeters > NativeConfig.APPROACH_EXIT_DISTANCE_M)
    assertEquals(TripState.CRUISING, arrive.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, arrive.tripStateMachine.capsuleLight)
    Bindings.detachService(arrive)
  }

  @Test
  fun engineShutdownConfirmAndMidnightUseServicePort() {
    val outsideClock = FakeClock()
    val outside = openService(outsideClock)
    val outsideReceiver = Bindings.activeReceiver!!
    assertTrue(outsideReceiver.dispatchEvent("START_TRIP"))
    assertEquals(TripState.CRUISING, outside.tripStateMachine.state)
    assertFalse(outsideReceiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 2))
    assertEquals(TripState.CRUISING, outside.tripStateMachine.state)
    assertTrue(outside.isFgsRunning)
    assertTrue(outsideReceiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    assertEquals(TripState.ENDED, outside.tripStateMachine.state)
    assertEquals("ENGINE_SHUTDOWN", outside.tripEndedReason)
    assertTrue(outside.isFgsRunning)
    outsideClock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(outside.isFgsRunning)
    Bindings.detachService(outside)

    val parked = openService(FakeClock())
    val parkedReceiver = Bindings.activeReceiver!!
    assertTrue(parkedReceiver.dispatchEvent("START_TRIP"))
    val before = Bindings.apiTransport.postCount
    assertTrue(parkedReceiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    assertEquals(TripState.PARKED_CONFIRMED, parked.tripStateMachine.state)
    assertTrue(Bindings.pendingDriverConfirm)
    assertEquals(before + 1, Bindings.apiTransport.postCount)
    assertTrue(Bindings.apiTransport.lastPath.contains("release"))
    assertTrue(parkedReceiver.dispatchEvent("DRIVER_CONFIRM"))
    assertTrue(Bindings.driverConfirmSent)
    assertEquals(before + 2, Bindings.apiTransport.postCount)
    assertTrue(Bindings.apiTransport.lastPath.contains("sync"))
    Bindings.detachService(parked)

    val offline = openService(FakeClock())
    val offlineReceiver = Bindings.activeReceiver!!
    assertTrue(offlineReceiver.dispatchEvent("START_TRIP"))
    assertTrue(offlineReceiver.dispatchEvent("NETWORK_FAIL"))
    val offlineShutdownBefore = Bindings.apiTransport.postCount
    assertTrue(offlineReceiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    assertEquals(offlineShutdownBefore, Bindings.apiTransport.postCount)
    val offlineBefore = Bindings.apiTransport.postCount
    assertTrue(offlineReceiver.dispatchEvent("DRIVER_CONFIRM"))
    assertEquals(offlineBefore, Bindings.apiTransport.postCount)
    assertTrue(Bindings.pendingDriverConfirm)
    assertTrue(offlineReceiver.dispatchEvent("NETWORK_OK"))
    assertEquals(offlineBefore + 1, Bindings.apiTransport.postCount)
    Bindings.detachService(offline)

    val cleared = openService(FakeClock())
    val clearedReceiver = Bindings.activeReceiver!!
    assertTrue(clearedReceiver.dispatchEvent("START_TRIP"))
    assertTrue(clearedReceiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    assertTrue(Bindings.pendingDriverConfirm)
    assertTrue(clearedReceiver.dispatchEvent("CLOCK_HKT_MIDNIGHT"))
    assertTrue(Bindings.todayTripsCleared)
    assertFalse(Bindings.pendingDriverConfirm)
    val clearedBefore = Bindings.apiTransport.postCount
    assertTrue(clearedReceiver.dispatchEvent("DRIVER_CONFIRM"))
    assertEquals(clearedBefore, Bindings.apiTransport.postCount)
    Bindings.detachService(cleared)
  }

  @Test
  fun voiceMicPermissionAndListenEvents() {
    val service = openService(FakeClock())
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertFalse(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "NOPE"))
    assertEquals(TripVoiceMode.MANUAL_ONLY, service.tripStateMachine.tripVoiceMode)
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "FULL_VOICE"))
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertEquals(TripVoiceMode.FULL_VOICE, service.tripStateMachine.tripVoiceMode)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION_MIC, service.activeFgsType)
    assertTrue(service.isMicActive)
    assertTrue(receiver.dispatchEvent("NO_MIC"))
    assertFalse(service.isMicActive)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, service.activeFgsType)
    assertEquals(TripState.ALERT_BREATHING, service.tripStateMachine.state)
    assertTrue(service.tripStateMachine.lastSpokenCue!!.contains(ZhHkTemplates.TAP_CAPSULE))
    Bindings.detachService(service)

    val micFail = openService(FakeClock())
    val micReceiver = Bindings.activeReceiver!!
    assertTrue(micReceiver.dispatchEvent("START_TRIP"))
    assertTrue(micReceiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, micFail.activeFgsType)
    assertFalse(micFail.isMicActive)
    assertTrue(micFail.isCapsuleVisible)
    assertTrue(micReceiver.dispatchEvent("MIC_FAIL"))
    assertTrue(micFail.isFgsRunning)
    assertTrue(micFail.isCapsuleVisible)
    assertFalse(micFail.isMicActive)
    assertNotEquals(TripState.ENDED, micFail.tripStateMachine.state)
    Bindings.detachService(micFail)

    val timeout = openService(FakeClock())
    assertFalse(Bindings.capabilityCheckTimedOut)
    assertTrue(Bindings.activeReceiver!!.dispatchEvent("CAPABILITY_CHECK_TIMEOUT"))
    assertTrue(Bindings.capabilityCheckTimedOut)
    assertTrue(timeout.isFgsRunning)
    assertFalse(timeout.capabilityChecker.downloadPromptRequested)
    Bindings.detachService(timeout)

    val revoked = openService(FakeClock())
    val revokedReceiver = Bindings.activeReceiver!!
    assertTrue(revokedReceiver.dispatchEvent("START_TRIP"))
    assertTrue(revokedReceiver.dispatchEvent("SET_VOICE_MODE", stringValue = "FULL_VOICE"))
    assertTrue(revokedReceiver.dispatchEvent("REVOKE_RECORD_AUDIO"))
    assertTrue(revoked.isFgsRunning)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, revoked.activeFgsType)
    assertFalse(revoked.isAudioRecordRunning)
    assertNotEquals(TripState.ENDED, revoked.tripStateMachine.state)
    assertTrue(revokedReceiver.dispatchEvent("REVOKE_REQUIRED"))
    assertFalse(revoked.isFgsRunning)
    assertEquals(TripState.ENDED, revoked.tripStateMachine.state)
    assertEquals(FloatingBubbleService.PERMISSION_REVOKED, revoked.tripEndedReason)
    Bindings.detachService(revoked)

    val listen = openService(FakeClock())
    val listenReceiver = Bindings.activeReceiver!!
    assertTrue(listenReceiver.dispatchEvent("START_TRIP"))
    assertTrue(Bindings.voiceInput.isListening)
    assertFalse(listenReceiver.dispatchEvent("INJECT_SILENCE", stringValue = "abc"))
    assertTrue(Bindings.voiceInput.isListening)
    assertTrue(listenReceiver.dispatchEvent("INJECT_SILENCE", intValue = 1))
    assertTrue(Bindings.voiceInput.isListening)
    assertTrue(listenReceiver.dispatchEvent("INJECT_LONG_UTTERANCE", intValue = 1))
    assertTrue(Bindings.voiceInput.isListening)
    assertTrue(listenReceiver.dispatchEvent("INJECT_SILENCE", intValue = 1))
    assertFalse(Bindings.voiceInput.isListening)
    assertEquals("END_SILENCE", listen.listenStopReason)
    assertEquals(NativeConfig.LISTEN_END_SILENCE_S, listen.listenLimitS)
    Bindings.detachService(listen)

    val opening = openService(FakeClock())
    val openingReceiver = Bindings.activeReceiver!!
    assertTrue(openingReceiver.dispatchEvent("START_TRIP"))
    assertTrue(openingReceiver.dispatchEvent("INJECT_SILENCE"))
    assertEquals("OPENING_WAIT", opening.listenStopReason)
    assertEquals(NativeConfig.LISTEN_OPENING_WAIT_S, opening.listenLimitS)
    Bindings.detachService(opening)

    val longUtterance = openService(FakeClock())
    val longReceiver = Bindings.activeReceiver!!
    assertTrue(longReceiver.dispatchEvent("START_TRIP"))
    assertTrue(longReceiver.dispatchEvent("INJECT_LONG_UTTERANCE"))
    assertEquals("MAX_UTTERANCE", longUtterance.listenStopReason)
    assertEquals(NativeConfig.LISTEN_MAX_UTTERANCE_S, longUtterance.listenLimitS)
    Bindings.detachService(longUtterance)

    val sttTimeout = openService(FakeClock())
    assertTrue(Bindings.activeReceiver!!.dispatchEvent("INJECT_LONG_UTTERANCE", stringValue = "stt"))
    assertEquals("STT_TIMEOUT", sttTimeout.listenStopReason)
    assertEquals(NativeConfig.STT_RESULT_TIMEOUT_S, sttTimeout.listenLimitS)
    Bindings.detachService(sttTimeout)
  }

  @Test
  fun confidenceEchoBootstrapBubbleTrieAndGpsAccuracy() {
    val confidence = openService(FakeClock())
    val confidenceReceiver = Bindings.activeReceiver!!
    assertTrue(confidenceReceiver.dispatchEvent("START_TRIP"))
    val beforeParse = Bindings.voiceInput.lastParseResult
    assertFalse(confidenceReceiver.dispatchEvent("INJECT_CONFIDENCE", stringValue = "abc"))
    assertTrue(beforeParse === Bindings.voiceInput.lastParseResult)
    assertTrue(confidenceReceiver.dispatchEvent("INJECT_CONFIDENCE", intValue = -1))
    assertTrue(Bindings.voiceInput.lastParseResult!!.missingConfidenceNotTreatedAsZero)
    assertFalse(Bindings.voiceInput.lastParseResult!!.lowConfidence)
    assertTrue(confidenceReceiver.dispatchEvent("INJECT_CONFIDENCE", intValue = 0))
    assertTrue(Bindings.voiceInput.lastParseResult!!.lowConfidence)
    assertFalse(Bindings.voiceInput.lastParseResult!!.missingConfidenceNotTreatedAsZero)
    assertEquals(TripState.CRUISING, confidence.tripStateMachine.state)
    assertTrue(confidenceReceiver.dispatchEvent("INJECT_TTS_ECHO"))
    assertFalse(Bindings.voiceInput.lastParseResult!!.confirmed)
    assertTrue(Bindings.voiceInput.lastParseResult!!.blockedAsSelfHearing)
    assertEquals(TripState.CRUISING, confidence.tripStateMachine.state)
    Bindings.detachService(confidence)

    val bootstrap = FloatingBubbleService(FakeClock().delayMs)
    bootstrap.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    assertFalse(bootstrap.isFgsRunning)
    assertEquals(null, bootstrap.bootstrapTimeoutReason)
    assertTrue(Bindings.activeReceiver!!.dispatchEvent("FGS_START_TIMEOUT"))
    assertFalse(bootstrap.isFgsRunning)
    assertTrue(bootstrap.bootstrapTimeoutReason!!.contains(NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS.toString()))
    Bindings.detachService(bootstrap)

    val tap = openService(FakeClock())
    assertFalse(tap.manualCardVisible)
    assertTrue(Bindings.activeReceiver!!.dispatchEvent("BUBBLE_TAP"))
    assertTrue(tap.manualCardVisible)
    Bindings.detachService(tap)

    val resolved = openService(FakeClock())
    val resolvedReceiver = Bindings.activeReceiver!!
    assertTrue(resolvedReceiver.dispatchEvent("START_TRIP"))
    assertEquals(TripState.CRUISING, resolved.tripStateMachine.state)
    assertTrue(resolvedReceiver.dispatchEvent("TRIE_MISS_RESOLVE_OK", stringValue = "朗豪坊"))
    assertEquals("朗豪坊", resolved.tripStateMachine.destinationName)
    assertEquals(TripState.CRUISING, resolved.tripStateMachine.state)
    Bindings.detachService(resolved)

    val trieFail = openService(FakeClock())
    val trieFailReceiver = Bindings.activeReceiver!!
    assertTrue(trieFailReceiver.dispatchEvent("START_TRIP"))
    assertNotEquals(TripState.GREY_BREATHING, trieFail.tripStateMachine.state)
    assertTrue(trieFailReceiver.dispatchEvent("TRIE_MISS_NETWORK_FAIL"))
    assertEquals(TripState.GREY_BREATHING, trieFail.tripStateMachine.state)
    Bindings.detachService(trieFail)

    val ambiguous = openService(FakeClock())
    val ambiguousReceiver = Bindings.activeReceiver!!
    assertTrue(ambiguousReceiver.dispatchEvent("START_TRIP"))
    assertNotEquals(TripState.ALERT_BREATHING, ambiguous.tripStateMachine.state)
    assertTrue(ambiguousReceiver.dispatchEvent("TRIE_MISS_AMBIGUOUS"))
    assertEquals(TripState.ALERT_BREATHING, ambiguous.tripStateMachine.state)
    Bindings.detachService(ambiguous)

    val gps = openService(FakeClock())
    val gpsReceiver = Bindings.activeReceiver!!
    assertTrue(gpsReceiver.dispatchEvent("START_TRIP"))
    val before = gps.tripStateMachine.state
    assertFalse(gpsReceiver.dispatchEvent("GPS_BAD_ACCURACY", stringValue = "abc"))
    assertEquals(null, Bindings.onSiteRejectedCue)
    assertEquals(before, gps.tripStateMachine.state)
    assertTrue(gpsReceiver.dispatchEvent("GPS_BAD_ACCURACY"))
    assertEquals(ZhHkTemplates.ON_SITE_LOCATION_MISSED, Bindings.onSiteRejectedCue)
    assertEquals(before, gps.tripStateMachine.state)
    Bindings.detachService(gps)
  }

  @Test
  fun pauseResumeAndSpeedInjectionUseExistingLocationPort() {
    val service = openService(FakeClock())
    val receiver = Bindings.activeReceiver!!
    val speeds = mutableListOf<Double>()
    Bindings.locationSource.start()
    Bindings.locationSource.setMotionListener { _, speedKmh, _, _, _ ->
      speeds += speedKmh
    }
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 900))
    assertEquals(900, service.lastDistanceMeters())

    assertTrue(receiver.dispatchEvent("PAUSE"))
    assertTrue(Bindings.locationSource.isPaused)
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 150))
    assertEquals(150, Bindings.locationSource.distanceMeters)
    assertEquals(900, service.lastDistanceMeters())
    assertTrue(receiver.dispatchEvent("SET_SPEED_KMH", intValue = 12))
    assertEquals(12.0, Bindings.locationSource.speedKmh, 0.0)
    assertTrue(speeds.isEmpty())

    assertTrue(receiver.dispatchEvent("RESUME"))
    assertFalse(Bindings.locationSource.isPaused)
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 150))
    assertEquals(150, service.lastDistanceMeters())
    assertFalse(receiver.dispatchEvent("SET_SPEED_KMH", intValue = -1))
    assertEquals(12.0, Bindings.locationSource.speedKmh, 0.0)
    assertTrue(receiver.dispatchEvent("SET_SPEED_KMH", intValue = 28))
    assertEquals(28.0, Bindings.locationSource.speedKmh, 0.0)
    assertEquals(listOf(28.0), speeds)
    Bindings.detachService(service)
  }

  @Test
  fun fgsStartTimeoutRecordsReasonWithoutActivityTrampoline() {
    val launches = intArrayOf(0)
    val previous = Bindings.fgsStarter
    Bindings.fgsStarter = FgsStarter {
      launches[0]++
      false
    }
    val service = FloatingBubbleService(FakeClock().delayMs)
    try {
      service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
      assertFalse(service.isFgsRunning)
      assertEquals(null, service.bootstrapTimeoutReason)
      val waiting = FgsBootstrapActivity(service)
      assertTrue(waiting.launchFromEntry(StartupEntry.A_2, PermissionCheckInput()))
      assertFalse(waiting.isFinished)
      assertTrue(Bindings.activeReceiver!!.dispatchEvent("FGS_START_TIMEOUT"))
      assertTrue(waiting.bootstrapTimedOut)
      assertTrue(waiting.isFinished)
      assertEquals(FgsBootstrapActivity.FGS_BOOTSTRAP_TIMEOUT, waiting.failureReason)
      assertTrue(service.bootstrapTimeoutReason!!.contains(NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS.toString()))
      assertEquals(0, launches[0])
      service.reportFgsStarted()
      assertFalse(waiting.fgsStartedReceived)
      val timedOut = waiting.bootstrapTimedOut
      val finished = waiting.isFinished
      val reason = waiting.failureReason
      val recorded = service.bootstrapTimeoutReason
      assertTrue(Bindings.activeReceiver!!.dispatchEvent("FGS_START_TIMEOUT"))
      assertEquals(timedOut, waiting.bootstrapTimedOut)
      assertEquals(finished, waiting.isFinished)
      assertEquals(reason, waiting.failureReason)
      assertEquals(recorded, service.bootstrapTimeoutReason)
      assertFalse(service.isFgsRunning)
    } finally {
      FloatingBubbleService.fireBootstrapTimeout()
      Bindings.fgsStarter = previous
      Bindings.detachService(service)
    }
  }

  @Test
  fun fgsStartTimeoutWithoutWaitingActivityStillRecordsReason() {
    val service = FloatingBubbleService(FakeClock().delayMs)
    try {
      service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
      assertEquals(null, service.bootstrapTimeoutReason)
      assertEquals(0, FloatingBubbleService.fireBootstrapTimeout())
      assertTrue(Bindings.activeReceiver!!.dispatchEvent("FGS_START_TIMEOUT"))
      assertFalse(service.isFgsRunning)
      assertTrue(service.bootstrapTimeoutReason!!.contains(NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS.toString()))
      assertEquals(0, FloatingBubbleService.fireBootstrapTimeout())
    } finally {
      FloatingBubbleService.fireBootstrapTimeout()
      Bindings.detachService(service)
    }
  }

  @Test
  fun forceFixturesSwitchNextRecommendOrCommit() {
    val service = openService(FakeClock())
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("FORCE_STALE"))
    assertEquals(FixtureApiTransport.STALE_FIXTURE, Bindings.apiTransport.fixtureName)
    val stale = runSuspend { Bindings.apiTransport.post(ApiClient.RECOMMEND, "{}") }
    assertTrue(stale.bodyJson.contains("is_stale"))
    assertEquals(-1, stale.recommendResponse!!.cards.first().vacancies)

    assertTrue(receiver.dispatchEvent("FORCE_DEGRADED"))
    assertEquals(FixtureApiTransport.DEGRADED_FIXTURE, Bindings.apiTransport.fixtureName)
    val degraded = runSuspend { Bindings.apiTransport.post(ApiClient.RECOMMEND, "{}") }
    assertEquals("degraded", degraded.recommendResponse!!.sourceStatus)

    assertTrue(receiver.dispatchEvent("FORCE_LOW_WATERMARK"))
    val low = runSuspend { Bindings.apiTransport.post(ApiClient.RECOMMEND, "{}") }
    assertTrue(low.spokenCue!!.contains("只剩兩個位"))

    assertTrue(receiver.dispatchEvent("FORCE_CROWD_FLAG"))
    val crowd = runSuspend { Bindings.apiTransport.post(ApiClient.RECOMMEND, "{}") }
    assertEquals(ZhHkTemplates.CROWD_YELLOW_FLAG, crowd.spokenCue)

    assertTrue(receiver.dispatchEvent("FORCE_LOCK_FULL"))
    assertEquals(FixtureApiTransport.LOCK_FULL_FIXTURE, Bindings.apiTransport.fixtureName)
    val locked = runSuspend { Bindings.apiTransport.post(ApiClient.COMMIT, "{}") }
    assertEquals(409, locked.statusCode)
    assertFalse(locked.spokenCue.isNullOrBlank())
    Bindings.detachService(service)
  }

  @Test
  fun singleDistanceInjectionPostsOnce() {
    val service = openService(FakeClock())
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    val before = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(1, Bindings.apiTransport.postCount - before)
    Bindings.detachService(service)
  }

  @Test
  fun jitLatchStaysSingleAcrossArriveAndLeave() {
    val service = openService(FakeClock())
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    val before = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    val seen = mutableListOf<String>()
    val prior = service.tripStateMachine.onStateChanged
    service.tripStateMachine.onStateChanged = {
      prior?.invoke()
      seen += "${service.tripStateMachine.state}/${service.tripStateMachine.capsuleLight}"
    }
    assertTrue(receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, service.tripStateMachine.capsuleLight)
    assertTrue(receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, service.tripStateMachine.capsuleLight)
    assertTrue(receiver.dispatchEvent("LEAVE_TARGET", intValue = NativeConfig.APPROACH_EXIT_HOLD_S))
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertTrue(receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, service.tripStateMachine.capsuleLight)
    assertEquals(1, Bindings.apiTransport.postCount - before)
    assertTrue(seen.none { it.contains("AMBER_STEADY") || it.contains("NEARBY_READY") })
    assertTrue(service.tripStateMachine.jitTriggered)
    Bindings.detachService(service)
  }

  @Test
  fun backgroundPrefetchSilentButFindParkingSpeaksCue() {
    val service = openService(FakeClock())
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(TripState.NEARBY_READY, service.tripStateMachine.state)
    assertTrue(service.ttsService.spokenCues.isEmpty())
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(
      listOf("前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。"),
      service.ttsService.spokenCues.toList(),
    )
    Bindings.detachService(service)
  }

  @Test
  fun engineShutdownWritesCandidateReleasesAndFades() {
    val insideClock = FakeClock()
    val inside = openService(insideClock)
    val insideReceiver = Bindings.activeReceiver!!
    assertTrue(insideReceiver.dispatchEvent("START_TRIP"))
    val before = Bindings.apiTransport.postCount
    assertTrue(insideReceiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    assertEquals(TripState.PARKED_CONFIRMED, inside.tripStateMachine.state)
    assertTrue(Bindings.pendingDriverConfirm)
    assertEquals(before + 1, Bindings.apiTransport.postCount)
    assertTrue(Bindings.apiTransport.lastPath.contains("/v1/session/release"))
    assertTrue(inside.isFgsRunning)
    assertTrue(inside.isCapsuleVisible)
    insideClock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong() - 1)
    assertTrue(inside.isFgsRunning)
    insideClock.advance(1)
    assertFalse(inside.isFgsRunning)
    assertFalse(inside.isCapsuleVisible)
    Bindings.detachService(inside)

    val outsideClock = FakeClock()
    val outside = openService(outsideClock)
    val outsideReceiver = Bindings.activeReceiver!!
    assertTrue(outsideReceiver.dispatchEvent("START_TRIP"))
    val outsideBefore = Bindings.apiTransport.postCount
    assertTrue(outsideReceiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    assertFalse(Bindings.pendingDriverConfirm)
    assertEquals(outsideBefore, Bindings.apiTransport.postCount)
    assertTrue(outside.isFgsRunning)
    outsideClock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(outside.isFgsRunning)
    Bindings.detachService(outside)
  }

  @Test
  fun leaveTargetHoldKeepsApproachUntilThreshold() {
    val service = openService(FakeClock())
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertTrue(
      receiver.dispatchEvent(
        "LEAVE_TARGET",
        intValue = (NativeConfig.APPROACH_EXIT_HOLD_S - 1).toInt(),
      ),
    )
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertTrue(receiver.dispatchEvent("LEAVE_TARGET", intValue = NativeConfig.APPROACH_EXIT_HOLD_S))
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    Bindings.detachService(service)
  }

  @Test
  fun simEventReceiverRegistersExportedActionViaRegistrar() {
    val previous = SimEventReceiver.registrar
    val calls = mutableListOf<Pair<String, Int>>()
    SimEventReceiver.registrar = SimEventReceiver.Companion.ReceiverRegistrar { _, _, action, flags ->
      calls += action to flags
    }
    try {
      val context = BlankContext()
      val receiver = SimEventReceiver()
      SimEventReceiver.register(context, receiver)
      assertEquals(1, calls.size)
      assertEquals("com.hkparking.copilot.SIM_EVENT", calls[0].first)
      assertEquals(ContextCompat.RECEIVER_EXPORTED, calls[0].second)
    } finally {
      SimEventReceiver.registrar = previous
    }
    assertTrue(SimEventReceiver.registrar === previous)
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

private class BlankContext : android.content.Context() {
  override fun bindService(p0: android.content.Intent, p1: android.content.ServiceConnection, p2: Int): Boolean = false
  override fun checkCallingOrSelfPermission(p0: String): Int = 0
  override fun checkCallingOrSelfUriPermission(p0: android.net.Uri?, p1: Int): Int = 0
  override fun checkCallingPermission(p0: String): Int = 0
  override fun checkCallingUriPermission(p0: android.net.Uri?, p1: Int): Int = 0
  override fun checkPermission(p0: String, p1: Int, p2: Int): Int = 0
  override fun checkSelfPermission(p0: String): Int = 0
  override fun checkUriPermission(p0: android.net.Uri?, p1: Int, p2: Int, p3: Int): Int = 0
  override fun checkUriPermission(p0: android.net.Uri?, p1: String?, p2: String?, p3: Int, p4: Int, p5: Int): Int = 0
  override fun clearWallpaper() {}
  override fun createConfigurationContext(p0: android.content.res.Configuration): android.content.Context = this
  override fun createContextForSplit(p0: String?): android.content.Context? = null
  override fun createDeviceProtectedStorageContext(): android.content.Context? = null
  override fun createDisplayContext(p0: android.view.Display): android.content.Context = this
  override fun createPackageContext(p0: String?, p1: Int): android.content.Context? = null
  override fun databaseList(): Array<String>? = null
  override fun deleteDatabase(p0: String?): Boolean = false
  override fun deleteFile(p0: String?): Boolean = false
  override fun deleteSharedPreferences(p0: String?): Boolean = false
  override fun enforceCallingOrSelfPermission(p0: String, p1: String?) {}
  override fun enforceCallingOrSelfUriPermission(p0: android.net.Uri?, p1: Int, p2: String?) {}
  override fun enforceCallingPermission(p0: String, p1: String?) {}
  override fun enforceCallingUriPermission(p0: android.net.Uri?, p1: Int, p2: String?) {}
  override fun enforcePermission(p0: String, p1: Int, p2: Int, p3: String?) {}
  override fun enforceUriPermission(p0: android.net.Uri?, p1: Int, p2: Int, p3: Int, p4: String?) {}
  override fun enforceUriPermission(p0: android.net.Uri?, p1: String?, p2: String?, p3: Int, p4: Int, p5: Int, p6: String?) {}
  override fun fileList(): Array<String>? = null
  override fun getApplicationContext(): android.content.Context? = null
  override fun getApplicationInfo(): android.content.pm.ApplicationInfo? = null
  override fun getAssets(): android.content.res.AssetManager? = null
  override fun getCacheDir(): java.io.File? = null
  override fun getClassLoader(): ClassLoader? = null
  override fun getCodeCacheDir(): java.io.File? = null
  override fun getContentResolver(): android.content.ContentResolver? = null
  override fun getDataDir(): java.io.File? = null
  override fun getDatabasePath(p0: String?): java.io.File? = null
  override fun getDir(p0: String?, p1: Int): java.io.File? = null
  override fun getExternalCacheDir(): java.io.File? = null
  override fun getExternalCacheDirs(): Array<java.io.File>? = null
  override fun getExternalFilesDir(p0: String?): java.io.File? = null
  override fun getExternalFilesDirs(p0: String?): Array<java.io.File>? = null
  override fun getExternalMediaDirs(): Array<java.io.File>? = null
  override fun getFileStreamPath(p0: String?): java.io.File? = null
  override fun getFilesDir(): java.io.File? = null
  override fun getMainLooper(): android.os.Looper? = null
  override fun getNoBackupFilesDir(): java.io.File? = null
  override fun getObbDir(): java.io.File? = null
  override fun getObbDirs(): Array<java.io.File>? = null
  override fun getPackageCodePath(): String? = null
  override fun getPackageManager(): android.content.pm.PackageManager? = null
  override fun getPackageName(): String? = null
  override fun getPackageResourcePath(): String? = null
  override fun getResources(): android.content.res.Resources? = null
  override fun getSharedPreferences(p0: String?, p1: Int): android.content.SharedPreferences? = null
  override fun getSystemService(p0: String): Any = this
  override fun getSystemServiceName(p0: Class<*>): String? = null
  override fun getTheme(): android.content.res.Resources.Theme? = null
  override fun getWallpaper(): android.graphics.drawable.Drawable? = null
  override fun getWallpaperDesiredMinimumHeight(): Int = 0
  override fun getWallpaperDesiredMinimumWidth(): Int = 0
  override fun grantUriPermission(p0: String?, p1: android.net.Uri?, p2: Int) {}
  override fun isDeviceProtectedStorage(): Boolean = false
  override fun moveDatabaseFrom(p0: android.content.Context?, p1: String?): Boolean = false
  override fun moveSharedPreferencesFrom(p0: android.content.Context?, p1: String?): Boolean = false
  override fun openFileInput(p0: String?): java.io.FileInputStream? = null
  override fun openFileOutput(p0: String?, p1: Int): java.io.FileOutputStream? = null
  override fun openOrCreateDatabase(p0: String?, p1: Int, p2: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase? = null
  override fun openOrCreateDatabase(p0: String?, p1: Int, p2: android.database.sqlite.SQLiteDatabase.CursorFactory?, p3: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase? = null
  override fun peekWallpaper(): android.graphics.drawable.Drawable? = null
  override fun registerReceiver(p0: android.content.BroadcastReceiver?, p1: android.content.IntentFilter?): android.content.Intent? = null
  override fun registerReceiver(p0: android.content.BroadcastReceiver?, p1: android.content.IntentFilter?, p2: Int): android.content.Intent? = null
  override fun registerReceiver(p0: android.content.BroadcastReceiver?, p1: android.content.IntentFilter?, p2: String?, p3: android.os.Handler?): android.content.Intent? = null
  override fun registerReceiver(p0: android.content.BroadcastReceiver?, p1: android.content.IntentFilter?, p2: String?, p3: android.os.Handler?, p4: Int): android.content.Intent? = null
  override fun removeStickyBroadcast(p0: android.content.Intent?) {}
  override fun removeStickyBroadcastAsUser(p0: android.content.Intent?, p1: android.os.UserHandle?) {}
  override fun revokeUriPermission(p0: android.net.Uri?, p1: Int) {}
  override fun revokeUriPermission(p0: String?, p1: android.net.Uri?, p2: Int) {}
  override fun sendBroadcast(p0: android.content.Intent?) {}
  override fun sendBroadcast(p0: android.content.Intent?, p1: String?) {}
  override fun sendBroadcastAsUser(p0: android.content.Intent?, p1: android.os.UserHandle?) {}
  override fun sendBroadcastAsUser(p0: android.content.Intent?, p1: android.os.UserHandle?, p2: String?) {}
  override fun sendOrderedBroadcast(p0: android.content.Intent?, p1: String?) {}
  override fun sendOrderedBroadcast(p0: android.content.Intent, p1: String?, p2: android.content.BroadcastReceiver?, p3: android.os.Handler?, p4: Int, p5: String?, p6: android.os.Bundle?) {}
  override fun sendOrderedBroadcastAsUser(p0: android.content.Intent?, p1: android.os.UserHandle?, p2: String?, p3: android.content.BroadcastReceiver?, p4: android.os.Handler?, p5: Int, p6: String?, p7: android.os.Bundle?) {}
  override fun sendStickyBroadcast(p0: android.content.Intent?) {}
  override fun sendStickyBroadcastAsUser(p0: android.content.Intent?, p1: android.os.UserHandle?) {}
  override fun sendStickyOrderedBroadcast(p0: android.content.Intent?, p1: android.content.BroadcastReceiver?, p2: android.os.Handler?, p3: Int, p4: String?, p5: android.os.Bundle?) {}
  override fun sendStickyOrderedBroadcastAsUser(p0: android.content.Intent?, p1: android.os.UserHandle?, p2: android.content.BroadcastReceiver?, p3: android.os.Handler?, p4: Int, p5: String?, p6: android.os.Bundle?) {}
  override fun setTheme(p0: Int) {}
  override fun setWallpaper(p0: android.graphics.Bitmap?) {}
  override fun setWallpaper(p0: java.io.InputStream?) {}
  override fun startActivities(p0: Array<android.content.Intent>?) {}
  override fun startActivities(p0: Array<android.content.Intent>?, p1: android.os.Bundle?) {}
  override fun startActivity(p0: android.content.Intent?) {}
  override fun startActivity(p0: android.content.Intent?, p1: android.os.Bundle?) {}
  override fun startForegroundService(p0: android.content.Intent?): android.content.ComponentName? = null
  override fun startInstrumentation(p0: android.content.ComponentName, p1: String?, p2: android.os.Bundle?): Boolean = false
  override fun startIntentSender(p0: android.content.IntentSender?, p1: android.content.Intent?, p2: Int, p3: Int, p4: Int) {}
  override fun startIntentSender(p0: android.content.IntentSender?, p1: android.content.Intent?, p2: Int, p3: Int, p4: Int, p5: android.os.Bundle?) {}
  override fun startService(p0: android.content.Intent?): android.content.ComponentName? = null
  override fun stopService(p0: android.content.Intent?): Boolean = false
  override fun unbindService(p0: android.content.ServiceConnection) {}
  override fun unregisterReceiver(p0: android.content.BroadcastReceiver?) {}
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

