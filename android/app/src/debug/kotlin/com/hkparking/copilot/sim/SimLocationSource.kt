package com.hkparking.copilot.sim

import com.hkparking.copilot.di.LocationSource

// SIM: debug source set only
class SimLocationSource : LocationSource {
  var distanceMeters: Int = 0
    private set
  var speedKmh: Double = 0.0
    private set
  var isPaused: Boolean = false
    private set
  var fixAgeSeconds: Int = 0
    private set
  var lowSpeedHoldS: Int = 0
    private set
  var exitHoldS: Int = 0
    private set

  private var listener: ((Int, Double, Int) -> Unit)? = null
  private var motionListener: ((Int, Double, Int, Int, Int) -> Unit)? = null
  private var started = false
  private var hasSample = false

  override fun setLocationListener(listener: ((Int, Double, Int) -> Unit)?) {
    this.listener = listener
  }

  override fun setMotionListener(
    listener: ((distanceMeters: Int, speedKmh: Double, fixAgeSeconds: Int, lowSpeedHoldS: Int, exitHoldS: Int) -> Unit)?,
  ) {
    motionListener = listener
  }

  override fun start() {
    started = true
    if (hasSample) dispatch()
  }

  override fun stop() {
    started = false
  }

  fun injectDistanceMeters(meters: Int) {
    distanceMeters = meters
    hasSample = true
    if (!started || isPaused) return
    listener?.invoke(distanceMeters, speedKmh, fixAgeSeconds)
  }

  fun injectSpeedKmh(kmh: Double) {
    speedKmh = kmh
    dispatch()
  }

  fun injectFixAgeSeconds(seconds: Int) {
    fixAgeSeconds = seconds
    val stale = seconds > com.hkparking.copilot.NativeConfig.GPS_FIX_STALE_S
    if (!stale) return
    if (distanceMeters == 0 || distanceMeters > com.hkparking.copilot.NativeConfig.APPROACH_RADIUS_M) {
      distanceMeters = com.hkparking.copilot.NativeConfig.APPROACH_RADIUS_M
    }
    if (speedKmh >= com.hkparking.copilot.NativeConfig.DEAD_RECKONING_SPEED_KMH) {
      speedKmh = (com.hkparking.copilot.NativeConfig.DEAD_RECKONING_SPEED_KMH - 1).toDouble()
    }
    dispatch()
  }

  fun injectArriveCore() {
    distanceMeters = com.hkparking.copilot.NativeConfig.APPROACH_CORE_RADIUS_M
    speedKmh = (com.hkparking.copilot.NativeConfig.APPROACH_SPEED_THRESHOLD_KMH - 1).toDouble().coerceAtLeast(0.0)
    lowSpeedHoldS = com.hkparking.copilot.NativeConfig.APPROACH_CONSECUTIVE_S
    exitHoldS = 0
    dispatchMotion()
  }

  fun injectLeaveTarget(holdS: Int) {
    distanceMeters = com.hkparking.copilot.NativeConfig.APPROACH_EXIT_DISTANCE_M + 1
    exitHoldS = holdS
    dispatchMotion()
  }

  fun pause() {
    isPaused = true
  }

  fun resume() {
    isPaused = false
  }

  fun resetDistance() {
    distanceMeters = 0
    speedKmh = 0.0
    fixAgeSeconds = 0
    lowSpeedHoldS = 0
    exitHoldS = 0
    hasSample = false
  }

  fun reset() {
    resetDistance()
    isPaused = false
    started = false
    listener = null
    motionListener = null
  }

  private fun dispatch() {
    if (!started || isPaused) return
    listener?.invoke(distanceMeters, speedKmh, fixAgeSeconds)
    dispatchMotion()
  }

  private fun dispatchMotion() {
    if (!started || isPaused) return
    motionListener?.invoke(distanceMeters, speedKmh, fixAgeSeconds, lowSpeedHoldS, exitHoldS)
  }
}
