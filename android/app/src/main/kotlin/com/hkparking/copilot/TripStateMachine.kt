package com.hkparking.copilot

import com.hkparking.copilot.voice.CapabilitySnapshot
import com.hkparking.copilot.voice.CommandCode
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
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
  var jitTriggered: Boolean = false
    private set
  val jitRequested: Boolean
    get() = jitTriggered
  var hasDestination: Boolean = false
    private set
  var destinationName: String? = null
    private set
  var isMicFgsActive: Boolean = false
    private set
  var voiceInputEnabled: Boolean = false
    private set
  var tripVoiceMode: TripVoiceMode = TripVoiceMode.MANUAL_ONLY
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

  fun applyVoiceCapability(checker: VoiceCapabilityChecker) {
    syncVoiceCapability(checker.currentSnapshot(timedOut = false), checker.hasConfirmed)
  }

  fun syncVoiceCapability(snapshot: CapabilitySnapshot, hasConfirmed: Boolean) {
    val mode = if (snapshot.timedOut && !hasConfirmed) {
      TripVoiceMode.MANUAL_ONLY
    } else {
      snapshot.mode
    }
    tripVoiceMode = mode
    voiceInputEnabled = snapshot.voiceInputEnabled && mode == TripVoiceMode.FULL_VOICE
  }

  fun coldTapCapsule(): StartOutcome = rejected(null)

  fun onMicFgsActive(active: Boolean) {
    isMicFgsActive = active
  }

  fun requestStart(
    entry: StartupEntry,
    commandCode: CommandCode? = null,
    destinationName: String? = null,
    gpsAvailable: Boolean = true,
    gpsAccuracyM: Double? = null,
    distanceToDestinationM: Int? = null,
  ): StartOutcome {
    val namedDestination = !destinationName.isNullOrBlank()
    val onSite = entry == StartupEntry.ON_SITE ||
      (entry == StartupEntry.B_1 && commandCode == CommandCode.ON_SITE)
    if (entry == StartupEntry.B_1) {
      val setDestination = commandCode == CommandCode.SET_DESTINATION && namedDestination
      if (commandCode == CommandCode.ON_SITE) {
        if (!onSiteGpsOk(gpsAvailable, gpsAccuracyM)) {
          return rejected(ZhHkTemplates.ON_SITE_LOCATION_MISSED)
        }
      } else if (setDestination || namedDestination) {
        // destination locked below
      } else {
        return rejected(ZhHkTemplates.B1_DESTINATION_MISSED)
      }
    }
    if (entry == StartupEntry.ON_SITE && !onSiteGpsOk(gpsAvailable, gpsAccuracyM)) {
      return rejected(ZhHkTemplates.ON_SITE_LOCATION_MISSED)
    }
    val id = sessionIdFactory()
    sessionId = id
    val locked = namedDestination || distanceToDestinationM != null
    this.destinationLocked = locked
    this.hasDestination = locked
    this.destinationName = if (namedDestination) destinationName else null
    this.distanceToDestinationM = distanceToDestinationM
    this.treatAsOnSite = onSite
    fgsStartAccepted = true
    fgsRunning = false
    recommend = false
    jitTriggered = false
    statusPip = StatusPip.NONE
    isMicFgsActive = false
    networkDegraded = false
    deferredAlert = null
    spokenAlerts.clear()
    alertSpeakCount = 0
    consecutiveHighSpeedS = 0
    accumulatedDistanceM = 0
    voiceInputEnabled = tripVoiceMode == TripVoiceMode.FULL_VOICE
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

  fun onDestinationSet(distanceToDestinationM: Int? = null, destinationName: String? = null) {
    hasDestination = true
    destinationLocked = true
    if (!destinationName.isNullOrBlank()) {
      this.destinationName = destinationName
    }
    if (distanceToDestinationM != null) {
      this.distanceToDestinationM = distanceToDestinationM
    }
    val withinTrigger = fgsRunning &&
      distanceToDestinationM != null &&
      distanceToDestinationM <= NativeConfig.TRIGGER_DISTANCE_M
    if (state == TripState.ALERT_BREATHING && !networkDegraded) {
      if (withinTrigger) {
        jitTriggered = true
        recommend = true
        enter(TripState.AMBER_STEADY)
      } else {
        enter(TripState.CRUISING)
      }
      return
    }
    if (withinTrigger && !jitTriggered) {
      jitTriggered = true
      recommend = true
      if (!networkDegraded && state != TripState.ALERT_BREATHING) {
        enter(TripState.AMBER_STEADY)
      }
    }
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
      jitTriggered = true
      recommend = true
      enter(TripState.AMBER_STEADY)
    } else {
      jitTriggered = false
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
      !jitTriggered &&
      distanceM <= NativeConfig.TRIGGER_DISTANCE_M
    ) {
      jitTriggered = true
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
      hasDestination = true
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
    jitTriggered = false
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
      TripVoiceMode.MANUAL_ONLY
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
    val cue = when (cause) {
      AlertCause.NO_DESTINATION -> ZhHkTemplates.SILENCE_DRIVE
      AlertCause.NAV_MID_CONFIDENCE -> ZhHkTemplates.ASK_DESTINATION
      AlertCause.PLACE_CLARIFICATION -> ZhHkTemplates.PLACE_CLARIFY
      AlertCause.DRIVER_ACTION_FAILED -> ZhHkTemplates.ASK_DESTINATION
    }
    return ZhHkTemplates.withVoiceReplyPrompt(cue, tripVoiceMode, isMicFgsActive)
  }

  private fun onSiteGpsOk(gpsAvailable: Boolean, horizontalAccuracyM: Double?): Boolean {
    if (!gpsAvailable || horizontalAccuracyM == null) {
      return false
    }
    return horizontalAccuracyM <= NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M
  }

  private fun inTrip(): Boolean {
    return sessionId != null || fgsStartAccepted || fgsRunning
  }

  fun clearSession() {
    sessionId = null
    fgsRunning = false
    fgsStartAccepted = false
    recommend = false
    jitTriggered = false
    statusPip = StatusPip.NONE
    destinationLocked = false
    hasDestination = false
    destinationName = null
    isMicFgsActive = false
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

}
