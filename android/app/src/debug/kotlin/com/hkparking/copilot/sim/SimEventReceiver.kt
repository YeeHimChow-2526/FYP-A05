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
      NETWORK_FAIL -> {
        Bindings.apiTransport.setNetworkFail(true)
        true
      }
      NETWORK_OK -> {
        Bindings.apiTransport.setNetworkFail(false)
        Bindings.apiTransport.forceRateLimit(0)
        true
      }
      FORCE_RATE_LIMIT -> forceRateLimit(event, intValue, stringValue)
      INJECT_UTTERANCE -> injectUtterance(event, stringValue)
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
    Bindings.locationSource.start()
    Bindings.voiceInput.startListening(hotword = true)
    val utterance = stringValue?.takeIf { it.isNotBlank() } ?: "泊車助手，去朗豪坊"
    Bindings.voiceInput.injectUtterance(utterance)
    return true
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

    fun register(context: android.content.Context, receiver: SimEventReceiver) {
      val filter = android.content.IntentFilter(ACTION_SIM_EVENT)
      context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
    }
  }
}
