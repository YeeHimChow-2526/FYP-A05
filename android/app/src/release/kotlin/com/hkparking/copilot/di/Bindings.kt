package com.hkparking.copilot.di

import com.hkparking.copilot.net.ApiTransport

class ConnectivityNetworkStatus : NetworkStatusProvider {
  var context: android.content.Context? = null

  override fun isOnline(): Boolean {
    val ctx = context ?: return false
    val manager = runCatching {
      ctx.getSystemService(android.net.ConnectivityManager::class.java)
    }.getOrNull() ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }
}

object Bindings {
  val voiceInput: VoiceInput = SttVoiceInput()
  val locationSource: LocationSource = FusedLocationSource()
  val apiTransport: ApiTransport = HttpApiTransport()
  val networkStatus: ConnectivityNetworkStatus = ConnectivityNetworkStatus()

  fun attachService(service: com.hkparking.copilot.FloatingBubbleService) {
    networkStatus.context = service.applicationContext
  }

  fun detachService(service: com.hkparking.copilot.FloatingBubbleService) {
    if (networkStatus.context === service.applicationContext) {
      networkStatus.context = null
    }
  }
}
