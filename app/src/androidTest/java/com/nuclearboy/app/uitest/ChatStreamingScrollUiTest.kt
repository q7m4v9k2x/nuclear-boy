package com.nuclearboy.app.uitest

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.nuclearboy.api.deepseek.ApiKeyManager
import com.nuclearboy.common.AppSettingsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

/**
 * Real front-end regression for streaming scroll behaviour.
 *
 * The target app talks to a local OpenAI-compatible SSE server.  This keeps
 * the test deterministic while still exercising the production HTTP client,
 * ViewModel, Compose LazyColumn and UiAutomator input/scroll path together.
 */
@RunWith(AndroidJUnit4::class)
class ChatStreamingScrollUiTest {
    private val robot = ChatJourneyRobot()

    @Test
    fun streamingChunksDoNotStealHistoryScrollAndBottomActionRecovers() {
        val projectId = "__general__"
        val targetContext = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val apiKeyManager = ApiKeyManager(targetContext)
        val previousProvider = configureDebugProviderSnapshot(apiKeyManager)
        val settingsPrefs = targetContext.getSharedPreferences(
            "nuclearboy_app_settings",
            android.content.Context.MODE_PRIVATE,
        )
        val previousProject = settingsPrefs.getString("last_project_id", null)
        val server = StreamingSseServer()

        try {
            snapshotConversation(projectId)
            seedConversation(projectId)
            robot.configureDebugProvider(
                baseUrl = "http://127.0.0.1:${server.port}/v1",
                model = "ui-scroll-regression",
                apiKey = "ui-test",
            )
            server.start()
            robot.launchApp()
            robot.waitForChatInput(30_000)

            // Seeded history is intentionally much taller than the viewport.
            assertTrue("唯一测试项目的历史 marker 应可见", waitForText("SCROLL_HISTORY_", 10_000))
            robot.enterDraftText("请发送一段可控的流式长回复")
            robot.tapSendButton()
            assertTrue("本地 SSE 服务应收到真实聊天请求", server.requestSeen.await(10, TimeUnit.SECONDS))
            server.append("STREAM_BEGIN\n" + (1..65).joinToString("\n") { "回复第 $it 行：这段长文本验证流式滚动保持。" })
            assertTrue("首个流式 chunk 应进入真实聊天气泡", waitForText("STREAM_BEGIN", 10_000))
            assertFalse("首次长回复应自动跟到底部", robot.device.hasObject(By.desc("到底部")))

            // Move away from the bottom while the one growing assistant item is
            // still streaming. The bottom action is the user-visible evidence
            // that the list remains detached from the tail.
            swipeUpRepeatedly(1)
            assertTrue("上翻历史后应显示‘到底部’按钮", waitForBottomAction(5_000))
            server.append("\nSTREAM_MIDDLE：用户阅读时继续追加。\n".repeat(30))
            Thread.sleep(1_500)
            assertTrue(
                "持续追加 chunk 时仍应停留在用户上翻位置",
                robot.device.hasObject(By.desc("到底部")),
            )

            swipeUpRepeatedly(5)
            server.append("\nSTREAM_HISTORY_UPDATE：离开最新消息后继续追加。\n".repeat(15))
            Thread.sleep(1_000)
            assertTrue("上翻到旧消息仍应保持阅读位置", robot.device.hasObject(By.desc("到底部")))
            val bottom = robot.device.findObject(By.desc("到底部"))
            assertTrue("到底部按钮应可点击", bottom != null)
            bottom?.click()
            assertTrue("点击到底部后应回到底部", waitUntil(5_000) {
                !robot.device.hasObject(By.desc("到底部"))
            })
            server.append("\nSTREAM_TAIL_MARKER\n")
            assertTrue("回到底部后应看到流式回复尾部", waitForText("STREAM_TAIL_MARKER", 10_000))
            server.finish()
            assertTrue("本地 SSE 服务应完成发送", server.completed.await(15, TimeUnit.SECONDS))
            assertTrue("正式聊天应收敛并恢复发送状态", waitUntil(10_000) {
                !robot.device.hasObject(By.desc("停止"))
            })
        } finally {
            server.close()
            runCatching { restoreConversation(projectId) }
            restoreProvider(apiKeyManager, previousProvider)
            if (previousProject == null) {
                settingsPrefs.edit().remove("last_project_id").commit()
            } else {
                AppSettingsStore(targetContext).setLastProjectId(previousProject)
            }
        }
    }

    private fun seedConversation(projectId: String) {
        val history = buildString {
            repeat(180) { append("SCROLL_HISTORY_$it · 历史消息用于验证生成期间上翻位置保持\n") }
        }
        val user = Base64.encodeToString("SCROLL_USER_SEED".toByteArray(), Base64.NO_WRAP)
        val assistant = Base64.encodeToString(history.toByteArray(), Base64.NO_WRAP)
        val result = robot.device.executeShellCommand(
            "am broadcast --receiver-foreground " +
                "-a com.nuclearboy.app.DEBUG_SEED_CONVERSATION " +
                "-n ${robot.appPackageName}/com.nuclearboy.app.diagnostics.DebugConversationSeedReceiver " +
                "--es project_id $projectId --ez select_after_write true " +
                "--es user_content_b64 $user --es assistant_content_b64 $assistant",
        )
        assertFalse("滚动测试会话种子不应失败：$result", result.contains("Exception", true))
        assertTrue("滚动测试会话种子广播应完成：$result", result.contains("Broadcast completed"))
    }

    private fun snapshotConversation(projectId: String) {
        val result = robot.device.executeShellCommand(
            "am broadcast --receiver-foreground -a com.nuclearboy.app.DEBUG_SNAPSHOT_CONVERSATION " +
                "-n ${robot.appPackageName}/com.nuclearboy.app.diagnostics.DebugConversationSeedReceiver " +
                "--es project_id $projectId",
        )
        assertFalse("滚动测试会话快照不应失败：$result", result.contains("Exception", true))
    }

    private fun restoreConversation(projectId: String) {
        val result = robot.device.executeShellCommand(
            "am broadcast --receiver-foreground -a com.nuclearboy.app.DEBUG_RESTORE_CONVERSATION " +
                "-n ${robot.appPackageName}/com.nuclearboy.app.diagnostics.DebugConversationSeedReceiver " +
                "--es project_id $projectId",
        )
        assertFalse("滚动测试会话恢复不应失败：$result", result.contains("Exception", true))
    }

    private fun swipeUpRepeatedly(count: Int) {
        repeat(count) {
            val w = robot.device.displayWidth
            val h = robot.device.displayHeight
            robot.device.swipe(w / 2, (h * 0.30).toInt(), w / 2, (h * 0.68).toInt(), 40)
            Thread.sleep(250)
        }
    }

    private fun waitForBottomAction(timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { robot.device.hasObject(By.desc("到底部")) }

    private fun waitForText(text: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { robot.device.hasObject(By.textContains(text)) }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (predicate()) return true
            Thread.sleep(200)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private data class ProviderSnapshot(
        val activeModelId: String,
        val customModels: List<ApiKeyManager.CustomModelConfig>,
    )

    private fun configureDebugProviderSnapshot(manager: ApiKeyManager): ProviderSnapshot =
        ProviderSnapshot(
            activeModelId = manager.getActiveModelId(),
            customModels = manager.state.value.customModels.mapNotNull { manager.getCustomModelConfig(it.id) },
        )

    private fun restoreProvider(manager: ApiKeyManager, snapshot: ProviderSnapshot) {
        val oldIds = snapshot.customModels.map { it.id }.toSet()
        manager.state.value.customModels.filterNot { it.id in oldIds }
            .forEach { manager.deleteCustomModel(it.id) }
        snapshot.customModels.forEach {
            manager.saveCustomModel(
                existingId = it.id,
                displayName = it.displayName,
                baseUrl = it.baseUrl,
                modelName = it.modelName,
                protocol = it.protocol,
                endpointMode = it.endpointMode,
                apiKey = it.apiKey,
                selectAfterSave = false,
            )
        }
        manager.selectModel(snapshot.activeModelId)
    }

    private class StreamingSseServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val port: Int get() = socket.localPort
        val requestSeen = CountDownLatch(1)
        val completed = CountDownLatch(1)
        private var worker: Thread? = null
        private val chunks = LinkedBlockingQueue<String>()
        @Volatile private var client: Socket? = null

        fun append(text: String) { chunks.put(text) }
        fun finish() { chunks.put("__FINISH__") }

        fun start() {
            worker = Thread {
                runCatching {
                    socket.accept().use { client ->
                        this.client = client
                        readRequest(client)
                        requestSeen.countDown()
                        val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
                        writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                        writer.flush()
                        while (true) {
                            val text = chunks.poll(60, TimeUnit.SECONDS) ?: error("Test did not advance stream")
                            if (text == "__FINISH__") break
                            writer.write("data: ${chunkJson(text)}\n\n")
                            writer.flush()
                        }
                        writer.write("data: ${finishJson()}\n\ndata: [DONE]\n\n")
                        writer.flush()
                        completed.countDown()
                    }
                }
            }.apply { name = "ui-scroll-sse"; isDaemon = true; start() }
        }

        private fun readRequest(client: Socket) {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
        }

        override fun close() {
            runCatching { socket.close() }
            runCatching { client?.close() }
            worker?.interrupt()
            worker?.join(1_000)
        }

        private fun chunkJson(text: String): String =
            "{\"id\":\"ui-scroll\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"${escape(text)}\"},\"finish_reason\":null}]}"

        private fun finishJson(): String =
            "{\"id\":\"ui-scroll\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"

        private fun escape(text: String): String =
            text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }
}
