package com.hkparking.copilot

import com.hkparking.copilot.bridge.DartBridgeRuntime
import com.hkparking.copilot.bridge.TripPlatformEvents
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// r8：r7 log 證實 Dart bridge speak → TtsService「tts_unavailable」，因為 service 自己嘅 checker 冇人 check（UNKNOWN）。
class R8BridgeTtsTest {
  @Before
  fun visible() {
    AppVisibility.foreground = true
    TripPlatformEvents.sink = DropSink() // 唔留排隊事件畀其他測試
  }

  @After
  fun tearDown() {
    DartBridgeRuntime.active?.stopTripFgs("TEST_CLEANUP") // sink 仲喺度：唔留排隊事件
    DartBridgeRuntime.active = null
    Bindings.onBridgeStopped()
    TripPlatformEvents.sink = null
    AppVisibility.foreground = false
  }

  private fun bridgeService(engine: RecordingTtsEngine): FloatingBubbleService {
    val service = FloatingBubbleService(VoiceCapabilityChecker(NeverProbe()))
    service.ttsService.attachEngine(engine)
    service.enterForegroundGate = { true }
    service.bridgeRuntime.onStartCommand(null)
    return service
  }

  @Test
  fun r8_1_bridgeSpeakWorksWhenServiceCheckerNeverProbed() {
    val engine = RecordingTtsEngine()
    val service = bridgeService(engine)
    assertEquals(CapabilityStatus.UNKNOWN, service.capabilityChecker.ttsStatus)
    service.bridgeRuntime.speak("已確認A場，正在計算路線")
    assertEquals(listOf("已確認A場，正在計算路線"), engine.spoken)
    assertEquals(CapabilityStatus.AVAILABLE, service.capabilityChecker.ttsStatus)
  }

  @Test
  fun r8_2_nativeInitFailureIsNotOverridden() {
    val engine = RecordingTtsEngine()
    val service = bridgeService(engine)
    service.onTtsInitFailed("status=-1")
    service.bridgeRuntime.speak("講唔到")
    assertTrue(engine.spoken.isEmpty())
  }

  @Test
  fun r8_3_freshServiceAfterRestartAlsoSyncs() {
    val first = RecordingTtsEngine()
    bridgeService(first).bridgeRuntime.stopTripFgs("RESTART")
    DartBridgeRuntime.active = null
    val second = RecordingTtsEngine()
    bridgeService(second).bridgeRuntime.speak("第二程")
    assertEquals(listOf("第二程"), second.spoken)
  }
}

private class NeverProbe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = LocaleProbe(false, false, false, false)
  override fun probeTts(locale: String): LocaleProbe = LocaleProbe(false, false, false, false)
}

private class DropSink : com.hkparking.copilot.bridge.BridgeSink {
  override var channelMounted: Boolean = true
  override fun emit(method: String, args: Map<String, Any?>) {}
}
