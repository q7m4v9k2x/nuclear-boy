package com.nuclearboy.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuclearboy.api.deepseek.ApiKeyManager
import com.nuclearboy.common.*
import com.nuclearboy.ui.chat.components.CommandShortcutBar
import com.nuclearboy.ui.chat.components.FilePanelEmptyState
import com.nuclearboy.ui.chat.components.FilePanelOverviewBar
import com.nuclearboy.ui.chat.components.FileReferenceIconButton
import com.nuclearboy.ui.chat.components.FileReferenceTextButton
import com.nuclearboy.ui.chat.components.FilePanelSearchField
import com.nuclearboy.ui.chat.components.FilePanelSortBar
import com.nuclearboy.ui.chat.components.FileSelectionActionBar
import com.nuclearboy.ui.chat.components.ScrollToBottomAction
import com.nuclearboy.ui.chat.components.ToolActionDraftHintBar
import com.nuclearboy.ui.chat.parts.appendToolRealityGuard
import com.nuclearboy.ui.chat.parts.appendToChatDraft
import com.nuclearboy.ui.chat.parts.buildFilePanelOverview
import com.nuclearboy.ui.chat.parts.buildFileReferencePrompt
import com.nuclearboy.ui.chat.parts.buildFileReferencesPrompt
import com.nuclearboy.ui.chat.parts.detectToolActionDraftHint
import com.nuclearboy.ui.chat.parts.fileReferenceToastMessage
import com.nuclearboy.ui.chat.parts.fileSelectionStatusLabel
import com.nuclearboy.ui.chat.parts.fileSelectionTotalSizeBytes
import com.nuclearboy.ui.chat.parts.fileReferencesToastMessage
import com.nuclearboy.ui.chat.parts.filePanelClearFilterDescription
import com.nuclearboy.ui.chat.parts.filePanelEmptyStateMessage
import com.nuclearboy.ui.chat.parts.filePanelFilterSummary
import com.nuclearboy.ui.chat.parts.filterQueryAfterMatchedReference
import com.nuclearboy.ui.chat.parts.filterFilePanelEntries
import com.nuclearboy.ui.chat.parts.FilePanelSortMode
import com.nuclearboy.ui.chat.parts.removeReferencedFilePaths
import com.nuclearboy.ui.chat.parts.projectRelativeFilePath
import com.nuclearboy.ui.chat.parts.selectVisibleFilePaths
import com.nuclearboy.ui.chat.parts.selectedFilePanelEntries
import com.nuclearboy.ui.chat.parts.shouldClosePanelAfterMatchedReference
import com.nuclearboy.ui.chat.parts.sortFilePanelEntries
import com.nuclearboy.ui.chat.parts.shouldFollowChatScroll
import com.nuclearboy.ui.chat.parts.shouldShowFileSelectionActionBar
import com.nuclearboy.ui.chat.parts.shouldShowFilePanelClearFilterAction
import com.nuclearboy.ui.chat.parts.shouldShowJumpToBottom
import com.nuclearboy.ui.chat.parts.toggleSelectedFilePath
import com.nuclearboy.ui.chat.parts.unselectHiddenFilePaths
import com.nuclearboy.ui.chat.parts.unselectVisibleFilePaths
import com.nuclearboy.ui.chat.parts.visibleFilePanelEntries
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    projectId: String = "",
    initialMessage: String = "",
    onNavigateBack: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onNotification: ((String, String?) -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    // Project setup loads persisted history and project skills. Keep the
    // optional auto-send behind that same suspend boundary so an initial
    // message cannot race setup and get overwritten or saved to no project.
    LaunchedEffect(projectId, initialMessage) {
        viewModel.setProject(projectId)
        if (initialMessage.isNotEmpty()) {
            viewModel.sendMessage(initialMessage)
        }
    }

    // 分开订阅而不是合并成一个 uiState：这几个字段的更新频率差很多
    // （streamingState 每个流式 chunk 都变，isProcessing/scrollToBottom 一轮才变一次），
    // 合并成一个对象会让任意一个字段变化都触发整个 ChatScreen 里所有读到该字段的地方重组。
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val streamingState by viewModel.streamingState.collectAsState()
    val scrollToBottom by viewModel.scrollToBottom.collectAsState()
    val savedConversations by viewModel.savedConversations.collectAsState()
    val apiKeyState by viewModel.apiKeyState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        android.util.Log.e("NuclearBoy", "[ChatScreen] ChatScreen composed projectId=$projectId")
        viewModel.notificationCallback = onNotification
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showFiles by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableIntStateOf(0) } // 0=Chat 1=Think 2=Expert
    var filePanelScrollState by remember { mutableStateOf(0f) }
    var filePanelScrollMax by remember { mutableStateOf(0f) }
    var inputDraft by rememberSaveable(projectId) { mutableStateOf("") }
    var inputFocusRequest by remember { mutableLongStateOf(0L) }
    var forceNextScrollToBottom by remember { mutableStateOf(true) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showConversationHistory by remember { mutableStateOf(false) }
    // 会话内搜索
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchPointer by remember { mutableIntStateOf(0) }
    val searchMatches = remember(searchQuery, messages) {
        MessageSearch.find(messages, searchQuery)
    }
    val currentMatchId = searchMatches.getOrNull(matchPointer)
        ?.let { messages.getOrNull(it)?.id }
    LaunchedEffect(matchPointer, searchMatches) {
        searchMatches.getOrNull(matchPointer)?.let { listState.animateScrollToItem(it) }
    }
    // 外部分享进来的文本：填入输入框（不自动发送，让用户补充指令再发）
    LaunchedEffect(Unit) {
        com.nuclearboy.common.SharedIntentBus.shared.collect { shared ->
            inputDraft = if (inputDraft.isBlank()) shared else "$inputDraft\n$shared"
            inputFocusRequest++
        }
    }
    val totalListItems by remember {
        derivedStateOf { listState.layoutInfo.totalItemsCount }
    }
    val lastVisibleItemIndex by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
    }
    val followChatScroll by remember {
        derivedStateOf {
            shouldFollowChatScroll(
                totalItemsCount = totalListItems,
                lastVisibleItemIndex = lastVisibleItemIndex,
            )
        }
    }
    val showJumpToBottom by remember {
        derivedStateOf {
            shouldShowJumpToBottom(
                totalItemsCount = totalListItems,
                lastVisibleItemIndex = lastVisibleItemIndex,
            )
        }
    }
    val lastMessageContentLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(projectId) {
        forceNextScrollToBottom = true
    }

    // File picker for attachments
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            android.util.Log.e("NuclearBoy", "[ChatScreen] filePicker selected uri=$uri")
            // 移到后台线程执行文件 IO，避免主线程阻塞
            scope.launch { copyAttachedFile(context, it, viewModel) }
        }
    }

    // Instant scroll to bottom on project switch (first load)
    LaunchedEffect(scrollToBottom) {
        if (messages.isNotEmpty() && (forceNextScrollToBottom || followChatScroll)) {
            listState.requestScrollToItem(messages.lastIndex)
        }
        // Reset unconditionally so clearing the conversation while scrollToBottom fires
        // does not leak `true` into the next conversation (finding 11).
        forceNextScrollToBottom = false
    }
    // Consolidated scroll trigger: animate when a new message is appended, snap (instant)
    // when only content length changed (streaming chunks).  Having two separate
    // LaunchedEffects with different scroll calls caused the instant snap to cancel the
    // in-flight animation every time streaming started, producing a jarring jump (finding 10).
    val prevMessageCount = remember { mutableIntStateOf(messages.size) }
    LaunchedEffect(messages.size, lastMessageContentLength) {
        if (messages.isNotEmpty() && followChatScroll) {
            val countChanged = messages.size != prevMessageCount.intValue
            prevMessageCount.intValue = messages.size
            if (countChanged) {
                listState.animateScrollToItem(messages.lastIndex)
            } else {
                listState.requestScrollToItem(messages.lastIndex)
            }
        }
    }

    // Refresh files every time panel opens
    LaunchedEffect(showFiles) {
        android.util.Log.e("NuclearBoy", "[ChatScreen] filePanel showFiles=$showFiles")
        if (showFiles) viewModel.refreshProjectFiles()
    }

    BackHandler(enabled = showFiles) {
        android.util.Log.e("NuclearBoy", "[ChatScreen] BackHandler closing file panel")
        showFiles = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .semantics { contentDescription = "NUCLEAR BOY" },
        containerColor = NuclearBoyTheme.colorScheme.material.background,
        topBar = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onMenuClick, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Menu, "菜单", tint = NuclearBoyTheme.colorScheme.material.primary, modifier = Modifier.size(26.dp))
                    }
                    TopModelSelector(
                        state = apiKeyState,
                        onSelect = { viewModel.selectModel(it) },
                    )
                    Spacer(Modifier.weight(1f))
                    // 顶栏标题
                    if (projectId == "__general__") {
                        Text(
                            "☢️ 核弹男孩",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = NuclearBoyTheme.colorScheme.material.primary,
                        )
                    } else {
                        val projectName = viewModel.projectName.collectAsState().value
                        if (projectName.isNotEmpty()) {
                            Text(
                                text = projectName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF08090B),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NuclearBoyTheme.colorScheme.material.primary)
                                    .padding(horizontal = 8.dp, vertical = 1.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 新对话（清空当前对话，带确认）。即使当前消息仍在异步加载，
                    // 也要让按钮有反馈；确认时 clearConversation 会安全地处理空会话。
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            "新对话",
                            tint = NuclearBoyTheme.colorScheme.material.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.refreshSavedConversations()
                            showConversationHistory = true
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.History,
                            "历史对话",
                            tint = if (showConversationHistory) NuclearBoyTheme.colorScheme.material.primary
                                   else NuclearBoyTheme.colorScheme.material.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // 会话内搜索开关
                    IconButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) { searchQuery = ""; matchPointer = 0 }
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Search,
                            "搜索对话",
                            tint = if (showSearch) NuclearBoyTheme.colorScheme.material.primary
                                   else NuclearBoyTheme.colorScheme.material.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // 导出/分享当前对话为 Markdown
                    val exportContext = LocalContext.current
                    IconButton(
                        onClick = {
                            val md = viewModel.buildExportMarkdown()
                            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TITLE, "核弹男孩对话")
                                putExtra(android.content.Intent.EXTRA_TEXT, md)
                            }
                            runCatching {
                                exportContext.startActivity(
                                    android.content.Intent.createChooser(share, "分享对话")
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Share,
                            "导出对话",
                            tint = NuclearBoyTheme.colorScheme.material.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // 文件面板按钮 — 类似首页设置图标
                    IconButton(
                        onClick = {
                            if (!showFiles) viewModel.refreshProjectFiles()
                            showFiles = !showFiles
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            "文件",
                            tint = if (showFiles) NuclearBoyTheme.colorScheme.material.primary
                                   else NuclearBoyTheme.colorScheme.material.onSurfaceVariant,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                // 会话内搜索行
                if (showSearch) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it; matchPointer = 0 },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("搜索本次对话…", fontSize = 13.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (searchMatches.isEmpty()) (if (searchQuery.isBlank()) "" else "无匹配")
                            else "${matchPointer + 1}/${searchMatches.size}",
                            fontSize = 12.sp,
                            color = NuclearBoyTheme.colorScheme.material.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = { if (searchMatches.isNotEmpty()) matchPointer = (matchPointer - 1 + searchMatches.size) % searchMatches.size },
                            enabled = searchMatches.isNotEmpty(), modifier = Modifier.size(36.dp),
                        ) { Icon(Icons.Default.KeyboardArrowUp, "上一个", modifier = Modifier.size(20.dp)) }
                        IconButton(
                            onClick = { if (searchMatches.isNotEmpty()) matchPointer = (matchPointer + 1) % searchMatches.size },
                            enabled = searchMatches.isNotEmpty(), modifier = Modifier.size(36.dp),
                        ) { Icon(Icons.Default.KeyboardArrowDown, "下一个", modifier = Modifier.size(20.dp)) }
                    }
                }
                // 聊天/思考/专家是 DeepSeek 官方的模型档位路由（Flash/Pro + thinking），
                // 第三方模型的请求会覆盖模型名并剥离 thinking 参数，模式完全不生效——不展示以免误导。
                if (!apiKeyState.customProviderEnabled) {
                    TokenHudBar(
                        selectedMode = selectedMode,
                        onModeChange = { selectedMode = it; viewModel.setMode(it) },
                    )
                }
            }
        },
    ) { paddingValues ->
        // 输入栏不再放进 Scaffold 的 bottomBar：bottomBar 自带 imePadding()，Scaffold 会把它的
        // 实时测量高度（键盘动画每一帧都在变）转成 paddingValues 喂给 content，逼着下面的消息
        // LazyColumn 在键盘弹出的每一帧都重新 measure/relayout —— 这就是键盘"一点点往上挤"而不是
        // 一次性弹出的卡顿根因。改成把输入栏当成悬浮在消息列表上方的 Box overlay，只有它自己响应
        // imePadding 动画；列表用输入栏"去掉ime贡献后的静止高度"来留白，这个值只在输入栏自身内容
        // （命令栏/多行文本/提示条）变化时才更新，不会随键盘动画逐帧刷新。
        // inputBarRestHeightPx only changes when the input bar's own static content changes
        // (command bar visibility, text wrapping to more lines, hint bar) — reading it here
        // does NOT subscribe this whole (expensive) content scope to per-frame ime updates;
        // that subscription is isolated inside ImeAwareInputBarSlot below.
        var inputBarRestHeightPx by remember { mutableIntStateOf(0) }
        val inputBarRestHeight = with(LocalDensity.current) { inputBarRestHeightPx.toDp() }

        // 用完整的 paddingValues（不只是 top）：bottomBar 已经不在 Scaffold 里了，
        // 这里的 bottom 只是 Scaffold 兜底的静态安全区（导航栏），不会随键盘动画变化，
        // 但 start/end 仍然需要，横屏下侧边有挖孔/手势区的机型才不会把内容画到下面。
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (messages.isEmpty()) {
                EmptyChatView(
                    modifier = Modifier.fillMaxSize().padding(bottom = inputBarRestHeight),
                    onSuggestionClick = { text -> viewModel.sendMessage(text) },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = inputBarRestHeight + 8.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                    // General Agent 欢迎卡片
                    if (projectId == "__general__" && messages.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = NuclearBoyTheme.colorScheme.material.surfaceVariant.copy(alpha = 0.5f)),
                                border = BorderStroke(1.dp, NuclearBoyTheme.colorScheme.material.primary.copy(alpha = 0.2f)),
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("☢️ 欢迎来到核弹男孩", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NuclearBoyTheme.colorScheme.material.primary)
                                    Spacer(Modifier.height(12.dp))
                                    Text("我可以帮你：", fontSize = 14.sp, color = NuclearBoyTheme.colorScheme.material.onSurface)
                                    Spacer(Modifier.height(8.dp))
                                    WelcomeItem("🔍", "搜资料", "Bing + 百度双引擎搜索最新信息")
                                    WelcomeItem("🐍", "写代码", "Python 3.11 执行器 + Java 桥接控制手机")
                                    WelcomeItem("📄", "生成文档", "Word / Excel / PPT 一键生成")
                                    WelcomeItem("📁", "管项目", "多项目切换，文件浏览编辑")
                                    WelcomeItem("📱", "控硬件", "闪光灯、闹钟、通知、日历… 全搞定")
                                    Spacer(Modifier.height(12.dp))
                                    Text("直接跟我说你想做什么，开干吧 💪", fontSize = 12.sp, color = NuclearBoyTheme.colorScheme.material.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    val lastMessageId = messages.lastOrNull()?.id
                    items(items = messages, key = { it.id }) { message ->
                        val isLast = message.id == lastMessageId
                        val isStreaming = isLast && streamingState?.isStreaming == true

                        MessageBubble(
                            message = message,
                            isStreaming = isStreaming,
                            onRetry = {
                                if (message.role == MessageRole.ASSISTANT) viewModel.retryLastMessage()
                            },
                            onCopy = { text ->
                                copyToClipboard(context, text)
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            onEdit = {
                                viewModel.editAndResend(message.id)?.let { content ->
                                    inputDraft = content
                                    inputFocusRequest++
                                }
                            },
                            searchHighlighted = showSearch && message.id == currentMatchId,
                            onDelete = { viewModel.deleteMessage(message.id) },
                        )
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
                ScrollToBottomAction(
                    visible = showJumpToBottom,
                    onClick = {
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = inputBarRestHeight + 12.dp),
                )
            }
            }

            ImeAwareInputBarSlot(
                modifier = Modifier.align(Alignment.BottomCenter),
                onRestHeightMeasured = { inputBarRestHeightPx = it },
            ) {
                ChatInputBar(
                    text = inputDraft,
                    onTextChange = { inputDraft = it },
                    isProcessing = isProcessing,
                    onSend = { text -> viewModel.sendMessage(text) },
                    onCancel = { viewModel.cancelCurrentOperation() },
                    fileCount = viewModel.projectFiles.collectAsState().value.size,
                    hasMessages = messages.any { it.role != MessageRole.SYSTEM },
                    focusRequest = inputFocusRequest,
                    placeholder = if (projectId == "__general__") "和核弹男孩对话…" else "输入指令…",
                    showToolActionDraftHint = true,
                    onAttachFile = {
                        android.util.Log.e("NuclearBoy", "[ChatScreen] filePicker launched")
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }

        // ── Right-side File Drawer ──────────────────────
        AnimatedVisibility(
            visible = showFiles,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(250)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)),
        ) {
            ProjectFilePanel(
                files = viewModel.projectFiles.collectAsState().value,
                browseDir = viewModel.browseDir.collectAsState().value,
                projectRoot = viewModel.getProjectRoot(),
                onRefresh = { viewModel.refreshProjectFiles(viewModel.browseDir.value) },
                onNavigateTo = { viewModel.navigateToDir(it) },
                onNavigateUp = { viewModel.navigateUp() },
                context = context,
                onClose = { showFiles = false },
                onReferenceFile = { file ->
                    val prompt = buildFileReferencePrompt(
                        filePath = file.path,
                        projectRoot = viewModel.getProjectRoot(),
                    )
                    inputDraft = appendToChatDraft(inputDraft, prompt)
                    showFiles = false
                    inputFocusRequest++
                    Toast.makeText(
                        context,
                        fileReferenceToastMessage(
                            fileName = file.name,
                            fileSizeLabel = fileSelectionTotalSizeBytes(listOf(file)).toFileSizeString(),
                            filePathLabel = projectRelativeFilePath(
                                filePath = file.path,
                                projectRoot = viewModel.getProjectRoot(),
                            ),
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onReferenceFiles = { files, closePanel, remainingSelectedCount ->
                    val prompt = buildFileReferencesPrompt(
                        filePaths = files.map { it.path },
                        projectRoot = viewModel.getProjectRoot(),
                    )
                    inputDraft = appendToChatDraft(inputDraft, prompt)
                    if (closePanel) {
                        showFiles = false
                        inputFocusRequest++
                    }
                    Toast.makeText(
                        context,
                        fileReferencesToastMessage(
                            referencedCount = files.size,
                            remainingSelectedCount = remainingSelectedCount,
                            referencedSizeLabel = fileSelectionTotalSizeBytes(files).toFileSizeString(),
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }

        // 远程电脑权限审批弹窗
        val permissionPrompt by viewModel.permissionPrompt.collectAsState()
        permissionPrompt?.let { request ->
            AlertDialog(
                onDismissRequest = { viewModel.respondPermission(false) },
                title = { Text("电脑请求权限", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("电脑上的 ${request.toolName} 操作需要你批准：")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            request.inputSummary.ifBlank { "(无参数详情)" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = NuclearBoyTheme.colorScheme.material.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.respondPermission(true) }) { Text("允许") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.respondPermission(false) }) { Text("拒绝") }
                },
            )
        }

        // 新对话确认
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("开始新对话？", fontWeight = FontWeight.Bold) },
                text = { Text("会清空当前对话内容，重新开始。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearConversation()
                        showClearConfirm = false
                    }) { Text("新对话") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                },
            )
        }

        if (showConversationHistory) {
            AlertDialog(
                onDismissRequest = { showConversationHistory = false },
                title = { Text("历史对话", fontWeight = FontWeight.Bold) },
                text = {
                    if (savedConversations.isEmpty()) {
                        Text("还没有归档的对话。点击“新对话”后，当前对话会保存在这里。")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(savedConversations, key = { it.id }) { conversation ->
                                OutlinedButton(
                                    onClick = {
                                        viewModel.restoreConversation(conversation.id)
                                        showConversationHistory = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.Start,
                                    ) {
                                        Text(
                                            conversation.title.ifBlank { "未命名对话" },
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${conversation.messageCount} 条消息 · ${formatConversationTime(conversation.updatedAt)}",
                                            fontSize = 11.sp,
                                            color = NuclearBoyTheme.colorScheme.material.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showConversationHistory = false }) { Text("关闭") }
                },
            )
        }
    } // Box
}

private fun formatConversationTime(timestamp: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))

// ═══════════════════════════════════════════════════════════════════════
//  File Panel
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ProjectFilePanel(
    files: List<FileInfo>, browseDir: String, projectRoot: String,
    onRefresh: () -> Unit, onNavigateTo: (String) -> Unit, onNavigateUp: () -> Unit,
    context: Context,
    onClose: () -> Unit = {},
    onReferenceFile: (FileInfo) -> Unit = {},
    onReferenceFiles: (List<FileInfo>, Boolean, Int) -> Unit = { _, _, _ -> },
) {
    val nc = NuclearBoyTheme.colorScheme
    val fileListState = rememberLazyListState()

    // Preview state
    var showPreview by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<FileInfo?>(null) }
    var previewContent by remember { mutableStateOf<String?>(null) }
    var filterQuery by rememberSaveable(browseDir) { mutableStateOf("") }
    var sortMode by rememberSaveable(browseDir) { mutableStateOf(FilePanelSortMode.Name) }
    var selectedFilePaths by rememberSaveable(browseDir) { mutableStateOf(emptyList<String>()) }
    var showSelectedOnly by rememberSaveable(browseDir) { mutableStateOf(false) }
    val selectedPathSet = remember(selectedFilePaths) { selectedFilePaths.toSet() }
    val filteredFiles = remember(files, filterQuery) {
        filterFilePanelEntries(files, filterQuery)
    }
    val sortedFilteredFiles = remember(filteredFiles, sortMode) {
        sortFilePanelEntries(filteredFiles, sortMode)
    }
    val selectedFiles = remember(files, selectedFilePaths, sortMode) {
        sortFilePanelEntries(
            selectedFilePanelEntries(
                files = files,
                selectedPaths = selectedFilePaths,
            ),
            sortMode,
        )
    }
    val allVisibleSelectableFileCount = remember(sortedFilteredFiles) {
        sortedFilteredFiles.count { !it.isDirectory }
    }
    val visibleFiles = remember(sortedFilteredFiles, selectedFiles, showSelectedOnly, filterQuery) {
        visibleFilePanelEntries(
            filteredFiles = sortedFilteredFiles,
            selectedFiles = selectedFiles,
            showSelectedOnly = showSelectedOnly,
            query = filterQuery,
        )
    }
    val selectedTotalSizeLabel = remember(selectedFiles) {
        fileSelectionTotalSizeBytes(selectedFiles).toFileSizeString()
    }
    val filePanelOverview = remember(visibleFiles) {
        buildFilePanelOverview(visibleFiles)
    }
    val visibleSelectableFiles = remember(visibleFiles) {
        visibleFiles.filterNot { it.isDirectory }
    }
    val visibleSelectableSizeLabel = remember(visibleSelectableFiles) {
        fileSelectionTotalSizeBytes(visibleSelectableFiles).toFileSizeString()
    }
    val selectedVisibleCount = remember(visibleSelectableFiles, selectedPathSet) {
        visibleSelectableFiles.count { it.path in selectedPathSet }
    }
    val shouldShowSelectionActionBar = remember(selectedFiles.size, visibleSelectableFiles.size) {
        shouldShowFileSelectionActionBar(
            selectedCount = selectedFiles.size,
            visibleFileCount = visibleSelectableFiles.size,
        )
    }
    val selectionStatusLabel = remember(
        selectedFiles.size,
        selectedVisibleCount,
        visibleSelectableFiles.size,
        visibleSelectableSizeLabel,
        selectedTotalSizeLabel,
        showSelectedOnly,
        filterQuery,
    ) {
        fileSelectionStatusLabel(
            selectedCount = selectedFiles.size,
            selectedVisibleCount = selectedVisibleCount,
            visibleFileCount = visibleSelectableFiles.size,
            selectedSizeLabel = selectedTotalSizeLabel,
            showSelectedOnly = showSelectedOnly,
            hasFilterQuery = filterQuery.isNotBlank(),
            visibleSizeLabel = visibleSelectableSizeLabel,
        )
    }
    val filterSummary = remember(
        files.size,
        filteredFiles.size,
        selectedFiles.size,
        visibleFiles.size,
        showSelectedOnly,
        filterQuery,
    ) {
        val summaryTotalCount = if (showSelectedOnly) selectedFiles.size else files.size
        val summaryFilteredCount = if (showSelectedOnly) visibleFiles.size else filteredFiles.size
        filePanelFilterSummary(
            totalCount = summaryTotalCount,
            filteredCount = summaryFilteredCount,
            query = filterQuery,
            scopeLabel = if (showSelectedOnly) "已选" else "",
        )
    }
    val emptyStateMessage = remember(filterQuery) {
        filePanelEmptyStateMessage(filterQuery)
    }
    val shouldShowEmptyClearFilter = remember(filterQuery, visibleFiles.size) {
        shouldShowFilePanelClearFilterAction(
            query = filterQuery,
            visibleCount = visibleFiles.size,
        )
    }

    LaunchedEffect(browseDir, filterQuery, sortMode) {
        fileListState.scrollToItem(0)
    }

    LaunchedEffect(files) {
        val validPaths = files.asSequence()
            .filterNot { it.isDirectory }
            .map { it.path }
            .toSet()
        selectedFilePaths = selectedFilePaths.filter { it in validPaths }
    }

    LaunchedEffect(selectedFilePaths) {
        if (selectedFilePaths.isEmpty()) {
            showSelectedOnly = false
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 260.dp, max = 320.dp)
            .fillMaxWidth(0.78f)
            .statusBarsPadding()
            .padding(bottom = 80.dp),  // avoid input bar overlap
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        // 必须完全不透明：半透明背景会让底下的聊天文字透出来形成鬼影（OLED 暗色下尤其明显）
        color = nc.material.surface,
        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
        border = BorderStroke(1.dp, nc.material.primary.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Header: title + prominent close button
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("📂 文件", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = nc.material.primary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                // Prominent close button — avoid swipe-back conflict
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, nc.material.primary.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = nc.material.primary),
                ) {
                    Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("关闭", fontSize = 12.sp)
                }
            }
            // Breadcrumb
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).background(
                nc.material.primary.copy(alpha = 0.07f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // Up button — always visible, disabled at root
                IconButton(onClick = onNavigateUp, enabled = browseDir != ".",
                    modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, "上级目录", modifier = Modifier.size(20.dp),
                        tint = if (browseDir != ".") nc.material.primary else nc.material.onSurfaceVariant.copy(alpha = 0.3f))
                }
                Text("files/", style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    color = nc.material.primary))
                Text(browseDir, style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = nc.material.onSurfaceVariant),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(15.dp),
                        tint = nc.material.onSurfaceVariant)
                }
            }
            FilePanelSearchField(
                query = filterQuery,
                resultSummary = filterSummary,
                onQueryChange = { filterQuery = it },
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (visibleFiles.isNotEmpty()) {
                FilePanelOverviewBar(
                    overview = filePanelOverview,
                    totalSizeLabel = filePanelOverview.totalFileSizeBytes.toFileSizeString(),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                FilePanelSortBar(
                    selectedMode = sortMode,
                    onModeSelected = { sortMode = it },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (shouldShowSelectionActionBar) {
                FileSelectionActionBar(
                    selectedCount = selectedFiles.size,
                    selectedVisibleCount = selectedVisibleCount,
                    statusLabel = selectionStatusLabel,
                    visibleFileCount = visibleSelectableFiles.size,
                    allVisibleFileCount = allVisibleSelectableFileCount,
                    showSelectedOnly = showSelectedOnly,
                    hasFilterQuery = filterQuery.isNotBlank(),
                    onSelectVisible = {
                        selectedFilePaths = selectVisibleFilePaths(
                            selectedPaths = selectedFilePaths,
                            visibleFiles = visibleFiles,
                        )
                    },
                    onUnselectVisible = {
                        selectedFilePaths = unselectVisibleFilePaths(
                            selectedPaths = selectedFilePaths,
                            visibleFiles = visibleFiles,
                        )
                    },
                    onUnselectHidden = {
                        selectedFilePaths = unselectHiddenFilePaths(
                            selectedPaths = selectedFilePaths,
                            visibleFiles = visibleFiles,
                        )
                    },
                    onShowSelectedOnlyChange = { showSelectedOnly = it },
                    onReferenceVisibleFiles = {
                        onReferenceFiles(visibleSelectableFiles, true, 0)
                    },
                    onReferenceVisible = {
                        val matchedSelectedFiles = visibleSelectableFiles
                            .filter { it.path in selectedPathSet }
                        val remainingSelectedPaths = removeReferencedFilePaths(
                            selectedPaths = selectedFilePaths,
                            referencedFiles = matchedSelectedFiles,
                        )
                        onReferenceFiles(
                            matchedSelectedFiles,
                            shouldClosePanelAfterMatchedReference(remainingSelectedPaths.size),
                            remainingSelectedPaths.size,
                        )
                        selectedFilePaths = remainingSelectedPaths
                        filterQuery = filterQueryAfterMatchedReference(
                            remainingSelectedCount = remainingSelectedPaths.size,
                            currentQuery = filterQuery,
                        )
                    },
                    onReferenceSelected = {
                        onReferenceFiles(selectedFiles, true, 0)
                        selectedFilePaths = emptyList()
                    },
                    onClearSelection = { selectedFilePaths = emptyList() },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            // File list
            if (files.isEmpty()) {
                Text("  空目录", style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace),
                    color = nc.material.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp))
            } else if (visibleFiles.isEmpty()) {
                FilePanelEmptyState(
                    message = emptyStateMessage,
                    showClearFilter = shouldShowEmptyClearFilter,
                    clearFilterContentDescription = filePanelClearFilterDescription(filterSummary),
                    onClearFilter = { filterQuery = "" },
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(state = fileListState, modifier = Modifier.fillMaxSize()) {
                        items(visibleFiles, key = { it.path }) { file ->
                            FileRow(
                                file = file, projectRoot = projectRoot, context = context,
                                isSelected = file.path in selectedPathSet,
                                onClick = {
                                    if (file.isDirectory) onNavigateTo(file.name)
                                    else {
                                        android.util.Log.e("NuclearBoy", "[ChatScreen] preview file: ${file.name}")
                                        previewContent = null
                                        previewFile = file
                                        showPreview = true
                                    }
                                },
                                onReference = { onReferenceFile(file) },
                                onSelectionToggle = {
                                    selectedFilePaths = toggleSelectedFilePath(
                                        selectedPaths = selectedFilePaths,
                                        filePath = file.path,
                                    )
                                },
                            )
                        }
                    }
                    // Scroll indicator on the right edge
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(3.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(2.dp))
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════
            //  File preview dialog
            // ═══════════════════════════════════════════════════════════
            if (showPreview && previewFile != null) {
                // Load text content for previewable files
                LaunchedEffect(previewFile) {
                    val file = previewFile ?: return@LaunchedEffect
                    val ext = file.extension.lowercase()
                    val textExtensions = setOf("md", "txt", "py", "kt", "java", "js", "ts",
                        "json", "xml", "yaml", "yml", "gradle", "properties", "csv",
                        "html", "css", "sh", "bat", "ps1", "sql", "toml", "cfg", "ini", "log")
                    if (ext in textExtensions) {
                        // 切到 IO 线程读文件，避免阻塞 UI
                        previewContent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val MAX_PREVIEW_BYTES = 102_400L
                            fun readCapped(f: java.io.File): String {
                                if (!f.exists()) return ""
                                return if (f.length() > MAX_PREVIEW_BYTES) {
                                    val buf = ByteArray(MAX_PREVIEW_BYTES.toInt())
                                    val read = f.inputStream().use { it.read(buf) }
                                    buf.copyOf(read).toString(Charsets.UTF_8) + "\n…（文件过大，仅显示前 100KB）"
                                } else {
                                    f.readText()
                                }
                            }
                            try {
                                readCapped(java.io.File(file.path))
                                    .takeIf { it.isNotEmpty() }
                            } catch (e: Exception) {
                                try {
                                    readCapped(java.io.File("${projectRoot}/${file.path}"))
                                        .takeIf { it.isNotEmpty() }
                                } catch (e2: Exception) {
                                    android.util.Log.e("NuclearBoy", "[ChatScreen] preview read error: ${e2.message}")
                                    null
                                }
                            }
                        }
                    }
                }

                AlertDialog(
                    onDismissRequest = {
                        showPreview = false
                        previewContent = null
                    },
                    title = {
                        Text(
                            previewFile!!.name,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            color = nc.material.onSurface
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            if (previewContent != null) {
                                SelectionContainer {
                                    Text(
                                        text = previewContent!!,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = nc.material.onSurface,
                                        modifier = Modifier.verticalScroll(rememberScrollState())
                                    )
                                }
                            } else {
                                // Binary file or preview failed — show metadata
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("📄", fontSize = 32.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "无法预览此文件类型",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = nc.material.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "${previewFile!!.extension.uppercase()} · ${previewFile!!.size.toFileSizeString()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = nc.material.onSurfaceVariant.copy(alpha = 0.6f),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FileReferenceTextButton(
                                onClick = {
                                    previewFile?.let(onReferenceFile)
                                    showPreview = false
                                    previewContent = null
                                },
                            )
                            Button(
                                onClick = { shareFile(context, previewFile!!.path) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = nc.material.primary
                                )
                            ) {
                                Icon(Icons.Default.Share, "分享", modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("分享")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showPreview = false
                            previewContent = null
                        }) {
                            Text("关闭")
                        }
                    },
                    containerColor = nc.material.surface,
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 6.dp,
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileInfo, projectRoot: String, context: Context,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onReference: () -> Unit = {},
    onSelectionToggle: () -> Unit = {},
) {
    val nc = NuclearBoyTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon: folder or file type
        Text(
            text = if (file.isDirectory) "📁" else extIcon(file.extension),
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(file.name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            color = if (file.isDirectory) nc.material.primary else nc.material.onSurface)
        Text(file.size.toFileSizeString(), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = nc.material.onSurfaceVariant.copy(alpha = 0.6f))
        if (!file.isDirectory) {
            Spacer(Modifier.width(4.dp))
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectionToggle() },
                modifier = Modifier.size(26.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = nc.material.primary,
                    uncheckedColor = nc.material.onSurfaceVariant.copy(alpha = 0.65f),
                    checkmarkColor = nc.material.onPrimary,
                ),
            )
            Spacer(Modifier.width(2.dp))
            FileReferenceIconButton(onClick = onReference)
        }
    }
}

private fun extIcon(ext: String): String = when (ext.lowercase()) {
    "py" -> "🐍"; "kt", "java" -> "☕"; "js", "ts" -> "📜"; "json" -> "📋"
    "xml", "yaml", "yml" -> "⚙️"; "md", "txt" -> "📝"; "docx" -> "📄"
    "xlsx" -> "📊"; "pptx" -> "📽️"; "pdf" -> "📕"; "png", "jpg", "jpeg", "gif" -> "🖼️"
    "zip", "tar", "gz" -> "📦"; "html", "css" -> "🌐"
    else -> "📄"
}

// ═══════════════════════════════════════════════════════════════════════
//  Empty State — Terminal/Hacker aesthetic
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyChatView(modifier: Modifier = Modifier, onSuggestionClick: (String) -> Unit = {}) {
    val nc = NuclearBoyTheme.colorScheme
    val greeting = LocalTime.now().toGreeting()

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ASCII-art style logo
        Box(
            modifier = Modifier.size(100.dp).clip(RoundedCornerShape(16.dp))
                .background(nc.material.surface)
                .border(2.dp, nc.material.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = ">_",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = nc.material.primary,
                    ),
                )
                Text(
                    text = "核弹",
                    fontSize = 14.sp, fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
                    color = nc.material.onSurface,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = "NUCLEAR BOY",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp, color = nc.material.primary,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "核 弹 男 孩",
            fontSize = 11.sp, fontFamily = FontFamily.Default,
            letterSpacing = 8.sp, color = nc.material.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // Typing animation prompt
        val prompt = "guest@nuclear-boy:~$ _"
        TypingPrompt(text = prompt, color = nc.material.primary)

        Spacer(Modifier.height(8.dp))

        Text(
            text = "$greeting。你的 AI 编程终端已就绪",
            style = MaterialTheme.typography.bodyMedium.copy(color = nc.material.onSurface, lineHeight = 22.sp),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "写代码 · 读文件 · 执行脚本 · 生成文档",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = nc.material.onSurfaceVariant, letterSpacing = 1.sp,
        )

        Spacer(Modifier.height(28.dp))

        // Command suggestions — terminal style
        val suggestions = listOf(
            "> 创建一个 Python 项目，写个计算器",
            "> 分析并修复这段代码的问题",
            "> 生成本周工作总结的 Excel 表格",
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { cmd ->
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(nc.material.surface)
                        .border(2.dp, nc.material.outline.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .clickable { onSuggestionClick(cmd.removePrefix("> ")) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = cmd,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = nc.material.onSurface.copy(alpha = 0.85f),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeItem(emoji: String, title: String, desc: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(emoji, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF00E676))
            Text(desc, fontSize = 11.sp, color = Color(0xFF838896))
        }
    }
}

@Composable
private fun TopModelSelector(
    state: ApiKeyManager.ApiKeyState,
    onSelect: (String) -> Unit,
) {
    val nc = NuclearBoyTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(nc.material.primary.copy(alpha = 0.08f))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (state.customProviderEnabled) Icons.Default.Cloud else Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = nc.material.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = state.activeModelLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = nc.material.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 112.dp),
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "选择模型",
                tint = nc.material.primary.copy(alpha = 0.65f),
                modifier = Modifier.size(15.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = nc.material.surface,
        ) {
            DropdownMenuItem(
                text = {
                    ModelMenuText(
                        title = "DeepSeek 官方",
                        subtitle = "官方 API · 支持聊天/思考/专家模式",
                    )
                },
                onClick = {
                    onSelect(ApiKeyManager.OFFICIAL_MODEL_ID)
                    expanded = false
                },
                leadingIcon = if (state.activeModelId == ApiKeyManager.OFFICIAL_MODEL_ID) {
                    { Icon(Icons.Default.Check, contentDescription = "选中", tint = nc.material.primary) }
                } else null,
            )
            state.customModels.forEach { model ->
                DropdownMenuItem(
                    text = {
                        ModelMenuText(
                            title = model.displayName,
                            subtitle = "${model.protocol.displayName} · ${model.modelName}",
                        )
                    },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                    leadingIcon = if (state.activeModelId == model.id) {
                        { Icon(Icons.Default.Check, contentDescription = "选中", tint = nc.material.primary) }
                    } else null,
                )
            }
            if (state.customModels.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "到设置里添加第三方模型",
                            fontSize = 12.sp,
                            color = nc.material.onSurfaceVariant,
                        )
                    },
                    onClick = { expanded = false },
                    enabled = false,
                )
            }
        }
    }
}

@Composable
private fun ModelMenuText(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, fontSize = 10.sp, color = NuclearBoyTheme.colorScheme.material.onSurfaceVariant)
    }
}

@Composable
private fun TypingPrompt(text: String, color: Color) {
    var visibleChars by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        for (i in 1..text.length) { visibleChars = i; delay(if (i == text.length) 2000 else 80) }
        while (true) {
            // Blink cursor
            visibleChars = text.length
            delay(500)
            visibleChars = text.length + 1 // Show cursor
            delay(500)
        }
    }
    val display = if (visibleChars > text.length) text else text.take(visibleChars)
    Text(
        text = "$display${if (visibleChars > text.length) "_" else ""}",
        fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = color.copy(alpha = 0.8f),
    )
}

/**
 * Wraps the input bar and reports its "resting" height (i.e. with the IME's own
 * contribution subtracted out) via [onRestHeightMeasured].
 *
 * Isolated into its own composable so that subscribing to [WindowInsets.ime] (which
 * changes on every frame of the keyboard show/hide animation) only forces *this small
 * wrapper* to recompose each frame — not the much larger screen content around it
 * (message list, etc.), which only needs the rare, settled rest-height value.
 */
@Composable
private fun ImeAwareInputBarSlot(
    modifier: Modifier = Modifier,
    onRestHeightMeasured: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    Box(
        modifier = modifier.onGloballyPositioned { coords ->
            val restHeight = coords.size.height - imeBottomPx
            if (restHeight > 0) onRestHeightMeasured(restHeight)
        },
    ) {
        content()
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Input Bar — Terminal prompt style
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isProcessing: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit,
    fileCount: Int = 0,
    hasMessages: Boolean = false,
    focusRequest: Long = 0L,
    onAttachFile: (() -> Unit)? = null,
    placeholder: String = "输入指令…",
    showToolActionDraftHint: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val nc = NuclearBoyTheme.colorScheme
    val toolActionDraftHint = remember(text, showToolActionDraftHint) {
        if (showToolActionDraftHint) detectToolActionDraftHint(text) else null
    }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 0.dp,
        // 完全不透明：消息列表从输入栏底下滚过时，半透明背景会透出文字鬼影
        color = nc.material.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        border = BorderStroke(2.dp, nc.material.outline.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            CommandShortcutBar(
                isProcessing = isProcessing,
                hasMessages = hasMessages,
                onCommandSelected = { command ->
                    if (command.submitImmediately) {
                        onTextChange("")
                        focusManager.clearFocus()
                        // Guard against commands with empty commandText bypassing the
                        // canSend check and dispatching a blank message (finding 15).
                        if (command.commandText == "/stop") onCancel()
                        else if (command.commandText.isNotBlank()) onSend(command.commandText)
                    } else {
                        onTextChange(command.commandText)
                        focusRequester.requestFocus()
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            // Attach file button
            if (onAttachFile != null && !isProcessing) {
                IconButton(onClick = onAttachFile, modifier = Modifier.size(32.dp)) {
                    BadgedBox(
                        badge = {
                            if (fileCount > 0) {
                                Badge(containerColor = nc.material.primary, contentColor = Color.Black) {
                                    Text(
                                        fileCount.coerceAtMost(99).toString(),
                                        fontSize = 8.sp,
                                    )
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.AttachFile, "添加附件", modifier = Modifier.size(18.dp),
                            tint = nc.material.onSurfaceVariant)
                    }
                }
            }

            // Terminal prompt
            Text(">", fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                color = if (isProcessing) nc.warning else nc.material.primary,
                modifier = Modifier.padding(start = 2.dp))

            OutlinedTextField(
                value = text, onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = "聊天输入框" },
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    color = nc.material.onSurfaceVariant.copy(alpha = 0.4f))) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = nc.material.onSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                    cursorColor = nc.material.primary),
                shape = RoundedCornerShape(0.dp), maxLines = 4, singleLine = false,
            )

            if (isProcessing) {
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(nc.material.errorContainer)) {
                    Icon(Icons.Filled.Close, "停止", modifier = Modifier.size(18.dp), tint = nc.material.error)
                }
            } else {
                val canSend = text.isNotBlank()
                IconButton(
                    onClick = {
                        if (canSend) {
                            android.util.Log.e("NuclearBoy", "[ChatScreen] sendButton clicked textLen=${text.length}")
                            onSend(text); onTextChange(""); focusManager.clearFocus()
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .semantics {
                            contentDescription = if (canSend) "发送消息" else "发送消息不可用"
                        }
                        .background(if (canSend) nc.material.primary else Color.Transparent)
                        .border(2.dp, if (canSend) nc.material.primary
                            else nc.material.outline.copy(alpha = 0.3f), CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", modifier = Modifier.size(16.dp),
                        tint = if (canSend) Color.Black else nc.material.onSurfaceVariant.copy(alpha = 0.3f))
                }
            }
            }
            toolActionDraftHint?.let { hint ->
                Spacer(Modifier.height(4.dp))
                ToolActionDraftHintBar(
                    hint = hint,
                    onAppendGuard = {
                        onTextChange(appendToolRealityGuard(text))
                        focusRequester.requestFocus()
                    },
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("NUCLEAR BOY", text))
}

private fun shareFile(context: Context, path: String) {
    try {
        val file = java.io.File(path)
        android.util.Log.e("NuclearBoy", "[ChatScreen] shareFile() path=$path exists=${file.exists()}")
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file)
        val mime = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt", "py", "kt", "java", "js", "ts", "json", "xml", "yaml", "yml", "md" -> "text/plain"
            "png", "jpg", "jpeg", "gif", "webp" -> "image/*"
            "mp4", "avi", "mkv" -> "video/*"
            "mp3", "wav", "ogg" -> "audio/*"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享: ${file.name}"))
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun copyAttachedFile(context: Context, uri: Uri, viewModel: ChatViewModel) {
    try {
        val cr = context.contentResolver
        var fileName = "attachment"
        cr.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) fileName = cursor.getString(idx)
            }
        }
        val target = java.io.File(viewModel.getProjectRoot(), fileName)
        android.util.Log.e("NuclearBoy", "[ChatScreen] copyAttachedFile() fileName=$fileName target=${target.absolutePath}")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cr.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        viewModel.refreshProjectFiles(viewModel.browseDir.value)
        android.util.Log.e("NuclearBoy", "[ChatScreen] copyAttachedFile() success: $fileName size=${target.length()}")
        android.widget.Toast.makeText(context, "已添加: $fileName", android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.util.Log.e("NuclearBoy", "[ChatScreen] copyAttachedFile() error: ${e.message}", e)
        android.widget.Toast.makeText(context, "添加失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
