package com.hkparking.copilot

import com.hkparking.copilot.voice.CommandCode
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import com.hkparking.copilot.voice.ZhHkTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStateMachineTest {
  @Test
  fun preFgsHasNoCapsuleAndIgnoresColdTapAndDrive() {
    val machine = TripStateMachine()
    assertEquals(TripState.PRE_FGS, machine.state)
    assertEquals(CapsuleLight.NONE, machine.capsuleLight)

    val tap = machine.coldTapCapsule()
    assertFalse(tap.accepted)
    assertFalse(tap.startFgs)
    assertNull(tap.sessionId)
    assertFalse(machine.fgsStartAccepted)
    assertEquals(TripState.PRE_FGS, machine.state)
    assertEquals(CapsuleLight.NONE, machine.capsuleLight)

    machine.onDriveSample(speedKmh = 80.0, deltaDistanceM = 500, deltaTimeS = 60)
    machine.onDistanceToDestination(NativeConfig.TRIGGER_DISTANCE_M)
    machine.onApproachSample(
      distanceM = NativeConfig.APPROACH_CORE_RADIUS_M,
      speedKmh = 1.0,
      lowSpeedHoldS = NativeConfig.APPROACH_CONSECUTIVE_S,
      fixAgeS = NativeConfig.GPS_FIX_STALE_S + 1,
    )
    machine.onNavigationConfidence(NativeConfig.NAV_CONFIDENCE_LOW_THRESHOLD)
    assertEquals(TripState.PRE_FGS, machine.state)
    assertEquals(CapsuleLight.NONE, machine.capsuleLight)
    assertEquals(0, machine.alertSpeakCount)
    assertFalse(machine.jitRequested)
    assertNull(machine.sessionId)
  }

  @Test
  fun successfulEntriesCreateUniqueSessionIds() {
    val ids = mutableSetOf<String>()
    StartupEntry.entries.forEach { entry ->
      val machine = TripStateMachine()
      val outcome = when (entry) {
        StartupEntry.B_1 -> machine.requestStart(
          entry,
          commandCode = CommandCode.SET_DESTINATION,
          destinationName = "朗豪坊",
        )
        StartupEntry.ON_SITE -> machine.requestStart(
          entry,
          commandCode = CommandCode.ON_SITE,
          gpsAvailable = true,
          gpsAccuracyM = NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M.toDouble(),
        )
        else -> machine.requestStart(entry)
      }
      assertTrue(entry.name, outcome.accepted)
      assertTrue(outcome.startFgs)
      assertFalse(outcome.recommend)
      assertTrue(outcome.sessionId!!.startsWith("sess_"))
      assertEquals(outcome.sessionId, machine.sessionId)
      assertEquals(TripState.PRE_FGS, machine.state)
      ids.add(outcome.sessionId)
    }
    assertEquals(StartupEntry.entries.size, ids.size)

    val again = TripStateMachine()
    val first = again.requestStart(StartupEntry.A_2).sessionId
    val second = again.requestStart(StartupEntry.A_2).sessionId
    assertNotEquals(first, second)
    assertEquals(second, again.sessionId)
  }

  @Test
  fun b1MissAndOnSiteGpsFailureStayPreFgsWithoutSession() {
    val missed = TripStateMachine()
    val missedParse = CommandCodes.parse("出發")
    val miss = missed.requestStart(
      StartupEntry.B_1,
      commandCode = missedParse.code,
      destinationName = missedParse.destinationName,
    )
    assertFalse(miss.accepted)
    assertFalse(miss.startFgs)
    assertFalse(miss.recommend)
    assertNull(miss.sessionId)
    assertNull(missed.sessionId)
    assertEquals(ZhHkTemplates.B1_DESTINATION_MISSED, miss.spokenCue)
    assertEquals(TripState.PRE_FGS, missed.state)
    assertEquals(CapsuleLight.NONE, missed.capsuleLight)
    assertFalse(missed.fgsStartAccepted)

    val blank = TripStateMachine().requestStart(StartupEntry.B_1)
    assertEquals(ZhHkTemplates.B1_DESTINATION_MISSED, blank.spokenCue)
    assertNull(blank.sessionId)

    val badGps = TripStateMachine()
    val gpsFail = badGps.requestStart(
      StartupEntry.ON_SITE,
      commandCode = CommandCode.ON_SITE,
      gpsAvailable = true,
      gpsAccuracyM = NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M + 1.0,
    )
    assertFalse(gpsFail.accepted)
    assertFalse(gpsFail.startFgs)
    assertFalse(gpsFail.recommend)
    assertNull(gpsFail.sessionId)
    assertEquals(ZhHkTemplates.ON_SITE_LOCATION_MISSED, gpsFail.spokenCue)
    assertEquals(TripState.PRE_FGS, badGps.state)

    val nearby = CommandCodes.parse("附近有冇位")
    val unavailable = TripStateMachine().requestStart(
      StartupEntry.B_1,
      commandCode = nearby.code,
      destinationName = nearby.destinationName,
      gpsAvailable = false,
      gpsAccuracyM = 10.0,
    )
    assertEquals(CommandCode.ON_SITE, nearby.code)
    assertEquals(ZhHkTemplates.ON_SITE_LOCATION_MISSED, unavailable.spokenCue)
    assertNull(unavailable.sessionId)
    assertFalse(unavailable.startFgs)

    val hereParse = CommandCodes.parse("就喺呢度")
    val here = TripStateMachine().requestStart(
      StartupEntry.B_1,
      commandCode = hereParse.code,
      destinationName = hereParse.destinationName,
      gpsAvailable = true,
      gpsAccuracyM = null,
    )
    assertNull(here.sessionId)
    assertEquals(ZhHkTemplates.ON_SITE_LOCATION_MISSED, here.spokenCue)

    val now = TripStateMachine()
    val nowParse = CommandCodes.parse("而家位置")
    val ok = now.requestStart(
      StartupEntry.B_1,
      commandCode = nowParse.code,
      destinationName = nowParse.destinationName,
      gpsAvailable = true,
      gpsAccuracyM = NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M.toDouble(),
    )
    assertTrue(ok.accepted)
    assertTrue(ok.sessionId!!.startsWith("sess_"))
    assertEquals(TripState.PRE_FGS, now.state)
  }

  @Test
  fun fgsStartedEntersCruisingOrImmediateJit() {
    val cruising = started(StartupEntry.A_2)
    assertEquals(TripState.CRUISING, cruising.state)
    assertEquals(CapsuleLight.GREY_STEADY, cruising.capsuleLight)
    assertFalse(cruising.jitRequested)

    val near = TripStateMachine()
    near.requestStart(
      StartupEntry.A_3,
      destinationName = "朗豪坊",
      distanceToDestinationM = NativeConfig.TRIGGER_DISTANCE_M,
    )
    near.onFgsStarted()
    assertEquals(TripState.AMBER_STEADY, near.state)
    assertEquals(CapsuleLight.AMBER_STEADY, near.capsuleLight)
    assertTrue(near.jitRequested)
    assertTrue(near.recommend)

    val far = TripStateMachine()
    far.requestStart(
      StartupEntry.B_3,
      destinationName = "朗豪坊",
      distanceToDestinationM = NativeConfig.TRIGGER_DISTANCE_M + 1,
    )
    far.onFgsStarted()
    assertEquals(TripState.CRUISING, far.state)
    assertEquals(CapsuleLight.GREY_STEADY, far.capsuleLight)
    assertFalse(far.jitRequested)

    val onSite = TripStateMachine()
    onSite.requestStart(
      StartupEntry.ON_SITE,
      commandCode = CommandCode.ON_SITE,
      gpsAvailable = true,
      gpsAccuracyM = 20.0,
    )
    onSite.onFgsStarted()
    assertEquals(TripState.AMBER_STEADY, onSite.state)
    assertTrue(onSite.jitRequested)
  }

  @Test
  fun networkFailureStaysGreyAheadOfAlert() {
    val machine = started(StartupEntry.A_1)
    machine.onDriveSample(speedKmh = 30.0, deltaDistanceM = 10, deltaTimeS = NativeConfig.SILENCE_DRIVE_DURATION_S)
    assertEquals(TripState.ALERT_BREATHING, machine.state)
    machine.onNetworkFailure()
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, machine.capsuleLight)
    machine.onDriveSample(speedKmh = 40.0, deltaDistanceM = NativeConfig.SILENCE_DRIVE_DISTANCE_M, deltaTimeS = 10)
    machine.onPlaceClarificationNeeded()
    machine.onDriverActionFailed(networkCause = false)
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, machine.capsuleLight)

    val noDestination = started(StartupEntry.B_2)
    noDestination.onNetworkFailure()
    noDestination.onNavigationConfidence(
      (NativeConfig.NAV_CONFIDENCE_LOW_THRESHOLD + NativeConfig.NAV_CONFIDENCE_HIGH_THRESHOLD) / 2,
    )
    assertFalse(noDestination.destinationLocked)
    assertEquals(TripState.GREY_BREATHING, noDestination.state)
    assertNotEquals(TripState.ALERT_BREATHING, noDestination.state)
  }

  @Test
  fun alertTriggersSpeakOnceAndLowConfidenceKeepsDestination() {
    val byDuration = started(StartupEntry.A_2)
    byDuration.onDriveSample(speedKmh = NativeConfig.SILENCE_DRIVE_SPEED_KMH.toDouble(), deltaDistanceM = 0, deltaTimeS = NativeConfig.SILENCE_DRIVE_DURATION_S)
    assertEquals(TripState.CRUISING, byDuration.state)
    byDuration.onDriveSample(
      speedKmh = NativeConfig.SILENCE_DRIVE_SPEED_KMH + 1.0,
      deltaDistanceM = 0,
      deltaTimeS = NativeConfig.SILENCE_DRIVE_DURATION_S,
    )
    assertEquals(TripState.ALERT_BREATHING, byDuration.state)
    assertEquals(CapsuleLight.ALERT_BREATHING, byDuration.capsuleLight)
    assertEquals(1, byDuration.alertSpeakCount)
    assertEquals(ZhHkTemplates.SILENCE_DRIVE, byDuration.lastSpokenCue)
    byDuration.onDriveSample(speedKmh = 40.0, deltaDistanceM = 50, deltaTimeS = 10)
    assertEquals(1, byDuration.alertSpeakCount)

    val byDistance = started(StartupEntry.A_2)
    byDistance.onDriveSample(
      speedKmh = 0.0,
      deltaDistanceM = NativeConfig.SILENCE_DRIVE_DISTANCE_M,
      deltaTimeS = 1,
    )
    assertEquals(TripState.ALERT_BREATHING, byDistance.state)
    assertEquals(1, byDistance.alertSpeakCount)

    val nav = started(StartupEntry.A_3, destinationName = "朗豪坊", distanceToDestinationM = 5000)
    nav.onNavigationConfidence(NativeConfig.NAV_CONFIDENCE_LOW_THRESHOLD - 0.01)
    assertTrue(nav.destinationLocked)
    assertEquals(TripState.CRUISING, nav.state)
    nav.onNavigationConfidence(NativeConfig.NAV_CONFIDENCE_HIGH_THRESHOLD)
    assertTrue(nav.destinationLocked)
    assertEquals(TripState.CRUISING, nav.state)
    assertEquals(0, nav.alertSpeakCount)
    val freshNav = started(StartupEntry.A_3)
    freshNav.onNavigationConfidence(NativeConfig.NAV_CONFIDENCE_LOW_THRESHOLD)
    assertEquals(TripState.ALERT_BREATHING, freshNav.state)
    assertEquals(1, freshNav.alertSpeakCount)
    freshNav.onNavigationConfidence(
      NativeConfig.NAV_CONFIDENCE_HIGH_THRESHOLD - 0.01,
    )
    assertEquals(1, freshNav.alertSpeakCount)

    val clarify = started(StartupEntry.B_1, destinationName = "朗豪坊")
    clarify.onPlaceClarificationNeeded()
    assertEquals(TripState.ALERT_BREATHING, clarify.state)
    assertEquals(ZhHkTemplates.PLACE_CLARIFY, clarify.lastSpokenCue)
    clarify.onPlaceClarificationNeeded()
    assertEquals(1, clarify.alertSpeakCount)
    assertTrue(clarify.destinationLocked)

    val failed = started(StartupEntry.B_3)
    failed.onDriverActionFailed(networkCause = true)
    assertEquals(TripState.GREY_BREATHING, failed.state)
    val nonNetwork = started(StartupEntry.B_3)
    nonNetwork.onDriverActionFailed(networkCause = false)
    assertEquals(TripState.ALERT_BREATHING, nonNetwork.state)
    nonNetwork.onDriverActionFailed(networkCause = false)
    assertEquals(1, nonNetwork.alertSpeakCount)
    val lockedFail = started(StartupEntry.B_3, destinationName = "朗豪坊", distanceToDestinationM = 2000)
    lockedFail.onDriverActionFailed(networkCause = false)
    assertEquals(TripState.CRUISING, lockedFail.state)
  }

  @Test
  fun permissionRevokeEndsSessionButMicRevokeOnlyDegrades() {
    val ended = mutableListOf<String>()
    val revoked = TripStateMachine(onTripEnded = { ended.add(it) })
    revoked.requestStart(StartupEntry.A_1)
    revoked.onFgsStarted()
    val session = revoked.sessionId
    assertTrue(session!!.startsWith("sess_"))
    revoked.onRequiredPermissionRevoked()
    assertEquals(listOf("PERMISSION_REVOKED"), ended)
    assertNull(revoked.sessionId)
    assertEquals(TripState.ENDED, revoked.state)
    assertEquals(CapsuleLight.NONE, revoked.capsuleLight)
    assertFalse(revoked.fgsRunning)

    val micEnded = mutableListOf<String>()
    val mic = TripStateMachine(onTripEnded = { micEnded.add(it) })
    mic.applyVoiceCapability(fullVoiceChecker())
    mic.requestStart(StartupEntry.A_2)
    mic.onFgsStarted()
    val micSession = mic.sessionId
    mic.onRecordAudioRevoked(ttsAvailable = true)
    assertEquals(emptyList<String>(), micEnded)
    assertEquals(micSession, mic.sessionId)
    assertEquals(TripState.CRUISING, mic.state)
    assertFalse(mic.voiceInputEnabled)
    assertEquals(TripVoiceMode.ANNOUNCE_MANUAL, mic.tripVoiceMode)

    val manual = started(StartupEntry.A_2)
    manual.onRecordAudioRevoked(ttsAvailable = false)
    assertEquals(TripVoiceMode.MANUAL_ONLY, manual.tripVoiceMode)
    assertFalse(manual.voiceInputEnabled)
    assertEquals(TripState.CRUISING, manual.state)
    assertTrue(manual.sessionId!!.startsWith("sess_"))
  }

  @Test
  fun tripEndDestroysSessionWithoutReconnectingOldId() {
    val reasons = mutableListOf<String>()
    val parked = TripStateMachine(onTripEnded = { reasons.add(it) })
    val oldId = parked.requestStart(StartupEntry.B_2).sessionId
    parked.onFgsStarted()
    parked.onParkedConfirmed()
    assertEquals(listOf("PARKED_CONFIRMED"), reasons)
    assertNull(parked.sessionId)
    assertEquals(TripState.PARKED_CONFIRMED, parked.state)
    val restarted = parked.requestStart(StartupEntry.B_2).sessionId
    assertNotEquals(oldId, restarted)

    val shutdown = mutableListOf<String>()
    val outside = TripStateMachine(onTripEnded = { shutdown.add(it) })
    outside.requestStart(StartupEntry.A_1)
    outside.onFgsStarted()
    outside.onEngineShutdown(withinTargetOrBasementLoss = false)
    assertEquals(listOf("ENGINE_SHUTDOWN"), shutdown)
    assertNull(outside.sessionId)
    assertEquals(TripState.ENDED, outside.state)

    val oem = TripStateMachine()
    oem.requestStart(
      StartupEntry.ON_SITE,
      commandCode = CommandCode.ON_SITE,
      gpsAvailable = true,
      gpsAccuracyM = 5.0,
    )
    oem.onFgsStarted()
    oem.onOemKilled()
    assertNull(oem.sessionId)
    assertEquals(TripState.ENDED, oem.state)
    assertEquals(CapsuleLight.NONE, oem.capsuleLight)
  }

  @Test
  fun preFgsServiceHasNoCapsuleLightUntilFgsStarted() {
    val service = FloatingBubbleService(fullVoiceChecker())
    assertEquals(TripState.PRE_FGS, service.tripStateMachine.state)
    assertEquals(CapsuleLight.NONE, service.tripStateMachine.capsuleLight)
    assertFalse(service.isCapsuleVisible)
    assertFalse(service.isFgsRunning)
    service.startInForeground(StartupEntry.A_2, recordAudioGranted = true)
    assertEquals(TripState.PRE_FGS, service.tripStateMachine.state)
    assertEquals(CapsuleLight.NONE, service.tripStateMachine.capsuleLight)
    assertNotEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)
    assertNotEquals(TripState.ALERT_BREATHING, service.tripStateMachine.state)
    service.reportFgsStarted()
    assertEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertEquals(CapsuleLight.GREY_STEADY, service.tripStateMachine.capsuleLight)
  }

  @Test
  fun capabilityTimeoutDoesNotBlockFgsStarted() {
    val checker = VoiceCapabilityChecker(HangAfterProbe(ReadyProbe(), hangImmediately = true))
    val service = FloatingBubbleService(checker)
    val activity = FgsBootstrapActivity(service)
    assertTrue(
      activity.launchFromEntry(
        StartupEntry.A_1,
        PermissionCheckInput(),
      ),
    )
    assertFalse(service.isCapsuleVisible)
    assertEquals(TripState.PRE_FGS, service.tripStateMachine.state)
    assertEquals(CapsuleLight.NONE, service.tripStateMachine.capsuleLight)
    assertNotEquals(TripState.CRUISING, service.tripStateMachine.state)
    assertNotEquals(TripState.GREY_BREATHING, service.tripStateMachine.state)
    assertNotEquals(TripState.ALERT_BREATHING, service.tripStateMachine.state)
    service.reportFgsStarted()
    assertTrue(activity.fgsStartedReceived)
    assertTrue(activity.isFinished)
    assertFalse(service.capabilityChecker.blocksFgsStarted)
    assertFalse(service.capabilityChecker.downloadPromptRequested)
    assertEquals(TripVoiceMode.MANUAL_ONLY, service.tripStateMachine.tripVoiceMode)
  }

  @Test
  fun silentRecheckTimeoutReusesConfirmedModeOrFallsBackWhenNeverConfirmed() {
    val confirmedProbe = HangAfterProbe(ReadyProbe())
    val confirmed = VoiceCapabilityChecker(confirmedProbe)
    confirmed.check(recordAudioGranted = true)
    confirmedProbe.hang = true
    val reused = confirmed.recheckSilently(recordAudioGranted = true)
    val kept = TripStateMachine()
    kept.syncVoiceCapability(reused, confirmed.hasConfirmed)
    assertTrue(reused.timedOut)
    assertFalse(reused.blocksFgsStarted)
    assertEquals(TripVoiceMode.FULL_VOICE, kept.tripVoiceMode)

    val fresh = VoiceCapabilityChecker(HangAfterProbe(ReadyProbe(), hangImmediately = true))
    assertFalse(fresh.hasConfirmed)
    val timedOut = fresh.recheckSilently(recordAudioGranted = true)
    val unavailable = TripStateMachine()
    unavailable.syncVoiceCapability(timedOut, fresh.hasConfirmed)
    assertTrue(timedOut.timedOut)
    assertEquals(TripVoiceMode.MANUAL_ONLY, unavailable.tripVoiceMode)
    assertNotEquals(TripVoiceMode.FULL_VOICE, unavailable.tripVoiceMode)
  }

  @Test
  fun greyBreathingStaysAheadOfAlertEvenWithoutDestination() {
    val machine = started(StartupEntry.A_2)
    assertFalse(machine.destinationLocked)
    machine.onNetworkFailure()
    assertEquals(TripState.GREY_BREATHING, machine.state)
    machine.onPlaceClarificationNeeded()
    machine.onDriveSample(speedKmh = 40.0, deltaDistanceM = 500, deltaTimeS = 40)
    assertEquals(TripState.GREY_BREATHING, machine.state)
    assertEquals(CapsuleLight.GREY_BREATHING, machine.capsuleLight)
    assertNotEquals(TripState.ALERT_BREATHING, machine.state)
  }

  @Test
  fun voiceModeComesFromCheckerAndFailedStartHasNoSession() {
    val announce = TripStateMachine()
    val announceChecker = VoiceCapabilityChecker(TtsOnlyProbe())
    announceChecker.check(recordAudioGranted = true)
    announce.applyVoiceCapability(announceChecker)
    announce.requestStart(StartupEntry.A_2)
    assertEquals(TripVoiceMode.ANNOUNCE_MANUAL, announce.tripVoiceMode)
    assertNotEquals(TripVoiceMode.FULL_VOICE, announce.tripVoiceMode)

    val manual = TripStateMachine()
    val manualChecker = VoiceCapabilityChecker(UnavailableProbe())
    manualChecker.check(recordAudioGranted = false)
    manual.applyVoiceCapability(manualChecker)
    manual.requestStart(StartupEntry.A_2)
    assertEquals(TripVoiceMode.MANUAL_ONLY, manual.tripVoiceMode)

    val missed = TripStateMachine()
    val missedParse = CommandCodes.parse("出發")
    val missedStart = missed.requestStart(
      StartupEntry.B_1,
      commandCode = missedParse.code,
      destinationName = missedParse.destinationName,
    )
    assertFalse(missedStart.accepted)
    assertNull(missed.sessionId)
    val gpsFail = TripStateMachine().requestStart(
      StartupEntry.ON_SITE,
      commandCode = CommandCode.ON_SITE,
      gpsAvailable = true,
      gpsAccuracyM = NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M + 1.0,
    )
    assertFalse(gpsFail.accepted)
    assertNull(gpsFail.sessionId)
  }

  private fun fullVoiceChecker(): VoiceCapabilityChecker {
    val checker = VoiceCapabilityChecker(ReadyProbe())
    checker.check(recordAudioGranted = true)
    return checker
  }

  @Test
  fun alertCueAppendsTapOnlyWhenFullVoiceMicNotUpgraded() {
    val full = alertCue(TripVoiceMode.FULL_VOICE, micActive = false)
    assertTrue(full.endsWith(ZhHkTemplates.TAP_CAPSULE))
    assertEquals(ZhHkTemplates.SILENCE_DRIVE + ZhHkTemplates.TAP_CAPSULE, full)

    val upgraded = alertCue(TripVoiceMode.FULL_VOICE, micActive = true)
    assertFalse(upgraded.contains(ZhHkTemplates.TAP_CAPSULE))
    assertEquals(ZhHkTemplates.SILENCE_DRIVE, upgraded)

    val announce = alertCue(TripVoiceMode.ANNOUNCE_MANUAL, micActive = false)
    assertFalse(announce.contains(ZhHkTemplates.TAP_CAPSULE))
    assertEquals(ZhHkTemplates.SILENCE_DRIVE, announce)

    val manual = alertCue(TripVoiceMode.MANUAL_ONLY, micActive = false)
    assertFalse(manual.contains(ZhHkTemplates.TAP_CAPSULE))
    assertEquals(ZhHkTemplates.SILENCE_DRIVE, manual)

    val clarify = voiced(TripVoiceMode.FULL_VOICE, micActive = false)
    clarify.onPlaceClarificationNeeded()
    assertTrue(clarify.lastSpokenCue!!.endsWith(ZhHkTemplates.TAP_CAPSULE))
    val nav = voiced(TripVoiceMode.ANNOUNCE_MANUAL, micActive = false)
    nav.onNavigationConfidence(NativeConfig.NAV_CONFIDENCE_LOW_THRESHOLD)
    assertFalse(nav.lastSpokenCue!!.contains(ZhHkTemplates.TAP_CAPSULE))
    val failed = voiced(TripVoiceMode.MANUAL_ONLY, micActive = true)
    failed.onDriverActionFailed(networkCause = false)
    assertFalse(failed.lastSpokenCue!!.contains(ZhHkTemplates.TAP_CAPSULE))
  }

  @Test
  fun onDestinationSetLeavesAlertAndJitWithinTrigger() {
    val far = voiced(TripVoiceMode.MANUAL_ONLY, micActive = false)
    far.onDriveSample(
      speedKmh = NativeConfig.SILENCE_DRIVE_SPEED_KMH + 1.0,
      deltaDistanceM = 0,
      deltaTimeS = NativeConfig.SILENCE_DRIVE_DURATION_S,
    )
    assertEquals(TripState.ALERT_BREATHING, far.state)
    far.onDestinationSet(distanceToDestinationM = NativeConfig.TRIGGER_DISTANCE_M + 500)
    assertEquals(TripState.CRUISING, far.state)
    assertEquals(CapsuleLight.GREY_STEADY, far.capsuleLight)
    assertFalse(far.jitTriggered)
    assertTrue(far.hasDestination)

    val near = voiced(TripVoiceMode.MANUAL_ONLY, micActive = false)
    near.onDriveSample(
      speedKmh = NativeConfig.SILENCE_DRIVE_SPEED_KMH + 1.0,
      deltaDistanceM = 0,
      deltaTimeS = NativeConfig.SILENCE_DRIVE_DURATION_S,
    )
    near.onDestinationSet(distanceToDestinationM = NativeConfig.TRIGGER_DISTANCE_M, destinationName = "朗豪坊")
    assertNotEquals(TripState.ALERT_BREATHING, near.state)
    assertTrue(near.jitTriggered)
    assertEquals("朗豪坊", near.destinationName)
  }

  @Test
  fun requestStartAcceptsCommandCodeAndResetsStatusPip() {
    val named = TripStateMachine().requestStart(
      StartupEntry.B_1,
      commandCode = CommandCode.SET_DESTINATION,
      destinationName = "朗豪坊",
    )
    assertTrue(named.accepted)
    assertTrue(named.sessionId!!.startsWith("sess_"))

    val onSite = TripStateMachine().requestStart(
      StartupEntry.B_1,
      commandCode = CommandCode.ON_SITE,
      gpsAvailable = true,
      gpsAccuracyM = NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M.toDouble(),
    )
    assertTrue(onSite.accepted)
    assertNotNull(onSite.sessionId)

    val unknown = TripStateMachine().requestStart(
      StartupEntry.B_1,
      commandCode = CommandCode.UNKNOWN,
    )
    assertFalse(unknown.accepted)
    assertNull(unknown.sessionId)

    val pip = started(StartupEntry.A_2)
    pip.onJitSucceeded(StatusPip.YELLOW)
    assertEquals(StatusPip.YELLOW, pip.statusPip)
    pip.clearSession()
    assertEquals(StatusPip.NONE, pip.statusPip)

    val next = started(StartupEntry.A_1)
    next.onJitSucceeded(StatusPip.RED)
    assertEquals(StatusPip.RED, next.statusPip)
    next.requestStart(StartupEntry.A_2)
    assertEquals(StatusPip.NONE, next.statusPip)
  }

  private fun alertCue(mode: TripVoiceMode, micActive: Boolean): String {
    return voiced(mode, micActive).lastSpokenCue!!
  }

  private fun voiced(mode: TripVoiceMode, micActive: Boolean): TripStateMachine {
    val machine = TripStateMachine()
    val checker = when (mode) {
      TripVoiceMode.FULL_VOICE -> fullVoiceChecker()
      TripVoiceMode.ANNOUNCE_MANUAL -> VoiceCapabilityChecker(TtsOnlyProbe()).also { it.check(recordAudioGranted = true) }
      TripVoiceMode.MANUAL_ONLY -> VoiceCapabilityChecker(UnavailableProbe()).also { it.check(recordAudioGranted = false) }
    }
    machine.applyVoiceCapability(checker)
    machine.requestStart(StartupEntry.A_2)
    machine.onFgsStarted()
    machine.onMicFgsActive(micActive)
    if (mode == TripVoiceMode.FULL_VOICE && !micActive) {
      machine.onDriveSample(
        speedKmh = NativeConfig.SILENCE_DRIVE_SPEED_KMH + 1.0,
        deltaDistanceM = 0,
        deltaTimeS = NativeConfig.SILENCE_DRIVE_DURATION_S,
      )
    } else if (mode != TripVoiceMode.FULL_VOICE || micActive) {
      machine.onDriveSample(
        speedKmh = NativeConfig.SILENCE_DRIVE_SPEED_KMH + 1.0,
        deltaDistanceM = 0,
        deltaTimeS = NativeConfig.SILENCE_DRIVE_DURATION_S,
      )
    }
    return machine
  }

  private fun started(
    entry: StartupEntry,
    destinationName: String? = null,
    distanceToDestinationM: Int? = null,
    commandCode: CommandCode? = null,
  ): TripStateMachine {
    val machine = TripStateMachine()
    val code = commandCode ?: when {
      entry == StartupEntry.B_1 && !destinationName.isNullOrBlank() -> CommandCode.SET_DESTINATION
      entry == StartupEntry.ON_SITE -> CommandCode.ON_SITE
      else -> null
    }
    machine.requestStart(
      entry = entry,
      commandCode = code,
      destinationName = destinationName,
      gpsAvailable = true,
      gpsAccuracyM = if (entry == StartupEntry.ON_SITE || code == CommandCode.ON_SITE) 10.0 else null,
      distanceToDestinationM = distanceToDestinationM,
    )
    machine.onFgsStarted()
    return machine
  }
}

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

private class TtsOnlyProbe : SpeechCapabilityProbe {
  override fun probeStt(locale: String): LocaleProbe = LocaleProbe(supported = false)
  override fun probeTts(locale: String): LocaleProbe = LocaleProbe(
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

private class HangAfterProbe(
  private val delegate: SpeechCapabilityProbe,
  hangImmediately: Boolean = false,
) : SpeechCapabilityProbe {
  var hang: Boolean = hangImmediately

  override fun probeStt(locale: String): LocaleProbe {
    if (hang) {
      try {
        java.util.concurrent.CountDownLatch(1).await()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      return LocaleProbe()
    }
    return delegate.probeStt(locale)
  }

  override fun probeTts(locale: String): LocaleProbe = delegate.probeTts(locale)
}
