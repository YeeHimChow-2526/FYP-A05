package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.sim.FixtureApiTransport
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.RecommendationCard
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

class Step24FloatingCardTest {
  @Test
  fun step24_showDetailsEntersExpandedAndSpeaksDetails() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertEquals(TripState.EXPANDED, harness.service.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, harness.service.tripStateMachine.capsuleLight)
    assertTrue(harness.service.floatingCard.uiState.visible)
    assertEquals("朗豪坊，首小時三十二蚊", harness.service.floatingCard.uiState.subtitle)
    assertEquals(NativeConfig.CARD_PAGE_TRANSITION_MS, harness.service.floatingCard.uiState.transitionMs)
    assertFalse(harness.service.floatingCard.uiState.showManualButtons)
    assertTrue(harness.service.ttsService.spokenCues.contains("朗豪坊，首小時三十二蚊"))
    assertEquals(
      "朗豪坊，首小時三十二蚊",
      ZhHkTemplates.cardDetails(
        RecommendationCard("langham", "朗豪坊", "INDOOR", 12, 180, "首小時三十二蚊", "cue", false),
      ),
    )
    Bindings.detachService(harness.service)
  }

  @Test
  fun step24_nextOptionLocalPageAfterTransition() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("FORCE_TWO_INDOOR"))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "有冇第二個"))
    assertEquals(0, harness.service.selectedCardIndex)
    assertFalse(harness.service.ttsService.spokenCues.contains("第二張室內場口播"))
    harness.clock.advance(NativeConfig.CARD_PAGE_TRANSITION_MS.toLong())
    assertEquals(1, harness.service.selectedCardIndex)
    assertEquals(TripState.EXPANDED, harness.service.tripStateMachine.state)
    assertTrue(harness.service.floatingCard.uiState.visible)
    assertEquals("CP_INDOOR_B", harness.service.floatingCard.uiState.carparkId)
    assertEquals(NativeConfig.CARD_PAGE_TRANSITION_MS, harness.service.floatingCard.uiState.transitionMs)
    assertTrue(harness.service.ttsService.spokenCues.contains("第二張室內場口播"))
    Bindings.detachService(harness.service)
  }

  @Test
  fun step24_lastPageNextSpeaksNoMoreAndKeepsIndex() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("FORCE_TWO_INDOOR"))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    harness.engine.finish()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "有冇第二個"))
    harness.clock.advance(NativeConfig.CARD_PAGE_TRANSITION_MS.toLong())
    harness.engine.finish()
    val index = harness.service.selectedCardIndex
    val before = harness.service.ttsService.spokenCues.size
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "有冇第二個"))
    assertEquals(index, harness.service.selectedCardIndex)
    assertEquals(1, index)
    assertTrue(harness.service.ttsService.spokenCues.drop(before).contains(ZhHkTemplates.NO_MORE_OPTIONS))
    assertTrue(harness.service.floatingCard.uiState.visible)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step24_expandedTimeoutGoodStillCommitsButNotLowConfidenceOrSelfHearing() {
    val committed = openHarness()
    assertTrue(committed.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    committed.engine.finish()
    committed.clock.advance(waitMs())
    assertEquals(com.hkparking.copilot.voice.PendingQuestion.NONE, committed.service.currentPendingQuestion)
    assertTrue(committed.service.floatingCard.uiState.visible)
    assertEquals(0, committed.service.selectedCardIndex)
    assertTrue(committed.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(TripState.NAV_TO_GATE, committed.service.tripStateMachine.state)
    Bindings.detachService(committed.service)

    val selfHear = openHarness()
    assertTrue(selfHear.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertTrue(selfHear.service.ttsService.isSpeaking)
    val posts = Bindings.apiTransport.postCount
    assertTrue(selfHear.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(posts, Bindings.apiTransport.postCount)
    assertNotEquals(TripState.NAV_TO_GATE, selfHear.service.tripStateMachine.state)
    assertTrue(selfHear.service.floatingCard.uiState.visible)
    Bindings.detachService(selfHear.service)

    val low = openHarness()
    assertTrue(low.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    low.engine.finish()
    low.clock.advance(waitMs())
    val lowPosts = Bindings.apiTransport.postCount
    Bindings.voiceInput.injectUtterance("好", 0.1)
    assertEquals(lowPosts, Bindings.apiTransport.postCount)
    assertNotEquals(TripState.NAV_TO_GATE, low.service.tripStateMachine.state)
    assertTrue(low.service.floatingCard.uiState.visible)
    Bindings.detachService(low.service)
  }

  @Test
  fun step24_cardConfirmAndTapCommit() {
    val confirm = openHarness()
    assertTrue(confirm.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertFalse(confirm.service.floatingCard.uiState.showManualButtons)
    assertTrue(confirm.receiver.dispatchEvent("CARD_CONFIRM"))
    assertEquals(TripState.NAV_TO_GATE, confirm.service.tripStateMachine.state)
    assertFalse(confirm.service.floatingCard.uiState.visible)
    assertEquals(listOf("朗豪坊"), Bindings.navigation.launchedTargets)
    Bindings.detachService(confirm.service)

    val tap = openHarness()
    assertTrue(tap.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertTrue(tap.receiver.dispatchEvent("CARD_TAP"))
    assertEquals(TripState.NAV_TO_GATE, tap.service.tripStateMachine.state)
    assertFalse(tap.service.floatingCard.uiState.visible)
    Bindings.detachService(tap.service)
  }

  @Test
  fun step24_announceManualButtonsCancelAndSwipeDismiss() {
    val cancel = openHarness(mode = "ANNOUNCE_MANUAL")
    assertTrue(cancel.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(cancel.receiver.dispatchEvent("BUBBLE_TAP"))
    assertTrue(cancel.service.manualCardVisible)
    assertTrue(cancel.service.floatingCard.uiState.visible)
    assertTrue(cancel.service.floatingCard.uiState.showManualButtons)
    assertEquals(FloatingCardView.MANUAL_ACTIONS, cancel.service.floatingCard.uiState.manualActions)
    assertTrue(cancel.service.ttsService.isSpeaking)
    assertTrue(cancel.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "取消"))
    assertFalse(cancel.service.floatingCard.uiState.visible)
    assertFalse(cancel.service.ttsService.isSpeaking)
    assertNotEquals(TripState.EXPANDED, cancel.service.tripStateMachine.state)
    Bindings.detachService(cancel.service)

    val swipe = openHarness(mode = "ANNOUNCE_MANUAL")
    assertTrue(swipe.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(swipe.receiver.dispatchEvent("BUBBLE_TAP"))
    assertTrue(swipe.service.ttsService.isSpeaking)
    assertTrue(swipe.receiver.dispatchEvent("CARD_SWIPE"))
    assertFalse(swipe.service.floatingCard.uiState.visible)
    assertFalse(swipe.service.ttsService.isSpeaking)
    assertEquals(TripState.NEARBY_READY, swipe.service.tripStateMachine.state)
    Bindings.detachService(swipe.service)
  }

  @Test
  fun step24_lockFullKeepsCardAndNextRerecommends() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("FORCE_TWO_INDOOR"))
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    harness.engine.finish()
    Bindings.apiTransport.armLockFull()
    assertTrue(harness.receiver.dispatchEvent("CARD_CONFIRM"))
    assertTrue(harness.service.floatingCard.uiState.visible)
    assertEquals("CP_INDOOR_A", harness.service.floatingCard.uiState.carparkId)
    assertNotEquals(TripState.NAV_TO_GATE, harness.service.tripStateMachine.state)
    assertTrue(harness.service.ttsService.spokenCues.contains("呢度滿咗"))
    Bindings.apiTransport.setFixtureLoader { name ->
      if (name == FixtureApiTransport.TWO_INDOOR_FIXTURE) RERECOMMEND else null
    }
    assertTrue(harness.receiver.dispatchEvent("FORCE_TWO_INDOOR"))
    assertTrue(harness.receiver.dispatchEvent("CARD_NEXT"))
    assertEquals(0, harness.service.selectedCardIndex)
    assertEquals("CP_RERECOMMEND", harness.service.floatingCard.uiState.carparkId)
    assertTrue(harness.service.floatingCard.uiState.visible)
    assertTrue(harness.service.ttsService.spokenCues.contains("重查場口播"))
    assertFalse(harness.service.ttsService.spokenCues.contains("第二張室內場口播"))
    Bindings.detachService(harness.service)
  }

  @Test
  fun step24_approachMutedCanExpandAndSampleDoesNotKick() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(harness.receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    assertFalse(harness.service.floatingCard.uiState.visible)
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertEquals(TripState.EXPANDED, harness.service.tripStateMachine.state)
    assertTrue(harness.service.floatingCard.uiState.visible)
    assertTrue(harness.receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.EXPANDED, harness.service.tripStateMachine.state)
    assertTrue(harness.receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertEquals(TripState.EXPANDED, harness.service.tripStateMachine.state)
    assertTrue(harness.receiver.dispatchEvent("CARD_CANCEL"))
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    assertFalse(harness.service.floatingCard.uiState.visible)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step24_emptyCacheShowDetailsRecommendsThenShows() {
    val harness = openHarness()
    val before = Bindings.apiTransport.postCount
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertTrue(Bindings.apiTransport.postCount > before)
    assertTrue(harness.service.floatingCard.uiState.visible)
    assertEquals("朗豪坊", harness.service.floatingCard.uiState.title)
    assertTrue(harness.service.ttsService.spokenCues.contains("朗豪坊，首小時三十二蚊"))
    Bindings.detachService(harness.service)
  }

  @Test
  fun step24_confirmWindowCancelDismissesAndDetailsShowsCard() {
    val details = openHarness()
    assertTrue(details.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    details.engine.finish()
    assertEquals(com.hkparking.copilot.voice.PendingQuestion.RECOMMENDATION_CONFIRM, details.service.currentPendingQuestion)
    assertFalse(details.service.floatingCard.uiState.visible)
    assertTrue(details.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertTrue(details.service.floatingCard.uiState.visible)
    assertEquals(TripState.EXPANDED, details.service.tripStateMachine.state)
    Bindings.detachService(details.service)

    val cancel = openHarness()
    assertTrue(cancel.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    cancel.engine.finish()
    assertEquals(com.hkparking.copilot.voice.PendingQuestion.RECOMMENDATION_CONFIRM, cancel.service.currentPendingQuestion)
    assertTrue(cancel.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "取消"))
    assertFalse(cancel.service.floatingCard.uiState.visible)
    assertFalse(cancel.service.ttsService.isSpeaking)
    assertEquals(TripState.NEARBY_READY, cancel.service.tripStateMachine.state)
    Bindings.detachService(cancel.service)
  }

  @Test
  fun step24_pageTransitionCancelledBySwipeDoesNotRevive() {
    val swipe = openHarness()
    assertTrue(swipe.receiver.dispatchEvent("FORCE_TWO_INDOOR"))
    assertTrue(swipe.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    swipe.engine.finish()
    assertTrue(swipe.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "有冇第二個"))
    assertTrue(swipe.receiver.dispatchEvent("CARD_SWIPE"))
    swipe.clock.advance(NativeConfig.CARD_PAGE_TRANSITION_MS.toLong())
    assertFalse(swipe.service.floatingCard.uiState.visible)
    assertEquals(0, swipe.service.selectedCardIndex)
    assertFalse(swipe.service.ttsService.spokenCues.contains("第二張室內場口播"))
    Bindings.detachService(swipe.service)

    val cancel = openHarness()
    assertTrue(cancel.receiver.dispatchEvent("FORCE_TWO_INDOOR"))
    assertTrue(cancel.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    cancel.engine.finish()
    assertTrue(cancel.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "有冇第二個"))
    assertTrue(cancel.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "取消"))
    cancel.clock.advance(NativeConfig.CARD_PAGE_TRANSITION_MS.toLong())
    assertFalse(cancel.service.floatingCard.uiState.visible)
    assertEquals(0, cancel.service.selectedCardIndex)
    assertFalse(cancel.service.ttsService.spokenCues.contains("第二張室內場口播"))
    Bindings.detachService(cancel.service)
  }

  @Test
  fun step24_greyTapDoesNotShowCardAndDegradedDetailsStayConservative() {
    val grey = openHarness(mode = "ANNOUNCE_MANUAL")
    grey.service.tripStateMachine.onNetworkFailure()
    assertEquals(TripState.GREY_BREATHING, grey.service.tripStateMachine.state)
    assertTrue(grey.receiver.dispatchEvent("BUBBLE_TAP"))
    assertTrue(grey.service.manualRetryRequested)
    assertTrue(grey.service.manualCardVisible)
    assertFalse(grey.service.floatingCard.uiState.visible)
    assertEquals(TripState.GREY_BREATHING, grey.service.tripStateMachine.state)
    Bindings.detachService(grey.service)

    val stale = openHarness()
    assertTrue(stale.receiver.dispatchEvent("FORCE_DEGRADED"))
    assertTrue(stale.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertEquals(ZhHkTemplates.METER_STALE_SPOKEN, stale.service.floatingCard.uiState.subtitle)
    assertTrue(stale.service.ttsService.spokenCues.contains(ZhHkTemplates.METER_STALE_SPOKEN))
    assertFalse(stale.service.ttsService.spokenCues.contains("十二"))
    Bindings.detachService(stale.service)
  }

  @Test
  fun step24_engineShutdownDropsCard() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertTrue(harness.service.floatingCard.uiState.visible)
    assertTrue(harness.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    harness.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(harness.service.floatingCard.uiState.visible)
    Bindings.detachService(harness.service)
  }

  private fun waitMs(): Long = (NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong()

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
    private const val RERECOMMEND = """
      {"status":"SUCCESS","source_status":"ok","cards":[
        {"carpark_id":"CP_RERECOMMEND","name":"重查場","type":"INDOOR","vacancies":2,"fee_summary":"首小時十蚊","spoken_cue":"重查場口播","is_stale":false}
      ]}
    """
  }
}
