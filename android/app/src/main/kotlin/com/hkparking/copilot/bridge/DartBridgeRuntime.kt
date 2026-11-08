package com.hkparking.copilot.bridge

import android.content.Intent
import com.hkparking.copilot.EngineShutdownReceiver
import com.hkparking.copilot.FloatingBubbleService
import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.SamplingTier
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.LocFix
import com.hkparking.copilot.voice.TripVoiceMode

class DartBridgeRuntime(private val service: FloatingBubbleService) {
  companion object {
    /** 非永久：App 唔見到時唔開咪。 */
    const val APP_NOT_VISIBLE_ERROR = "APP_NOT_VISIBLE"
    @Volatile var active: DartBridgeRuntime? = null
  }

  var active: Boolean = false
    private set
  var handledStarts: Int = 0
    private set
  val stopTrace = mutableListOf<String>()
  var engineReceiver: EngineShutdownReceiver? = null
    private set
  var notificationText: String = ""
    private set
  private var foregroundHeld = false
  private var engineRegistered = false
  private var wired = false

  fun onStartCommand(intent: Intent?): Int {
    handledStarts += 1
    active = true
    DartBridgeRuntime.active = this
    if (wired) {
      emitShare(intent)
      TripPlatformBridge.notifyForegroundReady(true)
      return android.app.Service.START_NOT_STICKY
    }
    val started = service.enterForeground()
    if (!started) {
      active = false
      if (DartBridgeRuntime.active === this) DartBridgeRuntime.active = null
      TripPlatformBridge.notifyForegroundReady(false)
      return android.app.Service.START_NOT_STICKY
    }
    foregroundHeld = true
    wired = true
    Bindings.onBridgeStarted(service)
    wireLocation()
    wireVoice()
    registerEngine()
    emitShare(intent)
    TripPlatformEvents.emit("onNetworkChanged", mapOf("online" to Bindings.networkStatus.isOnline()))
    TripPlatformBridge.notifyForegroundReady(true)
    return android.app.Service.START_NOT_STICKY
  }

  fun onDestroy() {
    if (!active && !foregroundHeld) return
    releaseForeground(stopSelf = false)
    unwire()
  }

  fun stopTripFgs(reason: String) {
    releaseForeground(stopSelf = true)
    TripPlatformEvents.emit("onFgsStopped", mapOf("reason" to reason))
    unwire()
  }

  fun applySamplingTier(tier: String) {
    val parsed = runCatching { SamplingTier.valueOf(tier) }.getOrDefault(SamplingTier.FAR)
    Bindings.bridgeLocationSource.applySamplingTier(parsed)
  }

  fun setNotificationText(text: String) {
    notificationText = text
    runCatching {
      val manager = service.getSystemService(android.app.NotificationManager::class.java) ?: return
      val notification = android.app.Notification.Builder(service, FloatingBubbleService.CHANNEL_ID)
        .setSmallIcon(service.applicationInfo.icon)
        .setContentTitle(FloatingBubbleService.CHANNEL_NAME)
        .setContentText(text)
        .build()
      manager.notify(FloatingBubbleService.NOTIFICATION_ID, notification)
    }
  }

  /**
   * 讀出 [text]。有 [utteranceId] 就喺 TTS 真正完結（UtteranceProgressListener onDone／onError／onStop，
   * 經 TtsService 主線回呼）先 emit `onSpeechDone{utteranceId, played}`；冇排到隊（TTS 用唔到／空字串）即時回 played=false。
   * 唔係 speak() return 就當講完。
   */
  fun speak(text: String, utteranceId: String? = null) {
    service.syncBridgeTtsAvailable()
    val queued = service.ttsService.speak(
      text,
      TripVoiceMode.ANNOUNCE_MANUAL,
      isApproachMuted = false,
      driverAsked = true,
      onFinished = if (utteranceId == null) null else { played -> emitSpeechDone(utteranceId, played) },
    )
    if (queued == null && utteranceId != null) emitSpeechDone(utteranceId, false)
  }

  internal fun emitSpeechDone(utteranceId: String, played: Boolean) {
    if (!wired) return
    TripPlatformEvents.emit("onSpeechDone", mapOf("utteranceId" to utteranceId, "played" to played))
  }

  fun interruptSpeech() {
    service.ttsService.interrupt()
  }

  fun launchGateNavigation(label: String, lat: Double? = null, lng: Double? = null) {
    Bindings.activeNavigation.launchGateNavigation(label, lat, lng)
  }

  fun voiceCapability(): Map<String, Any?> {
    val checker = service.capabilityChecker
    return mapOf(
      "stt" to checker.sttStatus.name,
      "tts" to checker.ttsStatus.name,
      "tripVoiceMode" to checker.tripVoiceMode.name,
      "timedOut" to false,
    )
  }

  fun prefsLoad(): Map<String, String?> {
    val prefs = prefs() ?: return emptyMap()
    return mapOf(
      NativeConfig.TODAY_TRIPS_JSON_KEY to prefs.getString(NativeConfig.TODAY_TRIPS_JSON_KEY, null),
      NativeConfig.TODAY_TRIPS_HKT_DATE_KEY to prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, null),
    )
  }

  fun prefsPut(key: String, value: String) {
    prefs()?.edit()?.putString(key, value)?.apply()
  }

  fun prefsRemove(key: String) {
    prefs()?.edit()?.remove(key)?.apply()
  }

  private fun releaseForeground(stopSelf: Boolean) {
    if (foregroundHeld) {
      stopTrace += "stopForeground"
      runCatching { service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
      foregroundHeld = false
    }
    if (stopSelf) {
      stopTrace += "stopSelf"
      runCatching { service.stopSelf() }
    }
    active = false
    wired = false
    if (DartBridgeRuntime.active === this) DartBridgeRuntime.active = null
  }

  /// Phase 3b：原生 STT（VoiceInput port；debug＝SimVoiceInput，release＝SttVoiceInput）認到嘅文字
  /// 用現有 CommandCodes 解析，經 trip_platform channel `onVoiceUtterance` 交 Dart 派發；行程邏輯喺 Dart。
  private fun wireVoice() {
    Bindings.voiceInput.setUtteranceListener { text, confidence -> emitUtterance(text, confidence) }
    Bindings.voiceInput.setErrorListener { code -> emitVoiceError(code) }
  }

  internal fun emitVoiceError(code: String) {
    if (!wired) return
    TripPlatformEvents.emit(
      "onVoiceError",
      mapOf("code" to code, "permanent" to (code in com.hkparking.copilot.voice.SpeechVoiceInput.PERMANENT)),
    )
  }

  internal fun emitUtterance(text: String, confidence: Double?) {
    if (!wired) return
    val parsed = com.hkparking.copilot.voice.CommandCodes.parse(
      text,
      com.hkparking.copilot.voice.ParseContext(confidence = confidence),
    )
    TripPlatformEvents.emit(
      "onVoiceUtterance",
      mapOf(
        "text" to text,
        "confidence" to confidence,
        "code" to parsed.code.name,
        "destinationName" to parsed.destinationName,
        "confirmed" to parsed.confirmed,
        "lowConfidence" to parsed.lowConfidence,
        "blockedAsSelfHearing" to parsed.blockedAsSelfHearing,
      ),
    )
  }

  /// Dart 要求開／收咪（只限 bridge 已 wire；未開程唔會聽）。
  fun startListening(hotword: Boolean): Boolean {
    if (!wired) return false
    // Android 14+ while-in-use：App 唔見到時唔開咪（亦唔升級成 microphone FGS）；交返 Dart 轉手動。
    if (!com.hkparking.copilot.AppVisibility.foreground) {
      emitVoiceError(APP_NOT_VISIBLE_ERROR)
      return false
    }
    val started = runCatching { Bindings.voiceInput.startListening(hotword) }
    if (started.isFailure) {
      emitVoiceError(if (started.exceptionOrNull() is SecurityException) com.hkparking.copilot.voice.SpeechVoiceInput.NO_PERMISSION else com.hkparking.copilot.voice.SpeechVoiceInput.CLIENT)
      return false
    }
    return true
  }

  fun stopListening() {
    Bindings.voiceInput.stopListening()
  }

  private fun unwireVoice() {
    Bindings.voiceInput.stopListening()
    Bindings.voiceInput.setErrorListener(null)
    Bindings.voiceInput.setUtteranceListener(null)
  }

  private fun wireLocation() {
    val source = Bindings.bridgeLocationSource
    source.setFixListener { fix -> emitFix(fix) }
    source.setTickListener { nowMs ->
      TripPlatformEvents.emit("onTick", mapOf("nowMs" to nowMs))
    }
    runCatching { source.start() }
  }

  private fun emitFix(fix: LocFix) {
    val speed: Any? = if (fix.speedKmh.isNaN()) null else fix.speedKmh
    TripPlatformEvents.emit(
      "onFix",
      mapOf(
        "lat" to fix.lat,
        "lng" to fix.lng,
        "accuracyM" to fix.accuracyM,
        "speedKmh" to speed,
        "timestampMs" to fix.timestampMs,
      ),
    )
  }

  private fun registerEngine() {
    unregisterEngine()
    val receiver = EngineShutdownReceiver { signal, disconnected ->
      TripPlatformEvents.emit(
        "onEngineSignal",
        mapOf("signal" to signal.name, "disconnected" to disconnected),
      )
    }
    engineReceiver = receiver
    val btGranted = runCatching {
      service.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    val ctx = runCatching { service.applicationContext }.getOrNull() ?: service
    val registered = runCatching {
      EngineShutdownReceiver.registrar.register(ctx, receiver, EngineShutdownReceiver.actionsFor(btGranted))
    }
    engineRegistered = registered.isSuccess
  }

  private fun emitShare(intent: Intent?) {
    if (intent?.hasExtra(FloatingBubbleService.EXTRA_SHARE_HAS_URL) != true) return
    val args = mapOf(
      "title" to intent.getStringExtra(FloatingBubbleService.EXTRA_SHARE_TITLE),
      "lat" to if (intent.hasExtra(FloatingBubbleService.EXTRA_SHARE_LAT)) intent.getDoubleExtra(FloatingBubbleService.EXTRA_SHARE_LAT, 0.0) else null,
      "lng" to if (intent.hasExtra(FloatingBubbleService.EXTRA_SHARE_LNG)) intent.getDoubleExtra(FloatingBubbleService.EXTRA_SHARE_LNG, 0.0) else null,
      "hasUrl" to intent.getBooleanExtra(FloatingBubbleService.EXTRA_SHARE_HAS_URL, false),
    )
    if (TripPlatformEvents.isMounted()) {
      TripPlatformEvents.emit("onShareIntake", args)
    } else {
      TripPlatformEvents.enqueue("onShareIntake", args)
    }
  }

  private fun unregisterEngine() {
    val receiver = engineReceiver
    if (engineRegistered && receiver != null) {
      val ctx = runCatching { service.applicationContext }.getOrNull() ?: service
      runCatching { EngineShutdownReceiver.registrar.unregister(ctx, receiver) }
    }
    engineRegistered = false
    engineReceiver = null
  }

  private fun unwire() {
    wired = false
    unwireVoice()
    val source = Bindings.bridgeLocationSource
    source.setFixListener(null)
    source.setTickListener(null)
    Bindings.onBridgeStopped()
    unregisterEngine()
  }

  private fun prefs(): android.content.SharedPreferences? {
    return runCatching {
      service.getSharedPreferences(NativeConfig.TODAY_TRIPS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }.getOrNull()
  }
}
