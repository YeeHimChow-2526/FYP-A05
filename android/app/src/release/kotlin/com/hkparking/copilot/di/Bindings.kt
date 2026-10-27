package com.hkparking.copilot.di

import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.trip.ApiClientSyncApi
import com.hkparking.copilot.trip.SharedPrefsPort
import com.hkparking.copilot.trip.TodayTripStore
import com.hkparking.copilot.trip.TodayTripSync
import com.hkparking.copilot.NativeConfig
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
  // Step 3.5（SC-018）：release 一律打真雲端；URL／key 由建置設定注入（android/local.properties／-P／環境變數）。
  // 未設定或唔合規 → HttpApiTransport 回 networkError（離線分支），唔 crash；release 唔准 http。
  val apiTransport: ApiTransport = HttpApiTransport(
    baseUrl = com.hkparking.copilot.BuildConfig.HKP_API_BASE_URL,
    apiKey = com.hkparking.copilot.BuildConfig.HKP_API_KEY,
    allowLocalCleartext = false,
  )
  val activeApiTransport: ApiTransport get() = apiTransport
  val networkStatus: ConnectivityNetworkStatus = ConnectivityNetworkStatus()
  val b2MissResolver: B2MissResolver = B2MissResolvers.NAME_LOCK
  private var appContext: android.content.Context? = null
  val navigation: NavigationPort = IntentNavigationPort { appContext }
  // Step 3.5（SC-018）：同 debug 對齊；release 永遠用真 Intent 導航。
  val activeNavigation: NavigationPort get() = navigation
  var todayTripStore: TodayTripStore? = null
    private set
  var todayTripSync: TodayTripSync? = null
    private set
  var todayTripsChangedEmitter: (() -> Unit)? = null

  fun attachService(service: com.hkparking.copilot.FloatingBubbleService) {
    val context = service.applicationContext
    networkStatus.context = context
    appContext = context
    locationSource.attach(service)
    locationSource.start()
    val prefs = SharedPrefsPort(
      context.getSharedPreferences(NativeConfig.TODAY_TRIPS_PREFS_NAME, android.content.Context.MODE_PRIVATE),
    )
    val store = TodayTripStore(
      prefs = prefs,
      listener = TodayTripStore.Listener { todayTripsChangedEmitter?.invoke() },
    )
    val sync = TodayTripSync(
      store = store,
      api = ApiClientSyncApi(ApiClient(apiTransport)),
      network = networkStatus,
      networkExecutor = java.util.concurrent.Executor { runnable ->
        Thread(runnable, "today-trip-sync").start()
      },
    )
    todayTripStore = store
    todayTripSync = sync
    service.todayTripStore = store
    service.todayTripSync = sync
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
    todayTripStore = null
    todayTripSync = null
  }

  fun clearPendingStart() {}
}
