package com.nuclearboy.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.nuclearboy.api.deepseek.ApiKeyManager
import com.nuclearboy.api.deepseek.DeepSeekApiClient
import com.nuclearboy.api.deepseek.ProviderEndpointMode
import com.nuclearboy.api.deepseek.ProviderFormalChatTestResult
import com.nuclearboy.api.deepseek.ProviderProtocol
import com.nuclearboy.api.deepseek.ProviderTestResult
import com.nuclearboy.api.deepseek.sanitizeProviderBaseUrl
import com.nuclearboy.api.deepseek.sanitizeProviderModelName
import com.nuclearboy.app.R
import com.nuclearboy.app.diagnostics.AppDiagnostics
import com.nuclearboy.app.diagnostics.DiagnosticResult
import com.nuclearboy.app.diagnostics.DiagnosticStatus
import com.nuclearboy.app.ui.settings.parts.DiagnosticsCopyItem
import com.nuclearboy.app.ui.settings.parts.apiKeyFingerprintSummary
import com.nuclearboy.app.ui.settings.parts.fullDiagnosticsCopySummary
import com.nuclearboy.app.ui.settings.parts.modelNameCleanupSummary
import com.nuclearboy.app.ui.settings.parts.modelTestFailureActionHint
import com.nuclearboy.app.ui.settings.parts.modelTestCopySummary
import com.nuclearboy.app.ui.settings.parts.modelTestFailureMessage
import com.nuclearboy.app.ui.settings.parts.modelTestRequestContextSummary
import com.nuclearboy.app.ui.settings.parts.modelTestTroubleshootingBundle
import com.nuclearboy.app.ui.settings.parts.providerDisplayNameSuggestion
import com.nuclearboy.app.ui.settings.parts.providerBaseUrlCleanupSummary
import com.nuclearboy.app.ui.settings.parts.providerExactEndpointCompletionActionLabel
import com.nuclearboy.app.ui.settings.parts.providerExactEndpointRecoveryActionLabel
import com.nuclearboy.app.ui.settings.parts.providerExactEndpointWarning
import com.nuclearboy.app.ui.settings.parts.providerEndpointPreviewSummary
import com.nuclearboy.app.ui.settings.parts.providerFormalChatCurlTemplate
import com.nuclearboy.app.ui.settings.parts.providerModelListClearFilterActionLabel
import com.nuclearboy.app.ui.settings.parts.providerModelListCurlTemplate
import com.nuclearboy.app.ui.settings.parts.providerModelListPickerHint
import com.nuclearboy.app.ui.settings.parts.providerModelListSummary
import com.nuclearboy.app.ui.settings.parts.providerModelListVisibleModels
import com.nuclearboy.app.ui.settings.parts.providerModelRouteHint
import com.nuclearboy.app.ui.settings.parts.providerModelRouteSuggestedModel
import com.nuclearboy.app.ui.settings.parts.providerRequestCurlTemplate
import com.nuclearboy.remotepc.PcBridgeClient
import com.nuclearboy.remotepc.PcBridgeConfigStore
import com.nuclearboy.app.update.UpdateDownloader
import com.nuclearboy.app.update.UpdateManager
import com.nuclearboy.common.AppResult
import com.nuclearboy.common.ModelTier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.widget.Toast
import javax.inject.Inject

data class ModelTestUiState(
    val targetId: String? = null,
    val inProgress: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
    val detail: String = "",
    val requestTemplate: String = "",
)

data class ModelListProbeUiState(
    val inProgress: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
    val detail: String = "",
    val endpoint: String = "",
    val modelIds: List<String> = emptyList(),
)

data class FullDiagnosticsUiState(
    val running: Boolean = false,
    val results: List<DiagnosticResult> = emptyList(),
)

data class PcBridgeTestUiState(
    val inProgress: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val apiKeyManager: ApiKeyManager,
    private val apiClient: DeepSeekApiClient,
    private val appDiagnostics: AppDiagnostics,
    private val pcBridgeConfigStore: PcBridgeConfigStore,
    private val pcBridgeClient: PcBridgeClient,
    private val appSettings: com.nuclearboy.common.AppSettingsStore,
) : androidx.lifecycle.ViewModel() {

    private val _customInstructions = MutableStateFlow(appSettings.customInstructions())
    val customInstructions: StateFlow<String> = _customInstructions.asStateFlow()
    fun saveCustomInstructions(text: String) {
        appSettings.setCustomInstructions(text)
        _customInstructions.value = text.trim()
    }

    val apiKeyState = apiKeyManager.state
    private val _modelTestState = MutableStateFlow(ModelTestUiState())
    val modelTestState: StateFlow<ModelTestUiState> = _modelTestState.asStateFlow()
    private val _modelListProbeState = MutableStateFlow(ModelListProbeUiState())
    val modelListProbeState: StateFlow<ModelListProbeUiState> = _modelListProbeState.asStateFlow()
    private val _fullDiagnosticsState = MutableStateFlow(FullDiagnosticsUiState())
    val fullDiagnosticsState: StateFlow<FullDiagnosticsUiState> = _fullDiagnosticsState.asStateFlow()
    val pcBridgeState = pcBridgeConfigStore.state
    private val _pcBridgeTestState = MutableStateFlow(PcBridgeTestUiState())
    val pcBridgeTestState: StateFlow<PcBridgeTestUiState> = _pcBridgeTestState.asStateFlow()

    fun setPcBridgeEnabled(enabled: Boolean) {
        pcBridgeConfigStore.setEnabled(enabled)
    }

    fun setPcEncryption(enabled: Boolean) {
        pcBridgeConfigStore.setEncryptionEnabled(enabled)
    }

    /** token 传 null 表示保留已存的不变 */
    fun savePcBridgeConnection(url: String, token: String?) {
        pcBridgeConfigStore.saveConnection(url, token)
        _pcBridgeTestState.value = PcBridgeTestUiState(message = "已保存，点测试连接试试 ✨")
    }

    private val _pcTasksState = MutableStateFlow<List<PcBridgeClient.RunningTask>?>(null)
    val pcTasksState: StateFlow<List<PcBridgeClient.RunningTask>?> = _pcTasksState.asStateFlow()

    fun loadPcTasks() {
        viewModelScope.launch {
            _pcTasksState.value = when (val result = pcBridgeClient.listRunningTasks()) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> {
                    _pcBridgeTestState.value = PcBridgeTestUiState(
                        success = false,
                        message = result.technicalDetail ?: result.error.humanMessage,
                    )
                    null
                }
            }
        }
    }

    fun testPcBridgeConnection(url: String, token: String?) {
        _pcBridgeTestState.value = PcBridgeTestUiState(inProgress = true)
        viewModelScope.launch {
            val result = pcBridgeClient.testConnection(
                url = url.takeIf { it.isNotBlank() },
                token = token?.takeIf { it.isNotBlank() },
            )
            _pcBridgeTestState.value = when (result) {
                is AppResult.Success -> {
                    val clis = result.data.clis.entries
                        .joinToString("，") { "${it.key} ${it.value}" }
                    PcBridgeTestUiState(success = true, message = "连上了 ${result.data.host} ✨ 可用：$clis")
                }
                is AppResult.Failure -> PcBridgeTestUiState(
                    success = false,
                    message = result.technicalDetail ?: result.error.humanMessage,
                )
            }
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            apiKeyManager.setPrimaryKey(key)
            // Trigger balance check
            // apiClient.checkBalance(key) -- needs a client instance
        }
    }

    fun removeApiKey() {
        viewModelScope.launch { apiKeyManager.removeKey(isPrimary = true) }
    }

    fun setAutoSwitch(enabled: Boolean) { apiKeyManager.setAutoSwitch(enabled) }
    fun setSimpleTasksUseFlash(enabled: Boolean) { apiKeyManager.setSimpleTasksUseFlash(enabled) }

    fun setCustomProviderEnabled(enabled: Boolean) { apiKeyManager.setCustomProviderEnabled(enabled) }

    /** key 传 null 表示保留已存的 Key 不变 */
    fun saveCustomProviderConfig(baseUrl: String, model: String, key: String?) {
        apiKeyManager.setCustomProviderConfig(baseUrl, model, key)
    }

    fun saveCustomModel(
        displayName: String,
        baseUrl: String,
        model: String,
        protocol: ProviderProtocol,
        endpointMode: ProviderEndpointMode,
        key: String?,
    ) {
        apiKeyManager.saveCustomModel(
            existingId = null,
            displayName = displayName,
            baseUrl = baseUrl,
            modelName = model,
            protocol = protocol,
            endpointMode = endpointMode,
            apiKey = key,
            selectAfterSave = true,
        )
    }

    fun selectModel(modelId: String) { apiKeyManager.selectModel(modelId) }

    fun deleteCustomModel(modelId: String) { apiKeyManager.deleteCustomModel(modelId) }

    fun testCustomModelInput(
        baseUrl: String,
        model: String,
        protocol: ProviderProtocol,
        endpointMode: ProviderEndpointMode,
        key: String?,
    ) {
        runCustomModelTest(
            targetId = NEW_MODEL_TEST_ID,
            baseUrl = baseUrl,
            model = model,
            protocol = protocol,
            endpointMode = endpointMode,
            key = key,
            label = model.ifBlank { "未命名模型" },
        )
    }

    fun probeCustomModelList(
        baseUrl: String,
        model: String,
        protocol: ProviderProtocol,
        endpointMode: ProviderEndpointMode,
        key: String?,
    ) {
        val normalizedBaseUrl = sanitizeProviderBaseUrl(baseUrl)
        val normalizedModel = sanitizeProviderModelName(model)
        if (normalizedBaseUrl.isBlank()) {
            _modelListProbeState.value = ModelListProbeUiState(
                success = false,
                message = "请先填写服务地址",
                detail = "服务地址示例：http://your-gateway:20128/v1",
            )
            return
        }
        val resolvedProtocol = ProviderProtocol.resolve(protocol, normalizedBaseUrl, normalizedModel)
        if (resolvedProtocol == ProviderProtocol.ANTHROPIC) {
            _modelListProbeState.value = ModelListProbeUiState(
                success = false,
                message = "当前协议不支持模型列表探测",
                detail = "模型列表探测使用 OpenAI 兼容的 GET /v1/models。Anthropic 兼容网关请在网关后台复制模型名。",
            )
            return
        }
        val endpoint = DeepSeekApiClient.buildOpenAiModelsEndpoint(normalizedBaseUrl, endpointMode)
        _modelListProbeState.value = ModelListProbeUiState(
            inProgress = true,
            message = "正在获取模型列表",
            endpoint = endpoint,
            detail = "会请求 GET $endpoint，只展示模型 id，不输出明文 Key。",
        )
        viewModelScope.launch {
            _modelListProbeState.value = when (val result = apiClient.listOpenAiProviderModels(
                baseUrl = normalizedBaseUrl,
                apiKey = key,
                endpointMode = endpointMode,
            )) {
                is AppResult.Success -> ModelListProbeUiState(
                    success = true,
                    message = "发现 ${result.data.modelIds.size} 个模型",
                    endpoint = result.data.endpoint,
                    modelIds = result.data.modelIds,
                    detail = providerModelListSummary(
                        endpoint = result.data.endpoint,
                        modelIds = result.data.modelIds,
                        latencyMs = result.data.latencyMs,
                    ),
                )
                is AppResult.Failure -> ModelListProbeUiState(
                    success = false,
                    message = "模型列表获取失败",
                    endpoint = endpoint,
                    detail = result.technicalDetail ?: "没有更多错误细节。",
                )
            }
        }
    }

    fun testSavedCustomModel(modelId: String) {
        val config = apiKeyManager.getCustomModelConfig(modelId)
        if (config == null) {
            _modelTestState.value = ModelTestUiState(
                targetId = modelId,
                success = false,
                message = "找不到这个模型配置",
                detail = "它可能已经被删除，请刷新设置页后重试。",
            )
            return
        }
        runCustomModelTest(
            targetId = modelId,
            baseUrl = config.baseUrl,
            model = config.modelName,
            protocol = config.protocol,
            endpointMode = config.endpointMode,
            key = config.apiKey,
            label = config.displayName,
        )
    }

    private fun providerProbeRequestBundle(
        protocolLabel: String,
        endpoint: String,
        modelName: String,
        hasApiKey: Boolean,
    ): String {
        val lightweightTemplate = providerRequestCurlTemplate(
            protocolLabel = protocolLabel,
            endpoint = endpoint,
            modelName = modelName,
            hasApiKey = hasApiKey,
        )
        val formalTemplate = providerFormalChatCurlTemplate(
            protocolLabel = protocolLabel,
            endpoint = endpoint,
            modelName = modelName,
            hasApiKey = hasApiKey,
        )
        return buildString {
            if (lightweightTemplate.isNotBlank()) {
                append("轻量 ping 请求：")
                append('\n')
                append(lightweightTemplate)
            }
            if (formalTemplate.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("正式 stream 聊天请求：")
                append('\n')
                append(formalTemplate)
            }
        }
    }

    private fun lightweightProbeSummary(result: ProviderTestResult): String =
        buildString {
            append("轻量 ping：")
            append(result.latencyMs)
            append(" ms")
            if (result.replyPreview.isNotBlank()) {
                append("\n轻量响应：")
                append(result.replyPreview)
            }
        }

    private fun formalChatProbeSummary(result: ProviderFormalChatTestResult): String =
        buildString {
            append("正式聊天：stream=")
            append(result.stream)
            append(" · 工具定义=")
            append(if (result.toolsRequested) "已携带" else "兼容模式未携带")
            append(" · ")
            append(result.latencyMs)
            append(" ms")
            append("\nSSE：")
            append(result.lineCount)
            append(" 行 · 内容=")
            append(if (result.sawContent) "有" else "无")
            append(" · 工具事件=")
            append(if (result.sawToolEvent) "有" else "无")
            if (result.compatibilityRetry) {
                append("\n提示：网关不接受工具定义，已按正式聊天逻辑降级为兼容聊天模式；普通聊天可用，但工具调用能力可能受限。")
            }
            if (result.replyPreview.isNotBlank()) {
                append("\n正式响应：")
                append(result.replyPreview)
            }
        }

    private fun runCustomModelTest(
        targetId: String,
        baseUrl: String,
        model: String,
        protocol: ProviderProtocol,
        endpointMode: ProviderEndpointMode,
        key: String?,
        label: String,
    ) {
        val rawBaseUrl = baseUrl.trim()
        val normalizedBaseUrl = sanitizeProviderBaseUrl(baseUrl)
        val trimmedModel = model.trim()
        val normalizedModel = sanitizeProviderModelName(model)
        if (normalizedBaseUrl.isBlank() || normalizedModel.isBlank()) {
            _modelTestState.value = ModelTestUiState(
                targetId = targetId,
                success = false,
                message = "请先填写服务地址和模型名",
                detail = "服务地址示例：http://your-gateway:20128/v1；模型名示例：nvidia/minimaxai/minimax-m2.7",
            )
            return
        }
        val keyFingerprint = apiKeyFingerprintSummary(key)
        val baseUrlCleanupSummary = providerBaseUrlCleanupSummary(rawBaseUrl, normalizedBaseUrl)
        val cleanupSummary = modelNameCleanupSummary(trimmedModel, normalizedModel)
        val resolvedProtocol = ProviderProtocol.resolve(protocol, normalizedBaseUrl, normalizedModel)
        val requestEndpoint = when (resolvedProtocol) {
            ProviderProtocol.ANTHROPIC ->
                DeepSeekApiClient.buildAnthropicMessagesEndpoint(normalizedBaseUrl, endpointMode)
            else ->
                DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(normalizedBaseUrl, endpointMode)
        }
        val requestContext = modelTestRequestContextSummary(
            endpoint = requestEndpoint,
            modelName = normalizedModel,
            protocolLabel = resolvedProtocol.displayName,
            endpointModeLabel = endpointMode.displayName,
            keyFingerprintSummary = keyFingerprint,
        )
        val requestTemplate = providerProbeRequestBundle(
            protocolLabel = resolvedProtocol.displayName,
            endpoint = requestEndpoint,
            modelName = normalizedModel,
            hasApiKey = key.orEmpty().trim().isNotBlank(),
        )
        val testLabel = sanitizeProviderModelName(label).ifBlank { normalizedModel }
        _modelTestState.value = ModelTestUiState(
            targetId = targetId,
            inProgress = true,
            message = "正在测试 $testLabel",
            requestTemplate = requestTemplate,
            detail = buildString {
                append("会先发送 max_tokens=8 的 ping 请求；成功后继续用 stream=true + 工具定义验证正式聊天链路。不会保存或输出明文 Key。")
                if (baseUrlCleanupSummary.isNotBlank()) {
                    append('\n')
                    append(baseUrlCleanupSummary)
                }
                if (cleanupSummary.isNotBlank()) {
                    append('\n')
                    append(cleanupSummary)
                }
                if (requestContext.isNotBlank()) {
                    append('\n')
                    append(requestContext)
                }
            },
        )
        viewModelScope.launch {
            when (val result = apiClient.testCustomProvider(
                baseUrl = normalizedBaseUrl,
                modelName = normalizedModel,
                apiKey = key,
                protocol = protocol,
                endpointMode = endpointMode,
            )) {
                is AppResult.Success -> {
                    val lightweight = result.data
                    val successRequestTemplate = providerProbeRequestBundle(
                        protocolLabel = lightweight.protocol.displayName,
                        endpoint = lightweight.endpoint,
                        modelName = lightweight.modelName,
                        hasApiKey = key.orEmpty().trim().isNotBlank(),
                    )
                    val successContext = modelTestRequestContextSummary(
                        endpoint = lightweight.endpoint,
                        modelName = lightweight.modelName,
                        protocolLabel = lightweight.protocol.displayName,
                        endpointModeLabel = lightweight.endpointMode.displayName,
                        keyFingerprintSummary = keyFingerprint,
                    )
                    _modelTestState.value = ModelTestUiState(
                        targetId = targetId,
                        inProgress = true,
                        message = "轻量连接成功，正在验证正式聊天",
                        requestTemplate = successRequestTemplate,
                        detail = buildString {
                            append(successContext)
                            append('\n')
                            append(lightweightProbeSummary(lightweight))
                            append("\n正在发送 stream=true + 工具定义的正式聊天测试。")
                        },
                    )
                    _modelTestState.value = when (val formal = apiClient.testCustomProviderFormalChat(
                        baseUrl = normalizedBaseUrl,
                        modelName = normalizedModel,
                        apiKey = key,
                        protocol = protocol,
                        endpointMode = endpointMode,
                    )) {
                        is AppResult.Success -> ModelTestUiState(
                            targetId = targetId,
                            success = true,
                            message = "模型正式聊天可用",
                            requestTemplate = providerProbeRequestBundle(
                                protocolLabel = formal.data.protocol.displayName,
                                endpoint = formal.data.endpoint,
                                modelName = formal.data.modelName,
                                hasApiKey = key.orEmpty().trim().isNotBlank(),
                            ),
                            detail = buildString {
                                append(successContext)
                                append('\n')
                                if (baseUrlCleanupSummary.isNotBlank()) {
                                    append(baseUrlCleanupSummary)
                                    append('\n')
                                }
                                if (cleanupSummary.isNotBlank()) {
                                    append(cleanupSummary)
                                    append('\n')
                                }
                                append(lightweightProbeSummary(lightweight))
                                append('\n')
                                append(formalChatProbeSummary(formal.data))
                            },
                        )
                        is AppResult.Failure -> {
                            val actionHint = modelTestFailureActionHint(formal.technicalDetail)
                            ModelTestUiState(
                                targetId = targetId,
                                success = false,
                                message = "轻量连接成功，正式聊天失败",
                                requestTemplate = successRequestTemplate,
                                detail = buildString {
                                    append(successContext)
                                    append('\n')
                                    if (baseUrlCleanupSummary.isNotBlank()) {
                                        append(baseUrlCleanupSummary)
                                        append('\n')
                                    }
                                    if (cleanupSummary.isNotBlank()) {
                                        append(cleanupSummary)
                                        append('\n')
                                    }
                                    append(lightweightProbeSummary(lightweight))
                                    append('\n')
                                    append("正式聊天必须支持 stream=true；当前失败会导致聊天页不可用或空回复。")
                                    if (actionHint.isNotBlank()) {
                                        append('\n')
                                        append(actionHint)
                                    }
                                    append('\n')
                                    append(formal.technicalDetail ?: formal.error.humanMessage)
                                },
                            )
                        }
                    }
                }
                is AppResult.Failure -> {
                    val actionHint = modelTestFailureActionHint(result.technicalDetail)
                    _modelTestState.value = ModelTestUiState(
                        targetId = targetId,
                        success = false,
                        message = modelTestFailureMessage(result.error, result.technicalDetail),
                        requestTemplate = requestTemplate,
                        detail = buildString {
                            if (requestContext.isNotBlank()) {
                                append(requestContext)
                                append('\n')
                            }
                            if (baseUrlCleanupSummary.isNotBlank()) {
                                append(baseUrlCleanupSummary)
                                append('\n')
                            }
                            if (cleanupSummary.isNotBlank()) {
                                append(cleanupSummary)
                                append('\n')
                            }
                            if (actionHint.isNotBlank()) {
                                append(actionHint)
                                append('\n')
                            }
                            append(result.technicalDetail ?: "没有更多错误细节。")
                        },
                    )
                }
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val key = apiKeyManager.getActiveKey() ?: return@launch
            // Validate by making a lightweight API call
            apiKeyManager.updateBalance("测试中…")
        }
    }

    fun runFullDiagnostics() {
        if (_fullDiagnosticsState.value.running) return
        _fullDiagnosticsState.value = _fullDiagnosticsState.value.copy(running = true)
        viewModelScope.launch {
            val results = appDiagnostics.runAll()
            _fullDiagnosticsState.value = FullDiagnosticsUiState(
                running = false,
                results = results,
            )
        }
    }

    companion object {
        const val NEW_MODEL_TEST_ID = "__new_custom_model__"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onMenuClick: () -> Unit = {},
    onNavigateToTutorial: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val composableScope = rememberCoroutineScope()
    val apiKeyState by viewModel.apiKeyState.collectAsState()
    val modelTestState by viewModel.modelTestState.collectAsState()
    val modelListProbeState by viewModel.modelListProbeState.collectAsState()
    val fullDiagnosticsState by viewModel.fullDiagnosticsState.collectAsState()
    val pcBridgeState by viewModel.pcBridgeState.collectAsState()
    val pcBridgeTestState by viewModel.pcBridgeTestState.collectAsState()
    val scrollState = rememberScrollState()
    var showSponsorDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
                actions = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── API Key Section ──────────────────────────
            Text("🔑 API 设置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {

                    // API Key status — 第三方模型激活时不要报红色"未配置"，会和正常聊天状态矛盾
                    val usingCustomProviderOnly = !apiKeyState.hasPrimaryKey && apiKeyState.customProviderEnabled
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (apiKeyState.hasPrimaryKey || usingCustomProviderOnly) Icons.Filled.CheckCircle
                            else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (apiKeyState.hasPrimaryKey || usingCustomProviderOnly) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                apiKeyState.hasPrimaryKey -> "已配置: ${apiKeyState.primaryKeyMasked}"
                                usingCustomProviderOnly -> "正在使用第三方模型，官方 Key 未配置（可选）"
                                else -> "未配置 API Key"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (apiKeyState.balanceCny != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("💰 余额: ${apiKeyState.balanceCny}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(Modifier.height(12.dp))

                    // API Key input
                    var apiKeyInput by remember { mutableStateOf("") }
                    var showKeyInput by remember { mutableStateOf(!apiKeyState.hasPrimaryKey) }

                    if (showKeyInput || !apiKeyState.hasPrimaryKey) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("DeepSeek API Key (sk-v4-...)") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (apiKeyInput.isNotBlank()) {
                                    viewModel.setApiKey(apiKeyInput.trim())
                                    apiKeyInput = ""
                                    showKeyInput = false
                                }
                            }),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (apiKeyInput.isNotBlank()) {
                                    viewModel.setApiKey(apiKeyInput.trim())
                                    apiKeyInput = ""
                                    showKeyInput = false
                                }
                            }, enabled = apiKeyInput.isNotBlank()) {
                                Text("保存")
                            }
                            if (apiKeyState.hasPrimaryKey) {
                                OutlinedButton(onClick = { showKeyInput = false }) {
                                    Text("取消")
                                }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showKeyInput = true }) { Text("更换 Key") }
                            OutlinedButton(onClick = { viewModel.removeApiKey() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text("删除 Key")
                            }
                        }
                    }
                }
            }

            // ── Model Section ────────────────────────────
            // 模型偏好（Flash/Pro 档位、自动切换）只对 DeepSeek 官方 API 生效；
            // 第三方模型固定走自定义网关和模型名，展示这些选项只会误导。
            if (!apiKeyState.customProviderEnabled) {
                Text("🧠 模型偏好",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

                Card(modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("默认模型: ${ModelTier.V4_PRO.displayName}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("说明: 简单任务自动用 ${ModelTier.V4_FLASH.displayName} 省钱",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(Modifier.height(8.dp))
                        // 直接用 state 值驱动 Checkbox，避免本地 var 与 state 脱节
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = apiKeyState.autoSwitchEnabled, onCheckedChange = {
                                viewModel.setAutoSwitch(it)
                            })
                            Text("自动选择模型", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = apiKeyState.simpleTasksUseFlash, onCheckedChange = {
                                viewModel.setSimpleTasksUseFlash(it)
                            })
                            Text("简单任务用 Flash", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // ── Third-Party Provider Section ─────────────
            Text("🌐 第三方模型（OpenAI / Anthropic 兼容）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("当前模型：${apiKeyState.activeModelLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Text("支持保存多个 OpenAI 或 Anthropic 兼容模型，列表中点选即可切换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("HTTP 私有网关也可测试；若网关暴露在公网，优先建议配置 HTTPS。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(12.dp))
                    ModelOptionRow(
                        title = "DeepSeek 官方",
                        subtitle = "使用官方 API Key，模式仍由聊天/思考/专家控制",
                        selected = apiKeyState.activeModelId == ApiKeyManager.OFFICIAL_MODEL_ID,
                        onSelect = { viewModel.selectModel(ApiKeyManager.OFFICIAL_MODEL_ID) },
                    )

                    apiKeyState.customModels.forEach { model ->
                        Spacer(Modifier.height(6.dp))
                        ModelOptionRow(
                            title = model.displayName,
                            subtitle = "${model.protocol.displayName} · ${model.endpointMode.displayName} · ${model.modelName} · ${model.baseUrl}",
                            selected = apiKeyState.activeModelId == model.id,
                            keyText = if (model.hasKey) model.keyMasked else "无鉴权",
                            testing = modelTestState.inProgress && modelTestState.targetId == model.id,
                            onSelect = { viewModel.selectModel(model.id) },
                            onTest = { viewModel.testSavedCustomModel(model.id) },
                            onDelete = { viewModel.deleteCustomModel(model.id) },
                        )
                    }

                    if (modelTestState.message.isNotBlank() &&
                        modelTestState.targetId != SettingsViewModel.NEW_MODEL_TEST_ID
                    ) {
                        Spacer(Modifier.height(10.dp))
                        ModelTestResultBox(modelTestState)
                    }

                    Spacer(Modifier.height(14.dp))
                    var displayNameInput by remember { mutableStateOf("") }
                    var baseUrlInput by remember { mutableStateOf("") }
                    var modelInput by remember { mutableStateOf("") }
                    var protocolInput by remember { mutableStateOf(ProviderProtocol.AUTO) }
                    var endpointModeInput by remember { mutableStateOf(ProviderEndpointMode.AUTO) }
                    var customKeyInput by remember { mutableStateOf("") }
                    val sanitizedBaseUrlInput = remember(baseUrlInput) {
                        sanitizeProviderBaseUrl(baseUrlInput)
                    }
                    val baseUrlInputCleanupSummary = remember(baseUrlInput, sanitizedBaseUrlInput) {
                        providerBaseUrlCleanupSummary(baseUrlInput, sanitizedBaseUrlInput)
                    }
                    val sanitizedModelInput = remember(modelInput) {
                        sanitizeProviderModelName(modelInput)
                    }
                    val modelInputCleanupSummary = remember(modelInput, sanitizedModelInput) {
                        modelNameCleanupSummary(modelInput, sanitizedModelInput)
                    }
                    val displayNameSuggestion = remember(sanitizedBaseUrlInput, sanitizedModelInput) {
                        providerDisplayNameSuggestion(
                            baseUrl = sanitizedBaseUrlInput,
                            modelName = sanitizedModelInput,
                        )
                    }
                    val currentModelListEndpoint = remember(sanitizedBaseUrlInput, endpointModeInput) {
                        if (sanitizedBaseUrlInput.isBlank()) {
                            ""
                        } else {
                            DeepSeekApiClient.buildOpenAiModelsEndpoint(sanitizedBaseUrlInput, endpointModeInput)
                        }
                    }
                    val showModelListProbeState = modelListProbeState.message.isNotBlank() &&
                        (modelListProbeState.endpoint.isBlank() || modelListProbeState.endpoint == currentModelListEndpoint)
                    val modelRouteHint = remember(
                        sanitizedModelInput,
                        modelListProbeState.modelIds,
                        showModelListProbeState,
                    ) {
                        providerModelRouteHint(
                            modelName = sanitizedModelInput,
                            modelIds = if (showModelListProbeState) modelListProbeState.modelIds else emptyList(),
                        )
                    }
                    val modelRouteSuggestion = remember(
                        sanitizedModelInput,
                        modelListProbeState.modelIds,
                        showModelListProbeState,
                    ) {
                        if (showModelListProbeState) {
                            providerModelRouteSuggestedModel(
                                modelName = sanitizedModelInput,
                                modelIds = modelListProbeState.modelIds,
                            )
                        } else {
                            ""
                        }
                    }
                    val providerEndpointPreview = remember(
                        sanitizedBaseUrlInput,
                        protocolInput,
                        endpointModeInput,
                        sanitizedModelInput,
                    ) {
                        val baseUrl = sanitizedBaseUrlInput
                        if (baseUrl.isBlank()) {
                            ""
                        } else {
                            val resolvedProtocol = ProviderProtocol.resolve(
                                protocolInput,
                                baseUrl,
                                sanitizedModelInput,
                            )
                            val endpoint = when (resolvedProtocol) {
                                ProviderProtocol.ANTHROPIC ->
                                    DeepSeekApiClient.buildAnthropicMessagesEndpoint(baseUrl, endpointModeInput)
                                else ->
                                    DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(baseUrl, endpointModeInput)
                            }
                            providerEndpointPreviewSummary(
                                protocolLabel = resolvedProtocol.displayName,
                                endpointModeLabel = endpointModeInput.displayName,
                                endpoint = endpoint,
                            )
                        }
                    }
                    val providerRequestTemplate = remember(
                        sanitizedBaseUrlInput,
                        sanitizedModelInput,
                        protocolInput,
                        endpointModeInput,
                        customKeyInput,
                    ) {
                        val baseUrl = sanitizedBaseUrlInput
                        val modelName = sanitizedModelInput
                        if (baseUrl.isBlank() || modelName.isBlank()) {
                            ""
                        } else {
                            val resolvedProtocol = ProviderProtocol.resolve(
                                protocolInput,
                                baseUrl,
                                modelName,
                            )
                            val endpoint = when (resolvedProtocol) {
                                ProviderProtocol.ANTHROPIC ->
                                    DeepSeekApiClient.buildAnthropicMessagesEndpoint(baseUrl, endpointModeInput)
                                else ->
                                    DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(baseUrl, endpointModeInput)
                            }
                            providerRequestCurlTemplate(
                                protocolLabel = resolvedProtocol.displayName,
                                endpoint = endpoint,
                                modelName = modelName,
                                hasApiKey = customKeyInput.trim().isNotBlank(),
                            )
                        }
                    }
                    val providerEndpointWarning = remember(
                        sanitizedBaseUrlInput,
                        protocolInput,
                        endpointModeInput,
                        sanitizedModelInput,
                    ) {
                        val baseUrl = sanitizedBaseUrlInput
                        if (baseUrl.isBlank() || endpointModeInput != ProviderEndpointMode.EXACT) {
                            ""
                        } else {
                            val resolvedProtocol = ProviderProtocol.resolve(
                                protocolInput,
                                baseUrl,
                                sanitizedModelInput,
                            )
                            val endpoint = when (resolvedProtocol) {
                                ProviderProtocol.ANTHROPIC ->
                                    DeepSeekApiClient.buildAnthropicMessagesEndpoint(baseUrl, endpointModeInput)
                                else ->
                                    DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(baseUrl, endpointModeInput)
                            }
                            providerExactEndpointWarning(
                                protocolLabel = resolvedProtocol.displayName,
                                endpoint = endpoint,
                            )
                        }
                    }
                    val providerExactCompletionEndpoint = remember(
                        sanitizedBaseUrlInput,
                        protocolInput,
                        endpointModeInput,
                        sanitizedModelInput,
                        providerEndpointWarning,
                    ) {
                        val baseUrl = sanitizedBaseUrlInput
                        if (baseUrl.isBlank() || providerEndpointWarning.isBlank()) {
                            ""
                        } else {
                            val resolvedProtocol = ProviderProtocol.resolve(
                                protocolInput,
                                baseUrl,
                                sanitizedModelInput,
                            )
                            when (resolvedProtocol) {
                                ProviderProtocol.ANTHROPIC ->
                                    DeepSeekApiClient.buildAnthropicMessagesEndpoint(baseUrl, ProviderEndpointMode.AUTO)
                                else ->
                                    DeepSeekApiClient.buildOpenAiChatCompletionsEndpoint(baseUrl, ProviderEndpointMode.AUTO)
                            }
                        }
                    }
                    val providerEndpointCompletionAction = remember(
                        providerEndpointWarning,
                        sanitizedBaseUrlInput,
                        providerExactCompletionEndpoint,
                    ) {
                        providerExactEndpointCompletionActionLabel(
                            warning = providerEndpointWarning,
                            currentEndpoint = sanitizedBaseUrlInput,
                            suggestedEndpoint = providerExactCompletionEndpoint,
                        )
                    }
                    val providerEndpointRecoveryAction = remember(providerEndpointWarning) {
                        providerExactEndpointRecoveryActionLabel(providerEndpointWarning)
                    }

                    Text("添加模型",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = displayNameInput,
                        onValueChange = { displayNameInput = it },
                        label = { Text("显示名称（如 9router Claude）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (displayNameInput.trim().isBlank() && displayNameSuggestion.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                displayNameInput = displayNameSuggestion
                                Toast.makeText(context, "已填入推荐名称", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("使用推荐名称")
                            Spacer(Modifier.width(8.dp))
                            Text(
                                displayNameSuggestion,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        label = { Text("服务地址（如 https://my-9router.com）") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            if (baseUrlInputCleanupSummary.isNotBlank()) {
                                Text(baseUrlInputCleanupSummary)
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("模型名（如 gpt-4o / claude-3-5-sonnet）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            if (modelInputCleanupSummary.isNotBlank() || modelRouteHint.isNotBlank()) {
                                Column {
                                    if (modelInputCleanupSummary.isNotBlank()) {
                                        Text(modelInputCleanupSummary)
                                    }
                                    if (modelRouteHint.isNotBlank()) {
                                        Text(modelRouteHint)
                                    }
                                }
                            }
                        },
                    )
                    if (modelRouteSuggestion.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                modelInput = modelRouteSuggestion
                                Toast.makeText(context, "已改用列表候选", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("改用列表候选")
                            Spacer(Modifier.width(8.dp))
                            Text(
                                modelRouteSuggestion,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.probeCustomModelList(
                                sanitizedBaseUrlInput,
                                sanitizedModelInput,
                                protocolInput,
                                endpointModeInput,
                                customKeyInput.trim(),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = sanitizedBaseUrlInput.isNotBlank() && !modelListProbeState.inProgress,
                    ) {
                        if (modelListProbeState.inProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (modelListProbeState.inProgress) "获取中..." else "获取模型列表")
                    }
                    if (showModelListProbeState) {
                        Spacer(Modifier.height(8.dp))
                        ModelListProbeBox(
                            state = modelListProbeState,
                            filterQuery = sanitizedModelInput,
                            hasApiKey = customKeyInput.trim().isNotBlank(),
                            onClearFilter = {
                                modelInput = ""
                                Toast.makeText(context, "已显示全部模型", Toast.LENGTH_SHORT).show()
                            },
                            onModelSelected = { selectedModel ->
                                modelInput = selectedModel
                                Toast.makeText(context, "已填入模型名", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("协议",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProviderProtocol.entries.forEach { protocol ->
                            FilterChip(
                                selected = protocolInput == protocol,
                                onClick = { protocolInput = protocol },
                                label = { Text(protocol.displayName) },
                            )
                        }
                    }
                    Text(protocolInput.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("地址模式",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProviderEndpointMode.entries.forEach { mode ->
                            FilterChip(
                                selected = endpointModeInput == mode,
                                onClick = { endpointModeInput = mode },
                                label = { Text(mode.displayName) },
                            )
                        }
                    }
                    Text(endpointModeInput.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (providerEndpointPreview.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            providerEndpointPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (providerRequestTemplate.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(providerRequestTemplate))
                                    Toast.makeText(context, "已复制请求模板", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("复制请求模板")
                            }
                        }
                    }
                    if (providerEndpointWarning.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            providerEndpointWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (
                        providerEndpointCompletionAction.isNotBlank() ||
                        providerEndpointRecoveryAction.isNotBlank()
                    ) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (providerEndpointCompletionAction.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        baseUrlInput = providerExactCompletionEndpoint
                                        Toast.makeText(context, "已补成完整地址", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.Build,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(providerEndpointCompletionAction)
                                }
                            }
                            if (providerEndpointRecoveryAction.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        endpointModeInput = ProviderEndpointMode.AUTO
                                        Toast.makeText(context, "已切回智能拼接", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(providerEndpointRecoveryAction)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customKeyInput,
                        onValueChange = { customKeyInput = it },
                        label = { Text("API Key（可留空，部分网关不需要）") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        supportingText = {
                            val fingerprint = apiKeyFingerprintSummary(customKeyInput)
                            if (fingerprint.isNotBlank()) {
                                Text(fingerprint)
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.testCustomModelInput(
                                    sanitizedBaseUrlInput,
                                    sanitizedModelInput,
                                    protocolInput,
                                    endpointModeInput,
                                    customKeyInput.trim(),
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = sanitizedBaseUrlInput.isNotBlank() &&
                                sanitizedModelInput.isNotBlank() &&
                                !(modelTestState.inProgress &&
                                    modelTestState.targetId == SettingsViewModel.NEW_MODEL_TEST_ID),
                        ) {
                            if (modelTestState.inProgress &&
                                modelTestState.targetId == SettingsViewModel.NEW_MODEL_TEST_ID
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("测试聊天")
                        }
                        Button(
                            onClick = {
                                viewModel.saveCustomModel(
                                    displayNameInput.trim().ifBlank { sanitizedModelInput },
                                    sanitizedBaseUrlInput,
                                    sanitizedModelInput,
                                    protocolInput,
                                    endpointModeInput,
                                    customKeyInput.trim(),
                                )
                                displayNameInput = ""
                                baseUrlInput = ""
                                modelInput = ""
                                protocolInput = ProviderProtocol.AUTO
                                endpointModeInput = ProviderEndpointMode.AUTO
                                customKeyInput = ""
                            },
                            modifier = Modifier.weight(1f),
                            enabled = sanitizedBaseUrlInput.isNotBlank() && sanitizedModelInput.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加并选中")
                        }
                    }
                    if (modelTestState.message.isNotBlank() &&
                        modelTestState.targetId == SettingsViewModel.NEW_MODEL_TEST_ID
                    ) {
                        Spacer(Modifier.height(10.dp))
                        val modelListSummaryForTestCopy = if (showModelListProbeState) {
                            modelListProbeState.detail
                        } else {
                            ""
                        }
                        val modelListRequestForTestCopy = if (showModelListProbeState) {
                            providerModelListCurlTemplate(
                                endpoint = modelListProbeState.endpoint,
                                hasApiKey = customKeyInput.trim().isNotBlank(),
                            )
                        } else {
                            ""
                        }
                        ModelTestResultBox(
                            state = modelTestState,
                            modelListSummary = modelListSummaryForTestCopy,
                            modelListRequestTemplate = modelListRequestForTestCopy,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("智能拼接会按协议补全标准路径；完整地址会原样请求你填写的 URL，适合非标准第三方网关。第三方模型会自动关闭 DeepSeek 专属思考参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Diagnostics Section ──────────────────────
            Text("🧪 功能自检",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("一键检查模型配置、第三方接口、Python 执行器、文件工具和 Agent 工具链。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.runFullDiagnostics() },
                        enabled = !fullDiagnosticsState.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (fullDiagnosticsState.running) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (fullDiagnosticsState.running) "自检中..." else "运行全量自检")
                    }

                    if (fullDiagnosticsState.results.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        val failed = fullDiagnosticsState.results.count { it.status == DiagnosticStatus.FAIL }
                        val warned = fullDiagnosticsState.results.count { it.status == DiagnosticStatus.WARN }
                        val diagnosticsCopySummary = remember(fullDiagnosticsState.results) {
                            fullDiagnosticsCopySummary(
                                fullDiagnosticsState.results.map { result ->
                                    DiagnosticsCopyItem(
                                        name = result.name,
                                        status = result.status.name,
                                        message = result.message,
                                        durationMs = result.durationMs,
                                        detail = result.detail,
                                    )
                                }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "结果：${fullDiagnosticsState.results.size} 项，失败 $failed，警告 $warned",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(diagnosticsCopySummary))
                                    Toast.makeText(context, "已复制自检摘要", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "复制自检摘要",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            fullDiagnosticsState.results.forEach { result ->
                                DiagnosticResultRow(result)
                            }
                        }
                    }
                }
            }

            // ── API Key Tutorial ─────────────────────────
            Text("📖 API Key 帮助",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("不知道怎么获取 API Key？",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("我们准备了一份详细的图文教程，手把手教你注册和获取 DeepSeek API Key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToTutorial,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color(0xFF08090B)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("查看图文教程", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ── 自定义指令 ────────────────────────────────
            Text(
                "🧭 自定义指令",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("给核弹男孩定规矩", fontWeight = FontWeight.Bold)
                    Text(
                        "你写的要求会加进每次对话的系统提示，让它按你的习惯回复。例如：「只用中文，回答尽量简短」「我是后端工程师，默认 Kotlin」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    val savedCi by viewModel.customInstructions.collectAsState()
                    var ciInput by remember(savedCi) { mutableStateOf(savedCi) }
                    OutlinedTextField(
                        value = ciInput,
                        onValueChange = { ciInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                        placeholder = { Text("写下你的偏好/人设/规则（可留空）") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.saveCustomInstructions(ciInput) },
                        enabled = ciInput != savedCi,
                    ) { Text("保存指令") }
                }
            }

            // ── Remote PC Section ────────────────────────
            val pcTasks by viewModel.pcTasksState.collectAsState()
            RemotePcSection(
                config = pcBridgeState,
                testState = pcBridgeTestState,
                runningTasks = pcTasks,
                onEnabledChange = { viewModel.setPcBridgeEnabled(it) },
                onSave = { url, token -> viewModel.savePcBridgeConnection(url, token) },
                onTest = { url, token -> viewModel.testPcBridgeConnection(url, token) },
                onLoadTasks = { viewModel.loadPcTasks() },
                onOpenTerminal = onNavigateToTerminal,
                onEncryptionChange = { viewModel.setPcEncryption(it) },
            )

            // ── Sponsor Section ──────────────────────────
            Text("❤️ 投喂作者",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("☢️ 核弹男孩 · 为爱发电",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "我是 mzpr00，一名普通的开发者。\n这款 App 永久免费开源，为所有同学打造。\n如果它帮到了你，可以请我喝杯奶茶 ☕",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )

                    Spacer(Modifier.height(16.dp))

                    // Payment tier buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SponsorTierButton(
                            emoji = "🍫",
                            label = "一块脆脆鲨",
                            amount = "¥1",
                            modifier = Modifier.weight(1f),
                            onClick = { showSponsorDialog = true },
                        )
                        SponsorTierButton(
                            emoji = "🧋",
                            label = "一杯奶茶",
                            amount = "¥6",
                            modifier = Modifier.weight(1f),
                            onClick = { showSponsorDialog = true },
                        )
                        SponsorTierButton(
                            emoji = "🍜",
                            label = "一顿午饭",
                            amount = "¥15",
                            modifier = Modifier.weight(1f),
                            onClick = { showSponsorDialog = true },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "每一份支持都是我继续开发的动力 💪",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // ── Sponsor Dialog ────────────────────────────
            if (showSponsorDialog) {
                AlertDialog(
                    onDismissRequest = { showSponsorDialog = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    title = {
                        Text("📱 扫码投喂",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Default,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                    },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            // 收款二维码
                            Image(
                                painter = painterResource(R.drawable.payment_qr),
                                contentDescription = "收款码",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "感谢每一位支持者！你的名字会被记录在 App 的致谢名单中 🌟",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF00E676),
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSponsorDialog = false }) {
                            Text("先不了，下次一定", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }

            // ── About Section ────────────────────────────
            Text("ℹ️ 关于",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("☢️ 核弹男孩 NUCLEAR BOY", style = MaterialTheme.typography.titleMedium)

                    // 动态版本号
                    val currentVersion = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
                    } catch (e: Exception) { "0.0.0" }
                    Text("v$currentVersion · BUILD ${com.nuclearboy.app.BuildConfig.BUILD_TYPE.uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(4.dp))
                    Text("作者: mzpr00 · mapr00@163.com", style = MaterialTheme.typography.bodySmall)
                    Text("免费开源 · 为所有同学打造", style = MaterialTheme.typography.bodySmall)
                    Text("新时代的贾维斯，比你更懂你的手机", style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(12.dp))

                    // 检查更新按钮
                    var updateChecking by remember { mutableStateOf(false) }
                    var updateResult by remember { mutableStateOf<UpdateManager.UpdateResult?>(null) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                updateChecking = true
                                updateResult = null
                                composableScope.launch {
                                    val um = UpdateManager(context)
                                    updateResult = um.checkForUpdate(force = true)
                                    updateChecking = false
                                }
                            },
                            enabled = !updateChecking,
                        ) {
                            if (updateChecking) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("检查中...")
                            } else {
                                Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("检查更新")
                            }
                        }
                    }

                    // 更新结果
                    when (val result = updateResult) {
                        is UpdateManager.UpdateResult.Available -> {
                            Spacer(Modifier.height(8.dp))
                            Text("🆕 新版本 ${result.version} 可用！",
                                color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                            if (result.body.isNotBlank()) {
                                Text(result.body.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = {
                                UpdateDownloader.download(
                                    context = context,
                                    url = result.url,
                                    version = result.version,
                                    expectedSize = result.expectedSize,
                                    expectedDigest = result.expectedDigest,
                                )
                            }) { Text("下载并安装 →", color = Color(0xFF00E676)) }
                        }
                        is UpdateManager.UpdateResult.UpToDate -> {
                            Spacer(Modifier.height(8.dp))
                            Text("✅ 已是最新版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is UpdateManager.UpdateResult.Error -> {
                            Spacer(Modifier.height(8.dp))
                            Text("⚠️ 检查失败: ${result.message}", color = MaterialTheme.colorScheme.error)
                        }
                        null -> {}
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SponsorTierButton(
    emoji: String,
    label: String,
    amount: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(80.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFF00E676).copy(alpha = 0.06f),
            contentColor = Color(0xFF00E676),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(emoji, fontSize = 18.sp)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(amount, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
        }
    }
}

@Composable
private fun ModelTestResultBox(
    state: ModelTestUiState,
    modelListSummary: String = "",
    modelListRequestTemplate: String = "",
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copySummary = remember(
        state.inProgress,
        state.success,
        state.message,
        state.detail,
        state.requestTemplate,
        modelListSummary,
        modelListRequestTemplate,
    ) {
        modelTestTroubleshootingBundle(
            testSummary = modelTestCopySummary(
                inProgress = state.inProgress,
                success = state.success,
                message = state.message,
                detail = state.detail,
            ),
            requestTemplate = state.requestTemplate,
            modelListSummary = modelListSummary,
            modelListRequestTemplate = modelListRequestTemplate,
        )
    }
    val color = when (state.success) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = color,
                    )
                } else {
                    Icon(
                        imageVector = if (state.success == true) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    state.message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = color,
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(copySummary))
                        Toast.makeText(context, "已复制测试排障包", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制测试排障包",
                        tint = color,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (state.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelListProbeBox(
    state: ModelListProbeUiState,
    filterQuery: String,
    hasApiKey: Boolean,
    onClearFilter: () -> Unit,
    onModelSelected: (String) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val visibleModelIds = remember(state.modelIds, filterQuery) {
        providerModelListVisibleModels(
            modelIds = state.modelIds,
            query = filterQuery,
            limit = 12,
        )
    }
    val pickerHint = remember(state.modelIds, visibleModelIds, filterQuery) {
        providerModelListPickerHint(
            totalCount = state.modelIds.map { it.trim() }.filter { it.isNotBlank() }.distinct().size,
            visibleCount = visibleModelIds.size,
            query = filterQuery,
        )
    }
    val clearFilterActionLabel = remember(state.modelIds, visibleModelIds, filterQuery) {
        providerModelListClearFilterActionLabel(
            totalCount = state.modelIds.map { it.trim() }.filter { it.isNotBlank() }.distinct().size,
            visibleCount = visibleModelIds.size,
            query = filterQuery,
        )
    }
    val requestTemplate = remember(state.endpoint, hasApiKey) {
        providerModelListCurlTemplate(
            endpoint = state.endpoint,
            hasApiKey = hasApiKey,
        )
    }
    val color = when (state.success) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.24f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = color,
                    )
                } else {
                    Icon(
                        imageVector = if (state.success == true) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    state.message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = color,
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(requestTemplate))
                        Toast.makeText(context, "已复制列表请求模板", Toast.LENGTH_SHORT).show()
                    },
                    enabled = requestTemplate.isNotBlank(),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = "复制模型列表请求模板",
                        tint = color,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(state.detail))
                        Toast.makeText(context, "已复制模型列表摘要", Toast.LENGTH_SHORT).show()
                    },
                    enabled = state.detail.isNotBlank(),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制模型列表摘要",
                        tint = color,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (state.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.modelIds.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                if (pickerHint.isNotBlank()) {
                    Text(
                        pickerHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (clearFilterActionLabel.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onClearFilter,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(clearFilterActionLabel)
                    }
                }
                Spacer(Modifier.height(4.dp))
                visibleModelIds.forEach { modelId ->
                    TextButton(
                        onClick = { onModelSelected(modelId) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            modelId,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticResultRow(result: DiagnosticResult) {
    val color = when (result.status) {
        DiagnosticStatus.PASS -> MaterialTheme.colorScheme.primary
        DiagnosticStatus.WARN -> Color(0xFFFFB020)
        DiagnosticStatus.FAIL -> MaterialTheme.colorScheme.error
    }
    val icon = when (result.status) {
        DiagnosticStatus.PASS -> Icons.Filled.CheckCircle
        DiagnosticStatus.WARN -> Icons.Filled.Warning
        DiagnosticStatus.FAIL -> Icons.Filled.Error
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${result.name} · ${result.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = color,
                )
                Text(
                    "耗时 ${result.durationMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        result.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    keyText: String? = null,
    testing: Boolean = false,
    onSelect: () -> Unit,
    onTest: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (!keyText.isNullOrBlank()) {
                Text(keyText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onTest != null) {
            IconButton(
                onClick = onTest,
                enabled = !testing,
                modifier = Modifier.size(36.dp),
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.WifiTethering, contentDescription = "测试模型")
                }
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "删除模型", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
