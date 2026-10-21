package com.hkparking.copilot

import com.hkparking.copilot.voice.ZhHkTemplates

enum class OperatingMode { MODE_A, MODE_B }

data class PermissionCheckInput(
  val mode: OperatingMode = OperatingMode.MODE_B,
  val fineLocationGranted: Boolean = true,
  val coarseLocationOnly: Boolean = false,
  val postNotificationsGranted: Boolean = true,
  val systemAlertWindowGranted: Boolean = true,
  val notificationListenerGranted: Boolean = true,
  val recordAudioGranted: Boolean = true,
)

data class PermissionGateResult(
  val allowedToStartFgs: Boolean,
  val missingItems: List<String>,
  val permissionDialogShown: Boolean = false,
  val activityFinished: Boolean = false,
  val repairNotificationText: String? = null,
  val redirectToPermissionBanner: Boolean = false,
)

object StartupPermissionChecker {
  const val FINE_LOCATION = "需要精確位置"
  const val NOTIFICATIONS = "通知"
  const val OVERLAY = "懸浮窗"
  const val LISTENER = "通知存取權"

  fun checkBeforeStart(entry: StartupEntry, input: PermissionCheckInput): PermissionGateResult {
    val missing = mutableListOf<String>()
    if (!input.fineLocationGranted || input.coarseLocationOnly) missing += FINE_LOCATION
    if (!input.postNotificationsGranted) missing += NOTIFICATIONS
    if (!input.systemAlertWindowGranted) missing += OVERLAY
    if (input.mode == OperatingMode.MODE_A && !input.notificationListenerGranted) missing += LISTENER
    if (missing.isEmpty()) {
      return PermissionGateResult(allowedToStartFgs = true, missingItems = emptyList())
    }
    val external = entry == StartupEntry.A_1 || entry == StartupEntry.A_3 || entry == StartupEntry.B_1 || entry == StartupEntry.B_2
    val repair = if (external && input.postNotificationsGranted) {
      ZhHkTemplates.permissionRepair(missing.joinToString("、"))
    } else {
      null
    }
    return PermissionGateResult(
      allowedToStartFgs = false,
      missingItems = missing,
      activityFinished = external,
      repairNotificationText = repair,
      redirectToPermissionBanner = !external,
    )
  }
}

class FgsBootstrapActivity(private val service: FloatingBubbleService) {
  var isFinished: Boolean = false
    private set
  var fgsStartedReceived: Boolean = false
    private set
  var bootstrapTimedOut: Boolean = false
    private set
  var failureReason: String? = null
    private set
  var lastPermissionGate: PermissionGateResult? = null
    private set
  var usedFixedSleep: Boolean = false
    private set

  fun launchFromEntry(
    entry: StartupEntry,
    permissions: PermissionCheckInput,
    hotwordEnabled: Boolean = true,
    startedAtMs: Long = 0L,
    capabilityCheckElapsedMs: Long = 0L,
    initialDistanceMeters: Int? = null,
    emitFgsStartedImmediately: Boolean = true,
  ): Boolean {
    val gate = StartupPermissionChecker.checkBeforeStart(entry, permissions)
    lastPermissionGate = gate
    if (!gate.allowedToStartFgs) {
      isFinished = gate.activityFinished
      if (gate.missingItems.isNotEmpty()) failureReason = MISSING_PERMISSION
      return false
    }
    isFinished = false
    service.startInForeground(
      entry = entry,
      recordAudioGranted = permissions.recordAudioGranted,
      hotwordEnabled = hotwordEnabled,
      startedAtMs = startedAtMs,
      capabilityRecheckNowMs = startedAtMs + capabilityCheckElapsedMs,
      initialDistanceMeters = initialDistanceMeters,
      emitFgsStartedImmediately = emitFgsStartedImmediately,
      onFgsStarted = {
        fgsStartedReceived = true
        isFinished = true
      },
    )
    return true
  }

  fun onHandshakeTick(startedAtMs: Long, nowMs: Long) {
    if (fgsStartedReceived || lastPermissionGate?.allowedToStartFgs == false) return
    if (nowMs - startedAtMs >= NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS) {
      bootstrapTimedOut = true
      failureReason = FGS_BOOTSTRAP_TIMEOUT
      isFinished = true
    }
  }

  companion object {
    const val MISSING_PERMISSION = "MISSING_PERMISSION"
    const val FGS_BOOTSTRAP_TIMEOUT = "FGS_BOOTSTRAP_TIMEOUT"
  }
}
