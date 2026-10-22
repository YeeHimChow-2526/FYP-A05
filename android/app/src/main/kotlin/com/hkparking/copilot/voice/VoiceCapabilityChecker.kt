package com.hkparking.copilot.voice

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.StartupEntry

enum class CapabilityStatus {
  AVAILABLE,
  SUPPORTED_NOT_INSTALLED,
  UNSUPPORTED,
  UNKNOWN,
}

enum class TripVoiceMode {
  FULL_VOICE,
  ANNOUNCE_MANUAL,
  MANUAL_ONLY,
}

data class LocaleProbe(
  val setLanguageSucceeded: Boolean = false,
  val offlineCantoneseVoiceInstalled: Boolean = false,
  val canRecognizeOrSynthesize: Boolean = false,
  val supported: Boolean? = null,
)

interface SpeechCapabilityProbe {
  fun probeStt(locale: String): LocaleProbe
  fun probeTts(locale: String): LocaleProbe
}

data class CapabilitySnapshot(
  val stt: CapabilityStatus,
  val tts: CapabilityStatus,
  val mode: TripVoiceMode,
  val voiceInputEnabled: Boolean,
  val hotwordActive: Boolean,
  val fgsType: String,
  val downloadPrompt: Boolean,
  val blocksFgsStarted: Boolean,
  val timedOut: Boolean,
  val ttsUsable: Boolean,
)

class VoiceCapabilityChecker(
  private val probe: SpeechCapabilityProbe,
) {
  var sttStatus: CapabilityStatus = CapabilityStatus.UNKNOWN
    private set
  var ttsStatus: CapabilityStatus = CapabilityStatus.UNKNOWN
    private set
  var tripVoiceMode: TripVoiceMode = TripVoiceMode.MANUAL_ONLY
    private set
  var voiceInputEnabled: Boolean = false
    private set
  var hotwordActive: Boolean = false
    private set
  var fgsType: String = FGS_LOCATION
    private set
  var recordAudioGranted: Boolean = false
    private set
  var downloadPromptRequested: Boolean = false
    private set
  var blocksFgsStarted: Boolean = false
    private set
  var hasConfirmed: Boolean = false
    private set

  private var confirmed: CapabilitySnapshot? = null
  private var runtimeInputDisabled: Boolean = false

  fun resolve(probeResult: LocaleProbe): CapabilityStatus {
    val usable = probeResult.offlineCantoneseVoiceInstalled && probeResult.canRecognizeOrSynthesize
    if (usable) {
      return CapabilityStatus.AVAILABLE
    }
    if (
      probeResult.supported == null &&
      !probeResult.setLanguageSucceeded &&
      !probeResult.offlineCantoneseVoiceInstalled
    ) {
      return CapabilityStatus.UNKNOWN
    }
    if (probeResult.supported == false && !probeResult.setLanguageSucceeded) {
      return CapabilityStatus.UNSUPPORTED
    }
    if (
      probeResult.setLanguageSucceeded ||
      probeResult.supported == true ||
      probeResult.offlineCantoneseVoiceInstalled
    ) {
      return CapabilityStatus.SUPPORTED_NOT_INSTALLED
    }
    return CapabilityStatus.UNSUPPORTED
  }

  fun check(
    recordAudioGranted: Boolean,
    hotwordEnabled: Boolean = true,
    entry: StartupEntry = StartupEntry.A_2,
    allowDownloadPrompt: Boolean = false,
  ): CapabilitySnapshot {
    val locale = NativeConfig.SPEECH_LOCALE_DEFAULT
    sttStatus = resolve(probe.probeStt(locale))
    ttsStatus = resolve(probe.probeTts(locale))
    this.recordAudioGranted = recordAudioGranted
    val snapshot = compose(
      stt = sttStatus,
      tts = ttsStatus,
      recordAudioGranted = recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
      entry = entry,
      allowDownloadPrompt = allowDownloadPrompt,
      timedOut = false,
    )
    confirmed = snapshot.copy(downloadPrompt = false, blocksFgsStarted = false, timedOut = false)
    hasConfirmed = true
    apply(snapshot)
    logSnapshot("check")
    return currentSnapshot(timedOut = false)
  }

  fun recheckSilently(
    recordAudioGranted: Boolean,
    hotwordEnabled: Boolean = true,
    entry: StartupEntry = StartupEntry.A_2,
  ): CapabilitySnapshot {
    val locale = NativeConfig.SPEECH_LOCALE_DEFAULT
    val sttProbe = withTimeoutOrNull(NativeConfig.VOICE_CAPABILITY_CHECK_TIMEOUT_MS) {
      probe.probeStt(locale)
    }
    if (sttProbe == null) {
      return timeoutSnapshot(recordAudioGranted, hotwordEnabled, entry)
    }
    val ttsProbe = withTimeoutOrNull(NativeConfig.VOICE_CAPABILITY_CHECK_TIMEOUT_MS) {
      probe.probeTts(locale)
    }
    if (ttsProbe == null) {
      return timeoutSnapshot(recordAudioGranted, hotwordEnabled, entry)
    }
    sttStatus = resolve(sttProbe)
    ttsStatus = resolve(ttsProbe)
    this.recordAudioGranted = recordAudioGranted
    val snapshot = compose(
      stt = sttStatus,
      tts = ttsStatus,
      recordAudioGranted = recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
      entry = entry,
      allowDownloadPrompt = false,
      timedOut = false,
    )
    confirmed = snapshot.copy(downloadPrompt = false, blocksFgsStarted = false, timedOut = false)
    hasConfirmed = true
    apply(snapshot)
    logSnapshot("recheck")
    return currentSnapshot(timedOut = false)
  }

  fun startupSnapshot(
    recordAudioGranted: Boolean,
    hotwordEnabled: Boolean = true,
    entry: StartupEntry = StartupEntry.A_2,
  ): CapabilitySnapshot {
    val stt = if (hasConfirmed) sttStatus else CapabilityStatus.UNKNOWN
    val tts = if (hasConfirmed) ttsStatus else CapabilityStatus.UNKNOWN
    val snapshot = compose(
      stt = stt,
      tts = tts,
      recordAudioGranted = recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
      entry = entry,
      allowDownloadPrompt = false,
      timedOut = false,
    )
    apply(snapshot)
    return currentSnapshot(timedOut = false)
  }

  private fun timeoutSnapshot(
    recordAudioGranted: Boolean,
    hotwordEnabled: Boolean,
    entry: StartupEntry,
  ): CapabilitySnapshot {
    val reused = confirmed
    if (reused == null) {
      sttStatus = CapabilityStatus.UNKNOWN
      ttsStatus = CapabilityStatus.UNKNOWN
      val unavailable = compose(
        stt = CapabilityStatus.UNKNOWN,
        tts = CapabilityStatus.UNKNOWN,
        recordAudioGranted = recordAudioGranted,
        hotwordEnabled = false,
        entry = entry,
        allowDownloadPrompt = false,
        timedOut = true,
      )
      apply(unavailable)
      logSnapshot("timeout")
      return currentSnapshot(timedOut = true)
    }
    sttStatus = reused.stt
    ttsStatus = reused.tts
    this.recordAudioGranted = recordAudioGranted
    val recomputed = compose(
      stt = reused.stt,
      tts = reused.tts,
      recordAudioGranted = recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
      entry = entry,
      allowDownloadPrompt = false,
      timedOut = true,
    )
    apply(recomputed)
    logSnapshot("timeout")
    return currentSnapshot(timedOut = true)
  }

  fun onMicReadyFailed() {
    runtimeInputDisabled = true
    voiceInputEnabled = false
    hotwordActive = false
    fgsType = FGS_LOCATION
    tripVoiceMode = if (ttsStatus == CapabilityStatus.AVAILABLE) {
      TripVoiceMode.ANNOUNCE_MANUAL
    } else {
      TripVoiceMode.MANUAL_ONLY
    }
    blocksFgsStarted = false
    downloadPromptRequested = false
    logSnapshot("mode_changed")
  }

  private fun logSnapshot(reason: String) {
    val usable = ttsStatus == CapabilityStatus.AVAILABLE && tripVoiceMode != TripVoiceMode.MANUAL_ONLY
    runCatching {
      android.util.Log.i(
        "VoiceCapability",
        "mode=$tripVoiceMode stt=$sttStatus tts=$ttsStatus ttsUsable=$usable reason=$reason",
      )
    }
  }

  fun onRecordAudioRevoked() {
    recordAudioGranted = false
    onMicReadyFailed()
  }

  fun applyExternalSnapshot(snapshot: CapabilitySnapshot) {
    sttStatus = snapshot.stt
    ttsStatus = snapshot.tts
    confirmed = snapshot.copy(downloadPrompt = false, blocksFgsStarted = false, timedOut = false)
    hasConfirmed = true
    apply(snapshot)
    logSnapshot("applied")
  }

  fun attachContext(context: android.content.Context) {
    (probe as? ContextAwareProbe)?.attach(context)
  }

  fun currentSnapshot(timedOut: Boolean): CapabilitySnapshot {
    return CapabilitySnapshot(
      stt = sttStatus,
      tts = ttsStatus,
      mode = tripVoiceMode,
      voiceInputEnabled = voiceInputEnabled,
      hotwordActive = hotwordActive,
      fgsType = fgsType,
      downloadPrompt = downloadPromptRequested,
      blocksFgsStarted = false,
      timedOut = timedOut,
      ttsUsable = ttsStatus == CapabilityStatus.AVAILABLE && tripVoiceMode != TripVoiceMode.MANUAL_ONLY,
    )
  }

  private fun apply(snapshot: CapabilitySnapshot) {
    tripVoiceMode = degradedMode(snapshot.mode)
    voiceInputEnabled = !runtimeInputDisabled && snapshot.voiceInputEnabled && tripVoiceMode == TripVoiceMode.FULL_VOICE
    hotwordActive = voiceInputEnabled && snapshot.hotwordActive
    fgsType = if (tripVoiceMode == TripVoiceMode.FULL_VOICE) snapshot.fgsType else FGS_LOCATION
    downloadPromptRequested = snapshot.downloadPrompt
    blocksFgsStarted = false
  }

  private fun degradedMode(mode: TripVoiceMode): TripVoiceMode {
    if (!runtimeInputDisabled) {
      return mode
    }
    return if (ttsStatus == CapabilityStatus.AVAILABLE) {
      TripVoiceMode.ANNOUNCE_MANUAL
    } else {
      TripVoiceMode.MANUAL_ONLY
    }
  }

  private fun compose(
    stt: CapabilityStatus,
    tts: CapabilityStatus,
    recordAudioGranted: Boolean,
    hotwordEnabled: Boolean,
    entry: StartupEntry,
    allowDownloadPrompt: Boolean,
    timedOut: Boolean,
  ): CapabilitySnapshot {
    val ttsAvailable = tts == CapabilityStatus.AVAILABLE
    val sttAvailable = stt == CapabilityStatus.AVAILABLE
    val mode = when {
      ttsAvailable && sttAvailable && recordAudioGranted -> TripVoiceMode.FULL_VOICE
      ttsAvailable -> TripVoiceMode.ANNOUNCE_MANUAL
      else -> TripVoiceMode.MANUAL_ONLY
    }
    val voiceInput = mode == TripVoiceMode.FULL_VOICE
    val hotword = voiceInput && hotwordEnabled
    val download = allowDownloadPrompt &&
      (stt == CapabilityStatus.SUPPORTED_NOT_INSTALLED || tts == CapabilityStatus.SUPPORTED_NOT_INSTALLED)
    return CapabilitySnapshot(
      stt = stt,
      tts = tts,
      mode = mode,
      voiceInputEnabled = voiceInput,
      hotwordActive = hotword,
      fgsType = fgsTypeFor(mode, entry, hotwordEnabled),
      downloadPrompt = download,
      blocksFgsStarted = false,
      timedOut = timedOut,
      ttsUsable = ttsAvailable && mode != TripVoiceMode.MANUAL_ONLY,
    )
  }

  private fun fgsTypeFor(mode: TripVoiceMode, entry: StartupEntry, hotwordEnabled: Boolean): String {
    if (mode != TripVoiceMode.FULL_VOICE) {
      return FGS_LOCATION
    }
    val alwaysMic = entry == StartupEntry.A_1 || entry == StartupEntry.A_3
    val micWhenHotword = entry == StartupEntry.B_1 ||
      entry == StartupEntry.A_2 ||
      entry == StartupEntry.B_3 ||
      entry == StartupEntry.ON_SITE
    if (alwaysMic || (micWhenHotword && hotwordEnabled)) {
      return FGS_LOCATION_MIC
    }
    return FGS_LOCATION
  }

  companion object {
    const val FGS_LOCATION = "location"
    const val FGS_LOCATION_MIC = "location|microphone"
  }
}

fun <T> withTimeoutOrNull(timeoutMs: Long, block: () -> T): T? {
  val done = java.util.concurrent.CountDownLatch(1)
  val holder = arrayOfNulls<Any>(1)
  val worker = Thread {
    try {
      holder[0] = block()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } finally {
      done.countDown()
    }
  }
  worker.isDaemon = true
  worker.start()
  val finished = done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
  if (!finished) {
    worker.interrupt()
    return null
  }
  @Suppress("UNCHECKED_CAST")
  return holder[0] as T?
}

interface ContextAwareProbe {
  fun attach(context: android.content.Context)
}

data class VoiceSnapshot(
  val localeTag: String,
  val networkRequired: Boolean,
  val notInstalled: Boolean,
)

fun isAcceptedSpeechLocale(tag: String): Boolean {
  val normalized = tag.trim().replace('_', '-').lowercase()
  return normalized == "zh-hk" || normalized == "yue-hk"
}

fun classifyTtsAvailability(languageCode: Int?, voices: List<VoiceSnapshot>): CapabilityStatus {
  if (languageCode == null) return CapabilityStatus.UNKNOWN
  if (languageCode == TTS_LANG_NOT_SUPPORTED) return CapabilityStatus.UNSUPPORTED
  if (languageCode == TTS_LANG_MISSING_DATA) return CapabilityStatus.SUPPORTED_NOT_INSTALLED
  if (languageCode < TTS_LANG_AVAILABLE) return CapabilityStatus.UNKNOWN
  val offlineReady = voices.any { voice ->
    isAcceptedSpeechLocale(voice.localeTag) && !voice.networkRequired && !voice.notInstalled
  }
  return if (offlineReady) CapabilityStatus.AVAILABLE else CapabilityStatus.SUPPORTED_NOT_INSTALLED
}

fun classifySttAvailability(
  sdkInt: Int,
  onDeviceAvailable: Boolean?,
  installedLocales: List<String>,
  supportedLocales: List<String>,
  queryFailed: Boolean,
): CapabilityStatus {
  if (queryFailed || sdkInt < STT_SUPPORT_API || onDeviceAvailable == null) return CapabilityStatus.UNKNOWN
  if (!onDeviceAvailable) return CapabilityStatus.UNSUPPORTED
  if (installedLocales.any(::isAcceptedSpeechLocale)) return CapabilityStatus.AVAILABLE
  if (supportedLocales.any(::isAcceptedSpeechLocale)) return CapabilityStatus.SUPPORTED_NOT_INSTALLED
  if (installedLocales.isEmpty() && supportedLocales.isEmpty()) return CapabilityStatus.UNKNOWN
  return CapabilityStatus.UNSUPPORTED
}

fun localeProbeFor(status: CapabilityStatus): LocaleProbe {
  return when (status) {
    CapabilityStatus.AVAILABLE -> LocaleProbe(
      setLanguageSucceeded = true,
      offlineCantoneseVoiceInstalled = true,
      canRecognizeOrSynthesize = true,
      supported = true,
    )
    CapabilityStatus.SUPPORTED_NOT_INSTALLED -> LocaleProbe(
      setLanguageSucceeded = true,
      supported = true,
    )
    CapabilityStatus.UNSUPPORTED -> LocaleProbe(supported = false)
    CapabilityStatus.UNKNOWN -> LocaleProbe()
  }
}

private const val STT_ON_DEVICE_API = 31
private const val STT_SUPPORT_API = 33

class AndroidSpeechProbe(
  context: android.content.Context? = null,
) : SpeechCapabilityProbe, ContextAwareProbe {
  private var context: android.content.Context? = context

  override fun attach(context: android.content.Context) {
    this.context = context
  }

  override fun probeStt(locale: String): LocaleProbe {
    if (onMainThread()) return LocaleProbe()
    val ctx = context ?: return LocaleProbe()
    return runCatching { localeProbeFor(queryStt(ctx)) }.getOrDefault(LocaleProbe())
  }

  override fun probeTts(locale: String): LocaleProbe {
    if (onMainThread()) return LocaleProbe()
    val ctx = context ?: return LocaleProbe()
    return runCatching { localeProbeFor(queryTts(ctx, locale)) }.getOrDefault(LocaleProbe())
  }

  private fun queryTts(ctx: android.content.Context, locale: String): CapabilityStatus {
    val latch = java.util.concurrent.CountDownLatch(1)
    var initStatus = android.speech.tts.TextToSpeech.ERROR
    val tts = android.speech.tts.TextToSpeech(ctx) { status ->
      initStatus = status
      latch.countDown()
    }
    val ready = latch.await(NativeConfig.VOICE_CAPABILITY_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    try {
      if (!ready || initStatus != android.speech.tts.TextToSpeech.SUCCESS) return CapabilityStatus.UNKNOWN
      val javaLocale = java.util.Locale.forLanguageTag(locale)
      val availableCode = tts.isLanguageAvailable(javaLocale)
      val setCode = tts.setLanguage(javaLocale)
      val voices = runCatching { tts.voices }.getOrNull().orEmpty().map { voice ->
        VoiceSnapshot(
          localeTag = voice.locale?.toLanguageTag().orEmpty(),
          networkRequired = voice.isNetworkConnectionRequired,
          notInstalled = voice.features?.contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
        )
      }
      val fromAvailable = classifyTtsAvailability(availableCode, voices)
      val fromSet = classifyTtsAvailability(setCode, voices)
      return moreSevere(fromAvailable, fromSet)
    } finally {
      runCatching { tts.shutdown() }
    }
  }

  private fun queryStt(ctx: android.content.Context): CapabilityStatus {
    val sdk = android.os.Build.VERSION.SDK_INT
    if (sdk < STT_SUPPORT_API) return CapabilityStatus.UNKNOWN
    val onDevice = if (sdk >= STT_ON_DEVICE_API) {
      runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrNull()
    } else {
      null
    }
    if (onDevice != true) return classifySttAvailability(sdk, onDevice, emptyList(), emptyList(), queryFailed = onDevice == null)
    val latch = java.util.concurrent.CountDownLatch(1)
    val installed = mutableListOf<String>()
    val supported = mutableListOf<String>()
    var failed = false
    val recognizer = android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    try {
      val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, NativeConfig.SPEECH_LOCALE_DEFAULT)
      recognizer.checkRecognitionSupport(
        intent,
        executor,
        object : android.speech.RecognitionSupportCallback {
          override fun onSupportResult(support: android.speech.RecognitionSupport) {
            installed += support.installedOnDeviceLanguages
            supported += support.supportedOnDeviceLanguages
            supported += support.pendingOnDeviceLanguages
            latch.countDown()
          }

          override fun onError(error: Int) {
            failed = true
            latch.countDown()
          }
        },
      )
      val finished = latch.await(NativeConfig.VOICE_CAPABILITY_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
      if (!finished) return CapabilityStatus.UNKNOWN
      return classifySttAvailability(sdk, true, installed, supported, failed)
    } finally {
      runCatching { recognizer.destroy() }
      executor.shutdown()
    }
  }

  private fun onMainThread(): Boolean {
    val main = runCatching { android.os.Looper.getMainLooper() }.getOrNull() ?: return false
    return android.os.Looper.myLooper() == main
  }

  private fun moreSevere(left: CapabilityStatus, right: CapabilityStatus): CapabilityStatus {
    fun rank(status: CapabilityStatus): Int = when (status) {
      CapabilityStatus.UNSUPPORTED -> 3
      CapabilityStatus.SUPPORTED_NOT_INSTALLED -> 2
      CapabilityStatus.UNKNOWN -> 1
      CapabilityStatus.AVAILABLE -> 0
    }
    return if (rank(left) >= rank(right)) left else right
  }
}
