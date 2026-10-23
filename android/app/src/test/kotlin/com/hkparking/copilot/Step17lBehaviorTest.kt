package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.net.QueryTriggerMode
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step17lBehaviorTest {
  @After
  fun restoreStarter() {
    Bindings.fgsStarter = defaultFgsStarter
    Bindings.pendingStartDestination = null
  }

  companion object {
    private val defaultFgsStarter = Bindings.fgsStarter
  }
  @Test
  fun startTripLocksNamedDestinationAndJitFiresOnTriggerEdgeOnly() {
    val service = openService()
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals("朗豪坊", service.tripStateMachine.destinationName)
    assertTrue(service.tripStateMachine.destinationLocked)
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))

    val beforeFar = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 3000))
    assertEquals(beforeFar, Bindings.apiTransport.postCount)
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)

    val seen = mutableListOf<TripState>()
    val prior = service.tripStateMachine.onStateChanged
    service.tripStateMachine.onStateChanged = {
      prior?.invoke()
      seen += service.tripStateMachine.state
    }
    val beforeNear = Bindings.apiTransport.postCount
    val beforeModes = service.launchedRecommendModes.size
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = NativeConfig.TRIGGER_DISTANCE_M))
    assertTrue(service.tripStateMachine.jitTriggered)
    assertTrue(seen.contains(TripState.AMBER_STEADY))
    assertEquals(1, Bindings.apiTransport.postCount - beforeNear)
    assertEquals(
      listOf(QueryTriggerMode.BACKGROUND_PREFETCH),
      service.launchedRecommendModes.drop(beforeModes),
    )
    assertTrue(service.ttsService.spokenCues.isEmpty())

    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 900))
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 800))
    assertEquals(beforeNear + 1, Bindings.apiTransport.postCount)
    assertTrue(service.tripStateMachine.jitTriggered)
    Bindings.detachService(service)
  }

  @Test
  fun lockingDestinationInsideTriggerPrefetchesOnceWithoutWaitingForNextFix() {
    val started = openService()
    val startedReceiver = Bindings.activeReceiver!!
    Bindings.locationSource.start()
    val before = Bindings.apiTransport.postCount
    assertTrue(startedReceiver.dispatchEvent("SET_DISTANCE_M", intValue = 800))
    assertEquals(before, Bindings.apiTransport.postCount)
    assertFalse(started.tripStateMachine.jitTriggered)
    assertTrue(startedReceiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    assertTrue(startedReceiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals("朗豪坊", started.tripStateMachine.destinationName)
    assertTrue(started.tripStateMachine.destinationLocked)
    assertTrue(started.tripStateMachine.jitTriggered)
    assertEquals(1, Bindings.apiTransport.postCount - before)
    assertEquals(QueryTriggerMode.BACKGROUND_PREFETCH, started.launchedRecommendModes.single())
    assertTrue(started.ttsService.spokenCues.isEmpty())
    assertTrue(startedReceiver.dispatchEvent("SET_DISTANCE_M", intValue = 800))
    assertEquals(1, Bindings.apiTransport.postCount - before)
    Bindings.detachService(started)

    val spoken = openService()
    val spokenReceiver = Bindings.activeReceiver!!
    Bindings.locationSource.start()
    Bindings.voiceInput.startListening(hotword = true)
    assertTrue(spokenReceiver.dispatchEvent("SET_DISTANCE_M", intValue = 800))
    assertTrue(spokenReceiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    val spokenBefore = Bindings.apiTransport.postCount
    assertTrue(spokenReceiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "目的地朗豪坊"))
    assertEquals("朗豪坊", spoken.tripStateMachine.destinationName)
    assertTrue(spoken.tripStateMachine.jitTriggered)
    assertEquals(1, Bindings.apiTransport.postCount - spokenBefore)
    assertEquals(QueryTriggerMode.BACKGROUND_PREFETCH, spoken.launchedRecommendModes.single())
    assertTrue(spoken.ttsService.spokenCues.isEmpty())
    Bindings.detachService(spoken)

    val unlocked = openService()
    val unlockedReceiver = Bindings.activeReceiver!!
    Bindings.locationSource.start()
    val unlockedBefore = Bindings.apiTransport.postCount
    assertTrue(unlockedReceiver.dispatchEvent("SET_DISTANCE_M", intValue = NativeConfig.TRIGGER_DISTANCE_M))
    assertEquals(unlockedBefore, Bindings.apiTransport.postCount)
    assertFalse(unlocked.tripStateMachine.destinationLocked)
    assertFalse(unlocked.tripStateMachine.jitTriggered)
    Bindings.detachService(unlocked)
  }

  @Test
  fun approachMutedLockStillPrefetchesOnceAndKeepsState() {
    val service = openService()
    val receiver = Bindings.activeReceiver!!
    Bindings.locationSource.start()
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 800))
    assertTrue(receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    val before = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertTrue(service.tripStateMachine.jitTriggered)
    assertEquals(1, Bindings.apiTransport.postCount - before)
    assertEquals(QueryTriggerMode.BACKGROUND_PREFETCH, service.launchedRecommendModes.single())
    assertTrue(service.ttsService.spokenCues.isEmpty())
    Bindings.detachService(service)
  }

  @Test
  fun approachMutedSurvivesNetworkAndLeavesOnlyOnHoldOrShutdown() {
    val clock = StepClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    assertTrue(receiver.dispatchEvent("NETWORK_FAIL"))
    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    exhaustRetries(clock)
    assertEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)
    assertTrue(service.tripStateMachine.networkDegraded)

    assertTrue(receiver.dispatchEvent("NETWORK_OK"))
    assertEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)
    assertTrue(service.tripStateMachine.networkDegraded)

    assertTrue(receiver.dispatchEvent("FORCE_RATE_LIMIT", intValue = 2))
    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    clock.advance(5_000L)
    assertEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)
    assertTrue(service.tripStateMachine.networkDegraded)

    assertTrue(receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertTrue(service.capsuleSnapshot.contains("state=APPROACH_MUTED"))

    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(
      "前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。",
      service.ttsService.spokenCues.last(),
    )
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertFalse(service.tripStateMachine.networkDegraded)

    assertTrue(receiver.dispatchEvent("NETWORK_FAIL"))
    val beforeMutedFail = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    exhaustRetries(clock, beforeMutedFail + NativeConfig.API_MAX_ATTEMPTS)
    assertEquals(beforeMutedFail + NativeConfig.API_MAX_ATTEMPTS, Bindings.apiTransport.postCount)
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertTrue(service.tripStateMachine.networkDegraded)
    Bindings.detachService(service)

    val clean = openService()
    val cleanReceiver = Bindings.activeReceiver!!
    assertTrue(cleanReceiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertTrue(cleanReceiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, clean.tripStateMachine.state)
    assertTrue(cleanReceiver.dispatchEvent("LEAVE_TARGET", intValue = NativeConfig.APPROACH_EXIT_HOLD_S - 1))
    assertEquals(TripState.APPROACH_MUTED, clean.tripStateMachine.state)
    assertTrue(cleanReceiver.dispatchEvent("LEAVE_TARGET", intValue = NativeConfig.APPROACH_EXIT_HOLD_S))
    assertEquals(TripState.CRUISING, clean.tripStateMachine.state)
    assertTrue(cleanReceiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, clean.tripStateMachine.state)
    Bindings.detachService(clean)

    val degradedClock = StepClock()
    val degraded = openService(degradedClock)
    val degradedReceiver = Bindings.activeReceiver!!
    assertTrue(degradedReceiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertTrue(degradedReceiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    assertTrue(degradedReceiver.dispatchEvent("NETWORK_FAIL"))
    assertTrue(degradedReceiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    exhaustRetries(degradedClock)
    assertTrue(degraded.tripStateMachine.networkDegraded)
    assertTrue(degradedReceiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, degraded.tripStateMachine.state)
    assertTrue(degradedReceiver.dispatchEvent("NETWORK_OK"))
    assertTrue(degradedReceiver.dispatchEvent("LEAVE_TARGET", intValue = NativeConfig.APPROACH_EXIT_HOLD_S))
    assertEquals(TripState.GREY_BREATHING, degraded.tripStateMachine.state)
    assertTrue(degradedReceiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertNotEquals(TripState.GREY_BREATHING, degraded.tripStateMachine.state)
    Bindings.detachService(degraded)
  }

  @Test
  fun startTripAfterEngineShutdownStartsNewFgsViaPendingDestination() {
    val clock = StepClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    var starts = 0
    Bindings.fgsStarter = com.hkparking.copilot.di.FgsStarter {
      starts += 1
      true
    }
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals(0, starts)
    val firstSession = service.tripStateMachine.sessionId
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 0))
    assertTrue(receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(service.isFgsRunning)
    Bindings.detachService(service)
    assertTrue(Bindings.activeReceiver === receiver)
    val beforeRestart = snapshot(service)
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals(1, starts)
    assertEquals(beforeRestart, snapshot(service))
    assertEquals("朗豪坊", Bindings.pendingStartDestination)

    val restarted = FloatingBubbleService(clock.delayMs)
    restarted.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    restarted.reportFgsStarted()
    assertEquals("朗豪坊", restarted.tripStateMachine.destinationName)
    assertTrue(restarted.tripStateMachine.destinationLocked)
    assertNotEquals(firstSession, restarted.tripStateMachine.sessionId)
    assertTrue(restarted.tripStateMachine.sessionId!!.isNotBlank())
    assertEquals(null, Bindings.pendingStartDestination)
    assertFalse(restarted.tripStateMachine.jitTriggered)
    assertTrue(restarted.launchedRecommendModes.isEmpty())
    val beforeNear = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 1000))
    assertTrue(restarted.tripStateMachine.jitTriggered)
    assertEquals(1, Bindings.apiTransport.postCount - beforeNear)
    assertEquals(listOf(QueryTriggerMode.BACKGROUND_PREFETCH), restarted.launchedRecommendModes.toList())
    Bindings.detachService(restarted)
  }

  @Test
  fun startTripRejectedWhenStarterThrowsOrReturnsFalse() {
    val clock = StepClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertTrue(receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    Bindings.detachService(service)
    val before = snapshot(service)

    Bindings.fgsStarter = com.hkparking.copilot.di.FgsStarter {
      throw IllegalStateException("blocked")
    }
    assertFalse(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals(null, Bindings.pendingStartDestination)
    assertEquals(before, snapshot(service))

    Bindings.fgsStarter = com.hkparking.copilot.di.FgsStarter { false }
    assertFalse(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals(null, Bindings.pendingStartDestination)
    assertEquals(before, snapshot(service))
  }

  @Test
  fun engineShutdownLeavesServiceInstanceWithFgsFlagFalse() {
    val clock = StepClock()
    val service = openService(clock)
    assertTrue(Bindings.activeReceiver!!.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertTrue(service.portsWired)
    assertTrue(Bindings.activeReceiver!!.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(service.isFgsRunning)
    assertFalse(service.portsWired)
    assertTrue(Bindings.activeService === service)
    assertEquals(null, Bindings.pendingStartDestination)
  }

  @Test
  fun startTripOnLiveStoppedInstanceConsumesPendingWithoutNewObject() {
    val clock = StepClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    val firstSession = service.tripStateMachine.sessionId
    assertTrue(receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(service.isFgsRunning)
    assertTrue(Bindings.activeService === service)
    service.enterForegroundGate = { true }
    Bindings.fgsStarter = com.hkparking.copilot.di.FgsStarter { true }
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertTrue(service.isFgsRunning)
    assertTrue(service.tripStateMachine.destinationLocked)
    assertEquals("朗豪坊", service.tripStateMachine.destinationName)
    assertNotEquals(firstSession, service.tripStateMachine.sessionId)
    assertEquals(null, Bindings.pendingStartDestination)
    assertTrue(service.launchedRecommendModes.isEmpty())
    val before = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 1000))
    assertEquals(1, Bindings.apiTransport.postCount - before)
    assertEquals(listOf(QueryTriggerMode.BACKGROUND_PREFETCH), service.launchedRecommendModes.toList())
    Bindings.detachService(service)
  }

  @Test
  fun startForegroundServiceTrueButOnStartCommandSkippedDoesNotReportSuccess() {
    val clock = StepClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertTrue(receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    service.enterForegroundGate = { false }
    Bindings.fgsStarter = com.hkparking.copilot.di.FgsStarter { true }
    assertFalse(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals(null, Bindings.pendingStartDestination)
    assertFalse(service.tripStateMachine.destinationLocked)
    Bindings.detachService(service)
  }

  @Test
  fun reusedInstanceSecondOnStartCommandRewiresPorts() {
    val clock = StepClock()
    val service = openService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    val firstSession = service.tripStateMachine.sessionId
    assertTrue(receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(service.isFgsRunning)
    assertFalse(service.portsWired)
    Bindings.pendingStartDestination = "朗豪坊"
    service.enterForegroundGate = { true }
    service.onStartCommand(null, 0, 0)
    assertTrue(service.portsWired)
    assertEquals(null, Bindings.pendingStartDestination)
    assertTrue(service.tripStateMachine.destinationLocked)
    assertNotEquals(firstSession, service.tripStateMachine.sessionId)
    assertTrue(service.capsuleSnapshot.contains("state=${TripState.CRUISING.name}") || service.capsuleSnapshot.contains("state=AMBER_STEADY"))
    val before = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 1000))
    assertEquals(1, Bindings.apiTransport.postCount - before)
    assertEquals(listOf(QueryTriggerMode.BACKGROUND_PREFETCH), service.launchedRecommendModes.toList())
    Bindings.detachService(service)
  }

  @Test
  fun startTripWhileFgsRunningLocksWithoutCallingStarter() {
    val service = openService()
    val receiver = Bindings.activeReceiver!!
    var starts = 0
    Bindings.fgsStarter = com.hkparking.copilot.di.FgsStarter {
      starts += 1
      true
    }
    assertTrue(service.isFgsRunning)
    assertTrue(receiver.dispatchEvent("START_TRIP", stringValue = "朗豪坊"))
    assertEquals(0, starts)
    assertEquals("朗豪坊", service.tripStateMachine.destinationName)
    assertTrue(service.tripStateMachine.destinationLocked)
    Bindings.detachService(service)
  }

  private fun exhaustRetries(clock: StepClock, requiredPosts: Int = NativeConfig.API_MAX_ATTEMPTS) {
    clock.advance(NativeConfig.API_TIMEOUT_MS)
    var guard = 0
    while (Bindings.apiTransport.postCount < requiredPosts && guard < 20) {
      clock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
      clock.advance(NativeConfig.API_TIMEOUT_MS)
      guard += 1
    }
  }

  private fun snapshot(service: FloatingBubbleService): String {
    val machine = service.tripStateMachine
    return listOf(
      service.isFgsRunning,
      machine.state,
      machine.sessionId,
      machine.destinationName,
      machine.destinationLocked,
      machine.jitTriggered,
      machine.networkDegraded,
      service.launchedRecommendModes.toList(),
      service.capsuleSnapshot,
    ).joinToString("|")
  }

  private fun openService(clock: StepClock = StepClock()): FloatingBubbleService {
    Bindings.fgsStarter = defaultFgsStarter
    Bindings.pendingStartDestination = null
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    Bindings.voiceInput.stopListening()
    val service = FloatingBubbleService(clock.delayMs)
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    service.reportFgsStarted()
    return service
  }
}

private class StepClock {
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
