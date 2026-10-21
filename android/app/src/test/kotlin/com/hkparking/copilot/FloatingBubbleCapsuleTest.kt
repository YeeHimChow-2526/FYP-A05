package com.hkparking.copilot

import com.hkparking.copilot.net.NetworkLightState
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBubbleCapsuleTest {
  @Test
  fun storedCallbackFiresOnReportFgsStartedWithoutImmediateFlagAndNoReflection() {
    val service = readyService()
    val activity = FgsBootstrapActivity(service)
    assertTrue(activity.launchFromEntry(entry = StartupEntry.A_1, permissions = PermissionCheckInput()))
    assertFalse(activity.isFinished)
    assertFalse(service.isCapsuleVisible)
    assertEquals(CapsuleRingLight.HIDDEN, service.ringLight)
    service.reportFgsStarted()
    assertTrue(activity.fgsStartedReceived)
    assertTrue(activity.isFinished)
    assertTrue(service.isCapsuleVisible)
    assertEquals(CapsuleRingLight.GREY_STEADY, service.ringLight)
    assertEquals(TripVoiceMode.FULL_VOICE, service.capabilityChecker.tripVoiceMode)
  }

  @Test
  fun preFgsHasNoRingLightOrPipAndVoiceStartRequiresMicUpgradeOrHeyGoogle() {
    val service = readyService()
    assertEquals(CapsuleRingLight.HIDDEN, service.ringLight)
    assertEquals(VacancyPip.NONE, service.vacancyPip)
    service.applyNetworkState(NetworkLightState.AMBER_STEADY)
    service.applyNetworkState(NetworkLightState.GREY_BREATHING)
    assertEquals(CapsuleRingLight.HIDDEN, service.ringLight)
    assertEquals(VacancyPip.NONE, service.vacancyPip)
    assertFalse(service.requestAlertBreathing(AlertTriggerReason.SILENCE_DRIVE_NO_DEST))
    assertEquals(CapsuleRingLight.HIDDEN, service.ringLight)
    assertFalse(service.onVoiceStartCommand(viaHeyGoogleColdStart = false))
    assertTrue(service.onVoiceStartCommand(viaHeyGoogleColdStart = true))
    FgsBootstrapActivity(service).launchFromEntry(StartupEntry.A_2, PermissionCheckInput())
    service.reportFgsStarted()
    assertFalse(service.isMicActive)
    assertFalse(service.onVoiceStartCommand())
    service.reportMicReady(succeeded = true)
    assertTrue(service.onVoiceStartCommand())
  }

  @Test
  fun greyBreathingOverridesAlertBreathingAndNoResponseStaysSteady() {
    val service = started()
    service.applyNetworkState(NetworkLightState.AMBER_STEADY)
    assertEquals(CapsuleRingLight.AMBER_STEADY, service.ringLight)
    service.applyNetworkState(NetworkLightState.NEARBY_READY, topVacancies = null)
    assertEquals(VacancyPip.YELLOW, service.vacancyPip)
    service.applyNetworkState(NetworkLightState.NEARBY_READY, topVacancies = -1)
    assertEquals(VacancyPip.YELLOW, service.vacancyPip)
    service.applyNetworkState(NetworkLightState.NEARBY_READY, topVacancies = 3)
    assertEquals(VacancyPip.RED, service.vacancyPip)
    service.applyNetworkState(NetworkLightState.NEARBY_READY, topVacancies = 4)
    assertEquals(VacancyPip.YELLOW, service.vacancyPip)
    service.applyNetworkState(NetworkLightState.NEARBY_READY, topVacancies = 10)
    assertEquals(VacancyPip.YELLOW, service.vacancyPip)
    service.applyNetworkState(NetworkLightState.NEARBY_READY, topVacancies = 11)
    assertEquals(VacancyPip.GREEN, service.vacancyPip)
    assertEquals(CapsuleRingLight.GREY_STEADY, service.ringLight)
    assertFalse(service.autoCardPopupShown)
    service.onFirstChoiceNoResponse()
    assertEquals(CapsuleRingLight.GREY_STEADY, service.ringLight)
    assertEquals(VacancyPip.GREEN, service.vacancyPip)
    assertFalse(service.autoCardPopupShown)

    service.applyNetworkState(NetworkLightState.GREY_BREATHING)
    assertFalse(service.requestAlertBreathing(AlertTriggerReason.SILENCE_DRIVE_NO_DEST))
    assertEquals(CapsuleRingLight.GREY_BREATHING, service.ringLight)
    service.applyNetworkState(NetworkLightState.ALERT_BREATHING)
    assertEquals(CapsuleRingLight.GREY_BREATHING, service.ringLight)

    val alert = started()
    assertTrue(alert.requestAlertBreathing(AlertTriggerReason.DESTINATION_NEEDS_CLARIFICATION))
    assertEquals(CapsuleRingLight.ALERT_BREATHING, alert.ringLight)
    alert.onDestinationLocked(true)
    assertEquals(CapsuleRingLight.GREY_STEADY, alert.ringLight)
    assertFalse(alert.requestAlertBreathing(AlertTriggerReason.USER_ACTION_NON_NETWORK_FAIL_NO_DEST))
    assertEquals(CapsuleRingLight.GREY_STEADY, alert.ringLight)
  }

  @Test
  fun activeBubbleTapUpgradesMicInFullVoiceOrShowsManualCardWhenSttUnavailable() {
    val full = started()
    assertTrue(full.onActiveBubbleTap())
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION_MIC, full.activeFgsType)
    assertTrue(full.isMicActive)
    assertEquals(FloatingBubbleService.LISTENING, full.persistentNotificationText)
    assertEquals(MicSessionEvent(true, FloatingBubbleService.BUBBLE_TAP), full.micSessionEvents.last())

    val announce = started()
    announce.reportMicReady(succeeded = false)
    assertEquals(TripVoiceMode.ANNOUNCE_MANUAL, announce.capabilityChecker.tripVoiceMode)
    assertTrue(announce.onActiveBubbleTap())
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, announce.activeFgsType)
    assertFalse(announce.isMicActive)
    assertTrue(announce.manualCardVisible)

    val manual = started(UnavailableProbe())
    assertEquals(TripVoiceMode.MANUAL_ONLY, manual.capabilityChecker.tripVoiceMode)
    assertTrue(manual.onActiveBubbleTap())
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, manual.activeFgsType)
    assertFalse(manual.isMicActive)
    assertTrue(manual.manualCardVisible)

    val grey = started()
    grey.applyNetworkState(NetworkLightState.GREY_BREATHING)
    assertTrue(grey.onActiveBubbleTap())
    assertTrue(grey.manualRetryRequested)
  }

  @Test
  fun oemKillAbandonsTripWithoutAutoRestart() {
    val service = started()
    service.onOemKilledOrTaskRemoved()
    assertFalse(service.isFgsRunning)
    assertFalse(service.isCapsuleVisible)
    assertEquals(CapsuleRingLight.HIDDEN, service.ringLight)
    assertTrue(service.abandonedBySystemKill)
    assertFalse(service.autoRestartScheduled)
    assertEquals(FloatingBubbleService.ABANDONED_OEM_KILL, service.tripEndedReason)
  }

  private fun started(probe: SpeechCapabilityProbe = ReadyProbe()): FloatingBubbleService {
    val service = FloatingBubbleService(VoiceCapabilityChecker(probe))
    FgsBootstrapActivity(service).launchFromEntry(StartupEntry.A_1, PermissionCheckInput())
    service.reportFgsStarted()
    return service
  }

  private fun readyService(): FloatingBubbleService = FloatingBubbleService(VoiceCapabilityChecker(ReadyProbe()))

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

  private class UnavailableProbe : SpeechCapabilityProbe {
    override fun probeStt(locale: String): LocaleProbe = LocaleProbe(supported = false)
    override fun probeTts(locale: String): LocaleProbe = LocaleProbe(supported = false)
  }
}
