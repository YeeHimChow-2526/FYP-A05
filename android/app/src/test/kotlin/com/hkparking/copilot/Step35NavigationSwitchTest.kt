package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.IntentNavigationPort
import com.hkparking.copilot.sim.SimNavigationLauncher
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

// Step 3.5（SC-018）：debug 導航開關 HKP_USE_REAL_NAVIGATION 揀啱實作；預設係 sim（現有 Step23／24／33 測試唔使改）。
class Step35NavigationSwitchTest {
  @Test
  fun step35_switchOffSelectsSimLauncher() {
    val port = Bindings.selectNavigation(false)
    assertSame(Bindings.navigation, port)
    assertTrue(port is SimNavigationLauncher)
  }

  @Test
  fun step35_switchOnSelectsRealIntentPort() {
    val port = Bindings.selectNavigation(true)
    assertTrue(port is IntentNavigationPort)
    assertSame(Bindings.realNavigation, port)
  }

  @Test
  fun step35_defaultBuildUsesSimAndActiveFollowsFlag() {
    assertEquals(BuildConfig.HKP_USE_REAL_NAVIGATION, Bindings.useRealNavigation)
    assertFalse(Bindings.useRealNavigation, "測試建置唔應該開 HKP_USE_REAL_NAVIGATION")
    assertSame(Bindings.navigation, Bindings.activeNavigation)
  }

  @Test
  fun step35_realPortWithoutServiceContextFailsSafely() {
    val port = IntentNavigationPort { null }
    assertFalse(port.launchGateNavigation("朗豪坊商場停車場"))
  }
}
