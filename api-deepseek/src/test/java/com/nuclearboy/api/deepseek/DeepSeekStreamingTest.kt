package com.nuclearboy.api.deepseek

import com.nuclearboy.common.ModelTier
import com.nuclearboy.common.ThinkingMode
import com.nuclearboy.common.AppError
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Regression coverage for the HTTP/SSE stream boundary. */
class DeepSeekStreamingTest {

    @Test(timeout = 5_000)
    fun `malformed custom endpoint is reported without retrying`() = runBlocking {
        val client = DeepSeekApiClient(
            apiKeyProvider = { "test-key" },
            baseUrlProvider = { "mock://missing-evidence/v1" },
            modelOverrideProvider = { "mock-model" },
        )
        try {
            val events = client.streamChat(
                messages = listOf(MessageDto(role = "user", content = "ping")),
                modelTier = ModelTier.V4_FLASH,
                thinkingMode = ThinkingMode.DISABLED,
            ).toList()

            assertFalse("a malformed URL must not trigger network retries", events.any { it is StreamEvent.ContentReset })
            val error = events.filterIsInstance<StreamEvent.Error>().single()
            assertEquals(AppError.InvalidRequest, error.appError)
        } finally {
            client.close()
        }
    }

    @Test(timeout = 15_000)
    fun `truncated SSE is retried and does not become a false completion`() = runBlocking {
        val partial = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"
        val complete = "data:{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"recovered\"},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n"
        RawHttpFixture(listOf(partial, complete)).use { fixture ->
            val client = DeepSeekApiClient(
                apiKeyProvider = { "test-key" },
                baseUrlProvider = { fixture.baseUrl },
                modelOverrideProvider = { "test-model" },
            )
            try {
                val events = client.streamChat(
                    messages = listOf(MessageDto(role = "user", content = "ping")),
                    modelTier = ModelTier.V4_FLASH,
                    thinkingMode = ThinkingMode.DISABLED,
                ).toList()

                assertTrue("the failed first attempt must announce a reset", events.any { it is StreamEvent.ContentReset })
                assertEquals("one successful completion is expected", 1, events.count { it is StreamEvent.Complete })
                assertFalse("a recovered stream must not emit a terminal error", events.any { it is StreamEvent.Error })
                assertTrue(
                    "the second attempt must be parsed after the reset",
                    events.filterIsInstance<StreamEvent.Content>().any { it.text == "recovered" },
                )
            } finally {
                client.close()
            }
        }
    }

    /** Minimal HTTP/1.1 fixture; it never logs or exposes request headers. */
    private class RawHttpFixture(
        private val bodies: List<String>,
    ) : java.io.Closeable {
        private val server = ServerSocket(0)
        private val worker = Thread {
            try {
                bodies.forEach { body ->
                    server.accept().use { socket ->
                        readRequest(socket)
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        val headers = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: text/event-stream\r\n")
                            append("Content-Length: ${bytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }.toByteArray(StandardCharsets.US_ASCII)
                        socket.getOutputStream().apply {
                            write(headers)
                            write(bytes)
                            flush()
                        }
                    }
                }
            } finally {
                runCatching { server.close() }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"

        override fun close() {
            runCatching { server.close() }
            worker.join(2_000)
        }

        private fun readRequest(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
        }
    }
}
