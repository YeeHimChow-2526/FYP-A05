package com.hkparking.copilot

import com.hkparking.copilot.trip.MemoryPrefsPort
import com.hkparking.copilot.voice.LocaleProbe
import com.hkparking.copilot.voice.SpeechCapabilityProbe
import com.hkparking.copilot.voice.VoiceCapabilityChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel boundary used by MainActivity: OverlayBridge.handle is what becomes
 * MethodChannel result.error. AndroidOverlayHost needs an Activity, so this JVM
 * test does not construct it. Its three trip methods are empty and this bridge
 * does not call them.
 */
class LegacyTripEntryTest {
  @Test
  fun legacyTripEntriesDoNotTouchRejectedDartRecordOrPretendSuccess() {
    val prefs = MemoryPrefsPort()
    val original = """
      [{"sessionId":"rej-1","carparkId":"CP","carparkName":"甲","parkedAtEpochMs":1000,"status":"CONFIRMED","confirmSyncPending":false,"feedbackSubmitted":false,"syncRejected":"HTTP_409","confirmSyncSent":false}]
    """.trim()
    prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, original)
    val host = WritingHost(prefs)
    val bridge = OverlayBridge(host)

    val confirm = bridge.handle("confirmParkedCandidate", mapOf("sessionId" to "rej-1"))
    val report = bridge.handle("submitTripReport", mapOf("payloadJson" to """{"session_id":"rej-1"}"""))
    val read = bridge.handle("getTodayTrips", emptyMap())

    for (result in listOf(confirm, report, read)) {
      assertFalse(result.ok)
      assertEquals(OverlayBridge.LEGACY_ENTRY_DISABLED, result.errorCode)
      assertEquals("舊入口已停用", result.message)
    }
    assertEquals("confirmParkedCandidate", confirm.details?.get("method"))
    assertEquals("submitTripReport", report.details?.get("method"))
    assertEquals("getTodayTrips", read.details?.get("method"))
    assertEquals(0, host.confirms)
    assertEquals(0, host.reports)
    assertEquals(0, host.reads)
    assertEquals(0, host.syncPosts)
    assertEquals(0, host.tripPosts)
    assertEquals(original, prefs.getString(NativeConfig.TODAY_TRIPS_JSON_KEY))
    assertTrue(bridge.handle("getTripState", emptyMap()).ok)
  }

  private class WritingHost(private val prefs: MemoryPrefsPort) : OverlayHost {
    var confirms = 0
    var reports = 0
    var reads = 0
    var syncPosts = 0
    var tripPosts = 0

    override var foregroundStartCount: Int = 0
      private set
    override var channelMounted: Boolean = false
    override val capabilityChecker = VoiceCapabilityChecker(UnavailableProbe())
    override val tripStateMachine = TripStateMachine()

    override fun permissionInput(): PermissionCheckInput = PermissionCheckInput()
    override fun bluetoothConnectStatus(): String = OverlayBridge.DENIED
    override fun startForegroundService(entry: StartupEntry) {
      foregroundStartCount += 1
    }
    override fun readString(key: String): String? = null
    override fun readBoolean(key: String): Boolean? = null
    override fun writeString(key: String, value: String) = Unit
    override fun writeBoolean(key: String, value: Boolean) = Unit

    override fun todayTrips(): List<Map<String, Any?>> {
      reads += 1
      syncPosts += 1
      prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, "[]")
      return emptyList()
    }

    override fun confirmParkedCandidate(sessionId: String) {
      confirms += 1
      syncPosts += 1
      prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, "[]")
    }

    override fun submitTripReport(payloadJson: String) {
      reports += 1
      tripPosts += 1
      prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, "[]")
    }

    override fun activeService(): FloatingBubbleService? = null
    override fun emit(method: String, arguments: Map<String, Any?>) = Unit
  }

  private class UnavailableProbe : SpeechCapabilityProbe {
    override fun probeStt(locale: String): LocaleProbe = LocaleProbe(supported = false)
    override fun probeTts(locale: String): LocaleProbe = LocaleProbe(supported = false)
  }
}
