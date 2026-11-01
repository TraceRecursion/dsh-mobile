package com.clarklevis.dsh.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 内嵌 Tailscale 隧道的 UI 侧断言：入口行存在且能进入页面；节点名校验与持久化。
 *
 * 这里刻意不去点「启用内嵌隧道」开关：点下它会真的启动一个 tailnet 节点（需要登录），
 * 那是 TsnetTunnelDeviceTest 的职责（带 auth key 参数），不该混进 UI 测试。
 */
class TailscaleTunnelUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsRowShowsStateAndOpensTheTunnelPage() {
        // 用独立的偏好文件，不去读 app 真实状态：用户可能已经把隧道开着，
        // 那会让「未启用」这种断言随真机状态摇摆（实测踩过）。
        val isolated = TailscaleTunnelSettings(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("tunnel_test_row_isolated", android.content.Context.MODE_PRIVATE)
        )
        var opened = 0
        compose.setContent {
            DshTheme(tunnelSettings = isolated) {
                TailscaleTunnelRow(onClick = { opened += 1 })
            }
        }
        isolated.selectEnabled(false)
        compose.onNodeWithText("内嵌隧道").assertIsDisplayed()
        compose.onNodeWithText("未启用").assertIsDisplayed()
        compose.onNodeWithText("内嵌隧道").performClick()
        assertEquals(1, opened)

        isolated.selectEnabled(true)
        compose.waitForIdle()
        compose.onNodeWithText("已启用").assertIsDisplayed()
        isolated.selectEnabled(false)
    }

    @Test
    fun tunnelPageRendersTheEnableControl() {
        compose.setContent {
            DshTheme { TailscaleTunnelScreen(onBack = {}) }
        }
        compose.onNodeWithTag("tunnel-content").assertIsDisplayed()
        compose.onNodeWithText("启用内嵌隧道").assertIsDisplayed()
    }

    @Test
    fun nodeNameIsValidatedBeforeItReachesTailscale() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("tunnel_test_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = TailscaleTunnelSettings(prefs)

        // 空格/下划线/点是刻意规范成连字符的，不是错误输入。
        assertEquals("my-phone", TailscaleTunnelSettings.normalizeNodeName("My Phone"))
        assertEquals("dsh-pixel-7", TailscaleTunnelSettings.normalizeNodeName("DSH Pixel 7 "))
        assertEquals("dsh-phone", TailscaleTunnelSettings.normalizeNodeName("dsh--phone"))
        // 首尾连字符同样是刻意裁掉的（与空格→连字符同一套宽容规范），不是错误输入。
        assertEquals("lead", TailscaleTunnelSettings.normalizeNodeName("-lead"))
        assertEquals("trail", TailscaleTunnelSettings.normalizeNodeName("trail-"))
        assertNull(TailscaleTunnelSettings.normalizeNodeName(""))
        assertNull(TailscaleTunnelSettings.normalizeNodeName("-----"))
        assertNull(TailscaleTunnelSettings.normalizeNodeName("bad!name"))
        assertNull(TailscaleTunnelSettings.normalizeNodeName("中文名称"))
        assertNull(TailscaleTunnelSettings.normalizeNodeName("a".repeat(64)))

        assertFalse(settings.selectNodeName("Bad Name!"))
        assertEquals("", settings.nodeName)
        assertTrue(settings.selectNodeName("  Dsh-Test-Phone  "))
        assertEquals("dsh-test-phone", settings.nodeName)
        assertEquals(
            "dsh-test-phone",
            TailscaleTunnelSettings(prefs).nodeName
        )
        assertTrue(TailscaleTunnelSettings.defaultNodeName().startsWith("dsh-"))
    }
}
