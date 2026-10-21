package com.hkparking.copilot

import com.hkparking.copilot.net.NetworkLightState
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker

data class MicSessionEvent(val isActive: Boolean, val reason: String)

enum class CapsuleRingLight { HIDDEN, GREY_STEADY, AMBER_STEADY, GREY_BREATHING, ALERT_BREATHING }

enum class VacancyPip { NONE, GREEN, YELLOW, RED }

enum class AlertTriggerReason {
  SILENCE_DRIVE_NO_DEST,
  MEDIUM_CONFIDENCE_NAV,
  DESTINATION_NEEDS_CLARIFICATION,
  USER_ACTION_NON_NETWORK_FAIL_NO_DEST,
}

class FloatingBubbleService(val capabilityChecker: VoiceCapabilityChecker) {
  var isFgsRunning: Boolean = false
    private set
  var isCapsuleVisible: Boolean = false
    private set
  var activeFgsType: String = VoiceCapabilityChecker.FGS_LOCATION
    private set
  var isMicActive: Boolean = false
    private set
  var isAudioRecordRunning: Boolean = false
    private set
  var isAudioRecordPausedByFocus: Boolean = false
    private set
  var persistentNotificationText: String? = null
    private set
  var immediateJitTriggered: Boolean = false
    private set
  var tripEndedReason: String? = null
    private set
  val micSessionEvents = mutableListOf<MicSessionEvent>()
  var ringLight: CapsuleRingLight = CapsuleRingLight.HIDDEN
    private set
  var vacancyPip: VacancyPip = VacancyPip.NONE
    private set
  val autoCardPopupShown: Boolean = false
  var manualCardVisible: Boolean = false
    private set
  var manualRetryRequested: Boolean = false
    private set
  var abandonedBySystemKill: Boolean = false
    private set
  val autoRestartScheduled: Boolean = false
  var hasLockedDestination: Boolean = false
    private set

  private var pendingFgsStartedCallback: (() -> Unit)? = null
  private var pendingInitialDistanceMeters: Int? = null

  fun onColdBubbleTap(): Boolean {
    if (!isFgsRunning) return false
    return false
  }

  fun startInForeground(
    entry: StartupEntry,
    recordAudioGranted: Boolean,
    hotwordEnabled: Boolean = true,
    startedAtMs: Long = 0L,
    capabilityRecheckNowMs: Long = 0L,
    initialDistanceMeters: Int? = null,
    emitFgsStartedImmediately: Boolean = false,
    onFgsStarted: () -> Unit = {},
  ) {
    capabilityChecker.recheckSilently(
      recordAudioGranted = recordAudioGranted,
      startedAtMs = startedAtMs,
      nowMs = capabilityRecheckNowMs,
      hotwordEnabled = hotwordEnabled,
      entry = entry,
    )
    activeFgsType = if (entry == StartupEntry.B_2) {
      VoiceCapabilityChecker.FGS_LOCATION
    } else {
      capabilityChecker.fgsType
    }
    pendingFgsStartedCallback = onFgsStarted
    pendingInitialDistanceMeters = initialDistanceMeters
    if (emitFgsStartedImmediately) reportFgsStarted(initialDistanceMeters, onFgsStarted)
  }

  fun reportFgsStarted(initialDistanceMeters: Int? = null, onFgsStarted: () -> Unit = {}) {
    val distance = initialDistanceMeters ?: pendingInitialDistanceMeters
    isFgsRunning = true
    isCapsuleVisible = true
    if (ringLight == CapsuleRingLight.HIDDEN) ringLight = CapsuleRingLight.GREY_STEADY
    abandonedBySystemKill = false
    if (distance != null && distance <= NativeConfig.TRIGGER_DISTANCE_M) immediateJitTriggered = true
    val saved = pendingFgsStartedCallback
    pendingFgsStartedCallback = null
    if (saved != null && saved !== onFgsStarted) saved.invoke()
    onFgsStarted()
  }

  fun reportMicReady(succeeded: Boolean, reasonOnSuccess: String = NOTIFICATION_TAP) {
    if (succeeded && capabilityChecker.tripVoiceMode == TripVoiceMode.FULL_VOICE) {
      isMicActive = true
      isAudioRecordRunning = true
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION_MIC
      persistentNotificationText = LISTENING
      micSessionEvents += MicSessionEvent(isActive = true, reason = reasonOnSuccess)
      return
    }
    if (!succeeded) {
      capabilityChecker.onMicReadyFailed()
      isMicActive = false
      isAudioRecordRunning = false
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION
      micSessionEvents += MicSessionEvent(isActive = false, reason = MIC_FAILED)
    }
  }

  fun onAudioFocusChanged(focusLostToMapsOrCall: Boolean) {
    if (!isMicActive) return
    if (focusLostToMapsOrCall) {
      isAudioRecordPausedByFocus = true
      isAudioRecordRunning = false
      return
    }
    isAudioRecordPausedByFocus = false
    isAudioRecordRunning = true
  }

  fun onRuntimePermissionRevoked(revokedPermission: String) {
    if (revokedPermission == RECORD_AUDIO) {
      capabilityChecker.onRecordAudioRevoked()
      isMicActive = false
      isAudioRecordRunning = false
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION
      return
    }
    isFgsRunning = false
    isCapsuleVisible = false
    isMicActive = false
    isAudioRecordRunning = false
    ringLight = CapsuleRingLight.HIDDEN
    vacancyPip = VacancyPip.NONE
    manualCardVisible = false
    tripEndedReason = PERMISSION_REVOKED
  }

  fun onDestinationLocked(locked: Boolean = true) {
    hasLockedDestination = locked
    if (locked && ringLight == CapsuleRingLight.ALERT_BREATHING) ringLight = CapsuleRingLight.GREY_STEADY
  }

  fun applyNetworkState(networkState: NetworkLightState, topVacancies: Int? = null) {
    if (!isFgsRunning || !isCapsuleVisible) {
      ringLight = CapsuleRingLight.HIDDEN
      vacancyPip = VacancyPip.NONE
      return
    }
    when (networkState) {
      NetworkLightState.AMBER_STEADY -> ringLight = CapsuleRingLight.AMBER_STEADY
      NetworkLightState.GREY_BREATHING -> ringLight = CapsuleRingLight.GREY_BREATHING
      NetworkLightState.NEARBY_READY -> {
        ringLight = CapsuleRingLight.GREY_STEADY
        vacancyPip = pipFor(topVacancies)
      }
      NetworkLightState.ALERT_BREATHING -> {
        if (ringLight != CapsuleRingLight.GREY_BREATHING) ringLight = CapsuleRingLight.ALERT_BREATHING
      }
    }
  }

  fun requestAlertBreathing(reason: AlertTriggerReason): Boolean {
    if (!isFgsRunning || !isCapsuleVisible) return false
    if (ringLight == CapsuleRingLight.GREY_BREATHING) return false
    if (reason == AlertTriggerReason.USER_ACTION_NON_NETWORK_FAIL_NO_DEST && hasLockedDestination) return false
    ringLight = CapsuleRingLight.ALERT_BREATHING
    return true
  }

  fun onFirstChoiceNoResponse() {
    if (ringLight != CapsuleRingLight.GREY_BREATHING) ringLight = CapsuleRingLight.GREY_STEADY
  }

  fun onActiveBubbleTap(): Boolean {
    if (!isFgsRunning || !isCapsuleVisible) return onColdBubbleTap()
    if (ringLight == CapsuleRingLight.GREY_BREATHING) manualRetryRequested = true
    if (capabilityChecker.tripVoiceMode == TripVoiceMode.FULL_VOICE) {
      if (!isMicActive) {
        activeFgsType = VoiceCapabilityChecker.FGS_LOCATION_MIC
        isMicActive = true
        isAudioRecordRunning = true
        persistentNotificationText = LISTENING
        micSessionEvents += MicSessionEvent(isActive = true, reason = BUBBLE_TAP)
      }
    } else {
      activeFgsType = VoiceCapabilityChecker.FGS_LOCATION
      isMicActive = false
      manualCardVisible = true
    }
    return true
  }

  fun onVoiceStartCommand(viaHeyGoogleColdStart: Boolean = false): Boolean {
    return viaHeyGoogleColdStart || (isFgsRunning && isMicActive)
  }

  fun onOemKilledOrTaskRemoved() {
    isFgsRunning = false
    isCapsuleVisible = false
    isMicActive = false
    isAudioRecordRunning = false
    ringLight = CapsuleRingLight.HIDDEN
    vacancyPip = VacancyPip.NONE
    manualCardVisible = false
    abandonedBySystemKill = true
    tripEndedReason = ABANDONED_OEM_KILL
  }

  private fun pipFor(topVacancies: Int?): VacancyPip {
    if (topVacancies == null || topVacancies < 0) return VacancyPip.YELLOW
    if (topVacancies <= PIP_RED_MAX) return VacancyPip.RED
    if (topVacancies <= PIP_YELLOW_MAX) return VacancyPip.YELLOW
    return VacancyPip.GREEN
  }

  companion object {
    const val NOTIFICATION_TAP = "NOTIFICATION_TAP"
    const val MIC_FAILED = "MIC_FAILED"
    const val LISTENING = "正在聽"
    const val RECORD_AUDIO = "RECORD_AUDIO"
    const val PERMISSION_REVOKED = "PERMISSION_REVOKED"
    const val BUBBLE_TAP = "BUBBLE_TAP"
    const val ABANDONED_OEM_KILL = "ABANDONED_OEM_KILL"
    private const val PIP_RED_MAX = 3
    private const val PIP_YELLOW_MAX = 10
  }
}
