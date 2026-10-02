package com.nuclearboy.app.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuclearboy.common.Project
import com.nuclearboy.common.SkillInfo
import com.nuclearboy.common.AppSettingsStore
import com.nuclearboy.memory.MemoryStore
import com.nuclearboy.skills.SkillManager
import com.nuclearboy.tools.docgen.FileOperations
import kotlinx.serialization.serializer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WelcomeData(
    val lastProject: String,
    val conversationCount: Int,
)

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val fileOperations: FileOperations,
    val skillManager: SkillManager,
    private val memoryStore: MemoryStore,
    private val appSettings: AppSettingsStore,
) : ViewModel() {
    val activeSkills: StateFlow<List<SkillInfo>> = skillManager.activeSkills

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    /** Project to restore after process recreation; null means use the general chat. */
    private val _startupProjectId = MutableStateFlow(appSettings.lastProjectId())
    val startupProjectId: StateFlow<String?> = _startupProjectId.asStateFlow()
    private val _projectsLoaded = MutableStateFlow(false)
    private val _startupMemoryLoaded = MutableStateFlow(false)

    private val _welcomeData = MutableStateFlow<WelcomeData?>(null)
    val welcomeData: StateFlow<WelcomeData?> = _welcomeData.asStateFlow()
    fun clearWelcome() { _welcomeData.value = null }

    /** 外部触发刷新项目列表 */
    fun refreshProjects() { loadProjects() }

    init {
        android.util.Log.e("NuclearBoy", "[ProjectVM] ProjectViewModel created")
        loadProjects()
        loadWelcomeMemory()
    }

    fun createProject(name: String) {
        android.util.Log.e("NuclearBoy", "[ProjectVM] createProject — name=$name")
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                fileOperations.createProject(name)
            }) {
                is com.nuclearboy.common.AppResult.Success -> {
                    val project = result.data
                    android.util.Log.e("NuclearBoy", "[ProjectVM] createProject SUCCESS — id=${project.id}, name=${project.name}")
                    loadProjects() // 实时刷新
                }
                is com.nuclearboy.common.AppResult.Failure -> {
                    android.util.Log.e("NuclearBoy", "[ProjectVM] createProject FAILED — ${result.error.humanMessage}")
                }
            }
        }
    }

    /**
     * Suspending variant: awaits filesystem creation and list refresh, then returns the new
     * project's id, or null on failure. Used by callers that need to navigate only after the
     * project is fully ready (e.g. createAndNavigate in MainActivity).
     */
    suspend fun createProjectAwait(name: String): String? {
        android.util.Log.e("NuclearBoy", "[ProjectVM] createProjectAwait — name=$name")
        return when (val result = withContext(Dispatchers.IO) {
            fileOperations.createProject(name)
        }) {
            is com.nuclearboy.common.AppResult.Success -> {
                val project = result.data
                android.util.Log.e("NuclearBoy", "[ProjectVM] createProjectAwait SUCCESS — id=${project.id}, name=${project.name}")
                loadProjects() // 实时刷新
                project.id
            }
            is com.nuclearboy.common.AppResult.Failure -> {
                android.util.Log.e("NuclearBoy", "[ProjectVM] createProjectAwait FAILED — ${result.error.humanMessage}")
                null
            }
        }
    }

    fun renameProject(projectId: String, newName: String) {
        val project = _projects.value.find { it.id == projectId } ?: return
        if (newName.isBlank() || newName.trim() == project.name) return
        viewModelScope.launch {
            val saved = fileOperations.currentProjectDir
            fileOperations.currentProjectDir = ""
            val result = withContext(Dispatchers.IO) { fileOperations.renameProject(project.name, newName) }
            // 若当前选中的就是被改名的项目，更新指向新名
            fileOperations.currentProjectDir =
                if (saved == project.name && result is com.nuclearboy.common.AppResult.Success) result.data else saved
            if (result is com.nuclearboy.common.AppResult.Success) {
                android.util.Log.e("NuclearBoy", "[ProjectVM] renameProject SUCCESS: ${project.name} -> ${result.data}")
                loadProjects()
            } else {
                val err = (result as com.nuclearboy.common.AppResult.Failure).error.humanMessage
                android.util.Log.e("NuclearBoy", "[ProjectVM] renameProject FAILED: $err")
            }
        }
    }

    fun selectProject(projectId: String) {
        val project = _projects.value.find { it.id == projectId }
        if (project != null) {
            android.util.Log.e("NuclearBoy", "[ProjectVM] selectProject — projectId=$projectId, name=${project.name} (found)")
        } else {
            android.util.Log.e("NuclearBoy", "[ProjectVM] selectProject — projectId=$projectId (NOT found in ${_projects.value.size} projects)")
        }
        fileOperations.currentProjectDir = project?.name ?: projectId
        // Keep the selected conversation stable across force-stop/relaunch. The
        // UUID is the durable project id used by conversation persistence;
        // FileOperations still receives the human-readable directory name.
        appSettings.setLastProjectId(projectId)
        _startupProjectId.value = projectId
    }

    /**
     * Resolve the persisted project against the currently loaded project list.
     * Deleted/invalid ids fall back to the always-present general conversation.
     */
    fun resolveStartupProjectId(): String {
        val saved = _startupProjectId.value?.trim().orEmpty()
        if (saved.isBlank() || saved == "__general__") return "__general__"
        return if (_projects.value.any { it.id == saved }) saved else "__general__"
    }

    /** Wait until the initial directory scan has finished before navigation. */
    suspend fun awaitStartupProjectId(): String {
        _projectsLoaded.first { it }
        _startupMemoryLoaded.first { it }
        return resolveStartupProjectId()
    }

    fun deleteProject(projectId: String) {
        android.util.Log.e("NuclearBoy", "[ProjectVM] deleteProject — projectId=$projectId")
        val project = _projects.value.find { it.id == projectId } ?: return
        viewModelScope.launch {
            // Must clear currentProjectDir so deleteFile resolves relative to workspaceRoot,
            // not workspaceRoot/projectName/projectName (double-nested)
            val saved = fileOperations.currentProjectDir
            fileOperations.currentProjectDir = ""
            val result = withContext(Dispatchers.IO) { fileOperations.deleteFile(project.name) }
            fileOperations.currentProjectDir = saved
            if (result is com.nuclearboy.common.AppResult.Success) {
                android.util.Log.e("NuclearBoy", "[ProjectVM] deleteProject SUCCESS: ${project.name}")
                // 清理该项目的 Memory 数据，避免孤立记忆占用存储并污染 AI 上下文
                try {
                    memoryStore.forgetProject(projectId)
                } catch (e: Exception) {
                    android.util.Log.e("NuclearBoy", "[ProjectVM] deleteProject — forgetProject failed: ${e.message}")
                }
                loadProjects() // 实时刷新
            } else {
                val err = (result as com.nuclearboy.common.AppResult.Failure).error.humanMessage
                android.util.Log.e("NuclearBoy", "[ProjectVM] deleteProject FAILED: ${project.name} — $err")
            }
        }
    }

    private fun loadWelcomeMemory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lastProject = memoryStore.getProfileValue("last_project")
                val totalConv = memoryStore.getProfileValue("total_conversations")
                @Suppress("UNCHECKED_CAST")
                val projectName = (lastProject as? com.nuclearboy.common.AppResult.Success<*>)?.data as? String
                @Suppress("UNCHECKED_CAST")
                val convCount = ((totalConv as? com.nuclearboy.common.AppResult.Success<*>)?.data as? String)?.toIntOrNull() ?: 0
                android.util.Log.e("NuclearBoy", "[ProjectVM] welcomeMemory rawLastProject=$lastProject rawConv=$totalConv")
                android.util.Log.e("NuclearBoy", "[ProjectVM] welcomeMemory project=$projectName convCount=$convCount")
                // Migrate the legacy memory value only when there is no
                // explicit launch preference. A user can switch projects
                // before this background read finishes; never overwrite that
                // newer choice with stale profile data from an older session.
                val persistedProjectId = appSettings.lastProjectId()?.trim().orEmpty()
                if (persistedProjectId.isBlank() &&
                    !projectName.isNullOrBlank() && projectName != "default"
                ) {
                    appSettings.setLastProjectId(projectName)
                    _startupProjectId.value = projectName
                }
                if (!projectName.isNullOrBlank() && projectName != "default" && convCount > 0) {
                    _welcomeData.value = WelcomeData(projectName, convCount)
                }
                _startupMemoryLoaded.value = true
            } catch (e: Exception) {
                android.util.Log.e("NuclearBoy", "[ProjectVM] welcomeMemory FAILED: ${e.message}")
                _startupMemoryLoaded.value = true
            }
        }
    }

    private fun loadProjects() {
        android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjects — entry")
        viewModelScope.launch {
            // Load from workspace root (NOT current project dir)
            val result = withContext(Dispatchers.IO) {
                // Save and restore currentProjectDir to avoid contamination
                val saved = fileOperations.currentProjectDir
                fileOperations.currentProjectDir = ""
                val workspaceRoot = fileOperations.getWorkspaceRoot()
                android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjects — workspaceRoot=$workspaceRoot")
                val r = fileOperations.listDirectory(".")
                fileOperations.currentProjectDir = saved
                r
            }
            when (result) {
                is com.nuclearboy.common.AppResult.Success -> {
                    val projectDirs = result.data.filter { it.isDirectory && it.name != "__general__" && !it.name.startsWith(".") }
                    android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjects — found ${projectDirs.size} directories")
                    // loadProjectMeta and persistProjectMeta perform blocking file I/O; run
                    // the entire mapping on the IO dispatcher to avoid blocking the main thread.
                    _projects.value = withContext(Dispatchers.IO) {
                        projectDirs.mapNotNull { dir ->
                            // Try to load persisted project metadata
                            val meta = loadProjectMeta(dir.name)
                            if (meta != null) {
                                android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjects — loaded meta for '${dir.name}', id=${meta.id}")
                                meta.copy(
                                    rootPath = dir.path,
                                    lastOpenedAt = dir.lastModified,
                                )
                            } else {
                                // Legacy project without metadata — create it now
                                val project = Project(
                                    name = dir.name,
                                    rootPath = dir.path,
                                    lastOpenedAt = dir.lastModified,
                                    fileCount = 0,
                                )
                                android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjects — legacy project '${dir.name}', created meta id=${project.id}")
                                persistProjectMeta(project)
                                project
                            }
                        }
                    }
                    android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjects — final count=${_projects.value.size}")
                    _projectsLoaded.value = true
                }
                is com.nuclearboy.common.AppResult.Failure -> {
                    android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjects FAILED — ${result.error.humanMessage}")
                    // Empty or error
                    _projectsLoaded.value = true
                }
            }
        }
    }

    // 项目元数据读写复用一个 Json（编码带默认值、解码忽略未知字段），避免每次项目操作重建
    private val metaJson = kotlinx.serialization.json.Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun persistProjectMeta(project: Project) {
        try {
            val metaDir = java.io.File(fileOperations.projectRoot(), ".agent")
            // projectRoot() uses currentProjectDir which may be empty — use workspaceRoot directly
            val projectDir = java.io.File(fileOperations.getWorkspaceRoot(), project.name)
            val agentDir = java.io.File(projectDir, ".agent")
            agentDir.mkdirs()
            val metaFile = java.io.File(agentDir, "project.json")
            metaFile.writeText(metaJson.encodeToString(Project.serializer(), project))
            android.util.Log.e("NuclearBoy", "[ProjectVM] persistProjectMeta — name=${project.name}, id=${project.id}, path=${metaFile.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[ProjectVM] persistProjectMeta FAILED — ${e.message}")
        }
    }

    private fun loadProjectMeta(dirName: String): Project? {
        return try {
            android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjectMeta — dirName=$dirName")
            val projectDir = java.io.File(fileOperations.getWorkspaceRoot(), dirName)
            val metaFile = java.io.File(projectDir, ".agent/project.json")
            if (metaFile.exists()) {
                val project = metaJson.decodeFromString(Project.serializer(), metaFile.readText())
                android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjectMeta — found meta file for '$dirName', id=${project.id}")
                project
            } else {
                android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjectMeta — no meta file for '$dirName'")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[ProjectVM] loadProjectMeta FAILED — ${e.message}")
            null
        }
    }
}
