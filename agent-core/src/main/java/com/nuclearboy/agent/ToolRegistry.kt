package com.nuclearboy.agent

import com.nuclearboy.api.deepseek.FunctionDefinitionDto
import com.nuclearboy.api.deepseek.ToolDefinitionDto
import com.nuclearboy.common.AppError
import com.nuclearboy.common.AppResult
import com.nuclearboy.common.ChangeType
import com.nuclearboy.common.FileChange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * A tool that the agent can invoke.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    val executor: suspend (Map<String, String>) -> ToolResult,
    val requiresConfirmation: Boolean = false,
)

/**
 * A single parameter for a tool.
 */
data class ToolParameter(
    val name: String,
    val type: String, // "string", "integer", "boolean"
    val description: String,
    val required: Boolean = true,
    val default: String? = null,
    val enum: List<String>? = null,
)

/**
 * Result of executing a tool.
 */
data class ToolResult(
    val success: Boolean,
    val output: String = "",
    val fileChanges: List<FileChange> = emptyList(),
    val error: String? = null,
) {
    companion object {
        fun success(output: String, fileChanges: List<FileChange> = emptyList()): ToolResult =
            ToolResult(success = true, output = output, fileChanges = fileChanges)

        fun failure(error: String): ToolResult =
            ToolResult(success = false, output = "", error = error)
    }
}

/**
 * Registry that manages all available tools for the agent.
 *
 * Thread-safe via [Mutex]. Converts registered tools into DeepSeek-compatible
 * [ToolDefinitionDto] lists and dispatches tool calls to the correct executor.
 *
 * Integration points:
 * - Skills module: skills can register their own tools
 * - PythonBridge module: document generation and script execution tools
 * - Tools-docgen module: Word/Excel file manipulation tools
 */
class ToolRegistry {

    private val mutex = Mutex()
    private val tools = mutableMapOf<String, ToolDefinition>()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    // 工具集运行期基本不变：缓存 DeepSeek schema 转换结果，仅在增删工具时失效，
    // 避免每条消息(每次 run)都持锁重建所有工具的 JSON schema。
    private var cachedDefs: List<ToolDefinitionDto>? = null
    private var cachedDefsMaxTokens: Long = -1L

    // ── External module references (set after construction) ──

    /** Optional reference to the Skills module for skill-based tool execution. */
    var skillsExecutor: (suspend (String, Map<String, String>) -> ToolResult)? = null

    /** Optional reference to PythonBridge for direct Python execution. */
    var pythonExecutor: (suspend (String, Map<String, String>) -> ToolResult)? = null

    // ── Registration ─────────────────────────────────────

    /**
     * Register a single tool. Overwrites if a tool with the same name already exists.
     */
    suspend fun register(tool: ToolDefinition): AppResult<Boolean> {
        android.util.Log.e("NuclearBoy", "[ToolReg] register() toolName=${tool.name}")
        mutex.withLock {
            tools[tool.name] = tool
            cachedDefs = null // 工具集变了，缓存失效
        }
        return AppResult.success(true)
    }

    /**
     * Register multiple tools at once.
     */
    suspend fun registerAll(newTools: List<ToolDefinition>): AppResult<Boolean> {
        android.util.Log.e("NuclearBoy", "[ToolReg] registerAll() toolCount=${newTools.size} names=${newTools.joinToString { it.name }}")
        mutex.withLock {
            newTools.forEach { tool ->
                tools[tool.name] = tool
            }
            cachedDefs = null // 工具集变了，缓存失效
        }
        return AppResult.success(true)
    }

    /**
     * Unregister a tool by name.
     */
    suspend fun unregister(name: String): AppResult<Boolean> {
        android.util.Log.e("NuclearBoy", "[ToolReg] unregister() toolName=$name")
        return mutex.withLock {
            if (tools.remove(name) != null) {
                cachedDefs = null // 工具集变了，缓存失效
                AppResult.success(true)
            } else {
                AppResult.failure(
                    AppError.SkillNotFound,
                    "工具 \"$name\" 未注册，无法移除"
                )
            }
        }
    }

    /**
     * Check if a tool is registered.
     */
    suspend fun hasTool(name: String): Boolean {
        mutex.withLock {
            return tools.containsKey(name)
        }
    }

    /**
     * Get a single tool definition by name.
     */
    suspend fun getTool(name: String): ToolDefinition? {
        mutex.withLock {
            val tool = tools[name]
            return tool
        }
    }

    /**
     * Get the count of registered tools.
     */
    suspend fun toolCount(): Int {
        mutex.withLock {
            return tools.size
        }
    }

    // ── DeepSeek API Conversion ──────────────────────────

    /**
     * Build a list of [ToolDefinitionDto] suitable for sending to the DeepSeek API.
     * Only returns tools that fit within the budget (AppConstants.BUDGET_TOOL_DEFINITIONS).
     * If the full list is too large, returns the most commonly used subset first.
     */
    suspend fun toDeepSeekToolDefinitions(maxTokens: Long = 5000): List<ToolDefinitionDto> {
        mutex.withLock {
            cachedDefs?.let { if (cachedDefsMaxTokens == maxTokens) return it }
            val totalTools = tools.size
            val result = mutableListOf<ToolDefinitionDto>()
            var estimatedTokens = 0L

            // Sort: prioritize Python/file tools first so they never get truncated
            val priorityTools = setOf("run_python", "read_file", "write_file", "list_directory")
            val sorted = tools.values.sortedBy { tool ->
                when {
                    tool.name in priorityTools -> 0  // Highest priority
                    tool.requiresConfirmation -> 2   // Confirmation-required tools last
                    else -> 1                         // Normal tools in middle
                }
            }

            for (tool in sorted) {
                val def = convertToDto(tool)
                val tokenEstimate = estimateToolDefTokens(def)
                if (estimatedTokens + tokenEstimate > maxTokens) break
                result.add(def)
                estimatedTokens += tokenEstimate
            }

            val excluded = totalTools - result.size
            android.util.Log.e("NuclearBoy", "[ToolReg] toDeepSeekToolDefinitions() total=$totalTools included=${result.size} excluded=$excluded budget=$maxTokens tokensUsed=$estimatedTokens")
            cachedDefs = result
            cachedDefsMaxTokens = maxTokens
            return result
        }
    }

    /**
     * Get tool definitions for specific tool names.
     */
    suspend fun toDeepSeekToolDefinitionsFor(vararg names: String): List<ToolDefinitionDto> {
        mutex.withLock {
            return names.mapNotNull { name ->
                tools[name]?.let { convertToDto(it) }
            }
        }
    }

    // ── Execution ────────────────────────────────────────

    /**
     * Execute a tool by name with the given parameters.
     *
     * Returns [AppResult.Failure] if the tool is not found.
     * Execution errors are captured in [ToolResult.error], not in the AppResult wrapper,
     * so the agent loop can feed the error back to the model as a tool response.
     */
    suspend fun execute(name: String, parameters: Map<String, String>): AppResult<ToolResult> {
        val tool = mutex.withLock { tools[name] }
        return executeResolved(tool, name, parameters)
    }

    /** Shared by [execute]/[executeSafe] so a resolved [ToolDefinition] is never looked up twice. */
    private suspend fun executeResolved(
        tool: ToolDefinition?,
        name: String,
        parameters: Map<String, String>,
    ): AppResult<ToolResult> {
        if (tool == null) {
            // Try external modules
            return executeViaExternalModule(name, parameters)
        }

        return AppResult.runCatching {
            tool.executor(parameters)
        }
    }

    /**
     * Execute a tool and return its result, feeding errors into the output
     * so the model can self-correct. Never returns a failure AppResult for
     * tool execution — failures are encoded as ToolResult(success=false).
     */
    suspend fun executeSafe(name: String, parameters: Map<String, String>): ToolResult {
        val startTime = System.currentTimeMillis()
        val toolDef = mutex.withLock { tools[name] }
        val result = when (val execResult = executeResolved(toolDef, name, parameters)) {
            is AppResult.Success -> execResult.data
            is AppResult.Failure -> {
                val paramHint = toolDef?.parameters?.filter { it.required }
                    ?.joinToString(", ") { "${it.name} (${it.type})" }
                val errorMsg = if (paramHint != null) {
                    "${execResult.error.humanMessage}。需要的参数: $paramHint"
                } else {
                    execResult.error.humanMessage
                }
                ToolResult.failure(errorMsg)
            }
        }
        val duration = System.currentTimeMillis() - startTime
        android.util.Log.e("NuclearBoy", "[ToolReg] executeSafe() $name: success=${result.success} dur=${duration}ms")
        return result
    }

    // ── Default Tools ────────────────────────────────────

    /**
     * Register the `run_python` tool.
     *
     * Its real implementation is wired in later via [pythonExecutor] (set by the
     * app module after Hilt DI finishes), so this must run before that — the
     * executor closure below reads the field lazily on each call, not at
     * registration time. Note: `read_file`/`write_file`/`search_files`/
     * `list_directory`/`web_search`/`web_fetch` used to have placeholder
     * registrations here too, but the app module always re-registers those
     * under the same names with real implementations right after this call —
     * `ToolRegistry.register`/`registerAll` overwrite by name, so the
     * placeholders here were pure dead weight and have been removed.
     */
    suspend fun registerDefaultTools() {
        android.util.Log.e("NuclearBoy", "[ToolReg] registerDefaultTools() entry")
        register(
            ToolDefinition(
                name = "run_python",
                description = "在 Python 3.11 执行器中直接运行 Python 代码并返回执行结果。你可以用它来运行脚本、测试代码、处理数据、生成文档等。",
                parameters = listOf(
                    ToolParameter("path", "string", "要执行的 Python 代码（完整脚本）", required = true),
                    ToolParameter("workingDir", "string", "工作目录", required = false, default = "."),
                    ToolParameter("scope", "string", "文件访问范围：project（当前项目，默认）或 global（外部存储全局读写，需开启所有文件访问权限）", required = false, default = "project", enum = listOf("project", "global")),
                    ToolParameter("timeout", "integer", "超时秒数（默认 120）", required = false, default = "120"),
                ),
                requiresConfirmation = false,
                executor = { params ->
                    // Attempt Python executor if available
                    pythonExecutor?.let { exec ->
                        exec("run_python", params)
                    } ?: ToolResult.failure("Python 运行时未初始化")
                },
            )
        )
        android.util.Log.e("NuclearBoy", "[ToolReg] registerDefaultTools() registered run_python")
    }

    // ── Private ──────────────────────────────────────────

    private fun convertToDto(tool: ToolDefinition): ToolDefinitionDto {
        // Build the JSON Schema parameters object
        val propertiesJson = JsonObject(
            tool.parameters.associate { param ->
                param.name to buildParamSchemaJson(param)
            }
        )

        val requiredList = tool.parameters
            .filter { it.required }
            .map { it.name }

        val paramsMap = mutableMapOf<String, JsonElement?>()
        paramsMap["type"] = JsonPrimitive("object")
        paramsMap["properties"] = propertiesJson
        if (requiredList.isNotEmpty()) {
            paramsMap["required"] = JsonArray(requiredList.map { JsonPrimitive(it) })
        }
        paramsMap["additionalProperties"] = JsonPrimitive(false)

        @Suppress("UNCHECKED_CAST")
        val paramsObj = JsonObject(paramsMap.filterValues { it != null } as Map<String, JsonElement>)

        return ToolDefinitionDto(
            function = FunctionDefinitionDto(
                name = tool.name,
                description = if (tool.requiresConfirmation) {
                    "${tool.description}\n[⚠️ 此操作需要用户确认]"
                } else {
                    tool.description
                },
                parameters = paramsObj,
            )
        )
    }

    private fun buildParamSchemaJson(param: ToolParameter): JsonObject {
        val fields = mutableMapOf<String, JsonElement?>()
        fields["type"] = JsonPrimitive(param.type)
        fields["description"] = JsonPrimitive(param.description)

        if (param.default != null) {
            fields["default"] = when (param.type) {
                "integer" -> {
                    val num = param.default.toLongOrNull()
                    if (num != null) JsonPrimitive(num) else JsonPrimitive(param.default)
                }
                "boolean" -> {
                    val bool = param.default.toBooleanStrictOrNull()
                    if (bool != null) JsonPrimitive(bool) else JsonPrimitive(param.default)
                }
                else -> JsonPrimitive(param.default)
            }
        }

        if (param.enum != null) {
            fields["enum"] = JsonArray(param.enum.map { JsonPrimitive(it) })
        }

        @Suppress("UNCHECKED_CAST")
        val safeFields = fields.filterValues { it != null } as Map<String, JsonElement>
        return JsonObject(safeFields)
    }

    private fun estimateToolDefTokens(def: ToolDefinitionDto): Long {
        val text = def.function.name + def.function.description +
                (def.function.parameters?.toString() ?: "")
        return (text.length / 3.5).toLong().coerceAtLeast(20)
    }

    private suspend fun executeViaExternalModule(
        name: String,
        params: Map<String, String>,
    ): AppResult<ToolResult> {
        // Try Python executor for tool names that look like Python tools
        if (name in listOf("run_python", "execute_shell")) {
            pythonExecutor?.let { exec ->
                return AppResult.runCatching { exec(name, params) }
            }
        }

        // Try skills executor as fallback
        skillsExecutor?.let { exec ->
            return AppResult.runCatching { exec(name, params) }
        }

        return AppResult.failure(
            AppError.SkillNotFound,
            "找不到工具 \"$name\"。检查一下工具名是否拼写正确？"
        )
    }

    /**
     * Serialize parameters map into a JSON string for tool call DTOs.
     */
    fun paramsToJson(params: Map<String, String>): String {
        return json.encodeToString(
            kotlinx.serialization.serializer<Map<String, String>>(),
            params
        )
    }
}
