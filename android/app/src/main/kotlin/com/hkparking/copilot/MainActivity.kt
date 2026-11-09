package com.hkparking.copilot

import android.content.Intent
import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.voice.AndroidSpeechProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
import com.hkparking.copilot.bridge.TripPlatformBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
  private var overlayHost: AndroidOverlayHost? = null

  override fun provideFlutterEngine(context: android.content.Context): FlutterEngine {
    val app = context.applicationContext
    val cache = FlutterEngineCache.getInstance()
    cache.get(ENGINE_KEY)?.let { return it }
    val engine = FlutterEngine(app)
    TripPlatformBridge.bindOnce(app, engine.dartExecutor.binaryMessenger)
    if (BuildConfig.DEBUG) bindFeedbackFixtureChannel(engine.dartExecutor.binaryMessenger)
    val fixture = BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_FEEDBACK_FIXTURE, false) == true
    engine.dartExecutor.executeDartEntrypoint(
      DartExecutor.DartEntrypoint.createDefault(),
      if (fixture) listOf(EXTRA_FEEDBACK_FIXTURE) else emptyList(),
    )
    cache.put(ENGINE_KEY, engine)
    return engine
  }

  override fun shouldDestroyEngineWithHost(): Boolean = false

  override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    val host = AndroidOverlayHost(this)
    overlayHost = host
    val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, OverlayBridge.CHANNEL_NAME)
    host.channelMounted = true
    // Step 3.5: sync may finish on a background thread; MethodChannel must be called on the main thread.
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    host.emitter = { method, args ->
      // RT-6：主線回呼落地時再檢查 channelMounted，避免 Activity onDestroy 後仍 emit。
      if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
        if (host.channelMounted) channel.invokeMethod(method, args)
      } else {
        mainHandler.post {
          if (host.channelMounted) channel.invokeMethod(method, args)
        }
      }
    }
    Bindings.todayTripsChangedEmitter = {
      if (host.channelMounted) host.emit("onTodayTripsChanged", emptyMap())
    }
    val bridge = OverlayBridge(host)
    channel.setMethodCallHandler { call, result ->
      val args = when (val raw = call.arguments) {
        is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
      }
      if (call.method == "requestRecordAudioPermission") {
        requestRecordAudio(result)
        return@setMethodCallHandler
      }
      when (call.method) {
        "getTripPermissions" -> {
          result.success(TripPermissions.allStatus(permissionHost, locationServicesOn()))
          return@setMethodCallHandler
        }
        "requestTripPermission" -> {
          requestTripPermission(args["kind"] as? String ?: "", result)
          return@setMethodCallHandler
        }
        "openAppSettings" -> {
          val opened = runCatching {
            startActivity(
              Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", packageName, null)),
            )
          }.isSuccess
          result.success(opened)
          return@setMethodCallHandler
        }
        "getVoiceFacts" -> {
          result.success(voiceFacts())
          return@setMethodCallHandler
        }
      }
      val outcome = bridge.handle(call.method, args)
      if (outcome.ok) {
        result.success(outcome.value)
      } else {
        result.error(outcome.errorCode ?: "ERROR", outcome.message, outcome.details)
      }
    }
  }

  // t1253：通用執行期權限請求（location／notifications／bluetooth／microphone）。同一時間只處理一個；
  // Activity 銷毀時回 false，唔會掛住 Dart。
  private var pendingPermission: Pair<String, MethodChannel.Result>? = null

  private val permissionHost = object : TripPermissions.Host {
    override val sdk: Int get() = android.os.Build.VERSION.SDK_INT
    override fun granted(permission: String): Boolean =
      checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    override fun rationale(permission: String): Boolean = shouldShowRequestPermissionRationale(permission)
    override fun askedBefore(kind: String): Boolean =
      getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE).getBoolean("asked_$kind", false)
  }

  private fun locationServicesOn(): Boolean = runCatching {
    getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled ?: false
  }.getOrDefault(false)

  private fun requestTripPermission(kind: String, result: MethodChannel.Result) {
    if (kind !in TripPermissions.KINDS) {
      result.error("UNKNOWN_KIND", kind, null)
      return
    }
    val perms = TripPermissions.manifestPermissions(kind, android.os.Build.VERSION.SDK_INT)
    if (perms.isEmpty() || TripPermissions.state(kind, permissionHost) == TripPermissions.GRANTED ||
      !TripPermissions.canRequest(kind, permissionHost)
    ) {
      result.success(TripPermissions.status(kind, permissionHost))
      return
    }
    if (pendingPermission != null || pendingAudioResult != null) {
      result.error("IN_PROGRESS", "permission request already showing", null)
      return
    }
    pendingPermission = kind to result
    getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE).edit().putBoolean("asked_$kind", true).apply()
    requestPermissions(perms.toTypedArray(), REQUEST_TRIP_PERMISSION)
  }

  private fun voiceFacts(): Map<String, Any?> {
    val tts = runCatching {
      packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0).isNotEmpty()
    }.getOrDefault(false)
    val recognizer = runCatching { android.speech.SpeechRecognizer.isRecognitionAvailable(this) }.getOrDefault(false)
    return mapOf(
      "ttsEngine" to tts,
      "recognizer" to recognizer,
      "recordAudio" to permissionHost.granted(android.Manifest.permission.RECORD_AUDIO),
      // debug：語音輸入係 adb 模擬（SimVoiceInput），唔用咪；release：真 SpeechRecognizer。
      "simulatedInput" to BuildConfig.DEBUG,
    )
  }

  override fun onResume() {
    super.onResume()
    AppVisibility.foreground = true
  }

  override fun onPause() {
    AppVisibility.foreground = false
    super.onPause()
  }

  // Phase 3c：全語音用嘅 RECORD_AUDIO 執行期權限（系統對話框；冇新 UI）。結果 true/false 回 Dart。
  private var pendingAudioResult: MethodChannel.Result? = null

  private fun requestRecordAudio(result: MethodChannel.Result) {
    val permission = android.Manifest.permission.RECORD_AUDIO
    if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      result.success(true)
      return
    }
    if (pendingAudioResult != null) {
      result.error("IN_PROGRESS", "RECORD_AUDIO request already showing", null)
      return
    }
    pendingAudioResult = result
    requestPermissions(arrayOf(permission), REQUEST_RECORD_AUDIO)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQUEST_TRIP_PERMISSION) {
      val pending = pendingPermission ?: return
      pendingPermission = null
      pending.second.success(TripPermissions.status(pending.first, permissionHost))
      return
    }
    if (requestCode != REQUEST_RECORD_AUDIO) return
    val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
    pendingAudioResult?.success(granted)
    pendingAudioResult = null
  }

  override fun onDestroy() {
    pendingAudioResult?.success(false)
    pendingAudioResult = null
    pendingPermission?.let { (kind, result) -> result.success(TripPermissions.status(kind, permissionHost)) }
    pendingPermission = null
    // RT-6：卸 channel；之後回呼見 channelMounted=false 就唔 emit。
    overlayHost?.channelMounted = false
    Bindings.todayTripsChangedEmitter = null
    overlayHost = null
    super.onDestroy()
  }

  private fun bindFeedbackFixtureChannel(messenger: io.flutter.plugin.common.BinaryMessenger) {
    MethodChannel(messenger, EXTRA_FEEDBACK_FIXTURE).setMethodCallHandler { call, result ->
      val prefs = getSharedPreferences(FIXTURE_PREFS, MODE_PRIVATE)
      val key = call.argument<String>("key")
      val allowed = key == NativeConfig.TODAY_TRIPS_JSON_KEY || key == NativeConfig.TODAY_TRIPS_HKT_DATE_KEY
      if (key.isNullOrEmpty() || !allowed) {
        result.error("BAD_KEY", key, null)
        return@setMethodCallHandler
      }
      when (call.method) {
        "get" -> result.success(prefs.getString(key, null))
        "put" -> {
          val value = call.argument<String>("value") ?: ""
          prefs.edit().putString(key, value).apply()
          result.success(null)
        }
        "remove" -> {
          prefs.edit().remove(key).apply()
          result.success(null)
        }
        else -> result.notImplemented()
      }
    }
  }

  companion object {
    const val ENGINE_KEY = "trip_engine"
    const val REQUEST_RECORD_AUDIO = 4107
    const val REQUEST_TRIP_PERMISSION = 4108
    const val PERMISSION_PREFS = "hkp_permission_requests"
    const val FIXTURE_PREFS = "hkp_feedback_fixture"
    const val EXTRA_FEEDBACK_FIXTURE = "hkp_feedback_fixture"
  }
}

interface OverlayHost {
  fun permissionInput(): PermissionCheckInput
  fun bluetoothConnectStatus(): String
  fun startForegroundService(entry: StartupEntry)
  val foregroundStartCount: Int
  fun readString(key: String): String?
  fun readBoolean(key: String): Boolean?
  fun writeString(key: String, value: String)
  fun writeBoolean(key: String, value: Boolean)
  val capabilityChecker: VoiceCapabilityChecker
  val tripStateMachine: TripStateMachine
  fun todayTrips(): List<Map<String, Any?>>
  fun confirmParkedCandidate(sessionId: String)
  fun submitTripReport(payloadJson: String)
  fun activeService(): FloatingBubbleService?
  val channelMounted: Boolean
  fun emit(method: String, arguments: Map<String, Any?>)
}

data class BridgeResult(
  val ok: Boolean,
  val errorCode: String? = null,
  val message: String? = null,
  val details: Map<String, Any?>? = null,
  val value: Any? = null,
)

class OverlayBridge(private val host: OverlayHost) {
  fun handle(method: String, args: Map<String, Any?>): BridgeResult {
    return when (method) {
      "startBubbleService" -> startBubbleService(args)
      "getPermissionStatus" -> ok(permissionStatus())
      "getVoiceCapability" -> ok(voiceCapability())
      "startVoiceCapture" -> startVoiceCapture(args)
      "stopVoiceCapture" -> stopVoiceCapture()
      "setPreferences" -> setPreferences(args)
      "getTripState" -> ok(host.tripStateMachine.state.name)
      "getTodayTrips", "confirmParkedCandidate", "submitTripReport" -> legacyEntryDisabled(method)
      else -> BridgeResult(ok = false, errorCode = "UNKNOWN_METHOD", message = method)
    }
  }

  fun onTripStateChanged(state: String, dataJson: String) {
    emit("onTripStateChanged", mapOf("state" to state, "dataJson" to dataJson))
  }

  fun onTodayTripsChanged() {
    emit("onTodayTripsChanged", emptyMap())
  }

  fun onTripEnded(reason: String) {
    emit("onTripEnded", mapOf("reason" to reason))
  }

  fun onMicSessionChanged(isActive: Boolean, reason: String) {
    emit("onMicSessionChanged", mapOf("isActive" to isActive, "reason" to reason))
  }

  private fun startBubbleService(args: Map<String, Any?>): BridgeResult {
    val entry = entryOf(args["entry"] as? String) ?: StartupEntry.A_2
    val gate = StartupPermissionChecker.checkBeforeStart(entry, host.permissionInput())
    if (!gate.allowedToStartFgs) {
      return BridgeResult(
        ok = false,
        errorCode = FgsBootstrapActivity.MISSING_PERMISSION,
        message = ZhHkTemplates.permissionRepair(gate.missingItems.joinToString("、")),
        details = mapOf(
          "missingItems" to gate.missingItems,
          "redirectToPermissionBanner" to gate.redirectToPermissionBanner,
        ),
      )
    }
    host.startForegroundService(entry)
    return ok(null)
  }

  private fun startVoiceCapture(args: Map<String, Any?>): BridgeResult {
    val enableHotword = args["enableHotword"] as? Boolean ?: false
    if (host.capabilityChecker.tripVoiceMode != TripVoiceMode.FULL_VOICE) {
      return ok(mapOf("upgraded" to false, "hotwordEnabled" to enableHotword))
    }
    val service = host.activeService() ?: return ok(mapOf("upgraded" to false, "hotwordEnabled" to enableHotword))
    service.reportMicReady(succeeded = true, reasonOnSuccess = FloatingBubbleService.BUBBLE_TAP)
    return ok(mapOf("upgraded" to service.isMicActive, "hotwordEnabled" to enableHotword))
  }

  private fun stopVoiceCapture(): BridgeResult {
    host.activeService()?.reportMicReady(succeeded = false)
    return ok(null)
  }

  private fun setPreferences(args: Map<String, Any?>): BridgeResult {
    val preference = args["preference"] as? String ?: host.readString(PREF_KEY) ?: "SMART_HYBRID"
    val hotwordEnabled = args["hotwordEnabled"] as? Boolean ?: true
    host.writeString(PREF_KEY, preference)
    host.writeBoolean(HOTWORD_KEY, hotwordEnabled)
    return ok(mapOf(PREF_KEY to preference, HOTWORD_KEY to hotwordEnabled))
  }

  private fun permissionStatus(): Map<String, Any?> {
    val input = host.permissionInput()
    return mapOf(
      "fineLocation" to if (input.fineLocationGranted && !input.coarseLocationOnly) GRANTED else DENIED,
      "coarseLocationOnly" to input.coarseLocationOnly,
      "postNotifications" to if (input.postNotificationsGranted) GRANTED else DENIED,
      "systemAlertWindow" to if (input.systemAlertWindowGranted) GRANTED else DENIED,
      "notificationListener" to if (input.notificationListenerGranted) GRANTED else DENIED,
      "recordAudio" to if (input.recordAudioGranted) GRANTED else DENIED,
      "bluetoothConnect" to host.bluetoothConnectStatus(),
    )
  }

  private fun voiceCapability(): Map<String, Any?> {
    val checker = host.capabilityChecker
    return mapOf(
      "stt" to checker.sttStatus.name,
      "tts" to checker.ttsStatus.name,
      "tripVoiceMode" to checker.tripVoiceMode.name,
      "downloadPrompt" to checker.downloadPromptRequested,
    )
  }

  private fun emit(method: String, arguments: Map<String, Any?>) {
    if (!host.channelMounted) return
    host.emit(method, arguments)
  }

  private fun ok(value: Any?): BridgeResult = BridgeResult(ok = true, value = value)

  private fun legacyEntryDisabled(method: String): BridgeResult = BridgeResult(
    ok = false,
    errorCode = LEGACY_ENTRY_DISABLED,
    message = "舊入口已停用",
    details = mapOf("method" to method),
  )

  private fun entryOf(name: String?): StartupEntry? {
    if (name.isNullOrEmpty()) return null
    return runCatching { StartupEntry.valueOf(name) }.getOrNull()
  }

  companion object {
    const val CHANNEL_NAME = "com.hkparking.copilot/overlay"
    const val PREF_KEY = "preference"
    const val HOTWORD_KEY = "hotwordEnabled"
    const val GRANTED = "granted"
    const val DENIED = "denied"
    const val LEGACY_ENTRY_DISABLED = "LEGACY_ENTRY_DISABLED"
  }
}

private class AndroidOverlayHost(private val activity: MainActivity) : OverlayHost {
  override var foregroundStartCount: Int = 0
    private set
  override var channelMounted: Boolean = false
  var emitter: (String, Map<String, Any?>) -> Unit = { _, _ -> }
  override val capabilityChecker: VoiceCapabilityChecker = VoiceCapabilityChecker(AndroidSpeechProbe(activity))
  override val tripStateMachine: TripStateMachine = TripStateMachine()
  override fun permissionInput(): PermissionCheckInput {
    val fine = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    return PermissionCheckInput(
      fineLocationGranted = fine,
      coarseLocationOnly = !fine && coarse,
      postNotificationsGranted = granted(android.Manifest.permission.POST_NOTIFICATIONS),
      systemAlertWindowGranted = android.provider.Settings.canDrawOverlays(activity),
      notificationListenerGranted = false,
      recordAudioGranted = granted(android.Manifest.permission.RECORD_AUDIO),
    )
  }

  override fun bluetoothConnectStatus(): String {
    return if (granted(android.Manifest.permission.BLUETOOTH_CONNECT)) OverlayBridge.GRANTED else OverlayBridge.DENIED
  }

  override fun startForegroundService(entry: StartupEntry) {
    foregroundStartCount += 1
    val intent = Intent(activity, FloatingBubbleService::class.java)
    intent.putExtra(FloatingBubbleService.EXTRA_ENTRY, entry.name)
    activity.startForegroundService(intent)
  }

  override fun readString(key: String): String? = prefs().getString(key, null)

  override fun readBoolean(key: String): Boolean? {
    if (!prefs().contains(key)) return null
    return prefs().getBoolean(key, false)
  }

  override fun writeString(key: String, value: String) {
    prefs().edit().putString(key, value).apply()
  }

  override fun writeBoolean(key: String, value: Boolean) {
    prefs().edit().putBoolean(key, value).apply()
  }

  override fun todayTrips(): List<Map<String, Any?>> = emptyList()

  override fun confirmParkedCandidate(sessionId: String) = Unit

  override fun submitTripReport(payloadJson: String) = Unit

  override fun activeService(): FloatingBubbleService? = null

  override fun emit(method: String, arguments: Map<String, Any?>) {
    if (!channelMounted) return
    emitter(method, arguments)
  }

  private fun prefs() = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

  private fun granted(permission: String): Boolean {
    return activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
  }

  companion object {
    const val PREFS = "hkparking_copilot"
  }
}


