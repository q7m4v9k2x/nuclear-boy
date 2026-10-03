package com.nuclearboy.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuclearboy.agent.AgentEngine
import com.nuclearboy.agent.AgentEvent
import com.nuclearboy.agent.ProjectContext
import com.nuclearboy.api.deepseek.ContextWindowManager
import com.nuclearboy.common.*
import com.nuclearboy.common.AppConstants
import com.nuclearboy.common.AppResult
import com.nuclearboy.memory.MemoryStore
import com.nuclearboy.ui.chat.parts.buildToolActionEvidenceMessage
import com.nuclearboy.ui.chat.parts.buildToolActionMissingEvidenceReview
import com.nuclearboy.ui.chat.parts.buildToolActionModelGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.UUID
import javax.inject.Inject

// ── UI State ────────────────────────────────────────────────────────────────

data class StreamingState(
    val messageId: String,
    val thinkingText: String = "",
    val responseText: String = "",
    val activeToolCalls: List<ToolCallRecord> = emptyList(),
    val isThinking: Boolean = false,
    val isStreaming: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentEngine: AgentEngine,
    private val contextManager: ContextWindowManager,
    private val apiKeyManager: com.nuclearboy.api.deepseek.ApiKeyManager,
    private val apiClient: com.nuclearboy.api.deepseek.DeepSeekApiClient,
    private val fileOperations: com.nuclearboy.tools.docgen.FileOperations,
    private val skillManager: com.nuclearboy.skills.SkillManager,
    private val memoryStore: MemoryStore,
    private val appSettings: com.nuclearboy.common.AppSettingsStore,
) : ViewModel() {

    // memory.json 内容缓存：避免每轮对话都从磁盘重读 memory.json
    // remember 工具写入后会在 invalidateMemoryCache() 中清空
    @Volatile private var cachedMemoryCtx: String? = null

    private fun invalidateMemoryCache() { cachedMemoryCtx = null }
    // remember 工具写入新记忆后 autoExtractMemories 会更新 DB，下轮会重新加载
    @Volatile private var cachedUserProfile: com.nuclearboy.common.UserProfile? = null

    // 从记忆加载用户画像（优先内存缓存）
    private suspend fun loadUserProfile(): com.nuclearboy.common.UserProfile {
        cachedUserProfile?.let { return it }
        val result = memoryStore.exportUserProfile()
        val profile = when (result) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> com.nuclearboy.common.UserProfile()
        }
        cachedUserProfile = profile
        return profile
    }

    /** 让缓存失效，使下次 loadUserProfile 重新从 DB 读取（记忆更新后调用）。 */
    private fun invalidateUserProfileCache() { cachedUserProfile = null }

    /** Set by NavHost to enable background notifications */
    var notificationCallback: ((String, String?) -> Unit)? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _isProcessing = MutableStateFlow(false)
    private val _streamingState = MutableStateFlow<StreamingState?>(null)
    private val _scrollToBottom = MutableStateFlow(0L)

    /** 复用的 JSON 解析器（读记忆文件等），避免每轮 executeTurn 重建 Json 配置 */
    private val memoryJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Guards the isProcessing-check + list-write pair in deleteMessage so they
     * are never split by a concurrent streaming job (finding 4 — TOCTOU).
     */
    private val messageEditMutex = Mutex()

    @Volatile private var agentJob: Job? = null
    private var lastUserMessage: ChatMessage? = null
    private var currentProjectId: String? = null
    private var currentThinkingId: String? = null
    private var currentAssistantMsgId: String? = null
    /** Incremented whenever a project/session is replaced. Stale stream callbacks cannot save over it. */
    @Volatile private var conversationEpoch: Long = 0L
    /** Prevents a slower project switch from overwriting a newer selection. */
    @Volatile private var projectSwitchGeneration: Long = 0L
    private val turnEpochs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val persistenceLock = Any()
    private var selectedMode: Int = 0
    /** 用户通过 /goal 设定的会话目标，每轮注入上下文 */
    private var sessionGoal: String? = null
    private val _projectName = MutableStateFlow("")
    val projectName: StateFlow<String> = _projectName.asStateFlow()
    val apiKeyState: StateFlow<com.nuclearboy.api.deepseek.ApiKeyManager.ApiKeyState> = apiKeyManager.state

    fun setMode(mode: Int) { selectedMode = mode.coerceIn(0, 2) }
    fun selectModel(modelId: String) { apiKeyManager.selectModel(modelId) }

    /** 远程电脑权限审批弹窗：非 null 时聊天界面弹出确认框 */
    private val _permissionPrompt = MutableStateFlow<com.nuclearboy.common.PermissionPromptBus.PermissionRequest?>(null)
    val permissionPrompt: StateFlow<com.nuclearboy.common.PermissionPromptBus.PermissionRequest?> = _permissionPrompt.asStateFlow()

    fun respondPermission(approved: Boolean) {
        _permissionPrompt.value?.decision?.complete(approved)
        _permissionPrompt.value = null
    }

    init {
        // 远程电脑权限请求 → 弹窗等用户决定
        viewModelScope.launch {
            com.nuclearboy.common.PermissionPromptBus.requests.collect { request ->
                _permissionPrompt.value = request
            }
        }
        // 长耗时工具（如 pc_cli_run）的实时进度 → 追加到当前 RUNNING 工具卡片的输出
        viewModelScope.launch {
            com.nuclearboy.common.ToolProgressBus.events.collect { progress ->
                val msgId = currentAssistantMsgId ?: return@collect
                updateAssistantMessage(msgId) { msg ->
                    val idx = msg.toolCalls.indexOfLast {
                        it.toolName == progress.toolName && it.status == ToolCallStatus.RUNNING
                    }
                    if (idx < 0) {
                        msg
                    } else {
                        val call = msg.toolCalls[idx]
                        val appended = (call.output.orEmpty() + progress.text + "\n")
                            .takeLast(MAX_TOOL_PROGRESS_CHARS)
                        msg.copy(
                            toolCalls = msg.toolCalls.toMutableList().also {
                                it[idx] = call.copy(output = appended)
                            },
                        )
                    }
                }
            }
        }
    }

    private val _projectFiles = MutableStateFlow<List<FileInfo>>(emptyList())
    val projectFiles: StateFlow<List<FileInfo>> = _projectFiles.asStateFlow()
    private val _savedConversations = MutableStateFlow<List<SavedConversation>>(emptyList())
    val savedConversations: StateFlow<List<SavedConversation>> = _savedConversations.asStateFlow()

    suspend fun setProject(projectId: String) {
        android.util.Log.e("NuclearBoy", "[ChatVM] setProject() projectId=$projectId previousId=$currentProjectId currentDir=${fileOperations.currentProjectDir}")
        val switchGeneration = projectSwitchGeneration + 1L
        projectSwitchGeneration = switchGeneration
        conversationEpoch++
        // 切换项目时按 Job 是否仍在运行来取消，而不只看 isProcessing。
        // launch(Dispatchers.IO) 可能已经返回 Job、但还没来得及把
        // _isProcessing 设为 true；只检查状态会让旧请求漏进新项目。
        if (agentJob?.isActive == true || _isProcessing.value) cancelCurrentOperation()
        // currentProjectDir 由外部 selectProject() 设置（UUID → 目录名的转换）
        // 此处不覆盖，信任外部已设置正确
        currentProjectId = projectId
        val root = fileOperations.projectRoot()
        _projectName.value = if (projectId == "__general__") "核弹男孩" else root.name
        sessionGoal = loadSessionGoal(projectId)
        // 每次切换都重新加载消息
        val loaded = try { loadPersistedMessages(projectId) }
            catch (e: Exception) { android.util.Log.e("NuclearBoy", "[ChatVM] 加载历史失败: ${e.message}"); emptyList() }
        if (switchGeneration != projectSwitchGeneration) return
        android.util.Log.e("NuclearBoy", "[ChatVM] setProject() messagesLoaded=${loaded.size} root=${root.absolutePath}")
        _messages.value = loaded
        refreshSavedConversationsFor(projectId)
        // 恢复最后一条用户消息，否则切换项目后直接点"重新生成"会因 lastUserMessage 为 null 失效
        lastUserMessage = loaded.findLast { it.role == MessageRole.USER }
        refreshProjectFiles()
        if (loaded.isNotEmpty()) _scrollToBottom.value++
        // The general agent also has a real workspace directory
        // (__general__). Load its project skills as well, otherwise a Skill
        // Creator run from the default conversation can never appear in the
        // active list or become callable until the user switches projects.
        try {
            val skillsDir = java.io.File(fileOperations.projectRoot(), AppConstants.PROJECT_SKILLS_DIR)
            // Await the reload so the active skill list and tool registry are
            // ready before the chat can send its first turn after navigation.
            skillManager.reloadProjectSkills(skillsDir)
            if (switchGeneration != projectSwitchGeneration) return
        } catch (e: Exception) { android.util.Log.e("NuclearBoy", "[ChatVM] setProject() skills load failed: ${e.message}") }
    }

    override fun onCleared() {
        android.util.Log.e("NuclearBoy", "[ChatVM] onCleared() ViewModel cleanup")
        super.onCleared()
        // 若销毁时还挂着一个未回答的权限弹窗，必须保守拒绝，否则等待这个 decision 的
        // 远程电脑任务协程会永久挂起（viewModelScope 里的收集器已随 onCleared 取消）。
        _permissionPrompt.value?.decision?.complete(false)
        _permissionPrompt.value = null
        skillManager.unloadProjectSkills()
    }

    private val _browseDir = MutableStateFlow(".")
    val browseDir: StateFlow<String> = _browseDir.asStateFlow()

    fun refreshProjectFiles(path: String = ".") {
        android.util.Log.e("NuclearBoy", "[ChatVM] refreshProjectFiles() path=$path")
        viewModelScope.launch {
            _browseDir.value = path
            val result = fileOperations.listDirectory(path)
            if (result is AppResult.Success) {
                // 文件面板只展示用户文件：隐藏内部状态目录和开发缓存/VCS 噪音，
                // 它们既不该被引用为附件（缓存喂回模型无意义），也不该计入附件角标数量。
                _projectFiles.value = result.data.filterNot { entry ->
                    entry.name in HIDDEN_FILE_ENTRIES
                }
                android.util.Log.e("NuclearBoy", "[ChatVM] refreshProjectFiles() filesFound=${_projectFiles.value.size} path=$path")
            }
        }
    }

    fun navigateToDir(dirName: String) {
        val current = _browseDir.value
        val newPath = if (current == ".") dirName else "$current/$dirName"
        android.util.Log.e("NuclearBoy", "[ChatVM] navigateToDir() '$current' -> '$newPath'")
        refreshProjectFiles(newPath)
    }

    fun navigateUp() {
        val current = _browseDir.value
        if (current == ".") return
        val parent = current.substringBeforeLast("/", ".")
        val newPath = if (parent.isEmpty()) "." else parent
        android.util.Log.e("NuclearBoy", "[ChatVM] navigateUp() '$current' -> '$newPath'")
        refreshProjectFiles(newPath)
    }

    private fun saveMessages(expectedEpoch: Long? = null) {
        if (expectedEpoch != null && expectedEpoch != conversationEpoch) {
            android.util.Log.e("NuclearBoy", "[ChatVM] saveMessages() skipped stale epoch=$expectedEpoch current=$conversationEpoch")
            return
        }
        val pid = currentProjectId ?: return
        synchronized(persistenceLock) {
            if (expectedEpoch != null && expectedEpoch != conversationEpoch) return
            android.util.Log.e("NuclearBoy", "[ChatVM] saveMessages() pid=$pid messagesCount=${_messages.value.size}")
            try {
                // 直接用 workspaceRoot + projectId 构建路径，不依赖 currentProjectDir
                val dir = java.io.File(fileOperations.getWorkspaceRoot(), "$pid/.agent")
                dir.mkdirs()
                val data = memoryJson.encodeToString(serializer(), _messages.value.takeLast(MAX_PERSISTED_MESSAGES))
                val file = java.io.File(dir, "conversation.json")
                // 原子写：先写临时文件再 rename，避免进程在 writeText 中途被杀导致 conversation.json
                // 被截断——那样 loadPersistedMessages 解码会抛异常、整段历史被当成空全丢。
                val tmp = java.io.File(dir, "conversation.json.tmp")
                tmp.writeText(data)
                if (!tmp.renameTo(file)) {
                    // 极少数文件系统 rename 覆盖失败：退回直接覆盖，至少不比原来差
                    file.writeText(data)
                    tmp.delete()
                }
                android.util.Log.e("NuclearBoy", "[ChatVM] saveMessages() saved to ${file.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("NuclearBoy", "[ChatVM] saveMessages() error: ${e.message}", e)
            }
        }
    }

    private fun refreshSavedConversationsFor(projectId: String) {
        _savedConversations.value = ConversationArchiveStore.list(
            workspaceRoot = fileOperations.getWorkspaceRoot(),
            projectId = projectId,
            json = memoryJson,
        )
    }

    /** Re-read the archive list after an external/debug write. */
    fun refreshSavedConversations() {
        currentProjectId?.let(::refreshSavedConversationsFor)
    }

    /** Restore an archived conversation into the active slot. */
    fun restoreConversation(conversationId: String) {
        val pid = currentProjectId ?: return
        val restored = ConversationArchiveStore.restore(
            workspaceRoot = fileOperations.getWorkspaceRoot(),
            projectId = pid,
            conversationId = conversationId,
            json = memoryJson,
        ) ?: return
        // Stop an in-flight turn before taking the snapshot that will be
        // archived.  Otherwise a late stream event can race this read/write
        // and either be omitted from the archive or persist under the
        // restored conversation after it replaces the active list.
        cancelCurrentOperation()
        // 恢复历史会替换当前会话；先把当前内容也归档，避免用户在恢复时
        // 无意丢掉尚未归档的对话（新对话流程通常这里为空）。
        val currentMessages = _messages.value
        if (currentMessages.isNotEmpty()) {
            val archived = ConversationArchiveStore.archive(
                workspaceRoot = fileOperations.getWorkspaceRoot(),
                projectId = pid,
                messages = currentMessages,
                json = memoryJson,
            )
            if (archived == null) {
                android.util.Log.e("NuclearBoy", "[ChatVM] restoreConversation() archive current conversation failed")
                addSystemMessage("无法归档当前对话，未恢复历史记录；请检查存储空间后重试")
                saveMessages()
                return
            }
            refreshSavedConversationsFor(pid)
        }
        conversationEpoch++
        _messages.value = restored
        lastUserMessage = restored.findLast { it.role == MessageRole.USER }
        contextManager.reset()
        saveMessages()
        _scrollToBottom.value++
    }

    private fun loadPersistedMessages(projectId: String): List<ChatMessage> {
        android.util.Log.e("NuclearBoy", "[ChatVM] loadPersistedMessages() projectId=$projectId")
        return try {
            val file = java.io.File(fileOperations.getWorkspaceRoot(), "$projectId/.agent/conversation.json")
            android.util.Log.e("NuclearBoy", "[ChatVM] loadPersistedMessages() path=${file.absolutePath} exists=${file.exists()}")
            if (file.exists()) {
                val loaded = memoryJson.decodeFromString(serializer<List<ChatMessage>>(), file.readText())
                android.util.Log.e("NuclearBoy", "[ChatVM] loadPersistedMessages() loaded=${loaded.size}")
                // A force-stop can interrupt a stream before finalizeProcessing
                // runs. Keep that turn in history but make the interrupted
                // assistant bubble explicit instead of silently showing an
                // empty/forever-thinking message.
                loaded.map { message ->
                    if (message.role == MessageRole.ASSISTANT &&
                        message.status != MessageStatus.COMPLETE &&
                        message.status != MessageStatus.ERROR
                    ) {
                        message.copy(
                            content = message.content.ifBlank { "上次生成在应用关闭时中断，可重试" },
                            status = MessageStatus.ERROR,
                        )
                    } else message
                }
            } else emptyList()
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[ChatVM] loadPersistedMessages() error: ${e.message}", e)
            emptyList()
        }
    }

    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    val streamingState: StateFlow<StreamingState?> = _streamingState.asStateFlow()
    val scrollToBottom: StateFlow<Long> = _scrollToBottom.asStateFlow()

    /** Expose project root path for file browser integration. */
    fun getProjectRoot(): String = fileOperations.projectRoot().absolutePath

    fun getActiveSkillCount(): Int = skillManager.activeSkills.value.size

    // ── Public actions ──────────────────────────────────────────────────

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        android.util.Log.e("NuclearBoy", "[ChatVM] sendMessage() entry textLen=${trimmed.length} isProcessing=${_isProcessing.value}")
        if (trimmed.isEmpty()) return
        // 斜杠命令优先处理（/stop 等命令在处理中也要可用）
        if (trimmed.startsWith("/")) {
            handleSlashCommand(trimmed)
            return
        }
        if (_isProcessing.value) return

        // Cancel any existing processing cleanly
        cancelCurrentOperation()

        val turnEpoch = conversationEpoch
        agentJob = viewModelScope.launch(Dispatchers.IO) {
            executeTurn(trimmed, turnEpoch = turnEpoch)
        }
    }

    /**
     * 执行一轮完整的 Agent 对话：用户消息 → AI 处理 → 工具调用 → 最终回复。
     * 被 [sendMessage]（单轮）和 /loop（多轮循环）复用。
     * @return 最终 ASSISTANT 回复内容（供循环模式判断目标是否达成）。
     */
    private suspend fun executeTurn(
        trimmed: String,
        clearAgentJobOnFinalize: Boolean = true,
        turnEpoch: Long = conversationEpoch,
    ): String {
        // setProject/clearConversation can invalidate a Job before its IO
        // coroutine gets scheduled. Never append a stale turn into the new
        // project's StateFlow or persist it under the new project id.
        if (turnEpoch != conversationEpoch) return ""
        val toolEvidenceMessage = buildToolActionEvidenceMessage(trimmed)?.let { evidence ->
            ChatMessage(role = MessageRole.SYSTEM, content = evidence, status = MessageStatus.COMPLETE)
        }
        val toolModelGuard = buildToolActionModelGuard(trimmed)
        // Check API key
        val key = apiKeyManager.getActiveKey()
        android.util.Log.e("NuclearBoy", "[ChatVM] executeTurn() credential status: configured=${key != null} blank=${key?.isBlank() == true}")
        if (key == null) {
            android.util.Log.e("NuclearBoy", "[ChatVM] executeTurn() no API key, showing tip")
            val userMessage = ChatMessage(role = MessageRole.USER, content = trimmed, status = MessageStatus.COMPLETE)
            // Keep the failed submission available for the retry action.  The
            // message is persisted below, but retry uses this in-memory pointer
            // and otherwise has nothing to resend after a key is configured.
            lastUserMessage = userMessage
            if (turnEpoch != conversationEpoch) return ""
            _messages.update { current ->
                if (toolEvidenceMessage == null) current + userMessage else current + userMessage + toolEvidenceMessage
            }
            addSystemMessage("需要配置 DeepSeek API Key 才能开始\n\n请到右上角「设置」输入你的 Key（sk-v4- 开头），保存后即可使用。\n如果你用的是自建模型服务，也可以在设置里开启「第三方模型」")
            saveMessages(turnEpoch)
            return ""
        }

        val userMessage = ChatMessage(
            role = MessageRole.USER, content = trimmed, status = MessageStatus.COMPLETE,
        )
        if (turnEpoch != conversationEpoch) return ""
        _messages.update { current ->
            if (toolEvidenceMessage == null) current + userMessage else current + userMessage + toolEvidenceMessage
        }
        lastUserMessage = userMessage
        saveMessages(turnEpoch)
        _scrollToBottom.value++
        _isProcessing.value = true

        // Create single ASSISTANT placeholder — all updates stream into this message
        val assistantId = UUID.randomUUID().toString()
        currentThinkingId = assistantId
        currentAssistantMsgId = assistantId
        turnEpochs[assistantId] = turnEpoch
        val placeholder = ChatMessage(
            id = assistantId, role = MessageRole.ASSISTANT,
            content = "", status = MessageStatus.THINKING,
        )
        _messages.update { it + placeholder }
        // Persist the user turn and assistant placeholder before network I/O.
        // If Android kills the process while the model is streaming, the next
        // launch still has the complete prompt and can show an interrupted
        // turn instead of losing the conversation tail.
        saveMessages(turnEpoch)
        _streamingState.value = StreamingState(messageId = assistantId, isThinking = true)
        _scrollToBottom.value++

        // 读记忆文件（优先内存缓存，避免每轮对话都磁盘读取）
        val memoryCtx = cachedMemoryCtx ?: run {
            val memFile = java.io.File(fileOperations.getWorkspaceRoot(), "__general__/.agent/memory.json")
            val ctx = if (memFile.exists()) {
                try {
                    val memories = memoryJson.decodeFromString<List<Map<String, String>>>(memFile.readText())
                    memories.takeLast(10).joinToString("\n") { "- ${it["value"]} [${it["category"]}]" }
                } catch (_: Exception) { "" }
            } else ""
            cachedMemoryCtx = ctx
            ctx
        }

        // /goal 设定的会话目标注入上下文
        val goal = sessionGoal
        val memoryCtxWithGoal = buildString {
            append(memoryCtx)
            if (!goal.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("【会话目标】用户设定的当前目标：$goal\n每次回复都要朝这个目标推进。")
            }
        }

        // Build project context with memory
        val projectContext = ProjectContext(
            project = currentProjectId?.let { id ->
                Project(id = id, name = id, rootPath = fileOperations.projectRoot().absolutePath)
            },
            currentFiles = _projectFiles.value,
            userProfile = UserProfile(),
            activeSkills = skillManager.activeSkills.value,
            memoryContext = memoryCtxWithGoal,
            customInstructions = buildTurnCustomInstructions(
                base = appSettings.customInstructions(),
                turnGuard = toolModelGuard,
            ),
        )

        notificationCallback?.invoke("thinking", currentProjectId)

        // 从记忆加载用户偏好
        val loadedProfile = loadUserProfile()
        val enrichedContext = projectContext.copy(userProfile = loadedProfile)
        try {
            agentEngine.processMessage(
                userMessage = trimmed,
                projectContext = enrichedContext,
                // 排除本轮刚加入的用户消息和空 placeholder：
                // AgentEngine 会自行追加 userMessage，否则用户消息会重复发送，
                // 且 history 末尾会多一条空 assistant 消息。
                conversationHistory = _messages.value.filter {
                    it.role != MessageRole.SYSTEM &&
                        it.id != userMessage.id &&
                        it.id != assistantId
                },
                userMode = selectedMode,
            ).collect { event ->
                handleAgentEvent(event, assistantId)
            }
        } catch (e: CancellationException) {
            android.util.Log.e("NuclearBoy", "[ChatVM] executeTurn() cancelled")
            updateAssistantMessage(assistantId) { msg ->
                if (msg.content.isEmpty()) msg.copy(content = "", status = MessageStatus.CANCELLED)
                else msg.copy(status = MessageStatus.COMPLETE)
            }
            throw e
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[ChatVM] executeTurn() error: ${e.message}", e)
            handleAgentError(e, assistantId)
        } finally {
            finalizeProcessing(assistantId, clearAgentJob = clearAgentJobOnFinalize)
        }
        return _messages.value.findLast { it.role == MessageRole.ASSISTANT }?.content ?: ""
    }

    /** 把当前对话导出成 Markdown 文本，供分享/存档。空对话返回占位说明。 */
    fun buildExportMarkdown(): String {
        val title = projectName.value.ifBlank { "核弹男孩对话" }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        return com.nuclearboy.common.ConversationExporter.toMarkdown(title, _messages.value, date)
    }

    /**
     * 编辑重发：截断到指定用户消息之前，返回该消息内容供输入框回填。
     * 正在处理中或目标非用户消息时返回 null。
     */
    fun editAndResend(messageId: String): String? {
        if (_isProcessing.value) return null
        val result = com.nuclearboy.common.ChatEditing.prepareEdit(_messages.value, messageId) ?: return null
        // Use update to avoid dropping a streamed chunk that arrived between prepareEdit
        // and the list assignment (finding 3).
        _messages.update { result.remaining }
        // The edited message (and everything after it) is gone — recompute lastUserMessage
        // so a later "重新生成" doesn't resend the now-removed message's content.
        lastUserMessage = result.remaining.findLast { it.role == MessageRole.USER }
        saveMessages()
        return result.content
    }

    /** 删除单条消息（处理中不允许，避免与流式写入冲突）。 */
    fun deleteMessage(messageId: String) {
        // Guard check and list write run inside a mutex so a streaming job cannot
        // flip _isProcessing to true in the gap between the two steps (finding 4 — TOCTOU).
        viewModelScope.launch {
            val deleted = messageEditMutex.withLock {
                if (_isProcessing.value) return@withLock false
                _messages.update { com.nuclearboy.common.ChatEditing.removeMessage(it, messageId) }
                // If the deleted message was the tracked lastUserMessage, repoint it at the
                // new last user message so "重新生成" won't resurrect the deleted content.
                if (lastUserMessage?.id == messageId) {
                    lastUserMessage = _messages.value.findLast { it.role == MessageRole.USER }
                }
                true
            }
            if (deleted) saveMessages()
        }
    }

    fun retryLastMessage() {
        android.util.Log.e("NuclearBoy", "[ChatVM] retryLastMessage() entry isProcessing=${_isProcessing.value}")
        if (_isProcessing.value) return
        val lastUser = lastUserMessage ?: return
        // Use update so we never silently drop a streamed chunk that arrived between
        // the snapshot copy and the assignment (finding 2).
        // sendMessage() below always appends a *fresh* user message, so the old one at
        // lastUserIndex must be dropped too (not kept) — otherwise retry duplicates the
        // user bubble. This also fixes the case where the user message is already the
        // last message (e.g. its assistant reply was deleted): previously that left the
        // list untouched and retry just appended a second, identical user message.
        _messages.update { currentMessages ->
            val lastUserIndex = currentMessages.indexOfLast { it.id == lastUser.id }
            if (lastUserIndex >= 0) currentMessages.subList(0, lastUserIndex) else currentMessages
        }
        sendMessage(lastUser.content)
    }

    fun cancelCurrentOperation() {
        val job = agentJob
        val wasActive = job != null && job.isActive
        android.util.Log.e("NuclearBoy", "[ChatVM] cancelCurrentOperation() jobActive=$wasActive")
        // Invalidate late AgentEvent callbacks from the cancelled generation,
        // even when the cancellation happens before _isProcessing is set.
        if (wasActive) conversationEpoch++
        if (job != null && job.isActive) {
            job.cancel()
            agentEngine.cancel()
        }
        agentJob = null
        _isProcessing.value = false
        _streamingState.value = null
        currentThinkingId = null
        currentAssistantMsgId = null
        if (wasActive) {
            // 用户主动取消：前台服务此前一直靠 finalizeProcessing() 里的"ready"通知才会被
            // 更新，取消掉的这一轮永远不会走到那里，服务会以"AI 正在思考…"卡住不停止。
            notificationCallback?.invoke("stop", currentProjectId)
        }
    }

    fun clearConversation() {
        android.util.Log.e("NuclearBoy", "[ChatVM] clearConversation() entry messagesCount=${_messages.value.size}")
        val projectId = currentProjectId
        val previousMessages = _messages.value
        cancelCurrentOperation()
        // "New conversation" must preserve the finished conversation so a user
        // can return to it later. Archive before replacing the active file; an
        // empty/new conversation still uses the same project and workspace.
        if (projectId != null && previousMessages.isNotEmpty()) {
            val archived = ConversationArchiveStore.archive(
                workspaceRoot = fileOperations.getWorkspaceRoot(),
                projectId = projectId,
                messages = previousMessages,
                json = memoryJson,
            )
            if (archived == null) {
                android.util.Log.e("NuclearBoy", "[ChatVM] clearConversation() archive failed; preserving current conversation")
                addSystemMessage("无法归档当前对话，未清空；请检查存储空间后重试")
                saveMessages()
                return
            }
            refreshSavedConversationsFor(projectId)
        }
        conversationEpoch++
        _messages.value = emptyList()
        lastUserMessage = null
        contextManager.reset()
        currentThinkingId = null
        currentAssistantMsgId = null
        saveMessages()
    }

    // ── Slash Commands（/loop /goal /compact /rewind 等）──────────────────

    private fun handleSlashCommand(input: String) {
        val (cmd, args) = parseSlashCommand(input)
        android.util.Log.e("NuclearBoy", "[ChatVM] slashCommand cmd=$cmd argsLen=${args.length}")
        when (cmd) {
            "help" -> addSystemMessage(SLASH_HELP)
            "stop" -> {
                cancelCurrentOperation()
                addSystemMessage("⏹️ 已停止当前任务")
            }
            "clear" -> {
                clearConversation()
                addSystemMessage("🧹 对话已清空，重新开始吧")
            }
            "sandbox" -> addSystemMessage("Python 现在固定为非隔离执行模式，/sandbox 开关已移除。")
            "model" -> handleModelCommand(args)
            "goal" -> handleGoalCommand(args)
            "rewind" -> {
                if (busyGuard()) return
                rewindConversation(args.toIntOrNull() ?: 1)
            }
            "compact" -> {
                if (busyGuard()) return
                compactConversation()
            }
            "loop" -> {
                if (busyGuard()) return
                handleLoopCommand(args)
            }
            else -> addSystemMessage("❓ 不认识的命令 /$cmd\n\n$SLASH_HELP")
        }
    }

    private fun busyGuard(): Boolean {
        if (_isProcessing.value) {
            addSystemMessage("⏳ 正在处理中，先用 /stop 停止当前任务再试")
            return true
        }
        return false
    }

    private fun handleModelCommand(args: String = "") {
        if (args.isNotBlank()) {
            switchModelByCommand(args)
            return
        }
        val state = apiKeyManager.state.value
        val modeText = when (selectedMode) {
            1 -> "思考模式 (V4 Pro · 深度思考)"
            2 -> "专家模式 (V4 Pro · 极致推理)"
            else -> "聊天模式 (V4 Flash · 快速)"
        }
        val text = if (state.customProviderEnabled) {
            val activeCustom = state.customModels.firstOrNull { it.id == state.activeModelId }
            buildString {
                appendLine("🌐 当前使用第三方模型服务")
                appendLine("· 显示名称：${state.activeModelLabel}")
                appendLine("· 协议：${activeCustom?.protocol?.displayName ?: "自动"}")
                appendLine("· 服务地址：${state.customBaseUrl.ifBlank { "(未配置)" }}")
                appendLine("· 模型：${state.customModelName.ifBlank { "(未配置)" }}")
                appendLine("· Key：${if (state.hasCustomKey) state.customKeyMasked else "(未配置)"}")
                append("可在左上角模型菜单或「设置 → 第三方模型」中切换")
            }
        } else {
            buildString {
                appendLine("🧠 当前使用 DeepSeek 官方 API")
                appendLine("· 模式：$modeText")
                appendLine("· Key：${if (state.hasPrimaryKey) state.primaryKeyMasked else "(未配置)"}")
                append("要接入自建服务（如 9router），到「设置 → 第三方模型」添加")
            }
        }
        val listing = if (state.customModels.isEmpty()) "" else buildString {
            appendLine()
            appendLine()
            appendLine("可切换的模型（/model <序号或名称>）：")
            val officialMark = if (state.activeModelId == com.nuclearboy.api.deepseek.ApiKeyManager.OFFICIAL_MODEL_ID) " ✅" else ""
            appendLine("0. DeepSeek 官方$officialMark")
            state.customModels.forEachIndexed { i, m ->
                val mark = if (state.activeModelId == m.id) " ✅" else ""
                appendLine("${i + 1}. ${m.displayName}（${m.modelName}）$mark")
            }
        }.trimEnd()
        addSystemMessage(text + listing)
    }

    /** /model <序号或名称>：切换当前模型。0/官方 = DeepSeek 官方，其余按列表序号或名称模糊匹配 */
    private fun switchModelByCommand(args: String) {
        val state = apiKeyManager.state.value
        val query = args.trim()
        val officialAliases = setOf("0", "官方", "official", "deepseek")
        if (query.lowercase() in officialAliases) {
            apiKeyManager.selectModel(com.nuclearboy.api.deepseek.ApiKeyManager.OFFICIAL_MODEL_ID)
            addSystemMessage("✅ 已切换到 DeepSeek 官方 API")
            return
        }
        val byIndex = query.toIntOrNull()?.let { idx ->
            state.customModels.getOrNull(idx - 1)
        }
        val target = byIndex ?: state.customModels.firstOrNull {
            it.displayName.contains(query, ignoreCase = true) ||
                it.modelName.contains(query, ignoreCase = true)
        }
        if (target == null) {
            addSystemMessage("❓ 没找到匹配「$query」的模型\n用 /model 查看可用列表")
            return
        }
        apiKeyManager.selectModel(target.id)
        addSystemMessage("✅ 已切换到 ${target.displayName}（${target.protocol.displayName} · ${target.modelName}）")
    }

    private fun handleGoalCommand(args: String) {
        when {
            args.isBlank() -> {
                val goal = sessionGoal
                addSystemMessage(
                    if (goal.isNullOrBlank()) "🎯 当前没有设定目标\n用 /goal <目标描述> 来设定一个"
                    else "🎯 当前目标：$goal\n用 /goal clear 可清除"
                )
            }
            args.equals("clear", ignoreCase = true) || args == "清除" -> {
                sessionGoal = null
                saveSessionGoal()
                addSystemMessage("🎯 目标已清除")
            }
            else -> {
                sessionGoal = args
                saveSessionGoal()
                addSystemMessage("🎯 目标已设定：$args\n之后每轮对话我都会朝这个目标推进。用 /goal clear 可清除")
            }
        }
    }

    private fun handleLoopCommand(args: String) {
        if (args.isBlank()) {
            addSystemMessage("用法：/loop [轮数] <任务描述>\n例如：/loop 5 写一个贪吃蛇游戏并自测通过\n轮数默认 5，最多 $LOOP_MAX_ITERATIONS")
            return
        }
        val tokens = args.split(Regex("\\s+"), limit = 2)
        val explicitCount = tokens[0].toIntOrNull()
        val (maxIterations, task) = when {
            explicitCount != null && tokens.size > 1 -> explicitCount.coerceIn(1, LOOP_MAX_ITERATIONS) to tokens[1].trim()
            // args 整体就是一个裸数字、没有任务描述（例如命令栏模板 "/loop 5 " 被原样发送）——
            // 不能把这个数字当成任务文本，否则会开始一个以"5"为目标的循环。
            explicitCount != null -> LOOP_DEFAULT_ITERATIONS to ""
            else -> LOOP_DEFAULT_ITERATIONS to args
        }
        if (task.isBlank()) {
            addSystemMessage("循环任务描述不能为空。用法：/loop [轮数] <任务描述>")
            return
        }
        startLoop(maxIterations, task)
    }

    private fun startLoop(maxIterations: Int, task: String) {
        cancelCurrentOperation()
        addSystemMessage("🔁 进入循环模式（最多 $maxIterations 轮）\n目标：$task\n随时输入 /stop 中断")
        agentJob = viewModelScope.launch(Dispatchers.IO) {
            var completed = false
            try {
                for (i in 1..maxIterations) {
                    if (!isActive) break
                    addSystemMessage("🔁 第 $i / $maxIterations 轮")
                    val prompt = if (i == 1) {
                        "$task\n\n【循环模式】这是一个多轮自动任务。本轮尽量推进；结束时自查目标是否已完全达成，" +
                            "若已达成，在回复末尾单独一行输出 $LOOP_DONE_MARKER"
                    } else {
                        "继续推进目标：$task\n检查之前的进度和遗留问题，完成剩余工作。" +
                            "若目标已完全达成，在回复末尾单独一行输出 $LOOP_DONE_MARKER"
                    }
                    val reply = executeTurn(prompt, clearAgentJobOnFinalize = false)
                    // executeTurn() 把 lastUserMessage 设成了本轮实际发送的内部续跑 prompt
                    // （"继续推进目标：...若目标已完全达成..." 这段脚手架文本，不是用户输入）。
                    // 改回原始 /loop 任务描述——只改内容，id 不变，不影响 retryLastMessage()
                    // 按 id 定位可视消息的逻辑——这样循环结束后点"重新生成"，重发的是任务本身。
                    lastUserMessage = lastUserMessage?.copy(content = task)
                    if (reply.contains(LOOP_DONE_MARKER)) {
                        completed = true
                        // 模型按提示词要求把标记单独输出在最后一行，这只是给循环逻辑看的内部
                        // 信号，不应该原样出现在用户可见的聊天气泡/persisted历史里。
                        stripLoopDoneMarkerFromLastAssistantMessage()
                        addSystemMessage("✅ 循环结束：目标已达成（共 $i 轮）")
                        break
                    }
                    if (reply.isBlank()) {
                        addSystemMessage("⚠️ 循环中断：本轮没有得到有效回复（第 $i 轮）")
                        break
                    }
                }
                if (!completed && isActive) {
                    addSystemMessage("🔁 循环结束：已达最大轮数 $maxIterations，目标可能未完全达成。可再次 /loop 继续")
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                agentJob = null
                _isProcessing.value = false
            }
        }
    }

    /** 去掉最后一条 assistant 消息里独占一行的 [LOOP_DONE_MARKER]（/loop 内部完成信号），不应展示给用户。 */
    private fun stripLoopDoneMarkerFromLastAssistantMessage() {
        _messages.update { messages ->
            val lastIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (lastIndex < 0) return@update messages
            val msg = messages[lastIndex]
            val cleaned = msg.content.lines()
                .filterNot { it.trim() == LOOP_DONE_MARKER }
                .joinToString("\n")
                .trimEnd()
            if (cleaned == msg.content) messages
            else messages.toMutableList().apply { this[lastIndex] = msg.copy(content = cleaned) }
        }
        saveMessages()
    }

    /** /compact：用 Flash 模型把对话历史压缩成摘要，替换原始消息，释放上下文 */
    private fun compactConversation() {
        val msgs = _messages.value.filter { it.role != MessageRole.SYSTEM && it.content.isNotBlank() }
        if (msgs.size < 4) {
            addSystemMessage("📦 对话还很短，暂时不需要压缩")
            return
        }
        cancelCurrentOperation()
        _isProcessing.value = true
        addSystemMessage("📦 正在压缩对话历史…")
        agentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val transcript = msgs.joinToString("\n\n") { m ->
                    val who = if (m.role == MessageRole.USER) "用户" else "助手"
                    "$who: ${m.content.take(COMPACT_PER_MESSAGE_CHARS)}"
                }.take(COMPACT_TRANSCRIPT_CHARS)

                val summary = StringBuilder()
                apiClient.streamChat(
                    messages = listOf(
                        com.nuclearboy.api.deepseek.MessageDto(
                            role = "system",
                            content = "你是对话压缩助手。把下面的对话历史压缩成精炼摘要，必须保留：" +
                                "1) 用户的目标和需求 2) 已做出的关键决定 3) 涉及的文件/代码改动 4) 未完成的事项。" +
                                "用要点列出，500 字以内，不要加开场白。",
                        ),
                        com.nuclearboy.api.deepseek.MessageDto(role = "user", content = transcript),
                    ),
                    modelTier = ModelTier.V4_FLASH,
                    thinkingMode = ThinkingMode.DISABLED,
                    tools = null,
                ).collect { event ->
                    when (event) {
                        is com.nuclearboy.api.deepseek.StreamEvent.Content ->
                            if (!event.isReasoning) summary.append(event.text)
                        is com.nuclearboy.api.deepseek.StreamEvent.Error ->
                            throw RuntimeException(event.appError.humanMessage)
                        // Mid-stream retry restarts the request from scratch — discard
                        // whatever partial summary text the failed attempt already streamed.
                        is com.nuclearboy.api.deepseek.StreamEvent.ContentReset -> summary.setLength(0)
                        else -> {}
                    }
                }

                if (summary.isBlank()) throw RuntimeException("摘要为空")
                val summaryMsg = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "📦 对话已压缩，以下是之前内容的摘要：\n\n$summary",
                    status = MessageStatus.COMPLETE,
                )
                // Atomic replacement — avoids erasing chunks that arrived between
                // the streaming guard check and this write (finding 1).
                _messages.update { listOf(summaryMsg) }
                contextManager.reset()
                lastUserMessage = null
                saveMessages()
                _scrollToBottom.value++
                android.util.Log.e("NuclearBoy", "[ChatVM] compact done, summaryLen=${summary.length}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("NuclearBoy", "[ChatVM] compact failed: ${e.message}", e)
                addSystemMessage("📦 压缩失败：${e.message?.take(100) ?: "未知错误"}，对话保持原样")
            } finally {
                _isProcessing.value = false
                agentJob = null
            }
        }
    }

    /** /rewind [n]：回退最近 n 轮对话（一轮 = 一条用户消息及其后的所有回复） */
    private fun rewindConversation(rounds: Int) {
        val n = rounds.coerceIn(1, 20)
        val msgs = _messages.value.toMutableList()
        var removed = 0
        repeat(n) {
            val idx = msgs.indexOfLast { it.role == MessageRole.USER }
            if (idx < 0) return@repeat
            // subList + clear 比逐条 removeAt(size-1) 更直接
            msgs.subList(idx, msgs.size).clear()
            removed++
        }
        if (removed == 0) {
            addSystemMessage("⏪ 没有可回退的对话")
            return
        }
        _messages.value = msgs
        lastUserMessage = msgs.findLast { it.role == MessageRole.USER }
        saveMessages()
        _scrollToBottom.value++
        addSystemMessage("⏪ 已回退 $removed 轮对话")
    }

    private fun addSystemMessage(text: String) {
        _messages.update {
            it + ChatMessage(role = MessageRole.SYSTEM, content = text, status = MessageStatus.COMPLETE)
        }
        _scrollToBottom.value++
    }

    private fun buildTurnCustomInstructions(base: String, turnGuard: String?): String = buildString {
        val baseTrimmed = base.trim()
        if (baseTrimmed.isNotEmpty()) append(baseTrimmed)
        val guardTrimmed = turnGuard?.trim().orEmpty()
        if (guardTrimmed.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(guardTrimmed)
        }
    }

    private fun goalFile(projectId: String = currentProjectId ?: "__general__"): java.io.File =
        java.io.File(fileOperations.getWorkspaceRoot(), "$projectId/.agent/session_goal.txt")

    private fun loadSessionGoal(projectId: String): String? {
        return try {
            goalFile(projectId).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[ChatVM] loadSessionGoal failed: ${e.message}")
            null
        }
    }

    private fun saveSessionGoal() {
        try {
            val file = goalFile()
            file.parentFile?.mkdirs()
            val goal = sessionGoal?.trim()
            if (goal.isNullOrBlank()) {
                if (file.exists()) file.delete()
            } else {
                file.writeText(goal)
            }
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[ChatVM] saveSessionGoal failed: ${e.message}")
        }
    }

    // ── Private: event handling ─────────────────────────────────────────

    private suspend fun handleAgentEvent(event: AgentEvent, thinkingId: String) {
        val turnEpoch = turnEpochs[thinkingId] ?: return
        if (turnEpoch != conversationEpoch) return
        when (event) {
            is AgentEvent.Thinking -> {
                // 每500字才打一次日志，避免流式思考阶段每token都触发 Log.e
                if (event.message.length % 500 < 20) {
                    android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() Thinking msgLen=${event.message.length}")
                }
                _streamingState.update { current ->
                    (current ?: StreamingState(thinkingId)).copy(
                        isThinking = true,
                        thinkingText = (current?.thinkingText ?: "") + event.message,
                    )
                }
                updateAssistantMessage(thinkingId) { msg ->
                    msg.copy(
                        status = MessageStatus.THINKING,
                        reasoningContent = _streamingState.value?.thinkingText,
                    )
                }
            }

            is AgentEvent.StreamContent -> {
                // 每500字才打一次日志，避免流式输出阶段每token都触发 Log.e
                if (event.text.length % 500 < 20) {
                    android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() StreamContent textLen=${event.text.length}")
                }
                _streamingState.update { current ->
                    (current ?: StreamingState(thinkingId)).copy(
                        isThinking = false,
                        isStreaming = true,
                        responseText = (current?.responseText ?: "") + event.text,
                    )
                }
                val streamedContent = _streamingState.value?.responseText ?: event.text
                updateAssistantMessage(thinkingId) { msg ->
                    msg.copy(
                        content = streamedContent,
                        status = MessageStatus.STREAMING,
                    )
                }
            }

            is AgentEvent.Response -> {
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() Response contentLen=${event.message.content.length} toolCalls=${event.message.toolCalls.size}")
                updateAssistantMessage(thinkingId) { msg ->
                    val finalContent = if (event.message.content.isNotBlank())
                        event.message.content else msg.content
                    msg.copy(
                        content = finalContent,
                        reasoningContent = event.message.reasoningContent,
                        toolCalls = event.message.toolCalls.ifEmpty { msg.toolCalls },
                        tokenUsage = event.message.tokenUsage,
                        // Preserve terminal error responses (for example the
                        // duplicate-tool-call guard). Ordinary streamed
                        // responses carry COMPLETE and keep the existing path.
                        status = event.message.status,
                    )
                }
                saveMessages(turnEpoch)
            }

            is AgentEvent.ToolExecution -> {
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() ToolExecution toolName=${event.toolName} status=${event.status} toolCallId=${event.toolCallId}")
                val record = ToolCallRecord(
                    toolName = event.toolName,
                    input = event.input,
                    status = event.status,
                    toolCallId = event.toolCallId,
                )
                _streamingState.update { current ->
                    (current ?: StreamingState(thinkingId)).copy(
                        activeToolCalls = (current?.activeToolCalls ?: emptyList()) + record,
                    )
                }
                updateAssistantMessage(thinkingId) { msg ->
                    val existingIdx = msg.toolCalls.indexOfFirst { it.toolCallId == event.toolCallId && it.toolCallId != null }
                    val updatedCalls = if (existingIdx >= 0) {
                        msg.toolCalls.toMutableList().also { it[existingIdx] = record }
                    } else {
                        msg.toolCalls + record
                    }
                    msg.copy(
                        toolCalls = updatedCalls,
                        status = MessageStatus.EXECUTING,
                    )
                }
            }

            is AgentEvent.ToolResultEvent -> {
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() ToolResultEvent toolName=${event.toolName} success=${event.result.success} outputLen=${event.result.output.length}")
                updateAssistantMessage(thinkingId) { msg ->
                    // 优先按 toolCallId 精确匹配，避免同名工具被多次调用时结果贴错卡片；
                    // 无 id 时回退到"同名且尚无结果"的旧逻辑。
                    val updatedCalls = msg.toolCalls.map { call ->
                        val matches = if (event.toolCallId.isNotEmpty() && call.toolCallId != null) {
                            call.toolCallId == event.toolCallId && call.output == null
                        } else {
                            call.toolName == event.toolName && call.output == null
                        }
                        if (matches) {
                            call.copy(
                                // 截断超长输出，避免大型工具结果撑大内存和持久化文件
                                output = event.result.output.takeIf { it.length <= MAX_TOOL_OUTPUT_DISPLAY_CHARS }
                                    ?: (event.result.output.take(MAX_TOOL_OUTPUT_DISPLAY_CHARS) + "\n…（输出过长已截断，完整结果已注入对话上下文）"),
                                status = if (event.result.success) ToolCallStatus.COMPLETED
                                    else ToolCallStatus.FAILED,
                                completedAt = System.currentTimeMillis(),
                            )
                        } else call
                    }
                    msg.copy(toolCalls = updatedCalls)
                }
                // Tool results are meaningful context even if the app is
                // closed before the final assistant response arrives.
                saveMessages(turnEpoch)
                // Skill Creator writes .agent/skills through Python, so the
                // tool result itself has no FileChange list for AgentEngine to
                // emit. Reload synchronously before the next model turn so
                // the current chat and sidebar immediately show the new skill.
                if (event.result.success && event.toolName.startsWith("skill_")) {
                    try {
                        val skillsDir = java.io.File(
                            fileOperations.projectRoot(),
                            AppConstants.PROJECT_SKILLS_DIR,
                        )
                        skillManager.reloadProjectSkills(skillsDir)
                        // Skill Creator can target the app-wide store with
                        // scope=global.  Refresh that store as well so the
                        // new skill is visible and registered as a tool in
                        // this same conversation, without an app restart.
                        skillManager.reloadGlobalSkills()
                        android.util.Log.e(
                            "NuclearBoy",
                            "[ChatVM] skill tool completed; activeSkills=${skillManager.activeSkills.value.size}",
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("NuclearBoy", "[ChatVM] skill reload failed: ${e.message}", e)
                    }
                }
            }

            is AgentEvent.FileChanged -> {
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() FileChanged count=${event.changes.size} paths=${event.changes.take(3).joinToString { it.filePath }}")
                refreshProjectFiles()
                // Reload skills if skill files changed
                if (event.changes.any { it.filePath.contains(".agent/skills/") || it.filePath.contains("skill.yaml") }) {
                    val skillsDir = java.io.File(fileOperations.projectRoot(), AppConstants.PROJECT_SKILLS_DIR)
                    skillManager.loadProjectSkills(skillsDir)
                }
                if (event.changes.isNotEmpty()) {
                    currentAssistantMsgId?.let { id ->
                        updateAssistantMessage(id) { msg ->
                            msg.copy(fileChanges = event.changes)
                        }
                    }
                }
            }

            is AgentEvent.ContextWarning -> {
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() ContextWarning level=${event.level}")
                val sysMsg = ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = when (event.level) {
                        com.nuclearboy.api.deepseek.ContextWarningLevel.YELLOW ->
                            "上下文空间稍显紧张，已自动整理早期对话"
                        com.nuclearboy.api.deepseek.ContextWarningLevel.RED ->
                            "上下文即将用完，已进行深度压缩"
                        else -> event.message
                    },
                    status = MessageStatus.COMPLETE,
                )
                _messages.update { it + sysMsg }
                _scrollToBottom.value++
            }

            is AgentEvent.TokenUpdate -> {
                // TokenUpdate 每 10 token 触发一次（TokenTracker 已节流），日志只打最终值
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() TokenUpdate total=${event.usage.totalTokens}")
                currentAssistantMsgId?.let { id ->
                    updateAssistantMessage(id) { msg ->
                        msg.copy(tokenUsage = event.usage)
                    }
                }
            }

            is AgentEvent.Error -> {
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() Error message=${event.error.humanMessage}")
                val content = "处理时遇到了问题：${event.error.humanMessage}"
                val activeAssistantId = currentAssistantMsgId
                if (activeAssistantId != null) {
                    updateAssistantMessage(activeAssistantId) { msg ->
                        msg.copy(
                            content = msg.content.ifBlank { content },
                            status = MessageStatus.ERROR,
                        )
                    }
                    _streamingState.update { state ->
                        if (state?.messageId == activeAssistantId && state.responseText.isEmpty()) {
                            state.copy(responseText = content)
                        } else {
                            state
                        }
                    }
                } else {
                    val errorMsg = ChatMessage(
                        role = MessageRole.SYSTEM,
                        content = content,
                        status = MessageStatus.ERROR,
                    )
                    _messages.update { it + errorMsg }
                }
                _scrollToBottom.value++
            }

            is AgentEvent.Complete -> {
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() Complete")
                /* Finalization handled in finally block */
            }

            is AgentEvent.Retrying -> {
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() Retrying")
                updateAssistantMessage(thinkingId) { msg ->
                    msg.copy(status = MessageStatus.THINKING)
                }
            }

            is AgentEvent.ContentReset -> {
                // Mid-stream retry: the request is being re-sent from scratch, so any
                // content/reasoning streamed for the failed attempt must be wiped —
                // otherwise the retried stream's text gets appended after the duplicate.
                android.util.Log.e("NuclearBoy", "[ChatVM] handleAgentEvent() ContentReset")
                _streamingState.update { current ->
                    current?.copy(responseText = "", thinkingText = "")
                }
                updateAssistantMessage(thinkingId) { msg ->
                    msg.copy(content = "", reasoningContent = null, status = MessageStatus.THINKING)
                }
            }
        }
    }

    private fun handleAgentError(error: Throwable, thinkingId: String) {
        val friendlyMsg = when (error) {
            is kotlinx.coroutines.CancellationException -> "已取消"
            else -> "出了一点小问题…${error.message?.take(100) ?: ""}"
        }
        // Update thinking placeholder with error — 已流出的部分内容要保留，不能被错误文案覆盖
        updateAssistantMessage(thinkingId) { msg ->
            msg.copy(
                content = msg.content.ifBlank { friendlyMsg },
                status = MessageStatus.ERROR,
            )
        }
        // Also update assistant message if one was created
        currentAssistantMsgId?.let { id ->
            updateAssistantMessage(id) { msg ->
                if (msg.content.isEmpty()) msg.copy(
                    content = friendlyMsg,
                    status = MessageStatus.ERROR,
                ) else msg
            }
        }
    }

    private suspend fun finalizeProcessing(thinkingId: String, clearAgentJob: Boolean = true) {
        val turnEpoch = turnEpochs[thinkingId] ?: return
        if (turnEpoch != conversationEpoch) {
            turnEpochs.remove(thinkingId)
            return
        }
        // /loop 模式下 clearAgentJob=false：轮间不重置 _isProcessing，防止用户消息插入循环间隙
        if (clearAgentJob) _isProcessing.value = false
        if (clearAgentJob) agentJob = null
        // Mark thinking placeholder as COMPLETE
        updateAssistantMessage(thinkingId) { msg ->
            if (msg.status == MessageStatus.THINKING) msg.copy(status = MessageStatus.COMPLETE)
            else msg
        }
        // Finalize assistant message。用传入的 thinkingId（本轮 assistant 消息 id，稳定不变），
        // 不读 currentAssistantMsgId 类字段——它可能被 cancelCurrentOperation 并发置空，
        // 导致这里 ?.let 被跳过、消息永久卡在 STREAMING/THINKING 状态。
        val streamState = _streamingState.value
        thinkingId.let { id ->
            val finalStatus = if (streamState?.responseText?.isEmpty() != false) "ERROR" else "COMPLETE"
            android.util.Log.e("NuclearBoy", "[ChatVM] finalizeProcessing() assistantId=$id finalStatus=$finalStatus responseTextLen=${streamState?.responseText?.length ?: 0}")
            updateAssistantMessage(id) { msg ->
                if (msg.content.isEmpty() && (streamState?.responseText?.isEmpty() != false)) {
                    msg.copy(content = "没能生成回复，请换个方式描述你的需求", status = MessageStatus.ERROR)
                } else if (msg.status == MessageStatus.STREAMING || msg.status == MessageStatus.THINKING) {
                    msg.copy(status = MessageStatus.COMPLETE)
                } else {
                    msg
                }
            }
        }
        _streamingState.value = null
        // Reset class-level tracking
        currentThinkingId = null
        currentAssistantMsgId = null
        // Show "ready" notification with content summary — find last ASSISTANT message
        val lastAssistant = _messages.value.findLast { it.role == MessageRole.ASSISTANT }
        appendToolActionMissingEvidenceReview(lastAssistant)
        val notificationSent = lastAssistant != null && lastAssistant.content.isNotBlank()
        android.util.Log.e("NuclearBoy", "[ChatVM] finalizeProcessing() notificationSent=$notificationSent lastAssistantRole=${lastAssistant?.role}")
        if (lastAssistant != null && lastAssistant.content.isNotBlank()) {
            notificationCallback?.invoke(lastAssistant.content, currentProjectId)
        } else {
            // 回复为空（网络/工具错误等）：没有内容可以展示"已就绪"通知，但前台服务不能就此晾着——
            // 之前这里什么都不做，"thinking" 状态的常驻通知会一直卡在通知栏，服务也不会停止。
            notificationCallback?.invoke("stop", currentProjectId)
        }
        saveMessages(turnEpoch)
        turnEpochs.remove(thinkingId)
        // 自动提取记忆：从本次对话中学习用户偏好和项目信息
        val projectId = currentProjectId ?: "default"
        val lastUser = lastUserMessage?.content ?: ""
        val lastAi = lastAssistant?.content ?: ""
        if (lastAi.isNotBlank()) {
            // 用 NonCancellable 直接 await（不再 fire-and-forget launch）：
            // 1) /loop 模式下 startLoop 逐轮顺序调用 executeTurn，若这里用一个不等待的
            //    后台协程做缓存失效，下一轮可能在失效完成前就读了 cachedUserProfile/
            //    cachedMemoryCtx，读到本轮写入前的旧数据。
            // 2) NonCancellable 保留了原本 viewModelScope.launch 的效果：即使用户此时
            //    点了"停止"（本轮 turn 已被取消），记忆写入和缓存失效依然会跑完，而不是
            //    在 finally 块里因协程已取消而被直接跳过。
            withContext(NonCancellable) {
                try {
                    val r1 = memoryStore.updateUserProfile("last_project", projectId, "interaction", 0.9f)
                    android.util.Log.e("NuclearBoy", "[ChatVM] memoryWrite last_project=$projectId result=$r1")
                    val convResult = memoryStore.getProfileValue("total_conversations")
                    val convCount = if (convResult is AppResult.Success) (convResult.data?.toIntOrNull() ?: 0) + 1 else 1
                    val r2 = memoryStore.updateUserProfile("total_conversations", convCount.toString(), "interaction", 0.9f)
                    android.util.Log.e("NuclearBoy", "[ChatVM] memoryWrite total_conversations=$convCount result=$r2")
                    val r3 = memoryStore.autoExtractMemories(projectId, lastUser, lastAi)
                    android.util.Log.e("NuclearBoy", "[ChatVM] memoryWrite autoExtractMemories result=$r3")
                    // 记忆已更新，使两个内存缓存都失效，下次对话重新从磁盘/DB 加载
                    invalidateUserProfileCache()
                    invalidateMemoryCache()
                } catch (e: Exception) {
                    android.util.Log.e("NuclearBoy", "[ChatVM] memoryWrite FAILED: ${e.message}", e)
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun updateAssistantMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { messages ->
            val lastIndex = messages.lastIndex
            // Streaming always targets the just-created assistant placeholder, which is
            // the last message in the list for the whole duration of a turn (no other
            // message gets added mid-stream) — this fast path is hit on every single SSE
            // chunk, so skip invoking transform()/comparing ids for every other message
            // in the conversation and just patch the one slot we know changed.
            if (lastIndex >= 0 && messages[lastIndex].id == id) {
                messages.toMutableList().apply { this[lastIndex] = transform(this[lastIndex]) }
            } else {
                messages.map { msg -> if (msg.id == id) transform(msg) else msg }
            }
        }
    }

    private fun appendToolActionMissingEvidenceReview(lastAssistant: ChatMessage?) {
        val userText = lastUserMessage?.content.orEmpty()
        val assistant = lastAssistant ?: return
        // A failed request is still a completed turn from the user's point of view.
        // ToolMissingEvidenceReviewUiTest deliberately uses an invalid mock:// endpoint
        // to finish quickly; the assistant bubble is ERROR in that case.  Keep the
        // post-turn evidence warning for both terminal statuses so a tool-shaped
        // request cannot silently lose its review just because the gateway failed.
        if (assistant.status != MessageStatus.COMPLETE && assistant.status != MessageStatus.ERROR) return
        if (assistant.content.isBlank()) return
        val hasVisibleToolEvidence = assistant.toolCalls.isNotEmpty() || assistant.fileChanges.isNotEmpty()
        val review = buildToolActionMissingEvidenceReview(
            userText = userText,
            assistantText = assistant.content,
            hasVisibleToolEvidence = hasVisibleToolEvidence,
        ) ?: return
        val lastMessage = _messages.value.lastOrNull()
        if (lastMessage?.role == MessageRole.SYSTEM && lastMessage.content == review) return
        _messages.update {
            it + ChatMessage(role = MessageRole.SYSTEM, content = review, status = MessageStatus.COMPLETE)
        }
        _scrollToBottom.value++
    }

    companion object {
        /** 工具卡片实时进度日志的最大保留字符数 */
        private const val MAX_TOOL_PROGRESS_CHARS = 4000

        /** /loop 默认轮数与上限 */
        private const val LOOP_DEFAULT_ITERATIONS = 5
        private const val LOOP_MAX_ITERATIONS = 10

        /** 循环模式下 AI 用于声明目标达成的标记 */
        private const val LOOP_DONE_MARKER = "TASK_COMPLETE"

        /** /compact 压缩时单条消息和整体文本的截断长度 */
        private const val COMPACT_PER_MESSAGE_CHARS = 2_000
        private const val COMPACT_TRANSCRIPT_CHARS = 60_000

        /** 工具执行结果在 UI 卡片里的最大显示字符数（超出截断，完整结果仍注入对话） */
        private const val MAX_TOOL_OUTPUT_DISPLAY_CHARS = 8_000

        /** conversation.json 最多持久化的消息条数 */
        private const val MAX_PERSISTED_MESSAGES = 50

        /**
         * 文件面板/附件角标不展示的内部状态目录与开发缓存/VCS 噪音。
         * .agent/__general__ 是 App 内部状态；其余为构建工具生成物，
         * 引用为附件无意义，列出来只会干扰用户。
         */
        private val HIDDEN_FILE_ENTRIES = setOf(
            ".agent", "__general__", "__pycache__",
            ".git", ".gradle", ".idea", ".vscode",
            "node_modules", ".pytest_cache", ".DS_Store",
        )
    }
}
