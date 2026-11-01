package com.hkparking.copilot

import com.hkparking.copilot.bridge.BridgeSink
import com.hkparking.copilot.bridge.TripPlatformBridge
import com.hkparking.copilot.bridge.TripPlatformEvents
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.LocFix
import com.hkparking.copilot.sim.SimLocationSource
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import io.flutter.plugin.common.MethodChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Step3bBridgeTest {
  @After
  fun tearDown() {
    TripPlatformEvents.sink = null
    TripPlatformBridge.stopOrphan = { com.hkparking.copilot.bridge.DartBridgeRuntime.active?.stopTripFgs("BOOTSTRAP_TIMEOUT") }
    TripPlatformBridge.resetForTest()
  }

  @Test
  fun b1_dartBridgeStartsForegroundWithoutLegacyTrip() {
    val sink = RecordingSink()
    TripPlatformEvents.sink = sink
    val service = service()
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    assertEquals(1, service.bridgeRuntime.handledStarts)
    assertEquals(TripState.PRE_FGS, service.tripStateMachine.state)
    assertFalse(service.isCapsuleVisible)
    assertFalse(service.isFgsRunning)
    service.bridgeRuntime.onDestroy()
    assertEquals(listOf("stopForeground"), service.bridgeRuntime.stopTrace)
  }

  @Test
  fun b2_missingModeStaysOnLegacyPath() {
    val service = service()
    service.enterForegroundGate = { true }
    service.onStartCommand(null, 0, 1)
    assertEquals(0, service.bridgeRuntime.handledStarts)
    assertTrue(service.isFgsRunning)
  }

  @Test
  fun b3_fixListenerEmitsOnFixAndNullsNanSpeed() {
    val sink = RecordingSink()
    TripPlatformEvents.sink = sink
    val service = service()
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    (Bindings.locationSource as SimLocationSource).injectFix(LocFix(22.3, 114.1, 8.0, Double.NaN, 10L))
    val fix = sink.events.last { it.first == "onFix" }.second
    assertEquals(22.3, fix["lat"])
    assertNull(fix["speedKmh"])
    service.bridgeRuntime.onDestroy()
  }

  @Test
  fun b4_engineSignalIsForwardedWithoutShutdown() {
    val sink = RecordingSink()
    TripPlatformEvents.sink = sink
    val service = service()
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    service.bridgeRuntime.engineReceiver!!.handleAction("android.intent.action.ACTION_POWER_DISCONNECTED", false)
    val event = sink.events.last { it.first == "onEngineSignal" }.second
    assertEquals("POWER", event["signal"])
    assertEquals(true, event["disconnected"])
    assertEquals(TripState.PRE_FGS, service.tripStateMachine.state)
    assertNull(service.tripEndedReason)
    service.bridgeRuntime.onDestroy()
  }

  @Test
  fun b5_stopTripFgsStopsForegroundThenSelf() {
    val service = service()
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    service.bridgeRuntime.stopTripFgs("TEST")
    assertEquals(listOf("stopForeground", "stopSelf"), service.bridgeRuntime.stopTrace)
  }

  @Test
  fun b6_bridgeModeDoesNotAttachTodayTripStore() {
    val before = Bindings.todayTripStore
    val service = service()
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    assertSame(before, Bindings.todayTripStore)
    service.bridgeRuntime.onDestroy()
  }

  @Test
  fun b7_prefsPutRejectsNonWhitelistKey() {
    val outcome = TripPlatformBridge.handleForTest("prefsPut", mapOf("key" to "other", "value" to "x"))
    assertFalse(outcome.ok)
    assertEquals("PREF_KEY", outcome.errorCode)
  }

  @Test
  fun b8_fixturePostUnsupportedWhenDisabled() {
    val previous = Bindings.allowFixturePost
    Bindings.allowFixturePost = false
    try {
      val outcome = TripPlatformBridge.handleForTest("fixturePost", mapOf("path" to "/v1/recommend", "body" to "{}"))
      assertFalse(outcome.ok)
      assertEquals("UNSUPPORTED", outcome.errorCode)
    } finally {
      Bindings.allowFixturePost = previous
    }
  }

  @Test
  fun b9_startHandshakeCompletesTrueOnce() {
    TripPlatformBridge.postDelayed = { _, _ -> { } }
    val result = RecResult()
    TripPlatformBridge.beginStartForTest("A_2", result)
    TripPlatformBridge.notifyForegroundReady(true)
    TripPlatformBridge.notifyForegroundReady(false)
    assertEquals(1, result.completed)
    assertEquals(true, result.value)
  }

  @Test
  fun b10_startHandshakeTimesOutFalse() {
    var fire: (() -> Unit)? = null
    TripPlatformBridge.postDelayed = { _, block ->
      fire = block
      { fire = null }
    }
    val result = RecResult()
    TripPlatformBridge.beginStartForTest("A_2", result)
    fire!!.invoke()
    fire?.invoke()
    assertEquals(1, result.completed)
    assertEquals(false, result.value)
  }

  @Test
  fun b11_bindOnceDoesNotRebind() {
    TripPlatformBridge.resetForTest()
    var calls = 0
    TripPlatformBridge.bindOnce { calls += 1 }
    TripPlatformBridge.bindOnce { calls += 1 }
    assertEquals(1, calls)
    assertTrue(TripPlatformBridge.isBound())
  }

  @Test
  fun b12_checkStartGateIgnoresOverlay() {
    TripPlatformBridge.startGateInput = {
      PermissionCheckInput(fineLocationGranted = true, postNotificationsGranted = true, systemAlertWindowGranted = false)
    }
    val allowed = RecResult()
    TripPlatformBridge.checkStartGateForTest(allowed)
    assertEquals(1, allowed.completed)
    assertNull(allowed.error)
    TripPlatformBridge.startGateInput = {
      PermissionCheckInput(fineLocationGranted = false, postNotificationsGranted = true, systemAlertWindowGranted = false)
    }
    val denied = RecResult()
    TripPlatformBridge.checkStartGateForTest(denied)
    assertEquals("MISSING_PERMISSION", denied.error)
    val missing = denied.details as Map<*, *>
    assertFalse((missing["missingItems"] as List<*>).contains(StartupPermissionChecker.OVERLAY))
  }

  @Test
  fun warmEngineEmitsImmediately() {
    val sink = RecordingSink()
    sink.channelMounted = true
    TripPlatformEvents.sink = sink
    TripPlatformEvents.emit("onHotwordStart", mapOf("entry" to "A_1"))
    assertEquals(listOf("onHotwordStart"), sink.events.map { it.first })
  }

  @Test
  fun coldEngineFlushesInOrderAfterReady() {
    val sink = RecordingSink()
    sink.channelMounted = false
    TripPlatformEvents.sink = sink
    TripPlatformEvents.emit("onHotwordStart", mapOf("entry" to "A_1"))
    TripPlatformEvents.emit("onShareIntake", mapOf("hasUrl" to true))
    assertTrue(sink.events.isEmpty())
    sink.channelMounted = true
    TripPlatformEvents.flush()
    assertEquals(listOf("onHotwordStart", "onShareIntake"), sink.events.map { it.first })
  }

  @Test
  fun lateReadyAfterTimeoutStopsOrphanFgs() {
    var stopped = 0
    TripPlatformBridge.stopOrphan = { stopped += 1 }
    TripPlatformBridge.postDelayed = { _, block ->
      block()
      ({ })
    }
    val result = RecResult()
    TripPlatformBridge.beginStartForTest("A_2", result)
    assertEquals(false, result.value)
    TripPlatformBridge.notifyForegroundReady(true)
    assertEquals(1, stopped)
  }

  @Test
  fun secondStartWhilePendingReturnsFgsPending() {
    TripPlatformBridge.postDelayed = { _, _ -> ({ }) }
    val first = RecResult()
    val second = RecResult()
    TripPlatformBridge.beginStartForTest("A_2", first)
    TripPlatformBridge.beginStartForTest("A_2", second)
    assertEquals(0, first.completed)
    assertEquals("FGS_PENDING", second.error)
  }

  private fun service(): FloatingBubbleService = FloatingBubbleService(VoiceCapabilityChecker(BridgeReadyProbe()))
}

private class RecordingSink : BridgeSink {
  override var channelMounted: Boolean = true
  val events = mutableListOf<Pair<String, Map<String, Any?>>>()
  override fun emit(method: String, args: Map<String, Any?>) {
    events += method to args
  }
}

private class RecResult : MethodChannel.Result {
  var value: Any? = null
  var error: String? = null
  var details: Any? = null
  var completed = 0
  override fun success(result: Any?) {
    completed += 1
    value = result
  }
  override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
    completed += 1
    error = errorCode
    details = errorDetails
  }
  override fun notImplemented() {}
}

private class BridgeReadyProbe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = ready()
  override fun probeTts(locale: String): LocaleProbe = ready()
  private fun ready() = LocaleProbe(setLanguageSucceeded = true, offlineCantoneseVoiceInstalled = true, canRecognizeOrSynthesize = true, supported = true)
}
