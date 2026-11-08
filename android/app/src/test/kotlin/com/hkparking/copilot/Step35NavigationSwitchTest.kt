package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import com.hkparking.copilot.di.IntentNavigationPort
import com.hkparking.copilot.sim.SimNavigationLauncher
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

  // 原測試同時要求「Bindings 跟 BuildConfig」同「一定係 false」，-PHKP_USE_REAL_NAVIGATION=true 時自相矛盾；
  // 而且 Bindings 係 singleton，其他測試 attachService／clearPorts 後會改值（次序相依）。拆成兩個獨立斷言：
  // (1) build flag 決定 selectNavigation 路由；(2) JVM 單元測試一律強制 sim（RT-2）。

  @Test
  fun step35_buildFlagRoutesNavigationPort() {
    val port = Bindings.selectNavigation(BuildConfig.HKP_USE_REAL_NAVIGATION)
    if (BuildConfig.HKP_USE_REAL_NAVIGATION) {
      assertTrue(port is IntentNavigationPort, "flag=true 必須揀真導航 port")
      assertSame(Bindings.realNavigation, port)
    } else {
      assertTrue(port is SimNavigationLauncher, "flag=false 必須揀 sim launcher")
      assertSame(Bindings.navigation, port)
    }
  }

  @Test
  fun step35_jvmUnitTestForcesSimRegardlessOfBuildFlag() {
    Bindings.simulateBuildConfigRealSwitchesForTest(api = false, navigation = BuildConfig.HKP_USE_REAL_NAVIGATION)
    Bindings.clearPorts()
    assertFalse(Bindings.useRealNavigation, "JVM 測試唔可以用真導航（RT-2）")
    assertSame(Bindings.navigation, Bindings.activeNavigation)
  }

  @Test
  fun step35_realPortWithoutServiceContextFailsSafely() {
    val port = IntentNavigationPort { null }
    assertFalse(port.launchGateNavigation("朗豪坊商場停車場"))
  }
}
