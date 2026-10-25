package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.sim.SimVoiceInput
import com.hkparking.copilot.voice.PendingQuestion
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.TtsEngine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Step22aListenStandbyTest {
  @Before
  fun setUp() {
    Bindings.clearPorts()
  }

  @After
  fun tearDown() {
    Bindings.clearPorts()
  }

  @Test
  fun step22a_scarcityTimeoutKeepsHotwordStandby() {
    val harness = openHarness()
    armAndAsk(harness)
    harness.engine.finish()
    harness.clock.advance(waitMs())
    assertFalse(Bindings.voiceInput.isListening)
    assertTrue(Bindings.voiceInput.isHotwordStandby)
    val before = driverActiveCount(harness.service)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(before + 1, driverActiveCount(harness.service))
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22a_injectSilenceThenUtteranceStillDelivered() {
    val harness = openHarness()
    val before = driverActiveCount(harness.service)
    assertTrue(harness.receiver.dispatchEvent("INJECT_SILENCE"))
    assertFalse(Bindings.voiceInput.isListening)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(before + 1, driverActiveCount(harness.service))
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22a_noStandbyNoWindowDropsUtterance() {
    val voice = SimVoiceInput()
    var calls = 0
    voice.setUtteranceListener { _, _ -> calls += 1 }
    voice.injectUtterance("搵位")
    assertEquals(0, calls)
  }

  @Test
  fun step22a_commandWindowOnlyDoesNotArmStandby() {
    val voice = SimVoiceInput()
    var calls = 0
    voice.setUtteranceListener { _, _ -> calls += 1 }
    voice.startListening(hotword = false)
    voice.stopListening()
    voice.injectUtterance("搵位")
    assertEquals(0, calls)
    assertFalse(voice.isHotwordStandby)
  }

  @Test
  fun step22a_yesAfterTimeoutDoesNotExpandButVoiceAlive() {
    val harness = openHarness()
    armAndAsk(harness)
    harness.engine.finish()
    harness.clock.advance(waitMs())
    val before = driverActiveCount(harness.service)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertFalse(Bindings.apiTransport.recommendBodies.any { it.contains("\"expand_scarcity\":true") })
    assertEquals(before, driverActiveCount(harness.service))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(before + 1, driverActiveCount(harness.service))
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22a_secondScarcityRoundAfterTimeoutAsksAgain() {
    val harness = openHarness()
    armAndAsk(harness)
    harness.engine.finish()
    harness.clock.advance(waitMs())
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertTrue(harness.receiver.dispatchEvent("FORCE_SCARCITY"))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(PendingQuestion.SCARCITY_EXPAND, harness.service.currentPendingQuestion)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22a_clearPortsResetsWindowAndStandby() {
    val harness = openHarness()
    assertTrue(Bindings.voiceInput.isListening)
    assertTrue(Bindings.voiceInput.isHotwordStandby)
    Bindings.clearPorts()
    assertFalse(Bindings.voiceInput.isListening)
    assertFalse(Bindings.voiceInput.isHotwordStandby)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22a_answerClosesCommandWindowKeepsStandby() {
    val harness = openHarness()
    armAndAsk(harness)
    harness.engine.finish()
    assertTrue(Bindings.voiceInput.isListening)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "唔使"))
    assertFalse(Bindings.voiceInput.isListening)
    assertTrue(Bindings.voiceInput.isHotwordStandby)
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22a_onDestroyWithoutTeardownClearsPorts() {
    val harness = openHarness()
    assertTrue(Bindings.voiceInput.isHotwordStandby)
    val before = driverActiveCount(harness.service)
    val err = runCatching { harness.service.onDestroy() }.exceptionOrNull()
    if (err != null) {
      assertTrue(err is RuntimeException && (err.message ?: "").contains("not mocked"))
    }
    assertFalse(Bindings.voiceInput.isHotwordStandby)
    assertFalse(Bindings.voiceInput.isListening)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(before, driverActiveCount(harness.service))
  }

  private fun armAndAsk(harness: Harness) {
    assertTrue(harness.receiver.dispatchEvent("FORCE_SCARCITY"))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(PendingQuestion.SCARCITY_EXPAND, harness.service.currentPendingQuestion)
  }

  private fun driverActiveCount(service: FloatingBubbleService): Int {
    return service.launchedRecommendModes.count { it == QueryTriggerMode.DRIVER_ACTIVE }
  }

  private fun waitMs(): Long = (NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong()

  private fun openHarness(): Harness {
    val clock = FakeClock()
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    Bindings.voiceInput.stopListening()
    val service = FloatingBubbleService(clock.delayMs)
    val engine = HoldEngine()
    assertTrue(service.ttsService.attachEngine(engine))
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    service.reportFgsStarted()
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "FULL_VOICE"))
    assertTrue(service.isMicActive)
    assertEquals(TripVoiceMode.FULL_VOICE, service.tripStateMachine.tripVoiceMode)
    return Harness(clock, service, receiver, engine)
  }

  private class Harness(
    val clock: FakeClock,
    val service: FloatingBubbleService,
    val receiver: SimEventReceiver,
    val engine: HoldEngine,
  )

  private class HoldEngine : TtsEngine {
    private var onDone: () -> Unit = {}
    private var onError: () -> Unit = {}

    override fun bindLocale(locale: String): Boolean = true

    override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
      this.onDone = onDone
      this.onError = onError
    }

    override fun stop() {}

    fun finish() {
      onDone()
    }
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
}
