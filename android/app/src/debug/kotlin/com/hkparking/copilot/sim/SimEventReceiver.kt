package com.hkparking.copilot.sim

import com.hkparking.copilot.di.Bindings

class SimEventReceiver(
  private val logger: (String) -> Unit = {},
  private val onEventHandled: () -> Unit = {},
) : android.content.BroadcastReceiver() {
  fun dispatchEvent(
    event: String?,
    intValue: Int? = null,
    stringValue: String? = null,
  ): Boolean {
    runCatching {
      android.util.Log.i(
        "SimEventReceiver",
        "SimEvent received: event=$event, intValue=$intValue, stringValue=$stringValue",
      )
    }
    val handled = when (event) {
      START_TRIP -> startTrip(stringValue)
      PAUSE -> {
        Bindings.locationSource.pause()
        true
      }
      RESUME -> {
        Bindings.locationSource.resume()
        true
      }
      SET_DISTANCE_M -> setDistance(event, intValue, stringValue)
      SET_SPEED_KMH -> setSpeed(event, intValue, stringValue)
      GPS_FIX_STALE -> gpsFixStale(event, intValue, stringValue)
      ARRIVE_CORE -> {
        Bindings.locationSource.injectArriveCore()
        true
      }
      LEAVE_TARGET -> leaveTarget(event, intValue, stringValue)
      ENGINE_SHUTDOWN -> engineShutdown(event, intValue, stringValue)
      DRIVER_CONFIRM -> emit(event, intValue, stringValue)
      NETWORK_FAIL -> {
        Bindings.apiTransport.setNetworkFail(true)
        Bindings.networkStatus.online = false
        emit(event, intValue, stringValue)
      }
      NETWORK_OK -> {
        Bindings.apiTransport.setNetworkFail(false)
        Bindings.apiTransport.forceRateLimit(0)
        Bindings.networkStatus.online = true
        emit(event, intValue, stringValue)
      }
      CLOCK_HKT_MIDNIGHT -> emit(event, intValue, stringValue)
      INJECT_UTTERANCE -> injectUtterance(event, stringValue)
      NO_MIC -> emit(event, intValue, stringValue)
      MIC_FAIL -> emit(event, intValue, stringValue)
      SET_VOICE_MODE -> setVoiceMode(event, stringValue)
      CAPABILITY_CHECK_TIMEOUT -> emit(event, intValue, stringValue)
      REVOKE_RECORD_AUDIO -> emit(event, intValue, stringValue)
      REVOKE_REQUIRED -> emit(event, intValue, stringValue)
      INJECT_SILENCE -> injectSilence(event, intValue, stringValue)
      INJECT_LONG_UTTERANCE -> injectLongUtterance(event, intValue, stringValue)
      INJECT_CONFIDENCE -> injectConfidence(event, intValue, stringValue)
      INJECT_TTS_ECHO -> emit(event, intValue, stringValue)
      FGS_START_TIMEOUT -> emit(event, intValue, stringValue)
      BUBBLE_TAP -> emit(event, intValue, stringValue)
      TRIE_MISS_RESOLVE_OK -> emit(event, intValue, stringValue)
      TRIE_MISS_NETWORK_FAIL -> emit(event, intValue, stringValue)
      TRIE_MISS_AMBIGUOUS -> emit(event, intValue, stringValue)
      GPS_BAD_ACCURACY -> gpsBadAccuracy(event, intValue, stringValue)
      FORCE_STALE -> armRecommend(com.hkparking.copilot.di.FixtureApiTransport.STALE_FIXTURE)
      FORCE_DEGRADED -> armRecommend(com.hkparking.copilot.di.FixtureApiTransport.DEGRADED_FIXTURE)
      FORCE_RATE_LIMIT -> forceRateLimit(event, intValue, stringValue)
      FORCE_LOCK_FULL -> {
        Bindings.apiTransport.armLockFull()
        true
      }
      FORCE_LOW_WATERMARK -> armRecommend(com.hkparking.copilot.di.FixtureApiTransport.LOW_WATERMARK_FIXTURE)
      FORCE_CROWD_FLAG -> armRecommend(com.hkparking.copilot.di.FixtureApiTransport.CROWD_FLAG_FIXTURE)
      else -> ignore(event)
    }
    if (handled) onEventHandled()
    return handled
  }

  override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
    if (intent?.action != ACTION_SIM_EVENT) return
    val event = intent.getStringExtra(EXTRA_EVENT)
    val raw = intent.extras?.get(EXTRA_VALUE)
    dispatchEvent(event, raw as? Int, raw as? String)
  }

  private fun startTrip(stringValue: String?): Boolean {
    val destinationName = stringValue?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_DESTINATION
    Bindings.locationSource.start()
    Bindings.voiceInput.startListening(hotword = true)
    return Bindings.lockOrStartTrip(destinationName)
  }

  private fun setDistance(event: String, intValue: Int?, stringValue: String?): Boolean {
    val meters = intValue ?: stringValue?.trim()?.toIntOrNull()
    if (meters == null || meters < 0) return ignore(event)
    Bindings.locationSource.injectDistanceMeters(meters)
    return true
  }

  private fun forceRateLimit(event: String, intValue: Int?, stringValue: String?): Boolean {
    val count = intValue ?: stringValue?.trim()?.toIntOrNull() ?: 1
    if (count <= 0) return ignore(event)
    Bindings.apiTransport.forceRateLimit(count)
    return true
  }

  private fun setSpeed(event: String, intValue: Int?, stringValue: String?): Boolean {
    val kmh = intValue?.toDouble() ?: stringValue?.trim()?.toDoubleOrNull()
    if (kmh == null || kmh < 0.0) return ignore(event)
    Bindings.locationSource.injectSpeedKmh(kmh)
    return true
  }

  private fun injectUtterance(event: String, stringValue: String?): Boolean {
    val utterance = stringValue?.trim()
    if (utterance.isNullOrEmpty()) return ignore(event)
    Bindings.voiceInput.injectUtterance(utterance)
    return true
  }

  private fun gpsFixStale(event: String, intValue: Int?, stringValue: String?): Boolean {
    val seconds = optionalInt(event, intValue, stringValue)
    if (seconds.invalid) return false
    Bindings.locationSource.injectFixAgeSeconds(
      seconds.value?.toInt() ?: (com.hkparking.copilot.NativeConfig.GPS_FIX_STALE_S + 1),
    )
    return true
  }

  private fun leaveTarget(event: String, intValue: Int?, stringValue: String?): Boolean {
    val hold = optionalInt(event, intValue, stringValue)
    if (hold.invalid) return false
    Bindings.locationSource.injectLeaveTarget(
      hold.value?.toInt() ?: com.hkparking.copilot.NativeConfig.APPROACH_EXIT_HOLD_S,
    )
    return true
  }

  private fun engineShutdown(event: String, intValue: Int?, stringValue: String?): Boolean {
    if (intValue == null && stringValue.isNullOrBlank()) return emit(event, null, null)
    val flag = intValue ?: stringValue?.trim()?.toIntOrNull()
    if (flag != 0 && flag != 1) return ignore(event)
    return emit(event, flag, null)
  }

  private fun setVoiceMode(event: String, stringValue: String?): Boolean {
    val mode = stringValue?.trim()
    if (mode != FULL_VOICE && mode != ANNOUNCE_MANUAL && mode != MANUAL_ONLY) return ignore(event)
    return emit(event, null, mode)
  }

  private fun injectSilence(event: String, intValue: Int?, stringValue: String?): Boolean {
    val seconds = optionalDouble(event, intValue, stringValue)
    if (seconds.invalid) return false
    return emit(event, intValue, seconds.value?.toString() ?: stringValue)
  }

  private fun injectLongUtterance(event: String, intValue: Int?, stringValue: String?): Boolean {
    if (stringValue?.trim() == STT_TOKEN) return emit(event, intValue, STT_TOKEN)
    val seconds = optionalDouble(event, intValue, stringValue)
    if (seconds.invalid) return false
    return emit(event, intValue, seconds.value?.toString() ?: stringValue)
  }

  private fun injectConfidence(event: String, intValue: Int?, stringValue: String?): Boolean {
    if (intValue == null && (stringValue.isNullOrBlank() || stringValue.trim() == MISSING_TOKEN)) {
      return emit(event, null, MISSING_TOKEN)
    }
    if (intValue != null) return emit(event, intValue, null)
    if (stringValue?.trim()?.toDoubleOrNull() == null) return ignore(event)
    return emit(event, null, stringValue.trim())
  }

  private fun gpsBadAccuracy(event: String, intValue: Int?, stringValue: String?): Boolean {
    if (intValue == null && stringValue.isNullOrBlank()) return emit(event, null, null)
    val meters = intValue?.toDouble() ?: stringValue?.trim()?.toDoubleOrNull()
    if (meters == null || meters < 0.0) return ignore(event)
    return emit(event, intValue, stringValue?.trim())
  }

  private fun armRecommend(name: String): Boolean {
    Bindings.apiTransport.armRecommendFixture(name)
    return true
  }

  private fun emit(event: String, intValue: Int?, stringValue: String?): Boolean {
    Bindings.voiceInput.emitSimControl(event, intValue, stringValue)
    return true
  }

  private data class OptionalNumber(val invalid: Boolean, val value: Double?)

  private fun optionalInt(event: String, intValue: Int?, stringValue: String?): OptionalNumber {
    if (intValue == null && stringValue.isNullOrBlank()) return OptionalNumber(false, null)
    val parsed = intValue ?: stringValue?.trim()?.toIntOrNull()
    if (parsed == null || parsed < 0) {
      ignore(event)
      return OptionalNumber(true, null)
    }
    return OptionalNumber(false, parsed.toDouble())
  }

  private fun optionalDouble(event: String, intValue: Int?, stringValue: String?): OptionalNumber {
    if (intValue == null && stringValue.isNullOrBlank()) return OptionalNumber(false, null)
    val parsed = intValue?.toDouble() ?: stringValue?.trim()?.toDoubleOrNull()
    if (parsed == null || parsed < 0.0) {
      ignore(event)
      return OptionalNumber(true, null)
    }
    return OptionalNumber(false, parsed)
  }

  private fun ignore(event: String?): Boolean {
    logger("Ignored unknown or invalid sim event: $event")
    return false
  }

  companion object {
    const val ACTION_SIM_EVENT = "com.hkparking.copilot.SIM_EVENT"
    const val EXTRA_EVENT = "event"
    const val EXTRA_VALUE = "value"
    private const val START_TRIP = "START_TRIP"
    private const val PAUSE = "PAUSE"
    private const val RESUME = "RESUME"
    private const val SET_DISTANCE_M = "SET_DISTANCE_M"
    private const val SET_SPEED_KMH = "SET_SPEED_KMH"
    private const val NETWORK_FAIL = "NETWORK_FAIL"
    private const val NETWORK_OK = "NETWORK_OK"
    private const val FORCE_RATE_LIMIT = "FORCE_RATE_LIMIT"
    private const val INJECT_UTTERANCE = "INJECT_UTTERANCE"
    private const val GPS_FIX_STALE = "GPS_FIX_STALE"
    private const val ARRIVE_CORE = "ARRIVE_CORE"
    private const val LEAVE_TARGET = "LEAVE_TARGET"
    private const val ENGINE_SHUTDOWN = "ENGINE_SHUTDOWN"
    private const val DRIVER_CONFIRM = "DRIVER_CONFIRM"
    private const val CLOCK_HKT_MIDNIGHT = "CLOCK_HKT_MIDNIGHT"
    private const val NO_MIC = "NO_MIC"
    private const val MIC_FAIL = "MIC_FAIL"
    private const val SET_VOICE_MODE = "SET_VOICE_MODE"
    private const val CAPABILITY_CHECK_TIMEOUT = "CAPABILITY_CHECK_TIMEOUT"
    private const val REVOKE_RECORD_AUDIO = "REVOKE_RECORD_AUDIO"
    private const val REVOKE_REQUIRED = "REVOKE_REQUIRED"
    private const val INJECT_SILENCE = "INJECT_SILENCE"
    private const val INJECT_LONG_UTTERANCE = "INJECT_LONG_UTTERANCE"
    private const val INJECT_CONFIDENCE = "INJECT_CONFIDENCE"
    private const val INJECT_TTS_ECHO = "INJECT_TTS_ECHO"
    private const val FGS_START_TIMEOUT = "FGS_START_TIMEOUT"
    private const val BUBBLE_TAP = "BUBBLE_TAP"
    private const val TRIE_MISS_RESOLVE_OK = "TRIE_MISS_RESOLVE_OK"
    private const val TRIE_MISS_NETWORK_FAIL = "TRIE_MISS_NETWORK_FAIL"
    private const val TRIE_MISS_AMBIGUOUS = "TRIE_MISS_AMBIGUOUS"
    private const val GPS_BAD_ACCURACY = "GPS_BAD_ACCURACY"
    private const val FORCE_STALE = "FORCE_STALE"
    private const val FORCE_DEGRADED = "FORCE_DEGRADED"
    private const val FORCE_LOCK_FULL = "FORCE_LOCK_FULL"
    private const val FORCE_LOW_WATERMARK = "FORCE_LOW_WATERMARK"
    private const val FORCE_CROWD_FLAG = "FORCE_CROWD_FLAG"
    private const val FULL_VOICE = "FULL_VOICE"
    private const val ANNOUNCE_MANUAL = "ANNOUNCE_MANUAL"
    private const val MANUAL_ONLY = "MANUAL_ONLY"
    private const val STT_TOKEN = "stt"
    private const val MISSING_TOKEN = "missing"
    private const val DEFAULT_DESTINATION = "朗豪坊"

    fun register(context: android.content.Context, receiver: SimEventReceiver) {
      val filter = android.content.IntentFilter(ACTION_SIM_EVENT)
      context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
    }
  }
}
