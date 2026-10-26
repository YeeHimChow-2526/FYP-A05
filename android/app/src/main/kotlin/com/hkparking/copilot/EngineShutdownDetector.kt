package com.hkparking.copilot

class EngineShutdownDetector {
  enum class Signal { POWER, BT_CAR }

  private val activeSignals = linkedSetOf<Signal>()
  private var nextToken: Long = 0L
  private var activeToken: Long? = null

  val isPending: Boolean
    get() = activeToken != null && activeSignals.isNotEmpty()

  fun onDisconnected(signal: Signal): Long? {
    val wasEmpty = activeSignals.isEmpty()
    activeSignals += signal
    if (!wasEmpty) return null
    val token = ++nextToken
    activeToken = token
    return token
  }

  fun onReconnected(signal: Signal) {
    activeSignals -= signal
    if (activeSignals.isEmpty()) {
      activeToken = null
    }
  }

  fun confirm(token: Long): Boolean {
    if (token != activeToken || activeSignals.isEmpty()) return false
    activeToken = null
    return true
  }

  fun reset() {
    activeSignals.clear()
    activeToken = null
  }
}
