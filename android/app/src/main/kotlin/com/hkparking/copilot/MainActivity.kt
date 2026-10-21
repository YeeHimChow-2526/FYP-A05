package com.hkparking.copilot

import android.content.Intent
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
  override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    val host = AndroidOverlayHost(this)
    val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, OverlayBridge.CHANNEL_NAME)
    host.channelMounted = true
    host.emitter = { method, args -> channel.invokeMethod(method, args) }
    val bridge = OverlayBridge(host)
    channel.setMethodCallHandler { call, result ->
      val args = when (val raw = call.arguments) {
        is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
      }
      val outcome = bridge.handle(call.method, args)
      if (outcome.ok) {
        result.success(outcome.value)
      } else {
        result.error(outcome.errorCode ?: "ERROR", outcome.message, outcome.details)
      }
    }
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
      "getTodayTrips" -> ok(host.todayTrips())
      "confirmParkedCandidate" -> {
        host.confirmParkedCandidate(args["sessionId"] as? String ?: "")
        ok(null)
      }
      "submitTripReport" -> {
        host.submitTripReport(args["payloadJson"] as? String ?: "")
        ok(null)
      }
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
  }
}

private class AndroidOverlayHost(private val activity: MainActivity) : OverlayHost {
  override var foregroundStartCount: Int = 0
    private set
  override var channelMounted: Boolean = false
  var emitter: (String, Map<String, Any?>) -> Unit = { _, _ -> }
  override val capabilityChecker: VoiceCapabilityChecker = VoiceCapabilityChecker(AndroidSpeechProbe())
  override val tripStateMachine: TripStateMachine = TripStateMachine()
  private val confirmed = mutableListOf<String>()
  private val reports = mutableListOf<String>()

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

  override fun confirmParkedCandidate(sessionId: String) {
    confirmed += sessionId
  }

  override fun submitTripReport(payloadJson: String) {
    reports += payloadJson
  }

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

private class AndroidSpeechProbe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = LocaleProbe()
  override fun probeTts(locale: String): LocaleProbe = LocaleProbe()
}
