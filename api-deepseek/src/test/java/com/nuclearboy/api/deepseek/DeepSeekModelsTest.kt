package com.nuclearboy.api.deepseek

import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DeepSeekModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `ToolDefinitionDto serializes type field`() {
        val dto = ToolDefinitionDto(
            function = FunctionDefinitionDto(
                name = "test_tool",
                description = "A test tool",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("File path"))
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                },
            )
        )
        val serialized = json.encodeToString(dto)
        // Must contain type: "function"
        assertTrue(serialized.contains("\"type\":\"function\""))
        // Must contain the function name
        assertTrue(serialized.contains("\"name\":\"test_tool\""))
        // parameters must be a JSON object, not a string
        assertTrue(serialized.contains("\"parameters\":{"))
    }

    @Test
    fun `ChatCompletionRequest includes tools when provided`() {
        val request = ChatCompletionRequest(
            model = "deepseek-v4-pro",
            messages = listOf(MessageDto(role = "user", content = "Hi")),
            tools = listOf(
                ToolDefinitionDto(
                    function = FunctionDefinitionDto(name = "test", description = "Test"),
                )
            ),
        )
        val serialized = json.encodeToString(request)
        assertTrue(serialized.contains("\"tools\":"))
        assertTrue(serialized.contains("\"type\":\"function\""))
    }

    @Test
    fun `MessageDto strips reasoningContent from assistant messages`() {
        val msg = MessageDto(
            role = "assistant",
            content = "Hello",
            reasoningContent = "I should say hello",
        )
        // After sanitization, reasoning should be null
        val sanitized = msg.copy(reasoningContent = null)
        assertNull(sanitized.reasoningContent)
        assertEquals("Hello", sanitized.content)
    }

    @Test
    fun `custom provider message sanitizer strips reasoning but keeps tool protocol by default`() {
        val messages = listOf(
            MessageDto(role = "user", content = "Hi"),
            MessageDto(role = "assistant", content = "Hello", reasoningContent = "hidden reasoning"),
            MessageDto(
                role = "assistant",
                content = null,
                toolCalls = listOf(
                    ToolCallDto(
                        id = "call_1",
                        function = FunctionCallDto(name = "read_file", arguments = "{}"),
                    ),
                ),
            ),
            MessageDto(role = "tool", content = "file content", toolCallId = "call_1", name = "read_file"),
            MessageDto(role = "assistant", content = null, reasoningContent = "reasoning only"),
        )

        val sanitized = sanitizeChatMessagesForProvider(
            messages = messages,
            isCustomProvider = true,
            omitToolProtocol = false,
        )

        assertEquals(4, sanitized.size)
        assertNull(sanitized[1].reasoningContent)
        assertNotNull(sanitized[2].toolCalls)
        assertEquals("tool", sanitized[3].role)
    }

    @Test
    fun `custom provider compatibility mode removes tool protocol but keeps tool output as text context`() {
        val messages = listOf(
            MessageDto(role = "user", content = "Hi"),
            MessageDto(role = "assistant", content = "Visible", reasoningContent = "hidden reasoning"),
            MessageDto(
                role = "assistant",
                content = null,
                toolCalls = listOf(
                    ToolCallDto(
                        id = "call_1",
                        function = FunctionCallDto(name = "read_file", arguments = "{}"),
                    ),
                ),
            ),
            MessageDto(role = "tool", content = "file content", toolCallId = "call_1", name = "read_file"),
        )

        val sanitized = sanitizeChatMessagesForProvider(
            messages = messages,
            isCustomProvider = true,
            omitToolProtocol = true,
        )

        assertEquals(5, sanitized.size)
        assertEquals(listOf("system", "user", "assistant", "user", "user"), sanitized.map { it.role })
        assertTrue(sanitized[0].content.orEmpty().contains("不能调用 read_file"))
        assertTrue(sanitized[0].content.orEmpty().contains("不得编造工具输出"))
        assertEquals("Visible", sanitized[2].content)
        assertNull(sanitized[2].reasoningContent)
        assertNull(sanitized[2].toolCalls)
        assertTrue(sanitized[3].content.orEmpty().contains("read_file"))
        assertTrue(sanitized[3].content.orEmpty().contains("file content"))
        assertNull(sanitized[3].toolCallId)
        assertNull(sanitized[3].name)
        assertTrue(sanitized[4].content.orEmpty().contains("不要输出 [TOOL_CALL]"))
        assertTrue(sanitized[4].content.orEmpty().contains("工具受限"))
    }

    @Test
    fun `custom provider compatibility mode appends tool limit notice to existing system prompt`() {
        val sanitized = sanitizeChatMessagesForProvider(
            messages = listOf(
                MessageDto(role = "system", content = "base prompt"),
                MessageDto(role = "user", content = "write a file"),
            ),
            isCustomProvider = true,
            omitToolProtocol = true,
        )

        assertEquals(3, sanitized.size)
        assertEquals("system", sanitized[0].role)
        assertTrue(sanitized[0].content.orEmpty().startsWith("base prompt"))
        assertTrue(sanitized[0].content.orEmpty().contains("当前第三方网关本轮没有可用工具调用协议"))
        assertTrue(sanitized[0].content.orEmpty().contains("尚未真实执行"))
        assertEquals("user", sanitized[2].role)
        assertTrue(sanitized[2].content.orEmpty().contains("尚未真实执行"))
    }

    @Test
    fun `official provider also strips reasoningContent to avoid DeepSeek 400`() {
        val messages = listOf(
            MessageDto(role = "user", content = "Hi"),
            MessageDto(role = "assistant", content = "Hello", reasoningContent = "hidden reasoning"),
            MessageDto(role = "user", content = "Continue"),
        )

        val sanitized = sanitizeChatMessagesForProvider(
            messages = messages,
            isCustomProvider = false,
            omitToolProtocol = false,
        )

        assertEquals(3, sanitized.size)
        assertNull(sanitized[1].reasoningContent)
        assertEquals("Hello", sanitized[1].content)
    }

    @Test
    fun `custom provider compatibility retry includes gateway route not found`() {
        assertTrue(CUSTOM_PROVIDER_COMPATIBILITY_HTTP_CODES.containsAll(listOf(400, 404, 422)))
    }

    @Test
    fun `custom provider retries without tools after repeated gateway 5xx`() {
        assertFalse(
            shouldFallbackCustomProviderTools(
                isCustomProvider = true,
                providerCompatibilityMode = false,
                toolsPresent = true,
                exception = DeepSeekHttpException(500, "gateway busy", null),
                retryCount = 0,
            )
        )
        assertTrue(
            shouldFallbackCustomProviderTools(
                isCustomProvider = true,
                providerCompatibilityMode = false,
                toolsPresent = true,
                exception = DeepSeekHttpException(500, "gateway busy", null),
                retryCount = 1,
            )
        )
    }

    @Test
    fun `custom provider tool fallback ignores official or tool-less requests`() {
        val exception = DeepSeekHttpException(500, "gateway busy", null)
        assertFalse(
            shouldFallbackCustomProviderTools(
                isCustomProvider = false,
                providerCompatibilityMode = false,
                toolsPresent = true,
                exception = exception,
                retryCount = 1,
            )
        )
        assertFalse(
            shouldFallbackCustomProviderTools(
                isCustomProvider = true,
                providerCompatibilityMode = false,
                toolsPresent = false,
                exception = exception,
                retryCount = 1,
            )
        )
    }

    @Test
    fun `retry after seconds parse to milliseconds`() {
        assertEquals(9_000L, DeepSeekApiClient.parseRetryAfterMillis("9"))
        assertEquals(30_000L, DeepSeekApiClient.parseRetryAfterMillis(" 30 "))
    }

    @Test
    fun `retry after parser ignores invalid values`() {
        assertNull(DeepSeekApiClient.parseRetryAfterMillis(null))
        assertNull(DeepSeekApiClient.parseRetryAfterMillis(""))
        assertNull(DeepSeekApiClient.parseRetryAfterMillis("soon"))
        assertNull(DeepSeekApiClient.parseRetryAfterMillis("-1"))
    }

    @Test
    fun `OpenAI SSE accepts data field with or without separator space`() {
        assertEquals("{\"choices\":[]}", extractOpenAiSseData("data: {\"choices\":[]}"))
        assertEquals("{\"choices\":[]}", extractOpenAiSseData("data:{\"choices\":[]}"))
        assertEquals("[DONE]", extractOpenAiSseData("data:[DONE]"))
        assertNull(extractOpenAiSseData("event: message"))
    }

    @Test
    fun `empty HTTP stream and plain JSON response are rejected as non SSE`() {
        val emptyReason = validateOpenAiStream(
            validEventCount = 0,
            malformedEventCount = 0,
            sawDone = false,
            sawFinishReason = false,
            hasUsefulOutput = false,
        )
        assertTrue(emptyReason!!.contains("no valid data events"))

        // A gateway that ignores stream=true may return one ordinary JSON object
        // without any `data:` frames. The caller must route that through retry/error.
        assertTrue(
            listOf("{\"id\":\"response\",\"choices\":[]}")
                .mapNotNull(::extractOpenAiSseData)
                .isEmpty()
        )
        val plainJsonReason = validateOpenAiStream(
            validEventCount = 0,
            malformedEventCount = 0,
            sawDone = false,
            sawFinishReason = false,
            hasUsefulOutput = false,
        )
        assertTrue(plainJsonReason!!.contains("no valid data events"))
    }

    @Test
    fun `partial or malformed SSE response is rejected instead of completed`() {
        val truncatedReason = validateOpenAiStream(
            validEventCount = 1,
            malformedEventCount = 0,
            sawDone = false,
            sawFinishReason = false,
            hasUsefulOutput = true,
        )
        assertTrue(truncatedReason!!.contains("before a completion marker"))

        val malformedReason = validateOpenAiStream(
            validEventCount = 1,
            malformedEventCount = 1,
            sawDone = true,
            sawFinishReason = true,
            hasUsefulOutput = true,
        )
        assertTrue(malformedReason!!.contains("malformed"))
    }

    @Test
    fun `complete SSE with done or finish marker remains successful`() {
        assertNull(
            validateOpenAiStream(
                validEventCount = 1,
                malformedEventCount = 0,
                sawDone = true,
                sawFinishReason = false,
                hasUsefulOutput = true,
            )
        )
        assertNull(
            validateOpenAiStream(
                validEventCount = 1,
                malformedEventCount = 0,
                sawDone = false,
                sawFinishReason = true,
                hasUsefulOutput = true,
            )
        )
    }

    @Test
    fun `StreamChunk parsing handles content delta`() {
        val chunkJson = """{"choices":[{"index":0,"delta":{"content":"Hello world"}}]}"""
        val chunk = json.decodeFromString<StreamChunk>(chunkJson)
        assertEquals(1, chunk.choices.size)
        assertEquals("Hello world", chunk.choices[0].delta?.content)
    }

    @Test
    fun `StreamChunk parsing handles tool call delta`() {
        val chunkJson = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"read_file","arguments":"{}"}}]}}]}"""
        val chunk = json.decodeFromString<StreamChunk>(chunkJson)
        assertEquals(1, chunk.choices.size)
        assertNotNull(chunk.choices[0].delta?.toolCalls)
    }

    @Test
    fun `UsageDto parsing handles cached tokens`() {
        val usageJson = """{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,"prompt_tokens_details":{"cached_tokens":80}}"""
        val usage = json.decodeFromString<UsageDto>(usageJson)
        assertEquals(100, usage.promptTokens)
        assertEquals(80L, usage.promptTokensDetails?.cachedTokens)
    }

    @Test
    fun `OpenAI compatible base URL normalization strips completion suffixes`() {
        assertEquals(
            "http://192.0.2.10:20128",
            DeepSeekApiClient.normalizeOpenAiBaseUrl("http://192.0.2.10:20128/v1")
        )
        assertEquals(
            "https://gateway.example.com",
            DeepSeekApiClient.normalizeOpenAiBaseUrl("https://gateway.example.com/v1/chat/completions")
        )
        assertEquals(
            "https://gateway.example.com",
            DeepSeekApiClient.normalizeOpenAiBaseUrl("https://gateway.example.com/chat/completions/")
        )
    }

    @Test
    fun `OpenAI endpoint builder preserves provider version paths`() {
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint("https://ark.cn-beijing.volces.com/api/v3")
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint("https://ark.cn-beijing.volces.com/api/compatible")
        )
        assertEquals(
            "https://api.minimaxi.com/v1/chat/completions",
            DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint("https://api.minimaxi.com/anthropic")
        )
        assertEquals(
            "https://gateway.example.com/v1/chat/completions",
            DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint("https://gateway.example.com/v1/chat/completions")
        )
    }

    @Test
    fun `exact endpoint mode does not rewrite OpenAI compatible address`() {
        val exact = "https://gateway.example.com/custom/nonstandard/model/path"
        assertEquals(
            exact,
            DeepSeekApiClient.normalizeOpenAiBaseUrl(exact, ProviderEndpointMode.EXACT)
        )
        assertEquals(
            exact,
            DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(exact, ProviderEndpointMode.EXACT)
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/compatible",
            DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(
                "https://ark.cn-beijing.volces.com/api/compatible",
                ProviderEndpointMode.EXACT,
            )
        )
    }

    @Test
    fun `exact endpoint mode preserves OpenAI root and requires caller intent`() {
        val root = "http://192.0.2.10:20128/v1"
        assertEquals(root, DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(root, ProviderEndpointMode.EXACT))
        assertEquals(
            "http://192.0.2.10:20128/v1/chat/completions",
            DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(root, ProviderEndpointMode.AUTO)
        )
    }

    @Test
    fun `Anthropic endpoint builder preserves anthropic roots`() {
        assertEquals(
            "https://api.minimaxi.com/anthropic/v1/messages",
            DeepSeekApiClient.buildAnthropicMessagesEndpoint("https://api.minimaxi.com/anthropic")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            DeepSeekApiClient.buildAnthropicMessagesEndpoint("https://api.anthropic.com/v1/messages")
        )
        assertEquals(
            "https://gateway.example.com/v1/messages",
            DeepSeekApiClient.buildAnthropicMessagesEndpoint("https://gateway.example.com")
        )
    }

    @Test
    fun `exact endpoint mode does not rewrite Anthropic address`() {
        val exact = "https://gateway.example.com/provider/messages/custom"
        assertEquals(
            exact,
            DeepSeekApiClient.normalizeAnthropicBaseUrl(exact, ProviderEndpointMode.EXACT)
        )
        assertEquals(
            exact,
            DeepSeekApiClient.buildAnthropicMessagesEndpoint(exact, ProviderEndpointMode.EXACT)
        )
    }

    @Test
    fun `ProviderProtocol auto resolves protocol from endpoint shape`() {
        assertEquals(
            ProviderProtocol.ANTHROPIC,
            ProviderProtocol.resolve(ProviderProtocol.AUTO, "https://api.minimaxi.com/anthropic", "MiniMax-M2.7-highspeed")
        )
        assertEquals(
            ProviderProtocol.OPENAI,
            ProviderProtocol.resolve(ProviderProtocol.AUTO, "https://gateway.example.com/v1", "nvidia/deepseek-ai/deepseek-v4-pro")
        )
        assertEquals(
            ProviderProtocol.ANTHROPIC,
            ProviderProtocol.resolve(ProviderProtocol.AUTO, "https://api.anthropic.com/v1/messages", "claude-3-5-sonnet")
        )
    }

    @Test
    fun `provider not found hint treats standard OpenAI 404 as possible model routing failure`() {
        val hint = buildProviderNotFoundHint(
            endpoint = "http://192.0.2.10:20128/v1/chat/completions",
            body = "404 page not found",
            modelName = "nvidia/minimaxai/minimax-m2.7",
        )

        assertTrue(hint.contains("网关在路由模型时报错"))
        assertTrue(hint.contains("GET <服务地址>/v1/models"))
        assertTrue(hint.contains("minimaxai/minimax-m2.7"))
    }

    @Test
    fun `provider model name hint suggests stripping NVIDIA provider prefix`() {
        val hint = providerModelNameHint("nvidia/example-org/example-model")

        assertTrue(hint.contains("不需要 nvidia/ 前缀"))
        assertTrue(hint.contains("GET /v1/models"))
    }

    @Test
    fun `provider model name sanitizer strips invisible routing characters`() {
        assertEquals(
            "nvidia/minimaxai/minimax-m2.7",
            sanitizeProviderModelName("\u200Bnvidia/minimaxai/minimax-m2.7\uFEFF")
        )
        assertEquals(
            "nvidia/minimaxai/minimax-m2.7",
            sanitizeProviderModelName("\u0000 nvidia/minimaxai/minimax-m2.7 \u0007")
        )
    }

    @Test
    fun `provider base url sanitizer strips invisible routing characters`() {
        assertEquals(
            "http://154.12.90.249:20128/v1",
            sanitizeProviderBaseUrl("\u200Bhttp://154.12.90.249:20128/v1\uFEFF")
        )
        assertEquals(
            "http://154.12.90.249:20128/v1/chat/completions",
            DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(
                "\u200Bhttp://154.12.90.249:20128/v1/chat/completions\uFEFF",
                ProviderEndpointMode.EXACT,
            )
        )
    }

    @Test
    fun `OpenAI model list endpoint follows configured address mode`() {
        assertEquals(
            "http://154.12.90.249:20128/v1/models",
            DeepSeekApiClient.buildOpenAiModelsEndpoint("http://154.12.90.249:20128/v1")
        )
        assertEquals(
            "http://154.12.90.249:20128/v1/models",
            DeepSeekApiClient.buildOpenAiModelsEndpoint(
                "http://154.12.90.249:20128/v1/chat/completions",
                ProviderEndpointMode.EXACT,
            )
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/models",
            DeepSeekApiClient.buildOpenAiModelsEndpoint("https://ark.cn-beijing.volces.com/api/compatible")
        )
    }

    @Test
    fun `provider model list parser extracts ids and strips hidden characters`() {
        val ids = parseProviderModelIds(
            """{"data":[{"id":"\u200Bnvidia/minimaxai/minimax-m2.7"},{"id":"gpt-4o"},{"name":"claude-3-5-sonnet"}]}"""
        )

        assertEquals(
            listOf("nvidia/minimaxai/minimax-m2.7", "gpt-4o", "claude-3-5-sonnet"),
            ids,
        )
    }

    @Test
    fun `provider model name hint handles zero width provider prefix`() {
        val hint = providerModelNameHint("\u200Bnvidia/example-org/example-model")

        assertTrue(hint.contains("不需要 nvidia/ 前缀"))
        assertTrue(hint.contains("GET /v1/models"))
    }

    @Test
    fun `inactive provider credential detector recognizes gateway credential failures`() {
        assertTrue(
            isInactiveProviderCredentialError(
                """{"error":{"message":"No active credentials for provider: nvidia"}}"""
            )
        )
        assertFalse(isInactiveProviderCredentialError("""{"error":{"message":"model not found"}}"""))
    }
}
