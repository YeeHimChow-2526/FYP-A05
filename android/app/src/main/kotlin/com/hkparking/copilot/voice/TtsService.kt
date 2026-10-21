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
  private val capabilityChecker: VoiceCapabilityChecker? = null,
) {
  var isSpeaking: Boolean = false
    private set

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
      return null
    }
    if (capabilityChecker != null && capabilityChecker.ttsStatus != CapabilityStatus.AVAILABLE) {
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
