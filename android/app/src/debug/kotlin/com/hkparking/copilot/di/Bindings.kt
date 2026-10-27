package com.hkparking.copilot.di

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.StartupEntry
import com.hkparking.copilot.share.B2MissResolver
import com.hkparking.copilot.share.B2MissResolvers
import com.hkparking.copilot.sim.FixtureApiTransport
import com.hkparking.copilot.sim.FixtureNetworkStatus
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.sim.SimJoystickOverlay
import com.hkparking.copilot.sim.SimLocationSource
import com.hkparking.copilot.sim.SimNavigationLauncher
import com.hkparking.copilot.sim.SimVoiceInput
import com.hkparking.copilot.voice.CapabilitySnapshot
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.PendingQuestion
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.trip.ApiClientSyncApi
import com.hkparking.copilot.trip.MemoryPrefsPort
import com.hkparking.copilot.trip.TodayTripStore
import com.hkparking.copilot.trip.TodayTripSync
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.voice.VoiceCapabilityChecker

fun interface FgsStarter {
  fun start(context: android.content.Context?): Boolean
}

object Bindings {
  val voiceInput: SimVoiceInput = SimVoiceInput()
  val locationSource: SimLocationSource = SimLocationSource()
  val apiTransport: FixtureApiTransport = FixtureApiTransport()
  // Step 3.5（SC-018／RT-2）：debug 預設用 fixture；建置時 HKP_USE_REAL_API=true 先改打真 HTTP。
  // 冇 runtime 產品開關；但 JVM 單測（attachService baseContext==null／clearPorts）強制重置為 false，
  // 避免本機 local.properties 殘留 HKP_USE_REAL_*=true 污染 testDebugUnitTest。
  @Volatile var useRealApi: Boolean = com.hkparking.copilot.BuildConfig.HKP_USE_REAL_API
    private set
  val realApiTransport: HttpApiTransport by lazy {
    HttpApiTransport(
      baseUrl = com.hkparking.copilot.BuildConfig.HKP_API_BASE_URL,
      apiKey = com.hkparking.copilot.BuildConfig.HKP_API_KEY,
      allowLocalCleartext = true,
    )
  }
  val activeApiTransport: ApiTransport get() = if (useRealApi) realApiTransport else apiTransport
  val networkStatus: FixtureNetworkStatus = FixtureNetworkStatus()
  val navigation: SimNavigationLauncher = SimNavigationLauncher()
  // Step 3.5（SC-018／RT-2）：debug 預設 SimNavigationLauncher；HKP_USE_REAL_NAVIGATION=true 先用 IntentNavigationPort。
  // 同樣喺 JVM 單測環境強制重置，唔跟 local.properties。
  @Volatile var useRealNavigation: Boolean = com.hkparking.copilot.BuildConfig.HKP_USE_REAL_NAVIGATION
    private set
  val realNavigation: IntentNavigationPort by lazy {
    IntentNavigationPort { runCatching { activeService?.applicationContext }.getOrNull() }
  }
  internal fun selectNavigation(useReal: Boolean): NavigationPort = if (useReal) realNavigation else navigation
  val activeNavigation: NavigationPort get() = selectNavigation(useRealNavigation)

  /** 測試專用：模擬 BuildConfig 開咗真通道，之後 attachService／clearPorts 應強制拉返 false。 */
  internal fun simulateBuildConfigRealSwitchesForTest(api: Boolean, navigation: Boolean) {
    useRealApi = api
    useRealNavigation = navigation
  }
  val b2MissResolver: B2MissResolver = B2MissResolvers.AWAIT_EVENT
  var activeReceiver: SimEventReceiver? = null
  var pendingStartDestination: String? = null
  var fgsStarter: FgsStarter = FgsStarter { context ->
    if (context == null) return@FgsStarter false
    val intent = android.content.Intent(context, com.hkparking.copilot.FloatingBubbleService::class.java)
    intent.putExtra(com.hkparking.copilot.FloatingBubbleService.EXTRA_ENTRY, StartupEntry.A_2.name)
    context.startForegroundService(intent)
    true
  }
  internal var activeService: com.hkparking.copilot.FloatingBubbleService? = null
    private set
  private var receiverRegistered = false
  var capabilityCheckTimedOut: Boolean = false
    private set
  var todayTripsCleared: Boolean = false
    private set
  var pendingDriverConfirm: Boolean = false
    private set
  var driverConfirmSent: Boolean = false
    private set
  var onSiteRejectedCue: String? = null
    private set
  private var pendingConfirmSessionId: String? = null
  private var networkFailed: Boolean = false
  var todayTripStore: TodayTripStore? = null
    private set
  var todayTripSync: TodayTripSync? = null
    private set
  var todayTripsChangedEmitter: (() -> Unit)? = null

  fun attachService(service: com.hkparking.copilot.FloatingBubbleService) {
    // RT-2：JVM 單元測試（Robolectric 以外、service.baseContext == null）強制 fixture／sim，
    // 唔受本機 local.properties HKP_USE_REAL_* 污染；真機／模擬器則跟返 BuildConfig。
    val jvmUnitTest = runCatching { service.baseContext }.getOrNull() == null
    if (jvmUnitTest) {
      useRealApi = false
      useRealNavigation = false
    } else {
      useRealApi = com.hkparking.copilot.BuildConfig.HKP_USE_REAL_API
      useRealNavigation = com.hkparking.copilot.BuildConfig.HKP_USE_REAL_NAVIGATION
    }
    activeService = service
    capabilityCheckTimedOut = false
    todayTripsCleared = false
    pendingDriverConfirm = false
    driverConfirmSent = false
    onSiteRejectedCue = null
    pendingConfirmSessionId = null
    networkFailed = false
    networkStatus.online = true
    // RT-4: fresh in-memory Prefs+Store+Sync shared by service and Bindings each attach.
    val prefs = MemoryPrefsPort()
    val store = TodayTripStore(
      prefs = prefs,
      listener = TodayTripStore.Listener { todayTripsChangedEmitter?.invoke() },
    )
    val syncApi = ApiClientSyncApi(
      ApiClient(
        transport = activeApiTransport,
        delayMs = service.delayMs,
      ),
    )
    val sync = TodayTripSync(
      store = store,
      api = syncApi,
      network = networkStatus,
      // Step 3.5: real HTTP must not block the main thread; fixture keeps inline order for sim tests.
      networkExecutor = if (useRealApi) {
        java.util.concurrent.Executor { runnable -> Thread(runnable, "today-trip-sync").start() }
      } else {
        java.util.concurrent.Executor { it.run() }
      },
    )
    todayTripStore = store
    todayTripSync = sync
    service.todayTripStore = store
    service.todayTripSync = sync
    voiceInput.setSimControlListener { event, intValue, stringValue ->
      dispatchSim(service, event, intValue, stringValue)
    }
    apiTransport.delayMs = service.delayMs
    apiTransport.setFixtureLoader { name ->
      runCatching {
        service.assets.open("mock/$name").bufferedReader().use { it.readText() }
      }.getOrNull()
    }
    if (activeReceiver == null) {
      activeReceiver = SimEventReceiver(
        onEventHandled = {
          val current = activeService
          if (current != null) current.updateCapsuleVisual()
        },
      )
    }
    if (!receiverRegistered) {
      val appContext = runCatching { service.applicationContext }.getOrNull()
      if (appContext != null && activeReceiver != null) {
        val result = runCatching { SimEventReceiver.register(appContext, activeReceiver!!) }
        if (result.isSuccess) {
          receiverRegistered = true
        } else {
          runCatching {
            android.util.Log.w(
              "SimEventReceiver",
              "register failed reason=${result.exceptionOrNull()?.javaClass?.simpleName}",
            )
          }
        }
      }
    }
    if (receiverRegistered) SimJoystickOverlay.show(service)
    if (service.isFgsRunning) {
      val name = pendingStartDestination
      if (name != null) {
        pendingStartDestination = null
        service.lockDestination(name)
      }
    }
    service.updateCapsuleVisual()
  }

  fun detachService(service: com.hkparking.copilot.FloatingBubbleService) {
    if (activeService !== service) return
    activeService = null
    voiceInput.setSimControlListener(null)
    SimJoystickOverlay.hide()
  }

  fun clearPorts() {
    // RT-2：每次清 port 都強制拉返 fixture／sim，避免上一輪測試／local.properties 殘留真通道。
    useRealApi = false
    useRealNavigation = false
    locationSource.setLocationListener(null)
    locationSource.setMotionListener(null)
    locationSource.setFixListener(null)
    locationSource.setTickListener(null)
    voiceInput.setUtteranceListener(null)
    navigation.reset()
    SimJoystickOverlay.hide()
    // RT-4: drop shared refs so next attachService is isolated; do not clearAll (T16).
    todayTripStore = null
    todayTripSync = null
  }

  fun clearPendingStart() {
    pendingStartDestination = null
  }

  fun lockOrStartTrip(
    destinationName: String,
    context: android.content.Context? = null,
  ): Boolean {
    val host = activeService
    if (host != null && host.isFgsRunning) {
      locationSource.start()
      voiceInput.startListening(hotword = true)
      host.lockDestination(destinationName)
      return host.tripStateMachine.destinationLocked &&
        host.tripStateMachine.destinationName == destinationName
    }
    locationSource.resetDistance()
    locationSource.start()
    voiceInput.startListening(hotword = true)
    pendingStartDestination = destinationName
    val started = try {
      fgsStarter.start(context)
    } catch (e: IllegalStateException) {
      pendingStartDestination = null
      runCatching {
        android.util.Log.i(
          "SimEventReceiver",
          "START_TRIP rejected reason=${e::class.java.simpleName}",
        )
      }
      return false
    }
    if (!started) {
      pendingStartDestination = null
      runCatching {
        android.util.Log.i("SimEventReceiver", "START_TRIP rejected reason=starter_false")
      }
      return false
    }
    if (host == null || activeService !== host) return true
    host.restartTripFromEntry(StartupEntry.A_2)
    if (host.isFgsRunning && host.tripStateMachine.destinationLocked) return true
    if (pendingStartDestination != null) {
      pendingStartDestination = null
      runCatching {
        android.util.Log.i("SimEventReceiver", "START_TRIP rejected reason=start_not_delivered")
      }
    }
    return false
  }

  private fun dispatchSim(
    service: com.hkparking.copilot.FloatingBubbleService,
    event: String,
    intValue: Int?,
    stringValue: String?,
  ) {
    when (event) {
      "ENGINE_SHUTDOWN" -> {
        val within = when (intValue) {
          1 -> true
          0 -> false
          else -> service.shutdownVerdict()
        }
        val sessionId = service.tripStateMachine.sessionId
        service.onEngineShutdown(within)
        pendingDriverConfirm = within && sessionId != null
        pendingConfirmSessionId = if (pendingDriverConfirm) sessionId else null
      }
      "DRIVER_CONFIRM" -> flushDriverConfirm(service)
      "NETWORK_FAIL" -> {
        networkFailed = true
        networkStatus.online = false
      }
      "NETWORK_OK" -> {
        networkFailed = false
        networkStatus.online = true
        flushDriverConfirm(service)
        val sync = service.todayTripSync ?: todayTripSync
        if (useRealApi) {
          Thread({ runCatching { sync?.flushPendingConfirms() } }, "today-trip-flush").start()
        } else {
          sync?.flushPendingConfirms()
        }
      }
      "CLOCK_HKT_MIDNIGHT" -> {
        todayTripsCleared = true
        pendingDriverConfirm = false
        pendingConfirmSessionId = null
        todayTripStore?.clearAll()
        service.todayTripStore?.clearAll()
      }
      "NO_MIC" -> {
        service.onVoiceCapabilityApplied(voiceSnapshot(TripVoiceMode.FULL_VOICE), micFgsActive = false)
        service.tripStateMachine.onPlaceClarificationNeeded()
        service.updateCapsuleVisual()
      }
      "MIC_FAIL" -> service.reportMicReady(succeeded = false)
      "SET_VOICE_MODE" -> {
        val mode = runCatching { TripVoiceMode.valueOf(stringValue ?: return) }.getOrNull() ?: return
        service.onVoiceCapabilityApplied(voiceSnapshot(mode), micFgsActive = mode == TripVoiceMode.FULL_VOICE)
      }
      "CAPABILITY_CHECK_TIMEOUT" -> {
        capabilityCheckTimedOut = true
        service.onVoiceCapabilityApplied(
          voiceSnapshot(service.tripStateMachine.tripVoiceMode, timedOut = true),
          micFgsActive = service.isMicActive,
        )
      }
      "REVOKE_RECORD_AUDIO" -> service.onRuntimePermissionRevoked(com.hkparking.copilot.FloatingBubbleService.RECORD_AUDIO)
      "REVOKE_REQUIRED" -> service.onRuntimePermissionRevoked("ACCESS_FINE_LOCATION")
      "INJECT_SILENCE" -> service.onListenSilence(stringValue?.toDoubleOrNull())
      "INJECT_LONG_UTTERANCE" -> service.onListenUtterance(
        elapsedS = if (stringValue == "stt") null else stringValue?.toDoubleOrNull(),
        sttTimedOut = stringValue == "stt",
      )
      "INJECT_CONFIDENCE" -> voiceInput.recordParse(
        CommandCodes.parse("詳細啲", ParseContext(confidence = confidenceOf(intValue, stringValue))),
      )
      "INJECT_TTS_ECHO" -> voiceInput.recordParse(
        CommandCodes.parse(
          "好",
          ParseContext(
            pendingQuestion = PendingQuestion.RECOMMENDATION_CONFIRM,
            isTtsSpeaking = true,
            confidence = NativeConfig.STT_CONFIDENCE_THRESHOLD,
          ),
        ),
      )
      "FGS_START_TIMEOUT" -> if (!service.isFgsRunning) {
        val routed = com.hkparking.copilot.FloatingBubbleService.fireBootstrapTimeout()
        service.onBootstrapTimedOut()
        runCatching {
          android.util.Log.i(
            "SimEventReceiver",
            "FGS_START_TIMEOUT route=${if (routed > 0) "activity" else "service_only"}",
          )
        }
      }
      "BUBBLE_TAP" -> service.onActiveBubbleTap()
      "CARD_TAP" -> service.floatingCard.performCardTap()
      "CARD_SWIPE" -> service.floatingCard.performSwipeDismiss()
      "CARD_NEXT" -> service.floatingCard.performManualNext()
      "CARD_CONFIRM" -> service.floatingCard.performManualConfirm()
      "CARD_CANCEL" -> service.floatingCard.performManualCancel()
      "TRIE_MISS_RESOLVE_OK" -> {
        service.onB2ResolveOk(stringValue)
      }
      "TRIE_MISS_NETWORK_FAIL" -> {
        service.onB2NetworkFail()
      }
      "TRIE_MISS_AMBIGUOUS" -> {
        service.onB2Ambiguous()
      }
      "GPS_BAD_ACCURACY" -> {
        val accuracy = intValue?.toDouble()
          ?: stringValue?.toDoubleOrNull()
          ?: (NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M + 1).toDouble()
        if (accuracy <= NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M) return
        val outcome = service.tripStateMachine.requestStart(
          entry = StartupEntry.ON_SITE,
          gpsAvailable = true,
          gpsAccuracyM = accuracy,
        )
        if (!outcome.accepted) {
          onSiteRejectedCue = outcome.spokenCue
          service.speakStartRejection(StartupEntry.ON_SITE, outcome.spokenCue)
        }
      }
    }
  }

  private fun flushDriverConfirm(service: com.hkparking.copilot.FloatingBubbleService) {
    val sessionId = pendingConfirmSessionId
    if (!pendingDriverConfirm || todayTripsCleared || sessionId == null) return
    val sync = service.todayTripSync ?: todayTripSync
    if (networkFailed) {
      // Offline DRIVER_CONFIRM: mark store CONFIRMED+pending; keep Bindings.pending for NETWORK_OK.
      if (sync != null) sync.confirm(sessionId) else service.confirmParkedCandidate(sessionId)
      return
    }
    pendingDriverConfirm = false
    driverConfirmSent = true
    if (sync != null) sync.confirm(sessionId) else service.confirmParkedCandidate(sessionId)
  }

  private fun confidenceOf(intValue: Int?, stringValue: String?): Double? {
    return when {
      stringValue == "missing" || (intValue == null && stringValue.isNullOrBlank()) -> null
      intValue != null -> intValue.toDouble()
      else -> stringValue?.toDoubleOrNull()
    }
  }

  private fun voiceSnapshot(mode: TripVoiceMode, timedOut: Boolean = false): CapabilitySnapshot {
    val fullVoice = mode == TripVoiceMode.FULL_VOICE
    return CapabilitySnapshot(
      stt = if (fullVoice) CapabilityStatus.AVAILABLE else CapabilityStatus.UNSUPPORTED,
      tts = if (mode == TripVoiceMode.MANUAL_ONLY) CapabilityStatus.UNSUPPORTED else CapabilityStatus.AVAILABLE,
      mode = mode,
      voiceInputEnabled = fullVoice,
      hotwordActive = fullVoice,
      fgsType = if (fullVoice) VoiceCapabilityChecker.FGS_LOCATION_MIC else VoiceCapabilityChecker.FGS_LOCATION,
      downloadPrompt = false,
      blocksFgsStarted = false,
      timedOut = timedOut,
      ttsUsable = mode != TripVoiceMode.MANUAL_ONLY,
    )
  }
}
