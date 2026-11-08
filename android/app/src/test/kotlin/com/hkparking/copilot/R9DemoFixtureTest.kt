package com.hkparking.copilot

import com.hkparking.copilot.di.Bindings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// r9：DEMO fixturePost 永遠本地 fixture，就算 build 開咗真 API 都唔打雲端。
class R9DemoFixtureTest {
  @After
  fun restore() {
    Bindings.simulateBuildConfigRealSwitchesForTest(api = false, navigation = false)
  }

  @Test
  fun fixturePostUsesLocalFixtureEvenWhenRealApiOn() {
    Bindings.simulateBuildConfigRealSwitchesForTest(api = true, navigation = false)
    assertTrue(Bindings.transportUseRealApi())
    val payload = Bindings.fixturePostOrNull("/v1/recommend", "{}")!!
    assertEquals(200, payload["statusCode"])
    assertTrue((payload["body"] as String).contains("CP_LANGHAM"))
  }
}
