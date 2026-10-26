package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.net.CallOutcome
import com.hkparking.copilot.sim.FixtureApiTransport
import com.hkparking.copilot.sim.FixtureNetworkStatus
import com.hkparking.copilot.trip.MemoryPrefsPort
import com.hkparking.copilot.trip.SyncApi
import com.hkparking.copilot.trip.TodayTripStore
import com.hkparking.copilot.trip.TodayTripSync
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step34TodayTripStoreTest {
  @Test
  fun step34_01_upsertCandidatePersistsAndListsDesc() {
    val prefs = MemoryPrefsPort()
    val store = TodayTripStore(prefs, hktDateOf = { "2026-10-26" })
    store.upsertCandidate("s1", "cp1", "甲場", 1_000L)
    store.upsertCandidate("s2", "cp2", "乙場", 1_000L) // same ms — newest-first by insert
    val list = store.list(1_000L)
    assertEquals(2, list.size)
    assertEquals("s2", list[0].sessionId)
    assertEquals("s1", list[1].sessionId)
    val maps = store.asBridgeMaps(1_000L)
    assertEquals("s2", maps[0]["sessionId"])
    assertEquals("乙場", maps[0]["carparkName"])
    assertEquals("CANDIDATE", maps[0]["status"])
  }

  @Test
  fun step34_02_purgeOnHktDateRollover() {
    val prefs = MemoryPrefsPort()
    var day = "2026-10-26"
    val store = TodayTripStore(prefs, hktDateOf = { day })
    store.upsertCandidate("s1", "cp1", "甲場", 1_000L)
    assertEquals(1, store.list(1_000L).size)
    day = "2026-10-27"
    assertTrue(store.list(2_000L).isEmpty())
    assertEquals("[]", prefs.getString(NativeConfig.TODAY_TRIPS_JSON_KEY) ?: "[]")
  }

  @Test
  fun step34_03_clearAllDropsPendingSync() {
    val prefs = MemoryPrefsPort()
    val store = TodayTripStore(prefs, hktDateOf = { "2026-10-26" })
    store.upsertCandidate("s1", "cp1", "甲場", 1_000L)
    store.markConfirmed("s1", syncPending = true)
    assertEquals(listOf("s1"), store.pendingSyncSessionIds())
    store.clearAll()
    assertTrue(store.pendingSyncSessionIds().isEmpty())
    assertNull(store.find("s1"))
  }

  @Test
  fun step34_04_engineShutdownWithinWritesStore() {
    val h = openHarness()
    val before = Bindings.apiTransport.postCount
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    val store = h.service.todayTripStore!!
    val rows = store.list(System.currentTimeMillis())
    assertEquals(1, rows.size)
    assertEquals(TodayTripStore.STATUS_CANDIDATE, rows[0].status)
    assertNotNull(h.service.parkedCandidateSessionId)
    assertEquals(before + 1, Bindings.apiTransport.postCount)
    assertTrue(Bindings.apiTransport.lastPath.contains("release"))
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_05_engineShutdownOutsideNoStoreRow() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 0))
    val store = h.service.todayTripStore!!
    assertTrue(store.list(System.currentTimeMillis()).isEmpty())
    assertNull(h.service.parkedCandidateSessionId)
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_06_confirmOnlinePostsSyncAndOpensRatable() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    val sessionId = h.service.parkedCandidateSessionId!!
    val before = Bindings.apiTransport.postCount
    val outcome = h.service.todayTripSync!!.confirm(sessionId)
    assertTrue(outcome.ok)
    assertTrue(outcome.syncPosted)
    assertEquals(before + 1, Bindings.apiTransport.postCount)
    assertTrue(Bindings.apiTransport.lastPath.contains("sync"))
    val body = Bindings.apiTransport.postBodies.last().second
    assertFalse(body.contains("\"timestamp\":0"))
    val rec = h.service.todayTripStore!!.find(sessionId)!!
    assertEquals(TodayTripStore.STATUS_CONFIRMED, rec.status)
    assertFalse(rec.confirmSyncPending)
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_07_confirmOfflineMarksPendingNoPost() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    val sessionId = h.service.parkedCandidateSessionId!!
    assertTrue(h.receiver.dispatchEvent("NETWORK_FAIL"))
    val before = Bindings.apiTransport.postCount
    val outcome = h.service.todayTripSync!!.confirm(sessionId)
    assertTrue(outcome.ok)
    assertFalse(outcome.syncPosted)
    assertEquals(before, Bindings.apiTransport.postCount)
    val rec = h.service.todayTripStore!!.find(sessionId)!!
    assertEquals(TodayTripStore.STATUS_CONFIRMED, rec.status)
    assertTrue(rec.confirmSyncPending)
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_08_networkOkFlushesPending() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    val sessionId = h.service.parkedCandidateSessionId!!
    assertTrue(h.receiver.dispatchEvent("NETWORK_FAIL"))
    h.service.todayTripSync!!.confirm(sessionId)
    val before = Bindings.apiTransport.postCount
    assertTrue(h.receiver.dispatchEvent("NETWORK_OK"))
    assertEquals(before + 1, Bindings.apiTransport.postCount)
    assertFalse(h.service.todayTripStore!!.find(sessionId)!!.confirmSyncPending)
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_09_clockHktClearsStoreAndBlocksFlush() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    assertTrue(Bindings.pendingDriverConfirm)
    assertTrue(h.receiver.dispatchEvent("CLOCK_HKT_MIDNIGHT"))
    assertTrue(Bindings.todayTripsCleared)
    assertFalse(Bindings.pendingDriverConfirm)
    assertTrue(h.service.todayTripStore!!.list(System.currentTimeMillis()).isEmpty())
    val before = Bindings.apiTransport.postCount
    assertTrue(h.receiver.dispatchEvent("DRIVER_CONFIRM"))
    assertEquals(before, Bindings.apiTransport.postCount)
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_10_simDriverConfirmOnlineMatchesShell() {
    val h = openHarness()
    val before = Bindings.apiTransport.postCount
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    assertTrue(Bindings.pendingDriverConfirm)
    assertEquals(before + 1, Bindings.apiTransport.postCount)
    assertTrue(Bindings.apiTransport.lastPath.contains("release"))
    assertTrue(h.receiver.dispatchEvent("DRIVER_CONFIRM"))
    assertTrue(Bindings.driverConfirmSent)
    assertEquals(before + 2, Bindings.apiTransport.postCount)
    assertTrue(Bindings.apiTransport.lastPath.contains("sync"))
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_11_submitReportRequiresConfirmed() {
    val prefs = MemoryPrefsPort()
    val store = TodayTripStore(prefs, hktDateOf = { "2026-10-26" })
    store.upsertCandidate("s1", "cp1", "甲場", 1_000L)
    val api = RecordingSyncApi()
    val sync = TodayTripSync(store, api, onlineNetwork(), { 1_000L })
    val out = sync.submitReport(
      """{"session_id":"s1","carpark_id":"cp1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":"","trip_timestamp":1}""",
    )
    assertFalse(out.posted)
    assertTrue(api.tripPosts.isEmpty())
  }

  @Test
  fun step34_12_submitReportAfterConfirmPosts() {
    val prefs = MemoryPrefsPort()
    val store = TodayTripStore(prefs, hktDateOf = { "2026-10-26" })
    store.upsertCandidate("s1", "cp1", "甲場", 2_000L)
    val api = RecordingSyncApi()
    val sync = TodayTripSync(store, api, onlineNetwork(), { 2_000L })
    assertTrue(sync.confirm("s1").ok)
    val out = sync.submitReport(
      """{"session_id":"s1","carpark_id":"cp1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":"","trip_timestamp":2}""",
    )
    assertTrue(out.ok)
    assertTrue(out.posted)
    assertEquals(1, api.tripPosts.size)
    assertTrue(store.find("s1")!!.feedbackSubmitted)
  }

  @Test
  fun step34_13_submitFlushesPendingBeforeReport() {
    val prefs = MemoryPrefsPort()
    val store = TodayTripStore(prefs, hktDateOf = { "2026-10-26" })
    store.upsertCandidate("s1", "cp1", "甲場", 3_000L)
    val api = RecordingSyncApi()
    val net = FixtureNetworkStatus().also { it.online = false }
    val sync = TodayTripSync(store, api, net, { 3_000L })
    assertTrue(sync.confirm("s1").ok)
    assertTrue(store.find("s1")!!.confirmSyncPending)
    assertTrue(api.syncPosts.isEmpty())
    net.online = true
    val out = sync.submitReport(
      """{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}""",
    )
    assertTrue(out.posted)
    assertEquals(1, api.syncPosts.size)
    assertEquals(1, api.tripPosts.size)
    // sync before trip-report
    assertTrue(api.order.indexOf("sync") < api.order.indexOf("trip"))
  }

  @Test
  fun step34_14_bridgeGetTodayTripsReadsStore() {
    val prefs = MemoryPrefsPort()
    val store = TodayTripStore(prefs, hktDateOf = { "2026-10-26" })
    store.upsertCandidate("s1", "cp1", "甲場", 4_000L)
    val host = StoreHost(store)
    val bridge = OverlayBridge(host)
    val result = bridge.handle("getTodayTrips", emptyMap())
    assertTrue(result.ok)
    @Suppress("UNCHECKED_CAST")
    val list = result.value as List<Map<String, Any?>>
    assertEquals(1, list.size)
    assertEquals("s1", list[0]["sessionId"])
    assertEquals("甲場", list[0]["carparkName"])
    assertEquals("CANDIDATE", list[0]["status"])
    assertNotNull(list[0]["carparkId"])
    assertNotNull(list[0]["parkedAtEpochMs"])
    assertNotNull(list[0]["confirmSyncPending"])
    assertNotNull(list[0]["feedbackSubmitted"])
  }

  @Test
  fun step34_15_onTodayTripsChangedEmittedWhenMounted() {
    val emits = mutableListOf<String>()
    val prefs = MemoryPrefsPort()
    lateinit var store: TodayTripStore
    val host = object : StoreHost(TodayTripStore(MemoryPrefsPort())) {
      override var channelMounted: Boolean = true
      override fun emit(method: String, arguments: Map<String, Any?>) {
        emits += method
      }
    }
    store = TodayTripStore(
      prefs,
      hktDateOf = { "2026-10-26" },
      listener = TodayTripStore.Listener {
        if (host.channelMounted) host.emit("onTodayTripsChanged", emptyMap())
      },
    )
    // replace host store access via direct listener path
    store.upsertCandidate("s1", "cp1", "甲場", 5_000L)
    assertEquals(listOf("onTodayTripsChanged"), emits)
  }

  @Test
  fun step34_16_teardownDoesNotClearStore() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    val sessionId = h.service.parkedCandidateSessionId!!
    val store = h.service.todayTripStore!!
    assertEquals(1, store.list(System.currentTimeMillis()).size)
    h.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(h.service.isFgsRunning)
    // Store instance still holds the candidate (clearPorts must not clearAll).
    assertNotNull(store.find(sessionId))
    assertEquals(TodayTripStore.STATUS_CANDIDATE, store.find(sessionId)!!.status)
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_17_confirmIdempotentOnSecondTap() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    val sessionId = h.service.parkedCandidateSessionId!!
    val first = h.service.todayTripSync!!.confirm(sessionId)
    val second = h.service.todayTripSync!!.confirm(sessionId)
    assertTrue(first.ok)
    assertTrue(second.ok)
    assertEquals(TodayTripStore.STATUS_CONFIRMED, h.service.todayTripStore!!.find(sessionId)!!.status)
    Bindings.detachService(h.service)
  }

  @Test
  fun step34_18_committedCarparkIdUsedInCandidate() {
    val h = openHarness()
    assertTrue(h.receiver.dispatchEvent("FORCE_TWO_INDOOR"))
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "有冇第二個"))
    h.clock.advance(NativeConfig.CARD_PAGE_TRANSITION_MS.toLong())
    assertEquals(1, h.service.selectedCardIndex)
    h.engine.finish()
    assertTrue(h.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(TripState.NAV_TO_GATE, h.service.tripStateMachine.state)
    assertTrue(h.receiver.dispatchEvent("SET_DISTANCE_M", intValue = 100))
    assertTrue(h.receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    val row = h.service.todayTripStore!!.list(System.currentTimeMillis()).first()
    assertEquals("CP_INDOOR_B", row.carparkId)
    assertEquals("第二室內場", row.carparkName)
    val release = Bindings.apiTransport.postBodies.last { it.first.contains("release") }
    assertTrue(release.second.contains("CP_INDOOR_B"))
    Bindings.detachService(h.service)
  }

  // --- harness ---

  private fun openHarness(): Harness {
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
    return Harness(clock, service, receiver, engine)
  }

  private fun onlineNetwork(): FixtureNetworkStatus = FixtureNetworkStatus().also { it.online = true }

  private class Harness(
    val clock: FakeClock,
    val service: FloatingBubbleService,
    val receiver: com.hkparking.copilot.sim.SimEventReceiver,
    val engine: HoldEngine,
  )

  private class HoldEngine : com.hkparking.copilot.voice.TtsEngine {
    private var onDone: () -> Unit = {}
    override fun bindLocale(locale: String): Boolean = true
    override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
      this.onDone = onDone
    }
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

  private class RecordingSyncApi : SyncApi {
    val syncPosts = mutableListOf<Pair<String, Long>>()
    val tripPosts = mutableListOf<String>()
    val order = mutableListOf<String>()
    override fun syncDriverConfirmed(sessionId: String, timestampSec: Long): CallOutcome {
      syncPosts += sessionId to timestampSec
      order += "sync"
      return ok(200)
    }
    override fun submitTripReport(
      sessionId: String,
      carparkId: String,
      ratingTag: String,
      perkStatusTag: String,
      perkNotes: String,
      tripTimestampSec: Long,
    ): CallOutcome {
      tripPosts += sessionId
      order += "trip"
      return ok(200)
    }
    private fun ok(code: Int) = CallOutcome(
      succeeded = true,
      httpStatus = code,
      state = TripState.ENDED,
      capsuleLight = CapsuleLight.GREY_STEADY,
      stateTimeline = emptyList(),
      spokenCues = emptyList(),
      attempts = 1,
      waitIntervalsMs = emptyList(),
    )
  }

  private open class StoreHost(
    private val store: TodayTripStore,
  ) : OverlayHost {
    override fun permissionInput(): PermissionCheckInput = PermissionCheckInput(
      fineLocationGranted = true,
      coarseLocationOnly = false,
      postNotificationsGranted = true,
      systemAlertWindowGranted = true,
      notificationListenerGranted = false,
      recordAudioGranted = false,
    )
    override fun bluetoothConnectStatus(): String = OverlayBridge.DENIED
    override fun startForegroundService(entry: StartupEntry) {}
    override val foregroundStartCount: Int = 0
    override fun readString(key: String): String? = null
    override fun readBoolean(key: String): Boolean? = null
    override fun writeString(key: String, value: String) {}
    override fun writeBoolean(key: String, value: Boolean) {}
    override val capabilityChecker = com.hkparking.copilot.voice.VoiceCapabilityChecker(
      object : com.hkparking.copilot.voice.SpeechCapabilityProbe {
        override fun probeStt(locale: String) = com.hkparking.copilot.voice.LocaleProbe(supported = false)
        override fun probeTts(locale: String) = com.hkparking.copilot.voice.LocaleProbe(supported = false)
      },
    )
    override val tripStateMachine = TripStateMachine()
    override fun todayTrips(): List<Map<String, Any?>> = store.asBridgeMaps(System.currentTimeMillis())
    override fun confirmParkedCandidate(sessionId: String) {}
    override fun submitTripReport(payloadJson: String) {}
    override fun activeService(): FloatingBubbleService? = null
    override var channelMounted: Boolean = false
    override fun emit(method: String, arguments: Map<String, Any?>) {}
  }
}
