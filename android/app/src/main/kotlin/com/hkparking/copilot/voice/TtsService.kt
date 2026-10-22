package com.hkparking.copilot.voice

import com.hkparking.copilot.NativeConfig

data class RecommendationCard(
  val carparkId: String,
  val name: String,
  val type: String,
  val vacancies: Int,
  val distanceMeters: Int,
  val feeSummary: String,
  val spokenCue: String,
  val isStale: Boolean,
)

interface TtsEngine {
  fun bindLocale(locale: String): Boolean
  fun speak(text: String, onDone: () -> Unit = {}, onError: () -> Unit = {})
  fun stop()
}

class TtsService(
  private val engine: TtsEngine,
  private val capabilityChecker: VoiceCapabilityChecker,
) {
  var isSpeaking: Boolean = false
    private set
  val spokenCues = ArrayDeque<String>()

  private val queue = ArrayDeque<String>()
  private var playbackEpoch: Int = 0

  init {
    engine.bindLocale(NativeConfig.SPEECH_LOCALE_DEFAULT)
  }

  fun pendingCount(): Int = queue.size

  fun speakFirstChoice(
    cards: List<RecommendationCard>,
    sourceStatus: String,
    voiceMode: TripVoiceMode,
    isApproachMuted: Boolean,
    driverAsked: Boolean = false,
  ): String? {
    if (voiceMode == TripVoiceMode.MANUAL_ONLY) {
      logManualSkip()
      return null
    }
    logSpeak(firstCueOf(cards, sourceStatus) ?: "", voiceMode)
    if (capabilityChecker.ttsStatus != CapabilityStatus.AVAILABLE) {
      logEngineFailure("tts_unavailable")
      return null
    }
    if (isApproachMuted && !driverAsked) {
      logEngineFailure("approach_muted")
      return null
    }
    if (cards.isEmpty()) {
      logEngineFailure("empty")
      return null
    }
    val first = cards.first()
    val text = if (first.type == METER_TYPE && (first.isStale || sourceStatus == SOURCE_DEGRADED)) {
      ZhHkTemplates.METER_STALE_SPOKEN
    } else {
      first.spokenCue
    }
    queue.addLast(text)
    rememberCue(text)
    if (!isSpeaking) {
      playHead()
    }
    return text
  }

  fun speak(
    text: String,
    voiceMode: TripVoiceMode,
    isApproachMuted: Boolean,
    driverAsked: Boolean = false,
  ): String? {
    if (voiceMode == TripVoiceMode.MANUAL_ONLY) {
      logManualSkip()
      return null
    }
    logSpeak(text, voiceMode)
    if (capabilityChecker.ttsStatus != CapabilityStatus.AVAILABLE) {
      logEngineFailure("tts_unavailable")
      return null
    }
    if (isApproachMuted && !driverAsked) {
      logEngineFailure("approach_muted")
      return null
    }
    if (text.isEmpty()) {
      logEngineFailure("empty")
      return null
    }
    queue.addLast(text)
    rememberCue(text)
    if (!isSpeaking) {
      playHead()
    }
    return text
  }

  fun bindLocale(setLanguageCode: Int): Boolean {
    val ok = ttsLanguageBindSucceeded(setLanguageCode)
    if (!ok) logEngineFailure("bind_locale_$setLanguageCode")
    return ok
  }

  fun interrupt() {
    playbackEpoch += 1
    queue.clear()
    isSpeaking = false
    engine.stop()
  }

  private fun firstCueOf(cards: List<RecommendationCard>, sourceStatus: String): String? {
    if (cards.isEmpty()) return null
    val first = cards.first()
    return if (first.type == METER_TYPE && (first.isStale || sourceStatus == SOURCE_DEGRADED)) {
      ZhHkTemplates.METER_STALE_SPOKEN
    } else {
      first.spokenCue
    }
  }

  private fun rememberCue(text: String) {
    spokenCues.addLast(text)
    while (spokenCues.size > SPOKEN_CUE_HISTORY_LIMIT) {
      spokenCues.removeFirst()
    }
  }

  private fun logSpeak(text: String, voiceMode: TripVoiceMode) {
    runCatching {
      android.util.Log.i("TtsService", "speak cue=$text mode=$voiceMode")
    }
  }

  private fun logManualSkip() {
    runCatching {
      android.util.Log.i("TtsService", "skip speak mode=MANUAL_ONLY")
    }
  }

  private fun logEngineFailure(reason: String) {
    runCatching {
      android.util.Log.i("TtsService", "speak failed reason=$reason")
    }
  }

  private fun playHead() {
    val headText = queue.firstOrNull() ?: return
    playbackEpoch += 1
    val epoch = playbackEpoch
    isSpeaking = true
    val played = runCatching {
      engine.speak(
        headText,
        onDone = { if (epoch == playbackEpoch) onCurrentUtteranceFinished() },
        onError = {
          if (epoch == playbackEpoch) {
            logEngineFailure("engine_error")
            onCurrentUtteranceFailed()
          }
        },
      )
    }
    if (played.isFailure) {
      logEngineFailure("engine_throw")
      onCurrentUtteranceFailed()
    }
  }

  private fun onCurrentUtteranceFinished() {
    advanceQueue()
  }

  private fun onCurrentUtteranceFailed() {
    advanceQueue()
  }

  private fun advanceQueue() {
    if (queue.isNotEmpty()) {
      queue.removeFirst()
    }
    if (queue.isNotEmpty()) {
      isSpeaking = true
      playHead()
    } else {
      isSpeaking = false
    }
  }

  companion object {
    const val METER_TYPE = "METER"
    const val SOURCE_DEGRADED = "degraded"
    const val SPOKEN_CUE_HISTORY_LIMIT = 32
  }
}

const val TTS_LANG_AVAILABLE = 0
const val TTS_LANG_MISSING_DATA = -1
const val TTS_LANG_NOT_SUPPORTED = -2

fun ttsLanguageBindSucceeded(setLanguageCode: Int): Boolean {
  return setLanguageCode >= TTS_LANG_AVAILABLE
}

fun createTtsEngine(context: android.content.Context): TtsEngine {
  return runCatching { AndroidTextToSpeechEngine(context) }.getOrElse { RecordingTtsEngine() }
}

class RecordingTtsEngine : TtsEngine {
  var boundLocale: String? = null
    private set

  override fun bindLocale(locale: String): Boolean {
    boundLocale = locale
    return true
  }

  override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
    onDone()
  }

  override fun stop() {}
}

class AndroidTextToSpeechEngine(
  context: android.content.Context,
) : TtsEngine {
  private val tts: android.speech.tts.TextToSpeech =
    android.speech.tts.TextToSpeech(context, this::onInit)

  private fun onInit(status: Int) {
    if (status == android.speech.tts.TextToSpeech.SUCCESS) {
      bindLocale(NativeConfig.SPEECH_LOCALE_DEFAULT)
    }
  }

  override fun bindLocale(locale: String): Boolean {
    val code = runCatching { tts.setLanguage(java.util.Locale.forLanguageTag(locale)) }.getOrNull()
    return code != null && ttsLanguageBindSucceeded(code)
  }

  override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
    val result = runCatching {
      tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "first-choice")
    }
    if (result.isFailure || result.getOrNull() == android.speech.tts.TextToSpeech.ERROR) {
      onError()
    } else {
      onDone()
    }
  }

  override fun stop() {
    runCatching { tts.stop() }
  }
}
