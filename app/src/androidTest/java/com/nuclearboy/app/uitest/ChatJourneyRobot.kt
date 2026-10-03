package com.nuclearboy.app.uitest

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.nuclearboy.api.deepseek.ApiKeyManager
import com.nuclearboy.api.deepseek.ProviderEndpointMode
import com.nuclearboy.api.deepseek.ProviderProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

data class ChatTurnEvidence(
    val label: String,
    val assistantCountBefore: Int,
    val assistantCountAfter: Int,
    val stopWasVisible: Boolean,
    val toolMarkerVisible: Boolean,
)

class ChatJourneyRobot {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val apiKeyManager by lazy { ApiKeyManager(instrumentation.targetContext) }
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val appPackageName: String = "com.nuclearboy.app.debug"

    data class DebugProviderSnapshot(
        val activeModelId: String,
        val customModels: List<ApiKeyManager.CustomModelConfig>,
    )

    fun prepareFreshConversation() {
        resetConversationHistory()
        configureProviderFromInstrumentationArgs()
        launchApp()
        dismissCommonSystemPrompts()
        waitForChatInput(30_000)
    }

    fun configureProviderFromInstrumentationArgs() {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("nbBaseUrl")?.trim().orEmpty()
        val model = args.getString("nbModel")?.trim().orEmpty()
        if (baseUrl.isBlank() || model.isBlank()) return
        val apiKey = args.getString("nbApiKey")?.trim().orEmpty()
        val endpointMode = args.getString("nbEndpointMode")?.trim().orEmpty().ifBlank { "auto" }
        configureDebugProvider(baseUrl, model, apiKey, endpointMode)
    }

    fun configureDebugProvider(
        baseUrl: String,
        model: String,
        apiKey: String,
        endpointMode: String = "auto",
    ): DebugProviderSnapshot {
        val snapshot = DebugProviderSnapshot(
            activeModelId = apiKeyManager.getActiveModelId(),
            customModels = apiKeyManager.state.value.customModels.mapNotNull { state ->
                apiKeyManager.getCustomModelConfig(state.id)
            },
        )
        val mode = when (endpointMode.trim().lowercase()) {
            "exact", "full", "完整地址" -> ProviderEndpointMode.EXACT
            else -> ProviderEndpointMode.AUTO
        }
        // API keys stay inside ApiKeyManager's encrypted preferences.  Do not
        // put them into an `am broadcast` command where shell history/logcat
        // could expose them.
        apiKeyManager.saveCustomModel(
            existingId = apiKeyManager.state.value.customModels.firstOrNull {
                it.modelName == model || it.baseUrl == baseUrl
            }?.id,
            displayName = model,
            baseUrl = baseUrl,
            modelName = model,
            protocol = ProviderProtocol.OPENAI,
            endpointMode = mode,
            apiKey = apiKey,
            selectAfterSave = true,
        )
        return snapshot
    }

    fun restoreDebugProvider(snapshot: DebugProviderSnapshot) {
        val previousIds = snapshot.customModels.map { it.id }.toSet()
        apiKeyManager.state.value.customModels
            .filterNot { it.id in previousIds }
            .forEach { apiKeyManager.deleteCustomModel(it.id) }
        snapshot.customModels.forEach { config ->
            apiKeyManager.saveCustomModel(
                existingId = config.id,
                displayName = config.displayName,
                baseUrl = config.baseUrl,
                modelName = config.modelName,
                protocol = config.protocol,
                endpointMode = config.endpointMode,
                apiKey = config.apiKey,
                selectAfterSave = false,
            )
        }
        apiKeyManager.selectModel(snapshot.activeModelId)
    }

    fun resetConversationHistory() {
        val result = device.executeShellCommand(
            "am broadcast --receiver-foreground -a com.nuclearboy.app.DEBUG_CLEAR_CONVERSATION " +
                "-n $appPackageName/com.nuclearboy.app.diagnostics.DebugConversationSeedReceiver " +
                "--es project_id ${DEBUG_SEED_PROJECT_ID.shellSingleQuoted()} " +
                "--ez select_after_write true",
        )
        assertFalse("调试会话清理不应失败：$result", result.contains("Exception", ignoreCase = true))
        assertTrue("调试会话清理广播应完成：$result", result.contains("Broadcast completed"))
    }

    fun launchApp() {
        wakeAndUnlockScreen()
        val activityName = "$appPackageName/com.nuclearboy.app.MainActivity"
        // MainActivity is singleTask, so a plain `am start` only delivers onNewIntent
        // and leaves the old ViewModel (and its in-memory history) alive.  Recreate
        // the task with NEW_TASK|CLEAR_TASK instead of force-stopping the package:
        // instrumentation runs in the debug app process on this ROM, so force-stop
        // would kill the test host itself.
        val launchResult = device.executeShellCommand("am start -W -f 0x10008000 -n $activityName")
        assertFalse(
            "目标 App 启动命令不应失败：$launchResult",
            launchResult.contains("Error", ignoreCase = true) ||
                launchResult.contains("Exception", ignoreCase = true),
        )
        device.waitForIdle(5_000)
        dismissCommonSystemPrompts()
        assertTrue("启动后目标 App 应处于前台：${focusedWindowSummary()}", waitUntil(10_000) {
            dismissCommonSystemPrompts()
            isAppInForeground()
        })
    }

    fun waitForChatInput(timeoutMs: Long): UiObject2 =
        waitForObject("聊天输入框", timeoutMs) {
            device.findObject(By.clazz("android.widget.EditText"))
                ?: device.findObject(By.desc("聊天输入框"))
                ?: device.findObject(By.textContains("输入指令"))
                ?: device.findObject(By.textContains("和核弹男孩对话"))
        }

    fun enterDraftText(text: String) {
        focusChatInputWithKeyboard()
        clearInputText()
        setInputText(text)
        assertTrue("输入框应持有测试草稿", waitUntil(15_000) {
            chatInputText()?.contains(text) == true
        })
    }

    /** Sends the already populated draft through the same visible button path
     * used by the user, without waiting for the network operation to finish. */
    fun tapSendButton(): UiObject2 {
        val send = waitForObject("发送消息按钮", 10_000) {
            device.findObject(By.desc("发送消息"))
        }
        tapObject(send)
        return send
    }

    fun sendPromptAndWait(
        prompt: String,
        label: String,
        timeoutMs: Long = 150_000,
        requireVisibleAssistantIncrease: Boolean = true,
        failOnKnownChatFailure: Boolean = true,
    ): ChatTurnEvidence {
        val assistantCountBefore = assistantMessageCount()
        focusChatInputWithKeyboard()
        clearInputText()
        setInputText(prompt)

        assertTrue("$label 输入后输入框应持有测试消息", waitUntil(15_000) {
            chatInputText()?.contains(prompt) == true
        })

        val send = waitForObject("$label 发送消息按钮", 10_000) {
            device.findObject(By.desc("发送消息"))
        }
        tapObject(send)

        assertTrue("$label 点击发送后输入框应被清空，不能停留在草稿态", waitUntil(10_000) {
            chatInputText()?.contains(prompt) != true
        })

        val stopWasVisible = waitForChatCompletion(
            label = label,
            assistantCountBefore = assistantCountBefore,
            timeoutMs = timeoutMs,
            failOnKnownChatFailure = failOnKnownChatFailure,
        )
        if (failOnKnownChatFailure) assertNoKnownChatFailure()
        val assistantCountAfter = assistantMessageCount()
        if (requireVisibleAssistantIncrease) {
            assertTrue(
                "$label 正式聊天结束后应新增助手消息，before=$assistantCountBefore after=$assistantCountAfter",
                assistantCountAfter > assistantCountBefore,
            )
        } else {
            assertTrue(
                "$label 正式聊天结束后底部应可见助手消息，after=$assistantCountAfter",
                assistantCountAfter > 0,
            )
        }

        return ChatTurnEvidence(
            label = label,
            assistantCountBefore = assistantCountBefore,
            assistantCountAfter = assistantCountAfter,
            stopWasVisible = stopWasVisible,
            toolMarkerVisible = hasVisibleToolMarker(),
        )
    }

    fun assertNoKnownChatFailure() {
        knownFailures.forEach { failureText ->
            assertFalse(
                "界面不应出现已知聊天失败文案：$failureText",
                device.hasObject(By.textContains(failureText)),
            )
        }
    }

    fun assertNoSelfReportedConversationProblem() {
        selfReportedProblems.forEach { problemText ->
            assertFalse(
                "多轮真实对话不应让模型自报上下文或工具结果存在问题：$problemText",
                device.hasObject(By.textContains(problemText)),
            )
        }
    }

    fun assertAppInForeground(stage: String) {
        assertTrue("$stage，当前前台必须是目标 App：${focusedWindowSummary()}", isAppInForeground())
    }

    private fun wakeAndUnlockScreen() {
        runCatching { device.wakeUp() }
        executeShellInput("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("svc power stayon true")
        device.waitForIdle(2_000)
    }

    private fun dismissCommonSystemPrompts() {
        listOf("始终允许", "仅在使用中允许", "允许", "确定", "同意", "继续", "OK", "Allow").forEach { label ->
            device.findObject(By.text(label))?.let(::tapPromptObject)
        }
    }

    private fun focusChatInputWithKeyboard() {
        assertAppInForeground("聚焦聊天输入前")
        repeat(24) {
            if (focusedInput() != null) return
            executeShellInput("input keyevent KEYCODE_DPAD_DOWN")
            Thread.sleep(120)
        }
        if (focusedInput() != null) return

        val input = waitForChatInput(5_000)
        tapObject(input)
        assertTrue("聊天输入框应能通过键盘导航或点击获得焦点", waitUntil(5_000) {
            focusedInput() != null
        })
    }

    private fun tapObject(target: UiObject2) {
        assertAppInForeground("执行点击前")
        tapPromptObject(target)
    }

    private fun tapPromptObject(target: UiObject2) {
        val bounds = target.visibleBounds
        executeShellInput("input tap ${bounds.centerX()} ${bounds.centerY()}")
        device.waitForIdle(1_000)
    }

    private fun setInputText(text: String) {
        assertAppInForeground("输入文本前")
        val input = focusedInput() ?: waitForChatInput(5_000)
        runCatching { input.setText(text) }
        if (waitUntil(2_000) { chatInputText()?.contains(text) == true }) {
            device.waitForIdle(500)
            return
        }

        if (text.isShellInputSafe()) {
            shellInputText(text)
        }
    }

    private fun shellInputText(text: String) {
        val safeText = text.replace(" ", "%s")
        executeShellInput("input text $safeText")
        device.waitForIdle(1_000)
    }

    private fun clearInputText() {
        assertAppInForeground("清空输入框前")
        val input = focusedInput() ?: waitForChatInput(5_000)
        runCatching { input.setText("") }
        if (waitUntil(2_000) { chatInputText().isNullOrEmpty() }) return

        executeShellInput("input keyevent KEYCODE_MOVE_END")
        repeat(96) {
            executeShellInput("input keyevent KEYCODE_DEL")
        }
        device.waitForIdle(1_000)
    }

    private fun executeShellInput(command: String) {
        // UiAutomator's shell identity can inject input on the test AVD. Do not
        // require a rooted emulator: `su -c` is unavailable on standard AVDs and
        // made the end-to-end gate fail before exercising the app.
        val result = device.executeShellCommand(command)
        assertFalse(
            "shell input 命令不应被系统拦截或失败：$result",
            result.contains("SecurityException", ignoreCase = true) ||
                result.contains("INJECT_EVENTS", ignoreCase = true) ||
                result.contains("not found", ignoreCase = true) ||
                result.contains("Permission denied", ignoreCase = true) ||
                result.contains("inaccessible", ignoreCase = true),
        )
    }

    private fun waitForChatCompletion(
        label: String,
        assistantCountBefore: Int,
        timeoutMs: Long,
        failOnKnownChatFailure: Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var stopWasVisible = false
        do {
            if (failOnKnownChatFailure) assertNoKnownChatFailure()
            val assistantCountAfter = assistantMessageCount()
            val processing = device.hasObject(By.desc("停止"))
            stopWasVisible = stopWasVisible || processing
            if (!processing && assistantCountAfter > assistantCountBefore) {
                return stopWasVisible
            }
            if (!processing && stopWasVisible && assistantCountAfter > 0) {
                return true
            }
            Thread.sleep(500)
        } while (System.currentTimeMillis() < deadline)
        fail("$label 正式聊天应在 ${timeoutMs / 1000} 秒内完成并产生助手回复")
        throw AssertionError("unreachable")
    }

    private fun assistantMessageCount(): Int =
        device.findObjects(By.descContains("核弹男孩消息")).size

    private fun hasVisibleToolMarker(): Boolean =
        device.hasObject(By.textContains("list_directory")) ||
            device.hasObject(By.textContains("read_file")) ||
            device.hasObject(By.textContains("完成")) && device.hasObject(By.desc("展开输出"))

    private fun focusedInput(): UiObject2? =
        device.findObject(By.focused(true).clazz("android.widget.EditText"))

    private fun chatInputText(): String? =
        focusedInput()?.text ?: device.findObject(By.clazz("android.widget.EditText"))?.text

    private fun isAppInForeground(): Boolean =
        focusedWindowSummary().contains(appPackageName)

    private fun focusedWindowSummary(): String {
        val dump = device.executeShellCommand("dumpsys window")
        val focusLines = dump.lineSequence()
            .filter {
                it.contains("mCurrentFocus") ||
                    it.contains("mFocusedApp") ||
                    it.contains("mInputMethodTarget")
            }
            .take(4)
            .joinToString(" | ") { it.trim() }
        return focusLines.take(500)
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (predicate()) return true
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return false
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
        fail("等待 $label 超时")
        throw AssertionError("unreachable")
    }

    private fun String.isShellInputSafe(): Boolean =
        all { it.code in 0x21..0x7E && it !in shellSpecialChars } && isNotBlank()

    private fun String.shellSingleQuoted(): String =
        "'" + replace("'", "'\\''") + "'"

    private companion object {
        /** Project selected by all seeded debug conversations unless a test overrides it. */
        const val DEBUG_SEED_PROJECT_ID = "__general__"

        private val shellSpecialChars = setOf('"', '\'', '\\', ';', '&', '|', '<', '>', '(', ')', '$', '`')
        private val knownFailures = listOf(
            "没能生成回复",
            "请求格式有误",
            "内部数据可能不一致",
            "模型路由失败",
            "正式聊天请求失败",
            "处理时遇到了问题",
            "需要配置 DeepSeek API Key",
            "没有可用模型",
            "空回复",
        )
        private val selfReportedProblems = listOf(
            "上下文有问题",
            "工具结果有问题",
            "存在问题",
            "误报",
            "存在不一致",
            "自相矛盾",
        )
    }
}
