package com.hkparking.copilot.di

import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.share.B2MissResolver
import com.hkparking.copilot.share.B2MissResolvers

class ConnectivityNetworkStatus : NetworkStatusProvider {
  var context: android.content.Context? = null

  override fun isOnline(): Boolean = safeIsOnline {
    val ctx = context ?: return@safeIsOnline false
    val manager = ctx.getSystemService(android.net.ConnectivityManager::class.java)
      ?: return@safeIsOnline false
    val network = manager.activeNetwork ?: return@safeIsOnline false
    val capabilities = manager.getNetworkCapabilities(network) ?: return@safeIsOnline false
    capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }
}

object Bindings {
  val voiceInput: VoiceInput = SttVoiceInput()
  val locationSource: PlatformLocationSource = PlatformLocationSource()
  val apiTransport: ApiTransport = HttpApiTransport()
  val networkStatus: ConnectivityNetworkStatus = ConnectivityNetworkStatus()
  val b2MissResolver: B2MissResolver = B2MissResolvers.NAME_LOCK
  private var appContext: android.content.Context? = null
  val navigation: NavigationPort = IntentNavigationPort { appContext }

  fun attachService(service: com.hkparking.copilot.FloatingBubbleService) {
    val context = service.applicationContext
    networkStatus.context = context
    appContext = context
    locationSource.attach(service)
    locationSource.start()
  }

  fun detachService(service: com.hkparking.copilot.FloatingBubbleService) {
    if (networkStatus.context === service.applicationContext) {
      networkStatus.context = null
    }
    if (appContext === service.applicationContext) {
      appContext = null
    }
  }

  fun clearPorts() {
    voiceInput.setUtteranceListener(null)
    locationSource.setLocationListener(null)
    locationSource.setMotionListener(null)
    locationSource.setFixListener(null)
    locationSource.setTickListener(null)
    locationSource.stop()
    locationSource.detach()
  }

  fun clearPendingStart() {}
}
