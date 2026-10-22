package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.NetworkStatusProvider
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TtsEngine
import com.hkparking.copilot.voice.TtsService
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.VoiceSnapshot
import com.hkparking.copilot.voice.ZhHkTemplates
import com.hkparking.copilot.voice.classifySttAvailability
import com.hkparking.copilot.voice.classifyTtsAvailability
import com.hkparking.copilot.voice.isAcceptedSpeechLocale
import com.hkparking.copilot.voice.ttsLanguageBindSucceeded
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Step17jBehaviorTest {
  @Test
  fun missingTtsDataIsNotInstalledAndManualOnly() {
    val missing = android.speech.tts.TextToSpeech.LANG_MISSING_DATA
    val unsupported = android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
    assertEquals(
      CapabilityStatus.SUPPORTED_NOT_INSTALLED,
      classifyTtsAvailability(missing, emptyList()),
    )
    assertEquals(CapabilityStatus.UNSUPPORTED, classifyTtsAvailability(unsupported, emptyList()))
    assertFalse(ttsLanguageBindSucceeded(missing))
    assertFalse(ttsLanguageBindSucceeded(unsupported))
    assertTrue(ttsLanguageBindSucceeded(android.speech.tts.TextToSpeech.LANG_AVAILABLE))
    val checker = VoiceCapabilityChecker(
      FixedProbe(
        LocaleProbe(setLanguageSucceeded = true, supported = true),
        LocaleProbe(setLanguageSucceeded = true, supported = true),
      ),
    )
    val mode = checker.check(recordAudioGranted = true)
    assertEquals(CapabilityStatus.SUPPORTED_NOT_INSTALLED, mode.tts)
    assertEquals(TripVoiceMode.MANUAL_ONLY, mode.mode)
    val tts = TtsService(FakeEngine(), checker)
    assertFalse(tts.bindLocale(missing))
    assertFalse(tts.bindLocale(unsupported))
  }

  @Test
  fun cantoneseLocaleDoesNotAcceptMandarinTags() {
    assertTrue(isAcceptedSpeechLocale("zh-HK"))
    assertTrue(isAcceptedSpeechLocale("yue-HK"))
    assertFalse(isAcceptedSpeechLocale("zh-CN"))
    assertFalse(isAcceptedSpeechLocale("zh-TW"))
    assertFalse(isAcceptedSpeechLocale("zh"))
    assertFalse(isAcceptedSpeechLocale("cmn"))
    assertEquals(
      CapabilityStatus.UNKNOWN,
      classifySttAvailability(30, null, listOf("zh-HK"), emptyList(), queryFailed = false),
    )
    assertEquals(
      CapabilityStatus.UNSUPPORTED,
      classifySttAvailability(33, true, listOf("zh-CN"), listOf("cmn"), queryFailed = false),
    )
    assertEquals(
      CapabilityStatus.AVAILABLE,
      classifyTtsAvailability(
        android.speech.tts.TextToSpeech.LANG_COUNTRY_AVAILABLE,
        listOf(VoiceSnapshot("zh-HK", networkRequired = false, notInstalled = false)),
      ),
    )
    assertEquals(
      CapabilityStatus.SUPPORTED_NOT_INSTALLED,
      classifyTtsAvailability(
        android.speech.tts.TextToSpeech.LANG_AVAILABLE,
        listOf(VoiceSnapshot("zh-HK", networkRequired = true, notInstalled = false)),
      ),
    )
  }

  @Test
  fun capabilityRecheckTimeoutDoesNotBlockFgsStarted() {
    val checker = VoiceCapabilityChecker(HangProbe())
    val service = FloatingBubbleService(checker)
    val activity = FgsBootstrapActivity(service)
    assertTrue(activity.launchFromEntry(StartupEntry.A_1, PermissionCheckInput()))
    service.reportFgsStarted()
    assertTrue(activity.fgsStartedReceived)
    assertTrue(service.isFgsRunning)
    assertEquals(TripVoiceMode.MANUAL_ONLY, service.tripStateMachine.tripVoiceMode)
  }

  @Test
  fun driverActiveFailureSpeaksAndBackgroundPrefetchStaysSilent() {
    val clock = ProbeClock()
    val service = voicedService(clock)
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    assertTrue(receiver.dispatchEvent("NETWORK_FAIL"))
    val before = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    clock.advance(NativeConfig.API_TIMEOUT_MS)
    assertTrue(service.ttsService.spokenCues.isEmpty())
    var guard = 0
    while (Bindings.apiTransport.postCount < before + NativeConfig.API_MAX_ATTEMPTS && guard < 20) {
      clock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
      clock.advance(NativeConfig.API_TIMEOUT_MS)
      guard += 1
    }
    assertTrue(service.ttsService.spokenCues.isEmpty())
    Bindings.detachService(service)

    val activeClock = ProbeClock()
    val active = voicedService(activeClock)
    val activeReceiver = Bindings.activeReceiver!!
    assertTrue(activeReceiver.dispatchEvent("START_TRIP"))
    assertTrue(activeReceiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    assertTrue(activeReceiver.dispatchEvent("NETWORK_FAIL"))
    assertTrue(activeReceiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    activeClock.advance(NativeConfig.API_TIMEOUT_MS)
    assertEquals(listOf(ZhHkTemplates.NETWORK_SLOW), active.ttsService.spokenCues.toList())
    var activeGuard = 0
    while (active.ttsService.spokenCues.none { it == ZhHkTemplates.NETWORK_FAILED } && activeGuard < 20) {
      activeClock.advance(NativeConfig.API_RETRY_INTERVAL_MS)
      activeClock.advance(NativeConfig.API_TIMEOUT_MS)
      activeGuard += 1
    }
    assertEquals(1, active.ttsService.spokenCues.count { it == ZhHkTemplates.NETWORK_SLOW })
    assertEquals(1, active.ttsService.spokenCues.count { it == ZhHkTemplates.NETWORK_FAILED })
    assertEquals(TripState.GREY_BREATHING, active.tripStateMachine.state)
    Bindings.detachService(active)
  }

  @Test
  fun approachMutedDriverAskSpeaksButManualOnlyNeverSpeaks() {
    val service = voicedService(ProbeClock())
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    assertTrue(receiver.dispatchEvent("SET_VOICE_MODE", stringValue = "ANNOUNCE_MANUAL"))
    assertTrue(receiver.dispatchEvent("SET_DISTANCE_M", intValue = 500))
    assertTrue(service.ttsService.spokenCues.isEmpty())
    assertTrue(receiver.dispatchEvent("ARRIVE_CORE"))
    assertEquals(TripState.APPROACH_MUTED, service.tripStateMachine.state)
    assertTrue(receiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertEquals(
      listOf("前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。"),
      service.ttsService.spokenCues.toList(),
    )
    Bindings.detachService(service)

    val manual = voicedService(ProbeClock())
    val manualReceiver = Bindings.activeReceiver!!
    assertTrue(manualReceiver.dispatchEvent("START_TRIP"))
    assertTrue(manualReceiver.dispatchEvent("SET_VOICE_MODE", stringValue = "MANUAL_ONLY"))
    assertTrue(manualReceiver.dispatchEvent("INJECT_UTTERANCE", stringValue = "搵位"))
    assertTrue(manual.ttsService.spokenCues.isEmpty())
    Bindings.detachService(manual)
  }

  @Test
  fun offlineShutdownSkipsRelease() {
    val offline = NetworkStatusProvider { false }
    val clock = ProbeClock()
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    val service = FloatingBubbleService(
      VoiceCapabilityChecker(ReadyProbe()),
      TripStateMachine(),
      clock.delayMs,
      offline,
    )
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    service.reportFgsStarted()
    val receiver = Bindings.activeReceiver!!
    assertTrue(receiver.dispatchEvent("START_TRIP"))
    val before = Bindings.apiTransport.postCount
    assertTrue(receiver.dispatchEvent("ENGINE_SHUTDOWN", intValue = 1))
    assertEquals(TripState.PARKED_CONFIRMED, service.tripStateMachine.state)
    assertTrue(Bindings.pendingDriverConfirm)
    assertEquals(before, Bindings.apiTransport.postCount)
    Bindings.detachService(service)
  }

  private fun voicedService(clock: ProbeClock): FloatingBubbleService {
    Bindings.locationSource.reset()
    Bindings.apiTransport.reset()
    Bindings.voiceInput.stopListening()
    val service = FloatingBubbleService(VoiceCapabilityChecker(ReadyProbe()), TripStateMachine(), clock.delayMs)
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    service.reportFgsStarted()
    return service
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

  private class FixedProbe(
    private val stt: LocaleProbe,
    private val tts: LocaleProbe,
  ) : SpeechCapabilityProbe {
    override fun probeStt(locale: String): LocaleProbe = stt
    override fun probeTts(locale: String): LocaleProbe = tts
  }

  private class HangProbe : SpeechCapabilityProbe {
    override fun probeStt(locale: String): LocaleProbe {
      runCatching { java.util.concurrent.CountDownLatch(1).await() }
      return LocaleProbe()
    }
    override fun probeTts(locale: String): LocaleProbe = LocaleProbe()
  }

  private class FakeEngine : TtsEngine {
    override fun bindLocale(locale: String): Boolean = true
    override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
      onDone()
    }
    override fun stop() {}
  }
}

private class ProbeClock {
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
