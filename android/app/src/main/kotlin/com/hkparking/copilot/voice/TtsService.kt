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
  engine: TtsEngine?,
  private val capabilityChecker: VoiceCapabilityChecker,
) {
  var isSpeaking: Boolean = false
    private set
  val spokenCues = ArrayDeque<String>()

  private var engine: TtsEngine? = engine
  private val queue = ArrayDeque<QueuedUtterance>()
  private var playbackEpoch: Int = 0

  init {
    engine?.bindLocale(NativeConfig.SPEECH_LOCALE_DEFAULT)
  }

  fun attachEngine(next: TtsEngine): Boolean {
    engine = next
    return next.bindLocale(NativeConfig.SPEECH_LOCALE_DEFAULT)
  }

  fun pendingCount(): Int = queue.size

  fun speakFirstChoice(
    cards: List<RecommendationCard>,
    sourceStatus: String,
    voiceMode: TripVoiceMode,
    isApproachMuted: Boolean,
    driverAsked: Boolean = false,
    onFinished: ((played: Boolean) -> Unit)? = null,
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
    enqueue(text, onFinished)
    return text
  }

  fun speak(
    text: String,
    voiceMode: TripVoiceMode,
    isApproachMuted: Boolean,
    driverAsked: Boolean = false,
    onFinished: ((played: Boolean) -> Unit)? = null,
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
    enqueue(text, onFinished)
    return text
  }

  fun bindLocale(setLanguageCode: Int): Boolean {
    val ok = ttsLanguageBindSucceeded(setLanguageCode)
    if (!ok) logEngineFailure("bind_locale_$setLanguageCode")
    return ok
  }

  fun interrupt() {
    playbackEpoch += 1
    val pending = queue.toList()
    queue.clear()
    isSpeaking = false
    pending.forEach { finishOnce(it, false) }
    engine?.stop()
  }

  private fun enqueue(text: String, onFinished: ((Boolean) -> Unit)?) {
    queue.addLast(QueuedUtterance(text, onFinished))
    rememberCue(text)
    if (!isSpeaking) {
      playHead()
    }
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
    val head = queue.firstOrNull() ?: return
    playbackEpoch += 1
    val epoch = playbackEpoch
    isSpeaking = true
    val active = engine
    if (active == null) {
      logEngineFailure("engine_missing")
      settle(head, played = false, epoch = epoch)
      return
    }
    val played = runCatching {
      active.speak(
        head.text,
        onDone = { settle(head, played = true, epoch = epoch) },
        onError = {
          if (epoch == playbackEpoch) logEngineFailure("engine_error")
          settle(head, played = false, epoch = epoch)
        },
      )
    }
    if (played.isFailure) {
      logEngineFailure("engine_throw")
      settle(head, played = false, epoch = epoch)
    }
  }

  private fun settle(item: QueuedUtterance, played: Boolean, epoch: Int) {
    if (item.finished) return
    val accepted = epoch == playbackEpoch
    queue.remove(item)
    finishOnce(item, if (accepted) played else false)
    if (!accepted) return
    if (queue.isNotEmpty()) {
      isSpeaking = true
      playHead()
    } else {
      isSpeaking = false
    }
  }

  private fun finishOnce(item: QueuedUtterance, played: Boolean) {
    if (item.finished) return
    item.finished = true
    runCatching { item.onFinished?.invoke(played) }
  }

  private class QueuedUtterance(
    val text: String,
    val onFinished: ((Boolean) -> Unit)?,
  ) {
    var finished: Boolean = false
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

fun createTtsEngine(
  context: android.content.Context,
  onInitFailed: (String) -> Unit,
): TtsEngine? {
  return try {
    AndroidTextToSpeechEngine(context, onInitFailed)
  } catch (error: Throwable) {
    onInitFailed(error.javaClass.simpleName)
    null
  }
}

class AndroidTextToSpeechEngine(
  context: android.content.Context,
  private val onInitFailed: (String) -> Unit,
) : TtsEngine {
  private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private var pendingDone: (() -> Unit)? = null
  private var pendingError: (() -> Unit)? = null
  private var utteranceSerial: Int = 0
  // 只接受「而家嗰句」嘅回呼；stop() 後到嘅舊 onDone／onStop 唔會完成下一句（防誤開咪）。
  private var currentUtteranceId: String? = null
  private val tts: android.speech.tts.TextToSpeech =
    android.speech.tts.TextToSpeech(context, this::onInit)

  init {
    tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {}

      override fun onDone(utteranceId: String?) {
        mainHandler.post { finishUtterance(utteranceId, success = true) }
      }

      @Deprecated("Use onError(utteranceId, errorCode)")
      override fun onError(utteranceId: String?) {
        mainHandler.post { finishUtterance(utteranceId, success = false) }
      }

      override fun onError(utteranceId: String?, errorCode: Int) {
        mainHandler.post { finishUtterance(utteranceId, success = false) }
      }

      override fun onStop(utteranceId: String?, interrupted: Boolean) {
        mainHandler.post { finishUtterance(utteranceId, success = false) }
      }
    })
  }

  private fun onInit(status: Int) {
    if (status != android.speech.tts.TextToSpeech.SUCCESS) {
      onInitFailed("status=$status")
      return
    }
    if (!bindLocale(NativeConfig.SPEECH_LOCALE_DEFAULT)) {
      onInitFailed("bind_locale")
    }
  }

  override fun bindLocale(locale: String): Boolean {
    val code = runCatching { tts.setLanguage(java.util.Locale.forLanguageTag(locale)) }.getOrNull()
    return code != null && ttsLanguageBindSucceeded(code)
  }

  override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
    pendingDone = onDone
    pendingError = onError
    utteranceSerial += 1
    val utteranceId = "cue-$utteranceSerial"
    currentUtteranceId = utteranceId
    val code = runCatching {
      tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, utteranceId)
    }.getOrNull()
    if (code == null || code == android.speech.tts.TextToSpeech.ERROR) {
      mainHandler.post { finishUtterance(utteranceId, success = false) }
    }
  }

  override fun stop() {
    pendingDone = null
    pendingError = null
    currentUtteranceId = null
    runCatching { tts.stop() }
  }

  private fun finishUtterance(utteranceId: String?, success: Boolean) {
    if (utteranceId == null || utteranceId != currentUtteranceId) return
    currentUtteranceId = null
    val done = pendingDone
    val failed = pendingError
    pendingDone = null
    pendingError = null
    if (success) done?.invoke() else failed?.invoke()
  }
}
