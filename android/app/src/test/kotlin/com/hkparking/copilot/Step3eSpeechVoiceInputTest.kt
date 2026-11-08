package com.hkparking.copilot

import com.hkparking.copilot.voice.SpeechEngine
import com.hkparking.copilot.voice.SpeechVoiceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 3c：SpeechVoiceInput 生命周期／權限／錯誤（假 SpeechEngine；JVM）。唔證明真咪或真 SpeechRecognizer。
class Step3eSpeechVoiceInputTest {
  private class FakeEngine(var permission: Boolean = true, var available: Boolean = true) : SpeechEngine {
    var starts = mutableListOf<String>()
    var stops = 0
    var destroys = 0
    var callbacks: SpeechEngine.Callbacks? = null
    override fun hasPermission() = permission
    override fun isAvailable() = available
    override fun start(locale: String, callbacks: SpeechEngine.Callbacks) {
      starts += locale
      this.callbacks = callbacks
    }
    override fun stop() { stops += 1 }
    override fun destroy() { destroys += 1 }
  }

  private fun input(engine: FakeEngine): Triple<SpeechVoiceInput, MutableList<String>, MutableList<String>> {
    val texts = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val voice = SpeechVoiceInput(engine)
    voice.setUtteranceListener { text, _ -> texts += text }
    voice.setErrorListener { errors += it }
    return Triple(voice, texts, errors)
  }

  @Test
  fun e1_oneShotRecognitionDeliversTextOnceThenStops() {
    val engine = FakeEngine()
    val (voice, texts, _) = input(engine)
    voice.startListening(hotword = false)
    voice.startListening(hotword = false) // 已聽緊：唔重複開
    assertEquals(listOf(NativeConfig.SPEECH_LOCALE_DEFAULT), engine.starts)
    assertTrue(voice.isListening)
    engine.callbacks!!.onText("詳細啲", 0.9)
    engine.callbacks!!.onText("重複", 0.9)
    assertEquals(listOf("詳細啲"), texts)
    assertFalse(voice.isListening)
  }

  @Test
  fun e2_hotwordIsNotAlwaysOnListening() {
    val engine = FakeEngine()
    val (voice, _, errors) = input(engine)
    voice.startListening(hotword = true)
    assertTrue(engine.starts.isEmpty())
    assertEquals(listOf(SpeechVoiceInput.HOTWORD_UNSUPPORTED), errors)
  }

  @Test
  fun e3_permissionAndAvailabilityAreReportedNotFaked() {
    val noPerm = FakeEngine(permission = false)
    val (v1, _, e1) = input(noPerm)
    v1.startListening()
    assertEquals(listOf(SpeechVoiceInput.NO_PERMISSION), e1)
    assertTrue(noPerm.starts.isEmpty())
    val noRec = FakeEngine(available = false)
    val (v2, _, e2) = input(noRec)
    v2.startListening()
    assertEquals(listOf(SpeechVoiceInput.NO_RECOGNIZER), e2)
    assertTrue(SpeechVoiceInput.NO_PERMISSION in SpeechVoiceInput.PERMANENT)
    assertFalse(SpeechVoiceInput.NO_MATCH in SpeechVoiceInput.PERMANENT)
  }

  @Test
  fun e4_stopDropsLateCallbacksAndDetachDestroysRecognizer() {
    val engine = FakeEngine()
    val (voice, texts, errors) = input(engine)
    voice.startListening()
    val stale = engine.callbacks!!
    voice.stopListening()
    assertEquals(1, engine.stops)
    stale.onText("遲到", 0.9)
    stale.onError("NO_MATCH")
    assertTrue(texts.isEmpty() && errors.isEmpty())
    voice.startListening()
    engine.callbacks!!.onError(SpeechVoiceInput.mapRecognizerError(7))
    assertEquals(listOf("NO_MATCH"), errors)
    voice.setUtteranceListener(null)
    assertEquals(1, engine.destroys)
    voice.startListening()
    assertEquals(2, engine.starts.size) // 冇 listener 唔開咪
  }

  @Test
  fun e5_errorCodeMapping() {
    assertEquals("NO_PERMISSION", SpeechVoiceInput.mapRecognizerError(9))
    assertEquals("SPEECH_TIMEOUT", SpeechVoiceInput.mapRecognizerError(6))
    assertEquals("NETWORK", SpeechVoiceInput.mapRecognizerError(2))
    assertEquals("LANGUAGE_UNAVAILABLE", SpeechVoiceInput.mapRecognizerError(13))
    assertEquals("ERROR_99", SpeechVoiceInput.mapRecognizerError(99))
  }
}
