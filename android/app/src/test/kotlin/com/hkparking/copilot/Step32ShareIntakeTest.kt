package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.poi.LocalPoiTrie
import com.hkparking.copilot.poi.PoiTrieProvider
import com.hkparking.copilot.share.B2MissResolvers
import com.hkparking.copilot.share.ShareIntake
import com.hkparking.copilot.share.ShareOutcome
import com.hkparking.copilot.share.ShareTextParser
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.PendingQuestion
import com.hkparking.copilot.voice.TtsEngine
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step32ShareIntakeTest {
  @Test
  fun step32_01_parseTitleAndShortUrl() {
    val p = ShareTextParser.parse("朗豪坊 https://maps.app.goo.gl/abc")
    assertEquals("朗豪坊", p.title)
    assertTrue(p.hasUrl)
    assertNull(p.lat)
    assertNull(p.lng)
  }

  @Test
  fun step32_02_parseMultilineTakesFirstLine() {
    val p = ShareTextParser.parse("朗豪坊\n旺角亞皆老街8號\nhttps://maps.app.goo.gl/abc")
    assertEquals("朗豪坊", p.title)
    assertTrue(p.hasUrl)
  }

  @Test
  fun step32_03_parsePlainDroppedPin() {
    val p = ShareTextParser.parse("22.318, 114.168")
    assertNull(p.title)
    assertEquals(22.318, p.lat!!, 0.0001)
    assertEquals(114.168, p.lng!!, 0.0001)
  }

  @Test
  fun step32_04_parseCoordsInsideUrl() {
    val a = ShareTextParser.parse("https://maps.google.com/?q=22.30123,114.17234")
    assertEquals(22.30123, a.lat!!, 0.00001)
    assertEquals(114.17234, a.lng!!, 0.00001)
    assertTrue(a.hasUrl)
    assertNull(a.title)

    val b = ShareTextParser.parse("https://www.google.com/maps/place/Foo/@22.30123,114.17234,17z")
    assertEquals(22.30123, b.lat!!, 0.00001)
    assertEquals(114.17234, b.lng!!, 0.00001)
    assertTrue(b.hasUrl)
    assertNull(b.title)
  }

  @Test
  fun step32_05_parseUrlOnlyHasNoTitle() {
    val p = ShareTextParser.parse("https://maps.app.goo.gl/abc")
    assertNull(p.title)
    assertTrue(p.hasUrl)
    assertNull(p.lat)
    assertNull(p.lng)
  }

  @Test
  fun step32_06_parseBlankAndNull() {
    for (raw in listOf(null, "   ")) {
      val p = ShareTextParser.parse(raw)
      assertNull(p.title)
      assertNull(p.lat)
      assertNull(p.lng)
      assertFalse(p.hasUrl)
      assertTrue(p.isEmpty)
    }
  }

  @Test
  fun step32_07_parseRejectsOutOfRangeCoords() {
    val a = ShareTextParser.parse("95.1234, 200.1234")
    assertNull(a.lat)
    assertNull(a.lng)
    val b = ShareTextParser.parse("12.3, 45.6")
    assertNull(b.lat)
    assertNull(b.lng)
  }

  @Test
  fun step32_08_parseSubjectFallback() {
    val p = ShareTextParser.parse("https://maps.app.goo.gl/abc", subject = "朗豪坊")
    assertEquals("朗豪坊", p.title)
    assertTrue(p.hasUrl)
  }

  @Test
  fun step32_09_tsmBackgroundResolveAmberThenCruising() {
    val tsm = started(StartupEntry.B_2)
    assertEquals(TripState.CRUISING, tsm.state)
    tsm.onBackgroundResolveStarted()
    assertEquals(TripState.AMBER_STEADY, tsm.state)
    assertTrue(tsm.backgroundResolvePending)
    tsm.onBackgroundResolveEnded()
    assertEquals(TripState.CRUISING, tsm.state)
    assertFalse(tsm.backgroundResolvePending)
  }

  @Test
  fun step32_10_tsmBackgroundResolveKeepsHigherStates() {
    val alert = started(StartupEntry.B_2)
    alert.onPlaceClarificationNeeded()
    assertEquals(TripState.ALERT_BREATHING, alert.state)
    alert.onBackgroundResolveStarted()
    assertEquals(TripState.ALERT_BREATHING, alert.state)
    assertTrue(alert.backgroundResolvePending)

    val grey = started(StartupEntry.B_2)
    grey.onNetworkFailure()
    assertEquals(TripState.GREY_BREATHING, grey.state)
    grey.onBackgroundResolveStarted()
    assertEquals(TripState.GREY_BREATHING, grey.state)
    assertTrue(grey.backgroundResolvePending)
  }

  @Test
  fun step32_11_shareHitEchoesInFullVoiceOnly() {
    val full = openHarness(mode = "FULL_VOICE")
    val cues0 = full.service.ttsService.spokenCues.size
    full.service.onShareIntake(ShareIntake("朗豪坊", null, null, hasUrl = true))
    assertEquals("朗豪坊", full.service.tripStateMachine.destinationName)
    assertEquals(22.318, full.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals("m:1", full.service.tripStateMachine.destinationPoiId)
    assertEquals(TripState.CRUISING, full.service.tripStateMachine.state)
    assertEquals(ShareOutcome.HIT, full.service.lastShareOutcome)
    assertEquals(ZhHkTemplates.directLock("朗豪坊"), full.service.ttsService.spokenCues.drop(cues0).last())
    Bindings.detachService(full.service)

    val prefix = openHarness(mode = "FULL_VOICE")
    val cuesP = prefix.service.ttsService.spokenCues.size
    prefix.service.onShareIntake(ShareIntake("太古城道", null, null, hasUrl = false))
    assertEquals("太古城道", prefix.service.tripStateMachine.destinationName)
    assertEquals("m:2", prefix.service.tripStateMachine.destinationPoiId)
    assertEquals(
      ZhHkTemplates.implicitLockEcho("東", "太古城中心"),
      prefix.service.ttsService.spokenCues.drop(cuesP).last(),
    )
    Bindings.detachService(prefix.service)

    for (mode in listOf("ANNOUNCE_MANUAL", "MANUAL_ONLY")) {
      val h = openHarness(mode = mode)
      val before = h.service.ttsService.spokenCues.size
      h.service.onShareIntake(ShareIntake("朗豪坊", null, null, hasUrl = true))
      assertEquals(ShareOutcome.HIT, h.service.lastShareOutcome)
      assertEquals(before, h.service.ttsService.spokenCues.size)
      Bindings.detachService(h.service)
    }
  }

  @Test
  fun step32_12_sharePinLocksEvenWhenTitleMisses() {
    val h = openHarness()
    h.service.onShareIntake(ShareIntake("火星", 22.3, 114.2, hasUrl = false))
    assertEquals("火星", h.service.tripStateMachine.destinationName)
    assertEquals(22.3, h.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals(114.2, h.service.tripStateMachine.destLng!!, 0.0001)
    assertNull(h.service.tripStateMachine.destinationPoiId)
    assertEquals(ShareOutcome.PIN, h.service.lastShareOutcome)
    assertNotEquals(TripState.AMBER_STEADY, h.service.tripStateMachine.state)
    assertFalse(h.service.tripStateMachine.backgroundResolvePending)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_13_sharePinWithoutTitleUsesCoordName() {
    val h = openHarness()
    h.service.onShareIntake(ShareIntake(null, 22.3, 114.2, hasUrl = false))
    assertEquals("22.3,114.2", h.service.tripStateMachine.destinationName)
    assertEquals(22.3, h.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals(114.2, h.service.tripStateMachine.destLng!!, 0.0001)
    assertEquals(ShareOutcome.PIN, h.service.lastShareOutcome)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_14_shareAmbiguousReusesClarify() {
    val h = openHarness(micActive = false)
    h.service.onShareIntake(ShareIntake("灣仔", null, null, hasUrl = false))
    assertEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    assertEquals(ShareOutcome.AMBIGUOUS, h.service.lastShareOutcome)
    val expected = ZhHkTemplates.withVoiceReplyPrompt(
      ZhHkTemplates.PLACE_CLARIFY,
      TripVoiceMode.FULL_VOICE,
      isMicFgsActive = false,
    )
    assertEquals(expected, h.service.ttsService.spokenCues.last())
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "大埔"))
    assertEquals("灣仔村", h.service.tripStateMachine.destinationName)
    assertEquals("a:2", h.service.tripStateMachine.destinationPoiId)
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_15_shareMissProductionNameLocks() {
    val h = openHarness()
    h.service.b2MissResolver = B2MissResolvers.NAME_LOCK
    val cuesBefore = h.service.ttsService.spokenCues.size
    h.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    assertEquals("火星", h.service.tripStateMachine.destinationName)
    assertNull(h.service.tripStateMachine.destLat)
    assertEquals(TripState.CRUISING, h.service.tripStateMachine.state)
    assertNotEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertFalse(h.service.tripStateMachine.backgroundResolvePending)
    assertEquals(ShareOutcome.MISS_NAME_LOCKED, h.service.lastShareOutcome)
    assertEquals(cuesBefore, h.service.ttsService.spokenCues.size)
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val body = Bindings.apiTransport.recommendBodies.last()
    assertTrue(body.contains("\"dest_lat\":0.0") || body.contains("\"dest_lat\":0"))
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_16_shareMissSimEntersAmberAndWaits() {
    val h = openHarness()
    h.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    assertEquals(TripState.AMBER_STEADY, h.service.tripStateMachine.state)
    assertTrue(h.service.tripStateMachine.backgroundResolvePending)
    assertEquals("火星", h.service.pendingShareTitleForTest)
    assertFalse(h.service.tripStateMachine.destinationLocked)
    assertEquals(ShareOutcome.MISS_PENDING, h.service.lastShareOutcome)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_17_simResolveOkFallsBackToPendingTitle() {
    val h = openHarness()
    h.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    assertTrue(h.receiver.dispatchEvent("TRIE_MISS_RESOLVE_OK"))
    assertEquals("火星", h.service.tripStateMachine.destinationName)
    assertNull(h.service.tripStateMachine.destLat)
    assertEquals(TripState.CRUISING, h.service.tripStateMachine.state)
    assertNull(h.service.pendingShareTitleForTest)
    assertFalse(h.service.tripStateMachine.backgroundResolvePending)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_18_simResolveOkStringHitLocksCoords() {
    val h = openHarness()
    h.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    assertTrue(h.receiver.dispatchEvent("TRIE_MISS_RESOLVE_OK", stringValue = "朗豪坊"))
    assertEquals("朗豪坊", h.service.tripStateMachine.destinationName)
    assertEquals("m:1", h.service.tripStateMachine.destinationPoiId)
    assertEquals(22.318, h.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals(TripState.CRUISING, h.service.tripStateMachine.state)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_19_simNetworkFailGreySpeaksOnce() {
    val h = openHarness(mode = "FULL_VOICE")
    h.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    assertTrue(h.receiver.dispatchEvent("TRIE_MISS_NETWORK_FAIL"))
    assertTrue(h.receiver.dispatchEvent("TRIE_MISS_NETWORK_FAIL"))
    assertEquals(TripState.GREY_BREATHING, h.service.tripStateMachine.state)
    assertEquals(1, h.service.ttsService.spokenCues.count { it == ZhHkTemplates.NETWORK_FAILED })
    assertEquals("火星", h.service.pendingShareTitleForTest)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_20_simNetworkFailSilentWhenMutedOrManual() {
    val muted = openHarness(mode = "FULL_VOICE")
    muted.service.onShareIntake(ShareIntake("朗豪坊", null, null, hasUrl = false))
    assertTrue(muted.receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, muted.service.tripStateMachine.state)
    muted.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    val before = muted.service.ttsService.spokenCues.count { it == ZhHkTemplates.NETWORK_FAILED }
    assertTrue(muted.receiver.dispatchEvent("TRIE_MISS_NETWORK_FAIL"))
    assertEquals(TripState.APPROACH_MUTED, muted.service.tripStateMachine.state)
    assertEquals(before, muted.service.ttsService.spokenCues.count { it == ZhHkTemplates.NETWORK_FAILED })
    Bindings.detachService(muted.service)

    val manual = openHarness(mode = "MANUAL_ONLY")
    manual.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    val beforeM = manual.service.ttsService.spokenCues.count { it == ZhHkTemplates.NETWORK_FAILED }
    assertTrue(manual.receiver.dispatchEvent("TRIE_MISS_NETWORK_FAIL"))
    assertEquals(TripState.GREY_BREATHING, manual.service.tripStateMachine.state)
    assertEquals(beforeM, manual.service.ttsService.spokenCues.count { it == ZhHkTemplates.NETWORK_FAILED })
    Bindings.detachService(manual.service)
  }

  @Test
  fun step32_21_greyTapRetriesResolveNotRecommend() {
    val h = openHarness(mode = "FULL_VOICE")
    h.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    assertTrue(h.receiver.dispatchEvent("TRIE_MISS_NETWORK_FAIL"))
    assertEquals(TripState.GREY_BREATHING, h.service.tripStateMachine.state)
    val modesBefore = h.service.launchedRecommendModes.size
    assertTrue(h.receiver.dispatchEvent("BUBBLE_TAP"))
    assertEquals(modesBefore, h.service.launchedRecommendModes.size)
    assertEquals(TripState.GREY_BREATHING, h.service.tripStateMachine.state)
    assertEquals("火星", h.service.pendingShareTitleForTest)
    assertTrue(h.receiver.dispatchEvent("TRIE_MISS_RESOLVE_OK"))
    assertEquals("火星", h.service.tripStateMachine.destinationName)
    assertFalse(h.service.tripStateMachine.networkDegraded)
    assertNotEquals(TripState.GREY_BREATHING, h.service.tripStateMachine.state)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_22_simAmbiguousRaisesPlaceClarify() {
    val h = openHarness()
    h.service.onShareIntake(ShareIntake("火星", null, null, hasUrl = false))
    assertTrue(h.receiver.dispatchEvent("TRIE_MISS_AMBIGUOUS"))
    assertEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertNotEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    assertNull(h.service.pendingShareTitleForTest)
    assertFalse(h.service.tripStateMachine.backgroundResolvePending)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_23_shareUrlOnlyRaisesAlertNoLock() {
    val h = openHarness()
    h.service.onShareIntake(ShareIntake(null, null, null, hasUrl = true))
    assertEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertFalse(h.service.tripStateMachine.destinationLocked)
    assertEquals(ShareOutcome.NO_TITLE, h.service.lastShareOutcome)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_24_shareWhileRunningKeepsSession() {
    val h = openHarness()
    h.service.onShareIntake(ShareIntake("朗豪坊", null, null, hasUrl = true))
    val session = h.service.tripStateMachine.sessionId
    assertNotNull(session)
    h.engine.finish()
    h.service.onShareIntake(ShareIntake("灣仔", null, null, hasUrl = false))
    assertEquals(session, h.service.tripStateMachine.sessionId)
    assertEquals("朗豪坊", h.service.tripStateMachine.destinationName)
    assertEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    h.service.onShareIntake(ShareIntake(null, 22.3, 114.2, hasUrl = false))
    assertEquals(session, h.service.tripStateMachine.sessionId)
    assertEquals(22.3, h.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals(ShareOutcome.PIN, h.service.lastShareOutcome)

    val activity = ShareIntentReceiverActivity(h.service)
    assertTrue(activity.launchFromShare("朗豪坊\nhttps://maps.app.goo.gl/x", null, PermissionCheckInput()))
    assertTrue(activity.fgsStartedReceived)
    assertTrue(activity.isFinished)
    assertEquals(session, h.service.tripStateMachine.sessionId)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_25_simResolveOkWithoutPendingUsesTrie() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("TRIE_MISS_RESOLVE_OK", stringValue = "朗豪坊"))
    assertEquals("朗豪坊", h.service.tripStateMachine.destinationName)
    assertEquals("m:1", h.service.tripStateMachine.destinationPoiId)
    assertEquals(22.318, h.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals(TripState.CRUISING, h.service.tripStateMachine.state)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_26_activityMissingPermissionNoFgs() {
    val clock = FakeClock()
    val service = FloatingBubbleService(clock.delayMs)
    val activity = ShareIntentReceiverActivity(service)
    assertFalse(
      activity.launchFromShare(
        "朗豪坊",
        null,
        PermissionCheckInput(fineLocationGranted = false),
      ),
    )
    assertEquals(ShareIntentReceiverActivity.MISSING_PERMISSION, activity.failureReason)
    assertTrue(activity.isFinished)
    assertNotNull(activity.lastPermissionGate?.repairNotificationText)
    assertFalse(service.isFgsRunning)
  }

  @Test
  fun step32_27_activityHandshakeAppliesShareAfterFgsStarted() {
    val h = openHarnessUnstarted()
    val activity = ShareIntentReceiverActivity(h.service, h.clock::now)
    assertTrue(
      activity.launchFromShare(
        "朗豪坊 https://maps.app.goo.gl/abc",
        null,
        PermissionCheckInput(),
      ),
    )
    assertFalse(activity.fgsStartedReceived)
    assertFalse(h.service.tripStateMachine.destinationLocked)
    h.service.reportFgsStarted()
    assertTrue(activity.fgsStartedReceived)
    assertTrue(activity.isFinished)
    assertEquals("朗豪坊", h.service.tripStateMachine.destinationName)
    assertEquals("m:1", h.service.tripStateMachine.destinationPoiId)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, h.service.activeFgsType)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_28_activityHandshakeTimeoutDropsShare() {
    val h = openHarnessUnstarted()
    val activity = ShareIntentReceiverActivity(h.service, h.clock::now)
    assertTrue(activity.launchFromShare("朗豪坊", null, PermissionCheckInput()))
    h.clock.advance(NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS)
    activity.onHandshakeTick()
    assertTrue(activity.bootstrapTimedOut)
    assertEquals(ShareIntentReceiverActivity.FGS_BOOTSTRAP_TIMEOUT, activity.failureReason)
    assertTrue(activity.isFinished)
    h.service.reportFgsStarted()
    assertFalse(h.service.tripStateMachine.destinationLocked)
    Bindings.detachService(h.service)
  }

  @Test
  fun step32_29_activityEmptyTextFinishesWithoutFgs() {
    val clock = FakeClock()
    val service = FloatingBubbleService(clock.delayMs)
    val a = ShareIntentReceiverActivity(service)
    assertFalse(a.launchFromShare(null, null, PermissionCheckInput()))
    assertEquals(ShareIntentReceiverActivity.SHARE_EMPTY, a.failureReason)
    assertTrue(a.isFinished)
    assertFalse(service.isFgsRunning)

    val b = ShareIntentReceiverActivity(service)
    assertFalse(b.launchFromShare("  ", "", PermissionCheckInput()))
    assertEquals(ShareIntentReceiverActivity.SHARE_EMPTY, b.failureReason)
    assertTrue(b.isFinished)
    assertFalse(service.isFgsRunning)
  }

  private fun openHarness(
    micActive: Boolean = true,
    mode: String = "FULL_VOICE",
    useTrie: Boolean = true,
  ): Harness {
    val clock = FakeClock()
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    Bindings.voiceInput.stopListening()
    val service = FloatingBubbleService(clock.delayMs)
    if (useTrie) {
      service.poiTrieProvider = PoiTrieProvider { fixtureTrie }
    } else {
      service.poiTrieProvider = null
    }
    val engine = HoldEngine()
    assertTrue(service.ttsService.attachEngine(engine))
    service.startInForeground(StartupEntry.B_2, recordAudioGranted = false)
    service.reportFgsStarted()
    val receiver = Bindings.activeReceiver!!
    Bindings.locationSource.start()
    Bindings.voiceInput.startListening(hotword = true)
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = mode))
    if (mode == "FULL_VOICE" && !micActive) {
      service.onVoiceCapabilityApplied(service.capabilityChecker.currentSnapshot(false), micFgsActive = false)
    }
    return Harness(clock, service, receiver, engine)
  }

  private fun openHarnessUnstarted(): Harness {
    val clock = FakeClock()
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    Bindings.voiceInput.stopListening()
    val service = FloatingBubbleService(clock.delayMs)
    service.poiTrieProvider = PoiTrieProvider { fixtureTrie }
    val engine = HoldEngine()
    assertTrue(service.ttsService.attachEngine(engine))
    // Do not start FGS yet — Activity launchFromShare will call startInForeground.
    // Placeholder receiver; T27/T28 do not dispatch sim events before attach.
    val placeholder = SimEventReceiver(onEventHandled = {})
    return Harness(clock, service, placeholder, engine)
  }

  private fun started(entry: StartupEntry): TripStateMachine {
    val machine = TripStateMachine()
    machine.requestStart(entry = entry)
    machine.onFgsStarted()
    return machine
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

    fun now(): Long = now

    fun advance(ms: Long) {
      now += ms
      val due = waiters.filter { it.first <= now }.sortedBy { it.first }
      waiters.removeAll(due)
      due.forEach { it.second.resume(Unit) }
    }
  }

  companion object {
    private fun poi(id: String, t: String, zh: String, d: String, la: Double, lo: Double, aliases: String = "[]") =
      """{"id":"$id","t":"$t","zh":"$zh","en":"","a":$aliases,"d":"$d","la":$la,"lo":$lo}"""

    private val fixtureJson = """
      {"v":1,"built":"2026-01-01","attribution":"test","districts":[],"pois":[
        ${poi("m:1","M","朗豪坊","油尖旺",22.318,114.168, aliases="""["Langham Place"]""")},
        ${poi("a:1","A","灣仔","灣仔",22.277,114.173)},
        ${poi("a:2","A","灣仔村","大埔",22.45,114.17, aliases="""["灣仔"]""")},
        ${poi("a:3","A","大埔","大埔",22.45,114.16)},
        ${poi("a:4","A","新村","元朗",22.44,114.02)},
        ${poi("a:5","A","新村","屯門",22.39,113.97)},
        ${poi("m:2","M","太古城中心","東",22.28,114.21, aliases="""["太古城"]""")},
        ${poi("d:1","D","東區","東",22.28,114.22)},
        ${poi("m:3","M","大埔超級城","大埔",22.451,114.171)}
      ]}
    """.trimIndent()

    private val fixtureTrie: LocalPoiTrie by lazy { LocalPoiTrie.fromJson(fixtureJson) }
  }
}
