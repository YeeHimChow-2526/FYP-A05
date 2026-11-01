package com.hkparking.copilot.bridge

import android.content.Intent
import com.hkparking.copilot.EngineShutdownReceiver
import com.hkparking.copilot.FloatingBubbleService
import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.SamplingTier
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.LocFix
import com.hkparking.copilot.voice.TripVoiceMode

class DartBridgeRuntime(private val service: FloatingBubbleService) : TripPlatformHost {
  companion object {
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

  fun onStartCommand(intent: Intent?): Int {
    handledStarts += 1
    service.bridgeMode = true
    active = true
    DartBridgeRuntime.active = this
    val started = service.enterForeground()
    if (!started) {
      active = false
      service.bridgeMode = false
      if (DartBridgeRuntime.active === this) DartBridgeRuntime.active = null
      return android.app.Service.START_NOT_STICKY
    }
    foregroundHeld = true
    wireLocation()
    registerEngine()
    Bindings.onBridgeStarted(service)
    emitShare(intent)
    TripPlatformEvents.emit("onNetworkChanged", mapOf("online" to Bindings.networkStatus.isOnline()))
    return android.app.Service.START_NOT_STICKY
  }

  fun onDestroy() {
    if (!active && !foregroundHeld) return
    releaseForeground(stopSelf = false)
    unwire()
  }

  override fun startTripFgs(entry: String): Boolean {
    val intent = Intent(service, FloatingBubbleService::class.java)
    intent.putExtra(FloatingBubbleService.EXTRA_ENTRY, entry)
    intent.putExtra(FloatingBubbleService.EXTRA_MODE, FloatingBubbleService.MODE_DART_BRIDGE)
    return runCatching {
      service.startForegroundService(intent)
      true
    }.getOrDefault(false)
  }

  override fun stopTripFgs(reason: String) {
    releaseForeground(stopSelf = true)
    TripPlatformEvents.emit("onFgsStopped", mapOf("reason" to reason))
    unwire()
  }

  override fun applySamplingTier(tier: String) {
    val parsed = runCatching { SamplingTier.valueOf(tier) }.getOrDefault(SamplingTier.FAR)
    Bindings.locationSource.applySamplingTier(parsed)
  }

  override fun setNotificationText(text: String) {
    notificationText = text
  }

  override fun speak(text: String) {
    service.ttsService.speak(text, TripVoiceMode.ANNOUNCE_MANUAL, isApproachMuted = false, driverAsked = true)
  }

  override fun interruptSpeech() {
    service.ttsService.interrupt()
  }

  override fun launchGateNavigation(label: String) {
    Bindings.activeNavigation.launchGateNavigation(label)
  }

  override fun voiceCapability(): Map<String, Any?> {
    val checker = service.capabilityChecker
    return mapOf(
      "stt" to checker.sttStatus.name,
      "tts" to checker.ttsStatus.name,
      "tripVoiceMode" to checker.tripVoiceMode.name,
      "timedOut" to false,
    )
  }

  override fun transportConfig(): Map<String, Any?> = mapOf(
    "useRealApi" to if (com.hkparking.copilot.BuildConfig.DEBUG) Bindings.transportUseRealApi() else true,
    "baseUrl" to com.hkparking.copilot.BuildConfig.HKP_API_BASE_URL,
    "apiKey" to com.hkparking.copilot.BuildConfig.HKP_API_KEY,
  )

  override fun prefsLoad(): Map<String, String?> {
    val prefs = prefs() ?: return emptyMap()
    return mapOf(
      NativeConfig.TODAY_TRIPS_JSON_KEY to prefs.getString(NativeConfig.TODAY_TRIPS_JSON_KEY, null),
      NativeConfig.TODAY_TRIPS_HKT_DATE_KEY to prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, null),
    )
  }

  override fun prefsPut(key: String, value: String) {
    prefs()?.edit()?.putString(key, value)?.apply()
  }

  override fun prefsRemove(key: String) {
    prefs()?.edit()?.remove(key)?.apply()
  }

  override fun fixturePost(path: String, body: String): Map<String, Any?> {
    return Bindings.fixturePostOrNull(path, body) ?: emptyMap()
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
    service.bridgeMode = false
    if (DartBridgeRuntime.active === this) DartBridgeRuntime.active = null
  }

  private fun wireLocation() {
    Bindings.locationSource.setFixListener { fix -> emitFix(fix) }
    Bindings.locationSource.setTickListener { nowMs ->
      TripPlatformEvents.emit("onTick", mapOf("nowMs" to nowMs))
    }
    runCatching { Bindings.locationSource.start() }
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
      TripPlatformEvents.enqueueShare(args)
    }
  }

  private fun unwire() {
    Bindings.locationSource.setFixListener(null)
    Bindings.locationSource.setTickListener(null)
    Bindings.onBridgeStopped()
    val receiver = engineReceiver
    if (engineRegistered && receiver != null) {
      val ctx = runCatching { service.applicationContext }.getOrNull() ?: service
      runCatching { EngineShutdownReceiver.registrar.unregister(ctx, receiver) }
    }
    engineRegistered = false
  }

  private fun prefs(): android.content.SharedPreferences? {
    return runCatching {
      service.getSharedPreferences(NativeConfig.TODAY_TRIPS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }.getOrNull()
  }
}
