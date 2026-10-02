package com.nuclearboy.app.uitest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolMissingEvidenceReviewUiTest {
    private val robot = ChatJourneyRobot()

    @Test
    fun toolRequestWithoutVisibleToolEvidenceShowsPostTurnReview() {
        robot.resetConversationHistory()
        val previousProvider = robot.configureDebugProvider(
            baseUrl = "mock://missing-evidence/v1",
            model = "mock/missing-evidence",
            // Custom providers may intentionally use an empty key; this mock
            // endpoint never authenticates and the test must not carry a key.
            apiKey = "",
        )
        try {
            robot.launchApp()
            robot.waitForChatInput(30_000)
            robot.sendPromptAndWait(
                prompt = "请读取 demo.md 并运行测试",
                label = "工具型请求缺少证据后置复核",
                timeoutMs = 45_000,
                failOnKnownChatFailure = false,
            )

            assertTrue("工具型请求缺少工具证据时应追加本轮结果复核", waitUntil(15_000) {
                robot.device.hasObject(By.descContains("结果复核提示")) &&
                    robot.device.hasObject(By.descContains("测试口径")) &&
                    robot.device.hasObject(By.textContains("本轮结果复核")) &&
                    robot.device.hasObject(By.textContains("未看到工具执行卡")) &&
                    robot.device.hasObject(By.textContains("不要把本轮回复当作已完成结果")) &&
                    robot.device.hasObject(By.textContains("tool.evidence.missing")) &&
                    robot.device.hasObject(By.textContains("正式聊天 / stream=true")) &&
                    robot.device.hasObject(By.textContains("tools/function_call"))
            })
        } finally {
            robot.restoreDebugProvider(previousProvider)
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
