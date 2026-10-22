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
  val spokenCues = mutableListOf<String>()

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
    if (voiceMode == TripVoiceMode.MANUAL_ONLY || capabilityChecker.ttsStatus != CapabilityStatus.AVAILABLE) {
      val reason = if (voiceMode == TripVoiceMode.MANUAL_ONLY) "degraded" else "tts_unavailable"
      logSkip(voiceMode, reason)
      return null
    }
    if (isApproachMuted && !driverAsked) {
      logSkip(voiceMode, "approach_muted")
      return null
    }
    if (cards.isEmpty()) {
      logSkip(voiceMode, "empty")
      return null
    }
    val first = cards.first()
    val text = if (first.type == METER_TYPE && (first.isStale || sourceStatus == SOURCE_DEGRADED)) {
      ZhHkTemplates.METER_STALE_SPOKEN
    } else {
      first.spokenCue
    }
    queue.addLast(text)
    spokenCues += text
    logSpeak(text)
    if (!isSpeaking) {
      playHead()
    }
    return text
  }

  fun speak(text: String, voiceMode: TripVoiceMode, isApproachMuted: Boolean): String? {
    if (voiceMode == TripVoiceMode.MANUAL_ONLY || capabilityChecker.ttsStatus != CapabilityStatus.AVAILABLE) {
      val reason = if (voiceMode == TripVoiceMode.MANUAL_ONLY) "degraded" else "tts_unavailable"
      logSkip(voiceMode, reason)
      return null
    }
    if (isApproachMuted) {
      logSkip(voiceMode, "approach_muted")
      return null
    }
    if (text.isEmpty()) {
      logSkip(voiceMode, "empty")
      return null
    }
    queue.addLast(text)
    spokenCues += text
    logSpeak(text)
    if (!isSpeaking) {
      playHead()
    }
    return text
  }

  fun interrupt() {
    playbackEpoch += 1
    queue.clear()
    isSpeaking = false
    engine.stop()
  }

  private fun logSpeak(text: String) {
    runCatching {
      android.util.Log.i("TtsService", "speak cue=\"$text\"")
    }
  }

  private fun logSkip(voiceMode: TripVoiceMode, reason: String) {
    runCatching {
      android.util.Log.i(
        "TtsService",
        "speak skipped: mode=$voiceMode ttsStatus=${capabilityChecker.ttsStatus} reason=$reason",
      )
    }
  }

  private fun playHead() {
    val headText = queue.firstOrNull() ?: return
    playbackEpoch += 1
    val epoch = playbackEpoch
    isSpeaking = true
    engine.speak(
      headText,
      onDone = { if (epoch == playbackEpoch) onCurrentUtteranceFinished() },
      onError = { if (epoch == playbackEpoch) onCurrentUtteranceFailed() },
    )
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
  }
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
    return runCatching {
      tts.setLanguage(java.util.Locale.forLanguageTag(locale))
      true
    }.getOrDefault(false)
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
