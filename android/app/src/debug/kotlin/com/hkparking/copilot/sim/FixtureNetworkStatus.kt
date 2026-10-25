package com.hkparking.copilot.sim

class FixtureNetworkStatus : com.hkparking.copilot.di.NetworkStatusProvider {
  var online: Boolean = true
  override fun isOnline(): Boolean = online
}
