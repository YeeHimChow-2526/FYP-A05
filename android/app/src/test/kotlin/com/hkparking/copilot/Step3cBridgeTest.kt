package com.hkparking.copilot

import com.hkparking.copilot.bridge.TripPlatformEvents
import com.hkparking.copilot.bridge.DartBridgeRuntime
import com.hkparking.copilot.di.AttachableLocationSource
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.share.ParsedShare
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Step3cBridgeTest {
  @After
  fun tearDown() {
    TripPlatformEvents.sink = null
    Bindings.bridgeLocationOverride = null
    Bindings.onBridgeStopped()
    DartBridgeRuntime.active?.stopTripFgs("TEST_CLEANUP")
    DartBridgeRuntime.active = null
  }

  @Test
  fun b13_releaseStyleFakeAttachesAndStartsOnce() {
    val fake = ReleaseStyleLocation()
    Bindings.bridgeLocationOverride = fake
    val service = service()
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    service.bridgeRuntime.onStartCommand(null)
    assertEquals(1, fake.attaches)
    assertEquals(1, fake.starts)
    service.bridgeRuntime.stopTripFgs("TEST")
    assertEquals(1, fake.stops)
  }

  @Test
  fun b14_debugBridgeRegistersSimReceiverOnceAndSetsFixtureLoader() {
    Bindings.receiverRegistered = false
    Bindings.apiTransport.customFixtureLoader = false
    var registrations = 0
    val restore = SimEventReceiver.swapRegistrarForTest { registrations += 1 }
    try {
      val service = service()
      service.enterForegroundGate = { true }
      service.bridgeRuntime.onStartCommand(null)
      service.bridgeRuntime.onStartCommand(null)
      assertEquals(1, registrations)
      assertTrue(Bindings.receiverRegistered)
      assertTrue(Bindings.apiTransport.customFixtureLoader)
    } finally {
      restore()
    }
  }

  @Test
  fun b15_simFixEmitsOnFixOnlyWhenBridgeActiveAndValueParses() {
    val sink = Step3cSink()
    sink.channelMounted = true
    TripPlatformEvents.sink = sink
    val receiver = SimEventReceiver()
    assertFalse(receiver.dispatchEvent("FIX", stringValue = "22.3,114.17,30"))
    assertTrue(sink.events.none { it.first == "onFix" })

    val service = service()
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    assertTrue(receiver.dispatchEvent("FIX", stringValue = "22.3,114.17,30"))
    val fix = sink.events.last { it.first == "onFix" }.second
    assertEquals(22.3, fix["lat"])
    assertEquals(114.17, fix["lng"])
    assertEquals(30.0, fix["speedKmh"])
    assertEquals(10.0, fix["accuracyM"])
    val before = sink.events.count { it.first == "onFix" }
    assertFalse(receiver.dispatchEvent("FIX", stringValue = "abc"))
    assertFalse(receiver.dispatchEvent("FIX", stringValue = "22.3"))
    assertEquals(before, sink.events.count { it.first == "onFix" })

    service.bridgeRuntime.stopTripFgs("TEST")
    DartBridgeRuntime.active = null
    assertFalse(receiver.dispatchEvent("FIX", stringValue = "22.3,114.17,30"))
    assertEquals(before, sink.events.count { it.first == "onFix" })
  }

  @Test
  fun b16_bridgeShareIgnoresOverlayAndEmitsMissingPermission() {
    val sink = Step3cSink()
    sink.channelMounted = true
    TripPlatformEvents.sink = sink
    val parsed = ParsedShare(title = "朗豪坊", lat = 22.3, lng = 114.17, hasUrl = false)
    val allowed = ShareIntentReceiverActivity()
    assertTrue(
      allowed.acceptBridgeShare(
        parsed,
        PermissionCheckInput(
          fineLocationGranted = true,
          postNotificationsGranted = true,
          systemAlertWindowGranted = false,
        ),
      ),
    )
    assertEquals("onShareIntake", sink.events.last().first)
    assertEquals("朗豪坊", sink.events.last().second["title"])

    sink.events.clear()
    val denied = ShareIntentReceiverActivity()
    assertFalse(
      denied.acceptBridgeShare(
        parsed,
        PermissionCheckInput(
          fineLocationGranted = true,
          postNotificationsGranted = false,
          systemAlertWindowGranted = false,
        ),
      ),
    )
    assertEquals(ShareIntentReceiverActivity.MISSING_PERMISSION, denied.failureReason)
    assertTrue(denied.isFinished)
    assertEquals("MISSING_PERMISSION", sink.events.single().first)
    assertTrue(sink.events.none { it.first == "onShareIntake" })
  }

  private fun service(): FloatingBubbleService = FloatingBubbleService(VoiceCapabilityChecker(Step3cProbe()))
}

private class ReleaseStyleLocation : AttachableLocationSource {
  var attaches = 0
  var starts = 0
  var stops = 0
  override fun attach(context: android.content.Context) {
    attaches += 1
  }
  override fun setLocationListener(listener: ((Int, Double, Int) -> Unit)?) {}
  override fun start() {
    starts += 1
  }
  override fun stop() {
    stops += 1
  }
}

private class Step3cSink : com.hkparking.copilot.bridge.BridgeSink {
  override var channelMounted: Boolean = true
  val events = mutableListOf<Pair<String, Map<String, Any?>>>()
  override fun emit(method: String, args: Map<String, Any?>) {
    events += method to args
  }
}

private class Step3cProbe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = ready()
  override fun probeTts(locale: String): LocaleProbe = ready()
  private fun ready() = LocaleProbe(
    setLanguageSucceeded = true,
    offlineCantoneseVoiceInstalled = true,
    canRecognizeOrSynthesize = true,
    supported = true,
  )
}
