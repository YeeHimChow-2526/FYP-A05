package com.hkparking.copilot.bridge

import android.content.Intent
import com.hkparking.copilot.FloatingBubbleService
import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.PermissionCheckInput
import com.hkparking.copilot.StartupPermissionChecker
import com.hkparking.copilot.di.Bindings
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

interface BridgeSink {
  var channelMounted: Boolean
  fun emit(method: String, args: Map<String, Any?>)
}

object TripPlatformEvents {
  @Volatile var sink: BridgeSink? = null
  private val pending = ArrayDeque<Pair<String, Map<String, Any?>>>()
  private val lock = Any()

  fun isMounted(): Boolean = sink?.channelMounted == true

  fun emit(method: String, args: Map<String, Any?>) {
    val current = sink
    if (current == null || !current.channelMounted) {
      enqueue(method, args)
      return
    }
    dispatch(current, method, args)
  }

  fun enqueue(method: String, args: Map<String, Any?>) {
    synchronized(lock) {
      val kept = ArrayDeque(pending.filterNot { it.first == method })
      pending.clear()
      pending.addAll(kept)
      pending.addLast(method to args)
    }
  }

  fun flush() {
    val current = sink ?: return
    if (!current.channelMounted) return
    val batch = synchronized(lock) {
      val copy = pending.toList()
      pending.clear()
      copy
    }
    for ((method, args) in batch) dispatch(current, method, args)
  }

  private fun dispatch(current: BridgeSink, method: String, args: Map<String, Any?>) {
    val posted = runCatching {
      val main = android.os.Looper.getMainLooper()
      if (main != null && android.os.Looper.myLooper() !== main) {
        android.os.Handler(main).post {
          if (current.channelMounted) current.emit(method, args)
        }
        return
      }
      current.emit(method, args)
    }
    if (posted.isFailure && current.channelMounted) current.emit(method, args)
  }
}

object TripPlatformBridge {
  const val CHANNEL = "com.hkparking.copilot/trip_platform"

  private var appContext: android.content.Context? = null
  private var bound = false
  private var pendingResult: MethodChannel.Result? = null
  private var startTimedOut = false
  private var cancelTimeout: (() -> Unit)? = null
  private val fixtureExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "fixture-post").apply { isDaemon = true }
  }

  internal var postDelayed: (Long, () -> Unit) -> (() -> Unit) = { delayMs, block ->
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val runnable = Runnable { block() }
    handler.postDelayed(runnable, delayMs)
    ({ handler.removeCallbacks(runnable) })
  }

  internal var starter: (android.content.Context, Intent) -> Unit = { context, intent ->
    context.startForegroundService(intent)
  }

  internal var startGateInput: () -> PermissionCheckInput = {
    val ctx = appContext
    if (ctx == null) {
      PermissionCheckInput(fineLocationGranted = false, postNotificationsGranted = false)
    } else {
      val fine = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
      val notes = ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
      PermissionCheckInput(fineLocationGranted = fine, postNotificationsGranted = notes, systemAlertWindowGranted = false)
    }
  }

  fun bindOnce(context: android.content.Context, messenger: BinaryMessenger) {
    appContext = context.applicationContext
    if (bound) return
    bound = true
    val channel = MethodChannel(messenger, CHANNEL)
    val sink = object : BridgeSink {
      override var channelMounted: Boolean = true
      override fun emit(method: String, args: Map<String, Any?>) {
        if (!channelMounted) return
        channel.invokeMethod(method, args)
      }
    }
    sink.channelMounted = false
    TripPlatformEvents.sink = sink
    channel.setMethodCallHandler { call, result -> dispatch(call.method, call.arguments, result) }
  }

  internal fun bindOnce(install: () -> Unit) {
    if (bound) return
    bound = true
    install()
  }

  fun isBound(): Boolean = bound

  internal var stopOrphan: () -> Unit = { DartBridgeRuntime.active?.stopTripFgs("BOOTSTRAP_TIMEOUT") }

  fun notifyForegroundReady(ok: Boolean) {
    if (pendingResult == null) {
      if (ok && startTimedOut) {
        startTimedOut = false
        stopOrphan()
      }
      return
    }
    startTimedOut = false
    completeStart(ok)
  }

  internal fun beginStartForTest(entry: String, result: MethodChannel.Result) = beginStart(entry, result)

  internal fun checkStartGateForTest(result: MethodChannel.Result) = checkStartGate(result)

  internal fun handleForTest(method: String, args: Map<String, Any?>): com.hkparking.copilot.BridgeResult {
    if (method == "fixturePost" && !Bindings.allowFixturePost) {
      return com.hkparking.copilot.BridgeResult(ok = false, errorCode = "UNSUPPORTED", message = "fixturePost")
    }
    return handleSync(method, args)
  }

  internal fun resetForTest() {
    bound = false
    pendingResult = null
    startTimedOut = false
    cancelTimeout?.invoke()
    cancelTimeout = null
    appContext = null
  }

  private fun dispatch(method: String, raw: Any?, result: MethodChannel.Result) {
    val args = when (raw) {
      is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
      else -> emptyMap()
    }
    when (method) {
      "startTripFgs" -> beginStart(args["entry"] as? String ?: "A_2", result)
      "ready" -> {
        TripPlatformEvents.sink?.channelMounted = true
        TripPlatformEvents.flush()
        result.success(null)
      }
      "checkStartGate" -> checkStartGate(result)
      "fixturePost" -> fixturePost(args["path"] as? String ?: "", args["body"] as? String ?: "", result)
      else -> {
        val outcome = handleSync(method, args)
        if (outcome.ok) result.success(outcome.value) else result.error(outcome.errorCode ?: "ERROR", outcome.message, outcome.details)
      }
    }
  }

  private fun beginStart(entry: String, result: MethodChannel.Result) {
    if (pendingResult != null) {
      result.error("FGS_PENDING", "start in progress", null)
      return
    }
    pendingResult = result
    val ctx = appContext
    if (ctx != null) {
      val intent = Intent(ctx, FloatingBubbleService::class.java)
      intent.putExtra(FloatingBubbleService.EXTRA_ENTRY, entry)
      intent.putExtra(FloatingBubbleService.EXTRA_MODE, FloatingBubbleService.MODE_DART_BRIDGE)
      val started = runCatching { starter(ctx, intent) }
      if (started.isFailure) {
        completeStart(false)
        return
      }
    }
    cancelTimeout = postDelayed(NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS.toLong()) { completeStart(false) }
  }

  private fun completeStart(ok: Boolean) {
    val result = pendingResult ?: return
    pendingResult = null
    cancelTimeout?.invoke()
    cancelTimeout = null
    if (!ok) startTimedOut = true
    result.success(ok)
  }

  private fun checkStartGate(result: MethodChannel.Result) {
    val input = startGateInput()
    val missing = mutableListOf<String>()
    if (!input.fineLocationGranted || input.coarseLocationOnly) missing += StartupPermissionChecker.FINE_LOCATION
    if (!input.postNotificationsGranted) missing += StartupPermissionChecker.NOTIFICATIONS
    if (missing.isNotEmpty()) {
      result.error(
        "MISSING_PERMISSION",
        missing.joinToString("、"),
        mapOf("missingItems" to missing),
      )
      return
    }
    result.success(null)
  }

  private fun fixturePost(path: String, body: String, result: MethodChannel.Result) {
    if (!Bindings.allowFixturePost) {
      result.error("UNSUPPORTED", "fixturePost", null)
      return
    }
    fixtureExecutor.execute {
      val payload = Bindings.fixturePostOrNull(path, body)
      val deliver = Runnable {
        if (payload == null) result.error("UNSUPPORTED", "fixturePost", null) else result.success(payload)
      }
      val main = runCatching { android.os.Looper.getMainLooper() }.getOrNull()
      if (main != null) android.os.Handler(main).post(deliver) else deliver.run()
    }
  }

  private fun handleSync(method: String, args: Map<String, Any?>): com.hkparking.copilot.BridgeResult {
    val runtime = DartBridgeRuntime.active
    return when (method) {
      "stopTripFgs" -> {
        runtime?.stopTripFgs(args["reason"] as? String ?: "")
        ok(null)
      }
      "applySamplingTier" -> {
        runtime?.applySamplingTier(args["tier"] as? String ?: "FAR")
        ok(null)
      }
      "setNotificationText" -> {
        runtime?.setNotificationText(args["text"] as? String ?: "")
        ok(null)
      }
      "speak" -> {
        runtime?.speak(args["text"] as? String ?: "")
        ok(null)
      }
      "interruptSpeech" -> {
        runtime?.interruptSpeech()
        ok(null)
      }
      "launchGateNavigation" -> {
        runtime?.launchGateNavigation(
          args["label"] as? String ?: "",
          (args["lat"] as? Number)?.toDouble(),
          (args["lng"] as? Number)?.toDouble(),
        )
        ok(null)
      }
      "getVoiceCapability" -> ok(runtime?.voiceCapability() ?: mapOf(
        "stt" to "UNKNOWN",
        "tts" to "UNKNOWN",
        "tripVoiceMode" to "MANUAL_ONLY",
        "timedOut" to false,
      ))
      "getTransportConfig" -> ok(mapOf(
        "useRealApi" to if (com.hkparking.copilot.BuildConfig.DEBUG) Bindings.transportUseRealApi() else true,
        "useRealNavigation" to if (com.hkparking.copilot.BuildConfig.DEBUG) Bindings.useRealNavigation else true,
        "baseUrl" to com.hkparking.copilot.BuildConfig.HKP_API_BASE_URL,
        "apiKey" to com.hkparking.copilot.BuildConfig.HKP_API_KEY,
      ))
      "prefsLoad" -> ok(loadPrefs())
      "prefsPut" -> mutatePrefs(args, remove = false)
      "prefsRemove" -> mutatePrefs(args, remove = true)
      else -> com.hkparking.copilot.BridgeResult(ok = false, errorCode = "UNKNOWN_METHOD", message = method)
    }
  }

  private fun loadPrefs(): Map<String, String?> {
    val prefs = prefs() ?: return emptyMap()
    return mapOf(
      NativeConfig.TODAY_TRIPS_JSON_KEY to prefs.getString(NativeConfig.TODAY_TRIPS_JSON_KEY, null),
      NativeConfig.TODAY_TRIPS_HKT_DATE_KEY to prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, null),
    )
  }

  private fun mutatePrefs(args: Map<String, Any?>, remove: Boolean): com.hkparking.copilot.BridgeResult {
    val key = args["key"] as? String ?: ""
    if (key != NativeConfig.TODAY_TRIPS_JSON_KEY && key != NativeConfig.TODAY_TRIPS_HKT_DATE_KEY) {
      return com.hkparking.copilot.BridgeResult(ok = false, errorCode = "PREF_KEY", message = key)
    }
    val prefs = prefs() ?: return ok(null)
    if (remove) prefs.edit().remove(key).apply() else prefs.edit().putString(key, args["value"] as? String ?: "").apply()
    return ok(null)
  }

  private fun prefs(): android.content.SharedPreferences? {
    val ctx = appContext ?: return null
    return ctx.getSharedPreferences(NativeConfig.TODAY_TRIPS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
  }

  private fun ok(value: Any?) = com.hkparking.copilot.BridgeResult(ok = true, value = value)
}
