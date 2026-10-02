package com.nuclearboy.skills

import com.nuclearboy.common.AppConstants
import com.nuclearboy.common.AppError
import com.nuclearboy.common.AppResult
import com.nuclearboy.common.SkillInfo
import com.nuclearboy.python.PythonResult
import com.nuclearboy.python.PythonSandbox
import com.nuclearboy.python.SandboxPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages the full lifecycle of installable skills:
 * - Discovery & parsing of skill.yaml manifests
 * - Installation from local paths or remote URLs
 * - Python dependency installation via the Python executor
 * - Execution of skill entry points
 * - Registration as agent tools via [ToolRegistry][com.nuclearboy.agent.ToolRegistry]
 *
 * Thread-safe: all public suspend functions switch to IO dispatcher.
 */
class SkillManager(
    private val pythonSandbox: PythonSandbox,
    val skillsDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    /** Callback invoked when a skill should be registered as an agent tool. */
    var onToolRegister: ((String, String, Map<String, String>) -> Unit)? = null

    /** Callback invoked when a skill should be unregistered from agent tools. */
    var onToolUnregister: ((String) -> Unit)? = null

    /**
     * Callback delivery is serialized so that initial registration cannot race
     * with a skill install/reload.  The set also prevents the same skill from
     * being registered twice when the manager is wired after its first scan.
     */
    private val toolCallbackMutex = Mutex()
    private val registeredToolNames = mutableSetOf<String>()
    private val registeredToolDescriptions = mutableMapOf<String, String>()

    private val _installedSkills = MutableStateFlow<List<InstalledSkill>>(emptyList())
    val installedSkills: StateFlow<List<InstalledSkill>> = _installedSkills.asStateFlow()

    private val _activeSkills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val activeSkills: StateFlow<List<SkillInfo>> = _activeSkills.asStateFlow()

    private var currentProjectSkillsDir: File? = null
    private val projectSkillNames = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val projectSkillsMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // YAML 解析：折叠块内容的空白合并只编译一次
    private val yamlFoldedWhitespaceRegex = Regex("\\s+")

    private val initialRefreshJob: Job

    init {
        android.util.Log.e("NuclearBoy", "[SkillMgr] init() skillsDir=${skillsDir.absolutePath}")
        initialRefreshJob = scope.launch {
            refreshSkills()
            updateActiveSkills()
        }
    }

    /**
     * Wire the tool callbacks and synchronously publish all skills discovered
     * during construction.  SkillManager starts its first scan in [init], so
     * assigning callbacks after construction used to lose those registrations
     * (and a second ad-hoc refresh could race with installs).  Waiting for that
     * scan, installing callbacks, refreshing once, and registering under one
     * mutex gives the DI layer a deterministic ready point.
     */
    suspend fun configureToolCallbacks(
        register: ((String, String, Map<String, String>) -> Unit)?,
        unregister: ((String) -> Unit)?,
    ) = withContext(Dispatchers.IO) {
        initialRefreshJob.join()
        toolCallbackMutex.withLock {
            onToolRegister = register
            onToolUnregister = unregister
            registeredToolNames.clear()
            registeredToolDescriptions.clear()

            // Pick up files created while the initial asynchronous scan was
            // running, then expose every currently installed skill exactly once.
            refreshSkills()
            _installedSkills.value.forEach { skill ->
                registerToolLocked(
                    skill.manifest.name,
                    skill.manifest.description,
                    emptyMap(),
                    force = false,
                )
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────

    /**
     * Load skills from a project's .agent/skills/ directory.
     * Unloads any previously loaded project skills first, then
     * scans and registers skills from the new directory.
     */
    fun loadProjectSkills(projectSkillsDir: File) {
        android.util.Log.e("NuclearBoy", "[SkillMgr] loadProjectSkills() dir=${projectSkillsDir.absolutePath}")
        scope.launch { reloadProjectSkills(projectSkillsDir) }
    }

    /**
     * Reload a project's skills and wait until [activeSkills] has been updated.
     *
     * Skill Creator writes files through a Python skill, whose result does not
     * carry FileChange records. Callers handling that tool result therefore
     * need a synchronous refresh so the current chat/sidebar reflects the new
     * skill before the next turn starts.
     */
    suspend fun reloadProjectSkills(projectSkillsDir: File) = withContext(Dispatchers.IO) {
        projectSkillsMutex.withLock {
            android.util.Log.e("NuclearBoy", "[SkillMgr] reloadProjectSkills() dir=${projectSkillsDir.absolutePath}")
            // 1. Unload previous project skills
            unloadProjectSkillsInternal()
            // 2. Set new project dir
            currentProjectSkillsDir = projectSkillsDir.also { it.mkdirs() }
            // 3. Scan and register project skills
            val skillDirs = projectSkillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            android.util.Log.e("NuclearBoy", "[SkillMgr] reloadProjectSkills() found ${skillDirs.size} skill directories")
            skillDirs.forEach { dir ->
                val yamlFile = dir.resolve("skill.yaml")
                if (yamlFile.isFile) {
                    val manifest = parseManifest(yamlFile.readText())
                    if (manifest != null) {
                        android.util.Log.e("NuclearBoy", "[SkillMgr] reloadProjectSkills() registered project skill: ${manifest.name}")
                        projectSkillNames.add(manifest.name)
                        registerTool(manifest.name, manifest.description, emptyMap())
                    }
                }
            }
            // 4. Refresh combined list
            refreshSkills()
            updateActiveSkills()
        }
    }

    /** Unload all project-specific skills. */
    fun unloadProjectSkills() {
        android.util.Log.e("NuclearBoy", "[SkillMgr] unloadProjectSkills() unloading ${projectSkillNames.size} skills")
        scope.launch {
            withContext(Dispatchers.IO) {
                projectSkillsMutex.withLock {
                    unloadProjectSkillsInternal()
                    currentProjectSkillsDir = null
                    refreshSkills()
                    updateActiveSkills()
                }
            }
        }
    }

    private suspend fun unloadProjectSkillsInternal() {
        android.util.Log.e("NuclearBoy", "[SkillMgr] unloadProjectSkillsInternal() count=${projectSkillNames.size}")
        projectSkillNames.forEach { name ->
            // The callback receives the manifest name; the DI layer owns the
            // public tool-name prefix so it is applied exactly once.
            unregisterTool(name)
        }
        projectSkillNames.clear()
    }

    private fun updateActiveSkills() {
        _activeSkills.value = _installedSkills.value.map { skill ->
            SkillInfo(
                name = skill.manifest.name,
                description = skill.manifest.description,
                isProjectSkill = skill.manifest.name in projectSkillNames,
            )
        }
        android.util.Log.e("NuclearBoy", "[SkillMgr] updateActiveSkills() totalActive=${_activeSkills.value.size}")
    }

    /**
     * (Re-)scan the skills directory for installed skills and update [installedSkills].
     * Called automatically at construction and after install/uninstall.
     */
    suspend fun refreshSkills() = withContext(Dispatchers.IO) {
        val skills = mutableListOf<InstalledSkill>()
        skillsDir.mkdirs()

        skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val yamlFile = dir.resolve("skill.yaml")
                if (yamlFile.isFile) {
                    parseAndEmitSkill(yamlFile, dir)?.let { skills.add(it) }
                }
            }

        // Also scan project skills dir (for listing purposes only)
        currentProjectSkillsDir?.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val yamlFile = dir.resolve("skill.yaml")
                if (yamlFile.isFile) {
                    val existing = skills.find { it.manifest.name == dir.name }
                    if (existing == null) {
                        parseAndEmitSkill(yamlFile, dir)?.let { skills.add(it) }
                    }
                }
            }

        _installedSkills.value = skills.toList()
        android.util.Log.e("NuclearBoy", "[SkillMgr] refreshSkills() skillsFound=${skills.size}")
        updateActiveSkills()
    }

    /**
     * Install a skill from a local directory path.
     * The directory must contain a valid skill.yaml manifest.
     */
    suspend fun installFromPath(path: String): AppResult<InstalledSkill> =
        withContext(Dispatchers.IO) {
            android.util.Log.e("NuclearBoy", "[SkillMgr] installSkill() source=path path=$path")
            val sourceDir = File(path)
            if (!sourceDir.isDirectory) {
                return@withContext AppResult.failure(
                    AppError.FileNotFound,
                    "Skill directory not found: $path"
                )
            }

            val yamlFile = sourceDir.resolve("skill.yaml")
            if (!yamlFile.isFile) {
                return@withContext AppResult.failure(
                    AppError.SkillInstallFailed,
                    "Missing skill.yaml in $path"
                )
            }

            val manifest = parseManifest(yamlFile.readText())
                ?: return@withContext AppResult.failure(
                    AppError.SkillInstallFailed,
                    "Failed to parse skill.yaml"
                )

            installSkillInternal(sourceDir, manifest)
        }

    /**
     * Install a skill from a remote URL (e.g. GitHub zip archive).
     * Downloads, extracts, and installs the skill.
     */
    suspend fun installFromUrl(url: String): AppResult<InstalledSkill> =
        withContext(Dispatchers.IO) {
            android.util.Log.e("NuclearBoy", "[SkillMgr] installSkill() source=url url=$url")
            try {
                val tempDir = File(skillsDir, ".tmp_${System.currentTimeMillis()}")
                tempDir.mkdirs()

                downloadAndExtract(url, tempDir)

                // Find the skill.yaml — some archives nest the skill one level deep
                val yamlCandidates = tempDir.walkTopDown()
                    .filter { it.name == "skill.yaml" }
                    .toList()

                if (yamlCandidates.isEmpty()) {
                    tempDir.deleteRecursively()
                    return@withContext AppResult.failure(
                        AppError.SkillInstallFailed,
                        "No skill.yaml found in archive"
                    )
                }

                val yamlFile = yamlCandidates.first()
                val skillRoot = yamlFile.parentFile!!

                val manifest = parseManifest(yamlFile.readText())
                    ?: run {
                        tempDir.deleteRecursively()
                        return@withContext AppResult.failure(
                            AppError.SkillInstallFailed,
                            "Failed to parse skill.yaml from archive"
                        )
                    }

                // If manifest name is missing/broken, derive from root dir
                val resolvedManifest = if (manifest.name.isBlank()) {
                    manifest.copy(name = skillRoot.name)
                } else {
                    manifest
                }

                val result = installSkillInternal(skillRoot, resolvedManifest)
                tempDir.deleteRecursively()
                result
            } catch (e: Exception) {
                AppResult.failure(
                    AppError.SkillInstallFailed,
                    "Download failed: ${e.message}"
                )
            }
        }

    /**
     * Uninstall a skill by name. Removes its directory and Python packages.
     */
    suspend fun uninstall(skillName: String): AppResult<Boolean> =
        withContext(Dispatchers.IO) {
            val skill = findSkillDir(skillName)
                ?: return@withContext AppResult.failure(
                    AppError.SkillNotFound,
                    "Skill '$skillName' is not installed"
                )

            if (!skill.deleteRecursively()) {
                return@withContext AppResult.failure(
                    AppError.FileWriteDenied,
                    "Failed to delete skill directory for '$skillName'"
                )
            }

            // Keep the unregister callback contract consistent with project
            // skill unloading: pass the manifest name without the tool prefix.
            unregisterTool(skillName)
            refreshSkills()
            AppResult.success(true)
        }

    /**
     * Update a skill by re-installing. If the skill was installed from a URL
     * stored in its metadata file, re-downloads from there; otherwise is a no-op
     * with a warning.
     */
    suspend fun update(skillName: String): AppResult<InstalledSkill> =
        withContext(Dispatchers.IO) {
            val existing = findSkillDir(skillName)
                ?: return@withContext AppResult.failure(
                    AppError.SkillNotFound,
                    "Skill '$skillName' is not installed"
                )

            val metaFile = existing.resolve(".install-meta.json")
            if (!metaFile.isFile) {
                return@withContext AppResult.failure(
                    AppError.Unknown,
                    "No install source recorded for '$skillName'; re-install from path/URL instead"
                )
            }

            val meta = try {
                json.decodeFromString<InstallMeta>(metaFile.readText())
            } catch (_: Exception) {
                return@withContext AppResult.failure(
                    AppError.Unknown,
                    "Install metadata is corrupt for '$skillName'"
                )
            }

            // Uninstall first
            uninstall(skillName)

            // Re-install from recorded source
            when (meta.sourceType) {
                "url" -> installFromUrl(meta.sourceUrl ?: "")
                "path" -> installFromPath(meta.sourcePath ?: "")
                else -> AppResult.failure(AppError.Unknown, "Unknown source type: ${meta.sourceType}")
            }
        }

    /**
     * Execute a skill by name with the given parameters.
     * Constructs a Python invocation of the skill's entry point.
     */
    suspend fun executeSkill(
        name: String,
        params: Map<String, String>,
    ): AppResult<PythonResult> = withContext(Dispatchers.IO) {
        android.util.Log.e("NuclearBoy", "[SkillMgr] executeSkill() name=$name params=${params.keys}")
        val skill = getSkill(name)
            ?: return@withContext AppResult.failure(
                AppError.SkillNotFound,
                "Skill '$name' not found"
            )

        // Validate required parameters
        for (param in skill.manifest.parameters) {
            val value = params[param.name]
            if (!param.validate(value)) {
                return@withContext AppResult.failure(
                    AppError.Unknown,
                    "Invalid value for parameter '${param.name}' (expected ${param.type})"
                )
            }
        }

        val (moduleName, functionName) = parseEntryPoint(skill.manifest.entryPoint)

        val paramsJson = Json.encodeToString(
            kotlinx.serialization.serializer<Map<String, String>>(),
            params,
        )
        val pythonScript = buildSkillRunnerScript(
            skillPath = skill.installPath,
            moduleName = moduleName,
            functionName = functionName,
        )

        // Params are passed via env var (not string-interpolated into the script)
        // so that untrusted parameter values can't break out of the Python source
        // and inject arbitrary code — ChaquopyPythonExecutor escapes env values safely.
        val result = pythonSandbox.execute(
            pythonScript,
            skill.installPath,
            env = mapOf("NB_SKILL_PARAMS_JSON" to paramsJson),
            policy = buildSkillPolicy(skill),
        )

        if (result.exitCode != 0) {
            android.util.Log.e("NuclearBoy", "[SkillMgr] executeSkill() error: ${skill.manifest.name} exitCode=${result.exitCode} stderr=${result.stderr}")
            return@withContext AppResult.failure(
                AppError.PythonRuntimeError,
                "Skill '${skill.manifest.name}' failed with exit code ${result.exitCode}: ${result.stderr}"
            )
        }

        android.util.Log.e("NuclearBoy", "[SkillMgr] executeSkill() success: ${skill.manifest.name} outputLen=${result.stdout.length}")
        AppResult.success(result)
    }

    /**
     * Retrieve an installed skill by name, or null.
     */
    fun getSkill(name: String): InstalledSkill? {
        return _installedSkills.value.find {
            it.manifest.name.equals(name, ignoreCase = true)
        }
    }

    /** Directory containing the selected skill, including project skills. */
    fun getSkillDirectory(name: String): File? = getSkill(name)?.installDir

    // ──────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────

    private suspend fun registerTool(
        name: String,
        description: String,
        metadata: Map<String, String>,
        force: Boolean = false,
    ) = toolCallbackMutex.withLock {
        registerToolLocked(name, description, metadata, force)
    }

    private fun registerToolLocked(
        name: String,
        description: String,
        metadata: Map<String, String>,
        force: Boolean,
    ) {
        val callback = onToolRegister ?: return
        val wasRegistered = registeredToolNames.contains(name)
        // An install can overlap the initial configure call.  If both paths
        // describe the same tool, the second delivery is redundant; force is
        // still honoured when an actual reinstall changes its description.
        if (wasRegistered && (!force || registeredToolDescriptions[name] == description)) return
        try {
            callback.invoke(name, description, metadata)
            registeredToolNames.add(name)
            registeredToolDescriptions[name] = description
        } catch (error: Throwable) {
            // Keep the marker clear when the first delivery failed so a later
            // configure/install can retry instead of silently dropping it.
            if (!wasRegistered) {
                registeredToolNames.remove(name)
                registeredToolDescriptions.remove(name)
            }
            throw error
        }
    }

    private suspend fun unregisterTool(name: String) = toolCallbackMutex.withLock {
        if (registeredToolNames.remove(name)) {
            registeredToolDescriptions.remove(name)
            onToolUnregister?.invoke(name)
        }
    }

    private suspend fun installSkillInternal(
        sourceDir: File,
        manifest: SkillManifest,
    ): AppResult<InstalledSkill> {
        android.util.Log.e("NuclearBoy", "[SkillMgr] installSkillInternal() name=${manifest.name} version=${manifest.version}")
        // Check for conflicts
        val existing = findSkillDir(manifest.name)
        if (existing != null) {
            android.util.Log.e("NuclearBoy", "[SkillMgr] installSkillInternal() overwriting existing ${manifest.name}")
            // Overwrite: remove existing
            if (!existing.deleteRecursively()) {
                return AppResult.failure(
                    AppError.FileWriteDenied,
                    "Failed to remove existing installation of '${manifest.name}'"
                )
            }
        }

        val targetDir = File(skillsDir, manifest.name)
        android.util.Log.e("NuclearBoy", "[SkillMgr] installSkillInternal() copying to ${targetDir.absolutePath}")
        sourceDir.copyRecursively(targetDir, overwrite = true)

        // Install pip dependencies if a requirements.txt exists
        val requirementsFile = targetDir.resolve("requirements.txt")
        if (requirementsFile.isFile) {
            android.util.Log.e("NuclearBoy", "[SkillMgr] installSkillInternal() installing pip dependencies for ${manifest.name}")
            val allowedPackages = manifest.permissions.packages?.allowed ?: emptyList()
            val installResult = installDependencies(targetDir, requirementsFile, allowedPackages)
            if (installResult.isFailure) {
                // Rollback: remove the skill directory
                targetDir.deleteRecursively()
                return installResult as AppResult<InstalledSkill>
            }
        }

        // Write install metadata
        val meta = InstallMeta(
            sourceType = "path",
            sourcePath = sourceDir.absolutePath,
            installedAt = System.currentTimeMillis(),
        )
        targetDir.resolve(".install-meta.json")
            .writeText(json.encodeToString(InstallMeta.serializer(), meta))

        val installed = InstalledSkill(
            manifest = manifest,
            installPath = targetDir.absolutePath,
            installedAt = System.currentTimeMillis(),
            isActive = true,
            version = manifest.version,
        )

        // Register as a tool
        android.util.Log.e("NuclearBoy", "[SkillMgr] installSkillInternal() registering tool for ${installed.manifest.name}")
        // Re-installing an existing skill may change its description or
        // parameters, so deliberately replace the previous tool definition.
        registerTool(
            installed.manifest.name,
            installed.manifest.description,
            emptyMap(),
            force = true,
        )

        refreshSkills()
        android.util.Log.e("NuclearBoy", "[SkillMgr] installSkillInternal() success: ${manifest.name}")
        return AppResult.success(installed)
    }

    private suspend fun installDependencies(
        skillDir: File,
        requirementsFile: File,
        allowedPackages: List<String>,
    ): AppResult<Unit> {
        val requirements = requirementsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (requirements.isEmpty()) return AppResult.success(Unit)

        // Validate against allowed list
        if (allowedPackages.isNotEmpty()) {
            for (req in requirements) {
                val pkgName = req.split("==", ">=", "<=", "~=", "!=", ">", "<").first().trim()
                val isAllowed = allowedPackages.any { allowed ->
                    pkgName.equals(allowed, ignoreCase = true) ||
                            allowed == "*" ||
                            (allowed.startsWith("*") && pkgName.contains(allowed.removePrefix("*")))
                }
                if (!isAllowed) {
                    return AppResult.failure(
                        AppError.SkillPermissionDenied,
                        "Package '$pkgName' is not in the skill's allowed packages list"
                    )
                }
            }
        }

        val pipScript = """
import subprocess
import sys
import os

os.chdir(r"${skillDir.absolutePath.replace("\\", "\\\\")}")
result = subprocess.run(
    [sys.executable, "-m", "pip", "install", "-r", "requirements.txt"],
    capture_output=True,
    text=True
)
print(result.stdout)
if result.stderr:
    print(result.stderr, file=sys.stderr)
sys.exit(result.returncode)
        """.trimIndent()

        val result = pythonSandbox.execute(pipScript, "")
        return if (result.exitCode == 0) {
            AppResult.success(Unit)
        } else {
            AppResult.failure(
                AppError.PythonPackageError,
                "pip install failed: ${result.stderr}"
            )
        }
    }

    /**
     * Derive a [SandboxPolicy] from the skill's declared manifest permissions.
     * A skill that only requests workspace-relative filesystem access (the
     * default) is confined strictly to its own install directory; a skill
     * that explicitly requests paths outside its workspace falls back to
     * the broader `standard` policy scoped to its install dir, since the
     * declarative glob permissions aren't translated to absolute paths here.
     * Either way, network/shell/package permissions are always honored exactly
     * as declared — never wider than what the skill manifest requests.
     */
    private fun buildSkillPolicy(skill: InstalledSkill): SandboxPolicy {
        val perms = skill.manifest.permissions
        val networkAllowed = perms.network?.allowed == true
        val shellAllowed = perms.shell?.allowed == true
        val allowedPackages = perms.packages?.allowed ?: emptyList()
        val fsWorkspaceOnly = perms.filesystem == null ||
            (perms.filesystem.read.all { it.startsWith("workspace") } &&
                perms.filesystem.write.all { it.startsWith("workspace") })

        return if (fsWorkspaceOnly) {
            SandboxPolicy(
                allowedReadPaths = listOf(skill.installPath),
                allowedWritePaths = listOf(skill.installPath),
                networkAllowed = networkAllowed,
                allowedPackages = allowedPackages,
                shellAllowed = shellAllowed,
            )
        } else {
            SandboxPolicy.standard(skill.installPath).copy(
                networkAllowed = networkAllowed,
                allowedPackages = allowedPackages,
                shellAllowed = shellAllowed,
            )
        }
    }

    private fun buildSkillRunnerScript(
        skillPath: String,
        moduleName: String,
        functionName: String,
    ): String {
        return """
import sys
import json
import os

# Add skill directory to path
skill_dir = r"${skillPath.replace("\\", "\\\\")}"
if skill_dir not in sys.path:
    sys.path.insert(0, skill_dir)

# Change working directory
os.chdir(skill_dir)

# Parse parameters — passed via env var, not interpolated into source,
# so untrusted parameter values can't break out and inject Python code
params = json.loads(os.environ["NB_SKILL_PARAMS_JSON"])

# Import and run the entry point
import importlib
module = importlib.import_module("${moduleName}")
func = getattr(module, "${functionName}")
result = func(**params)

# Print result for capture
if result is not None:
    if isinstance(result, str):
        print(result)
    else:
        print(json.dumps(result, ensure_ascii=False, default=str))
        """.trimIndent()
    }

    private fun findSkillDir(name: String): File? {
        val dir = File(skillsDir, name)
        return if (dir.isDirectory) dir else null
    }

    private fun parseAndEmitSkill(yamlFile: File, dir: File): InstalledSkill? {
        val manifest = parseManifest(yamlFile.readText()) ?: return null
        val metaFile = dir.resolve(".install-meta.json")
        val installedAt = if (metaFile.isFile) {
            try {
                val meta = json.decodeFromString<InstallMeta>(metaFile.readText())
                meta.installedAt
            } catch (_: Exception) {
                dir.lastModified()
            }
        } else {
            dir.lastModified()
        }

        return InstalledSkill(
            manifest = manifest,
            installPath = dir.absolutePath,
            installedAt = installedAt,
            isActive = true,
            version = manifest.version,
        )
    }

    // ──────────────────────────────────────────────
    //  YAML parsing (minimal, dependency-free)
    // ──────────────────────────────────────────────

    /**
     * Parses a minimal YAML subset sufficient for skill.yaml files.
     * Supports: top-level scalars, nested mappings (2 levels), lists of strings,
     * and block scalars (|, >).
     * Does NOT support: anchors, tags, complex nesting.
     */
    private fun parseManifest(yaml: String): SkillManifest? {
        return try {
            val root = parseYamlNode(yaml)
            val manifest = manifestFromNode(root)
            android.util.Log.e("NuclearBoy", "[SkillMgr] parseManifest() success name=${manifest.name}")
            manifest
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[SkillMgr] parseManifest() failed: ${e.message} yamlSnippet=${yaml.take(200)}")
            null
        }
    }

    private data class YamlNode(
        val map: MutableMap<String, Any?> = mutableMapOf(),
    )

    private fun parseYamlNode(yaml: String): YamlNode {
        val node = YamlNode()
        val lines = yaml.lines()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }

            // Check if this starts a new top-level key
            if (!line.startsWith(" ") && !line.startsWith("\t") && ':' in trimmed) {
                val colonIdx = trimmed.indexOf(':')
                val key = trimmed.substring(0, colonIdx).trim()
                val valuePart = trimmed.substring(colonIdx + 1).trim()

                // Check for block scalar indicators: |, |-, |+, >, >-, >+
                val isBlockScalar = valuePart == "|" || valuePart == "|-" || valuePart == "|+" ||
                                    valuePart == ">" || valuePart == ">-" || valuePart == ">+"
                val isFolded = valuePart.startsWith(">") // > folds newlines to spaces

                if (isBlockScalar) {
                    // Read indented block scalar content
                    val blockLines = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val subLine = lines[i]
                        val subTrimmed = subLine.trim()
                        if (subTrimmed.isEmpty() || subTrimmed.startsWith("#")) {
                            // Empty lines inside block scalar are preserved
                            if (subTrimmed.isEmpty() && i + 1 < lines.size &&
                                (lines[i + 1].startsWith("  ") || lines[i + 1].startsWith("\t"))) {
                                blockLines.add("")
                            }
                            i++
                            continue
                        }
                        if (subLine.startsWith("  ") || subLine.startsWith("\t")) {
                            blockLines.add(subLine.trimStart())
                            i++
                        } else {
                            break
                        }
                    }
                    val content = if (isFolded) {
                        // Folded: join with spaces, collapse whitespace
                        blockLines.joinToString(" ").replace(yamlFoldedWhitespaceRegex, " ").trim()
                    } else {
                        // Literal: join with newlines preserving line breaks
                        blockLines.joinToString("\n")
                    }
                    node.map[key] = content
                } else if (valuePart.isEmpty()) {
                    // Nested block — collect sub-lines
                    val subLines = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val subLine = lines[i]
                        val subTrimmed = subLine.trim()
                        if (subTrimmed.isEmpty() || subTrimmed.startsWith("#")) {
                            i++
                            continue
                        }
                        // Check indentation — must be indented
                        if (subLine.startsWith("  ") || subLine.startsWith("\t")) {
                            subLines.add(subLine.trimStart())
                            i++
                        } else {
                            break
                        }
                    }
                    node.map[key] = parseNestedValue(subLines)
                } else {
                    node.map[key] = parseScalarValue(valuePart)
                    i++
                }
            } else {
                i++
            }
        }
        android.util.Log.e("NuclearBoy", "[SkillMgr] parseYamlNode() keysParsed=${node.map.size}")
        return node
    }

    private fun parseNestedValue(lines: List<String>): Any {
        if (lines.isEmpty()) return emptyMap<String, Any>()

        // Check if this is a list (lines starting with '- ')
        val isList = lines.all { it.trimStart().startsWith("- ") }
        if (isList) {
            return lines.map { line ->
                line.trimStart().removePrefix("- ").trim().trim('"').trim('\'')
            }
        }

        // Otherwise parse as a nested map
        val map = mutableMapOf<String, Any>()
        for (line in lines) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim().trim('"').trim('\'')
                map[key] = parseScalarValue(value)
            }
        }
        return map
    }

    private fun parseScalarValue(raw: String): Any {
        val value = raw.trim().trim('"').trim('\'')
        return when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            value.equals("null", ignoreCase = true) || value.equals("~") -> "null"
            value.toIntOrNull() != null -> value.toInt()
            value.toDoubleOrNull() != null -> value.toDouble()
            else -> value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun manifestFromNode(node: YamlNode): SkillManifest {
        val m = node.map

        val name = (m["name"] as? String) ?: "unknown"
        val version = (m["version"] as? String) ?: "0.1.0"
        val description = (m["description"] as? String) ?: ""
        val author = (m["author"] as? String) ?: "community"
        val homepage = m["homepage"] as? String
        val entryPoint = (m["entry_point"] as? String) ?: (m["entryPoint"] as? String) ?: "main:run"

        val permissions = parsePermissions(m["permissions"])
        val parameters = parseParameters(m["parameters"])
        val triggers = parseTriggers(m["triggers"])

        return SkillManifest(
            name = name,
            version = version,
            description = description,
            author = author,
            homepage = homepage,
            permissions = permissions,
            parameters = parameters,
            entryPoint = entryPoint,
            triggers = triggers,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePermissions(raw: Any?): SkillPermissions {
        if (raw !is Map<*, *>) return SkillPermissions()

        val map = raw as Map<String, Any>

        val filesystem = parseFilesystemPermissions(map["filesystem"])
        val network = parseNetworkPermission(map["network"])
        val packages = parsePackagePermission(map["packages"])
        val shell = parseShellPermission(map["shell"])

        return SkillPermissions(
            filesystem = filesystem,
            network = network,
            packages = packages,
            shell = shell,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFilesystemPermissions(raw: Any?): FilesystemPermissions? {
        if (raw !is Map<*, *>) return null
        val map = raw as Map<String, Any>
        val read = (map["read"] as? List<String>) ?: listOf("workspace/**")
        val write = (map["write"] as? List<String>) ?: listOf("workspace/**")
        return FilesystemPermissions(read = read, write = write)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseNetworkPermission(raw: Any?): NetworkPermission? {
        if (raw is Boolean) return NetworkPermission(allowed = raw)
        if (raw !is Map<*, *>) return null
        val map = raw as Map<String, Any>
        val allowed = (map["allowed"] as? Boolean) ?: false
        val allowedHosts = (map["allowed_hosts"] as? List<String>)
            ?: (map["allowedHosts"] as? List<String>)
            ?: emptyList()
        return NetworkPermission(allowed = allowed, allowedHosts = allowedHosts)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePackagePermission(raw: Any?): PackagePermission? {
        if (raw is String) return PackagePermission(allowed = listOf(raw))
        if (raw !is List<*>) return null
        return PackagePermission(allowed = raw.filterIsInstance<String>())
    }

    private fun parseShellPermission(raw: Any?): ShellPermission? {
        if (raw is Boolean) return ShellPermission(allowed = raw)
        if (raw !is Map<*, *>) return null
        val map = raw as Map<String, Any>
        return ShellPermission(allowed = (map["allowed"] as? Boolean) ?: false)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTriggers(raw: Any?): SkillTriggers? {
        if (raw !is Map<*, *>) return null
        val map = raw as Map<String, Any>
        return SkillTriggers(
            on_startup = (map["on_startup"] as? Boolean) ?: false,
            on_new_project = (map["on_new_project"] as? Boolean) ?: false,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseParameters(raw: Any?): List<SkillParameter> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            val map = item as Map<String, Any>
            SkillParameter(
                name = (map["name"] as? String) ?: return@mapNotNull null,
                type = (map["type"] as? String) ?: "string",
                description = (map["description"] as? String) ?: "",
                required = (map["required"] as? Boolean) ?: true,
                default = map["default"] as? String,
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Network helpers
    // ──────────────────────────────────────────────

    private fun downloadAndExtract(urlString: String, destDir: File) {
        android.util.Log.e("NuclearBoy", "[SkillMgr] downloadAndExtract() url=$urlString dest=${destDir.absolutePath}")
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "NuclearBoy-SkillManager/1.0")
        }

        val zipFile = File(destDir, "download.zip")

        try {
            connection.inputStream.use { input ->
                BufferedInputStream(input).use { bis ->
                    zipFile.outputStream().use { output -> bis.copyTo(output) }
                }
            }
        } finally {
            connection.disconnect()
        }
        android.util.Log.e("NuclearBoy", "[SkillMgr] downloadAndExtract() downloadSize=${zipFile.length()} bytes")

        // Extract zip
        var entryCount = 0
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryFile = File(destDir, entry.name)

                // Security: prevent zip-slip
                val canonicalPath = entryFile.canonicalPath
                if (!canonicalPath.startsWith(destDir.canonicalPath + File.separator) &&
                    canonicalPath != destDir.canonicalPath) {
                    android.util.Log.e("NuclearBoy", "[SkillMgr] downloadAndExtract() ZIP-SLIP BLOCKED: entry=${entry.name} destDir=${destDir.canonicalPath}")
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    entryFile.outputStream().use { fileOut -> zis.copyTo(fileOut) }
                }
                entryCount++
                entry = zis.nextEntry
            }
        }

        android.util.Log.e("NuclearBoy", "[SkillMgr] downloadAndExtract() extractionEntries=$entryCount")
        zipFile.delete()
    }

    private fun parseEntryPoint(entryPoint: String): Pair<String, String> {
        val parts = entryPoint.split(":", limit = 2)
        val moduleName = parts.getOrElse(0) { "main" }.sanitizePythonIdentifier()
        val functionName = parts.getOrElse(1) { "run" }.sanitizePythonIdentifier()
        return moduleName to functionName
    }

    /** 只保留合法的 Python 模块/函数名字符（字母、数字、下划线、点），防止 entry_point 注入代码。 */
    private fun String.sanitizePythonIdentifier(): String =
        filter { it.isLetterOrDigit() || it == '_' || it == '.' }.ifBlank { "main" }

    // ──────────────────────────────────────────────
    //  Internal types
    // ──────────────────────────────────────────────

    @kotlinx.serialization.Serializable
    private data class InstallMeta(
        val sourceType: String,
        val sourceUrl: String? = null,
        val sourcePath: String? = null,
        val installedAt: Long,
    )
}

/**
 * Represents a skill that has been installed and is ready for execution.
 */
data class InstalledSkill(
    val manifest: SkillManifest,
    val installPath: String,
    val installedAt: Long,
    val isActive: Boolean = true,
    val version: String,
) {
    /**
     * The directory where the skill files reside.
     */
    val installDir: File
        get() = File(installPath)

    /**
     * Human-readable install time.
     */
    val installedAtDisplay: String
        get() = installedAt.toRelativeTimeString()
}

private fun Long.toRelativeTimeString(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 172_800_000 -> "昨天"
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> "${diff / 604_800_000}周前"
    }
}
