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
  fun speak(text: String)
  fun stop()
}

class TtsService(
  private val engine: TtsEngine,
) {
  var isSpeaking: Boolean = false
    private set
  var cantoneseAvailable: Boolean = false
    private set
  var mandarinFallbackRefused: Boolean = false
    private set

  private val queue = ArrayDeque<String>()

  init {
    cantoneseAvailable = engine.bindLocale(NativeConfig.SPEECH_LOCALE_DEFAULT)
    if (!cantoneseAvailable) {
      mandarinFallbackRefused = true
    }
  }

  fun pendingCount(): Int = queue.size

  fun speakFirstChoice(
    cards: List<RecommendationCard>,
    sourceStatus: String,
    voiceMode: TripVoiceMode,
    isApproachMuted: Boolean,
    driverAsked: Boolean = false,
  ): String? {
    if (!cantoneseAvailable || voiceMode == TripVoiceMode.MANUAL_ONLY) {
      return null
    }
    if (isApproachMuted && !driverAsked) {
      return null
    }
    if (cards.isEmpty()) {
      return null
    }
    val first = cards.first()
    val text = if (first.type == METER_TYPE && (first.isStale || sourceStatus == SOURCE_DEGRADED)) {
      ZhHkTemplates.METER_STALE_SPOKEN
    } else {
      first.spokenCue
    }
    queue.addLast(text)
    isSpeaking = true
    engine.speak(text)
    return text
  }

  fun interrupt() {
    queue.clear()
    isSpeaking = false
    engine.stop()
  }

  companion object {
    const val METER_TYPE = "METER"
    const val SOURCE_DEGRADED = "degraded"
  }
}
