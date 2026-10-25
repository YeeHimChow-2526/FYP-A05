package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.FgsStarter
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.sim.FixtureApiTransport
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.PendingInvalidation
import com.hkparking.copilot.voice.TtsEngine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step25ShutdownAndGreyRetryTest {
  @Test
  fun step25_shutdownStopsFirstChoiceImmediately() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(harness.service.ttsService.isSpeaking)
    assertTrue(harness.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    assertFalse(harness.service.ttsService.isSpeaking)
    assertTrue(harness.engine.stopped)
    assertEquals(PendingInvalidation.DIALOGUE_INTERRUPTED, harness.service.lastPendingInvalidation)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step25_shutdownStopsScarcityPromptWithoutLaterTimeout() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("FORCE_SCARCITY"))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(harness.service.ttsService.isSpeaking)
    assertTrue(harness.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    assertFalse(harness.service.ttsService.isSpeaking)
    assertTrue(harness.engine.stopped)
    assertEquals(PendingInvalidation.DIALOGUE_INTERRUPTED, harness.service.lastPendingInvalidation)
    harness.clock.advance((NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong())
    assertEquals(PendingInvalidation.DIALOGUE_INTERRUPTED, harness.service.lastPendingInvalidation)
    assertNotEquals(PendingInvalidation.TIMEOUT, harness.service.lastPendingInvalidation)
    assertFalse(Bindings.voiceInput.isListening)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step25_lateRecommendDuringFadeIsDropped() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("FORCE_RATE_LIMIT", intValue = 1))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val wait = rateLimitWaitMs()
    val remain = NativeConfig.TEARDOWN_FADE_MS.toLong() / 2
    harness.clock.advance(wait - remain)
    val cues = harness.service.ttsService.spokenCues.toList()
    assertTrue(harness.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    assertEquals(TripState.ENDED, harness.service.tripStateMachine.state)
    harness.clock.advance(remain)
    assertEquals(cues, harness.service.ttsService.spokenCues.toList())
    assertFalse(harness.service.floatingCard.uiState.visible)
    assertEquals(TripState.ENDED, harness.service.tripStateMachine.state)
    assertTrue(harness.service.shutdownResultDropped)
    assertTrue(harness.service.isFgsRunning)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step25_teardownInterruptAfterFadeDoesNotCrash() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(harness.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    harness.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(harness.service.isFgsRunning)
    assertFalse(harness.service.ttsService.isSpeaking)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step25_greyTapSendsOneDriverActiveAndLeavesGrey() {
    val harness = openHarness()
    harness.service.tripStateMachine.onNetworkFailure()
    assertEquals(TripState.GREY_BREATHING, harness.service.tripStateMachine.state)
    val before = harness.service.launchedRecommendModes.count { it == QueryTriggerMode.DRIVER_ACTIVE }
    assertTrue(harness.receiver.dispatchEvent("BUBBLE_TAP"))
    assertEquals(before + 1, harness.service.launchedRecommendModes.count { it == QueryTriggerMode.DRIVER_ACTIVE })
    assertNotEquals(TripState.GREY_BREATHING, harness.service.tripStateMachine.state)
    assertFalse(harness.service.greyRetryInFlight)
    assertFalse(harness.service.floatingCard.uiState.visible)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step25_greyRetryDoesNotOverlapAndCanRetryAfterFailure() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("NETWORK_FAIL"))
    harness.service.tripStateMachine.onNetworkFailure()
    val before = harness.service.launchedRecommendModes.count { it == QueryTriggerMode.DRIVER_ACTIVE }
    assertTrue(harness.receiver.dispatchEvent("BUBBLE_TAP"))
    assertTrue(harness.receiver.dispatchEvent("BUBBLE_TAP"))
    assertEquals(before + 1, harness.service.launchedRecommendModes.count { it == QueryTriggerMode.DRIVER_ACTIVE })
    var steps = 0
    while (steps < NativeConfig.API_MAX_ATTEMPTS + 1 && harness.service.greyRetryInFlight) {
      harness.clock.advance(NativeConfig.API_TIMEOUT_MS)
      harness.clock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
      steps += 1
    }
    assertEquals(TripState.GREY_BREATHING, harness.service.tripStateMachine.state)
    assertFalse(harness.service.greyRetryInFlight)
    assertTrue(harness.receiver.dispatchEvent("BUBBLE_TAP"))
    assertEquals(before + 2, harness.service.launchedRecommendModes.count { it == QueryTriggerMode.DRIVER_ACTIVE })
    Bindings.apiTransport.setNetworkFail(false)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step25_shutdownClearsGreyRetryAndNextTripCanTapAgain() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("NETWORK_FAIL"))
    harness.service.tripStateMachine.onNetworkFailure()
    assertTrue(harness.receiver.dispatchEvent("BUBBLE_TAP"))
    assertTrue(harness.service.greyRetryInFlight)
    assertTrue(harness.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    assertFalse(harness.service.greyRetryInFlight)
    harness.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(harness.service.isFgsRunning)
    harness.service.enterForegroundGate = { true }
    Bindings.fgsStarter = FgsStarter {
      harness.service.restartTripFromEntry(StartupEntry.A_2)
      true
    }
    assertTrue(harness.receiver.dispatchEvent("START_TRIP"))
    assertTrue(harness.receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "FULL_VOICE"))
    assertFalse(harness.service.shutdownInProgress)
    Bindings.apiTransport.setNetworkFail(false)
    harness.service.tripStateMachine.onNetworkFailure()
    assertEquals(TripState.GREY_BREATHING, harness.service.tripStateMachine.state)
    val before = harness.service.launchedRecommendModes.count { it == QueryTriggerMode.DRIVER_ACTIVE }
    assertTrue(harness.receiver.dispatchEvent("BUBBLE_TAP"))
    assertEquals(before + 1, harness.service.launchedRecommendModes.count { it == QueryTriggerMode.DRIVER_ACTIVE })
    Bindings.detachService(harness.service)
  }

  @Test
  fun step25_announceGreyTapDoesNotShowCard() {
    val harness = openHarness(mode = "ANNOUNCE_MANUAL")
    harness.service.tripStateMachine.onNetworkFailure()
    assertTrue(harness.receiver.dispatchEvent("BUBBLE_TAP"))
    assertTrue(harness.service.manualCardVisible)
    assertFalse(harness.service.floatingCard.uiState.visible)
    Bindings.detachService(harness.service)
  }

  private fun rateLimitWaitMs(): Long {
    val seconds = FixtureApiTransport.parseRetryAfterSeconds(
      FixtureApiTransport.loadMockFile(FixtureApiTransport.RATE_LIMIT_FIXTURE).orEmpty(),
    )
    return if (seconds != null && seconds > 0L) {
      minOf(seconds * 1000L, NativeConfig.RATE_LIMIT_MAX_WAIT_MS)
    } else {
      NativeConfig.RATE_LIMIT_RETRY_DELAY_MS
    }
  }

  private fun openHarness(mode: String = "FULL_VOICE"): Harness {
    val clock = FakeClock()
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    Bindings.apiTransport.setFixtureLoader { FixtureApiTransport.loadMockFile(it) }
    Bindings.navigation.reset()
    Bindings.voiceInput.stopListening()
    val service = FloatingBubbleService(clock.delayMs)
    val engine = HoldEngine()
    assertTrue(service.ttsService.attachEngine(engine))
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    service.reportFgsStarted()
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = mode))
    return Harness(clock, service, receiver, engine)
  }

  private class Harness(
    val clock: FakeClock,
    val service: FloatingBubbleService,
    val receiver: SimEventReceiver,
    val engine: HoldEngine,
  )

  private class HoldEngine : TtsEngine {
    var stopped: Boolean = false
      private set
    private var onDone: () -> Unit = {}
    override fun bindLocale(locale: String): Boolean = true
    override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) { this.onDone = onDone }
    override fun stop() { stopped = true }
    fun finish() { onDone() }
  }

  private class FakeClock {
    private var now: Long = 0L
    private val waiters = mutableListOf<Pair<Long, Continuation<Unit>>>()
    val delayMs: suspend (Long) -> Unit = { ms ->
      if (ms > 0L) {
        suspendCoroutine { cont -> waiters += (now + ms) to cont }
      }
    }
    fun advance(ms: Long) {
      now += ms
      val due = waiters.filter { it.first <= now }.sortedBy { it.first }
      waiters.removeAll(due)
      due.forEach { it.second.resume(Unit) }
    }
  }
}
