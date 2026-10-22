package com.hkparking.copilot

import com.hkparking.copilot.di.safeIsOnline
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult
import com.hkparking.copilot.net.QueryTriggerMode
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.TtsEngine
import com.hkparking.copilot.voice.TtsService
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step17kBehaviorTest {
  @Test
  fun ttsInitFailureDegradesToManualOnly() {
    val checker = VoiceCapabilityChecker(ReadyProbe())
    checker.check(recordAudioGranted = true)
    val service = FloatingBubbleService(checker)
    service.onTtsInitFailed("status=-1")
    assertNotEquals(CapabilityStatus.AVAILABLE, service.capabilityChecker.ttsStatus)
    assertEquals(TripVoiceMode.MANUAL_ONLY, service.tripStateMachine.tripVoiceMode)
    assertEquals(TripVoiceMode.MANUAL_ONLY, service.capabilityChecker.tripVoiceMode)
  }

  @Test
  fun queuedSpeakWaitsForPreviousDone() {
    val engine = HoldEngine()
    val checker = VoiceCapabilityChecker(ReadyProbe())
    checker.check(recordAudioGranted = true)
    val tts = TtsService(engine, checker)
    assertEquals("第一句", tts.speak("第一句", TripVoiceMode.FULL_VOICE, isApproachMuted = false))
    assertEquals("第二句", tts.speak("第二句", TripVoiceMode.FULL_VOICE, isApproachMuted = false))
    assertEquals(listOf("第一句"), engine.started)
    engine.finish()
    assertEquals(listOf("第一句", "第二句"), engine.started)
  }

  @Test
  fun driverTimeoutSpeaksBeforeNextAttemptAndBackgroundStaysSilent() {
    val posts = mutableListOf<Int>()
    var releaseSecond: Continuation<Unit>? = null
    val transport = object : ApiTransport {
      override suspend fun post(path: String, bodyJson: String): HttpResult {
        posts += 1
        if (posts.size == 1) return HttpResult(statusCode = 0, timedOut = true)
        suspendCoroutine { releaseSecond = it }
        return HttpResult(statusCode = 0, timedOut = true)
      }
    }
    val spoken = mutableListOf<String>()
    val machine = TripStateMachine()
    machine.requestStart(StartupEntry.A_2, destinationName = "朗豪坊")
    machine.onFgsStarted()
    val client = ApiClient(transport, machine, onAttemptFailed = { attempt, mode, timedOut ->
      if (mode == QueryTriggerMode.DRIVER_ACTIVE && attempt == 1 && timedOut) {
        spoken += ZhHkTemplates.NETWORK_SLOW
      }
      if (mode == QueryTriggerMode.DRIVER_ACTIVE && attempt >= NativeConfig.API_MAX_ATTEMPTS) {
        spoken += ZhHkTemplates.NETWORK_FAILED
      }
    })
    var done = false
    suspend { client.recommendAndRoute(sampleRequest(), QueryTriggerMode.DRIVER_ACTIVE) }
      .startCoroutine(Continuation(EmptyCoroutineContext) { done = true })
    assertEquals(listOf(ZhHkTemplates.NETWORK_SLOW), spoken)
    assertEquals(1, posts.size)
    assertFalse(done)
    assertTrue(releaseSecond == null)

    val backgroundSpoken = mutableListOf<String>()
    val background = ApiClient(object : ApiTransport {
      override suspend fun post(path: String, bodyJson: String): HttpResult {
        return HttpResult(statusCode = 0, timedOut = true)
      }
    }, TripStateMachine().also {
      it.requestStart(StartupEntry.A_2)
      it.onFgsStarted()
    }, onAttemptFailed = { attempt, mode, timedOut ->
      if (mode == QueryTriggerMode.DRIVER_ACTIVE && attempt == 1 && timedOut) backgroundSpoken += "slow"
    })
    suspend { background.recommendAndRoute(sampleRequest(), QueryTriggerMode.BACKGROUND_PREFETCH) }
      .startCoroutine(Continuation(EmptyCoroutineContext) { })
    assertTrue(backgroundSpoken.isEmpty())
  }

  @Test
  fun safeIsOnlineReturnsFalseWhenQueryThrows() {
    assertFalse(safeIsOnline { throw SecurityException("denied") })
    assertFalse(safeIsOnline { throw IllegalStateException("boom") })
    assertTrue(safeIsOnline { true })
  }

  private fun sampleRequest() = com.hkparking.copilot.net.RecommendRequest(
    sessionId = "sess",
    originLat = 22.3,
    originLng = 114.1,
    destLat = 22.31,
    destLng = 114.17,
    destName = "朗豪坊",
    preference = "SMART_HYBRID",
  )

  private class HoldEngine : TtsEngine {
    val started = mutableListOf<String>()
    private var pendingDone: (() -> Unit)? = null

    override fun bindLocale(locale: String): Boolean = true

    override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
      started += text
      pendingDone = onDone
    }

    override fun stop() {
      pendingDone = null
    }

    fun finish() {
      val done = pendingDone
      pendingDone = null
      done?.invoke()
    }
  }

  private class ReadyProbe : SpeechCapabilityProbe {
    override fun probeStt(locale: String): LocaleProbe = ready()
    override fun probeTts(locale: String): LocaleProbe = ready()
    private fun ready(): LocaleProbe = LocaleProbe(
      setLanguageSucceeded = true,
      offlineCantoneseVoiceInstalled = true,
      canRecognizeOrSynthesize = true,
      supported = true,
    )
  }
}
