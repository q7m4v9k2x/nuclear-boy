package com.nuclearboy.api.deepseek

import com.nuclearboy.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Main DeepSeek API client.
 *
 * Handles:
 * - Chat completions (streaming + non-streaming)
 * - API key validation and balance checking
 * - Automatic retry with exponential backoff
 * - Rate limit handling
 * - Error classification with user-friendly messages
 * - SSL error handling (China network friendly)
 */
class DeepSeekApiClient(
    private val apiKeyProvider: () -> String?,
    private val tokenTracker: TokenTracker = TokenTracker(),
    val contextManager: ContextWindowManager = ContextWindowManager(),
    private val baseUrlProvider: () -> String = { AppConstants.DEEPSEEK_BASE_URL },
    private val modelOverrideProvider: () -> String? = { null },
    private val providerProtocolProvider: () -> ProviderProtocol = { ProviderProtocol.OPENAI },
    private val providerEndpointModeProvider: () -> ProviderEndpointMode = { ProviderEndpointMode.AUTO },
) {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true  // Required for ToolDefinitionDto.type="function"
        explicitNulls = false  // Third-party OpenAI-compatible gateways may reject null fields
    }

    private val random = SecureRandom()

    private val internalProtocolHeader = "X-NuclearBoy-Provider-Protocol"

    // 底座 client：主 client 与诊断 client 都从它 newBuilder() 派生，
    // 从而共享同一个 Dispatcher(线程池) 和 ConnectionPool，避免两套独立线程/连接池。
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }

    private val client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(AppConstants.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConstants.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppConstants.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val apiKey = apiKeyProvider() ?: run {
                    throw IOException("API Key not configured")
                }
                val original = chain.request()
                val providerProtocol = original.header(internalProtocolHeader)
                // Requests that already carry credentials (e.g. checkBalance with an
                // explicit key) keep them — adding a second auth header confuses gateways.
                val alreadyAuthed = original.header("Authorization") != null ||
                    original.header("x-api-key") != null
                val requestBuilder = original.newBuilder()
                    .removeHeader(internalProtocolHeader)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                if (apiKey.isNotBlank() && !alreadyAuthed) {
                    if (providerProtocol == ProviderProtocol.ANTHROPIC.name) {
                        requestBuilder.addHeader("x-api-key", apiKey)
                        requestBuilder.addHeader("anthropic-version", "2023-06-01")
                    } else {
                        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }
                }
                chain.proceed(requestBuilder.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Authorization")
                redactHeader("x-api-key")
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
    }

    private val diagnosticClient: OkHttpClient by lazy {
        // 从 baseClient 派生：共享线程池/连接池，但用更短超时、且不带主 client 的鉴权/日志拦截器
        // （诊断要测任意第三方网关，绝不能自动注入全局 key）
        baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── Public API ─────────────────────────────────────

    /**
     * Send a streaming chat completion request.
     * Emits [StreamEvent] for each chunk received.
     */
    fun streamChat(
        messages: List<MessageDto>,
        modelTier: ModelTier = ModelTier.V4_PRO,
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
        tools: List<ToolDefinitionDto>? = null,
    ): Flow<StreamEvent> = flow {
        val model = sanitizeProviderModelName(modelOverrideProvider() ?: modelTier.modelId)
        val activeProtocol = activeProviderProtocol(model)
        if (activeProtocol == ProviderProtocol.ANTHROPIC) {
            emitAll(streamAnthropicChat(messages, model, modelTier, tools))
            return@flow
        }
        val isCustomProvider = modelOverrideProvider() != null
        var providerCompatibilityMode = false
        android.util.Log.e("NuclearBoy", "[ApiClient] streamChat() ENTRY model=$model msgs=${messages.size} tools=${tools?.size ?: 0} thinking=$thinkingMode stream=true custom=$isCustomProvider")
        val clientStartMs = System.currentTimeMillis()

        val promptTokens = estimatePromptTokens(messages)
        tokenTracker.startRequest(modelTier, thinkingMode.apiValue, promptTokens)

        emit(StreamEvent.Thinking("正在思考…"))

        var retryCount = 0
        var lastException: Exception? = null

        while (retryCount <= AppConstants.MAX_RETRIES) {
            try {
                val request = buildRequest(
                    messages = messages,
                    model = model,
                    thinkingMode = thinkingMode,
                    tools = tools,
                    stream = true,
                    compatibilityMode = providerCompatibilityMode,
                )
                val httpRequest = buildHttpRequest(request)
                android.util.Log.e("NuclearBoy", "[ApiClient] streamChat() HTTP request sent url=${httpRequest.url} bodySize=${httpRequest.body?.contentLength() ?: -1}")
                // .use 保证取消/异常/正常返回时都关闭响应体，避免用户按"停止"后 OkHttp 连接泄漏
                client.newCall(httpRequest).execute().use { response ->

                android.util.Log.e("NuclearBoy", "[ApiClient] HTTP ${response.code} contentLen=${response.body?.contentLength()}")
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    android.util.Log.e("NuclearBoy", "API: ERROR body=${sanitizeProviderBody(body)}")
                    val errorResponse = try {
                        json.decodeFromString<DeepSeekErrorResponse>(body)
                    } catch (_: Exception) { null }
                    throw DeepSeekHttpException(
                        code = response.code,
                        message = errorResponse?.error?.message ?: "HTTP ${response.code}",
                        errorType = errorResponse?.error?.type,
                        retryAfterMillis = parseRetryAfterMillis(response.header("retry-after")),
                    )
                }

                val body = response.body ?: throw IOException("Empty response body")
                android.util.Log.e("NuclearBoy", "[ApiClient] SSE streaming start")
                val reader = body.charStream().buffered()
                var content = StringBuilder()
                var reasoningContent = StringBuilder()
                var finalUsage: UsageDto? = null
                var toolCallsDetected = false
                var lineCount = 0
                var validEventCount = 0
                var malformedEventCount = 0
                var sawDone = false
                var sawFinishReason = false

                reader.useLines { lines ->
                    for (line in lines) {
                        val jsonStr = extractOpenAiSseData(line) ?: continue
                        if (jsonStr.isBlank()) continue
                        if (jsonStr == "[DONE]") {
                            sawDone = true
                            break
                        }
                        lineCount++
                        if (lineCount % 50 == 0) {
                            android.util.Log.e("NuclearBoy", "[ApiClient] SSE line $lineCount processed, content=${content.length} reasoning=${reasoningContent.length}")
                        }

                        val chunk = try {
                            json.decodeFromString<StreamChunk>(jsonStr)
                        } catch (_: Exception) {
                            malformedEventCount++
                            continue
                        }
                        validEventCount++
                        chunk.usage?.let { finalUsage = it }

                        chunk.choices.forEach { choice ->
                            if (!choice.finishReason.isNullOrBlank()) {
                                sawFinishReason = true
                            }
                            val delta = choice.delta ?: return@forEach

                            if (!delta.reasoningContent.isNullOrEmpty()) {
                                reasoningContent.append(delta.reasoningContent)
                                emit(StreamEvent.Thinking(delta.reasoningContent))
                                tokenTracker.onStreamToken(isReasoning = true)
                            }
                            if (!delta.content.isNullOrEmpty()) {
                                content.append(delta.content)
                                emit(StreamEvent.Content(delta.content, isReasoning = false))
                                tokenTracker.onStreamToken(isReasoning = false)
                            }
                            delta.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolDeltas ->
                                toolCallsDetected = true
                                emit(StreamEvent.ToolCallDelta(toolDeltas.map { toolDelta ->
                                    ToolCallDeltaDto(
                                        index = toolDelta.index,
                                        id = toolDelta.id,
                                        type = toolDelta.type,
                                        function = toolDelta.function,
                                    )
                                }))
                            }
                        }
                    }
                }

                val streamFailure = validateOpenAiStream(
                    validEventCount = validEventCount,
                    malformedEventCount = malformedEventCount,
                    sawDone = sawDone,
                    sawFinishReason = sawFinishReason,
                    hasUsefulOutput = content.isNotEmpty() || reasoningContent.isNotEmpty() || toolCallsDetected,
                )
                if (streamFailure != null) {
                    throw IOException(streamFailure)
                }

                val usage = finalUsage ?: UsageDto(
                    promptTokens = promptTokens,
                    completionTokens = content.length.toLong(),
                    totalTokens = promptTokens + content.length,
                )
                val clientElapsedMs = System.currentTimeMillis() - clientStartMs
                android.util.Log.e("NuclearBoy", "[ApiClient] SSE stream complete lines=$lineCount contentLen=${content.length} reasoningLen=${reasoningContent.length} toolCalls=$toolCallsDetected usage=prompt=${usage.promptTokens} completion=${usage.completionTokens} total=${usage.totalTokens} cached=${usage.promptTokensDetails?.cachedTokens ?: 0} reasoningTokens=${usage.completionTokensDetails?.reasoningTokens ?: 0} elapsedMs=$clientElapsedMs")
                tokenTracker.onRequestComplete(usage)
                emit(StreamEvent.Complete(usage))
                return@flow
                } // end response.use

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                val appError = classifyError(e)
                android.util.Log.e("NuclearBoy", "[ApiClient] streamChat() error retryCount=$retryCount maxRetries=${AppConstants.MAX_RETRIES} appError=$appError isRetryable=${appError.isRetryable}")
                if (shouldFallbackCustomProviderTools(
                    isCustomProvider = isCustomProvider,
                    providerCompatibilityMode = providerCompatibilityMode,
                    toolsPresent = !tools.isNullOrEmpty(),
                    exception = e,
                    retryCount = retryCount,
                )) {
                    providerCompatibilityMode = true
                    android.util.Log.e("NuclearBoy", "[ApiClient] streamChat() custom provider tool request failed; retrying without tool protocol")
                    emit(StreamEvent.ContentReset)
                    emit(StreamEvent.Thinking("当前网关在工具调用下持续失败，已切换兼容聊天模式重试…"))
                    continue
                }
                if (!appError.isRetryable || retryCount >= AppConstants.MAX_RETRIES) {
                    android.util.Log.e("NuclearBoy", "[ApiClient] streamChat() non-retryable or max retries reached, emitting error")
                    emit(StreamEvent.Error(appError, e.message))
                    return@flow
                }
                retryCount++
                val delayMs = retryDelayMillis(retryCount, e)
                android.util.Log.e("NuclearBoy", "[ApiClient] streamChat() retrying attempt=$retryCount delayMs=$delayMs")
                // The next attempt re-issues the whole request and re-streams from byte
                // zero — any content/reasoning/tool-call fragments already emitted for
                // this (failed) attempt must be discarded by collectors before we continue.
                emit(StreamEvent.ContentReset)
                emit(StreamEvent.Thinking("重试第 ${retryCount} 次，约 ${(delayMs + 999) / 1000} 秒…"))
                delay(delayMs)
            }
        }
        lastException?.let { emit(StreamEvent.Error(classifyError(it), it.message)) }
    }

    /**
     * Validate an API key by making a lightweight request.
     */
    suspend fun validateApiKey(apiKey: String): AppResult<Boolean> = withContext(Dispatchers.IO) {
        android.util.Log.e("NuclearBoy", "[ApiClient] validateApiKey() ENTRY")
        try {
            val testMessages = listOf(
                MessageDto(role = "user", content = "Hi")
            )
            val request = buildRequest(
                messages = testMessages,
                model = sanitizeProviderModelName(modelOverrideProvider() ?: ModelTier.V4_FLASH.modelId),
                thinkingMode = ThinkingMode.DISABLED,
                tools = null,
                stream = false,
                maxTokens = 1,
            )

            val httpRequest = buildHttpRequest(request)
            client.newCall(httpRequest).execute().use { response ->
                val result = when (response.code) {
                    200 -> AppResult.success(true)
                    401 -> AppResult.failure(AppError.ApiKeyInvalid)
                    402 -> AppResult.failure(AppError.InsufficientBalance)
                    429 -> AppResult.failure(AppError.RateLimited)
                    in 500..599 -> AppResult.failure(AppError.ServerError)
                    else -> AppResult.failure(AppError.Unknown, "HTTP ${response.code}")
                }
                android.util.Log.e("NuclearBoy", "[ApiClient] validateApiKey() response httpCode=${response.code} result=${result}")
                result
            }
        } catch (e: SSLException) {
            android.util.Log.e("NuclearBoy", "[ApiClient] validateApiKey() SSLException: ${e.message}")
            AppResult.failure(
                AppError.NetworkUnavailable,
                "SSL 连接失败，网络可能被干扰。请尝试切换网络环境。"
            )
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("NuclearBoy", "[ApiClient] validateApiKey() SocketTimeoutException: ${e.message}")
            AppResult.failure(AppError.NetworkTimeout)
        } catch (e: IOException) {
            android.util.Log.e("NuclearBoy", "[ApiClient] validateApiKey() IOException: ${e.message}")
            AppResult.failure(AppError.NetworkUnavailable, e.message)
        }
    }

    /**
     * Check account balance via the DeepSeek API.
     */
    suspend fun checkBalance(apiKey: String): AppResult<BalanceResponse> =
        withContext(Dispatchers.IO) {
            android.util.Log.e("NuclearBoy", "[ApiClient] checkBalance() ENTRY")
            try {
                val httpRequest = Request.Builder()
                    .url("${AppConstants.DEEPSEEK_BASE_URL}/user/balance")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                android.util.Log.e("NuclearBoy", "[ApiClient] checkBalance() httpCode=${response.code} bodyLen=${body.length}")

                if (response.isSuccessful) {
                    val balance = json.decodeFromString<BalanceResponse>(body)
                    android.util.Log.e("NuclearBoy", "[ApiClient] checkBalance() success isAvailable=${balance.isAvailable} totalBalance=${balance.balanceInfos?.joinToString { "${it.currency}:${it.totalBalance}" }}")
                    AppResult.success(balance)
                } else {
                    val errorBody = try {
                        json.decodeFromString<DeepSeekErrorResponse>(body)
                    } catch (_: Exception) { null }
                    AppResult.failure(
                        error = AppError.fromHttpCode(response.code),
                        detail = errorBody?.error?.message ?: body
                    )
                }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(
                    error = classifyError(e),
                    detail = e.message
                )
            }
        }

    suspend fun testCustomProvider(
        baseUrl: String,
        modelName: String,
        apiKey: String?,
        protocol: ProviderProtocol = ProviderProtocol.AUTO,
        endpointMode: ProviderEndpointMode = ProviderEndpointMode.AUTO,
    ): AppResult<ProviderTestResult> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = sanitizeProviderBaseUrl(baseUrl)
        val normalizedModel = sanitizeProviderModelName(modelName)
        val resolvedProtocol = ProviderProtocol.resolve(protocol, normalizedBaseUrl, normalizedModel)
        if (normalizedModel.isBlank()) {
            return@withContext AppResult.failure(AppError.InvalidRequest, "模型名不能为空")
        }

        val endpoint = when (resolvedProtocol) {
            ProviderProtocol.ANTHROPIC -> buildAnthropicMessagesEndpoint(normalizedBaseUrl, endpointMode)
            else -> buildOpenAiChatCompletionsEndpoint(normalizedBaseUrl, endpointMode)
        }
        if (endpoint.isBlank()) {
            return@withContext AppResult.failure(AppError.InvalidRequest, "服务地址不能为空")
        }
        val startedAt = System.currentTimeMillis()
        var responseToClose: okhttp3.Response? = null
        try {
            if (resolvedProtocol == ProviderProtocol.ANTHROPIC) {
                return@withContext testAnthropicProvider(
                    endpoint = endpoint,
                    modelName = normalizedModel,
                    apiKey = apiKey,
                    startedAt = startedAt,
                    endpointMode = endpointMode,
                )
            }
            val request = ChatCompletionRequest(
                model = normalizedModel,
                messages = listOf(MessageDto(role = "user", content = "ping")),
                temperature = 0.0,
                maxTokens = 8,
                stream = false,
                thinking = null,
                reasoningEffort = null,
            )
            val body = json.encodeToString(ChatCompletionRequest.serializer(), request)
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
            val key = apiKey?.trim().orEmpty()
            if (key.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $key")
            }

            var attempts = 1
            var response = diagnosticClient.newCall(requestBuilder.build()).execute()
            responseToClose = response
            var responseBody = response.body?.string().orEmpty()
            if (response.code == 404 && isInactiveProviderCredentialError(responseBody)) {
                delay(800)
                attempts += 1
                response.close() // 关闭被丢弃的首个响应
                response = diagnosticClient.newCall(requestBuilder.build()).execute()
                responseToClose = response
                responseBody = response.body?.string().orEmpty()
            }
            val elapsedMs = System.currentTimeMillis() - startedAt
            if (!response.isSuccessful) {
                val retryDetail = if (attempts > 1) "\n已按网关上游凭证短暂不可用场景重试 1 次，仍失败。" else ""
                val modelListDetail = if (response.code == 404) {
                    "\n" + probeOpenAiModelList(
                        baseUrl = normalizedBaseUrl,
                        endpointMode = endpointMode,
                        apiKey = key,
                        requestedModel = normalizedModel,
                    )
                } else {
                    ""
                }
                return@withContext AppResult.failure(
                    providerHttpError(response.code),
                    buildProviderHttpFailureDetail(
                        httpCode = response.code,
                        endpoint = endpoint,
                        modelName = normalizedModel,
                        body = responseBody,
                    ) + retryDetail + modelListDetail,
                )
            }

            val parsed = runCatching {
                json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
            }.getOrElse {
                return@withContext AppResult.failure(
                    AppError.Unknown,
                    "服务返回 HTTP 200，但响应不是 OpenAI chat/completions JSON。\n" +
                        "请确认接入地址是 OpenAI 兼容网关，不是模型列表或网页地址。\n" +
                        "返回片段：${sanitizeProviderBody(responseBody)}",
                )
            }
            val content = parsed.choices.firstOrNull()?.message?.content.orEmpty()
            AppResult.success(
                ProviderTestResult(
                    endpoint = endpoint,
                    modelName = parsed.model.ifBlank { normalizedModel },
                    protocol = ProviderProtocol.OPENAI,
                    endpointMode = endpointMode,
                    latencyMs = elapsedMs,
                    replyPreview = content.take(120),
                )
            )
        } catch (e: IllegalArgumentException) {
            AppResult.failure(
                AppError.InvalidRequest,
                "服务地址格式不正确：${e.message ?: "无法解析 URL"}。\n" +
                    "示例：http://your-gateway:20128/v1 或 https://example.com/v1",
            )
        } catch (e: SSLException) {
            AppResult.failure(
                AppError.NetworkUnavailable,
                "HTTPS/TLS 握手失败。若网关只支持 HTTP，请填 http:// 地址；若填的是 HTTPS，请检查证书链和域名。",
            )
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.failure(
                AppError.NetworkTimeout,
                "连接超时。请确认手机当前网络能访问该 IP/域名和端口，网关服务正在运行。",
            )
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            val detail = when {
                message.contains("CLEARTEXT communication", ignoreCase = true) ->
                    "Android 拦截了明文 HTTP 请求。当前地址是 HTTP，请升级到允许自定义 HTTP 网关的版本，或改用 HTTPS。"
                message.contains("Failed to connect", ignoreCase = true) ||
                    message.contains("Connection refused", ignoreCase = true) ->
                    "连接失败。请确认地址、端口、防火墙和网关进程状态。"
                message.contains("Unable to resolve host", ignoreCase = true) ->
                    "域名解析失败。请检查域名或 DNS；如果是 IP 地址，请确认格式正确。"
                else -> "网络请求失败：${message.ifBlank { e.javaClass.simpleName }}"
            }
            AppResult.failure(classifyError(e), detail)
        } catch (e: Exception) {
            AppResult.failure(
                classifyError(e),
                "测试请求失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    suspend fun testCustomProviderFormalChat(
        baseUrl: String,
        modelName: String,
        apiKey: String?,
        protocol: ProviderProtocol = ProviderProtocol.AUTO,
        endpointMode: ProviderEndpointMode = ProviderEndpointMode.AUTO,
    ): AppResult<ProviderFormalChatTestResult> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = sanitizeProviderBaseUrl(baseUrl)
        val normalizedModel = sanitizeProviderModelName(modelName)
        val resolvedProtocol = ProviderProtocol.resolve(protocol, normalizedBaseUrl, normalizedModel)
        if (normalizedModel.isBlank()) {
            return@withContext AppResult.failure(AppError.InvalidRequest, "模型名不能为空")
        }
        val endpoint = when (resolvedProtocol) {
            ProviderProtocol.ANTHROPIC -> buildAnthropicMessagesEndpoint(normalizedBaseUrl, endpointMode)
            else -> buildOpenAiChatCompletionsEndpoint(normalizedBaseUrl, endpointMode)
        }
        if (endpoint.isBlank()) {
            return@withContext AppResult.failure(AppError.InvalidRequest, "服务地址不能为空")
        }

        return@withContext try {
            if (resolvedProtocol == ProviderProtocol.ANTHROPIC) {
                testAnthropicProviderFormalChat(
                    endpoint = endpoint,
                    modelName = normalizedModel,
                    apiKey = apiKey,
                    endpointMode = endpointMode,
                )
            } else {
                testOpenAiProviderFormalChat(
                    endpoint = endpoint,
                    baseUrl = normalizedBaseUrl,
                    modelName = normalizedModel,
                    apiKey = apiKey,
                    endpointMode = endpointMode,
                )
            }
        } catch (e: IllegalArgumentException) {
            AppResult.failure(
                AppError.InvalidRequest,
                "服务地址格式不正确：${e.message ?: "无法解析 URL"}。\n" +
                    "示例：http://your-gateway:20128/v1 或 https://example.com/v1",
            )
        } catch (e: SSLException) {
            AppResult.failure(
                AppError.NetworkUnavailable,
                "HTTPS/TLS 握手失败。若网关只支持 HTTP，请填 http:// 地址；若填的是 HTTPS，请检查证书链和域名。",
            )
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.failure(
                AppError.NetworkTimeout,
                "正式聊天连接超时。请确认手机当前网络能访问该 IP/域名和端口，网关服务正在运行。",
            )
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            val detail = when {
                message.contains("CLEARTEXT communication", ignoreCase = true) ->
                    "Android 拦截了明文 HTTP 请求。当前地址是 HTTP，请升级到允许自定义 HTTP 网关的版本，或改用 HTTPS。"
                message.contains("Failed to connect", ignoreCase = true) ||
                    message.contains("Connection refused", ignoreCase = true) ->
                    "正式聊天连接失败。请确认地址、端口、防火墙和网关进程状态。"
                message.contains("Unable to resolve host", ignoreCase = true) ->
                    "域名解析失败。请检查域名或 DNS；如果是 IP 地址，请确认格式正确。"
                else -> "正式聊天请求失败：${message.ifBlank { e.javaClass.simpleName }}"
            }
            AppResult.failure(classifyError(e), detail)
        } catch (e: Exception) {
            AppResult.failure(
                classifyError(e),
                "正式聊天测试异常：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    suspend fun listOpenAiProviderModels(
        baseUrl: String,
        apiKey: String?,
        endpointMode: ProviderEndpointMode = ProviderEndpointMode.AUTO,
    ): AppResult<ProviderModelListResult> = withContext(Dispatchers.IO) {
        val endpoint = buildOpenAiModelsEndpoint(baseUrl, endpointMode)
        if (endpoint.isBlank()) {
            return@withContext AppResult.failure(AppError.InvalidRequest, "服务地址不能为空")
        }
        val startedAt = System.currentTimeMillis()
        try {
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .get()
                .addHeader("Accept", "application/json")
            val key = apiKey?.trim().orEmpty()
            if (key.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $key")
            }
            diagnosticClient.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val elapsedMs = System.currentTimeMillis() - startedAt
                if (!response.isSuccessful) {
                    return@withContext AppResult.failure(
                        providerHttpError(response.code),
                        "模型列表探测：GET $endpoint 返回 HTTP ${response.code}" +
                            providerModelListFailureSuffix(body),
                    )
                }
                val modelIds = parseProviderModelIds(body)
                if (modelIds.isEmpty()) {
                    return@withContext AppResult.failure(
                        AppError.InvalidRequest,
                        "模型列表探测：GET $endpoint 成功，但没有解析到模型 id；请在网关后台确认模型列表格式。",
                    )
                }
                AppResult.success(
                    ProviderModelListResult(
                        endpoint = endpoint,
                        modelIds = modelIds,
                        latencyMs = elapsedMs,
                    )
                )
            }
        } catch (e: IllegalArgumentException) {
            AppResult.failure(
                AppError.InvalidRequest,
                "模型列表地址格式不正确：${e.message ?: "无法解析 URL"}。",
            )
        } catch (e: SSLException) {
            AppResult.failure(
                AppError.NetworkUnavailable,
                "HTTPS/TLS 握手失败。若网关只支持 HTTP，请填 http:// 地址；若填的是 HTTPS，请检查证书链和域名。",
            )
        } catch (e: java.net.SocketTimeoutException) {
            AppResult.failure(
                AppError.NetworkTimeout,
                "模型列表请求超时。请确认手机当前网络能访问该 IP/域名和端口，网关服务正在运行。",
            )
        } catch (e: IOException) {
            AppResult.failure(
                classifyError(e),
                "模型列表请求失败：${e.message ?: e.javaClass.simpleName}",
            )
        } catch (e: Exception) {
            AppResult.failure(
                classifyError(e),
                "模型列表探测异常：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun streamAnthropicChat(
        messages: List<MessageDto>,
        model: String,
        modelTier: ModelTier,
        tools: List<ToolDefinitionDto>?,
    ): Flow<StreamEvent> = flow {
        android.util.Log.e("NuclearBoy", "[ApiClient] streamAnthropicChat() ENTRY model=$model msgs=${messages.size} tools=${tools?.size ?: 0}")
        val clientStartMs = System.currentTimeMillis()
        val promptTokens = estimatePromptTokens(messages)
        tokenTracker.startRequest(modelTier, ProviderProtocol.ANTHROPIC.name.lowercase(), promptTokens)
        emit(StreamEvent.Thinking("正在思考…"))

        var retryCount = 0
        var lastException: Exception? = null

        while (retryCount <= AppConstants.MAX_RETRIES) {
            try {
                val body = buildAnthropicRequestBody(
                    messages = messages,
                    model = model,
                    tools = tools,
                    stream = true,
                    maxTokens = 8192,
                )
                val requestBody = body.toRequestBody("application/json".toMediaType())
                val httpRequest = Request.Builder()
                    .url(buildAnthropicMessagesEndpoint(baseUrlProvider(), activeProviderEndpointMode()))
                    .header(internalProtocolHeader, ProviderProtocol.ANTHROPIC.name)
                    .post(requestBody)
                    .build()
                android.util.Log.e("NuclearBoy", "[ApiClient] streamAnthropicChat() HTTP request sent url=${httpRequest.url} bodySize=${body.length}")

                // .use 保证取消/异常/正常返回时都关闭响应体，避免连接泄漏
                client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    throw DeepSeekHttpException(
                        code = response.code,
                        message = parseProviderErrorMessage(errorBody) ?: "HTTP ${response.code}",
                        errorType = null,
                        retryAfterMillis = parseRetryAfterMillis(response.header("retry-after")),
                    )
                }

                val responseBody = response.body ?: throw IOException("Empty response body")
                val reader = responseBody.charStream().buffered()
                var inputTokens = promptTokens
                var outputTokens = 0L
                val content = StringBuilder()
                var lineCount = 0

                reader.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank() || !line.startsWith("data: ")) continue
                        val payload = line.removePrefix("data: ").trim()
                        if (payload == "[DONE]") break
                        lineCount++

                        val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                        when (event["type"]?.jsonPrimitive?.contentOrNull) {
                            "message_start" -> {
                                val usage = event["message"]?.jsonObject?.get("usage")?.jsonObject
                                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: inputTokens
                                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: outputTokens
                            }
                            "content_block_start" -> {
                                val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                                val block = event["content_block"]?.jsonObject ?: continue
                                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                                    "text" -> {
                                        val text = block["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        if (text.isNotEmpty()) {
                                            content.append(text)
                                            emit(StreamEvent.Content(text))
                                        }
                                    }
                                    "tool_use" -> {
                                        val id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        val name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        val input = block["input"]?.takeIf { it !is JsonNull }?.toString().orEmpty()
                                        emit(StreamEvent.ToolCallDelta(listOf(
                                            ToolCallDeltaDto(
                                                index = index,
                                                id = id,
                                                function = FunctionCallDeltaDto(
                                                    name = name,
                                                    arguments = input.takeIf { it != "{}" },
                                                ),
                                            )
                                        )))
                                    }
                                }
                            }
                            "content_block_delta" -> {
                                val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
                                val delta = event["delta"]?.jsonObject ?: continue
                                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                    "text_delta" -> {
                                        val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        if (text.isNotEmpty()) {
                                            content.append(text)
                                            emit(StreamEvent.Content(text))
                                            tokenTracker.onStreamToken(isReasoning = false)
                                        }
                                    }
                                    "input_json_delta" -> {
                                        val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        if (partial.isNotEmpty()) {
                                            emit(StreamEvent.ToolCallDelta(listOf(
                                                ToolCallDeltaDto(
                                                    index = index,
                                                    function = FunctionCallDeltaDto(arguments = partial),
                                                )
                                            )))
                                        }
                                    }
                                }
                            }
                            "message_delta" -> {
                                val usage = event["usage"]?.jsonObject
                                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: outputTokens
                            }
                        }
                    }
                }

                val usage = UsageDto(
                    promptTokens = inputTokens,
                    completionTokens = outputTokens.takeIf { it > 0 } ?: content.length.toLong(),
                    totalTokens = inputTokens + (outputTokens.takeIf { it > 0 } ?: content.length.toLong()),
                )
                val elapsedMs = System.currentTimeMillis() - clientStartMs
                android.util.Log.e("NuclearBoy", "[ApiClient] Anthropic stream complete lines=$lineCount contentLen=${content.length} prompt=${usage.promptTokens} completion=${usage.completionTokens} elapsedMs=$elapsedMs")
                tokenTracker.onRequestComplete(usage)
                emit(StreamEvent.Complete(usage))
                return@flow
                } // end response.use
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                val appError = classifyError(e)
                android.util.Log.e("NuclearBoy", "[ApiClient] streamAnthropicChat() error retryCount=$retryCount maxRetries=${AppConstants.MAX_RETRIES} appError=$appError isRetryable=${appError.isRetryable}")
                if (!appError.isRetryable || retryCount >= AppConstants.MAX_RETRIES) {
                    emit(StreamEvent.Error(appError, e.message))
                    return@flow
                }
                retryCount++
                val delayMs = retryDelayMillis(retryCount, e)
                // Anthropic streams are replayed from byte zero on retry.  Tell
                // collectors to discard the partial assistant text from the
                // failed attempt, otherwise the replay is appended twice.
                emit(StreamEvent.ContentReset)
                emit(StreamEvent.Thinking("重试第 ${retryCount} 次，约 ${(delayMs + 999) / 1000} 秒…"))
                delay(delayMs)
            }
        }
        lastException?.let { emit(StreamEvent.Error(classifyError(it), it.message)) }
    }

    private fun testAnthropicProvider(
        endpoint: String,
        modelName: String,
        apiKey: String?,
        startedAt: Long,
        endpointMode: ProviderEndpointMode,
    ): AppResult<ProviderTestResult> {
        val body = buildAnthropicRequestBody(
            messages = listOf(MessageDto(role = "user", content = "ping")),
            model = modelName,
            tools = null,
            stream = false,
            maxTokens = 8,
        )
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("anthropic-version", "2023-06-01")
        val key = apiKey?.trim().orEmpty()
        if (key.isNotBlank()) {
            requestBuilder.addHeader("x-api-key", key)
        }

        // .use 关闭响应（多处 return 为内联非局部返回，退出前仍会关闭）
        return diagnosticClient.newCall(requestBuilder.build()).execute().use { response ->
        val responseBody = response.body?.string().orEmpty()
        val elapsedMs = System.currentTimeMillis() - startedAt
        if (!response.isSuccessful) {
            return AppResult.failure(
                providerHttpError(response.code),
                buildProviderHttpFailureDetail(
                    httpCode = response.code,
                    endpoint = endpoint,
                    modelName = modelName,
                    body = responseBody,
                ),
            )
        }

        val parsed = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrElse {
            return AppResult.failure(
                AppError.Unknown,
                "服务返回 HTTP 200，但响应不是 Anthropic messages JSON。\n" +
                    "请确认接入地址是 Anthropic 兼容网关。\n" +
                    "返回片段：${sanitizeProviderBody(responseBody)}",
            )
        }
        val content = parsed["content"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
        AppResult.success(
            ProviderTestResult(
                endpoint = endpoint,
                modelName = parsed["model"]?.jsonPrimitive?.contentOrNull?.ifBlank { modelName } ?: modelName,
                protocol = ProviderProtocol.ANTHROPIC,
                endpointMode = endpointMode,
                latencyMs = elapsedMs,
                replyPreview = content.take(120),
            )
        )
        }
    }

    private fun testOpenAiProviderFormalChat(
        endpoint: String,
        baseUrl: String,
        modelName: String,
        apiKey: String?,
        endpointMode: ProviderEndpointMode,
    ): AppResult<ProviderFormalChatTestResult> {
        val startedAt = System.currentTimeMillis()
        val key = apiKey?.trim().orEmpty()
        var compatibilityMode = false

        while (true) {
            val request = ChatCompletionRequest(
                model = modelName,
                messages = listOf(MessageDto(role = "user", content = "ping")),
                temperature = 0.0,
                topP = 1.0,
                maxTokens = 32,
                stream = true,
                tools = if (compatibilityMode) null else listOf(providerDiagnosticTool()),
                thinking = null,
                reasoningEffort = null,
            )
            val body = json.encodeToString(ChatCompletionRequest.serializer(), request)
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
            if (key.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $key")
            }

            var shouldRetryCompatibility = false
            diagnosticClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    if (
                        !compatibilityMode &&
                        response.code in CUSTOM_PROVIDER_COMPATIBILITY_HTTP_CODES
                    ) {
                        compatibilityMode = true
                        shouldRetryCompatibility = true
                        return@use
                    }
                    val modelListDetail = if (response.code == 404) {
                        "\n" + probeOpenAiModelList(
                            baseUrl = baseUrl,
                            endpointMode = endpointMode,
                            apiKey = key,
                            requestedModel = modelName,
                        )
                    } else {
                        ""
                    }
                    return AppResult.failure(
                        providerHttpError(response.code),
                        buildProviderHttpFailureDetail(
                            httpCode = response.code,
                            endpoint = endpoint,
                            modelName = modelName,
                            body = responseBody,
                        ) + modelListDetail,
                    )
                }

                val responseBody = response.body ?: return AppResult.failure(
                    AppError.Unknown,
                    "正式聊天返回 HTTP 200，但响应体为空。",
                )
                val parsed = parseOpenAiFormalStream(responseBody.charStream().buffered())
                val elapsedMs = System.currentTimeMillis() - startedAt
                if (!parsed.hasUsefulOutput) {
                    return AppResult.failure(
                        AppError.Unknown,
                        "正式聊天 stream=true 返回成功，但没有内容或工具事件。" +
                            " lineCount=${parsed.lineCount}; done=${parsed.sawDone}; finish=${parsed.sawFinish}",
                    )
                }

                return AppResult.success(
                    ProviderFormalChatTestResult(
                        endpoint = endpoint,
                        modelName = modelName,
                        protocol = ProviderProtocol.OPENAI,
                        endpointMode = endpointMode,
                        latencyMs = elapsedMs,
                        stream = true,
                        toolsRequested = !compatibilityMode,
                        compatibilityRetry = compatibilityMode,
                        sawContent = parsed.sawContent,
                        sawToolEvent = parsed.sawToolEvent,
                        lineCount = parsed.lineCount,
                        replyPreview = parsed.replyPreview,
                    )
                )
            }
            if (shouldRetryCompatibility) continue
        }
    }

    private fun testAnthropicProviderFormalChat(
        endpoint: String,
        modelName: String,
        apiKey: String?,
        endpointMode: ProviderEndpointMode,
    ): AppResult<ProviderFormalChatTestResult> {
        val startedAt = System.currentTimeMillis()
        val body = buildAnthropicRequestBody(
            messages = listOf(MessageDto(role = "user", content = "ping")),
            model = modelName,
            tools = listOf(providerDiagnosticTool()),
            stream = true,
            maxTokens = 32,
        )
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .addHeader("anthropic-version", "2023-06-01")
        val key = apiKey?.trim().orEmpty()
        if (key.isNotBlank()) {
            requestBuilder.addHeader("x-api-key", key)
        }

        return diagnosticClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                return AppResult.failure(
                    providerHttpError(response.code),
                    buildProviderHttpFailureDetail(
                        httpCode = response.code,
                        endpoint = endpoint,
                        modelName = modelName,
                        body = responseBody,
                    ),
                )
            }
            val responseBody = response.body ?: return AppResult.failure(
                AppError.Unknown,
                "正式聊天返回 HTTP 200，但响应体为空。",
            )
            val parsed = parseAnthropicFormalStream(responseBody.charStream().buffered())
            val elapsedMs = System.currentTimeMillis() - startedAt
            if (!parsed.hasUsefulOutput) {
                return AppResult.failure(
                    AppError.Unknown,
                    "正式聊天 stream=true 返回成功，但没有内容或工具事件。" +
                        " lineCount=${parsed.lineCount}; done=${parsed.sawDone}; finish=${parsed.sawFinish}",
                )
            }

            AppResult.success(
                ProviderFormalChatTestResult(
                    endpoint = endpoint,
                    modelName = modelName,
                    protocol = ProviderProtocol.ANTHROPIC,
                    endpointMode = endpointMode,
                    latencyMs = elapsedMs,
                    stream = true,
                    toolsRequested = true,
                    compatibilityRetry = false,
                    sawContent = parsed.sawContent,
                    sawToolEvent = parsed.sawToolEvent,
                    lineCount = parsed.lineCount,
                    replyPreview = parsed.replyPreview,
                )
            )
        }
    }

    // ── Private ────────────────────────────────────────

    private fun providerDiagnosticTool(): ToolDefinitionDto =
        ToolDefinitionDto(
            function = FunctionDefinitionDto(
                name = "diagnostic_noop",
                description = "Diagnostic no-op tool used to verify chat tool schema compatibility.",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject { })
                },
            ),
        )

    private data class FormalStreamParseResult(
        val sawContent: Boolean,
        val sawToolEvent: Boolean,
        val sawDone: Boolean,
        val sawFinish: Boolean,
        val lineCount: Int,
        val replyPreview: String,
    ) {
        val hasUsefulOutput: Boolean = sawContent || sawToolEvent
    }

    private fun parseOpenAiFormalStream(reader: java.io.BufferedReader): FormalStreamParseResult {
        var sawContent = false
        var sawToolEvent = false
        var sawDone = false
        var sawFinish = false
        var lineCount = 0
        val content = StringBuilder()

        reader.useLines { lines ->
            for (line in lines) {
                val payload = extractOpenAiSseData(line)?.takeIf { it.isNotBlank() } ?: continue
                if (payload == "[DONE]") {
                    sawDone = true
                    break
                }
                lineCount++
                val chunk = runCatching { json.decodeFromString(StreamChunk.serializer(), payload) }
                    .getOrNull()
                    ?: continue
                chunk.choices.forEach { choice ->
                    if (!choice.finishReason.isNullOrBlank()) {
                        sawFinish = true
                    }
                    val delta = choice.delta ?: return@forEach
                    if (!delta.content.isNullOrBlank()) {
                        sawContent = true
                        content.append(delta.content)
                    }
                    if (!delta.toolCalls.isNullOrEmpty()) {
                        sawToolEvent = true
                    }
                }
            }
        }

        return FormalStreamParseResult(
            sawContent = sawContent,
            sawToolEvent = sawToolEvent,
            sawDone = sawDone,
            sawFinish = sawFinish,
            lineCount = lineCount,
            replyPreview = content.toString().trim().take(120),
        )
    }

    private fun parseAnthropicFormalStream(reader: java.io.BufferedReader): FormalStreamParseResult {
        var sawContent = false
        var sawToolEvent = false
        var sawDone = false
        var sawFinish = false
        var lineCount = 0
        val content = StringBuilder()

        reader.useLines { lines ->
            for (line in lines) {
                val payload = extractOpenAiSseData(line)?.takeIf { it.isNotBlank() } ?: continue
                if (payload == "[DONE]") {
                    sawDone = true
                    break
                }
                lineCount++
                val event = runCatching { json.parseToJsonElement(payload).jsonObject }
                    .getOrNull()
                    ?: continue
                when (event["type"]?.jsonPrimitive?.contentOrNull) {
                    "message_stop" -> sawFinish = true
                    "content_block_start" -> {
                        val block = event["content_block"]?.jsonObject
                        if (block?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use") {
                            sawToolEvent = true
                        }
                        val text = block
                            ?.get("text")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()
                        if (text.isNotBlank()) {
                            sawContent = true
                            content.append(text)
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event["delta"]?.jsonObject
                        val type = delta?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
                        when (type) {
                            "text_delta" -> {
                                val text = delta
                                    ?.get("text")
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    .orEmpty()
                                if (text.isNotBlank()) {
                                    sawContent = true
                                    content.append(text)
                                }
                            }
                            "input_json_delta" -> sawToolEvent = true
                        }
                    }
                }
            }
        }

        return FormalStreamParseResult(
            sawContent = sawContent,
            sawToolEvent = sawToolEvent,
            sawDone = sawDone,
            sawFinish = sawFinish,
            lineCount = lineCount,
            replyPreview = content.toString().trim().take(120),
        )
    }

    private fun activeProviderProtocol(modelName: String): ProviderProtocol {
        return if (modelOverrideProvider() == null) {
            ProviderProtocol.OPENAI
        } else {
            ProviderProtocol.resolve(providerProtocolProvider(), baseUrlProvider(), sanitizeProviderModelName(modelName))
        }
    }

    private fun activeProviderEndpointMode(): ProviderEndpointMode =
        if (modelOverrideProvider() == null) ProviderEndpointMode.AUTO else providerEndpointModeProvider()

    private fun buildAnthropicRequestBody(
        messages: List<MessageDto>,
        model: String,
        tools: List<ToolDefinitionDto>?,
        stream: Boolean,
        maxTokens: Int,
    ): String {
        val normalizedModel = sanitizeProviderModelName(model)
        val systemText = messages
            .filter { it.role == "system" }
            .mapNotNull { it.content }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        val anthropicMessages = mutableListOf<JsonObject>()
        var index = 0
        val nonSystem = messages.filterNot { it.role == "system" }
        while (index < nonSystem.size) {
            val msg = nonSystem[index]
            if (msg.role == "tool") {
                val blocks = mutableListOf<JsonObject>()
                while (index < nonSystem.size && nonSystem[index].role == "tool") {
                    val toolMessage = nonSystem[index]
                    blocks.add(buildJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(toolMessage.toolCallId.orEmpty()))
                        put("content", JsonPrimitive(toolMessage.content.orEmpty()))
                    })
                    index++
                }
                anthropicMessages.add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonArray(blocks))
                })
                continue
            }

            val role = when (msg.role) {
                "assistant" -> "assistant"
                else -> "user"
            }
            val contentElement = if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                val blocks = mutableListOf<JsonObject>()
                msg.content?.takeIf { it.isNotBlank() }?.let { text ->
                    blocks.add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    })
                }
                msg.toolCalls.forEach { toolCall ->
                    blocks.add(buildJsonObject {
                        put("type", JsonPrimitive("tool_use"))
                        put("id", JsonPrimitive(toolCall.id))
                        put("name", JsonPrimitive(toolCall.function.name))
                        put("input", parseJsonObjectOrEmpty(toolCall.function.arguments))
                    })
                }
                JsonArray(blocks)
            } else {
                JsonPrimitive(msg.content.orEmpty())
            }
            anthropicMessages.add(buildJsonObject {
                put("role", JsonPrimitive(role))
                put("content", contentElement)
            })
            index++
        }

        val body = buildJsonObject {
            put("model", JsonPrimitive(normalizedModel))
            put("max_tokens", JsonPrimitive(maxTokens))
            put("stream", JsonPrimitive(stream))
            if (systemText.isNotBlank()) {
                put("system", JsonPrimitive(systemText))
            }
            put("messages", JsonArray(anthropicMessages))
            if (!tools.isNullOrEmpty()) {
                put("tools", JsonArray(tools.map { tool ->
                    buildJsonObject {
                        put("name", JsonPrimitive(tool.function.name))
                        put("description", JsonPrimitive(tool.function.description))
                        put("input_schema", tool.function.parameters ?: emptyJsonSchema())
                    }
                }))
            }
        }
        return body.toString()
    }

    private fun parseJsonObjectOrEmpty(raw: String): JsonObject {
        if (raw.isBlank()) return buildJsonObject { }
        return runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrElse {
            buildJsonObject {
                put("value", JsonPrimitive(raw))
            }
        }
    }

    private fun emptyJsonSchema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { })
    }

    private fun buildRequest(
        messages: List<MessageDto>,
        model: String,
        thinkingMode: ThinkingMode,
        tools: List<ToolDefinitionDto>?,
        stream: Boolean,
        maxTokens: Int = 8192,
        compatibilityMode: Boolean = false,
    ): ChatCompletionRequest {
        // Custom OpenAI-compatible providers (e.g. self-hosted routers) don't understand
        // DeepSeek's thinking/reasoning_effort extensions — omit them entirely.
        val isCustomProvider = modelOverrideProvider() != null
        val omitToolProtocol = isCustomProvider && compatibilityMode
        val effectiveTools = if (omitToolProtocol) null else tools
        val normalizedModel = sanitizeProviderModelName(model)
        android.util.Log.e("NuclearBoy", "[ApiClient] buildRequest() model=$normalizedModel stream=$stream thinking=$thinkingMode tools=${effectiveTools?.size ?: 0} messages=${messages.size} maxTokens=$maxTokens custom=$isCustomProvider compat=$compatibilityMode")
        return ChatCompletionRequest(
            model = normalizedModel,
            messages = sanitizeMessages(
                messages = messages,
                isCustomProvider = isCustomProvider,
                omitToolProtocol = omitToolProtocol,
            ),
            temperature = 1.0,
            topP = 1.0,
            maxTokens = maxTokens,
            stream = stream,
            tools = effectiveTools,
            thinking = when {
                isCustomProvider -> null
                thinkingMode != ThinkingMode.DISABLED -> ThinkingConfigDto(type = "enabled")
                else -> ThinkingConfigDto(type = "disabled")  // Must be explicit: DeepSeek defaults to enabled!
            },
            reasoningEffort = if (isCustomProvider) null else when (thinkingMode) {
                ThinkingMode.HIGH -> "high"
                ThinkingMode.MAX -> "max"
                ThinkingMode.DISABLED -> null
            },
        )
    }

    /**
     * Strip reasoning_content and normalize tool fields before sending.
     * Official docs: "if the reasoning_content field is included in the
     * sequence of input messages, the API will return a 400 error."
     */
    private fun sanitizeMessages(
        messages: List<MessageDto>,
        isCustomProvider: Boolean,
        omitToolProtocol: Boolean,
    ): List<MessageDto> {
        return sanitizeChatMessagesForProvider(
            messages = messages,
            isCustomProvider = isCustomProvider,
            omitToolProtocol = omitToolProtocol,
        )
    }

    private fun buildHttpRequest(request: ChatCompletionRequest): okhttp3.Request {
        val body = json.encodeToString(ChatCompletionRequest.serializer(), request)
        android.util.Log.e("NuclearBoy", "[ApiClient] buildHttpRequest() bodySize=${body.length} bytes")
        val requestBody = body.toRequestBody("application/json".toMediaType())
        val endpoint = buildOpenAiChatCompletionsEndpoint(baseUrlProvider(), activeProviderEndpointMode())

        return okhttp3.Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()
    }

    private fun providerHttpError(code: Int): AppError = when (code) {
        400, 404, 405, 422 -> AppError.InvalidRequest
        401, 403 -> AppError.ApiKeyInvalid
        402 -> AppError.InsufficientBalance
        429 -> AppError.RateLimited
        in 500..599 -> AppError.ServerError
        else -> AppError.Unknown
    }

    private fun buildProviderHttpFailureDetail(
        httpCode: Int,
        endpoint: String,
        modelName: String,
        body: String,
    ): String {
        val providerMessage = parseProviderErrorMessage(body)
        val providerHint = providerSpecificEndpointHint(endpoint)
        val hint = when (httpCode) {
            400, 422 -> "请求格式或模型名可能不被网关接受。请核对模型名：$modelName。"
            401, 403 -> "鉴权失败。请检查 API Key、网关是否要求 Bearer Token，以及账号是否有该模型权限。"
            404 -> buildProviderNotFoundHint(endpoint, body, modelName)
            405 -> buildMethodNotAllowedHint(endpoint)
            402 -> "账号余额或额度不足。"
            429 -> "请求过快或额度被限流，稍后再试。"
            in 500..599 -> "网关服务端报错。请查看网关日志，确认上游模型可用。"
            else -> "网关返回了未识别的 HTTP 状态。"
        }
        return buildString {
            append("HTTP $httpCode")
            if (!providerMessage.isNullOrBlank()) append("：$providerMessage")
            append('\n')
            append(hint)
            if (providerHint.isNotBlank()) {
                append('\n')
                append(providerHint)
            }
            val preview = sanitizeProviderBody(body)
            if (preview.isNotBlank()) {
                append("\n返回片段：")
                append(preview)
            }
        }
    }

    private fun buildMethodNotAllowedHint(endpoint: String): String {
        val normalized = endpoint.trimEnd('/')
        val lower = normalized.lowercase()
        return when {
            lower.endsWith("/v1") ->
                "接口存在但不接受当前 POST 请求。你可能在“完整地址”模式里只填到了服务根路径 /v1。" +
                    "OpenAI 兼容网关请切换到“智能拼接”，或把完整地址改成 $normalized/chat/completions。"
            lower.endsWith("/anthropic") ->
                "接口存在但不接受当前 POST 请求。Anthropic 兼容网关的完整地址通常需要填到 $normalized/v1/messages。"
            lower.endsWith("/messages") ->
                "接口存在但不接受当前请求方法。请确认协议选择为 Anthropic，并确认网关支持 Messages POST。"
            lower.endsWith("/chat/completions") ->
                "接口存在但不接受当前请求方法。请确认协议选择为 OpenAI，并确认网关支持 Chat Completions POST。"
            else ->
                "接口存在但不接受当前 POST 请求。若你选择了“完整地址”，请确认它是最终接口路径：" +
                    "OpenAI 使用 /v1/chat/completions，Anthropic 使用 /v1/messages；否则请改用“智能拼接”。"
        }
    }

    private fun providerSpecificEndpointHint(endpoint: String): String {
        val lower = endpoint.lowercase()
        return when {
            "ark.cn-beijing.volces.com" in lower ->
                "火山 Ark 聊天补全请使用 https://ark.cn-beijing.volces.com/api/v3。"
            "api.minimaxi.com" in lower ->
                "MiniMax 可使用 OpenAI 兼容地址 https://api.minimaxi.com/v1，也可选择 Anthropic 协议并填写 https://api.minimaxi.com/anthropic。"
            lower.endsWith("/v1/messages") ->
                "当前请求使用 Anthropic Messages 协议；如果你的网关只支持 OpenAI，请把协议切到 OpenAI。"
            else -> ""
        }
    }

    private fun probeOpenAiModelList(
        baseUrl: String,
        endpointMode: ProviderEndpointMode,
        apiKey: String,
        requestedModel: String,
    ): String {
        val endpoint = buildOpenAiModelsEndpoint(baseUrl, endpointMode)
        if (endpoint.isBlank()) return "模型列表探测：服务地址为空，未请求 /v1/models。"
        return try {
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .get()
                .addHeader("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }
            diagnosticClient.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return "模型列表探测：GET $endpoint 返回 HTTP ${response.code}" +
                        providerModelListFailureSuffix(body)
                }
                val modelIds = parseProviderModelIds(body)
                if (modelIds.isEmpty()) {
                    "模型列表探测：GET $endpoint 成功，但没有解析到模型 id；请在网关后台确认模型列表格式。"
                } else {
                    val sampleLimit = 12
                    val sample = modelIds.take(sampleLimit).joinToString(", ")
                    val tail = if (modelIds.size > sampleLimit) " 等 ${modelIds.size} 个" else ""
                    val requestedState = if (modelIds.contains(requestedModel)) {
                        "当前模型在列表中"
                    } else {
                        "当前模型不在列表中，建议从示例里复制完整模型名"
                    }
                    "模型列表探测：GET $endpoint 成功，发现 ${modelIds.size} 个模型，$requestedState。可用示例：$sample$tail"
                }
            }
        } catch (e: IllegalArgumentException) {
            "模型列表探测：/v1/models 地址格式不正确：${e.message ?: "无法解析 URL"}。"
        } catch (e: IOException) {
            "模型列表探测：请求 /v1/models 失败：${e.message ?: e.javaClass.simpleName}。"
        } catch (e: Exception) {
            "模型列表探测：请求 /v1/models 异常：${e.message ?: e.javaClass.simpleName}。"
        }
    }

    private fun providerModelListFailureSuffix(body: String): String {
        val providerMessage = sanitizeProviderBody(parseProviderErrorMessage(body).orEmpty())
        val preview = sanitizeProviderBody(body)
        return buildString {
            if (providerMessage.isNotBlank()) {
                append("：")
                append(providerMessage.take(180))
            }
            append("。请确认网关是否开放 /v1/models，或在网关后台查看可用模型名。")
            if (preview.isNotBlank()) {
                append("\n模型列表返回片段：")
                append(preview)
            }
        }
    }

    private fun parseProviderErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            json.decodeFromString(DeepSeekErrorResponse.serializer(), body).error?.message
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeProviderBody(body: String): String {
        return body
            .replace(bearerTokenRegex, "Bearer <REDACTED_TOKEN>")
            .replace(skKeyRegex, "sk-<REDACTED_TOKEN>")
            .replace(whitespaceRegex, " ")
            .trim()
            .take(500)
    }

    private fun classifyError(e: Exception): AppError {
        val result = when (e) {
            is DeepSeekHttpException -> AppError.fromHttpCode(e.code)
            // OkHttp throws IllegalArgumentException before any network I/O when a
            // custom endpoint is malformed (for example, the `mock://` URL used by
            // local UI diagnostics). Retrying the same invalid configuration only
            // delays the actionable error and can make the UI look stuck.
            is IllegalArgumentException -> AppError.InvalidRequest
            is SSLException -> AppError.NetworkUnavailable
            is java.net.SocketTimeoutException -> AppError.NetworkTimeout
            is IOException -> {
                val msg = e.message?.lowercase() ?: ""
                when {
                    "timeout" in msg -> AppError.NetworkTimeout
                    "connect" in msg || "resolve" in msg -> AppError.NetworkUnavailable
                    "ssl" in msg -> AppError.NetworkUnavailable
                    else -> AppError.Unknown
                }
            }
            else -> AppError.Unknown
        }
        android.util.Log.e("NuclearBoy", "[ApiClient] classifyError() exceptionType=${e.javaClass.simpleName} message=${e.message} result=$result")
        return result
    }

    private fun retryDelayMillis(attempt: Int, exception: Exception): Long {
        // coerceIn(0,30) 防止 attempt 极大时 shl 溢出（Long 最大位移 63，Int 最大位移 31）
        val exponentialMs = AppConstants.RETRY_BASE_DELAY_MS * (1L shl attempt.coerceIn(0, 30).minus(1))
        val retryAfterMs = (exception as? DeepSeekHttpException)?.retryAfterMillis ?: 0L
        val boundedRetryAfterMs = retryAfterMs.coerceAtMost(MAX_RETRY_AFTER_DELAY_MS)
        return maxOf(exponentialMs, boundedRetryAfterMs) + random.nextInt(500).toLong()
    }

    private fun estimatePromptTokens(messages: List<MessageDto>): Long {
        val totalChars = messages.sumOf { (it.content?.length ?: 0) + (it.reasoningContent?.length ?: 0) }
        val result = totalChars / 3L
        android.util.Log.e("NuclearBoy", "[ApiClient] estimatePromptTokens() totalChars=$totalChars estimatedTokens=$result messages=${messages.size}")
        return result
    }

    // 脱敏正则只编译一次（错误上报路径可能高频触发）
    private val bearerTokenRegex = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
    private val skKeyRegex = Regex("sk-[A-Za-z0-9_-]+")
    private val whitespaceRegex = Regex("\\s+")

    fun close() {
        client.dispatcher.executorService.shutdown()
        diagnosticClient.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        diagnosticClient.connectionPool.evictAll()
    }

    companion object {
        internal const val MAX_RETRY_AFTER_DELAY_MS = 30_000L

        // 预编译：URL 路径版本号检测（如 /v1、/v2），避免每次构建端点时重复编译
        private val versionedPathRegex = Regex(""".*/v\d+$""")

        internal fun parseRetryAfterMillis(raw: String?): Long? {
            val seconds = raw?.trim()?.toLongOrNull() ?: return null
            return seconds
                .takeIf { it >= 0L }
                ?.times(1000L)
        }

        fun normalizeOpenAiBaseUrl(
            raw: String,
            endpointMode: ProviderEndpointMode = ProviderEndpointMode.AUTO,
        ): String {
            val sanitizedRaw = sanitizeProviderBaseUrl(raw)
            if (endpointMode == ProviderEndpointMode.EXACT) return sanitizedRaw
            var url = sanitizedRaw.trimEnd('/')
            if (url.isBlank()) return ""
            val lower = url.lowercase()
            if (lower.startsWith("https://api.minimaxi.com/anthropic")) {
                return "https://api.minimaxi.com"
            }
            if (lower.startsWith("https://ark.cn-beijing.volces.com/api/compatible")) {
                return "https://ark.cn-beijing.volces.com/api/v3"
            }
            val suffixes = listOf(
                "/v1/messages",
                "/v1/chat/completions",
                "/chat/completions",
                "/v1",
            )
            for (suffix in suffixes) {
                if (url.lowercase().endsWith(suffix)) {
                    url = url.dropLast(suffix.length).trimEnd('/')
                    break
                }
            }
            return url
        }

        fun buildOpenAiChatCompletionsEndpoint(
            raw: String,
            endpointMode: ProviderEndpointMode = ProviderEndpointMode.AUTO,
        ): String {
            if (endpointMode == ProviderEndpointMode.EXACT) return sanitizeProviderBaseUrl(raw)
            val baseUrl = normalizeOpenAiBaseUrl(raw).trimEnd('/')
            if (baseUrl.isBlank()) return ""
            val lower = baseUrl.lowercase()
            val hasVersionedPath = versionedPathRegex.matches(lower)
            return if (hasVersionedPath) {
                "$baseUrl/chat/completions"
            } else {
                "$baseUrl/v1/chat/completions"
            }
        }

        fun buildOpenAiModelsEndpoint(
            raw: String,
            endpointMode: ProviderEndpointMode = ProviderEndpointMode.AUTO,
        ): String {
            val sanitizedRaw = sanitizeProviderBaseUrl(raw).trimEnd('/')
            if (sanitizedRaw.isBlank()) return ""
            if (endpointMode == ProviderEndpointMode.EXACT) {
                val lower = sanitizedRaw.lowercase()
                return when {
                    lower.endsWith("/models") -> sanitizedRaw
                    lower.endsWith("/chat/completions") ->
                        sanitizedRaw.dropLast("/chat/completions".length).trimEnd('/') + "/models"
                    lower.endsWith("/completions") ->
                        sanitizedRaw.dropLast("/completions".length).trimEnd('/') + "/models"
                    lower.endsWith("/v1") -> "$sanitizedRaw/models"
                    else -> buildOpenAiModelsEndpoint(sanitizedRaw, ProviderEndpointMode.AUTO)
                }
            }
            val baseUrl = normalizeOpenAiBaseUrl(sanitizedRaw).trimEnd('/')
            if (baseUrl.isBlank()) return ""
            return if (versionedPathRegex.matches(baseUrl.lowercase())) {
                "$baseUrl/models"
            } else {
                "$baseUrl/v1/models"
            }
        }

        fun normalizeAnthropicBaseUrl(
            raw: String,
            endpointMode: ProviderEndpointMode = ProviderEndpointMode.AUTO,
        ): String {
            val sanitizedRaw = sanitizeProviderBaseUrl(raw)
            if (endpointMode == ProviderEndpointMode.EXACT) return sanitizedRaw
            var url = sanitizedRaw.trimEnd('/')
            if (url.isBlank()) return ""
            val suffixes = listOf(
                "/v1/messages",
                "/messages",
            )
            for (suffix in suffixes) {
                if (url.lowercase().endsWith(suffix)) {
                    url = url.dropLast(suffix.length).trimEnd('/')
                    break
                }
            }
            return url
        }

        fun buildAnthropicMessagesEndpoint(
            raw: String,
            endpointMode: ProviderEndpointMode = ProviderEndpointMode.AUTO,
        ): String {
            if (endpointMode == ProviderEndpointMode.EXACT) return sanitizeProviderBaseUrl(raw)
            val baseUrl = normalizeAnthropicBaseUrl(raw).trimEnd('/')
            if (baseUrl.isBlank()) return ""
            return if (baseUrl.lowercase().endsWith("/v1")) {
                "$baseUrl/messages"
            } else {
                "$baseUrl/v1/messages"
            }
        }
    }
}

/**
 * Extract the payload from one OpenAI-compatible SSE data line.
 *
 * SSE permits both `data: {...}` and `data:{...}`.  Gateways also commonly
 * append spaces after the field name, so trim only the field separator area and
 * leave JSON parsing to the caller.
 */
internal fun extractOpenAiSseData(line: String): String? {
    if (!line.startsWith("data:")) return null
    return line.substring("data:".length).trim()
}

/**
 * Reject an HTTP 200 response that was not a complete, useful SSE stream.
 * Returning a reason lets the caller route it through the existing retry/error
 * path instead of emitting a misleading successful empty response.
 */
internal fun validateOpenAiStream(
    validEventCount: Int,
    malformedEventCount: Int,
    sawDone: Boolean,
    sawFinishReason: Boolean,
    hasUsefulOutput: Boolean,
): String? {
    if (malformedEventCount > 0) {
        return "SSE stream contained $malformedEventCount malformed data event(s)"
    }
    if (validEventCount == 0) {
        return "SSE stream contained no valid data events"
    }
    if (!sawDone && !sawFinishReason) {
        return "SSE stream ended before a completion marker"
    }
    if (!hasUsefulOutput) {
        return "SSE stream completed without content or tool calls"
    }
    return null
}

// ── Supporting Types ──────────────────────────────────

internal val CUSTOM_PROVIDER_COMPATIBILITY_HTTP_CODES = setOf(400, 404, 422)
internal val CUSTOM_PROVIDER_TOOL_RETRY_FALLBACK_HTTP_CODES = setOf(500, 502, 503, 504)

internal fun shouldFallbackCustomProviderTools(
    isCustomProvider: Boolean,
    providerCompatibilityMode: Boolean,
    toolsPresent: Boolean,
    exception: Exception,
    retryCount: Int,
): Boolean {
    if (!isCustomProvider || providerCompatibilityMode || !toolsPresent) return false
    val httpException = exception as? DeepSeekHttpException ?: return false
    if (httpException.code in CUSTOM_PROVIDER_COMPATIBILITY_HTTP_CODES) return true
    return retryCount >= 1 && httpException.code in CUSTOM_PROVIDER_TOOL_RETRY_FALLBACK_HTTP_CODES
}

internal fun sanitizeChatMessagesForProvider(
    messages: List<MessageDto>,
    isCustomProvider: Boolean,
    omitToolProtocol: Boolean,
): List<MessageDto> {
    val sanitizedMessages = messages.mapNotNull { message ->
        if (omitToolProtocol && message.role == "tool") {
            val toolName = message.name?.takeIf { it.isNotBlank() } ?: "unknown_tool"
            return@mapNotNull MessageDto(
                role = "user",
                content = "工具结果（$toolName）：\n${message.content.orEmpty()}",
            )
        }

        // Skip allocation when no fields actually change
        val noChange = message.reasoningContent == null &&
            (!omitToolProtocol || (message.toolCalls == null && message.toolCallId == null && message.name == null))
        val sanitized = if (noChange) message else message.copy(
            // DeepSeek 官方规则：输入 messages 含 reasoning_content 会返回 400，
            // 所有协议（含官方 DeepSeek）一律剥离后再发送。
            reasoningContent = null,
            toolCalls = if (omitToolProtocol) null else message.toolCalls,
            toolCallId = if (omitToolProtocol) null else message.toolCallId,
            name = if (omitToolProtocol) null else message.name,
        )

        val becameEmptyAssistant = isCustomProvider &&
            sanitized.role == "assistant" &&
            sanitized.content.isNullOrBlank() &&
            sanitized.toolCalls.isNullOrEmpty()
        if (becameEmptyAssistant) null else sanitized
    }
    return if (omitToolProtocol) {
        sanitizedMessages.withCompatibilityToolLimitNotice()
    } else {
        sanitizedMessages
    }
}

internal const val COMPATIBILITY_TOOL_LIMIT_NOTICE =
    "【兼容聊天模式限制】当前第三方网关本轮没有可用工具调用协议，不能调用 read_file、write_file、list_directory、run_python 等工具。" +
        "只能基于用户输入和此前明确标为“工具结果”的文本继续回答；不得编造工具输出，不得声称已经读取、写入、执行、安装、测试或验证文件。" +
        "如果用户要求新的读写、执行或测试，必须明确说明当前网关不支持工具调用，尚未真实执行，并建议切换支持工具调用的模型/网关或让用户提供文件内容。"

internal const val COMPATIBILITY_TOOL_FINAL_NOTICE =
    "工具兼容降级提醒：你现在没有真实工具调用能力。不要输出 [TOOL_CALL]、伪代码块或模拟工具结果；" +
        "如果当前用户要求读取、写入、执行、测试或验证，只能回答工具受限且尚未真实执行。"

private fun List<MessageDto>.withCompatibilityToolLimitNotice(): List<MessageDto> {
    val firstSystemIndex = indexOfFirst { it.role == "system" }
    return buildList(size + 2) {
        if (firstSystemIndex < 0) {
            add(MessageDto(role = "system", content = COMPATIBILITY_TOOL_LIMIT_NOTICE))
        }
        for ((index, message) in this@withCompatibilityToolLimitNotice.withIndex()) {
            if (index == firstSystemIndex) {
                add(message.copy(
                    content = listOfNotNull(
                        message.content?.takeIf { it.isNotBlank() },
                        COMPATIBILITY_TOOL_LIMIT_NOTICE,
                    ).joinToString("\n\n"),
                ))
            } else {
                add(message)
            }
        }
        add(MessageDto(role = "user", content = COMPATIBILITY_TOOL_FINAL_NOTICE))
    }
}

data class ProviderTestResult(
    val endpoint: String,
    val modelName: String,
    val protocol: ProviderProtocol,
    val endpointMode: ProviderEndpointMode,
    val latencyMs: Long,
    val replyPreview: String,
)

data class ProviderFormalChatTestResult(
    val endpoint: String,
    val modelName: String,
    val protocol: ProviderProtocol,
    val endpointMode: ProviderEndpointMode,
    val latencyMs: Long,
    val stream: Boolean,
    val toolsRequested: Boolean,
    val compatibilityRetry: Boolean,
    val sawContent: Boolean,
    val sawToolEvent: Boolean,
    val lineCount: Int,
    val replyPreview: String,
)

data class ProviderModelListResult(
    val endpoint: String,
    val modelIds: List<String>,
    val latencyMs: Long,
)

sealed class StreamEvent {
    data class Thinking(val message: String) : StreamEvent()
    data class Content(val text: String, val isReasoning: Boolean = false) : StreamEvent()
    data class ToolCallRequest(val toolCalls: List<ToolCallDto>) : StreamEvent()
    data class ToolCallDelta(val deltas: List<ToolCallDeltaDto>) : StreamEvent()
    data class Complete(val usage: UsageDto) : StreamEvent()
    data class Error(val appError: AppError, val technicalDetail: String?) : StreamEvent()

    /**
     * The in-flight HTTP request failed mid-stream and is being retried from
     * scratch (new request, new SSE stream). Collectors must discard any
     * content/reasoning/tool-call state accumulated so far for this response —
     * otherwise the retried stream's re-emitted events get appended after the
     * partial content from the failed attempt, duplicating the reply.
     */
    data object ContentReset : StreamEvent()
}

class DeepSeekHttpException(
    val code: Int,
    message: String,
    val errorType: String?,
    val retryAfterMillis: Long? = null,
) : IOException(message)
