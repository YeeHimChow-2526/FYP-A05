package com.hkparking.copilot.di

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.StartupEntry
import com.hkparking.copilot.sim.FixtureApiTransport
import com.hkparking.copilot.sim.FixtureNetworkStatus
import com.hkparking.copilot.sim.SimEventReceiver
import com.hkparking.copilot.sim.SimJoystickOverlay
import com.hkparking.copilot.sim.SimLocationSource
import com.hkparking.copilot.sim.SimVoiceInput
import com.hkparking.copilot.voice.CapabilitySnapshot
import com.hkparking.copilot.voice.CapabilityStatus
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.PendingQuestion
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker

fun interface FgsStarter {
  fun start(context: android.content.Context?): Boolean
}

object Bindings {
  val voiceInput: SimVoiceInput = SimVoiceInput()
  val locationSource: SimLocationSource = SimLocationSource()
  val apiTransport: FixtureApiTransport = FixtureApiTransport()
  val networkStatus: FixtureNetworkStatus = FixtureNetworkStatus()
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

  fun attachService(service: com.hkparking.copilot.FloatingBubbleService) {
    activeService = service
    capabilityCheckTimedOut = false
    todayTripsCleared = false
    pendingDriverConfirm = false
    driverConfirmSent = false
    onSiteRejectedCue = null
    pendingConfirmSessionId = null
    networkFailed = false
    networkStatus.online = true
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
    locationSource.setLocationListener(null)
    locationSource.setMotionListener(null)
    voiceInput.setUtteranceListener(null)
    SimJoystickOverlay.hide()
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
          else -> service.lastDistanceMeters() <= NativeConfig.APPROACH_RADIUS_M
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
      }
      "CLOCK_HKT_MIDNIGHT" -> {
        todayTripsCleared = true
        pendingDriverConfirm = false
        pendingConfirmSessionId = null
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
      "TRIE_MISS_RESOLVE_OK" -> {
        val name = stringValue?.takeIf { it.isNotBlank() } ?: service.tripStateMachine.destinationName ?: "朗豪坊"
        service.lockDestination(name)
      }
      "TRIE_MISS_NETWORK_FAIL" -> {
        service.tripStateMachine.onNetworkFailure()
        service.updateCapsuleVisual()
      }
      "TRIE_MISS_AMBIGUOUS" -> {
        service.tripStateMachine.onPlaceClarificationNeeded()
        service.updateCapsuleVisual()
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
        if (!outcome.accepted) onSiteRejectedCue = outcome.spokenCue
      }
    }
  }

  private fun flushDriverConfirm(service: com.hkparking.copilot.FloatingBubbleService) {
    val sessionId = pendingConfirmSessionId
    if (!pendingDriverConfirm || networkFailed || todayTripsCleared || sessionId == null) return
    pendingDriverConfirm = false
    driverConfirmSent = true
    service.confirmParkedCandidate(sessionId)
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
