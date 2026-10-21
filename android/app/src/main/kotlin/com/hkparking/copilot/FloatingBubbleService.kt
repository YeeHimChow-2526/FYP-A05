package com.hkparking.copilot

import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker

data class MicSessionEvent(val isActive: Boolean, val reason: String)

class FloatingBubbleService(private val capabilityChecker: VoiceCapabilityChecker) {
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

  private var fgsStartedCallback: () -> Unit = {}

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
    emitFgsStartedImmediately: Boolean = true,
    onFgsStarted: () -> Unit = {},
  ) {
    val snapshot = capabilityChecker.recheckSilently(
      recordAudioGranted = recordAudioGranted,
      startedAtMs = startedAtMs,
      nowMs = capabilityRecheckNowMs,
      hotwordEnabled = hotwordEnabled,
      entry = entry,
    )
    check(!snapshot.blocksFgsStarted)
    check(!snapshot.downloadPrompt)
    activeFgsType = if (entry == StartupEntry.B_2) {
      VoiceCapabilityChecker.FGS_LOCATION
    } else {
      capabilityChecker.fgsType
    }
    fgsStartedCallback = onFgsStarted
    if (emitFgsStartedImmediately) reportFgsStarted(initialDistanceMeters)
  }

  fun reportFgsStarted(initialDistanceMeters: Int? = null, onFgsStarted: () -> Unit = {}) {
    isFgsRunning = true
    isCapsuleVisible = true
    if (initialDistanceMeters != null && initialDistanceMeters <= NativeConfig.TRIGGER_DISTANCE_M) {
      immediateJitTriggered = true
    }
    fgsStartedCallback()
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
    tripEndedReason = PERMISSION_REVOKED
  }

  companion object {
    const val NOTIFICATION_TAP = "NOTIFICATION_TAP"
    const val MIC_FAILED = "MIC_FAILED"
    const val LISTENING = "正在聽"
    const val RECORD_AUDIO = "RECORD_AUDIO"
    const val PERMISSION_REVOKED = "PERMISSION_REVOKED"
  }
}
