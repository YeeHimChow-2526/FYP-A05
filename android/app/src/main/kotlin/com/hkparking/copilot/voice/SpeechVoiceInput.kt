package com.hkparking.copilot.voice

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.di.VoiceInput

/**
 * 平台邊界：原生語音辨識引擎（Android SpeechRecognizer；之後可換 Whisper 而唔郁 [SpeechVoiceInput]）。
 * 介面刻意簡單：start / stop / 文字 / 錯誤。JVM 測試注入假引擎；呢啲測試唔證明真咪。
 */
interface SpeechEngine {
  interface Callbacks {
    fun onText(text: String, confidence: Double?)
    fun onError(code: String)
    fun onEnd()
  }

  fun hasPermission(): Boolean
  fun isAvailable(): Boolean
  fun start(locale: String, callbacks: Callbacks)
  fun stop()
  fun destroy()
}

/**
 * 單次（one-shot）聽寫：每次 [startListening] 只收一句，收到文字／錯誤／完結就自動停。
 * Android SpeechRecognizer 唔適合長開熱詞（耗電、系統提示音、每句要重開），所以 `hotword=true`
 * 唔會開咪，只回報 HOTWORD_UNSUPPORTED（非永久錯誤）。
 */
class SpeechVoiceInput(
  private val engine: SpeechEngine,
  private val locale: String = NativeConfig.SPEECH_LOCALE_DEFAULT,
) : VoiceInput {
  @Volatile private var listener: ((String, Double?) -> Unit)? = null
  @Volatile private var errorListener: ((String) -> Unit)? = null
  @Volatile var isListening: Boolean = false
    private set
  private var session = 0

  override fun setUtteranceListener(listener: ((String, Double?) -> Unit)?) {
    this.listener = listener
    if (listener == null) {
      stopListening()
      runCatching { engine.destroy() }
    }
  }

  override fun setErrorListener(listener: ((String) -> Unit)?) {
    errorListener = listener
  }

  override fun startListening(hotword: Boolean) {
    if (listener == null) return
    if (hotword) {
      errorListener?.invoke(HOTWORD_UNSUPPORTED)
      return
    }
    if (isListening) return
    if (!engine.hasPermission()) {
      errorListener?.invoke(NO_PERMISSION)
      return
    }
    if (!engine.isAvailable()) {
      errorListener?.invoke(NO_RECOGNIZER)
      return
    }
    val mine = ++session
    isListening = true
    val callbacks = object : SpeechEngine.Callbacks {
      override fun onText(text: String, confidence: Double?) {
        if (mine != session || !isListening) return
        isListening = false
        if (text.isNotBlank()) listener?.invoke(text, confidence)
      }

      override fun onError(code: String) {
        if (mine != session) return
        isListening = false
        errorListener?.invoke(code)
      }

      override fun onEnd() {
        if (mine == session) isListening = false
      }
    }
    runCatching { engine.start(locale, callbacks) }.onFailure {
      isListening = false
      errorListener?.invoke(CLIENT)
    }
  }

  override fun stopListening() {
    session += 1 // 之後到嘅舊回呼一律丟棄
    if (!isListening) return
    isListening = false
    runCatching { engine.stop() }
  }

  companion object {
    const val HOTWORD_UNSUPPORTED = "HOTWORD_UNSUPPORTED"
    const val NO_PERMISSION = "NO_PERMISSION"
    const val NO_RECOGNIZER = "NO_RECOGNIZER"
    const val LANGUAGE_UNAVAILABLE = "LANGUAGE_UNAVAILABLE"
    const val NO_MATCH = "NO_MATCH"
    const val SPEECH_TIMEOUT = "SPEECH_TIMEOUT"
    const val BUSY = "BUSY"
    const val NETWORK = "NETWORK"
    const val AUDIO = "AUDIO"
    const val CLIENT = "CLIENT"

    /** 永久（本次行程唔再試）錯誤；其餘係暫時性。 */
    val PERMANENT: Set<String> = setOf(NO_PERMISSION, NO_RECOGNIZER, LANGUAGE_UNAVAILABLE)

    fun mapRecognizerError(error: Int): String = when (error) {
      7 -> NO_MATCH // ERROR_NO_MATCH
      6 -> SPEECH_TIMEOUT // ERROR_SPEECH_TIMEOUT
      9 -> NO_PERMISSION // ERROR_INSUFFICIENT_PERMISSIONS
      8 -> BUSY // ERROR_RECOGNIZER_BUSY
      1, 2, 4 -> NETWORK // ERROR_NETWORK_TIMEOUT / ERROR_NETWORK / ERROR_SERVER
      3 -> AUDIO // ERROR_AUDIO
      5 -> CLIENT // ERROR_CLIENT
      12, 13 -> LANGUAGE_UNAVAILABLE // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31)
      else -> "ERROR_$error"
    }
  }
}

/**
 * Android SpeechRecognizer 實作。所有 SpeechRecognizer 呼叫都 post 去主線程（API 要求）。
 * 有離線粵語（API 31+ on-device）就用 on-device 辨識器，否則用系統預設辨識服務並 prefer offline。
 */
class AndroidSpeechEngine(
  private val contextProvider: () -> android.content.Context?,
) : SpeechEngine {
  private val main = android.os.Handler(android.os.Looper.getMainLooper())
  private var recognizer: android.speech.SpeechRecognizer? = null
  private var onDevice = false

  override fun hasPermission(): Boolean {
    val ctx = contextProvider() ?: return false
    return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED
  }

  override fun isAvailable(): Boolean {
    val ctx = contextProvider() ?: return false
    return runCatching {
      android.speech.SpeechRecognizer.isRecognitionAvailable(ctx) || onDeviceAvailable(ctx)
    }.getOrDefault(false)
  }

  private fun onDeviceAvailable(ctx: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 31 &&
      runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)

  override fun start(locale: String, callbacks: SpeechEngine.Callbacks) {
    main.post {
      val ctx = contextProvider()
      if (ctx == null) {
        callbacks.onError(SpeechVoiceInput.NO_RECOGNIZER)
        return@post
      }
      val rec = recognizer ?: create(ctx).also { recognizer = it }
      if (rec == null) {
        callbacks.onError(SpeechVoiceInput.NO_RECOGNIZER)
        return@post
      }
      rec.setRecognitionListener(Listener(callbacks))
      val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
      }
      runCatching { rec.startListening(intent) }.onFailure { callbacks.onError(SpeechVoiceInput.CLIENT) }
    }
  }

  private fun create(ctx: android.content.Context): android.speech.SpeechRecognizer? = runCatching {
    onDevice = onDeviceAvailable(ctx)
    if (onDevice) {
      android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
    } else if (android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
      android.speech.SpeechRecognizer.createSpeechRecognizer(ctx)
    } else {
      null
    }
  }.getOrNull()

  override fun stop() {
    main.post { runCatching { recognizer?.cancel() } }
  }

  override fun destroy() {
    main.post {
      runCatching { recognizer?.destroy() }
      recognizer = null
    }
  }

  private class Listener(private val callbacks: SpeechEngine.Callbacks) : android.speech.RecognitionListener {
    override fun onResults(results: android.os.Bundle?) {
      val texts = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
      val scores = results?.getFloatArray(android.speech.SpeechRecognizer.CONFIDENCE_SCORES)
      val text = texts?.firstOrNull()
      if (text.isNullOrBlank()) {
        callbacks.onError(SpeechVoiceInput.NO_MATCH)
        return
      }
      val score = scores?.firstOrNull()?.takeIf { it >= 0f }?.toDouble()
      callbacks.onText(text, score)
    }

    override fun onError(error: Int) = callbacks.onError(SpeechVoiceInput.mapRecognizerError(error))
    override fun onEndOfSpeech() = Unit
    override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
  }
}
