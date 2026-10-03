package com.nuclearboy.app.di

import android.content.Context
import android.os.Environment
import com.nuclearboy.agent.AgentEngine
import com.nuclearboy.agent.SystemPromptBuilder
import com.nuclearboy.agent.ToolRegistry
import com.nuclearboy.agent.ToolDefinition
import com.nuclearboy.agent.ToolParameter
import com.nuclearboy.agent.ToolResult
import com.nuclearboy.api.deepseek.*
import com.nuclearboy.common.*
import com.nuclearboy.common.toFileSizeString
import com.nuclearboy.app.python.ChaquopyPythonExecutor
import com.nuclearboy.app.service.PcTaskNotifier
import com.nuclearboy.app.ui.permissions.PermissionManager
import com.nuclearboy.memory.MemoryStore
import com.nuclearboy.python.PythonExecutor
import com.nuclearboy.python.PythonSandbox
import com.nuclearboy.python.SandboxPolicy
import com.nuclearboy.remotepc.PcBridgeClient
import com.nuclearboy.remotepc.PcBridgeConfigStore
import com.nuclearboy.skills.SkillManager
import com.nuclearboy.skills.SkillMarketPlace
import com.nuclearboy.tools.docgen.FileOperations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val memoryFileMutex = Mutex()

    // ── DeepSeek API ──────────────────────────────────

    @Provides
    @Singleton
    fun provideApiKeyManager(@ApplicationContext context: Context): ApiKeyManager {
        android.util.Log.e("NuclearBoy", "[DI] provideApiKeyManager")
        return ApiKeyManager(context)
    }

    @Provides
    @Singleton
    fun provideTokenTracker(): TokenTracker {
        android.util.Log.e("NuclearBoy", "[DI] provideTokenTracker")
        return TokenTracker()
    }

    @Provides
    @Singleton
    fun provideContextWindowManager(): ContextWindowManager {
        android.util.Log.e("NuclearBoy", "[DI] provideContextWindowManager")
        return ContextWindowManager()
    }

    @Provides
    @Singleton
    fun provideDeepSeekApiClient(
        apiKeyManager: ApiKeyManager,
        tokenTracker: TokenTracker,
        contextWindowManager: ContextWindowManager,
    ): DeepSeekApiClient {
        android.util.Log.e("NuclearBoy", "[DI] provideDeepSeekApiClient")
        return DeepSeekApiClient(
            apiKeyProvider = { apiKeyManager.getActiveKey() },
            tokenTracker = tokenTracker,
            contextManager = contextWindowManager,
            baseUrlProvider = { apiKeyManager.getActiveBaseUrl() },
            modelOverrideProvider = { apiKeyManager.getModelOverride() },
            providerProtocolProvider = { apiKeyManager.getActiveProviderProtocol() },
            providerEndpointModeProvider = { apiKeyManager.getActiveProviderEndpointMode() },
        )
    }

    // ── Python Executor ────────────────────────────────

    @Provides
    @Singleton
    fun providePythonExecutor(): PythonExecutor {
        android.util.Log.e("NuclearBoy", "[DI] providePythonExecutor -> ChaquopyPythonExecutor")
        return ChaquopyPythonExecutor()
    }

    @Provides
    @Singleton
    fun providePythonSandbox(
        @ApplicationContext context: Context,
        executor: PythonExecutor,
    ): PythonSandbox {
        android.util.Log.e("NuclearBoy", "[DI] providePythonSandbox")
        val sandbox = PythonSandbox(context)
        sandbox.executor = executor
        return sandbox
    }

    // ── Memory ────────────────────────────────────────

    @Provides
    @Singleton
    fun provideMemoryStore(@ApplicationContext context: Context): MemoryStore {
        android.util.Log.e("NuclearBoy", "[DI] provideMemoryStore")
        return MemoryStore(context)
    }

    // ── Skills ────────────────────────────────────────

    @Provides
    @Singleton
    fun provideSkillsDir(@ApplicationContext context: Context): File {
        android.util.Log.e("NuclearBoy", "[DI] provideSkillsDir")
        // Built-in skills are copied from APK assets by Application.onCreate
        // on an IO coroutine.  Wait for that one-shot job before SkillManager
        // scans the directory; otherwise a fresh install can expose an empty
        // Skill list and omit the skill_* tools until the next process start.
        (context.applicationContext as? com.nuclearboy.app.NuclearBoyApp)?.let { app ->
            runBlocking { app.awaitBuiltinSkillsReady() }
        }
        return File(context.filesDir, "skills").also { it.mkdirs() }
    }

    @Provides
    @Singleton
    fun provideSkillManager(
        pythonSandbox: PythonSandbox,
        skillsDir: File,
    ): SkillManager {
        android.util.Log.e("NuclearBoy", "[DI] provideSkillManager")
        return SkillManager(pythonSandbox, skillsDir)
    }

    @Provides
    @Singleton
    fun provideSkillMarketPlace(): SkillMarketPlace {
        android.util.Log.e("NuclearBoy", "[DI] provideSkillMarketPlace")
        return SkillMarketPlace()
    }

    // ── Tools ─────────────────────────────────────────

    @Provides
    @Singleton
    fun provideFileOperations(@ApplicationContext context: Context): FileOperations {
        android.util.Log.e("NuclearBoy", "[DI] provideFileOperations")
        // Use app-specific external storage for full read/write/delete permissions
        val root = File(context.getExternalFilesDir(null), AppConstants.APP_DOCUMENTS_DIR).also { it.mkdirs() }
        // 自动创建全局 General Agent 文件夹
        val generalDir = File(root, "__general__")
        generalDir.mkdirs()
        val generalAgentDir = File(generalDir, ".agent")
        generalAgentDir.mkdirs()
        android.util.Log.e("NuclearBoy", "[DI] __general__ folder created at ${generalDir.absolutePath}")
        return FileOperations(root)
    }

    // ── Remote PC Bridge ──────────────────────────────

    @Provides
    @Singleton
    fun providePcBridgeConfigStore(@ApplicationContext context: Context): PcBridgeConfigStore {
        android.util.Log.e("NuclearBoy", "[DI] providePcBridgeConfigStore")
        return PcBridgeConfigStore(context)
    }

    @Provides
    @Singleton
    fun provideAppSettingsStore(@ApplicationContext context: Context): com.nuclearboy.common.AppSettingsStore =
        com.nuclearboy.common.AppSettingsStore(context)

    @Provides
    @Singleton
    fun providePcBridgeClient(configStore: PcBridgeConfigStore): PcBridgeClient {
        android.util.Log.e("NuclearBoy", "[DI] providePcBridgeClient")
        return PcBridgeClient(configStore)
    }

    // ── Agent ─────────────────────────────────────────

    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext appContext: Context,
        pythonSandbox: PythonSandbox,
        fileOperations: FileOperations,
        skillManager: SkillManager,
        memoryStore: MemoryStore,
        pcBridgeClient: PcBridgeClient,
        pcBridgeConfigStore: PcBridgeConfigStore,
    ): ToolRegistry {
        android.util.Log.e("NuclearBoy", "[DI] provideToolRegistry — entry")
        val registry = ToolRegistry()

        runBlocking {
            android.util.Log.e("NuclearBoy", "[DI] registerDefaultTools")
            registry.registerDefaultTools()

            val fileTools = buildFileOperationTools(fileOperations)
            android.util.Log.e("NuclearBoy", "[DI] buildFileOperationTools — ${fileTools.size} tools: ${fileTools.joinToString { it.name }}")
            registry.registerAll(fileTools)

            val webTools = buildWebTools(pythonSandbox)
            android.util.Log.e("NuclearBoy", "[DI] buildWebTools — ${webTools.size} tools: ${webTools.joinToString { it.name }}")
            registry.registerAll(webTools)

            val memTools = buildMemoryTools(fileOperations)
            android.util.Log.e("NuclearBoy", "[DI] buildMemoryTools — ${memTools.size} tools: ${memTools.joinToString { it.name }}")
            registry.registerAll(memTools)

            val pcTools = buildRemotePcTools(appContext, pcBridgeClient, pcBridgeConfigStore)
            android.util.Log.e("NuclearBoy", "[DI] buildRemotePcTools — ${pcTools.size} tools: ${pcTools.joinToString { it.name }}")
            registry.registerAll(pcTools)

        }

        registry.pythonExecutor = pythonExecutor@{ _, params ->
            val scriptCode = params["path"] ?: params["script"]
            if (scriptCode == null) ToolResult(false, "", error = "缺少 path 参数。示例：path=\"print('hello')\"")
            else {
                val scope = params["scope"]?.trim()?.lowercase().orEmpty().ifBlank { "project" }
                if (scope !in setOf("project", "global")) {
                    return@pythonExecutor ToolResult(false, error = "scope 只能是 project 或 global")
                }
                val globalScope = scope == "global"
                if (globalScope && !PermissionManager.isAllFilesAccessGranted(appContext)) {
                    return@pythonExecutor ToolResult(
                        false,
                        error = "全局文件访问需要开启‘所有文件访问’权限。请在系统设置中为核弹男孩开启后重试。",
                    )
                }
                val projectRoot = fileOperations.projectRoot().canonicalFile
                val externalRoot = Environment.getExternalStorageDirectory().canonicalFile
                val accessRoot = if (globalScope) externalRoot else projectRoot
                val requestedWd = params["workingDir"]?.trim().orEmpty()
                val wd = try {
                    val candidate = if (requestedWd.isBlank() || requestedWd == ".") {
                        accessRoot
                    } else {
                        val raw = File(requestedWd)
                        if (raw.isAbsolute) raw else File(accessRoot, requestedWd)
                    }.canonicalFile
                    val insideScope = candidate == accessRoot ||
                        candidate.path.startsWith(accessRoot.path + File.separator)
                    if (!insideScope || !candidate.isDirectory) {
                        return@pythonExecutor ToolResult(
                            false,
                            error = if (globalScope) {
                                "global scope 的 workingDir 必须是共享存储内已存在的目录: $requestedWd"
                            } else {
                                "workingDir 必须是当前项目内已存在的目录: $requestedWd"
                            },
                        )
                    }
                    candidate.absolutePath
                } catch (e: Exception) {
                    return@pythonExecutor ToolResult(false, error = "workingDir 无效: ${e.message}")
                }
                val policy = if (globalScope) {
                    // relaxed() grants read/write within shared external
                    // storage while still enforcing Python's shell and size
                    // limits.  It is available only after the explicit
                    // Android all-files permission check above.
                    SandboxPolicy.relaxed(wd)
                } else {
                    SandboxPolicy.standard(wd)
                }
                val r = pythonSandbox.execute(scriptCode, wd, policy = policy)
                android.util.Log.e("NuclearBoy", "[DI] pythonExecutor result — exitCode=${r.exitCode}, stdoutLen=${r.stdout.length}, stderrLen=${r.stderr.length}")
                ToolResult(success = r.exitCode == 0, output = r.stdout, error = r.stderr.ifBlank { null })
            }
        }

        registry.skillsExecutor = { name, params ->
            when (val r = skillManager.executeSkill(name, params)) {
                is AppResult.Success -> {
                    val output = r.data.stdout.ifBlank { "OK" }
                    android.util.Log.e("NuclearBoy", "[DI] skillsExecutor — skill '$name' OK outputLen=${r.data.stdout.length}")
                    ToolResult(true, output)
                }
                is AppResult.Failure -> {
                    android.util.Log.e("NuclearBoy", "[DI] skillsExecutor — skill '$name' FAILED: ${r.error.humanMessage}")
                    ToolResult(false, "", error = r.error.humanMessage)
                }
            }
        }

        // SkillManager performs its first filesystem scan asynchronously during
        // construction.  Configure callbacks through its synchronization point
        // so existing skills are registered before this provider returns, while
        // installs/reloads wait on the same lock and cannot race or duplicate.
        runBlocking {
            skillManager.configureToolCallbacks(
                register = { name, desc, _ ->
                    android.util.Log.e("NuclearBoy", "[DI] skill tool register callback — skillName=$name, desc=${desc.take(50)}")
                    val parameters = skillManager.getSkill(name)?.manifest?.parameters.orEmpty().map { parameter ->
                        val toolType = when (parameter.type.lowercase()) {
                            "int", "integer" -> "integer"
                            "float", "double", "number" -> "number"
                            "bool", "boolean" -> "boolean"
                            else -> "string"
                        }
                        com.nuclearboy.agent.ToolParameter(
                            name = parameter.name,
                            type = toolType,
                            description = parameter.description,
                            required = parameter.required,
                            default = parameter.default,
                            enum = parameter.allowedValues.takeIf { it.isNotEmpty() },
                        )
                    }
                    runBlocking {
                        registry.register(ToolDefinition("skill_$name", desc, parameters = parameters,
                            executor = { p ->
                                when (val r = runBlocking { skillManager.executeSkill(name, p) }) {
                                    is AppResult.Success -> ToolResult(true, "OK")
                                    is AppResult.Failure -> ToolResult(false, "", error = r.error.humanMessage)
                                }
                            }))
                    }
                },
                unregister = { name ->
                    android.util.Log.e("NuclearBoy", "[DI] skill tool unregister callback — skillName=$name")
                    runBlocking { registry.unregister("skill_$name") }
                },
            )
        }

        android.util.Log.e("NuclearBoy", "[DI] provideToolRegistry — returning registry")
        return registry
    }

    @Provides
    @Singleton
    fun provideAgentEngine(
        apiClient: DeepSeekApiClient,
        toolRegistry: ToolRegistry,
        contextManager: ContextWindowManager,
        tokenTracker: TokenTracker,
        memoryStore: MemoryStore,
        pcBridgeClient: PcBridgeClient,
    ): AgentEngine {
        android.util.Log.e("NuclearBoy", "[DI] provideAgentEngine")
        val engine = AgentEngine(
            apiClient = apiClient,
            toolRegistry = toolRegistry,
            contextManager = contextManager,
            tokenTracker = tokenTracker,
            memoryStore = memoryStore,
        )
        // 用户取消对话时的清理回调
        engine.onCancel = {
            // Python 执行器暂不支持外部中断，正在执行的脚本会自然完成（结果丢弃）
            // 远程电脑任务：通知电脑端终止，避免白跑
            pcBridgeClient.cancelActiveTasksAsync()
        }
        return engine
    }

    // ── Tool Builder Helpers ──────────────────────────

    private fun buildFileOperationTools(fileOps: FileOperations): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "read_file",
                description = "读取项目中的文件内容。使用场景：1) 用户让你查看文件内容；2) 需要了解代码结构；3) 需要分析文件信息。参数 path 必须是具体文件路径，不能是目录。示例：path=\"README.md\"",
                parameters = listOf(
                    ToolParameter("path", "string", "文件路径。示例：README.md、src/main.py。不能传目录或 .", required = true),
                ),
                executor = { params ->
                    val path = params["path"] ?: params["filePath"] ?: params["filename"] ?: return@ToolDefinition ToolResult(false, error = "缺少 path 参数。如 path=\"README.md\"")
                    when (val result = kotlinx.coroutines.runBlocking { fileOps.readFile(path) }) {
                        is AppResult.Success -> {
                            val outLen = result.data.content?.length ?: 0
                            android.util.Log.e("NuclearBoy", "[DI] read_file SUCCESS — outputLen=$outLen")
                            ToolResult(
                                success = true,
                                output = result.data.content ?: "(空文件)",
                            )
                        }
                        is AppResult.Failure -> {
                            android.util.Log.e("NuclearBoy", "[DI] read_file FAILED — ${result.error.humanMessage}")
                            ToolResult(false, error = result.error.humanMessage)
                        }
                    }
                },
            ),
            ToolDefinition(
                name = "write_file",
                description = "创建新文件或覆盖已有文件。使用场景：1) 用户让你创建文件；2) 需要修改文件内容；3) 生成代码文件。会自动创建父目录。",
                parameters = listOf(
                    ToolParameter("path", "string", "文件路径。示例：hello.py、src/main.py", required = true),
                    ToolParameter("content", "string", "要写入的完整内容。示例：print('Hello World')", required = true),
                ),
                requiresConfirmation = true,
                executor = { params ->
                    val path = params["path"] ?: return@ToolDefinition ToolResult(false, error = "缺少 path 参数")
                    val content = params["content"] ?: return@ToolDefinition ToolResult(false, error = "缺少 content 参数")
                    // Capture the pre-write state.  The previous size-based
                    // heuristic classified every non-empty newly-created file
                    // as MODIFIED and every empty overwrite as CREATED.
                    val existedBefore = fileOps.pathExists(path)
                    when (val result = kotlinx.coroutines.runBlocking { fileOps.writeFile(path, content) }) {
                        is AppResult.Success -> {
                            val changeType = if (existedBefore) ChangeType.MODIFIED else ChangeType.CREATED
                            android.util.Log.e("NuclearBoy", "[DI] write_file SUCCESS — path=$path changeType=$changeType")
                            ToolResult(
                                success = true,
                                output = "文件已写入: $path",
                                fileChanges = listOf(FileChange(path, changeType, null)),
                            )
                        }
                        is AppResult.Failure -> {
                            android.util.Log.e("NuclearBoy", "[DI] write_file FAILED — ${result.error.humanMessage}")
                            ToolResult(false, error = result.error.humanMessage)
                        }
                    }
                },
            ),
            ToolDefinition(
                name = "list_directory",
                description = "列出目录中的文件和子目录。使用场景：1) 需要了解项目结构；2) 查看某目录下有什么文件；3) 不确定文件路径时先探索。参数 path 可选，默认为项目根目录。示例：list_directory() 或 list_directory(path=\"src\")",
                parameters = listOf(
                    ToolParameter("path", "string", "目录路径。示例：src、tests。默认为 . 即项目根目录", required = false, default = "."),
                ),
                executor = { params ->
                    val path = params["path"] ?: "."
                    when (val result = kotlinx.coroutines.runBlocking { fileOps.listDirectory(path) }) {
                        is AppResult.Success -> {
                            val listing = result.data.joinToString("\n") { f ->
                                val icon = if (f.isDirectory) "📁" else "📄"
                                "$icon ${f.name}  ${f.size.toFileSizeString()}"
                            }
                            android.util.Log.e("NuclearBoy", "[DI] list_directory SUCCESS — entries=${result.data.size}")
                            ToolResult(success = true, output = listing)
                        }
                        is AppResult.Failure -> {
                            android.util.Log.e("NuclearBoy", "[DI] list_directory FAILED — ${result.error.humanMessage}")
                            ToolResult(false, error = result.error.humanMessage)
                        }
                    }
                },
            ),
            ToolDefinition(
                name = "search_files",
                description = "按文件名搜索项目中的文件。使用场景：1) 找不到某个文件；2) 查找特定类型的文件；3) 搜索包含某关键词的文件名。query 是文件名的纯文本子串匹配（不是glob通配符，*不生效）。示例：search_files(query=\"README\") 可匹配 README.md",
                parameters = listOf(
                    ToolParameter("path", "string", "搜索关键词，纯文本子串匹配。示例：README、.py、test", required = true),
                ),
                executor = { params ->
                    val query = params["path"] ?: params["query"] ?: return@ToolDefinition ToolResult(false, error = "缺少 path 参数。示例：path=\"README\"")
                    when (val result = kotlinx.coroutines.runBlocking { fileOps.searchFiles(query) }) {
                        is AppResult.Success -> {
                            // 只输出文件名（不含绝对路径），避免设备内部路径暴露给模型造成混淆
                            val listing = result.data.joinToString("\n") { "📄 ${it.name}  ${it.path}" }
                            android.util.Log.e("NuclearBoy", "[DI] search_files SUCCESS — found=${result.data.size}")
                            ToolResult(success = true, output = listing.ifEmpty { "未找到匹配的文件" })
                        }
                        is AppResult.Failure -> {
                            android.util.Log.e("NuclearBoy", "[DI] search_files FAILED — ${result.error.humanMessage}")
                            ToolResult(false, error = result.error.humanMessage)
                        }
                    }
                },
            ),
            ToolDefinition(
                name = "create_project",
                description = "创建新项目。参数 name 是项目名称。示例：create_project(name=\"my-project\")",
                parameters = listOf(
                    ToolParameter("path", "string", "项目名称。示例：my-project、家庭作业", required = true),
                    ToolParameter("tech_stack", "string", "技术栈（逗号分隔，如 python,fastapi）", required = false),
                ),
                executor = { params ->
                    val name = params["name"] ?: params["path"] ?: params["projectName"] ?: return@ToolDefinition ToolResult(false, error = "缺少 name 参数。请提供项目名称，如 name=\"my-project\"")
                    val techStack = params["tech_stack"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                    when (val result = kotlinx.coroutines.runBlocking { fileOps.createProject(name, techStack) }) {
                        is AppResult.Success -> {
                            android.util.Log.e("NuclearBoy", "[DI] create_project SUCCESS — id=${result.data.id}, name=${result.data.name}")
                            ToolResult(
                                success = true,
                                output = "项目 '${result.data.name}' 创建成功！路径: ${result.data.rootPath}",
                            )
                        }
                        is AppResult.Failure -> {
                            android.util.Log.e("NuclearBoy", "[DI] create_project FAILED — ${result.error.humanMessage}")
                            ToolResult(false, error = result.error.humanMessage)
                        }
                    }
                },
            ),
        )
    }

    // remember 工具读/写 memory.json 用的 Json 实例——提升为字段避免每次工具调用重建
    private val memoryReadJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    private val memoryWriteJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    // 共享的轻量 HTTP 客户端（web_fetch 专用），复用连接池避免每次调用重建线程资源
    private val webFetchClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // web_fetch HTML 清洗正则——编译一次，每次调用复用
    private val scriptTagRegex = Regex("<script[^>]*>[\\s\\S]*?</script>", setOf(RegexOption.IGNORE_CASE))
    private val styleTagRegex  = Regex("<style[^>]*>[\\s\\S]*?</style>",  setOf(RegexOption.IGNORE_CASE))
    private val anyTagRegex    = Regex("<[^>]+>")
    private val whitespaceRegex = Regex("\\s+")

    private fun buildWebTools(sandbox: PythonSandbox) = listOf(
        ToolDefinition("web_search", "搜索互联网获取最新信息。DuckDuckGo+Bing双引擎，自动回退。使用场景：1) 用户询问最新新闻或实时信息；2) 需要查找技术资料；3) 需要了解某个话题。参数 query 是搜索关键词，max_results控制条数(1-8)。示例：web_search(query=\"Python 3.13 新特性\")",
            listOf(ToolParameter("path", "string", "搜索关键词。建议2-5个核心词，中文搜索加英文术语辅助。示例：Kotlin协程、Android 16 API变更", true),
                   ToolParameter("max_results", "integer", "返回结果数量，范围1-8。默认5", required = false, default = "5")),
            executor = { p ->
                val q = p["path"] ?: p["query"] ?: return@ToolDefinition ToolResult(false, error = "缺少 path 参数。示例：path=\"搜索关键词\"")
                val n = (p["max_results"]?.toIntOrNull() ?: 5).coerceIn(0, 8)
                if (q.isBlank()) return@ToolDefinition ToolResult(false, error = "query 不能为空，请输入搜索关键词")
                if (n == 0) return@ToolDefinition ToolResult(true, "(无结果：max_results=0)")
                android.util.Log.e("NuclearBoy", "[DI] web_search — query=$q, max_results=$n")
                val pyQuery = q.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n")
                val script = "__Q__ = '" + pyQuery + "'\n" +
                    "__N__ = " + n.toString() + "\n" +
                    """
import urllib.request, urllib.parse, re, time

def search_baidu(raw_query, n):
    try:
        url = "https://www.baidu.com/s?wd=" + urllib.parse.quote(raw_query) + "&rn=" + str(n)
        hdrs = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
                "Accept-Language": "zh-CN,zh;q=0.9"}
        req = urllib.request.Request(url, headers=hdrs)
        html = urllib.request.urlopen(req, timeout=8).read().decode('utf-8', errors='ignore')
        results = []
        for m in re.finditer(r'<h3[^>]*class="t"[^>]*>\s*<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>', html, re.I|re.S):
            link = m.group(1)
            title = re.sub(r'<[^>]+>', '', m.group(2)).strip()
            if title and len(title) > 4 and "baidu.com" not in link and len(link) < 300:
                results.append("- [{0}]({1})".format(title, link))
            if len(results) >= n: break
        if not results:
            for m in re.finditer(r'<div[^>]*class="[^"]*result[^"]*c-container[^"]*"[^>]*>(.*?)</div>', html, re.I|re.S):
                block = m.group(1)
                am = re.search(r'<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>', block, re.I|re.S)
                if am and "baidu.com" not in am.group(1) and len(am.group(1)) < 300:
                    title = re.sub(r'<[^>]+>', '', am.group(2)).strip()
                    if title and len(title) > 4:
                        results.append("- [{0}]({1})".format(title, am.group(1)))
                if len(results) >= n: break
        if not results:
            for m in re.finditer(r'<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>', html, re.I|re.S):
                link = m.group(1)
                title = re.sub(r'<[^>]+>', '', m.group(2)).strip()
                if title and len(title) > 6 and "baidu.com" not in link and len(link) < 300 \
                   and not link.endswith(('.css','.js','.png','.jpg','.gif','.ico')):
                    results.append("- [{0}]({1})".format(title, link))
                if len(results) >= n: break
        return results
    except:
        return []

def search_bing(raw_query, n):
    try:
        import http.cookiejar
        cj = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
        ascii_cnt = sum(1 for c in raw_query if ord(c) < 128)
        mkt = 'en-US' if len(raw_query) > 0 and ascii_cnt / len(raw_query) > 0.5 else 'zh-CN'
        params = urllib.parse.urlencode({'q': raw_query, 'count': str(n), 'mkt': mkt, 'setlang': 'zh-cn'})
        url = "https://www.bing.com/search?" + params
        hdrs = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"}
        req = urllib.request.Request(url, headers=hdrs)
        html = opener.open(req, timeout=12).read().decode('utf-8', errors='ignore')
        results = []
        for m in re.finditer(r'<h2[^>]*><a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a></h2>', html, re.I|re.S):
            link = m.group(1)
            title = re.sub(r'<[^>]+>', '', m.group(2)).strip()
            if title and len(title) > 4 and link.startswith("https://") and "bing.com" not in link and len(link) < 200:
                title = re.sub(r'^[a-zA-Z0-9.-]+\.[a-z]{2,}\s*[>]\s*', '', title)
                results.append("- [{0}]({1})".format(title, link))
            if len(results) >= n: break
        if not results:
            for m in re.finditer(r'<li class="b_algo"[^>]*>(.*?)</li>', html, re.I|re.S):
                block = m.group(1)
                lm = re.search(r'<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>', block, re.I|re.S)
                if lm and lm.group(1).startswith("https://") and "bing.com" not in lm.group(1):
                    title = re.sub(r'<[^>]+>', '', lm.group(2)).strip()
                    title = re.sub(r'^[a-zA-Z0-9.-]+\.[a-z]{2,}\s*[>]\s*', '', title)
                    if title and len(title) > 4:
                        results.append("- [{0}]({1})".format(title, lm.group(1)))
                if len(results) >= n: break
        return results
    except:
        return []

start_ms = int(time.time() * 1000)
cjk_cnt = sum(1 for c in __Q__ if '一' <= c <= '鿿' or '぀' <= c <= 'ヿ')
is_cjk = len(__Q__) > 0 and cjk_cnt / len(__Q__) > 0.3
if is_cjk:
    results = search_baidu(__Q__, __N__)
    engine = "Baidu"
    if not results:
        results = search_bing(__Q__, __N__)
        engine = "Baidu->Bing"
else:
    results = search_bing(__Q__, __N__)
    engine = "Bing"
    if not results:
        results = search_baidu(__Q__, __N__)
        engine = "Bing->Baidu"
elapsed_ms = int(time.time() * 1000) - start_ms
if results:
    for r in results:
        print(r)
    print("#meta: {0} results in {1}ms, engine={2}".format(len(results), elapsed_ms, engine))
else:
    print("#no_results: " + __Q__[:50])
                """.trimIndent()
                val r = runBlocking { sandbox.execute(script, ".") }
                val out = r.stdout.trim()
                val resultCount = out.lines().count { it.isNotBlank() }
                if (out.isNotBlank() && !out.startsWith("#no_results")) ToolResult(true, output = out.take(5000), error = r.stderr.ifBlank { null })
                else {
                    android.util.Log.e("NuclearBoy", "[DI] web_search NO RESULTS — stderr=${r.stderr.take(200)}")
                    ToolResult(false, error = "未找到结果: " + r.stderr.take(200))
                }
            }),
        ToolDefinition("web_fetch", "抓取网页的文本内容。使用场景：1) 需要阅读某个网页的详细内容；2) web_search 返回了链接需要深入了解；3) 用户提供了URL让你查看。参数 url 必须是完整的 https:// 链接。示例：web_fetch(url=\"https://example.com\")",
            listOf(ToolParameter("path", "string", "网页完整URL，必须以 https:// 开头。示例：https://example.com", true)),
            executor = { p ->
                val url = p["path"] ?: p["url"] ?: p["link"] ?: p["query"] ?: return@ToolDefinition ToolResult(false, error = "缺少 path 参数。示例：path=\"https://example.com\"")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return@ToolDefinition ToolResult(false, error = "URL 必须以 http:// 或 https:// 开头，收到: ${url.take(80)}")
                }
                try {
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 NUCLEAR-BOY/1.0").build()
                    val resp = webFetchClient.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    resp.close()
                    // 优先用 BeautifulSoup 提取正文（如果可用），否则回退到简单正则
                    val text = scriptTagRegex.replace(body, "")
                        .let { styleTagRegex.replace(it, "") }
                        .let { anyTagRegex.replace(it, " ") }
                        .let { whitespaceRegex.replace(it, " ") }
                        .trim().take(8000)
                    android.util.Log.e("NuclearBoy", "[DI] web_fetch SUCCESS — url=$url, bodyLen=${body.length}, textLen=${text.length}")
                    ToolResult(true, output = text)
                } catch (e: Exception) {
                    android.util.Log.e("NuclearBoy", "[DI] web_fetch FAILED — url=$url, error=${e.message}")
                    ToolResult(false, error = "抓取失败: " + e.message)
                }
            }),
    )

    private fun buildMemoryTools(fileOps: FileOperations) = listOf(
        ToolDefinition("remember", "记住重要信息。AI主动调用此工具将用户偏好、重要事实、经验教训写入长期记忆。记忆会立即出现在下一轮对话的系统提示词中。参数 path=要记住的内容, content=类别。示例：remember(path=\"用户喜欢简短回答\", content=\"偏好\")",
            listOf(
                ToolParameter("path", "string", "要记住的内容。示例：用户偏好Kotlin、项目用Compose", true),
                ToolParameter("content", "string", "类别标签。示例：偏好、事实、经验、提醒", required = false, default = "偏好"),
            ),
            executor = { p ->
                val value = p["path"] ?: return@ToolDefinition ToolResult(false, error = "缺少 path 参数")
                val category = p["content"] ?: "偏好"
                val memFile = java.io.File(fileOps.getWorkspaceRoot(), "__general__/.agent/memory.json")
                memFile.parentFile?.mkdirs()
                memoryFileMutex.withLock {
                    val memories: MutableList<Map<String, String>> = try {
                        memoryReadJson.decodeFromString<List<Map<String, String>>>(memFile.readText()).toMutableList()
                    } catch (_: Exception) { mutableListOf() }
                    memories.add(mapOf("value" to value, "category" to category, "time" to java.time.Instant.now().toString()))
                    // 只保留最近 50 条
                    val trimmed = memories.takeLast(50)
                    memFile.writeText(memoryWriteJson.encodeToString(kotlinx.serialization.serializer(), trimmed))
                    android.util.Log.e("NuclearBoy", "[DI] remember saved to ${memFile.absolutePath} total=${trimmed.size}")
                }
                ToolResult(true, "已记住: $value")
            }),
    )

    private fun buildRemotePcTools(
        appContext: Context,
        client: PcBridgeClient,
        configStore: PcBridgeConfigStore,
    ) = listOf(
        ToolDefinition(
            name = "pc_cli_run",
            description = "把编程任务下发给用户电脑上的 AI 编程 CLI（Claude Code / Codex / OpenCode）执行，返回执行结果。使用场景：1) 用户明确要求\"让电脑上的 claude/codex 做某事\"；2) 任务需要电脑上的完整开发环境（大型项目构建、桌面端工具链）；3) 用户想远程操作电脑里的代码仓库。前提是用户已在设置页开启并配置\"远程电脑\"。任务可能耗时数分钟，耐心等待结果。结果会返回会话 ID，对同一件事继续追问或迭代时把它传入 session 参数（仅 claude），CLI 就能记住之前的上下文。示例：pc_cli_run(cli=\"claude\", prompt=\"修复 D:/myproject 里的编译错误\", cwd=\"D:/myproject\")",
            parameters = listOf(
                ToolParameter("cli", "string", "用哪个编程 CLI。claude=Claude Code，codex=Codex，opencode=OpenCode", required = true, enum = listOf("claude", "codex", "opencode")),
                ToolParameter("prompt", "string", "下发给 CLI 的任务描述，要完整、自包含（CLI 在电脑上独立执行，看不到当前对话）", required = true),
                ToolParameter("cwd", "string", "电脑上的工作目录，如 D:/myproject。不传用电脑端默认目录", required = false),
                ToolParameter("timeout", "integer", "任务超时秒数（默认 600）", required = false, default = "600"),
                ToolParameter("session", "string", "上次结果里的会话 ID，或直接传 \"last\" 续传该 CLI 最近一次任务的会话，CLI 会记住之前的上下文", required = false),
                ToolParameter("isolate", "string", "传 \"true\" 时在 git 仓库旁创建隔离 worktree 执行，改动不碰主工作区，适合实验性修改或并行多任务", required = false, enum = listOf("true", "false")),
                ToolParameter("approval", "string", "传 \"ask\" 时（仅 claude）电脑端每个写文件/执行命令操作都弹到手机上让用户批准，适合高危或不放心的任务；默认 auto 自动放行", required = false, enum = listOf("auto", "ask")),
            ),
            executor = { params ->
                val cli = params["cli"] ?: ""
                val prompt = params["prompt"] ?: ""
                if (prompt.isBlank()) {
                    ToolResult.failure("缺少 prompt 参数，告诉我要让电脑做什么")
                } else {
                    val outputLines = mutableListOf<String>()
                    val result = client.runCliTask(
                        cli = cli,
                        prompt = prompt,
                        cwd = params["cwd"],
                        timeoutSec = params["timeout"]?.toIntOrNull() ?: PcBridgeClient.DEFAULT_TASK_TIMEOUT_SEC,
                        sessionId = params["session"],
                        useWorktree = params["isolate"] == "true",
                        approval = params["approval"]?.takeIf { it == "ask" },
                        onOutput = { kind, text ->
                            if (kind == "tool" || kind == "status") outputLines.add(text)
                            // 实时进度推给聊天界面的工具卡片
                            ToolProgressBus.report("pc_cli_run", text)
                        },
                        onPermissionRequest = { toolName, inputSummary ->
                            ToolProgressBus.report("pc_cli_run", "等待你在手机上批准: $toolName")
                            PermissionPromptBus.requestApproval(
                                taskId = "pc_cli_run", toolName = toolName, inputSummary = inputSummary,
                            )
                        },
                    )
                    when (result) {
                        is AppResult.Success -> {
                            val r = result.data
                            val process = if (outputLines.isEmpty()) "" else
                                "\n\n执行过程:\n" + outputLines.takeLast(20).joinToString("\n")
                            val sessionHint = if (r.sessionId.isBlank()) "" else
                                "\n\n会话 ID: ${r.sessionId}（继续这件事时传入 session 参数）"
                            val worktreeHint = if (r.worktreePath.isBlank()) "" else
                                "\n隔离执行：改动在 ${r.worktreePath}（分支 ${r.worktreeBranch}），确认满意后需要合并回主分支"
                            android.util.Log.e("NuclearBoy", "[DI] pc_cli_run done cli=$cli exit=${r.exitCode} ${r.durationMs}ms session=${r.sessionId.take(8)}")
                            // 后台也能知道好了没：任务结束发系统通知（借鉴 claudecodeui run-completed 推送）
                            PcTaskNotifier.notifyComplete(
                                appContext, cli, r.exitCode == 0, r.result, r.durationMs,
                            )
                            ToolResult(
                                success = r.exitCode == 0,
                                output = "电脑端 $cli 执行完成（${r.durationMs / 1000}s）:\n${r.result}$process$sessionHint$worktreeHint",
                                error = if (r.exitCode == 0) null else "CLI 退出码 ${r.exitCode}",
                            )
                        }
                        is AppResult.Failure -> {
                            android.util.Log.e("NuclearBoy", "[DI] pc_cli_run failed cli=$cli code=${result.error.code}")
                            PcTaskNotifier.notifyComplete(
                                appContext, cli, false,
                                result.technicalDetail ?: result.error.humanMessage, 0L,
                            )
                            ToolResult.failure(result.technicalDetail ?: result.error.humanMessage)
                        }
                    }
                }
            },
        ),
        ToolDefinition(
            name = "pc_task_list",
            description = "查看电脑上正在执行的远程任务列表（任务 ID、CLI、已运行时长、任务摘要）。使用场景：1) 用户问\"电脑上在跑什么\"；2) 下发新任务前确认电脑是否空闲；3) 配合会话续传查找之前的任务。",
            parameters = emptyList(),
            executor = { _ ->
                when (val result = client.listRunningTasks()) {
                    is AppResult.Success -> {
                        if (result.data.isEmpty()) {
                            ToolResult.success("电脑当前没有正在执行的远程任务")
                        } else {
                            val lines = result.data.joinToString("\n") { t ->
                                "· [${t.cli}] 已运行 ${t.elapsedMs / 1000}s — ${t.promptPreview}（id: ${t.id.take(8)}…，目录: ${t.cwd}）"
                            }
                            ToolResult.success("电脑上正在执行 ${result.data.size} 个任务:\n$lines")
                        }
                    }
                    is AppResult.Failure ->
                        ToolResult.failure(result.technicalDetail ?: result.error.humanMessage)
                }
            },
        ),
        ToolDefinition(
            name = "pc_list_dir",
            description = "列出电脑上某个目录的内容（文件名、是否目录、大小），只读不改动。使用场景：1) 下发任务前先看看项目结构；2) 用户问\"电脑上 D:/xxx 里有什么\"；3) 定位要读/要改的文件。前提是用户已配置\"远程电脑\"。",
            parameters = listOf(
                ToolParameter("path", "string", "电脑上的目录路径，如 D:/myproject。不传用默认工作目录", required = false),
            ),
            executor = { params ->
                when (val result = client.listDir(params["path"] ?: "")) {
                    is AppResult.Success -> {
                        val d = result.data
                        if (d.entries.isEmpty()) {
                            ToolResult.success("${d.path} 是空目录")
                        } else {
                            val lines = d.entries.joinToString("\n") { e ->
                                if (e.isDir) "📁 ${e.name}/" else "📄 ${e.name}（${e.size} 字节）"
                            }
                            val more = if (d.truncated) "\n…（条目过多已截断）" else ""
                            ToolResult.success("${d.path}:\n$lines$more")
                        }
                    }
                    is AppResult.Failure -> ToolResult.failure(result.technicalDetail ?: result.error.humanMessage)
                }
            },
        ),
        ToolDefinition(
            name = "pc_read_file",
            description = "读取电脑上某个文件的文本内容（只读，默认上限 64KB）。使用场景：1) 查看电脑上代码/配置文件内容来理解或规划改动；2) 用户问某个文件里写了什么。前提是用户已配置\"远程电脑\"。读大文件会被截断，必要时配合 pc_cli_run 处理。",
            parameters = listOf(
                ToolParameter("path", "string", "电脑上的文件路径，如 D:/myproject/build.gradle", required = true),
            ),
            executor = { params ->
                val path = params["path"] ?: ""
                if (path.isBlank()) {
                    ToolResult.failure("缺少 path 参数，告诉我要读电脑上哪个文件")
                } else when (val result = client.readFile(path)) {
                    is AppResult.Success -> {
                        val f = result.data
                        val note = if (f.truncated) "\n…（文件 ${f.size} 字节，超出已截断）" else ""
                        ToolResult.success("${f.path}（${f.size} 字节）:\n${f.content}$note")
                    }
                    is AppResult.Failure -> ToolResult.failure(result.technicalDetail ?: result.error.humanMessage)
                }
            },
        ),
        ToolDefinition(
            name = "pc_list_sessions",
            description = "列出电脑上 Claude Code 最近的历史会话（会话 ID、工作目录、首条消息预览、时间），用于找回之前的对话继续干。使用场景：1) 用户说\"继续昨天/之前在电脑上那个任务\"；2) 想接着某次远程会话迭代但忘了会话 ID。拿到 sessionId 后传给 pc_cli_run 的 session 参数即可续聊（仅 claude）。可传 cwd 只看某项目目录的会话。",
            parameters = listOf(
                ToolParameter("limit", "integer", "最多返回多少个会话（默认 20）", required = false, default = "20"),
                ToolParameter("cwd", "string", "只看这个工作目录下的会话，如 D:/myproject。不传则看全部", required = false),
            ),
            executor = { params ->
                val limit = params["limit"]?.toIntOrNull() ?: 20
                when (val result = client.listSessions(limit, params["cwd"]?.takeIf { it.isNotBlank() })) {
                    is AppResult.Success -> {
                        if (result.data.isEmpty()) {
                            ToolResult.success("电脑上没有找到 Claude 历史会话")
                        } else {
                            val lines = result.data.joinToString("\n") { s ->
                                "· ${s.preview.ifBlank { "(无预览)" }}（${s.cwd}，session: ${s.sessionId}）"
                            }
                            ToolResult.success("电脑上最近 ${result.data.size} 个 Claude 会话:\n$lines\n\n续聊：pc_cli_run(cli=\"claude\", session=\"<上面的 sessionId>\", prompt=\"...\")")
                        }
                    }
                    is AppResult.Failure -> ToolResult.failure(result.technicalDetail ?: result.error.humanMessage)
                }
            },
        ),
        ToolDefinition(
            name = "pc_write_file",
            description = "把文本内容写入电脑上的文件（覆盖或追加），用于直接落地小改动而不必起 CLI。这是会改动电脑文件的危险操作，每次都会弹到手机让用户确认后才执行。使用场景：1) 用户要你\"把这段代码写到电脑上的某文件\"；2) 创建/更新配置或脚本文件。父目录必须已存在（不自动创建）。复杂的多文件改动仍建议用 pc_cli_run 交给 claude/codex。",
            parameters = listOf(
                ToolParameter("path", "string", "电脑上的目标文件路径，如 D:/myproject/note.txt", required = true),
                ToolParameter("content", "string", "要写入的完整文本内容", required = true),
                ToolParameter("append", "string", "传 \"true\" 时追加到文件末尾，否则覆盖整个文件", required = false, enum = listOf("true", "false")),
            ),
            executor = { params ->
                val path = params["path"] ?: ""
                val content = params["content"] ?: ""
                val append = params["append"] == "true"
                if (path.isBlank()) {
                    ToolResult.failure("缺少 path 参数，告诉我要写到电脑上哪个文件")
                } else {
                    val action = if (append) "追加写入" else "覆盖写入"
                    val approved = PermissionPromptBus.requestApproval(
                        taskId = "pc_write_file",
                        toolName = "电脑写文件",
                        inputSummary = "$action $path（${content.length} 字符）",
                    )
                    if (!approved) {
                        ToolResult.failure("用户拒绝了写入电脑文件 $path")
                    } else when (val result = client.writeFile(path, content, append)) {
                        is AppResult.Success ->
                            ToolResult.success("已写入 ${result.data.path}（${result.data.bytes} 字节）✨")
                        is AppResult.Failure ->
                            ToolResult.failure(result.technicalDetail ?: result.error.humanMessage)
                    }
                }
            },
        ),
        ToolDefinition(
            name = "pc_bridge_status",
            description = "检查和电脑的连接状态，返回电脑主机名和可用的编程 CLI 版本。使用场景：1) 用户问\"电脑连上了吗\"；2) pc_cli_run 失败时排查连接。",
            parameters = emptyList(),
            executor = { _ ->
                if (!configStore.isEnabled()) {
                    ToolResult.failure("远程电脑功能未开启。提醒用户去 设置 → 远程电脑 打开开关并配置连接")
                } else {
                    when (val result = client.testConnection()) {
                        is AppResult.Success -> {
                            val info = result.data
                            val clis = info.clis.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
                            ToolResult.success("已连上电脑 ${info.host}，可用 CLI:\n$clis")
                        }
                        is AppResult.Failure ->
                            ToolResult.failure(result.technicalDetail ?: result.error.humanMessage)
                    }
                }
            },
        ),
    )
}
