package com.hkparking.copilot

import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.CommandCode
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.PendingInvalidation
import com.hkparking.copilot.voice.PendingQuestion
import com.hkparking.copilot.voice.PreferenceOverride
import com.hkparking.copilot.voice.RecommendationCard
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TtsEngine
import com.hkparking.copilot.voice.TtsService
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hkparking.copilot.voice.TripVoiceMode as VoiceMode

class VoiceAndTtsTest {
  @Test
  fun capabilityStatusesAndExclusiveModes() {
    val available = usableProbe()
    val checker = VoiceCapabilityChecker(available)
    val full = checker.check(recordAudioGranted = true, hotwordEnabled = true, entry = StartupEntry.A_2)
    assertEquals(CapabilityStatus.AVAILABLE, full.stt)
    assertEquals(CapabilityStatus.AVAILABLE, full.tts)
    assertEquals(VoiceMode.FULL_VOICE, full.mode)
    assertTrue(full.voiceInputEnabled)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION_MIC, full.fgsType)
    assertFalse(full.blocksFgsStarted)
    assertEquals(NativeConfig.SPEECH_LOCALE_DEFAULT, available.lastLocale)

    val languageOnly = VoiceCapabilityChecker(
      FakeProbe(
        stt = LocaleProbe(setLanguageSucceeded = true, supported = true),
        tts = LocaleProbe(setLanguageSucceeded = true, supported = true),
      ),
    )
    val notInstalled = languageOnly.check(recordAudioGranted = true, allowDownloadPrompt = true)
    assertEquals(CapabilityStatus.SUPPORTED_NOT_INSTALLED, notInstalled.stt)
    assertEquals(CapabilityStatus.SUPPORTED_NOT_INSTALLED, notInstalled.tts)
    assertNotEquals(CapabilityStatus.AVAILABLE, notInstalled.tts)
    assertEquals(VoiceMode.MANUAL_ONLY, notInstalled.mode)
    assertTrue(notInstalled.downloadPrompt)

    val unsupported = VoiceCapabilityChecker(
      FakeProbe(
        stt = LocaleProbe(supported = false),
        tts = LocaleProbe(supported = false),
      ),
    ).check(recordAudioGranted = true, allowDownloadPrompt = false)
    assertEquals(CapabilityStatus.UNSUPPORTED, unsupported.stt)
    assertEquals(CapabilityStatus.UNSUPPORTED, unsupported.tts)
    assertEquals(VoiceMode.MANUAL_ONLY, unsupported.mode)

    val unknown = VoiceCapabilityChecker(FakeProbe(LocaleProbe(), LocaleProbe()))
      .check(recordAudioGranted = true, allowDownloadPrompt = false)
    assertEquals(CapabilityStatus.UNKNOWN, unknown.stt)
    assertEquals(CapabilityStatus.UNKNOWN, unknown.tts)
    assertEquals(VoiceMode.MANUAL_ONLY, unknown.mode)

    val noMicPermission = VoiceCapabilityChecker(usableProbe())
      .check(recordAudioGranted = false, entry = StartupEntry.A_2)
    assertEquals(VoiceMode.ANNOUNCE_MANUAL, noMicPermission.mode)
    assertFalse(noMicPermission.voiceInputEnabled)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, noMicPermission.fgsType)
    assertTrue(noMicPermission.ttsUsable)

    val sttMissing = VoiceCapabilityChecker(
      FakeProbe(
        stt = LocaleProbe(supported = false),
        tts = usable(),
      ),
    ).check(recordAudioGranted = true)
    assertEquals(VoiceMode.ANNOUNCE_MANUAL, sttMissing.mode)
    assertEquals(CapabilityStatus.AVAILABLE, sttMissing.tts)

    val micFailed = VoiceCapabilityChecker(usableProbe())
    micFailed.check(recordAudioGranted = true, entry = StartupEntry.A_1)
    micFailed.onMicReadyFailed()
    assertEquals(VoiceMode.ANNOUNCE_MANUAL, micFailed.tripVoiceMode)
    assertFalse(micFailed.voiceInputEnabled)
    assertFalse(micFailed.hotwordActive)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, micFailed.fgsType)
    assertEquals(CapabilityStatus.AVAILABLE, micFailed.ttsStatus)
    assertTrue(micFailed.currentSnapshot(timedOut = false).ttsUsable)
    assertFalse(micFailed.blocksFgsStarted)

    val revoked = VoiceCapabilityChecker(usableProbe())
    revoked.check(recordAudioGranted = true)
    revoked.onRecordAudioRevoked()
    assertFalse(revoked.recordAudioGranted)
    assertEquals(VoiceMode.ANNOUNCE_MANUAL, revoked.tripVoiceMode)
    assertFalse(revoked.voiceInputEnabled)
    assertEquals(CapabilityStatus.AVAILABLE, revoked.ttsStatus)
  }

  @Test
  fun silentRecheckTimeoutReusesConfirmedOrTreatsUnknownAsUnavailable() {
    val probe = usableProbe()
    val checker = VoiceCapabilityChecker(probe)
    val within = checker.recheckSilently(recordAudioGranted = true)
    assertEquals(VoiceMode.FULL_VOICE, within.mode)
    assertFalse(within.timedOut)
    assertFalse(within.blocksFgsStarted)
    assertFalse(within.downloadPrompt)
    assertEquals(1, probe.calls)

    val hanging = HangAfterFirstProbe(usable(), usable())
    val confirmed = VoiceCapabilityChecker(hanging)
    val first = confirmed.recheckSilently(recordAudioGranted = true)
    assertFalse(first.timedOut)
    hanging.hang = true
    val timedOut = confirmed.recheckSilently(recordAudioGranted = true)
    assertTrue(timedOut.timedOut)
    assertEquals(VoiceMode.FULL_VOICE, timedOut.mode)
    assertEquals(CapabilityStatus.AVAILABLE, timedOut.stt)
    assertEquals(CapabilityStatus.AVAILABLE, timedOut.tts)
    assertFalse(timedOut.blocksFgsStarted)
    assertFalse(timedOut.downloadPrompt)

    val never = VoiceCapabilityChecker(HangAfterFirstProbe(usable(), usable(), hangImmediately = true))
    val unavailable = never.recheckSilently(recordAudioGranted = true)
    assertTrue(unavailable.timedOut)
    assertEquals(VoiceMode.MANUAL_ONLY, unavailable.mode)
    assertNotEquals(CapabilityStatus.AVAILABLE, unavailable.stt)
    assertNotEquals(CapabilityStatus.AVAILABLE, unavailable.tts)
    assertFalse(unavailable.voiceInputEnabled)
    assertFalse(unavailable.blocksFgsStarted)
    assertFalse(unavailable.downloadPrompt)
    assertFalse(never.hasConfirmed)
  }

  @Test
  fun commandMappingConfidenceAndQuestionBinding() {
    assertEquals(CommandCode.FIND_PARKING, CommandCodes.parse("我要泊車").code)
    assertEquals(CommandCode.FIND_PARKING, CommandCodes.parse("泊車").code)
    assertEquals(CommandCode.FIND_PARKING, CommandCodes.parse("搵位").code)
    assertEquals(CommandCode.FIND_PARKING, CommandCodes.parse("出發").code)
    assertEquals(CommandCode.SHOW_DETAILS, CommandCodes.parse("詳細啲").code)
    assertEquals(CommandCode.NEXT_OPTION, CommandCodes.parse("有冇第二個").code)
    assertEquals(CommandCode.NEXT_OPTION, CommandCodes.parse("下一個").code)
    assertEquals(CommandCode.NEXT_OPTION, CommandCodes.parse("轉場").code)
    assertEquals(CommandCode.CANCEL, CommandCodes.parse("唔使").code)
    assertEquals(CommandCode.CANCEL, CommandCodes.parse("取消").code)
    assertEquals(CommandCode.CANCEL, CommandCodes.parse("收埋").code)
    assertEquals(CommandCode.CANCEL, CommandCodes.parse("唔好").code)
    assertEquals(CommandCode.CANCEL, CommandCodes.parse("等等").code)
    assertEquals(CommandCode.ON_SITE, CommandCodes.parse("附近").code)
    assertEquals(CommandCode.ON_SITE, CommandCodes.parse("呢度").code)
    assertEquals(CommandCode.ON_SITE, CommandCodes.parse("而家位置").code)
    val meter = CommandCodes.parse("想泊街錶")
    assertEquals(CommandCode.METER_PREFERENCE_OVERRIDE, meter.code)
    assertEquals(PreferenceOverride.METERS_FOCUS, meter.preferenceOverride)
    assertEquals(CommandCode.METER_PREFERENCE_OVERRIDE, CommandCodes.parse("搵個錶位").code)
    val destination = CommandCodes.parse("泊車助手，去朗豪坊")
    assertEquals(CommandCode.SET_DESTINATION, destination.code)
    assertEquals("朗豪坊", destination.destinationName)
    assertEquals("海港城", CommandCodes.parse("泊車助手，目的地海港城").destinationName)

    val low = CommandCodes.parse("詳細啲", ParseContext(confidence = NativeConfig.STT_CONFIDENCE_THRESHOLD - 0.1))
    assertTrue(low.lowConfidence)
    assertFalse(low.accepted)
    assertEquals(ZhHkTemplates.LOW_CONFIDENCE, low.spokenCue)
    assertEquals("太嘈聽唔清，稍後再試", low.spokenCue)
    assertNotEquals(CommandCode.SHOW_DETAILS, low.code)

    val zero = CommandCodes.parse("詳細啲", ParseContext(confidence = 0.0))
    assertTrue(zero.lowConfidence)
    assertNotEquals(CommandCode.SHOW_DETAILS, zero.code)

    val missing = CommandCodes.parse("詳細啲", ParseContext(confidence = null))
    assertEquals(CommandCode.SHOW_DETAILS, missing.code)
    assertTrue(missing.accepted)
    assertTrue(missing.missingConfidenceNotTreatedAsZero)
    assertFalse(missing.lowConfidence)

    val negative = CommandCodes.parse("詳細啲", ParseContext(confidence = -1.0))
    assertEquals(CommandCode.SHOW_DETAILS, negative.code)
    assertTrue(negative.missingConfidenceNotTreatedAsZero)
    assertFalse(negative.lowConfidence)

    val unbound = CommandCodes.parse("好", ParseContext(pendingQuestion = PendingQuestion.NONE))
    assertFalse(unbound.confirmed)
    assertFalse(unbound.accepted)
    val yesUnbound = CommandCodes.parse("Yes", ParseContext())
    assertFalse(yesUnbound.confirmed)

    val bound = CommandCodes.parse(
      "好",
      ParseContext(pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM),
    )
    assertTrue(bound.confirmed)
    assertEquals(CommandCode.CONFIRM_TARGET, bound.code)
    assertTrue(CommandCodes.parse("Yes", ParseContext(pendingQuestion = PendingQuestion.SCARCITY_EXPAND)).confirmed)
    assertTrue(CommandCodes.parse("好的", ParseContext(pendingQuestion = PendingQuestion.CLARIFY_DESTINATION)).confirmed)

    val cleared = CommandCodes.invalidatePending(PendingInvalidation.TIMEOUT)
    assertEquals(PendingQuestion.NONE, cleared)
    assertEquals(PendingQuestion.NONE, CommandCodes.invalidatePending(PendingInvalidation.CANCEL))
    assertEquals(PendingQuestion.NONE, CommandCodes.invalidatePending(PendingInvalidation.DESTINATION_OR_CANDIDATE_CHANGED))
    assertEquals(PendingQuestion.NONE, CommandCodes.invalidatePending(PendingInvalidation.DIALOGUE_INTERRUPTED))
    val afterCancel = CommandCodes.parse("好", ParseContext(pendingQuestion = cleared))
    assertFalse(afterCancel.confirmed)

    val whileSpeaking = CommandCodes.parse(
      "好",
      ParseContext(
        pendingQuestion = PendingQuestion.SILENCE_DRIVE_ASK_DEST,
        isTtsSpeaking = true,
      ),
    )
    assertFalse(whileSpeaking.confirmed)
    assertFalse(whileSpeaking.accepted)
    assertTrue(whileSpeaking.blockedAsSelfHearing)
    assertFalse(
      CommandCodes.parse(
        "Yes",
        ParseContext(pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM, isMapsSpeaking = true),
      ).confirmed,
    )
    assertFalse(
      CommandCodes.parse(
        "好的",
        ParseContext(pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM, isBluetoothSwitching = true),
      ).confirmed,
    )
    assertFalse(
      CommandCodes.parse(
        "去呢個",
        ParseContext(pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM, isInCall = true),
      ).confirmed,
    )
  }

  @Test
  fun voiceReplyPromptOnlyWhenFullVoiceMicNotUpgraded() {
    val cue = ZhHkTemplates.PLACE_CLARIFY
    val prompted = ZhHkTemplates.withVoiceReplyPrompt(cue, VoiceMode.FULL_VOICE, isMicFgsActive = false)
    assertTrue(prompted.endsWith("請拍一拍膠囊答我"))
    assertEquals(cue + ZhHkTemplates.TAP_CAPSULE, prompted)
    assertEquals(cue, ZhHkTemplates.withVoiceReplyPrompt(cue, VoiceMode.ANNOUNCE_MANUAL, isMicFgsActive = false))
    assertEquals(cue, ZhHkTemplates.withVoiceReplyPrompt(cue, VoiceMode.MANUAL_ONLY, isMicFgsActive = false))
    assertEquals(cue, ZhHkTemplates.withVoiceReplyPrompt(cue, VoiceMode.FULL_VOICE, isMicFgsActive = true))
    assertEquals(NativeConfig.SPEECH_LOCALE_DEFAULT, ZhHkTemplates.speechLocale)
    assertEquals("附近可能有咪錶，資料暫時未更新", ZhHkTemplates.METER_STALE_SPOKEN)
    assertEquals("咪錶資料暫時未更新", ZhHkTemplates.METER_STALE_UI)
    assertEquals("聽唔到目的地，請再講一次", ZhHkTemplates.B1_DESTINATION_MISSED)
    assertEquals("暫時搵唔到你位置，請稍後再講一次", ZhHkTemplates.ON_SITE_LOCATION_MISSED)
    assertEquals("網絡有啲慢，我再試吓", ZhHkTemplates.NETWORK_SLOW)
    assertEquals("網絡唔順，遲啲再試", ZhHkTemplates.NETWORK_FAILED)
    assertEquals("暫時冇其他選擇", ZhHkTemplates.NO_MORE_OPTIONS)
  }

  @Test
  fun ttsSpeaksOnlyFirstChoiceAndInterrupts() {
    val engine = FakeEngine()
    val tts = TtsService(engine)
    assertEquals(NativeConfig.SPEECH_LOCALE_DEFAULT, engine.boundLocale)
    assertTrue(tts.cantoneseAvailable)
    val first = card("a", "前方首選甲場，剩十二個位", vacancies = 12)
    val second = card("b", "第二個乙場，剩三個位", vacancies = 3)
    val spoken = tts.speakFirstChoice(
      cards = listOf(first, second),
      sourceStatus = "ok",
      voiceMode = VoiceMode.FULL_VOICE,
      isApproachMuted = false,
    )
    assertEquals(first.spokenCue, spoken)
    assertEquals(listOf(first.spokenCue), engine.spoken)
    assertFalse(engine.spoken.contains(second.spokenCue))
    assertTrue(tts.isSpeaking)
    assertEquals(1, tts.pendingCount())

    tts.interrupt()
    assertFalse(tts.isSpeaking)
    assertEquals(0, tts.pendingCount())
    assertEquals(1, engine.stopCount)

    val staleEngine = FakeEngine()
    val staleTts = TtsService(staleEngine)
    val meter = card("m", "咪錶剩8個位", type = "METER", vacancies = 8, isStale = true)
    val staleSpoken = staleTts.speakFirstChoice(
      cards = listOf(meter, second),
      sourceStatus = "ok",
      voiceMode = VoiceMode.ANNOUNCE_MANUAL,
      isApproachMuted = false,
    )
    assertEquals(ZhHkTemplates.METER_STALE_SPOKEN, staleSpoken)
    assertFalse(staleSpoken!!.contains("8"))
    assertFalse(staleSpoken.contains(meter.vacancies.toString()))
    assertEquals(listOf(ZhHkTemplates.METER_STALE_SPOKEN), staleEngine.spoken)

    val degraded = TtsService(FakeEngine())
    val freshMeter = card("m2", "咪錶剩9個位", type = "METER", vacancies = 9, isStale = false)
    val degradedSpoken = degraded.speakFirstChoice(
      cards = listOf(freshMeter),
      sourceStatus = "degraded",
      voiceMode = VoiceMode.FULL_VOICE,
      isApproachMuted = false,
    )
    assertEquals(ZhHkTemplates.METER_STALE_SPOKEN, degradedSpoken)
    assertFalse(degradedSpoken!!.contains("9"))

    val muted = TtsService(FakeEngine())
    assertNull(
      muted.speakFirstChoice(
        cards = listOf(first),
        sourceStatus = "ok",
        voiceMode = VoiceMode.FULL_VOICE,
        isApproachMuted = true,
      ),
    )
    assertNull(
      TtsService(FakeEngine()).speakFirstChoice(
        cards = listOf(first),
        sourceStatus = "ok",
        voiceMode = VoiceMode.MANUAL_ONLY,
        isApproachMuted = false,
      ),
    )

    val refused = FakeEngine(bindResult = false)
    val noCantonese = TtsService(refused)
    assertFalse(noCantonese.cantoneseAvailable)
    assertTrue(noCantonese.mandarinFallbackRefused)
    assertNull(
      noCantonese.speakFirstChoice(
        cards = listOf(first),
        sourceStatus = "ok",
        voiceMode = VoiceMode.FULL_VOICE,
        isApproachMuted = false,
      ),
    )
    assertTrue(refused.spoken.isEmpty())
  }

  private fun usable(): LocaleProbe {
    return LocaleProbe(
      setLanguageSucceeded = true,
      offlineCantoneseVoiceInstalled = true,
      canRecognizeOrSynthesize = true,
      supported = true,
    )
  }

  private fun usableProbe(): FakeProbe = FakeProbe(usable(), usable())

  private fun card(
    id: String,
    cue: String,
    type: String = "INDOOR",
    vacancies: Int = 1,
    isStale: Boolean = false,
  ): RecommendationCard {
    return RecommendationCard(
      carparkId = id,
      name = id,
      type = type,
      vacancies = vacancies,
      distanceMeters = 180,
      feeSummary = "首小時三十二蚊",
      spokenCue = cue,
      isStale = isStale,
    )
  }
}

private class HangAfterFirstProbe(
  private val stt: LocaleProbe,
  private val tts: LocaleProbe,
  hangImmediately: Boolean = false,
) : SpeechCapabilityProbe {
  var hang: Boolean = hangImmediately

  override fun probeStt(locale: String): LocaleProbe {
    if (hang) {
      try {
        java.util.concurrent.CountDownLatch(1).await()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      return LocaleProbe()
    }
    return stt
  }

  override fun probeTts(locale: String): LocaleProbe = tts
}

private class FakeProbe(
  var stt: LocaleProbe,
  var tts: LocaleProbe,
) : SpeechCapabilityProbe {
  var calls: Int = 0
  var lastLocale: String? = null

  override fun probeStt(locale: String): LocaleProbe {
    calls += 1
    lastLocale = locale
    return stt
  }

  override fun probeTts(locale: String): LocaleProbe {
    lastLocale = locale
    return tts
  }
}

private class FakeEngine(
  private val bindResult: Boolean = true,
) : TtsEngine {
  var boundLocale: String? = null
  val spoken = mutableListOf<String>()
  var stopCount: Int = 0

  override fun bindLocale(locale: String): Boolean {
    boundLocale = locale
    return bindResult
  }

  override fun speak(text: String) {
    spoken.add(text)
  }

  override fun stop() {
    stopCount += 1
  }
}
