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
class ToolLimitNoticeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val robot = ChatJourneyRobot()

    @Test
    fun persistedToolLimitMessageShowsNoticeCard() {
        grantNotificationPermissionIfPossible()
        seedToolLimitConversation()

        robot.launchApp()
        dismissPermissionPrompts()
        robot.waitForChatInput(30_000)

        assertTrue("聊天页应渲染工具受限提示卡语义", waitUntil(15_000) {
            device.hasObject(By.descContains("工具受限提示"))
        })
        assertTrue("提示卡标题应可见", device.hasObject(By.textContains("工具受限")))
        assertTrue("提示卡应说明模型连接与工具协议状态", device.hasObject(By.textContains("模型连接正常")))
        assertTrue("提示卡应显示工具协议诊断指纹", device.hasObject(By.textContains("tool.protocol")))
        assertTrue("提示卡应显示正式链路口径", device.hasObject(By.textContains("正式聊天 / stream=true")))
        assertTrue("提示卡无障碍语义应包含测试口径", device.hasObject(By.descContains("测试口径")))
    }

    private fun seedToolLimitConversation() {
        val assistantContent = "I can't verify the fix or run the tests because I don't have a runtime environment for your app here."
        val assistantContentB64 = Base64.encodeToString(
            assistantContent.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val result = device.executeShellCommand(
            "am broadcast -a com.nuclearboy.app.DEBUG_SEED_CONVERSATION " +
                "-n ${robot.appPackageName}/com.nuclearboy.app.diagnostics.DebugConversationSeedReceiver " +
                "--es project_id __general__ --ez select_after_write true " +
                "--es assistant_content_b64 $assistantContentB64",
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
}
