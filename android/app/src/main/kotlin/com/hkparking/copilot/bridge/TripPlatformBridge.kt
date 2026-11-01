package com.hkparking.copilot.bridge

import com.hkparking.copilot.BridgeResult
import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.di.Bindings

interface BridgeSink {
  var channelMounted: Boolean
  fun emit(method: String, args: Map<String, Any?>)
}

interface TripPlatformHost {
  fun startTripFgs(entry: String): Boolean
  fun stopTripFgs(reason: String)
  fun applySamplingTier(tier: String)
  fun setNotificationText(text: String)
  fun speak(text: String)
  fun interruptSpeech()
  fun launchGateNavigation(label: String)
  fun voiceCapability(): Map<String, Any?>
  fun transportConfig(): Map<String, Any?>
  fun prefsLoad(): Map<String, String?>
  fun prefsPut(key: String, value: String)
  fun prefsRemove(key: String)
  fun fixturePost(path: String, body: String): Map<String, Any?>
}

object TripPlatformEvents {
  @Volatile var sink: BridgeSink? = null
  private val pending = ArrayDeque<Pair<String, Map<String, Any?>>>()

  fun isMounted(): Boolean = sink?.channelMounted == true

  fun emit(method: String, args: Map<String, Any?>) {
    val current = sink
    if (current == null || !current.channelMounted) {
      if (method == "onShareIntake") {
        pending.clear()
        pending.addLast(method to args)
      }
      return
    }
    dispatch(current, method, args)
  }

  fun enqueueShare(args: Map<String, Any?>) {
    pending.clear()
    pending.addLast("onShareIntake" to args)
  }

  fun flush() {
    val current = sink ?: return
    if (!current.channelMounted) return
    while (pending.isNotEmpty()) {
      val (method, args) = pending.removeFirst()
      dispatch(current, method, args)
    }
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

class TripPlatformBridge(private val host: TripPlatformHost) {
  fun handle(method: String, args: Map<String, Any?>): BridgeResult {
    return when (method) {
      "startTripFgs" -> BridgeResult(ok = true, value = host.startTripFgs(args["entry"] as? String ?: "A_2"))
      "stopTripFgs" -> {
        host.stopTripFgs(args["reason"] as? String ?: "")
        ok(null)
      }
      "applySamplingTier" -> {
        host.applySamplingTier(args["tier"] as? String ?: "FAR")
        ok(null)
      }
      "setNotificationText" -> {
        host.setNotificationText(args["text"] as? String ?: "")
        ok(null)
      }
      "speak" -> {
        host.speak(args["text"] as? String ?: "")
        ok(null)
      }
      "interruptSpeech" -> {
        host.interruptSpeech()
        ok(null)
      }
      "launchGateNavigation" -> {
        host.launchGateNavigation(args["label"] as? String ?: "")
        ok(null)
      }
      "getVoiceCapability" -> ok(host.voiceCapability())
      "getTransportConfig" -> ok(host.transportConfig())
      "fixturePost" -> fixturePost(args)
      "prefsLoad" -> ok(host.prefsLoad())
      "prefsPut" -> prefsMutate(args, remove = false)
      "prefsRemove" -> prefsMutate(args, remove = true)
      else -> BridgeResult(ok = false, errorCode = "UNKNOWN_METHOD", message = method)
    }
  }

  private fun fixturePost(args: Map<String, Any?>): BridgeResult {
    if (!Bindings.allowFixturePost) {
      return BridgeResult(ok = false, errorCode = "UNSUPPORTED", message = "fixturePost")
    }
    return ok(host.fixturePost(args["path"] as? String ?: "", args["body"] as? String ?: ""))
  }

  private fun prefsMutate(args: Map<String, Any?>, remove: Boolean): BridgeResult {
    val key = args["key"] as? String ?: ""
    if (key != NativeConfig.TODAY_TRIPS_JSON_KEY && key != NativeConfig.TODAY_TRIPS_HKT_DATE_KEY) {
      return BridgeResult(ok = false, errorCode = "PREF_KEY", message = key)
    }
    if (remove) host.prefsRemove(key) else host.prefsPut(key, args["value"] as? String ?: "")
    return ok(null)
  }

  private fun ok(value: Any?): BridgeResult = BridgeResult(ok = true, value = value)
}
