package com.nuclearboy.agent

import com.nuclearboy.api.deepseek.*
import com.nuclearboy.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

// ── Agent Events ────────────────────────────────────────

/**
 * Events emitted by [AgentEngine.run] during the agent loop.
 * The UI layer observes these to render the chat interface.
 */
sealed class AgentEvent {
    /** Thinking/reasoning content being streamed from the model. */
    data class Thinking(val message: String) : AgentEvent()

    /** A chunk of regular content being streamed. */
    data class StreamContent(val text: String) : AgentEvent()

    /** A complete assistant response message. */
    data class Response(val message: ChatMessage) : AgentEvent()

    /** A tool is being executed. */
    data class ToolExecution(
        val toolName: String,
        val status: ToolCallStatus,
        val toolCallId: String = "",
        val input: String = "",
    ) : AgentEvent()

    /** The result of a tool execution. */
    data class ToolResultEvent(
        val toolName: String,
        val result: ToolResult,
        val toolCallId: String = "",
    ) : AgentEvent()

    /** Files were changed by a tool execution. */
    data class FileChanged(val changes: List<FileChange>) : AgentEvent()

    /** Context window warning. */
    data class ContextWarning(
        val level: ContextWarningLevel,
        val message: String,
    ) : AgentEvent()

    /** Token usage updated (for the HUD). */
    data class TokenUpdate(val usage: TokenUsage) : AgentEvent()

    /** An error occurred during processing. */
    data class Error(
        val error: AppError,
        val detail: String? = null,
    ) : AgentEvent()

    /** Agent loop completed successfully. */
    data class Complete(val stats: SessionStats) : AgentEvent()

    /** The agent is retrying after an error. */
    data class Retrying(val attempt: Int, val reason: String) : AgentEvent()

    /**
     * The current response is being re-streamed from scratch after a mid-stream
     * failure. UI layers must discard any partial content/reasoning shown for the
     * in-progress assistant message before more [StreamContent]/[Thinking] arrive.
     */
    data object ContentReset : AgentEvent()
}

// ── Project Context ─────────────────────────────────────

/**
 * Context provided to the agent for a run, describing the project environment.
 */
data class ProjectContext(
    val project: Project?,
    val currentFiles: List<FileInfo>,
    val userProfile: UserProfile,
    val activeSkills: List<SkillInfo>,
    val memoryContext: String = "",
    /** 用户在设置里自定义的附加指令（自定义人设/规则），追加进系统提示 */
    val customInstructions: String = "",
)

/** The model tier + thinking mode selected for a run, and why. */
data class RouteDecision(
    val modelTier: ModelTier,
    val thinkingMode: ThinkingMode,
    val reason: String,
)

// ── Tool Call Accumulator ───────────────────────────────

/**
 * Accumulates tool call fragments from streaming SSE deltas
 * into complete [ToolCallDto] objects.
 */
private class ToolCallAccumulator {
    private val partialCalls = mutableMapOf<Int, ToolCallBuilder>()

    data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    fun feed(deltas: List<ToolCallDeltaDto>?) {
        deltas?.forEach { delta ->
            val builder = partialCalls.getOrPut(delta.index) { ToolCallBuilder() }
            delta.id?.let { builder.id = it }
            delta.function?.name?.let { builder.name = it }
            delta.function?.arguments?.let { builder.arguments.append(it) }
        }
        // 热路径：每个SSE delta都调用，不打逐次日志，只在 100 次时打里程碑
    }

    fun toCompletedCalls(): List<ToolCallDto> {
        val completed = partialCalls.values
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
            .map { builder ->
                ToolCallDto(
                    id = builder.id,
                    function = FunctionCallDto(
                        name = builder.name,
                        arguments = builder.arguments.toString(),
                    )
                )
            }
        return completed
    }

    fun hasPartialCalls(): Boolean = partialCalls.isNotEmpty()

    fun clear() {
        partialCalls.clear()
    }
}

// ── Agent Engine ────────────────────────────────────────

/**
 * The core agent loop for 核弹男孩 (NUCLEAR BOY).
 *
 * Implements a ReAct-style loop:
 * 1. Send user message + context to DeepSeek API
 * 2. Receive response (may include tool calls)
 * 3. Execute any requested tools
 * 4. Feed tool results back to the model
 * 5. Repeat until the model produces a final answer (no more tool calls)
 *
 * CRITICAL: Reasoning content is stripped from messages between turns
 * (see [DeepSeekApiClient.sanitizeMessages]). This prevents 400 errors
 * from the DeepSeek API which rejects reasoning_content on assistant messages.
 *
 * Context window management:
 * - Before each API call, checks if context is in warning zones
 * - Emits [AgentEvent.ContextWarning] when approaching limits
 * - Automatically compresses conversation history when in red zone
 *
 * Thread safety: All public methods are safe to call from any coroutine context.
 * Internal state is guarded by a mutex.
 */
class AgentEngine(
    private val apiClient: DeepSeekApiClient,
    private val toolRegistry: ToolRegistry,
    private val contextManager: ContextWindowManager,
    private val tokenTracker: TokenTracker,
    var memoryStore: com.nuclearboy.memory.MemoryStore? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    /** 单个工具结果注入对话的最大字符数；超出截断（读大文件/长目录列表的主要膨胀源）。 */
    private val maxToolOutputChars get() = MAX_TOOL_OUTPUT_CHARS

    /**
     * 发送前对话负载的真实 token 上限（保守值，给回复留余量）。
     * 注意：这是面向真实模型窗口的硬保护，与 ContextWindowManager 的 UI 计数无关。
     */
    private val maxPayloadTokens get() = MAX_PAYLOAD_TOKENS

    // ── Public API ───────────────────────────────────────

    /** Callback invoked when the agent run is cancelled — e.g. to interrupt Python. */
    var onCancel: (() -> Unit)? = null

    /**
     * Run the agent loop for a single user message.
     *
     * @param userMessage The user's input text.
     * @param projectContext Information about the current project, files, and user profile.
     * @param conversationHistory Previous messages in the conversation (excluding system prompt).
     * @return A cold [Flow] of [AgentEvent] that the UI layer collects.
     */
    fun run(
        userMessage: String,
        projectContext: ProjectContext,
        conversationHistory: List<ChatMessage> = emptyList(),
        userMode: Int = 0,
    ): Flow<AgentEvent> = flow {
        val startTimeMs = System.currentTimeMillis()
        android.util.Log.e("NuclearBoy", "[AgentEngine] run() ENTRY userMsgLen=${userMessage.length} hasProject=${projectContext.project != null} fileCount=${projectContext.currentFiles.size} skillCount=${projectContext.activeSkills.size} historySize=${conversationHistory.size}")

        // Reset tool call accumulator for this run
        val accumulator = ToolCallAccumulator()

        // 记忆上下文由 ChatViewModel 在调用前注入到 projectContext.memoryContext
        val memoryContext = projectContext.memoryContext

        // Build system prompt
        val systemPrompt = SystemPromptBuilder.build(
            userProfile = projectContext.userProfile,
            project = projectContext.project,
            currentFiles = projectContext.currentFiles,
            activeSkills = projectContext.activeSkills,
            memoryContext = memoryContext,
            customInstructions = projectContext.customInstructions,
        )

        // User manual mode: 0=Chat(Flash+NonThink) 1=Think(Pro+High) 2=Expert(Pro+Max)
        val routeDecision = when (userMode) {
            1 -> RouteDecision(ModelTier.V4_PRO, ThinkingMode.HIGH, "思考模式")
            2 -> RouteDecision(ModelTier.V4_PRO, ThinkingMode.MAX, "专家模式")
            else -> RouteDecision(ModelTier.V4_FLASH, ThinkingMode.DISABLED, "聊天模式")
        }
        // 合并 setup 阶段日志为一条；路由信息只写日志不 emit 给 UI（避免 ThinkingBar 显示内部调试）
        android.util.Log.e("NuclearBoy", "[AgentEngine] run() setup memoryLen=${memoryContext.length} systemPromptLen=${systemPrompt.length} route=${routeDecision.modelTier}/${routeDecision.thinkingMode}(${routeDecision.reason})")

        // Build initial messages list
        val messages = mutableListOf<MessageDto>()

        // 1. System prompt
        messages.add(
            MessageDto(
                role = "system",
                content = systemPrompt,
            )
        )
        contextManager.updateAllocation(
            systemPrompt = contextManager.estimateTokens(systemPrompt),
        )

        // 2. Conversation history (most recent first, truncated to budget)
        val historyDtos = buildHistoryMessages(conversationHistory)
        messages.addAll(historyDtos)

        // 3. Current user message
        messages.add(
            MessageDto(
                role = "user",
                content = userMessage,
            )
        )

        // 4. Attach file contents if present
        val fileContents = buildFileContextStrings(projectContext.currentFiles)
        if (fileContents.isNotEmpty()) {
            val lastUserMsg = messages.last()
            messages[messages.lastIndex] = lastUserMsg.copy(
                content = (lastUserMsg.content ?: "") + "\n\n" + fileContents
            )
        }

        val historyTokens = historyDtos.sumOf {
            ((it.content?.length ?: 0) + (it.reasoningContent?.length ?: 0)) / 3L
        }
        val userTokens = (userMessage.length + fileContents.length) / 3L
        contextManager.updateAllocation(
            conversationHistory = historyTokens.coerceAtMost(AppConstants.BUDGET_CONVERSATION_HISTORY),
        )

        // ── Main Tool-Use Loop ───────────────────────────
        val toolDefs = toolRegistry.toDeepSeekToolDefinitions()
        android.util.Log.e("NuclearBoy", "[AgentEngine] run() assembled msgs=${messages.size}(hist=${historyDtos.size}) fileBytes=${fileContents.length} histTok=$historyTokens tools=${toolDefs.size}")

        var iteration = 0
        var finalResponse: ChatMessage? = null
        var contextTrimNotified = false
        val toolCallLoopGuard = ToolCallLoopGuard()
        val retryableErrorGate = RetryableErrorGate()

        while (finalResponse == null && currentCoroutineContext().isActive && iteration < MAX_TOOL_ITERATIONS) {
            iteration++
            val iterStartMs = System.currentTimeMillis()
            android.util.Log.e("NuclearBoy", "[AgentEngine] run() iteration=$iteration contextUsed=${contextManager.budget.value.totalUsed}")

            // 发送前把对话负载真实裁剪到模型窗口内。
            // 旧实现只改 ContextWindowManager 的计数器、却谎报"已压缩 ✨"而不动真实 payload；
            // 这里整组丢弃最早的工具往返（保 system / 所有 user / 最近一组），保证 tool_call 配对不破。
            val trimmed = trimMessagesForPayload(messages)
            if (trimmed > 0 && !contextTrimNotified) {
                contextTrimNotified = true
                android.util.Log.e("NuclearBoy", "[AgentEngine] run() trimMessagesForPayload removed=$trimmed msgs")
                emit(
                    AgentEvent.ContextWarning(
                        level = ContextWarningLevel.YELLOW,
                        message = "对话有点长了，我把较早的几轮工具记录精简掉了，保留最近的上下文继续帮你 ✨",
                    )
                )
            }

            // Call the API
            val responseContent = StringBuilder()
            val reasoningContent = StringBuilder()
            var toolCallsDetected = false
            var toolProtocolUnavailable = false

            emit(AgentEvent.Thinking(if (iteration == 1) "正在思考…" else "继续处理…"))

            try {
                // Use the streaming API — accumulate content and tool calls
                android.util.Log.e("NuclearBoy", "[AgentEngine] run() callApiStreaming iteration=$iteration messages=${messages.size} modelTier=${routeDecision.modelTier} thinkingMode=${routeDecision.thinkingMode} hasTools=${toolDefs.isNotEmpty()}")
                val streamResult = callApiStreaming(
                    messages = messages.toList(),
                    modelTier = routeDecision.modelTier,
                    thinkingMode = routeDecision.thinkingMode,
                    tools = toolDefs.ifEmpty { null },
                    accumulator = accumulator,
                    reasoningContent = reasoningContent,
                    responseContent = responseContent,
                )

                // Collect emitted events and forward agent-relevant ones
                var contentEventCount = 0
                streamResult.collect { streamEvent ->
                    when (streamEvent) {
                        is StreamEvent.Thinking -> {
                            if (streamEvent.message.contains("兼容聊天模式")) {
                                toolProtocolUnavailable = true
                            }
                            emit(AgentEvent.Thinking(streamEvent.message))
                        }
                        is StreamEvent.Content -> {
                            contentEventCount++
                            if (streamEvent.isReasoning) {
                                if (reasoningContent.length > 0 && (reasoningContent.length % 500) < streamEvent.text.length) {
                                    android.util.Log.e("NuclearBoy", "[AgentEngine] run() reasoning milestone=${reasoningContent.length + streamEvent.text.length} chars")
                                }
                                emit(AgentEvent.Thinking(streamEvent.text))
                            } else {
                                if (responseContent.length > 0 && (responseContent.length % 500) < streamEvent.text.length) {
                                    android.util.Log.e("NuclearBoy", "[AgentEngine] run() content milestone=${responseContent.length + streamEvent.text.length} chars")
                                }
                                emit(AgentEvent.StreamContent(streamEvent.text))
                            }
                        }
                        is StreamEvent.ToolCallRequest -> {
                            android.util.Log.e("NuclearBoy", "[AgentEngine] run() ToolCallRequest detected, toolCount=${streamEvent.toolCalls.size}")
                            toolCallsDetected = true
                        }
                        is StreamEvent.ToolCallDelta -> {
                            toolCallsDetected = true
                        }
                        is StreamEvent.Complete -> {
                            android.util.Log.e("NuclearBoy", "[AgentEngine] run() Stream Complete usage=prompt=${streamEvent.usage.promptTokens} completion=${streamEvent.usage.completionTokens} total=${streamEvent.usage.totalTokens}")
                            // Emit token usage
                            val usage = TokenUsage(
                                promptTokens = streamEvent.usage.promptTokens,
                                completionTokens = streamEvent.usage.completionTokens,
                                totalTokens = streamEvent.usage.totalTokens,
                                cachedPromptTokens = streamEvent.usage.promptTokensDetails?.cachedTokens ?: 0,
                                reasoningTokens = streamEvent.usage.completionTokensDetails?.reasoningTokens ?: 0,
                                estimatedCostUsd = tokenTracker.snapshot.value.estimatedCostUsd,
                            )
                            emit(AgentEvent.TokenUpdate(usage))
                        }
                        is StreamEvent.Error -> {
                            android.util.Log.e("NuclearBoy", "[AgentEngine] run() Stream Error appError=${streamEvent.appError} detailLen=${streamEvent.technicalDetail?.length ?: 0}")
                            emit(AgentEvent.Error(streamEvent.appError, streamEvent.technicalDetail))
                        }
                        is StreamEvent.ContentReset -> {
                            android.util.Log.e("NuclearBoy", "[AgentEngine] run() ContentReset — mid-stream retry, discarding partial content")
                            emit(AgentEvent.ContentReset)
                        }
                    }
                }

                android.util.Log.e("NuclearBoy", "[AgentEngine] run() stream finished iteration=$iteration contentEvents=$contentEventCount responseLen=${responseContent.length} reasoningLen=${reasoningContent.length} toolCallsDetected=$toolCallsDetected iterTimeMs=${System.currentTimeMillis() - iterStartMs}")
                retryableErrorGate.onSuccessfulApiRound()

                // After the stream completes, check for tool calls
                if (toolCallsDetected && accumulator.hasPartialCalls()) {
                    android.util.Log.e("NuclearBoy", "[AgentEngine] run() entering tool execution block, hasPartialCalls=${accumulator.hasPartialCalls()}")
                    val toolCalls = accumulator.toCompletedCalls()
                    accumulator.clear()

                    if (toolCalls.isNotEmpty()) {
                        val loopObservation = toolCallLoopGuard.observe(toolCalls)
                        if (loopObservation.shouldStop) {
                            android.util.Log.e(
                                "NuclearBoy",
                                "[AgentEngine] run() duplicate tool-call loop stopped count=${loopObservation.consecutiveDuplicateBatches}/${loopObservation.maxConsecutiveDuplicateBatches} tools=${loopObservation.toolSummary}",
                            )
                            finalResponse = ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = "我连续 ${loopObservation.consecutiveDuplicateBatches} 次准备执行同一组工具调用（${loopObservation.toolSummary}），这通常表示任务陷入了重复操作。为避免继续浪费时间或重复改动，我先停在这里。你可以换个描述、缩小目标，或指定下一步要我做什么。",
                                status = MessageStatus.ERROR,
                            )
                            break
                        }
                        // Add the assistant message with tool calls to history
                        val assistantMsg = MessageDto(
                            role = "assistant",
                            content = responseContent.toString().ifEmpty { null },
                            reasoningContent = reasoningContent.toString().ifEmpty { null },
                            toolCalls = toolCalls,
                        )
                        messages.add(assistantMsg)

                        // Execute each tool and add results
                        for ((toolIdx, toolCall) in toolCalls.withIndex()) {
                            val toolName = toolCall.function.name.trim()  // 防止模型偶尔生成带空白的工具名导致找不到
                            val toolStartMs = System.currentTimeMillis()
                            android.util.Log.e("NuclearBoy", "[AgentEngine] run() executing tool[$toolIdx] name=$toolName id=${toolCall.id} argsLen=${toolCall.function.arguments.length}")
                            val params = try {
                                parseToolParams(toolCall.function.arguments)
                            } catch (e: Exception) {
                                android.util.Log.e("NuclearBoy", "[AgentEngine] run() tool[$toolIdx] parseToolParams FAILED: ${e.message}")
                                emptyMap()
                            }

                            emit(
                                AgentEvent.ToolExecution(
                                    toolName = toolName,
                                    status = ToolCallStatus.RUNNING,
                                    toolCallId = toolCall.id,
                                    input = toolCall.function.arguments,
                                )
                            )

                            val result = toolRegistry.executeSafe(toolName, params)
                            val toolElapsedMs = System.currentTimeMillis() - toolStartMs

                            val status = if (result.success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
                            android.util.Log.e("NuclearBoy", "[AgentEngine] run() tool[$toolIdx] result name=$toolName success=${result.success} outputLen=${result.output.length} fileChanges=${result.fileChanges.size} error=${result.error} elapsedMs=$toolElapsedMs")
                            emit(
                                AgentEvent.ToolExecution(
                                    toolName = toolName,
                                    status = status,
                                    toolCallId = toolCall.id,
                                    input = toolCall.function.arguments,
                                )
                            )
                            emit(
                                AgentEvent.ToolResultEvent(
                                    toolName = toolName,
                                    result = result,
                                    toolCallId = toolCall.id,
                                )
                            )

                            // Emit file changes if any
                            if (result.fileChanges.isNotEmpty()) {
                                android.util.Log.e("NuclearBoy", "[AgentEngine] run() tool[$toolIdx] fileChanges count=${result.fileChanges.size} types=${result.fileChanges.map { it.changeType }}")
                                emit(AgentEvent.FileChanged(result.fileChanges))
                            }

                            // Add tool result message（超长输出截断，单个工具结果不撑爆 payload）
                            messages.add(
                                MessageDto(
                                    role = "tool",
                                    content = capToolOutput(
                                        if (result.success) result.output
                                        else "错误: ${result.error ?: "未知错误"}"
                                    ),
                                    toolCallId = toolCall.id,
                                    name = toolName,
                                )
                            )
                        }

                        // Loop back for another API call (model may have more tool calls or a final answer)
                        android.util.Log.e("NuclearBoy", "[AgentEngine] run() tool execution done, looping back for iteration ${iteration + 1}")
                        contextManager.incrementTurn()
                        continue
                    }
                }

                // No tool calls detected → this is the final response
                val finalContent = buildFinalContent(
                    userMessage = userMessage,
                    responseContent = responseContent.toString(),
                    toolProtocolUnavailable = toolProtocolUnavailable,
                )
                android.util.Log.e("NuclearBoy", "[AgentEngine] run() final response reached, responseLen=${responseContent.length} finalLen=${finalContent.length} reasoningLen=${reasoningContent.length} toolProtocolUnavailable=$toolProtocolUnavailable")
                finalResponse = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = finalContent,
                    reasoningContent = reasoningContent.toString().ifEmpty { null },
                    status = MessageStatus.COMPLETE,
                    tokenUsage = TokenUsage(
                        promptTokens = tokenTracker.snapshot.value.promptTokensThisRequest,
                        completionTokens = tokenTracker.snapshot.value.completionTokensThisRequest,
                        totalTokens = tokenTracker.snapshot.value.promptTokensThisRequest +
                                tokenTracker.snapshot.value.completionTokensThisRequest,
                        cachedPromptTokens = tokenTracker.snapshot.value.cachedTokensThisRequest,
                        reasoningTokens = tokenTracker.snapshot.value.reasoningTokensThisRequest,
                        estimatedCostUsd = tokenTracker.snapshot.value.estimatedCostUsd,
                    ),
                )

                // Add final assistant message to messages for history
                messages.add(
                    MessageDto(
                        role = "assistant",
                        content = finalResponse.content,
                        reasoningContent = finalResponse.reasoningContent,
                    )
                )

            } catch (e: CancellationException) {
                // 取消必须向上传播（绝不吞）：否则结构化并发被破坏，用户"停止"后任务仍跑完。
                // 友好的 CANCELLED 状态由收集方 ChatViewModel.executeTurn 的 catch 负责展示。
                android.util.Log.e("NuclearBoy", "[AgentEngine] run() CancellationException — propagating, iteration=$iteration")
                onCancel?.invoke() // 即使仅 flow 被取消也确保中断 Python 等长任务
                throw e

            } catch (e: Exception) {
                val appError = classifyException(e)
                android.util.Log.e("NuclearBoy", "[AgentEngine] run() Exception caught iteration=$iteration type=${e.javaClass.simpleName} message=${e.message} appError=${appError} isRetryable=${appError.isRetryable}")

                when (val decision = retryableErrorGate.classify(appError)) {
                    is RetryDecision.Retry -> {
                        android.util.Log.e("NuclearBoy", "[AgentEngine] run() retrying attempt=${decision.attempt}/${decision.maxAttempts} iteration=$iteration reason=${appError.humanMessage}")
                        emit(AgentEvent.Retrying(decision.attempt, appError.humanMessage))
                        delay(1000)
                        continue
                    }
                    RetryDecision.FinalError -> Unit
                }

                android.util.Log.e("NuclearBoy", "[AgentEngine] run() non-retryable error, breaking loop")
                emit(AgentEvent.Error(appError, e.message))
                finalResponse = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "抱歉，处理过程中遇到了问题：${appError.humanMessage}",
                    status = MessageStatus.ERROR,
                )
                break
            }
        }

        // Emit the final response if we have one
        // 若达到工具循环上限且仍无最终回复，合成一条友好提示
        if (finalResponse == null && iteration >= MAX_TOOL_ITERATIONS) {
            finalResponse = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "已连续执行 $MAX_TOOL_ITERATIONS 轮工具调用，超出单次任务上限，自动停止。如果任务未完成，可以继续追问我或用 /loop 继续推进。",
                status = MessageStatus.COMPLETE,
            )
        }
        finalResponse?.let { response ->
            android.util.Log.e("NuclearBoy", "[AgentEngine] run() emitting final response status=${response.status} contentLen=${response.content.length} reasoningLen=${response.reasoningContent?.length ?: 0}")
            emit(AgentEvent.Response(response))
        }

        // Emit completion with session stats
        val stats = tokenTracker.getSessionStats()
        val totalElapsedMs = System.currentTimeMillis() - startTimeMs
        android.util.Log.e("NuclearBoy", "[AgentEngine] run() EXIT requests=${stats.requestCount} prompt=${stats.totalPromptTokens} completion=${stats.totalCompletionTokens} cost=${stats.totalCostUsd} elapsedMs=$totalElapsedMs")
        emit(AgentEvent.Complete(stats))
    }.flowOn(Dispatchers.IO)

    private fun buildFinalContent(
        userMessage: String,
        responseContent: String,
        toolProtocolUnavailable: Boolean,
    ): String {
        if (!toolProtocolUnavailable) return responseContent
        if (!shouldBlockCompatibilityToolClaim(userMessage, responseContent)) return responseContent
        return "工具受限，未真实执行。当前第三方网关不支持工具调用协议，本轮不能读取、写入、运行或测试；请切换支持工具调用的模型/网关后重试。"
    }

    private fun shouldBlockCompatibilityToolClaim(
        userMessage: String,
        responseContent: String,
    ): Boolean {
        if (responseContent.contains("[TOOL_CALL]", ignoreCase = true)) return true
        // A compatibility response is allowed to discuss files, testing, or
        // execution in general.  The old implementation looked at every word
        // in the user message and replaced ordinary answers with an error.  Only
        // block an explicit claim that an unavailable tool was actually used.
        val lowerResponse = responseContent.lowercase()
        val executionClaim = Regex(
            "(?:已|已经|刚刚|成功地?)\\s*(?:读取|写入|创建|修改|删除|运行|执行|测试|验证|安装)"
        )
        return executionClaim.containsMatchIn(lowerResponse) ||
            listOf("工具结果：", "tool result:", "read_file(", "write_file(", "run_python(")
                .any { lowerResponse.contains(it) }
    }

    /** 工具输出超长则截断，保留首部并标注被截字符数，避免单条结果撑爆 payload。 */
    private fun capToolOutput(text: String): String {
        if (text.length <= maxToolOutputChars) return text
        val dropped = text.length - maxToolOutputChars
        return text.take(maxToolOutputChars) + "\n…（输出过长，已截断 $dropped 字符。如需完整内容请缩小范围或分页查看）"
    }

    /**
     * 发送前把 [messages] 真实裁剪到 [maxPayloadTokens] 以内：从最早开始**整组**丢弃
     * assistant(带 tool_calls) + 其后连续 tool 结果。保留 system、所有 user 消息，以及
     * 最近一组工具往返；整组丢弃保证 tool_call/tool 配对不被破坏（否则 API 400）。
     * reasoning_content 不计入（出站本就被 sanitize 剥离）。返回被丢弃的消息条数。
     */
    private fun trimMessagesForPayload(messages: MutableList<MessageDto>): Int {
        fun MessageDto.tokenEstimate(): Long =
            ((content?.length ?: 0) +
                (toolCalls?.sumOf { tc -> tc.function.arguments.length } ?: 0)) / 3L

        // Fix 5: compute once, update incrementally rather than re-scanning on every iteration
        var currentEstimate = messages.sumOf { it.tokenEstimate() }
        if (currentEstimate <= maxPayloadTokens) return 0

        // Fix 6: pre-collect tool-call group starts once to avoid per-iteration linear tail-scan
        val toolGroupMessages = messages.filter { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
        var toolGroupIdx = 0

        var removed = 0
        while (currentEstimate > maxPayloadTokens) {
            val startIdx = messages.indexOfFirst {
                it.role == "assistant" && !it.toolCalls.isNullOrEmpty()
            }
            if (startIdx <= 0) break // 只剩 system/user，无可丢弃的工具往返
            var endIdx = startIdx
            while (endIdx + 1 <= messages.lastIndex && messages[endIdx + 1].role == "tool") endIdx++
            // 若这已是最后一组工具往返，保留它（模型需要最近上下文继续），停止
            // Fix 6: O(1) check using pre-built list instead of O(n) tail-scan each iteration
            val hasLaterGroup = toolGroupIdx + 1 < toolGroupMessages.size
            if (!hasLaterGroup) break
            // Fix 5: subtract removed tokens before clearing
            val removedTokens = messages.subList(startIdx, endIdx + 1).sumOf { it.tokenEstimate() }
            // Fix 4: remove whole range atomically in O(n) instead of O(n²) repeated removeAt
            messages.subList(startIdx, endIdx + 1).clear()
            currentEstimate -= removedTokens
            removed += endIdx - startIdx + 1
            toolGroupIdx++
        }
        return removed
    }

    // ── Conversation History ─────────────────────────────

    /**
     * Build a list of [MessageDto] from conversation history,
     * respecting the context budget.
     *
     * CRITICAL: reasoning_content is stripped from all assistant messages
     * per DeepSeek API requirements.
     */
    private fun buildHistoryMessages(history: List<ChatMessage>): List<MessageDto> {
        android.util.Log.e("NuclearBoy", "[AgentEngine] buildHistoryMessages() ENTRY historyCount=${history.size} budget=${AppConstants.BUDGET_CONVERSATION_HISTORY}")
        if (history.isEmpty()) {
            android.util.Log.e("NuclearBoy", "[AgentEngine] buildHistoryMessages() EXIT empty history, returning empty")
            return emptyList()
        }

        // ArrayDeque.addFirst 是 O(1)；原先用 ArrayList.add(0,…) 头插每次 O(n)、整段 O(n²)
        val result = ArrayDeque<MessageDto>()
        var tokenBudget = AppConstants.BUDGET_CONVERSATION_HISTORY
        var tokensUsed = 0L
        var historyCallIdx = 0

        // Iterate from most recent to oldest
        // Fix 7: use forEachIndexed so the index is available without O(n) indexOf() call
        val reversedHistory = history.reversed()
        for ((reversedIdx, msg) in reversedHistory.withIndex()) {
            val originalIdx = history.lastIndex - reversedIdx
            // Skip standalone TOOL messages — tool results are now generated from
            // assistant messages' toolCalls below. Old conversations may have both,
            // which causes "Duplicate tool_call_id" errors from the API.
            if (msg.role == MessageRole.TOOL) {
                // 跳过旧格式独立 TOOL 消息（现在从 assistant.toolCalls 重建），只统计不单条打日志
                continue
            }

            // 跳过无内容也无工具调用的 assistant 消息（如取消后留下的空 placeholder）——
            // 它们浪费 token，部分网关还会拒绝空 assistant content。
            if (msg.role == MessageRole.ASSISTANT && msg.content.isBlank() && msg.toolCalls.isEmpty()) {
                continue
            }

            val contentTokens = (msg.content.length / 3L).coerceAtLeast(4)
            val reasoningTokens = ((msg.reasoningContent?.length ?: 0) / 3L)

            if (tokensUsed + contentTokens > tokenBudget) {
                android.util.Log.e("NuclearBoy", "[AgentEngine] buildHistoryMessages() budget exceeded, truncating at msg $originalIdx tokensUsed=$tokensUsed budget=$tokenBudget")
                break
            }

            // If this assistant message has tool calls, insert tool result messages AFTER it
            // in chronological order. Build messages in reverse then reverse at the end.
            if (msg.role == MessageRole.ASSISTANT && msg.toolCalls.isNotEmpty()) {
                // Deduplicate tool calls by toolCallId — AgentEngine emits two ToolExecution
                // events per call (RUNNING + COMPLETED), and ChatViewModel appends a new
                // record each time. We keep the LAST one (usually COMPLETED with output).
                // Fix 8: single-pass LinkedHashMap with put/overwrite instead of groupBy + intermediate map
                val deduped = LinkedHashMap<String, ToolCallRecord>(msg.toolCalls.size)
                for (tc in msg.toolCalls) {
                    deduped[tc.toolCallId ?: tc.toolName] = tc
                }
                val uniqueCalls = deduped.values.toList()
                val completedCalls = uniqueCalls.filter { it.output != null && it.toolCallId != null }
                // 工具调用全部未完成（如取消中断遗留）且无文本 → 会变成空 assistant，跳过避免 API 400
                if (completedCalls.isEmpty() && msg.content.isBlank()) {
                    continue
                }
                for (tc in completedCalls.reversed()) {
                    result.addFirst(MessageDto(
                        role = "tool",
                        content = tc.output,
                        toolCallId = tc.toolCallId,
                        name = tc.toolName,
                    ))
                    tokensUsed += ((tc.output?.length ?: 0) / 3L).coerceAtLeast(4)
                }

                val dto = MessageDto(
                    role = "assistant",
                    content = msg.content,
                    reasoningContent = msg.reasoningContent, // 仅用于 token 预算估算；发送前在 DeepSeekApiClient.sanitizeMessages 统一剥离
                    // ONLY include completed tool calls — pending ones have no result msg and cause API 400
                    toolCalls = if (completedCalls.isNotEmpty()) completedCalls.map { tc ->
                        ToolCallDto(
                            id = tc.toolCallId!!,
                            function = FunctionCallDto(
                                name = tc.toolName,
                                arguments = tc.input,
                            )
                        )
                    } else null,
                    name = null,
                )
                result.addFirst(dto)
            } else {
                val dto = MessageDto(
                    role = when (msg.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                        MessageRole.TOOL -> "tool"
                        MessageRole.SYSTEM -> "system"
                    },
                    content = msg.content,
                    reasoningContent = msg.reasoningContent, // 仅用于 token 预算估算；发送前在 DeepSeekApiClient.sanitizeMessages 统一剥离
                    toolCalls = null,
                    name = null,
                )
                result.addFirst(dto)
            }
            tokensUsed += contentTokens
        }

        android.util.Log.e("NuclearBoy", "[AgentEngine] buildHistoryMessages() EXIT resultCount=${result.size} tokensUsed=$tokensUsed tokenBudgetLeft=${tokenBudget - tokensUsed}")
        return result.toList()
    }

    // ── File Context ─────────────────────────────────────

    /**
     * Build a string representation of attached file contents
     * for injection into the user message.
     */
    private fun buildFileContextStrings(files: List<FileInfo>): String {
        if (files.isEmpty()) return ""

        var totalContentSize = 0L
        var truncatedCount = 0
        val sb = StringBuilder()
        sb.appendLine("--- 当前项目文件 ---")

        for (file in files) {
            val fileContent = file.content // local val for smart cast across modules
            if (fileContent != null && fileContent.length < AppConstants.FILE_CONTENT_TRUNCATE_THRESHOLD) {
                totalContentSize += fileContent.length
                sb.appendLine("## 文件: ${file.path}")
                sb.appendLine("```${file.extension.fileExtension()}")
                sb.appendLine(fileContent)
                sb.appendLine("```")
                sb.appendLine()
            } else if (fileContent != null) {
                // Large file: truncate
                totalContentSize += fileContent.length
                truncatedCount++
                android.util.Log.e("NuclearBoy", "[AgentEngine] buildFileContextStrings() TRUNCATE file=${file.path} size=${fileContent.length} threshold=${AppConstants.FILE_CONTENT_TRUNCATE_THRESHOLD}")
                sb.appendLine("## 文件: ${file.path} （内容较长，已截断）")
                sb.appendLine("```${file.extension.fileExtension()}")
                sb.appendLine(fileContent.take(AppConstants.FILE_CONTENT_TRUNCATE_THRESHOLD.toInt()))
                sb.appendLine("// ... (${
                    AppConstants.formatTokens(
                        (fileContent.length / 3).toLong()
                    )
                } tokens 已省略)")
                sb.appendLine("```")
                sb.appendLine()
            } else {
                // Just metadata
                sb.appendLine("- ${file.path} (${file.size.toFileSizeString()}, ${file.lastModified.toRelativeTimeString()})")
            }
        }

        android.util.Log.e("NuclearBoy", "[AgentEngine] buildFileContextStrings() EXIT fileCount=${files.size} totalContentSize=$totalContentSize truncatedCount=$truncatedCount outputLen=${sb.length}")
        return sb.toString()
    }

    // ── API Streaming Wrapper ────────────────────────────

    /**
     * Calls the DeepSeek API in streaming mode, properly accumulating
     * content and tool call deltas. Emits [StreamEvent] items for each
     * meaningful chunk.
     *
     * This wraps the [DeepSeekApiClient.streamChat] call and adds
     * content/tool-call event emission that the API client's internal
     * stream processing doesn't expose.
     *
     * The API client handles auth, retry, SSL, and token tracking internally.
     * The agent layer focuses on content accumulation and tool call parsing.
     */
    private suspend fun callApiStreaming(
        messages: List<MessageDto>,
        modelTier: ModelTier,
        thinkingMode: ThinkingMode,
        tools: List<ToolDefinitionDto>?,
        accumulator: ToolCallAccumulator,
        reasoningContent: StringBuilder,
        responseContent: StringBuilder,
    ): Flow<StreamEvent> = flow {
        android.util.Log.e("NuclearBoy", "[AgentEngine] callApiStreaming() ENTRY messages=${messages.size} modelTier=$modelTier thinkingMode=$thinkingMode tools=${tools?.size ?: 0}")
        var eventCount = 0
        var contentCount = 0
        var thinkingCount = 0
        accumulator.clear() // Clear once per API call, NOT per individual tool call
        try {
            apiClient.streamChat(
                messages = messages,
                modelTier = modelTier,
                thinkingMode = thinkingMode,
                tools = tools,
            ).collect { event ->
                eventCount++
                when (event) {
                    is StreamEvent.Thinking -> {
                        thinkingCount++
                        reasoningContent.append(event.message)
                        emit(event)
                    }
                    is StreamEvent.Content -> {
                        contentCount++
                        if (event.isReasoning) {
                            reasoningContent.append(event.text)
                        } else {
                            responseContent.append(event.text)
                        }
                        emit(event)
                    }
                    is StreamEvent.ToolCallRequest -> {
                        android.util.Log.e("NuclearBoy", "[AgentEngine] callApiStreaming() ToolCallRequest tools=${event.toolCalls.size}")
                        // Feed the ToolCallRequest data into the accumulator —
                        // DeepSeek sends id+name in the first frame (ToolCallRequest),
                        // then arguments in subsequent frames (ToolCallDelta).
                        // Without this, the accumulator has arguments but no id/name,
                        // so toCompletedCalls() returns empty even though hasPartialCalls()=true.
                        event.toolCalls.forEachIndexed { idx, tc ->
                            accumulator.feed(listOf(ToolCallDeltaDto(
                                index = idx,
                                id = tc.id,
                                function = FunctionCallDeltaDto(
                                    name = tc.function.name,
                                    arguments = tc.function.arguments,
                                )
                            )))
                        }
                        emit(event)
                    }
                    is StreamEvent.ToolCallDelta -> {
                        accumulator.feed(event.deltas)
                        emit(event)
                    }
                    is StreamEvent.Complete -> {
                        android.util.Log.e("NuclearBoy", "[AgentEngine] callApiStreaming() Complete usage=prompt=${event.usage.promptTokens} completion=${event.usage.completionTokens}")
                        emit(event)
                    }
                    is StreamEvent.Error -> {
                        android.util.Log.e("NuclearBoy", "[AgentEngine] callApiStreaming() Error appError=${event.appError} detailLen=${event.technicalDetail?.length ?: 0}")
                        emit(event)
                    }
                    is StreamEvent.ContentReset -> {
                        // The underlying HTTP request is being retried from scratch —
                        // wipe everything accumulated for this (failed) attempt so the
                        // retried stream doesn't get appended after duplicate content.
                        responseContent.setLength(0)
                        reasoningContent.setLength(0)
                        accumulator.clear()
                        emit(event)
                    }
                }
            }
            android.util.Log.e("NuclearBoy", "[AgentEngine] callApiStreaming() EXIT events=$eventCount content=$contentCount thinking=$thinkingCount")
        } catch (e: CancellationException) {
            // 取消必须向上传播（绝不吞）：与 run() 的取消处理保持一致，否则用户"停止"后
            // 这层收集逻辑会把取消误判成普通错误，流不会被真正中断。
            android.util.Log.e("NuclearBoy", "[AgentEngine] callApiStreaming() CancellationException — propagating, eventsSoFar=$eventCount")
            throw e
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[AgentEngine] callApiStreaming() EXCEPTION type=${e.javaClass.simpleName} message=${e.message} eventsSoFar=$eventCount")
            emit(StreamEvent.Error(classifyException(e), e.message))
        }
    }

    // ── Helpers ──────────────────────────────────────────

    /**
     * Parse a JSON string of tool call arguments into a Map.
     */
    private fun parseToolParams(arguments: String): Map<String, String> {
        android.util.Log.e("NuclearBoy", "[AgentEngine] parseToolParams() ENTRY rawArgsLen=${arguments.length}")
        if (arguments.isBlank()) {
            android.util.Log.e("NuclearBoy", "[AgentEngine] parseToolParams() EXIT blank args, returning empty")
            return emptyMap()
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(arguments)
            val result = map.mapValues { (_, value) ->
                when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }
            android.util.Log.e("NuclearBoy", "[AgentEngine] parseToolParams() EXIT parsed count=${result.size} keys=${result.keys}")
            result
        } catch (e: Exception) {
            // Fallback: try to extract key=value pairs
            android.util.Log.e("NuclearBoy", "[AgentEngine] parseToolParams() PARSE FAILED type=${e.javaClass.simpleName} message=${e.message}")
            emptyMap()
        }
    }

    /**
     * Classify exceptions into [AppError] types.
     */
    private fun classifyException(e: Exception): AppError {
        val result = when (e) {
            is DeepSeekHttpException -> AppError.fromHttpCode(e.code)
            is javax.net.ssl.SSLException -> AppError.NetworkUnavailable
            is java.net.SocketTimeoutException -> AppError.NetworkTimeout
            is java.io.IOException -> {
                val msg = e.message?.lowercase() ?: ""
                when {
                    "timeout" in msg -> AppError.NetworkTimeout
                    "connect" in msg || "resolve" in msg || "ssl" in msg -> AppError.NetworkUnavailable
                    else -> AppError.Unknown
                }
            }
            is CancellationException -> AppError.UserCancelled
            else -> AppError.Unknown
        }
        android.util.Log.e("NuclearBoy", "[AgentEngine] classifyException() type=${e.javaClass.simpleName} message=${e.message} result=${result}")
        return result
    }

    // ── Public Utility ───────────────────────────────────

    /**
     * Convenience method: process a user message without requiring explicit [ProjectContext].
     * Uses sensible defaults — no project, empty file list, default user profile.
     *
     * This is the primary API called by [ChatViewModel].
     */
    fun processMessage(
        userMessage: String,
        projectContext: ProjectContext = ProjectContext(
            project = null,
            currentFiles = emptyList(),
            userProfile = UserProfile(),
            activeSkills = emptyList(),
        ),
        conversationHistory: List<ChatMessage> = emptyList(),
        userMode: Int = 0,
    ): Flow<AgentEvent> {
        android.util.Log.e("NuclearBoy", "[AgentEngine] processMessage() ENTRY userMsgLen=${userMessage.length} userMode=$userMode")
        return run(userMessage, projectContext, conversationHistory, userMode)
    }

    /**
     * Notify the engine that the in-progress agent run's collecting coroutine has
     * been cancelled by the caller (e.g. ChatViewModel cancels its own Job). This
     * doesn't cancel anything itself — it just triggers [onCancel] to interrupt
     * running side-effects (e.g. Python execution, remote PC tasks) that would
     * otherwise keep running to completion after the flow collection stops.
     */
    fun cancel() {
        onCancel?.invoke()
    }

    /**
     * Build a compact context string for injection into the system prompt.
     * Delegates to the context manager.
     */
    fun buildContextSummary(): String {
        val budget = contextManager.budget.value
        return buildString {
            append("上下文状态: ")
            append("已用 ${AppConstants.formatTokens(budget.totalUsed)} / ")
            append("${AppConstants.formatTokens(AppConstants.DEEPSEEK_CONTEXT_WINDOW)}")
            append(" (${AppConstants.formatPercentage(budget.usagePercent)})")
            append(" · 剩余 ${AppConstants.formatTokens(budget.remaining)}")
            if (budget.warningLevel >= ContextWarningLevel.YELLOW) {
                append(" ⚠️ 需要关注")
            }
        }
    }

    companion object {
        /** 单个工具结果注入对话的最大字符数 */
        private const val MAX_TOOL_OUTPUT_CHARS = 12_000
        /** 发送前对话负载的真实 token 上限 */
        private const val MAX_PAYLOAD_TOKENS = 96_000L
        /** 工具调用循环的绝对最大轮次，防止模型无限调工具循环。 */
        private const val MAX_TOOL_ITERATIONS = 20
    }
}
