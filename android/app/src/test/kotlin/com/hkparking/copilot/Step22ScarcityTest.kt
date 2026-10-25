package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.sim.FixtureApiTransport
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.PendingQuestion
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.TtsEngine
import com.hkparking.copilot.voice.TtsService
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step22ScarcityTest {
  @Test
  fun step22_fullVoiceScarcityAsksOnceAndSetsPending() {
    val harness = openHarness()
    armScarcity(harness)
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(listOf(SCARCITY_CUE), harness.service.ttsService.spokenCues.drop(before))
    assertFalse(harness.service.ttsService.spokenCues.last().contains(ZhHkTemplates.TAP_CAPSULE))
    assertEquals(PendingQuestion.SCARCITY_EXPAND, harness.service.currentPendingQuestion)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_noMicScarcityCueHasTapCapsuleSuffix() {
    val harness = openHarness(micActive = false)
    armScarcity(harness)
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val expected = SCARCITY_CUE + ZhHkTemplates.TAP_CAPSULE
    assertEquals(listOf(expected), harness.service.ttsService.spokenCues.drop(before))
    assertTrue(harness.service.ttsService.spokenCues.last().endsWith(ZhHkTemplates.TAP_CAPSULE))
    harness.engine.finish()
    harness.clock.advance(waitMs())
    assertEquals(PendingQuestion.SCARCITY_EXPAND, harness.service.currentPendingQuestion)
    assertNotEquals("OPENING_WAIT", harness.service.listenStopReason)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_yesDuringTtsIsSelfHearingAndKeepsPending() {
    val harness = openHarness()
    armScarcity(harness)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val posts = Bindings.apiTransport.postCount
    assertTrue(harness.service.ttsService.isSpeaking)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(posts, Bindings.apiTransport.postCount)
    assertEquals(PendingQuestion.SCARCITY_EXPAND, harness.service.currentPendingQuestion)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_yesAfterTtsResendsSameSessionWithExpandFlag() {
    val harness = openHarness()
    armScarcity(harness)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val sessionId = harness.service.tripStateMachine.sessionId
    harness.engine.finish()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    val bodies = Bindings.apiTransport.recommendBodies
    assertTrue(bodies.last().contains("\"expand_scarcity\":true"))
    assertTrue(bodies.all { it.contains("\"session_id\":\"$sessionId\"") })
    assertEquals(sessionId, harness.service.tripStateMachine.sessionId)
    assertTrue(harness.service.ttsService.spokenCues.contains(EXPANDED_CUE))
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_expandEmptySpeaksNoMoreOptions() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("FORCE_SCARCITY", intValue = 0))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(listOf(ZhHkTemplates.NO_MORE_OPTIONS), harness.service.ttsService.spokenCues.drop(before))
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_openingWaitTimeoutInvalidates() {
    val harness = openHarness()
    armScarcity(harness)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    val posts = Bindings.apiTransport.postCount
    harness.clock.advance(waitMs())
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertEquals("OPENING_WAIT", harness.service.listenStopReason)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(posts, Bindings.apiTransport.postCount)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_speechBeforeTimeoutCancelsTimer() {
    val harness = openHarness()
    armScarcity(harness)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "唔使"))
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    val before = harness.service.listenStopReason
    val posts = Bindings.apiTransport.postCount
    harness.clock.advance(waitMs())
    assertEquals(before, harness.service.listenStopReason)
    assertEquals(posts, Bindings.apiTransport.postCount)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_cancelWordsInvalidateWithoutSpeech() {
    listOf("唔使", "等等").forEach { word ->
      val harness = openHarness()
      armScarcity(harness)
      assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
      val cues = harness.service.ttsService.spokenCues.size
      val posts = Bindings.apiTransport.postCount
      assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = word))
      assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
      assertEquals(cues, harness.service.ttsService.spokenCues.size)
      assertEquals(posts, Bindings.apiTransport.postCount)
      Bindings.detachService(harness.service)
    }
  }

  @Test
  fun step22_announceManualSpeaksWithoutPending() {
    val harness = openHarness(mode = "ANNOUNCE_MANUAL")
    armScarcity(harness)
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(listOf(SCARCITY_CUE), harness.service.ttsService.spokenCues.drop(before))
    assertFalse(harness.service.ttsService.spokenCues.last().contains(ZhHkTemplates.TAP_CAPSULE))
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertFalse(harness.service.isMicActive)
    harness.clock.advance(waitMs())
    assertNotEquals("OPENING_WAIT", harness.service.listenStopReason)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_manualOnlyIsSilentWithoutPending() {
    val harness = openHarness(mode = "MANUAL_ONLY")
    armScarcity(harness)
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(0, harness.service.ttsService.spokenCues.drop(before).size)
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertFalse(harness.service.isMicActive)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_backgroundPrefetchIgnoresAwaitReply() {
    val harness = openHarness()
    armScarcity(harness)
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(0, harness.service.ttsService.spokenCues.drop(before).size)
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(listOf(SCARCITY_CUE), harness.service.ttsService.spokenCues.drop(before))
    assertEquals(PendingQuestion.SCARCITY_EXPAND, harness.service.currentPendingQuestion)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_teardownInvalidatesPending() {
    val harness = openHarness()
    armScarcity(harness)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(PendingQuestion.SCARCITY_EXPAND, harness.service.currentPendingQuestion)
    assertTrue(harness.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    harness.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_fixtureRoutesByExpandFlag() {
    val transport = FixtureApiTransport()
    transport.armScarcity(FixtureApiTransport.SCARCITY_EXPANDED_FIXTURE)
    val first = runSuspend { transport.post("/v1/recommend-and-route", FALSE_EXPAND) }
    val second = runSuspend { transport.post("/v1/recommend-and-route", FALSE_EXPAND) }
    assertTrue(first.recommendResponse!!.awaitVoiceReply)
    assertEquals("CP_SCARCE_ONLY", first.recommendResponse!!.cards.first().carparkId)
    assertTrue(second.recommendResponse!!.awaitVoiceReply)
    assertEquals("CP_SCARCE_ONLY", second.recommendResponse!!.cards.first().carparkId)
    val expanded = runSuspend { transport.post("/v1/recommend-and-route", TRUE_EXPAND) }
    assertFalse(expanded.recommendResponse!!.awaitVoiceReply)
    assertEquals("CP_SCARCE_EXPANDED", expanded.recommendResponse!!.cards.first().carparkId)
    assertEquals(EXPANDED_CUE, expanded.recommendResponse!!.cards.first().spokenCue)
    val disarmed = runSuspend { transport.post("/v1/recommend-and-route", TRUE_EXPAND) }
    assertEquals("langham", disarmed.recommendResponse!!.cards.first().carparkId)
    transport.armScarcity(FixtureApiTransport.SCARCITY_EMPTY_FIXTURE)
    transport.armRecommendFixture(FixtureApiTransport.LOW_WATERMARK_FIXTURE)
    val override = runSuspend { transport.post("/v1/recommend-and-route", FALSE_EXPAND) }
    assertFalse(override.recommendResponse!!.awaitVoiceReply)
    transport.armRecommendFixture(FixtureApiTransport.LOW_WATERMARK_FIXTURE)
    transport.armScarcity(FixtureApiTransport.SCARCITY_EXPANDED_FIXTURE)
    val scarcity = runSuspend { transport.post("/v1/recommend-and-route", FALSE_EXPAND) }
    assertTrue(scarcity.recommendResponse!!.awaitVoiceReply)
    transport.reset()
    assertTrue(transport.recommendBodies.isEmpty())
    val reset = runSuspend { transport.post("/v1/recommend-and-route", FALSE_EXPAND) }
    assertEquals("langham", reset.recommendResponse!!.cards.first().carparkId)
    assertFalse(reset.recommendResponse!!.awaitVoiceReply)
    val receiver = SimEventReceiver()
    assertFalse(receiver.dispatchEvent("FORCE_SCARCITY", intValue = 2))
    assertFalse(receiver.dispatchEvent("FORCE_SCARCITY", stringValue = "expanded"))
  }

  @Test
  fun step22_ttsOnFinishedFiresOncePerUtterance() {
    val checker = availableChecker()
    val played = mutableListOf<Boolean>()
    val playedEngine = HoldEngine()
    val playedTts = TtsService(playedEngine, checker)
    assertEquals("播完", playedTts.speak("播完", TripVoiceMode.FULL_VOICE, false, true) { played += it })
    playedEngine.finish()
    playedEngine.finish()
    assertEquals(listOf(true), played)

    val failed = mutableListOf<Boolean>()
    val failedEngine = HoldEngine()
    val failedTts = TtsService(failedEngine, checker)
    assertEquals("失敗", failedTts.speak("失敗", TripVoiceMode.FULL_VOICE, false, true) { failed += it })
    failedEngine.fail()
    failedEngine.fail()
    assertEquals(listOf(false), failed)

    val interrupted = mutableListOf<Boolean>()
    val interruptEngine = HoldEngine()
    val interruptTts = TtsService(interruptEngine, checker)
    interruptTts.speak("一句", TripVoiceMode.FULL_VOICE, false, true) { interrupted += it }
    interruptTts.speak("二句", TripVoiceMode.FULL_VOICE, false, true) { interrupted += it }
    assertEquals(2, interruptTts.pendingCount())
    interruptTts.interrupt()
    interruptEngine.finish()
    interruptEngine.fail()
    assertEquals(listOf(false, false), interrupted)
    assertEquals(0, interruptTts.pendingCount())

    var called = false
    assertNull(playedTts.speak("", TripVoiceMode.FULL_VOICE, false, true) { called = true })
    assertNull(playedTts.speak("手動", TripVoiceMode.MANUAL_ONLY, false, true) { called = true })
    assertFalse(called)
  }

  @Test
  fun step22_bubbleTapAfterNoMicCueStartsTimer() {
    val waiting = openHarness(micActive = false)
    armScarcity(waiting)
    assertTrue(waiting.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    waiting.engine.finish()
    assertEquals(PendingQuestion.SCARCITY_EXPAND, waiting.service.currentPendingQuestion)
    assertTrue(waiting.receiver.dispatchEvent("BUBBLE_TAP"))
    waiting.clock.advance(waitMs())
    assertEquals(PendingQuestion.NONE, waiting.service.currentPendingQuestion)
    assertEquals("OPENING_WAIT", waiting.service.listenStopReason)
    Bindings.detachService(waiting.service)

    val speaking = openHarness(micActive = false)
    armScarcity(speaking)
    assertTrue(speaking.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(speaking.service.ttsService.isSpeaking)
    assertTrue(speaking.receiver.dispatchEvent("BUBBLE_TAP"))
    speaking.clock.advance(waitMs())
    assertEquals(PendingQuestion.SCARCITY_EXPAND, speaking.service.currentPendingQuestion)
    assertNotEquals("OPENING_WAIT", speaking.service.listenStopReason)
    speaking.engine.finish()
    speaking.clock.advance(waitMs())
    assertEquals(PendingQuestion.NONE, speaking.service.currentPendingQuestion)
    assertEquals("OPENING_WAIT", speaking.service.listenStopReason)
    Bindings.detachService(speaking.service)
  }

  @Test
  fun step22_destinationChangeInvalidatesPending() {
    val harness = openHarness()
    armScarcity(harness)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去朗豪坊"))
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    assertEquals("朗豪坊", harness.service.tripStateMachine.destinationName)
    assertTrue(harness.service.tripStateMachine.destinationLocked)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step22_staleOnFinishedDoesNotOpenTimer() {
    val harness = openHarness()
    armScarcity(harness)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val posts = Bindings.apiTransport.postCount
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "唔使"))
    assertEquals(PendingQuestion.NONE, harness.service.currentPendingQuestion)
    harness.engine.finish()
    harness.clock.advance(waitMs())
    assertNotEquals("OPENING_WAIT", harness.service.listenStopReason)
    assertEquals(posts, Bindings.apiTransport.postCount)
    Bindings.detachService(harness.service)
  }

  private fun armScarcity(harness: Harness) {
    assertTrue(harness.receiver.dispatchEvent("FORCE_SCARCITY"))
  }

  private fun waitMs(): Long = (NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong()

  private fun openHarness(micActive: Boolean = true, mode: String = "FULL_VOICE"): Harness {
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
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = mode))
    if (mode == "FULL_VOICE" && !micActive) {
      service.onVoiceCapabilityApplied(service.capabilityChecker.currentSnapshot(false), micFgsActive = false)
    }
    if (mode == "FULL_VOICE" && micActive) {
      assertTrue(service.isMicActive)
      assertEquals(TripVoiceMode.FULL_VOICE, service.tripStateMachine.tripVoiceMode)
    }
    return Harness(clock, service, receiver, engine)
  }

  private fun availableChecker(): VoiceCapabilityChecker {
    val probe = LocaleProbe(
      setLanguageSucceeded = true,
      offlineCantoneseVoiceInstalled = true,
      canRecognizeOrSynthesize = true,
      supported = true,
    )
    return VoiceCapabilityChecker(object : SpeechCapabilityProbe {
      override fun probeStt(locale: String): LocaleProbe = probe
      override fun probeTts(locale: String): LocaleProbe = probe
    }).also { it.check(recordAudioGranted = true) }
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

    fun fail() {
      onError()
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

  companion object {
    private const val SCARCITY_CUE = "該區僅有一場，目前已滿。要唔要幫你搵遠少少、大概三公里外嘅場？"
    private const val EXPANDED_CUE = "擴大範圍後，最近有位係後備停車場，兩公里八，剩八個位。"
    private const val FALSE_EXPAND = "{\"expand_scarcity\":false}"
    private const val TRUE_EXPAND = "{\"expand_scarcity\":true}"
  }
}

private fun <T> runSuspend(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}
