package com.nuclearboy.app.uitest

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationHistoryUiTest {
    private val robot = ChatJourneyRobot()

    @Test
    fun startingNewConversationArchivesAndRestoresPreviousMessages() {
        seedConversation()
        robot.launchApp()
        robot.waitForChatInput(30_000)
        assertTrue(
            "启动后应加载种入的历史消息",
            waitForText("历史归档测试", 10_000) != null,
        )

        val newChat = robot.device.findObject(By.desc("新对话"))
        assertTrue("聊天页应提供新对话按钮", newChat != null)
        newChat?.click()

        // Compose commits the dialog on the next frame; wait instead of reading
        // the hierarchy immediately after UiObject2.click().
        val confirm = waitForExactText("新对话", 5_000)
        assertTrue("新对话按钮应弹出确认框", confirm != null)
        confirm?.click()
        robot.device.waitForIdle(1_000)

        assertFalse(
            "开始新对话后旧消息不应继续显示在当前聊天",
            robot.device.hasObject(By.textContains("历史归档测试")),
        )

        val history = robot.device.findObject(By.desc("历史对话"))
        assertTrue("聊天页应提供历史对话入口", history != null)
        history.click()

        val archived = waitForText("历史归档测试", 10_000)
        assertTrue("新对话后应能在历史列表看到旧对话", archived != null)
        archived?.click()
        robot.device.waitForIdle(1_000)

        assertTrue("选择历史对话后应恢复原消息", robot.device.hasObject(By.textContains("历史归档测试")))
    }

    private fun seedConversation() {
        val user = Base64.encodeToString("历史归档测试：请保留这段上下文".toByteArray(), Base64.NO_WRAP)
        val assistant = Base64.encodeToString("已保存".toByteArray(), Base64.NO_WRAP)
        val result = robot.device.executeShellCommand(
            "am broadcast --receiver-foreground " +
                "-a com.nuclearboy.app.DEBUG_SEED_CONVERSATION " +
                "-n ${robot.appPackageName}/com.nuclearboy.app.diagnostics.DebugConversationSeedReceiver " +
                "--es project_id __general__ --ez select_after_write true " +
                "--es user_content_b64 $user --es assistant_content_b64 $assistant",
        )
        assertFalse("历史测试种子广播不应失败：$result", result.contains("Exception", ignoreCase = true))
        assertTrue("历史测试种子广播应完成：$result", result.contains("Broadcast completed"))
    }

    private fun waitForText(text: String, timeoutMs: Long): androidx.test.uiautomator.UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            robot.device.findObject(By.textContains(text))?.let { return it }
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun waitForExactText(text: String, timeoutMs: Long): androidx.test.uiautomator.UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            robot.device.findObject(By.text(text))?.let { return it }
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        return null
    }
}
