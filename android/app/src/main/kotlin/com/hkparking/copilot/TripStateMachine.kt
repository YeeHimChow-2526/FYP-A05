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
  var onStateChanged: (() -> Unit)? = null
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
  var destLat: Double? = null
    private set
  var destLng: Double? = null
    private set
  var destinationPoiId: String? = null
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
  var onAlertCueReady: ((mutedBefore: Boolean) -> Unit)? = null
  var networkDegraded: Boolean = false
    private set

  private var distanceToDestinationM: Int? = null
  private var lastApproachDistanceM: Int? = null
  private var lastApproachSpeedKmh: Double = 0.0
  private var lastApproachLowSpeedHoldS: Int = 0
  private var lastApproachFixAgeS: Int = 0
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
    this.destLat = null
    this.destLng = null
    this.destinationPoiId = null
    this.distanceToDestinationM = distanceToDestinationM
    this.treatAsOnSite = onSite
    fgsStartAccepted = true
    fgsRunning = false
    recommend = false
    jitTriggered = false
    assignStatusPip(StatusPip.NONE)
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

  fun onDestinationSet(
    distanceToDestinationM: Int? = null,
    destinationName: String? = null,
    destLat: Double? = null,
    destLng: Double? = null,
    destinationPoiId: String? = null,
  ) {
    hasDestination = true
    destinationLocked = true
    if (!destinationName.isNullOrBlank()) {
      this.destinationName = destinationName
    }
    this.destLat = destLat
    this.destLng = destLng
    this.destinationPoiId = destinationPoiId
    spokenAlerts.remove(AlertCause.PLACE_CLARIFICATION)
    if (deferredAlert == AlertCause.PLACE_CLARIFICATION) {
      deferredAlert = null
    }
    if (distanceToDestinationM != null) {
      this.distanceToDestinationM = distanceToDestinationM
    }
    if (state == TripState.NAV_TO_GATE) return
    val distance = this.distanceToDestinationM
    if (state == TripState.ALERT_BREATHING && !networkDegraded) {
      if (fgsRunning && distance != null && distance <= NativeConfig.TRIGGER_DISTANCE_M) {
        armBackgroundPrefetch(distance)
        if (state == TripState.ALERT_BREATHING) {
          enter(TripState.AMBER_STEADY)
        }
      } else {
        enter(TripState.CRUISING)
      }
      return
    }
    armBackgroundPrefetch(distance)
  }

  private fun withinTriggerForJit(distanceM: Int?): Boolean {
    return fgsRunning &&
      destinationLocked &&
      distanceM != null &&
      distanceM <= NativeConfig.TRIGGER_DISTANCE_M &&
      !jitTriggered
  }

  private fun armBackgroundPrefetch(distanceM: Int?) {
    if (state == TripState.NAV_TO_GATE) return
    if (!withinTriggerForJit(distanceM)) {
      return
    }
    jitTriggered = true
    recommend = true
    if (
      !networkDegraded &&
      state != TripState.ALERT_BREATHING &&
      state != TripState.APPROACH_MUTED &&
      state != TripState.PARKED_CONFIRMED &&
      state != TripState.NAV_TO_GATE
    ) {
      enter(TripState.AMBER_STEADY)
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
      recommend = false
      enter(TripState.CRUISING)
    }
  }

  fun onDistanceToDestination(distanceM: Int) {
    if (!fgsRunning) {
      return
    }
    if (state == TripState.EXPANDED) {
      distanceToDestinationM = distanceM
      return
    }
    if (state == TripState.APPROACH_MUTED || state == TripState.PARKED_CONFIRMED || state == TripState.NAV_TO_GATE) {
      return
    }
    distanceToDestinationM = distanceM
    armBackgroundPrefetch(distanceM)
  }

  fun onApproachSample(
    distanceM: Int,
    speedKmh: Double,
    lowSpeedHoldS: Int,
    fixAgeS: Int,
    exitHoldS: Int = 0,
  ) {
    if (!fgsRunning) {
      return
    }
    lastApproachDistanceM = distanceM
    lastApproachSpeedKmh = speedKmh
    lastApproachLowSpeedHoldS = lowSpeedHoldS
    lastApproachFixAgeS = fixAgeS
    if (state == TripState.EXPANDED) {
      return
    }
    if (
      state == TripState.APPROACH_MUTED &&
      distanceM > NativeConfig.APPROACH_EXIT_DISTANCE_M &&
      exitHoldS >= NativeConfig.APPROACH_EXIT_HOLD_S
    ) {
      if (networkDegraded) {
        enter(TripState.GREY_BREATHING)
      } else {
        enter(TripState.CRUISING)
      }
      return
    }
    val coreHit = distanceM <= NativeConfig.APPROACH_CORE_RADIUS_M &&
      speedKmh < NativeConfig.APPROACH_SPEED_THRESHOLD_KMH &&
      lowSpeedHoldS >= NativeConfig.APPROACH_CONSECUTIVE_S
    val deadReckoning = distanceM <= NativeConfig.APPROACH_RADIUS_M &&
      speedKmh < NativeConfig.DEAD_RECKONING_SPEED_KMH &&
      fixAgeS > NativeConfig.GPS_FIX_STALE_S
    if ((coreHit || deadReckoning) && state != TripState.PARKED_CONFIRMED && state != TripState.ALERT_BREATHING) {
      enter(TripState.APPROACH_MUTED)
    }
  }

  fun onNetworkFailure() {
    if (!fgsRunning) {
      return
    }
    networkDegraded = true
    recommend = false
    if (state == TripState.APPROACH_MUTED) {
      return
    }
    enter(TripState.GREY_BREATHING)
  }

  fun onRequestSuccess() {
    if (!fgsRunning || !networkDegraded) {
      return
    }
    networkDegraded = false
    if (state == TripState.NAV_TO_GATE) {
      deferredAlert = null
      recommend = false
      return
    }
    if (state == TripState.APPROACH_MUTED) {
      deferredAlert = null
      return
    }
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

  fun onCommitSucceeded() {
    if (!fgsRunning) return
    enter(TripState.NAV_TO_GATE)
  }

  fun enterExpanded() {
    if (!fgsRunning) return
    if (state == TripState.NAV_TO_GATE || state == TripState.ENDED || state == TripState.PARKED_CONFIRMED) return
    enter(TripState.EXPANDED)
  }

  fun onCardDismissed(hasRecommendation: Boolean) {
    if (!fgsRunning) return
    if (state == TripState.NAV_TO_GATE || state == TripState.ENDED || state == TripState.PARKED_CONFIRMED) return
    if (approachConditionsMet()) {
      enter(TripState.APPROACH_MUTED)
      return
    }
    if (hasRecommendation || statusPip != StatusPip.NONE) {
      enter(TripState.NEARBY_READY)
      return
    }
    enter(TripState.CRUISING)
  }

  private fun approachConditionsMet(): Boolean {
    val distance = lastApproachDistanceM ?: return false
    val coreHit = distance <= NativeConfig.APPROACH_CORE_RADIUS_M &&
      lastApproachSpeedKmh < NativeConfig.APPROACH_SPEED_THRESHOLD_KMH &&
      lastApproachLowSpeedHoldS >= NativeConfig.APPROACH_CONSECUTIVE_S
    val deadReckoning = distance <= NativeConfig.APPROACH_RADIUS_M &&
      lastApproachSpeedKmh < NativeConfig.DEAD_RECKONING_SPEED_KMH &&
      lastApproachFixAgeS > NativeConfig.GPS_FIX_STALE_S
    return coreHit || deadReckoning
  }

  fun onJitSucceeded(pip: StatusPip) {
    if (!fgsRunning || networkDegraded) {
      return
    }
    if (state == TripState.APPROACH_MUTED || state == TripState.PARKED_CONFIRMED || state == TripState.NAV_TO_GATE) {
      return
    }
    if (state == TripState.EXPANDED) {
      assignStatusPip(pip)
      recommend = false
      return
    }
    assignStatusPip(pip)
    recommend = false
    enter(TripState.NEARBY_READY)
  }

  fun onRequiredPermissionRevoked() {
    if (!inTrip()) {
      return
    }
    enter(TripState.ENDED)
    assignCapsuleLight(CapsuleLight.NONE)
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
      assignCapsuleLight(CapsuleLight.NONE)
      onTripEnded("ENGINE_SHUTDOWN")
      clearSession()
    }
  }

  fun onParkedConfirmed() {
    if (!inTrip()) {
      return
    }
    enter(TripState.PARKED_CONFIRMED)
    assignCapsuleLight(CapsuleLight.NONE)
    onTripEnded("PARKED_CONFIRMED")
    clearSession()
  }

  fun onOemKilled() {
    if (!inTrip()) {
      return
    }
    enter(TripState.ENDED)
    assignCapsuleLight(CapsuleLight.NONE)
    clearSession()
  }

  private fun raiseAlert(cause: AlertCause) {
    if (!fgsRunning) {
      return
    }
    val mutedBefore = state == TripState.APPROACH_MUTED
    if (networkDegraded) {
      deferredAlert = cause
      enter(TripState.GREY_BREATHING)
      return
    }
    enter(TripState.ALERT_BREATHING)
    if (spokenAlerts.add(cause)) {
      alertSpeakCount += 1
      lastSpokenCue = cueFor(cause)
      onAlertCueReady?.invoke(mutedBefore)
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
    assignStatusPip(StatusPip.NONE)
    destinationLocked = false
    hasDestination = false
    destinationName = null
    destLat = null
    destLng = null
    destinationPoiId = null
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

  private fun assignCapsuleLight(next: CapsuleLight) {
    if (capsuleLight == next) return
    capsuleLight = next
    onStateChanged?.invoke()
  }

  private fun assignStatusPip(next: StatusPip) {
    if (statusPip == next) return
    statusPip = next
    onStateChanged?.invoke()
  }

  private fun enter(next: TripState) {
    val nextLight = when (next) {
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
    val changed = state != next || capsuleLight != nextLight
    state = next
    capsuleLight = nextLight
    if (changed) onStateChanged?.invoke()
  }

}
