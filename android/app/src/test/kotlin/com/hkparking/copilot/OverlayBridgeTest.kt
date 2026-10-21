package com.hkparking.copilot

import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.TripVoiceMode
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBridgeTest {
  @Test
  fun missingPermissionBlocksStartAndReturnsBannerError() {
    val coarse = host(PermissionCheckInput(coarseLocationOnly = true))
    val coarseResult = OverlayBridge(coarse).handle("startBubbleService", emptyMap())
    assertFalse(coarseResult.ok)
    assertEquals(FgsBootstrapActivity.MISSING_PERMISSION, coarseResult.errorCode)
    assertEquals(0, coarse.foregroundStartCount)
    assertEquals(true, coarseResult.details?.get("redirectToPermissionBanner"))
    assertTrue((coarseResult.details?.get("missingItems") as List<*>).contains(StartupPermissionChecker.FINE_LOCATION))

    val overlay = host(PermissionCheckInput(systemAlertWindowGranted = false))
    val overlayResult = OverlayBridge(overlay).handle("startBubbleService", emptyMap())
    assertFalse(overlayResult.ok)
    assertEquals(FgsBootstrapActivity.MISSING_PERMISSION, overlayResult.errorCode)
    assertEquals(0, overlay.foregroundStartCount)
    assertEquals(true, overlayResult.details?.get("redirectToPermissionBanner"))
  }

  @Test
  fun nonFullVoiceDoesNotUpgradeMicrophone() {
    val announce = host(PermissionCheckInput(), TtsOnlyProbe())
    announce.capabilityChecker.check(recordAudioGranted = true)
    assertEquals(TripVoiceMode.ANNOUNCE_MANUAL, announce.capabilityChecker.tripVoiceMode)
    announce.service.startInForeground(StartupEntry.A_2, recordAudioGranted = true)
    val announceResult = OverlayBridge(announce).handle("startVoiceCapture", mapOf("enableHotword" to true))
    assertTrue(announceResult.ok)
    assertFalse(announce.service.isMicActive)
    assertFalse(announce.service.isAudioRecordRunning)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, announce.service.activeFgsType)
    assertEquals(0, announce.foregroundStartCount)

    val manual = host(PermissionCheckInput(), UnavailableProbe())
    manual.capabilityChecker.check(recordAudioGranted = false)
    assertEquals(TripVoiceMode.MANUAL_ONLY, manual.capabilityChecker.tripVoiceMode)
    manual.service.startInForeground(StartupEntry.A_2, recordAudioGranted = false)
    OverlayBridge(manual).handle("startVoiceCapture", mapOf("enableHotword" to true))
    assertFalse(manual.service.isMicActive)
    assertEquals(VoiceCapabilityChecker.FGS_LOCATION, manual.service.activeFgsType)
    assertEquals(0, manual.foregroundStartCount)
  }

  @Test
  fun setPreferencesPersistsPreferenceAndHotword() {
    val host = host(PermissionCheckInput())
    val bridge = OverlayBridge(host)
    val written = bridge.handle("setPreferences", mapOf("preference" to "METERS_FOCUS", "hotwordEnabled" to false))
    assertTrue(written.ok)
    assertEquals("METERS_FOCUS", host.readString(OverlayBridge.PREF_KEY))
    assertEquals(false, host.readBoolean(OverlayBridge.HOTWORD_KEY))
    assertEquals("METERS_FOCUS", written.value.let { (it as Map<*, *>)[OverlayBridge.PREF_KEY] })
    assertEquals(false, (written.value as Map<*, *>)[OverlayBridge.HOTWORD_KEY])
  }

  private fun host(input: PermissionCheckInput, probe: SpeechCapabilityProbe = ReadyProbe()): MemoryHost {
    return MemoryHost(input, probe)
  }

  private class MemoryHost(
    private var input: PermissionCheckInput,
    probe: SpeechCapabilityProbe,
  ) : OverlayHost {
    override var foregroundStartCount: Int = 0
      private set
    override var channelMounted: Boolean = false
    override val capabilityChecker: VoiceCapabilityChecker = VoiceCapabilityChecker(probe)
    override val tripStateMachine: TripStateMachine = TripStateMachine()
    val service: FloatingBubbleService = FloatingBubbleService(capabilityChecker)
    private val strings = mutableMapOf<String, String>()
    private val bools = mutableMapOf<String, Boolean>()

    override fun permissionInput(): PermissionCheckInput = input

    override fun bluetoothConnectStatus(): String = "unknown"

    override fun startForegroundService(entry: StartupEntry) {
      foregroundStartCount += 1
    }

    override fun readString(key: String): String? = strings[key]

    override fun readBoolean(key: String): Boolean? = bools[key]

    override fun writeString(key: String, value: String) {
      strings[key] = value
    }

    override fun writeBoolean(key: String, value: Boolean) {
      bools[key] = value
    }

    override fun todayTrips(): List<Map<String, Any?>> = emptyList()

    override fun confirmParkedCandidate(sessionId: String) = Unit

    override fun submitTripReport(payloadJson: String) = Unit

    override fun activeService(): FloatingBubbleService = service

    override fun emit(method: String, arguments: Map<String, Any?>) = Unit
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
}
