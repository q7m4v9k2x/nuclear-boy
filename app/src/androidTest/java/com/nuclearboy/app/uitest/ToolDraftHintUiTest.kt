package com.nuclearboy.app.uitest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.nuclearboy.api.deepseek.ApiKeyManager
import com.nuclearboy.api.deepseek.ApiKeyManager.CustomModelConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolDraftHintUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val robot = ChatJourneyRobot()

    private data class ProviderSnapshot(
        val activeModelId: String,
        val customModels: List<CustomModelConfig>,
    )

    @Test
    fun customProviderToolDraftShowsPreSendHint() {
        withTemporaryToolDraftProvider {
            robot.resetConversationHistory()
            robot.launchApp()

            robot.waitForChatInput(30_000)
            robot.enterDraftText("帮我把这个订单取消掉")

            assertTrue("工具型草稿应在发送前显示工具能力预警语义", waitUntil(10_000) {
                device.hasObject(By.descContains("工具能力预警")) ||
                    device.hasObject(By.textContains("可能需要工具能力"))
            })
            assertTrue("工具型草稿预警标题应可见", device.hasObject(By.textContains("可能需要工具能力")))
            assertTrue("API 配置类草稿应显示接口调用范围", device.hasObject(By.textContains("调用接口/API")))
            assertTrue("API 配置类草稿应提示无法真实调用接口", device.hasObject(By.textContains("不能真实调用接口")))

            val appendGuard = device.findObject(By.desc("追加防假执行提示"))
                ?: device.findObject(By.text("追加防假执行提示"))
            assertTrue("工具型草稿预警应提供追加防假执行提示操作", appendGuard != null)
            appendGuard?.click()

            val input = robot.waitForChatInput(10_000)
            assertTrue("追加后输入框应包含防假执行约束", waitUntil(10_000) {
                input.text.orEmpty().contains("工具受限，未真实执行") &&
                    input.text.orEmpty().contains("不要编造已读取")
            })

            val sendButton = waitForObject("发送消息按钮", 10_000) {
                device.findObject(By.desc("发送消息"))
            }
            tapObject(sendButton)

            assertTrue("发送后聊天流应留下工具能力证据提示", waitUntil(10_000) {
                device.hasObject(By.textContains("本轮工具能力提示")) &&
                    device.hasObject(By.textContains("接口/API 调用记录"))
            })
        }
    }

    @Test
    fun apiLearningQuestionDoesNotShowToolDraftHint() {
        withTemporaryToolDraftProvider {
            robot.resetConversationHistory()
            robot.launchApp()

            robot.waitForChatInput(30_000)
            robot.enterDraftText("请调用接口时怎么带参数")
            Thread.sleep(1_500)

            assertTrue(
                "学习型 API 问题不应显示工具能力预警",
                !hasToolDraftWarning(),
            )
        }
    }

    private fun hasToolDraftWarning(): Boolean =
        device.hasObject(By.descContains("工具能力预警")) ||
            device.hasObject(By.textContains("可能需要工具能力"))

    private fun tapObject(target: UiObject2) {
        val bounds = target.visibleBounds
        val result = device.executeShellCommand("su -c input tap ${bounds.centerX()} ${bounds.centerY()}")
        assertTrue(
            "root 点击不应失败：$result",
            !result.contains("SecurityException", ignoreCase = true) &&
                !result.contains("Permission denied", ignoreCase = true),
        )
        device.waitForIdle(1_000)
    }

    private fun waitForObject(
        label: String,
        timeoutMs: Long,
        finder: () -> UiObject2?,
    ): UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            finder()?.let { return it }
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        throw AssertionError("等待 $label 超时")
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (predicate()) return true
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun withTemporaryToolDraftProvider(block: () -> Unit) {
        val apiKeyManager = ApiKeyManager(instrumentation.targetContext)
        val previous = ProviderSnapshot(
            activeModelId = apiKeyManager.getActiveModelId(),
            customModels = apiKeyManager.state.value.customModels.mapNotNull { state ->
                apiKeyManager.getCustomModelConfig(state.id)
            },
        )
        try {
            apiKeyManager.setCustomProviderConfig(
                // An invalid scheme makes OkHttp fail before any network wait;
                // the test only needs a deterministic terminal error to verify
                // that the post-send evidence reminder remains visible.
                baseUrl = "mock://tool-draft-hint/v1",
                modelName = "local/tool-draft-hint",
                apiKey = "",
            )
            block()
        } finally {
            restoreProvider(apiKeyManager, previous)
        }
    }

    private fun restoreProvider(
        apiKeyManager: ApiKeyManager,
        previous: ProviderSnapshot,
    ) {
        val previousIds = previous.customModels.map { it.id }.toSet()
        apiKeyManager.state.value.customModels
            .filterNot { it.id in previousIds }
            .forEach { apiKeyManager.deleteCustomModel(it.id) }
        previous.customModels.forEach { previousConfig ->
            apiKeyManager.saveCustomModel(
                existingId = previousConfig.id,
                displayName = previousConfig.displayName,
                baseUrl = previousConfig.baseUrl,
                modelName = previousConfig.modelName,
                protocol = previousConfig.protocol,
                endpointMode = previousConfig.endpointMode,
                apiKey = previousConfig.apiKey,
                selectAfterSave = false,
            )
        }
        apiKeyManager.selectModel(previous.activeModelId)
    }
}
