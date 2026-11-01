// FROZEN Step1 — NOT SoT; behavior mirror for FBS compile only; do not edit; delete after Step3 FBS removal
package com.hkparking.copilot

@Deprecated("SoT is Dart lib/core — do not edit; delete after Step3 FBS removal")
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
