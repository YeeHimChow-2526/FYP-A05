package com.hkparking.copilot

import java.util.UUID

enum class StartupEntry {
  A_1,
  A_2,
  A_3,
  B_1,
  B_2,
  B_3,
  ON_SITE,
}

enum class TripState {
  PRE_FGS,
  CRUISING,
  AMBER_STEADY,
  GREY_BREATHING,
  ALERT_BREATHING,
  NEARBY_READY,
  EXPANDED,
  NAV_TO_GATE,
  APPROACH_MUTED,
  PARKED_CONFIRMED,
  ENDED,
}

enum class CapsuleLight {
  NONE,
  GREY_STEADY,
  AMBER_STEADY,
  GREY_BREATHING,
  ALERT_BREATHING,
}

enum class StatusPip {
  NONE,
  GREEN,
  YELLOW,
  RED,
}

enum class TripVoiceMode {
  FULL_VOICE,
  ANNOUNCE_MANUAL,
  MANUAL,
}

enum class AlertCause {
  NO_DESTINATION,
  NAV_MID_CONFIDENCE,
  PLACE_CLARIFICATION,
  DRIVER_ACTION_FAILED,
}

data class StartOutcome(
  val accepted: Boolean,
  val sessionId: String?,
  val spokenCue: String?,
  val recommend: Boolean,
  val startFgs: Boolean,
)

class TripStateMachine(
  private val onTripEnded: (String) -> Unit = {},
  private val sessionIdFactory: () -> String = { "sess_${UUID.randomUUID().toString().replace("-", "")}" },
) {
  var state: TripState = TripState.PRE_FGS
    private set
  var capsuleLight: CapsuleLight = CapsuleLight.NONE
    private set
  var statusPip: StatusPip = StatusPip.NONE
    private set
  var sessionId: String? = null
    private set
  var destinationLocked: Boolean = false
    private set
  var fgsRunning: Boolean = false
    private set
  var fgsStartAccepted: Boolean = false
    private set
  var recommend: Boolean = false
    private set
  var jitRequested: Boolean = false
    private set
  var voiceInputEnabled: Boolean = true
    private set
  var tripVoiceMode: TripVoiceMode = TripVoiceMode.FULL_VOICE
    private set
  var lastSpokenCue: String? = null
    private set
  var alertSpeakCount: Int = 0
    private set
  var networkDegraded: Boolean = false
    private set

  private var distanceToDestinationM: Int? = null
  private var treatAsOnSite: Boolean = false
  private var deferredAlert: AlertCause? = null
  private val spokenAlerts = mutableSetOf<AlertCause>()
  private var consecutiveHighSpeedS: Int = 0
  private var accumulatedDistanceM: Int = 0

  fun coldTapCapsule(): StartOutcome = rejected(null)

  fun requestStart(
    entry: StartupEntry,
    utterance: String? = null,
    hasDestinationName: Boolean = false,
    gpsAvailable: Boolean = false,
    horizontalAccuracyM: Double? = null,
    destinationLocked: Boolean = false,
    distanceToDestinationM: Int? = null,
  ): StartOutcome {
    val onSitePhrase = containsOnSitePhrase(utterance)
    val onSite = entry == StartupEntry.ON_SITE ||
      (entry == StartupEntry.B_1 && !hasDestinationName && onSitePhrase)
    if (entry == StartupEntry.B_1 && !hasDestinationName && !onSitePhrase) {
      return rejected(CUE_B1_DESTINATION_MISSED)
    }
    if (onSite && !onSiteGpsOk(gpsAvailable, horizontalAccuracyM)) {
      return rejected(CUE_ON_SITE_LOCATION_MISSED)
    }
    val id = sessionIdFactory()
    sessionId = id
    this.destinationLocked = hasDestinationName || destinationLocked
    this.distanceToDestinationM = distanceToDestinationM
    this.treatAsOnSite = onSite
    fgsStartAccepted = true
    fgsRunning = false
    recommend = false
    jitRequested = false
    networkDegraded = false
    deferredAlert = null
    spokenAlerts.clear()
    alertSpeakCount = 0
    consecutiveHighSpeedS = 0
    accumulatedDistanceM = 0
    voiceInputEnabled = true
    tripVoiceMode = TripVoiceMode.FULL_VOICE
    lastSpokenCue = null
    enter(TripState.PRE_FGS)
    return StartOutcome(
      accepted = true,
      sessionId = id,
      spokenCue = null,
      recommend = false,
      startFgs = true,
    )
  }

  fun onFgsStarted() {
    if (!fgsStartAccepted || sessionId == null || fgsRunning) {
      return
    }
    fgsRunning = true
    val distance = distanceToDestinationM
    val withinTrigger = destinationLocked &&
      distance != null &&
      distance <= NativeConfig.TRIGGER_DISTANCE_M
    if (treatAsOnSite || withinTrigger) {
      jitRequested = true
      recommend = true
      enter(TripState.AMBER_STEADY)
    } else {
      jitRequested = false
      recommend = false
      enter(TripState.CRUISING)
    }
  }

  fun onDistanceToDestination(distanceM: Int) {
    if (!fgsRunning) {
      return
    }
    distanceToDestinationM = distanceM
    if (
      destinationLocked &&
      !jitRequested &&
      distanceM <= NativeConfig.TRIGGER_DISTANCE_M
    ) {
      jitRequested = true
      recommend = true
      if (!networkDegraded && state != TripState.ALERT_BREATHING) {
        enter(TripState.AMBER_STEADY)
      }
    }
  }

  fun onApproachSample(
    distanceM: Int,
    speedKmh: Double,
    lowSpeedHoldS: Int,
    fixAgeS: Int,
  ) {
    if (!fgsRunning) {
      return
    }
    val coreHit = distanceM <= NativeConfig.APPROACH_CORE_RADIUS_M &&
      speedKmh < NativeConfig.APPROACH_SPEED_THRESHOLD_KMH &&
      lowSpeedHoldS >= NativeConfig.APPROACH_CONSECUTIVE_S
    val deadReckoning = distanceM <= NativeConfig.APPROACH_RADIUS_M &&
      speedKmh < NativeConfig.DEAD_RECKONING_SPEED_KMH &&
      fixAgeS > NativeConfig.GPS_FIX_STALE_S
    if ((coreHit || deadReckoning) && !networkDegraded && state != TripState.ALERT_BREATHING) {
      enter(TripState.APPROACH_MUTED)
    }
  }

  fun onNetworkFailure() {
    if (!fgsRunning) {
      return
    }
    networkDegraded = true
    recommend = false
    enter(TripState.GREY_BREATHING)
  }

  fun onRequestSuccess() {
    if (!fgsRunning || !networkDegraded) {
      return
    }
    networkDegraded = false
    val pending = deferredAlert
    deferredAlert = null
    if (pending != null) {
      raiseAlert(pending)
      return
    }
    if (jitRequested && statusPip == StatusPip.NONE) {
      enter(TripState.AMBER_STEADY)
    } else if (statusPip != StatusPip.NONE) {
      enter(TripState.NEARBY_READY)
    } else {
      enter(TripState.CRUISING)
    }
  }

  fun onDriveSample(speedKmh: Double, deltaDistanceM: Int, deltaTimeS: Int) {
    if (!fgsRunning) {
      return
    }
    if (deltaDistanceM > 0) {
      accumulatedDistanceM += deltaDistanceM
    }
    if (speedKmh > NativeConfig.SILENCE_DRIVE_SPEED_KMH) {
      if (deltaTimeS > 0) {
        consecutiveHighSpeedS += deltaTimeS
      }
    } else {
      consecutiveHighSpeedS = 0
    }
    if (destinationLocked) {
      return
    }
    val durationMet = consecutiveHighSpeedS >= NativeConfig.SILENCE_DRIVE_DURATION_S
    val distanceMet = accumulatedDistanceM >= NativeConfig.SILENCE_DRIVE_DISTANCE_M
    if (durationMet || distanceMet) {
      raiseAlert(AlertCause.NO_DESTINATION)
    }
  }

  fun onNavigationConfidence(confidence: Double) {
    if (!fgsRunning) {
      return
    }
    if (confidence < NativeConfig.NAV_CONFIDENCE_LOW_THRESHOLD) {
      return
    }
    if (confidence >= NativeConfig.NAV_CONFIDENCE_HIGH_THRESHOLD) {
      destinationLocked = true
      return
    }
    raiseAlert(AlertCause.NAV_MID_CONFIDENCE)
  }

  fun onPlaceClarificationNeeded() {
    if (!fgsRunning) {
      return
    }
    raiseAlert(AlertCause.PLACE_CLARIFICATION)
  }

  fun onDriverActionFailed(networkCause: Boolean) {
    if (!fgsRunning) {
      return
    }
    if (networkCause) {
      onNetworkFailure()
      return
    }
    if (destinationLocked) {
      return
    }
    raiseAlert(AlertCause.DRIVER_ACTION_FAILED)
  }

  fun onJitSucceeded(pip: StatusPip) {
    if (!fgsRunning || networkDegraded) {
      return
    }
    statusPip = pip
    jitRequested = false
    recommend = false
    enter(TripState.NEARBY_READY)
  }

  fun onRequiredPermissionRevoked() {
    if (!inTrip()) {
      return
    }
    enter(TripState.ENDED)
    capsuleLight = CapsuleLight.NONE
    onTripEnded("PERMISSION_REVOKED")
    clearSession()
  }

  fun onRecordAudioRevoked(ttsAvailable: Boolean) {
    if (!inTrip() || tripVoiceMode != TripVoiceMode.FULL_VOICE) {
      return
    }
    voiceInputEnabled = false
    tripVoiceMode = if (ttsAvailable) {
      TripVoiceMode.ANNOUNCE_MANUAL
    } else {
      TripVoiceMode.MANUAL
    }
  }

  fun onEngineShutdown(withinTargetOrBasementLoss: Boolean) {
    if (!inTrip()) {
      return
    }
    if (withinTargetOrBasementLoss) {
      onParkedConfirmed()
    } else {
      enter(TripState.ENDED)
      capsuleLight = CapsuleLight.NONE
      onTripEnded("ENGINE_SHUTDOWN")
      clearSession()
    }
  }

  fun onParkedConfirmed() {
    if (!inTrip()) {
      return
    }
    enter(TripState.PARKED_CONFIRMED)
    capsuleLight = CapsuleLight.NONE
    onTripEnded("PARKED_CONFIRMED")
    clearSession()
  }

  fun onOemKilled() {
    if (!inTrip()) {
      return
    }
    enter(TripState.ENDED)
    capsuleLight = CapsuleLight.NONE
    clearSession()
  }

  private fun raiseAlert(cause: AlertCause) {
    if (!fgsRunning) {
      return
    }
    if (networkDegraded) {
      deferredAlert = cause
      enter(TripState.GREY_BREATHING)
      return
    }
    enter(TripState.ALERT_BREATHING)
    if (spokenAlerts.add(cause)) {
      alertSpeakCount += 1
      lastSpokenCue = cueFor(cause)
    }
  }

  private fun cueFor(cause: AlertCause): String {
    return when (cause) {
      AlertCause.NO_DESTINATION -> CUE_NO_DESTINATION
      AlertCause.NAV_MID_CONFIDENCE -> CUE_NAV_CLARIFY
      AlertCause.PLACE_CLARIFICATION -> CUE_PLACE_CLARIFY
      AlertCause.DRIVER_ACTION_FAILED -> CUE_DRIVER_ACTION_FAILED
    }
  }

  private fun onSiteGpsOk(gpsAvailable: Boolean, horizontalAccuracyM: Double?): Boolean {
    if (!gpsAvailable || horizontalAccuracyM == null) {
      return false
    }
    return horizontalAccuracyM <= NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M
  }

  private fun containsOnSitePhrase(utterance: String?): Boolean {
    if (utterance.isNullOrBlank()) {
      return false
    }
    return ON_SITE_PHRASES.any { utterance.contains(it) }
  }

  private fun inTrip(): Boolean {
    return sessionId != null || fgsStartAccepted || fgsRunning
  }

  private fun clearSession() {
    sessionId = null
    fgsRunning = false
    fgsStartAccepted = false
    recommend = false
    jitRequested = false
    destinationLocked = false
    treatAsOnSite = false
    networkDegraded = false
    deferredAlert = null
    voiceInputEnabled = false
    distanceToDestinationM = null
  }

  private fun rejected(spokenCue: String?): StartOutcome {
    lastSpokenCue = spokenCue
    return StartOutcome(
      accepted = false,
      sessionId = null,
      spokenCue = spokenCue,
      recommend = false,
      startFgs = false,
    )
  }

  private fun enter(next: TripState) {
    state = next
    capsuleLight = when (next) {
      TripState.PRE_FGS, TripState.ENDED, TripState.PARKED_CONFIRMED -> CapsuleLight.NONE
      TripState.CRUISING,
      TripState.NEARBY_READY,
      TripState.EXPANDED,
      TripState.NAV_TO_GATE,
      TripState.APPROACH_MUTED,
      -> CapsuleLight.GREY_STEADY
      TripState.AMBER_STEADY -> CapsuleLight.AMBER_STEADY
      TripState.GREY_BREATHING -> CapsuleLight.GREY_BREATHING
      TripState.ALERT_BREATHING -> CapsuleLight.ALERT_BREATHING
    }
  }

  companion object {
    const val CUE_B1_DESTINATION_MISSED = "聽唔到目的地，請再講一次"
    const val CUE_ON_SITE_LOCATION_MISSED = "暫時搵唔到你位置，請稍後再講一次"
    const val CUE_NO_DESTINATION = "出發緊？去邊度等我睇定車位？"
    const val CUE_NAV_CLARIFY = "去邊度？請拍一拍膠囊答我"
    const val CUE_PLACE_CLARIFY = "邊一區？深水埗定旺角？"
    const val CUE_DRIVER_ACTION_FAILED = "去邊度？請拍一拍膠囊答我"
    val ON_SITE_PHRASES = listOf("附近", "呢度", "而家位置")
  }
}
