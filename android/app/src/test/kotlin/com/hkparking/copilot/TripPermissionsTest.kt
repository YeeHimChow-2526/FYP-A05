package com.hkparking.copilot

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// t1253：權限狀態／可否再問嘅語義（純邏輯；唔假裝「永久拒絕」）。
class TripPermissionsTest {
  private class Host(
    override val sdk: Int,
    val granted: MutableSet<String> = mutableSetOf(),
    val rationale: MutableSet<String> = mutableSetOf(),
    val asked: MutableSet<String> = mutableSetOf(),
  ) : TripPermissions.Host {
    override fun granted(permission: String) = permission in granted
    override fun rationale(permission: String) = permission in rationale
    override fun askedBefore(kind: String) = kind in asked
  }

  @Test
  fun p1_locationPreciseVsApproximate() {
    val h = Host(34)
    assertEquals(TripPermissions.DENIED, TripPermissions.state(TripPermissions.LOCATION, h))
    h.granted += Manifest.permission.ACCESS_COARSE_LOCATION
    assertEquals(TripPermissions.APPROXIMATE, TripPermissions.state(TripPermissions.LOCATION, h))
    assertEquals(listOf(StartupPermissionChecker.FINE_LOCATION), TripPermissions.missingForStart(h, true))
    h.granted += Manifest.permission.ACCESS_FINE_LOCATION
    assertEquals(TripPermissions.GRANTED, TripPermissions.state(TripPermissions.LOCATION, h))
    assertTrue(TripPermissions.missingForStart(h, true).isEmpty())
    assertEquals(listOf(TripPermissions.LOCATION_SERVICES_OFF), TripPermissions.missingForStart(h, false))
  }

  @Test
  fun p2_notApplicableOnOldSdk() {
    val h = Host(30)
    assertEquals(TripPermissions.NOT_APPLICABLE, TripPermissions.state(TripPermissions.NOTIFICATIONS, h))
    assertEquals(TripPermissions.NOT_APPLICABLE, TripPermissions.state(TripPermissions.BLUETOOTH, h))
    assertFalse(TripPermissions.canRequest(TripPermissions.NOTIFICATIONS, h))
    assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), TripPermissions.manifestPermissions(TripPermissions.NOTIFICATIONS, 33))
  }

  @Test
  fun p3_canRequestFollowsRationaleAndHistory() {
    val h = Host(34)
    assertTrue("未問過：可以彈", TripPermissions.canRequest(TripPermissions.MICROPHONE, h))
    h.asked += TripPermissions.MICROPHONE
    h.rationale += Manifest.permission.RECORD_AUDIO
    assertTrue("拒絕過一次＋rationale：可以再問", TripPermissions.canRequest(TripPermissions.MICROPHONE, h))
    h.rationale.clear()
    assertFalse("問過＋冇 rationale：要去系統設定", TripPermissions.canRequest(TripPermissions.MICROPHONE, h))
    h.granted += Manifest.permission.RECORD_AUDIO
    assertFalse(TripPermissions.canRequest(TripPermissions.MICROPHONE, h))
    val all = TripPermissions.allStatus(h, true)
    assertEquals(true, all["locationServicesOn"])
    assertEquals(TripPermissions.GRANTED, (all[TripPermissions.MICROPHONE] as Map<*, *>)["state"])
  }
}
