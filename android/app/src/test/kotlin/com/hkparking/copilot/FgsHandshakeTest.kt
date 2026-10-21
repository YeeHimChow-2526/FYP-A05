package com.hkparking.copilot

import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FgsHandshakeTest {
  @Test
  fun preFgsNoCapsuleAndColdTapIgnored() {
    val service = service()
    assertFalse(service.isFgsRunning)
    assertFalse(service.isCapsuleVisible)
    assertFalse(service.onColdBubbleTap())
    assertFalse(service.isFgsRunning)
    assertFalse(service.isCapsuleVisible)
  }

  @Test
  fun permissionCheckBlocksCoarseLocationAndMissingPermissionsWithoutDialog() {
    val coarse = StartupPermissionChecker.checkBeforeStart(
      StartupEntry.A_1,
      PermissionCheckInput(coarseLocationOnly = true),
    )
    assertFalse(coarse.allowedToStartFgs)
    assertEquals(listOf(StartupPermissionChecker.FINE_LOCATION), coarse.missingItems)
    assertFalse(coarse.permissionDialogShown)

    val external = StartupPermissionChecker.checkBeforeStart(
      StartupEntry.B_2,
      PermissionCheckInput(systemAlertWindowGranted = false),
    )
    assertTrue(external.activityFinished)
    assertEquals(ZhHkTemplates.permissionRepair(StartupPermissionChecker.OVERLAY), external.repairNotificationText)
    val externalService = service()
    val externalActivity = FgsBootstrapActivity(externalService)
    assertFalse(externalActivity.launchFromEntry(StartupEntry.A_1, PermissionCheckInput(fineLocationGranted = false)))
    assertFalse(externalService.isFgsRunning)
    assertTrue(externalActivity.isFinished)
    assertEquals(FgsBootstrapActivity.MISSING_PERMISSION, externalActivity.failureReason)

    val noNotify = StartupPermissionChecker.checkBeforeStart(
      StartupEntry.A_1,
      PermissionCheckInput(postNotificationsGranted = false, fineLocationGranted = false),
    )
    assertTrue(noNotify.activityFinished)
    assertNull(noNotify.repairNotificationText)

    val internal = StartupPermissionChecker.checkBeforeStart(
      StartupEntry.A_2,
      PermissionCheckInput(fineLocationGranted = false),
    )
    assertTrue(internal.redirectToPermissionBanner)
    assertFalse(internal.activityFinished)
    assertNull(internal.repairNotificationText)
    val internalService = service()
    assertFalse(
      FgsBootstrapActivity(internalService).launchFromEntry(
        StartupEntry.ON_SITE,
        PermissionCheckInput(systemAlertWindowGranted = false),
      ),
    )
    assertFalse(internalService.isFgsRunning)

    val degraded = service()
    val started = FgsBootstrapActivity(degraded).launchFromEntry(
      StartupEntry.A_2,
      PermissionCheckInput(recordAudioGranted = false),
      emitFgsStartedImmediately = true,
    )
    assertTrue(started)
    assertTrue(degraded.isFgsRunning)
    assertEquals(TripVoiceMode.ANNOUNCE_MANUAL, degradedChecker(degraded).tripVoiceMode)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, degraded.activeFgsType)
  }

  @Test
  fun fgsStartedHandshakeAndTimeout() {
    val service = service()
    val activity = FgsBootstrapActivity(service)
    assertTrue(
      activity.launchFromEntry(
        StartupEntry.A_1,
        PermissionCheckInput(),
        emitFgsStartedImmediately = false,
      ),
    )
    assertFalse(activity.isFinished)
    assertFalse(activity.fgsStartedReceived)
    assertFalse(service.isCapsuleVisible)
    assertFalse(activity.usedFixedSleep)
    service.reportFgsStarted(initialDistanceMeters = NativeConfig.TRIGGER_DISTANCE_M)
    assertTrue(activity.fgsStartedReceived)
    assertTrue(activity.isFinished)
    assertTrue(service.isCapsuleVisible)
    assertTrue(service.isFgsRunning)
    assertTrue(service.immediateJitTriggered)

    val waiting = service()
    val timed = FgsBootstrapActivity(waiting)
    timed.launchFromEntry(StartupEntry.A_3, PermissionCheckInput(), startedAtMs = 0L, emitFgsStartedImmediately = false)
    timed.onHandshakeTick(0L, NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS)
    assertTrue(timed.bootstrapTimedOut)
    assertEquals(FgsBootstrapActivity.FGS_BOOTSTRAP_TIMEOUT, timed.failureReason)
    assertTrue(timed.isFinished)
    assertFalse(waiting.isCapsuleVisible)

    val slow = service()
    val slowActivity = FgsBootstrapActivity(slow)
    assertTrue(
      slowActivity.launchFromEntry(
        StartupEntry.B_1,
        PermissionCheckInput(),
        startedAtMs = 0L,
        capabilityCheckElapsedMs = NativeConfig.VOICE_CAPABILITY_CHECK_TIMEOUT_MS,
        emitFgsStartedImmediately = true,
      ),
    )
    assertTrue(slow.isFgsRunning)
    assertTrue(slowActivity.fgsStartedReceived)
    assertFalse(slow.capabilityChecker().downloadPromptRequested)
  }

  @Test
  fun micReadyFailureDegradesWithoutStoppingFgsAndAudioFocusPausesMic() {
    val failed = service()
    FgsBootstrapActivity(failed).launchFromEntry(StartupEntry.A_1, PermissionCheckInput(), emitFgsStartedImmediately = true)
    failed.reportMicReady(succeeded = false)
    assertTrue(failed.isFgsRunning)
    assertTrue(failed.isCapsuleVisible)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, failed.activeFgsType)
    assertEquals(TripVoiceMode.ANNOUNCE_MANUAL, failed.capabilityChecker().tripVoiceMode)
    assertEquals(MicSessionEvent(false, FloatingBubbleService.MIC_FAILED), failed.micSessionEvents.last())

    val live = service()
    FgsBootstrapActivity(live).launchFromEntry(StartupEntry.A_1, PermissionCheckInput(), emitFgsStartedImmediately = true)
    live.reportMicReady(succeeded = true)
    assertEquals(FloatingBubbleService.LISTENING, live.persistentNotificationText)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION_MIC, live.activeFgsType)
    live.onAudioFocusChanged(true)
    assertTrue(live.isAudioRecordPausedByFocus)
    assertFalse(live.isAudioRecordRunning)
    assertTrue(live.isFgsRunning)
    live.onAudioFocusChanged(false)
    assertFalse(live.isAudioRecordPausedByFocus)
    assertTrue(live.isAudioRecordRunning)

    live.onRuntimePermissionRevoked(FloatingBubbleService.RECORD_AUDIO)
    assertTrue(live.isFgsRunning)
    assertTrue(live.isCapsuleVisible)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, live.activeFgsType)
    assertFalse(live.isMicActive)
    live.onRuntimePermissionRevoked("ACCESS_FINE_LOCATION")
    assertFalse(live.isFgsRunning)
    assertFalse(live.isCapsuleVisible)
    assertEquals(FloatingBubbleService.PERMISSION_REVOKED, live.tripEndedReason)
  }

  private fun service(): FloatingBubbleService = FloatingBubbleService(VoiceCapabilityChecker(ReadyProbe()))

  private fun degradedChecker(service: FloatingBubbleService): VoiceCapabilityChecker = service.capabilityChecker()

  private class ReadyProbe : SpeechCapabilityProbe {
    override fun probeStt(locale: String): LocaleProbe = ready()
    override fun probeTts(locale: String): LocaleProbe = ready()

    private fun ready(): LocaleProbe = LocaleProbe(
      setLanguageSucceeded = true,
      offlineCantoneseVoiceInstalled = true,
      canRecognizeOrSynthesize = true,
      supported = true,
    )
  }
}

private fun FloatingBubbleService.capabilityChecker(): VoiceCapabilityChecker {
  val field = FloatingBubbleService::class.java.getDeclaredField("capabilityChecker")
  field.isAccessible = true
  return field.get(this) as VoiceCapabilityChecker
}
