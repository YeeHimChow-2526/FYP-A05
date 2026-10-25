package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.TtsEngine
import com.hkparking.copilot.voice.ZhHkTemplates
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step23CommitAndNavTest {
  @Test
  fun step23_afterFirstChoiceGoodCommitsAndLaunchesNav() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(TripState.NAV_TO_GATE, harness.service.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, harness.service.tripStateMachine.capsuleLight)
    assertEquals(listOf("朗豪坊"), Bindings.navigation.launchedTargets)
    val encoded = java.net.URLEncoder.encode("朗豪坊", Charsets.UTF_8.name()).replace("+", "%20")
    assertEquals("google.navigation:q=$encoded&mode=d", Bindings.navigation.lastUri)
    assertTrue(harness.receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(TripState.NAV_TO_GATE, harness.service.tripStateMachine.state)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step23_selfHearingGoodDuringFirstChoiceKept() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val posts = Bindings.apiTransport.postCount
    assertTrue(harness.service.ttsService.isSpeaking)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(posts, Bindings.apiTransport.postCount)
    assertEquals(com.hkparking.copilot.voice.PendingQuestion.RECOMMENDATION_CONFIRM, harness.service.currentPendingQuestion)
    assertTrue(Bindings.navigation.launchedTargets.isEmpty())
    Bindings.detachService(harness.service)
  }

  @Test
  fun step23_timeoutThenGoodDoesNotCommit() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    val posts = Bindings.apiTransport.postCount
    harness.clock.advance(waitMs())
    assertEquals(com.hkparking.copilot.voice.PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(posts, Bindings.apiTransport.postCount)
    assertTrue(Bindings.navigation.launchedTargets.isEmpty())
    assertNotEquals(TripState.NAV_TO_GATE, harness.service.tripStateMachine.state)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step23_goThereTokenCommitsSameAsGood() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "去呢個"))
    assertEquals(TripState.NAV_TO_GATE, harness.service.tripStateMachine.state)
    assertEquals(listOf("朗豪坊"), Bindings.navigation.launchedTargets)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step23_lockFull409SpeaksCueWithoutNav() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    Bindings.apiTransport.armLockFull()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertTrue(harness.service.ttsService.spokenCues.contains("呢度滿咗"))
    assertTrue(Bindings.navigation.launchedTargets.isEmpty())
    assertNotEquals(TripState.NAV_TO_GATE, harness.service.tripStateMachine.state)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step23_after409NextOptionRerecommendsSkippingFull() {
    val harness = openHarness()
    var body = twoCards()
    Bindings.apiTransport.setFixtureLoader { name -> if (name == INLINE) body else null }
    Bindings.apiTransport.armRecommendFixture(INLINE)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    Bindings.apiTransport.armLockFull()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertTrue(harness.service.ttsService.spokenCues.contains("呢度滿咗"))
    body = twoCards()
    Bindings.apiTransport.armRecommendFixture(INLINE)
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "有冇第二個"))
    assertTrue(harness.service.ttsService.spokenCues.drop(before).contains("下一個場口播"))
    assertFalse(harness.service.ttsService.spokenCues.drop(before).contains("已滿場口播"))
    assertTrue(Bindings.navigation.launchedTargets.isEmpty())
    assertNotEquals(TripState.NAV_TO_GATE, harness.service.tripStateMachine.state)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step23_after409NextOptionAllFullSpeaksNoMore() {
    val harness = openHarness()
    var body = twoCards()
    Bindings.apiTransport.setFixtureLoader { name -> if (name == INLINE) body else null }
    Bindings.apiTransport.armRecommendFixture(INLINE)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    Bindings.apiTransport.armLockFull()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    body = fullOnly()
    Bindings.apiTransport.armRecommendFixture(INLINE)
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "有冇第二個"))
    assertEquals(listOf(ZhHkTemplates.NO_MORE_OPTIONS), harness.service.ttsService.spokenCues.drop(before))
    assertEquals(com.hkparking.copilot.voice.PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertTrue(Bindings.navigation.launchedTargets.isEmpty())
    Bindings.detachService(harness.service)
  }

  @Test
  fun step23_commitNetworkErrorSpeaksNetworkFailed() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    Bindings.apiTransport.setNetworkFail(true)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    var steps = 0
    while (steps < 12 && !harness.service.ttsService.spokenCues.contains(ZhHkTemplates.NETWORK_FAILED)) {
      harness.clock.advance(NativeConfig.API_TIMEOUT_MS)
      harness.clock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
      steps += 1
    }
    assertTrue(harness.service.ttsService.spokenCues.contains(ZhHkTemplates.NETWORK_FAILED))
    assertEquals(TripState.GREY_BREATHING, harness.service.tripStateMachine.state)
    assertTrue(Bindings.navigation.launchedTargets.isEmpty())
    Bindings.apiTransport.setNetworkFail(false)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step23_manualOnlyDoesNotOpenConfirmListen() {
    val harness = openHarness(mode = "MANUAL_ONLY")
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val posts = Bindings.apiTransport.postCount
    assertEquals(com.hkparking.copilot.voice.PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertFalse(harness.service.isMicActive)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(posts, Bindings.apiTransport.postCount)
    assertEquals(com.hkparking.copilot.voice.PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertTrue(Bindings.navigation.launchedTargets.isEmpty())
    Bindings.detachService(harness.service)
  }

  private fun twoCards(): String = """
    {"status":"SUCCESS","cards":[
      {"carpark_id":"CP_FULL","name":"已滿場","type":"INDOOR","vacancies":0,"spoken_cue":"已滿場口播","is_stale":false},
      {"carpark_id":"CP_NEXT","name":"下一個場","type":"INDOOR","vacancies":3,"spoken_cue":"下一個場口播","is_stale":false}
    ]}
  """.trimIndent()

  private fun fullOnly(): String = """
    {"status":"SUCCESS","cards":[
      {"carpark_id":"CP_FULL","name":"已滿場","type":"INDOOR","vacancies":0,"spoken_cue":"已滿場口播","is_stale":false}
    ]}
  """.trimIndent()

  private fun waitMs(): Long = (NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong()

  private fun openHarness(mode: String = "FULL_VOICE"): Harness {
    val clock = FakeClock()
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
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
    private var onDone: () -> Unit = {}
    override fun bindLocale(locale: String): Boolean = true
    override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) { this.onDone = onDone }
    override fun stop() {}
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

  companion object {
    private const val INLINE = "inline.json"
  }
}
