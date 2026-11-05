package com.hkparking.copilot.sim

class SimNavigationLauncher : com.hkparking.copilot.di.NavigationPort {
  val launchedTargets = mutableListOf<String>()
  var lastUri: String? = null
    private set
  fun reset() { launchedTargets.clear(); lastUri = null }
  override fun launchGateNavigation(carparkName: String, lat: Double?, lng: Double?): Boolean {
    val query = if (lat != null && lng != null) "$lat,$lng" else carparkName
    val q = java.net.URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
    lastUri = "google.navigation:q=$q&mode=d"
    launchedTargets += carparkName
    return true
  }
}
