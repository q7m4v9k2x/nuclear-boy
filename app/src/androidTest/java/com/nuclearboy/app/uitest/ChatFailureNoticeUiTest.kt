package com.nuclearboy.app.uitest

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFailureNoticeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val robot = ChatJourneyRobot()

    @Test
    fun persistedModelRoutingFailureShowsActionableNoticeCard() {
        grantNotificationPermissionIfPossible()
        seedFailureConversation(
            """处理时遇到了问题：HTTP 404: {"error":{"message":"No active credentials for provider: nvidia","type":"invalid_request_error","code":"model_not_found"}}""",
        )

        robot.launchApp()
        dismissPermissionPrompts()
        robot.waitForChatInput(30_000)

        assertTrue("聊天页应渲染模型路由失败提示卡语义", waitUntil(15_000) {
            device.hasObject(By.descContains("模型路由失败提示"))
        })
        assertTrue("提示卡标题应可见", device.hasObject(By.textContains("模型路由失败")))
        assertTrue("提示卡应显示 provider 前缀", device.hasObject(By.textContains("nvidia")))
        assertTrue("提示卡应显示脱敏诊断指纹", device.hasObject(By.textContains("route.provider")))
        assertTrue("提示卡应显示 HTTP 状态", device.hasObject(By.textContains("HTTP 404")))
        assertTrue("提示卡应显示正式链路口径", device.hasObject(By.textContains("正式聊天 / stream=true")))
        assertTrue("提示卡应给出模型列表下一步", device.hasObject(By.textContains("获取模型列表")))
    }

    @Test
    fun persistedAuthFailureShowsActionableNoticeCard() {
        grantNotificationPermissionIfPossible()
        seedFailureConversation("处理时遇到了问题：HTTP 401: unauthorized invalid api key")

        robot.launchApp()
        dismissPermissionPrompts()
        robot.waitForChatInput(30_000)

        assertTrue("聊天页应渲染鉴权失败提示卡语义", waitUntil(15_000) {
            device.hasObject(By.descContains("鉴权失败提示"))
        })
        assertTrue("提示卡标题应可见", device.hasObject(By.textContains("鉴权失败")))
        assertTrue("提示卡应提示 API Key", device.hasObject(By.textContains("API Key")))
        assertTrue("提示卡应显示鉴权诊断指纹", device.hasObject(By.textContains("auth.key")))
        assertTrue("提示卡无障碍语义应包含诊断指纹", device.hasObject(By.descContains("诊断指纹")))
        assertTrue("提示卡无障碍语义应包含测试口径", device.hasObject(By.descContains("测试口径")))
        assertTrue("提示卡应提示重新测试正式聊天", device.hasObject(By.textContains("重新测试正式聊天")))
    }

    private fun seedFailureConversation(assistantContent: String) {
        val result = device.executeShellCommand(
            listOf(
                "am broadcast",
                "-a com.nuclearboy.app.DEBUG_SEED_CONVERSATION",
                "-n ${robot.appPackageName}/com.nuclearboy.app.diagnostics.DebugConversationSeedReceiver",
                "--es project_id __general__",
                "--ez select_after_write true",
                "--es assistant_content_b64 ${assistantContent.toShellSafeBase64()}",
            ).joinToString(" "),
        )
        assertFalse("调试会话广播不应失败：$result", result.contains("Exception", ignoreCase = true))
        assertTrue("调试会话广播应完成：$result", result.contains("Broadcast completed"))
    }

    private fun grantNotificationPermissionIfPossible() {
        device.executeShellCommand(
            "pm grant ${robot.appPackageName} android.permission.POST_NOTIFICATIONS",
        )
    }

    private fun dismissPermissionPrompts() {
        listOf("始终允许", "允许", "确定", "OK", "Allow").forEach { label ->
            device.findObject(By.text(label))?.let {
                it.click()
                device.waitForIdle(500)
            }
        }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (predicate()) return true
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun String.toShellSafeBase64(): String =
        Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
