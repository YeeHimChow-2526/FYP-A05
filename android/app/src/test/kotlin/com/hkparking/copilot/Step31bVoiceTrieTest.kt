package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.poi.LocalPoiTrie
import com.hkparking.copilot.poi.PoiTrieProvider
import com.hkparking.copilot.poi.TrieResult
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.CommandCode
import com.hkparking.copilot.voice.PendingInvalidation
import com.hkparking.copilot.voice.PendingQuestion
import com.hkparking.copilot.voice.TtsEngine
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.ZhHkTemplates
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step31bVoiceTrieTest {
  @Test
  fun step31b_01_exactHitLocksWithCoordsAndDirectLock() {
    val h = openHarness()
    val modesBefore = h.service.launchedRecommendModes.size
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去朗豪坊"))
    assertEquals("朗豪坊", h.service.tripStateMachine.destinationName)
    assertEquals(22.318, h.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals("m:1", h.service.tripStateMachine.destinationPoiId)
    assertEquals(ZhHkTemplates.directLock("朗豪坊"), h.service.ttsService.spokenCues.last())
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    assertEquals(modesBefore, h.service.launchedRecommendModes.size)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_02_prefixHitUsesImplicitLockEcho() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去太古城道"))
    assertEquals("太古城道", h.service.tripStateMachine.destinationName)
    assertEquals("m:2", h.service.tripStateMachine.destinationPoiId)
    val cue = h.service.ttsService.spokenCues.last()
    assertEquals(ZhHkTemplates.implicitLockEcho("東", "太古城中心"), cue)
    assertFalse(cue.contains("【"))
    assertFalse(cue.contains("["))
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_03_ambiguousNoDestinationAsksClarify() {
    val h = openHarness(micActive = true)
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    assertEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    val expected = ZhHkTemplates.withVoiceReplyPrompt(
      ZhHkTemplates.PLACE_CLARIFY,
      TripVoiceMode.FULL_VOICE,
      isMicFgsActive = true,
    )
    assertEquals(expected, h.service.ttsService.spokenCues.last())
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    assertFalse(h.service.tripStateMachine.destinationLocked)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_04_ambiguousKeepsLockedDestination() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去朗豪坊"))
    assertEquals("朗豪坊", h.service.tripStateMachine.destinationName)
    assertEquals("m:1", h.service.tripStateMachine.destinationPoiId)
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    assertEquals("朗豪坊", h.service.tripStateMachine.destinationName)
    assertEquals("m:1", h.service.tripStateMachine.destinationPoiId)
    assertEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_05_clarifyAnswerDistrictLocks() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    val beforeCue = h.service.ttsService.spokenCues.size
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "大埔區"))
    assertEquals("灣仔村", h.service.tripStateMachine.destinationName)
    assertEquals(22.45, h.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals("a:2", h.service.tripStateMachine.destinationPoiId)
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    assertNotEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertEquals(
      ZhHkTemplates.implicitLockEcho("大埔", "灣仔村"),
      h.service.ttsService.spokenCues.drop(beforeCue).last(),
    )
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val body = Bindings.apiTransport.recommendBodies.last()
    assertTrue(body.contains("\"dest_name\":\"灣仔村\""))
    assertTrue(body.contains("\"dest_lat\":22.45"))
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_06_clarifyHaoAndUnmatchedIgnored() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    val cuesBefore = h.service.ttsService.spokenCues.size
    val modesBefore = h.service.launchedRecommendModes.size
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "東涌"))
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "九龍城"))
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    assertFalse(h.service.tripStateMachine.destinationLocked)
    assertEquals(cuesBefore, h.service.ttsService.spokenCues.size)
    assertEquals(modesBefore, h.service.launchedRecommendModes.size)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_07_clarifyNewDestinationReResolves() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去朗豪坊"))
    assertEquals("朗豪坊", h.service.tripStateMachine.destinationName)
    assertEquals("m:1", h.service.tripStateMachine.destinationPoiId)
    assertEquals(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED, h.service.lastPendingInvalidation)
    assertEquals(ZhHkTemplates.directLock("朗豪坊"), h.service.ttsService.spokenCues.last())
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_08_clarifyTimeoutKeepsAlert() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    h.clock.advance(waitMs())
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    assertEquals(PendingInvalidation.TIMEOUT, h.service.lastPendingInvalidation)
    assertEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertFalse(h.service.tripStateMachine.destinationLocked)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_09_missLocksByNameNoAlert() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去火星"))
    assertEquals("火星", h.service.tripStateMachine.destinationName)
    assertNull(h.service.tripStateMachine.destLat)
    assertNotEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertEquals(ZhHkTemplates.directLock("火星"), h.service.ttsService.spokenCues.last())
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val body = Bindings.apiTransport.recommendBodies.last()
    assertTrue(body.contains("\"dest_lat\":0.0") || body.contains("\"dest_lat\":0"))
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_10_nullProviderKeepsTodayBehaviour() {
    val h = openHarness(useTrie = false)
    val cuesBefore = h.service.ttsService.spokenCues.size
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    assertEquals("灣仔", h.service.tripStateMachine.destinationName)
    assertTrue(h.service.tripStateMachine.destinationLocked)
    assertNull(h.service.tripStateMachine.destLat)
    assertNotEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    assertEquals(cuesBefore, h.service.ttsService.spokenCues.size)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_11_capsuleTapDuringClarifyOpensListenNoCard() {
    val h = openHarness(micActive = false)
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    assertTrue(h.service.ttsService.spokenCues.last().contains(ZhHkTemplates.TAP_CAPSULE))
    h.engine.finish()
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    assertTrue(h.receiver.dispatchEvent("BUBBLE_TAP"))
    assertFalse(h.service.manualCardVisible)
    assertTrue(Bindings.voiceInput.isListening)
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_12_announceManualAmbiguousNoPending() {
    val h = openHarness(mode = "ANNOUNCE_MANUAL")
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    assertEquals(TripState.ALERT_BREATHING, h.service.tripStateMachine.state)
    assertEquals(ZhHkTemplates.PLACE_CLARIFY, h.service.ttsService.spokenCues.last())
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    assertFalse(h.service.ttsService.spokenCues.any { it.contains("收到，為你鎖定") })
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_13_protectedMallsHitInRealAsset() {
    val trie = LocalPoiTrie.fromJson(assetText())
    val cases = listOf(
      "海港城" to ("ig:530031" to "油尖旺"),
      "又一城" to ("ig:530276" to "深水埗"),
      "太古城" to ("ig:533687" to "東"),
      "太古城中心" to ("ig:533687" to "東"),
      "Harbour City" to ("ig:530031" to "油尖旺"),
      "Festival Walk" to ("ig:530276" to "深水埗"),
      "Cityplaza" to ("ig:533687" to "東"),
    )
    for ((q, expected) in cases) {
      val r = trie.lookup(q)
      assertTrue("expected Hit for $q, got $r", r is TrieResult.Hit)
      val hit = r as TrieResult.Hit
      assertEquals(expected.first, hit.poi.id)
      assertEquals(expected.second, hit.poi.district)
    }
  }

  @Test
  fun step31b_14_clarifyCueRepeatsAfterLock() {
    val h = openHarness()
    val sessionId = h.service.tripStateMachine.sessionId
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "大埔"))
    h.engine.finish()
    val countAfterLock = h.service.tripStateMachine.alertSpeakCount
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去新村"))
    assertEquals(countAfterLock + 1, h.service.tripStateMachine.alertSpeakCount)
    val clarifyCues = h.service.ttsService.spokenCues.filter {
      it.startsWith(ZhHkTemplates.PLACE_CLARIFY) || it == ZhHkTemplates.PLACE_CLARIFY
    }
    assertEquals(2, clarifyCues.size)
    assertEquals(sessionId, h.service.tripStateMachine.sessionId)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_15_clarifyCueNotRepeatedWithoutLock() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    val countAfterFirst = h.service.tripStateMachine.alertSpeakCount
    val clarifyBefore = h.service.ttsService.spokenCues.count {
      it.startsWith(ZhHkTemplates.PLACE_CLARIFY) || it == ZhHkTemplates.PLACE_CLARIFY
    }
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去新村"))
    assertEquals(countAfterFirst, h.service.tripStateMachine.alertSpeakCount)
    val clarifyAfter = h.service.ttsService.spokenCues.count {
      it.startsWith(ZhHkTemplates.PLACE_CLARIFY) || it == ZhHkTemplates.PLACE_CLARIFY
    }
    assertEquals(clarifyBefore, clarifyAfter)
    assertEquals(1, clarifyAfter)
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    val ids = h.service.currentClarifyCandidates.map { it.id }.toSet()
    assertEquals(setOf("a:4", "a:5"), ids)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_16_tsmLockClearsOnlyPlaceClarification() {
    val machine = started(StartupEntry.A_2)
    machine.onPlaceClarificationNeeded()
    assertEquals(1, machine.alertSpeakCount)
    machine.onDriverActionFailed(networkCause = false)
    assertEquals(2, machine.alertSpeakCount)
    machine.onDestinationSet(destinationName = "X")
    val afterLock = machine.alertSpeakCount
    machine.onPlaceClarificationNeeded()
    assertEquals(afterLock + 1, machine.alertSpeakCount)
    machine.onDriverActionFailed(networkCause = false)
    assertEquals(afterLock + 1, machine.alertSpeakCount)
  }

  @Test
  fun step31b_17_lockClearsDeferredClarification() {
    val machine = started(StartupEntry.A_2)
    machine.onNetworkFailure()
    machine.onPlaceClarificationNeeded()
    val before = machine.alertSpeakCount
    machine.onDestinationSet(destinationName = "X")
    machine.onRequestSuccess()
    assertEquals(before, machine.alertSpeakCount)
    assertNotEquals(TripState.ALERT_BREATHING, machine.state)
  }

  @Test
  fun step31b_18_findDuringClarifyRunsTodayFind() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    val modesBefore = h.service.launchedRecommendModes.size
    val clarifyCuesBefore = h.service.ttsService.spokenCues.count {
      it.startsWith(ZhHkTemplates.PLACE_CLARIFY) || it == ZhHkTemplates.PLACE_CLARIFY
    }
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    // Clarify cleared; today's FIND may arm RECOMMENDATION_CONFIRM after a successful recommend.
    assertNotEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    assertTrue(h.service.currentClarifyCandidates.isEmpty())
    assertEquals(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED, h.service.lastPendingInvalidation)
    assertEquals(modesBefore + 1, h.service.launchedRecommendModes.size)
    assertEquals(
      com.hkparking.copilot.net.QueryTriggerMode.DRIVER_ACTIVE,
      h.service.launchedRecommendModes.last(),
    )
    assertFalse(h.service.tripStateMachine.destinationLocked)
    val clarifyCuesAfter = h.service.ttsService.spokenCues.count {
      it.startsWith(ZhHkTemplates.PLACE_CLARIFY) || it == ZhHkTemplates.PLACE_CLARIFY
    }
    assertEquals(clarifyCuesBefore, clarifyCuesAfter)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_19_cancelDuringClarifyClearsPending() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "取消"))
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    assertEquals(PendingInvalidation.CANCEL, h.service.lastPendingInvalidation)
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "大埔"))
    assertFalse(h.service.tripStateMachine.destinationLocked)
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_20_clarifyStepBSameDistrictLandmarkLocks() {
    // RT-2: step (b) via same-district non-district name 「大埔超級城」
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "大埔超級城"))
    assertEquals("灣仔村", h.service.tripStateMachine.destinationName)
    assertEquals("a:2", h.service.tripStateMachine.destinationPoiId)
    assertEquals(22.45, h.service.tripStateMachine.destLat!!, 0.0001)
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    assertEquals(
      ZhHkTemplates.implicitLockEcho("大埔", "灣仔村"),
      h.service.ttsService.spokenCues.last(),
    )
    Bindings.detachService(h.service)
  }

  @Test
  fun step31b_21_ignoredClarifyAnswersDoNotArmSpeechHeard() {
    // RT-4: after ignored answers, timeout still fires (speechHeard stays false)
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "泊車助手，去灣仔"))
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "東涌"))
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    assertEquals(PendingQuestion.CLARIFY_DESTINATION, h.service.currentPendingQuestion)
    h.clock.advance(waitMs())
    assertEquals(PendingQuestion.NONE, h.service.currentPendingQuestion)
    assertEquals(PendingInvalidation.TIMEOUT, h.service.lastPendingInvalidation)
    Bindings.detachService(h.service)
  }

  private fun waitMs(): Long = (NativeConfig.LISTEN_OPENING_WAIT_S * 1000).toLong()

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
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    service.reportFgsStarted()
    val receiver = Bindings.activeReceiver!!
    // Do NOT START_TRIP (would lock DEFAULT 朗豪坊 without trie coords).
    Bindings.locationSource.start()
    Bindings.voiceInput.startListening(hotword = true)
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = mode))
    if (mode == "FULL_VOICE" && !micActive) {
      service.onVoiceCapabilityApplied(service.capabilityChecker.currentSnapshot(false), micFgsActive = false)
    }
    return Harness(clock, service, receiver, engine)
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

    private fun assetText(): String {
      val candidates = listOf(
        java.io.File("src/main/assets/hk_poi_master.json"),
        java.io.File("app/src/main/assets/hk_poi_master.json"),
        java.io.File("android/app/src/main/assets/hk_poi_master.json"),
      )
      return candidates.first { it.isFile }.readText(Charsets.UTF_8)
    }
  }
}
