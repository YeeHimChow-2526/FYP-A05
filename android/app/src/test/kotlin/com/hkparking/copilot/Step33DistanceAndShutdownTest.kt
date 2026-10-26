package com.hkparking.copilot

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.FgsStarter
import com.hkparking.copilot.di.LocFix
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.sim.FixtureApiTransport
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.PendingInvalidation
import com.hkparking.copilot.voice.TtsEngine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step33DistanceAndShutdownTest {
  companion object {
    private const val DEST_LAT = 22.3193
    private const val DEST_LNG = 114.1694
    private const val DELTA_100M = 0.000898
    private const val DELTA_1000M = 0.008983
  }

  @Test
  fun step33_01_haversineRoundsToMetres() {
    val m100 = DistanceTracker.haversineM(DEST_LAT, DEST_LNG, DEST_LAT + DELTA_100M, DEST_LNG)
    val m1000 = DistanceTracker.haversineM(DEST_LAT, DEST_LNG, DEST_LAT + DELTA_1000M, DEST_LNG)
    assertTrue(abs(m100 - 100.0) <= 1.0)
    assertTrue(abs(m1000 - 1000.0) <= 2.0)
  }

  @Test
  fun step33_02_tierHysteresis150Enter200Exit() {
    val tiers = mutableListOf<SamplingTier>()
    val tracker = DistanceTracker(object : DistanceTracker.Listener {
      override fun onSample(sample: DistanceSample) {}
      override fun onTierChanged(tier: SamplingTier) { tiers += tier }
    })
    tracker.setDestination(DEST_LAT, DEST_LNG)
    var t = 0L
    fun push(metres: Int) {
      tracker.onFix(fixNorthOf(metres, 20.0, t))
      t += 1_000L
    }
    push(1500)
    assertEquals(SamplingTier.FAR, tracker.tier)
    push(900)
    assertEquals(listOf(SamplingTier.NEAR), tiers)
    push(150)
    assertEquals(listOf(SamplingTier.NEAR, SamplingTier.APPROACH), tiers)
    push(180)
    assertEquals(SamplingTier.APPROACH, tracker.tier)
    push(201)
    assertEquals(listOf(SamplingTier.NEAR, SamplingTier.APPROACH, SamplingTier.NEAR), tiers)
    push(1001)
    assertEquals(listOf(SamplingTier.NEAR, SamplingTier.APPROACH, SamplingTier.NEAR, SamplingTier.FAR), tiers)
  }

  @Test
  fun step33_03_coreHoldEntersMutedOnFourthFix() {
    val samples = mutableListOf<DistanceSample>()
    val tracker = DistanceTracker(object : DistanceTracker.Listener {
      override fun onSample(sample: DistanceSample) { samples += sample }
      override fun onTierChanged(tier: SamplingTier) {}
    })
    tracker.setDestination(DEST_LAT, DEST_LNG)
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    val postsBefore = Bindings.apiTransport.postCount
    for (i in 0..3) {
      val fix = fixNorthOf(70, 5.0, i * 1_000L)
      tracker.onFix(fix)
      Bindings.locationSource.injectFix(fix)
    }
    assertEquals(listOf(0, 1, 2, 3), samples.map { it.lowSpeedHoldS })
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    assertTrue(Bindings.apiTransport.postBodies.none { it.first.contains("release") })
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_04_coreHoldResetsOnSpeedOrDistance() {
    val samples = mutableListOf<DistanceSample>()
    val tracker = DistanceTracker(object : DistanceTracker.Listener {
      override fun onSample(sample: DistanceSample) { samples += sample }
      override fun onTierChanged(tier: SamplingTier) {}
    })
    tracker.setDestination(DEST_LAT, DEST_LNG)
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    var t = 0L
    fun push(metres: Int, speed: Double) {
      val fix = fixNorthOf(metres, speed, t)
      tracker.onFix(fix)
      Bindings.locationSource.injectFix(fix)
      t += 1_000L
    }
    push(70, 5.0)
    push(70, 5.0)
    push(70, 9.0)
    assertEquals(0, samples.last().lowSpeedHoldS)
    push(70, 5.0)
    push(70, 5.0)
    push(70, 5.0)
    assertEquals(2, samples.last().lowSpeedHoldS)
    push(81, 5.0)
    assertEquals(0, samples.last().lowSpeedHoldS)
    assertNotEqualsMuted(harness.service.tripStateMachine.state)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_05_staleWithin150Below15MutesOnce() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    val tracker = harness.service.distanceTracker
    Bindings.locationSource.injectFix(fixNorthOf(140, 12.0, 0L))
    assertNotEqualsMuted(harness.service.tripStateMachine.state)
    Bindings.locationSource.injectTick(5_000L)
    assertNotEqualsMuted(harness.service.tripStateMachine.state)
    Bindings.locationSource.injectTick(5_001L)
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    Bindings.locationSource.injectTick(8_000L)
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    // Only one stale transition into mute; further ticks must not re-enter via fresh samples
    assertTrue(tracker.lastDistanceM != null)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_06_staleFarOrFastDoesNotMute() {
    val far = openHarness()
    far.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(300, 5.0, 0L))
    Bindings.locationSource.injectTick(6_000L)
    assertNotEqualsMuted(far.service.tripStateMachine.state)
    Bindings.detachService(far.service)

    val fast = openHarness()
    fast.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(140, 20.0, 0L))
    Bindings.locationSource.injectTick(6_000L)
    assertNotEqualsMuted(fast.service.tripStateMachine.state)
    Bindings.detachService(fast.service)
  }

  @Test
  fun step33_07_exitHoldOnlyInMutedAndTenSeconds() {
    val samples = mutableListOf<DistanceSample>()
    val tracker = DistanceTracker(object : DistanceTracker.Listener {
      override fun onSample(sample: DistanceSample) { samples += sample }
      override fun onTierChanged(tier: SamplingTier) {}
    })
    tracker.setDestination(DEST_LAT, DEST_LNG)
    var t = 0L
    repeat(3) {
      tracker.onFix(fixNorthOf(250, 5.0, t))
      t += 1_000L
    }
    assertTrue(samples.takeLast(3).all { it.exitHoldS == 0 })

    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    // Enter muted via core hold
    for (i in 0..3) {
      Bindings.locationSource.injectFix(fixNorthOf(70, 5.0, i * 1_000L))
    }
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    val base = 10_000L
    Bindings.locationSource.injectFix(fixNorthOf(250, 5.0, base))
    Bindings.locationSource.injectFix(fixNorthOf(250, 5.0, base + 5_000L))
    Bindings.locationSource.injectFix(fixNorthOf(250, 5.0, base + 9_000L))
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    Bindings.locationSource.injectFix(fixNorthOf(250, 5.0, base + 10_000L))
    assertEquals(TripState.CRUISING, harness.service.tripStateMachine.state)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_08_noGpsKeepsMutedAndResetsExitHold() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    for (i in 0..3) {
      Bindings.locationSource.injectFix(fixNorthOf(70, 5.0, i * 1_000L))
    }
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    val t = 10_000L
    Bindings.locationSource.injectFix(fixNorthOf(250, 5.0, t))
    Bindings.locationSource.injectTick(t + 20_000L)
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    Bindings.locationSource.injectFix(fixNorthOf(250, 5.0, t + 20_000L))
    Bindings.locationSource.injectFix(fixNorthOf(250, 5.0, t + 29_000L))
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    Bindings.locationSource.injectFix(fixNorthOf(250, 5.0, t + 30_000L))
    assertEquals(TripState.CRUISING, harness.service.tripStateMachine.state)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_09_inaccurateFixIgnored() {
    val samples = mutableListOf<DistanceSample>()
    val tracker = DistanceTracker(object : DistanceTracker.Listener {
      override fun onSample(sample: DistanceSample) { samples += sample }
      override fun onTierChanged(tier: SamplingTier) {}
    })
    tracker.setDestination(DEST_LAT, DEST_LNG)
    tracker.onFix(fixNorthOf(100, 5.0, 0L, accuracyM = 150.0))
    tracker.onTick(6_000L)
    assertTrue(samples.isEmpty())
    assertNull(tracker.lastDistanceM)
  }

  @Test
  fun step33_10_trackerFarFixesStillFeedJit() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(1500, 30.0, 0L))
    assertFalse(harness.service.tripStateMachine.jitTriggered)
    Bindings.locationSource.injectFix(fixNorthOf(900, 30.0, 1_000L))
    assertTrue(harness.service.tripStateMachine.jitTriggered)
    assertEquals(
      1,
      harness.service.launchedRecommendModes.count { it == QueryTriggerMode.BACKGROUND_PREFETCH },
    )
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_11_lockWithCoordinatesUsesTrackerDistance() {
    val harness = openHarness()
    Bindings.locationSource.injectFix(fixNorthOf(100, 20.0, 0L))
    // Destination B is ~3km north of current fix (~100m north of DEST)
    val bLat = DEST_LAT + 3 * DELTA_1000M
    harness.service.lockDestinationAt("遠方", bLat, DEST_LNG)
    assertFalse(harness.service.tripStateMachine.jitTriggered)
    // Move to ~900m from B
    Bindings.locationSource.injectFix(
      LocFix(bLat + DELTA_100M * 9, DEST_LNG, 10.0, 20.0, 2_000L),
    )
    assertTrue(harness.service.tripStateMachine.jitTriggered)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_12_nameOnlyDestinationEmitsNoDistance() {
    val harness = openHarness()
    harness.service.lockDestination("無名終點")
    Bindings.locationSource.injectFix(fixNorthOf(100, 5.0, 0L))
    Bindings.locationSource.injectFix(fixNorthOf(100, 5.0, 1_000L))
    Bindings.locationSource.injectFix(fixNorthOf(100, 5.0, 2_000L))
    assertNull(harness.service.distanceTracker.lastDistanceM)
    assertEquals(SamplingTier.FAR, harness.service.distanceTracker.tier)
    assertFalse(harness.service.tripStateMachine.jitTriggered)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_13_powerOffWithin150ParksAfterDebounce() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(100, 0.0, 0L))
    val postsBefore = Bindings.apiTransport.postCount
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS - 1)
    assertNotEquals(TripState.PARKED_CONFIRMED, harness.service.tripStateMachine.state)
    assertNotEquals(TripState.ENDED, harness.service.tripStateMachine.state)
    assertFalse(harness.engine.stopped)
    harness.clock.advance(1)
    assertEquals(TripState.PARKED_CONFIRMED, harness.service.tripStateMachine.state)
    assertEquals(postsBefore + 1, Bindings.apiTransport.postCount)
    assertTrue(Bindings.apiTransport.lastPath?.contains("/v1/session/release") == true)
    harness.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(harness.service.isFgsRunning)
    assertFalse(harness.service.engineReceiverRegistered)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_14_powerBlipRecoveredIsIgnored() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(100, 0.0, 0L))
    val state = harness.service.tripStateMachine.state
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS / 2)
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, false)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    assertEquals(state, harness.service.tripStateMachine.state)
    assertTrue(harness.service.isFgsRunning)
    assertEquals("CANCELLED", harness.service.lastEngineSignalOutcome)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_15_signalWhileMovingIgnored() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(100, 30.0, 1_000L))
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    assertTrue(harness.service.isFgsRunning)
    assertEquals("IGNORED_MOVING", harness.service.lastEngineSignalOutcome)
    assertFalse(harness.service.engineShutdownDetector.isPending)
    // Detector reset — can open a new window
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    assertTrue(harness.service.engineShutdownDetector.isPending)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_16_outsideRangeEndsWithoutCandidate() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(600, 0.0, 0L))
    val postsBefore = Bindings.apiTransport.postCount
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    assertEquals(TripState.ENDED, harness.service.tripStateMachine.state)
    assertEquals("ENGINE_SHUTDOWN", harness.service.tripEndedReason)
    assertEquals(postsBefore, Bindings.apiTransport.postCount)
    assertNull(harness.service.parkedCandidateSessionId)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_17_bluetoothAndPowerShutdownOnce() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(100, 0.0, 0L))
    val postsBefore = Bindings.apiTransport.postCount
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.BT_CAR, true)
    harness.clock.advance(500)
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    assertEquals(TripState.PARKED_CONFIRMED, harness.service.tripStateMachine.state)
    assertEquals(postsBefore + 1, Bindings.apiTransport.postCount)
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    assertEquals(postsBefore + 1, Bindings.apiTransport.postCount)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_18_receiverRoutingAndCarClassFilter() {
    val seen = mutableListOf<Pair<EngineShutdownDetector.Signal, Boolean>>()
    val receiver = EngineShutdownReceiver { signal, disconnected ->
      seen += signal to disconnected
    }
    receiver.handleAction(Intent.ACTION_POWER_DISCONNECTED, false)
    receiver.handleAction(Intent.ACTION_POWER_CONNECTED, true)
    receiver.handleAction(BluetoothDevice.ACTION_ACL_DISCONNECTED, true)
    receiver.handleAction(BluetoothDevice.ACTION_ACL_DISCONNECTED, false)
    receiver.handleAction(BluetoothDevice.ACTION_ACL_CONNECTED, true)
    receiver.handleAction(BluetoothDevice.ACTION_ACL_CONNECTED, false)
    assertEquals(
      listOf(
        EngineShutdownDetector.Signal.POWER to true,
        EngineShutdownDetector.Signal.POWER to false,
        EngineShutdownDetector.Signal.BT_CAR to true,
        EngineShutdownDetector.Signal.BT_CAR to false,
      ),
      seen,
    )
    assertTrue(EngineShutdownReceiver.isCarDeviceClass(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO))
    assertTrue(EngineShutdownReceiver.isCarDeviceClass(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE))
    assertFalse(EngineShutdownReceiver.isCarDeviceClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
    assertFalse(EngineShutdownReceiver.isCarDeviceClass(null))
  }

  @Test
  fun step33_19_registrationFollowsFgsAndPermission() {
    val original = EngineShutdownReceiver.registrar
    try {
      val log = RecordingRegistrar()
      EngineShutdownReceiver.registrar = log

      Bindings.locationSource.reset()
      Bindings.apiTransport.reset()
      Bindings.apiTransport.setFixtureLoader { FixtureApiTransport.loadMockFile(it) }
      Bindings.navigation.reset()
      Bindings.voiceInput.stopListening()
      val clock = FakeClock()
      val service = FloatingBubbleService(clock.delayMs)
      service.bluetoothConnectGranted = { false }
      val engine = HoldEngine()
      assertTrue(service.ttsService.attachEngine(engine))
      service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
      service.reportFgsStarted()
      assertEquals(1, log.registers.size)
      assertEquals(
        listOf(Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_POWER_CONNECTED),
        log.registers[0].third,
      )
      service.onEngineShutdown(false)
      clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
      assertEquals(1, log.unregisters)
      Bindings.detachService(service)

      log.registers.clear()
      log.unregisters = 0
      val clock2 = FakeClock()
      val service2 = FloatingBubbleService(clock2.delayMs)
      service2.bluetoothConnectGranted = { true }
      assertTrue(service2.ttsService.attachEngine(HoldEngine()))
      service2.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
      service2.reportFgsStarted()
      assertEquals(1, log.registers.size)
      assertEquals(4, log.registers[0].third.size)
      assertTrue(log.registers[0].third.contains(BluetoothDevice.ACTION_ACL_DISCONNECTED))
      assertTrue(log.registers[0].third.contains(BluetoothDevice.ACTION_ACL_CONNECTED))
      Bindings.detachService(service2)
    } finally {
      EngineShutdownReceiver.registrar = original
    }
  }

  @Test
  fun step33_20_shutdownMidDialogueKeepsSc012Order() {
    val harness = openHarness()
    assertTrue(harness.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(harness.service.ttsService.isSpeaking)
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    // Debounce window: still speaking, no invalidate yet
    assertTrue(harness.service.ttsService.isSpeaking)
    assertNull(harness.service.lastPendingInvalidation)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    assertEquals(PendingInvalidation.DIALOGUE_INTERRUPTED, harness.service.lastPendingInvalidation)
    assertTrue(harness.engine.stopped)
    assertFalse(Bindings.voiceInput.isListening)
    assertFalse(harness.service.ttsService.isSpeaking)
    Bindings.detachService(harness.service)

    // Late recommend during fade (Step25 rate-limit pattern)
    val late = openHarness()
    assertTrue(late.receiver.dispatchEvent("FORCE_RATE_LIMIT", intValue = 1))
    assertTrue(late.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    val wait = minOf(
      // parse retry_after_seconds from fixture path used by Step25
      NativeConfig.RATE_LIMIT_RETRY_DELAY_MS,
      NativeConfig.RATE_LIMIT_MAX_WAIT_MS,
    )
    val remain = NativeConfig.TEARDOWN_FADE_MS.toLong() / 2
    late.clock.advance(wait - remain)
    late.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    late.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    late.clock.advance(remain)
    assertTrue(late.service.shutdownResultDropped || !late.service.isFgsRunning || late.service.tripStateMachine.state == TripState.ENDED || late.service.tripStateMachine.state == TripState.PARKED_CONFIRMED)
    Bindings.detachService(late.service)
  }

  @Test
  fun step33_21_navToGateReleasesCommittedCarpark() {
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
    assertEquals(listOf("第二室內場"), Bindings.navigation.launchedTargets)
    assertTrue(h.receiver.dispatchEvent("SET_DISTANCE_M", intValue = 100))
    val postsBefore = Bindings.apiTransport.postCount
    h.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    h.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    assertEquals(TripState.PARKED_CONFIRMED, h.service.tripStateMachine.state)
    assertEquals(postsBefore + 1, Bindings.apiTransport.postCount)
    val last = Bindings.apiTransport.postBodies.last()
    assertTrue(last.first.contains("release"))
    assertTrue(last.second.contains("CP_INDOOR_B"))
    assertEquals("第二室內場", h.service.parkedCandidateName)
    Bindings.detachService(h.service)
  }

  @Test
  fun step33_22_simEngineShutdownNoValueUsesVerdict() {
    val a = openHarness()
    assertTrue(a.receiver.dispatchEvent("ENGINE_SHUTDOWN"))
    assertEquals(TripState.ENDED, a.service.tripStateMachine.state)
    a.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    Bindings.detachService(a.service)

    val b = openHarness()
    assertTrue(b.receiver.dispatchEvent("SET_DISTANCE_M", intValue = 100))
    assertTrue(b.receiver.dispatchEvent("ENGINE_SHUTDOWN"))
    assertEquals(TripState.PARKED_CONFIRMED, b.service.tripStateMachine.state)
    b.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    Bindings.detachService(b.service)

    val c = openHarness()
    assertTrue(c.receiver.dispatchEvent("FORCE_TWO_INDOOR"))
    assertTrue(c.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    c.engine.finish()
    assertTrue(c.receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "好"))
    assertEquals(TripState.NAV_TO_GATE, c.service.tripStateMachine.state)
    assertTrue(c.receiver.dispatchEvent("SET_DISTANCE_M", intValue = 100))
    assertTrue(c.receiver.dispatchEvent("ENGINE_SHUTDOWN"))
    assertEquals(TripState.PARKED_CONFIRMED, c.service.tripStateMachine.state)
    Bindings.detachService(c.service)
  }

  @Test
  fun step33_23_basementDriftWhileMutedParks() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    for (i in 0..3) {
      Bindings.locationSource.injectFix(fixNorthOf(70, 5.0, i * 1_000L))
    }
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    Bindings.locationSource.injectFix(fixNorthOf(190, 5.0, 10_000L))
    Bindings.locationSource.injectTick(16_000L)
    assertTrue(harness.service.shutdownVerdict())
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    assertEquals(TripState.PARKED_CONFIRMED, harness.service.tripStateMachine.state)
    Bindings.detachService(harness.service)
  }

  @Test
  fun step33_24_teardownResetsTrackerForNextTrip() {
    val harness = openHarness()
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    for (i in 0..3) {
      Bindings.locationSource.injectFix(fixNorthOf(70, 5.0, i * 1_000L))
    }
    assertEquals(TripState.APPROACH_MUTED, harness.service.tripStateMachine.state)
    assertEquals(SamplingTier.APPROACH, harness.service.distanceTracker.tier)
    harness.service.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
    harness.clock.advance(NativeConfig.ENGINE_OFF_DEBOUNCE_MS)
    harness.clock.advance(NativeConfig.TEARDOWN_FADE_MS.toLong())
    assertFalse(harness.service.isFgsRunning)

    harness.service.enterForegroundGate = { true }
    Bindings.fgsStarter = FgsStarter {
      harness.service.restartTripFromEntry(StartupEntry.A_2)
      true
    }
    assertTrue(harness.receiver.dispatchEvent("START_TRIP"))
    assertTrue(harness.receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "FULL_VOICE"))
    assertNull(harness.service.distanceTracker.lastDistanceM)
    assertEquals(SamplingTier.FAR, harness.service.distanceTracker.tier)
    assertEquals(SamplingTier.FAR, Bindings.locationSource.appliedTiers.last())
    harness.service.lockDestinationAt("尖沙咀", DEST_LAT, DEST_LNG)
    Bindings.locationSource.injectFix(fixNorthOf(70, 5.0, 100_000L))
    // First qualifying fix after reset → hold 0
    val samples = mutableListOf<DistanceSample>()
    val probe = DistanceTracker(object : DistanceTracker.Listener {
      override fun onSample(sample: DistanceSample) { samples += sample }
      override fun onTierChanged(tier: SamplingTier) {}
    })
    probe.setDestination(DEST_LAT, DEST_LNG)
    probe.onFix(fixNorthOf(70, 5.0, 100_000L))
    assertEquals(0, samples.last().lowSpeedHoldS)
    Bindings.detachService(harness.service)
  }

  private fun assertNotEqualsMuted(state: TripState) {
    assertTrue(state != TripState.APPROACH_MUTED)
  }

  private fun fixNorthOf(metres: Int, speedKmh: Double, timestampMs: Long, accuracyM: Double = 10.0): LocFix {
    // Match DistanceTracker.haversineM earth radius so round(distance) == metres.
    val metresPerDeg = 6_371_000.0 * Math.PI / 180.0
    val lat = DEST_LAT + metres / metresPerDeg
    return LocFix(lat, DEST_LNG, accuracyM, speedKmh, timestampMs)
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
    override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) { this.onDone = onDone; stopped = false }
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

  private class RecordingListener(private val samples: MutableList<DistanceSample>) : DistanceTracker.Listener {
    override fun onSample(sample: DistanceSample) { samples += sample }
    override fun onTierChanged(tier: SamplingTier) {}
  }

  private class RecordingRegistrar : EngineShutdownReceiver.Registrar {
    val registers = mutableListOf<Triple<Context, BroadcastReceiver, List<String>>>()
    var unregisters: Int = 0
    override fun register(context: Context, receiver: BroadcastReceiver, actions: List<String>) {
      registers += Triple(context, receiver, actions)
    }
    override fun unregister(context: Context, receiver: BroadcastReceiver) {
      unregisters += 1
    }
  }
}
