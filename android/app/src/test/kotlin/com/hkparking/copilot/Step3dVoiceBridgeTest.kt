package com.hkparking.copilot

import com.hkparking.copilot.bridge.DartBridgeRuntime
import com.hkparking.copilot.bridge.TripPlatformEvents
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 3b：STT 文字路徑 VoiceInput → DartBridgeRuntime → onVoiceUtterance（JVM；唔係咪 E2E）。
class Step3dVoiceBridgeTest {
  @org.junit.Before
  fun visible() {
    AppVisibility.foreground = true
  }

  @After
  fun tearDown() {
    TripPlatformEvents.sink = null
    Bindings.onBridgeStopped()
    DartBridgeRuntime.active?.stopTripFgs("TEST_CLEANUP")
    DartBridgeRuntime.active = null
    AppVisibility.foreground = false
  }

  @Test
  fun v9_backgroundAppNeverOpensMicAndReportsNonPermanentError() {
    val sink = Sink()
    TripPlatformEvents.sink = sink
    val service = FloatingBubbleService(VoiceCapabilityChecker(Probe()))
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    AppVisibility.foreground = false
    assertFalse("App 唔見到：唔開咪", service.bridgeRuntime.startListening(hotword = false))
    val err = sink.events.last { it.first == "onVoiceError" }.second
    assertEquals(DartBridgeRuntime.APP_NOT_VISIBLE_ERROR, err["code"])
    SimEventReceiver().dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲")
    assertTrue("冇開聽：模擬句子都唔會入", sink.events.none { it.first == "onVoiceUtterance" })
    AppVisibility.foreground = true
    assertTrue(service.bridgeRuntime.startListening(hotword = false))
  }

  @Test
  fun v1_utteranceFromVoiceInputReachesDartAsParsedCommand() {
    val sink = Sink()
    TripPlatformEvents.sink = sink
    val service = FloatingBubbleService(VoiceCapabilityChecker(Probe()))
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    assertTrue(service.bridgeRuntime.startListening(hotword = false))
    val receiver = SimEventReceiver()
    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲"))
    val event = sink.events.last { it.first == "onVoiceUtterance" }.second
    assertEquals("詳細啲", event["text"])
    assertEquals("SHOW_DETAILS", event["code"])
    assertEquals(false, event["lowConfidence"])
  }

  @Test
  fun v2_notListeningOrStoppedTripEmitsNothing() {
    val sink = Sink()
    TripPlatformEvents.sink = sink
    val service = FloatingBubbleService(VoiceCapabilityChecker(Probe()))
    assertFalse("未開程唔准開咪", service.bridgeRuntime.startListening(hotword = false))
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    val receiver = SimEventReceiver()
    receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "詳細啲")
    assertTrue("未 startListening：Sim 丟棄", sink.events.none { it.first == "onVoiceUtterance" })
    service.bridgeRuntime.startListening(hotword = false)
    service.bridgeRuntime.stopTripFgs("TEST")
    Bindings.voiceInput.injectUtterance("詳細啲")
    assertTrue("停程後 listener 已拆", sink.events.none { it.first == "onVoiceUtterance" })
  }

  @Test
  fun v3_nativeVoiceErrorReachesDartWithPermanenceFlag() {
    val sink = Sink()
    TripPlatformEvents.sink = sink
    val service = FloatingBubbleService(VoiceCapabilityChecker(Probe()))
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    Bindings.voiceInput.injectError("NO_PERMISSION")
    val event = sink.events.last { it.first == "onVoiceError" }.second
    assertEquals("NO_PERMISSION", event["code"])
    assertEquals(true, event["permanent"])
    service.bridgeRuntime.stopTripFgs("TEST")
    val before = sink.events.count { it.first == "onVoiceError" }
    Bindings.voiceInput.injectError("NO_MATCH")
    assertEquals("停程後 error listener 已拆", before, sink.events.count { it.first == "onVoiceError" })
  }

  @Test
  fun v4_speechDoneEmittedOnlyOnEngineCompletionNotOnSpeakReturn() {
    val sink = Sink()
    TripPlatformEvents.sink = sink
    val checker = VoiceCapabilityChecker(Probe()).also { it.check(recordAudioGranted = true) }
    val service = FloatingBubbleService(checker)
    val engine = HoldTts()
    assertTrue(service.ttsService.attachEngine(engine))
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    service.bridgeRuntime.speak("去CP", "u1")
    assertTrue("speak() return 唔等於講完", sink.events.none { it.first == "onSpeechDone" })
    engine.done()
    val done = sink.events.single { it.first == "onSpeechDone" }.second
    assertEquals("u1", done["utteranceId"])
    assertEquals(true, done["played"])
    service.bridgeRuntime.speak("第二句", "u2")
    service.ttsService.interrupt()
    val stopped = sink.events.last { it.first == "onSpeechDone" }.second
    assertEquals("u2", stopped["utteranceId"])
    assertEquals("interrupt／stop → played=false", false, stopped["played"])
    engine.done() // 舊回呼遲到：已 settle，唔會再 emit
    assertEquals(2, sink.events.count { it.first == "onSpeechDone" })
    service.bridgeRuntime.speak("", "u3")
    assertEquals("排唔到隊即時 played=false（Dart 唔會等死）", false, sink.events.last { it.first == "onSpeechDone" }.second["played"])
  }
}

private class HoldTts : com.hkparking.copilot.voice.TtsEngine {
  private var onDone: () -> Unit = {}
  override fun bindLocale(locale: String): Boolean = true
  override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
    this.onDone = onDone
  }
  override fun stop() {}
  fun done() = onDone()
}

private class Sink : com.hkparking.copilot.bridge.BridgeSink {
  override var channelMounted: Boolean = true
  val events = mutableListOf<Pair<String, Map<String, Any?>>>()
  override fun emit(method: String, args: Map<String, Any?>) {
    events += method to args
  }
}

private class Probe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = ready()
  override fun probeTts(locale: String): LocaleProbe = ready()
  private fun ready() = LocaleProbe(
    setLanguageSucceeded = true,
    offlineCantoneseVoiceInstalled = true,
    canRecognizeOrSynthesize = true,
    supported = true,
  )
}
