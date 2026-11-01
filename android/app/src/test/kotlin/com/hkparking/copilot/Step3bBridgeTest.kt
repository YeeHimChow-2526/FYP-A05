package com.hkparking.copilot

import com.hkparking.copilot.bridge.BridgeSink
import com.hkparking.copilot.bridge.TripPlatformBridge
import com.hkparking.copilot.bridge.TripPlatformEvents
import com.hkparking.copilot.bridge.TripPlatformHost
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.LocFix
import com.hkparking.copilot.sim.SimLocationSource
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.VoiceCapabilityChecker
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
  }

  @Test
  fun b1_dartBridgeStartsForegroundWithoutLegacyTrip() {
    val sink = RecordingSink()
    TripPlatformEvents.sink = sink
    val service = service()
    service.enterForegroundGate = { true }
    service.dispatchStart(FloatingBubbleService.MODE_DART_BRIDGE, null)
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
    service.dispatchStart(null, null)
    assertFalse(service.bridgeMode)
    assertEquals(0, service.bridgeRuntime.handledStarts)
    assertTrue(service.isFgsRunning)
  }

  @Test
  fun b3_fixListenerEmitsOnFixAndNullsNanSpeed() {
    val sink = RecordingSink()
    TripPlatformEvents.sink = sink
    val service = service()
    service.enterForegroundGate = { true }
    service.dispatchStart(FloatingBubbleService.MODE_DART_BRIDGE, null)
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
    service.dispatchStart(FloatingBubbleService.MODE_DART_BRIDGE, null)
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
    service.dispatchStart(FloatingBubbleService.MODE_DART_BRIDGE, null)
    service.bridgeRuntime.stopTripFgs("TEST")
    assertEquals(listOf("stopForeground", "stopSelf"), service.bridgeRuntime.stopTrace)
  }

  @Test
  fun b6_bridgeModeDoesNotAttachTodayTripStore() {
    val before = Bindings.todayTripStore
    val service = service()
    service.enterForegroundGate = { true }
    service.dispatchStart(FloatingBubbleService.MODE_DART_BRIDGE, null)
    assertSame(before, Bindings.todayTripStore)
    service.bridgeRuntime.onDestroy()
  }

  @Test
  fun b7_prefsPutRejectsNonWhitelistKey() {
    val bridge = TripPlatformBridge(UnusedHost())
    val outcome = bridge.handle("prefsPut", mapOf("key" to "other", "value" to "x"))
    assertFalse(outcome.ok)
    assertEquals("PREF_KEY", outcome.errorCode)
  }

  @Test
  fun b8_fixturePostUnsupportedWhenDisabled() {
    val previous = Bindings.allowFixturePost
    Bindings.allowFixturePost = false
    try {
      val outcome = TripPlatformBridge(UnusedHost()).handle("fixturePost", mapOf("path" to "/v1/recommend", "body" to "{}"))
      assertFalse(outcome.ok)
      assertEquals("UNSUPPORTED", outcome.errorCode)
    } finally {
      Bindings.allowFixturePost = previous
    }
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

private class UnusedHost : TripPlatformHost {
  override fun startTripFgs(entry: String) = false
  override fun stopTripFgs(reason: String) {}
  override fun applySamplingTier(tier: String) {}
  override fun setNotificationText(text: String) {}
  override fun speak(text: String) {}
  override fun interruptSpeech() {}
  override fun launchGateNavigation(label: String) {}
  override fun voiceCapability(): Map<String, Any?> = emptyMap()
  override fun transportConfig(): Map<String, Any?> = emptyMap()
  override fun prefsLoad(): Map<String, String?> = emptyMap()
  override fun prefsPut(key: String, value: String) {}
  override fun prefsRemove(key: String) {}
  override fun fixturePost(path: String, body: String): Map<String, Any?> = emptyMap()
}

private class BridgeReadyProbe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = ready()
  override fun probeTts(locale: String): LocaleProbe = ready()
  private fun ready() = LocaleProbe(setLanguageSucceeded = true, offlineCantoneseVoiceInstalled = true, canRecognizeOrSynthesize = true, supported = true)
}
