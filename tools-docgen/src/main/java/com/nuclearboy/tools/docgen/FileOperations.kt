package com.nuclearboy.tools.docgen

import com.nuclearboy.common.AppError
import com.nuclearboy.common.AppResult
import com.nuclearboy.common.FileInfo
import com.nuclearboy.common.Project
import com.nuclearboy.common.fileExtension
import com.nuclearboy.common.isTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * High-level file operations scoped to a workspace root.
 *
 * All paths given to public methods are resolved relative to [workspaceRoot].
 * Operations that read files auto-detect text/binary and handle encoding.
 *
 * Thread-safe: all public methods switch to the IO dispatcher.
 */
class FileOperations(
    internal val workspaceRoot: File,
) {
    /** Public read access to workspace root for external modules. */
    fun getWorkspaceRoot(): File = workspaceRoot

    /** Current project subdirectory, set when a project is opened. */
    @Volatile var currentProjectDir: String = ""

    // 项目名清理：只编译一次，sanitizeProjectName 每次创建/重命名项目都会调用
    private val invalidProjectNameCharsRegex = Regex("""[<>:"/\\|?*\x00-\x1f]""")
    private val projectNameWhitespaceRegex   = Regex("""\s+""")

    init {
        android.util.Log.e("NuclearBoy", "[FileOps] init() workspaceRoot=${workspaceRoot.absolutePath}")
        workspaceRoot.mkdirs()
    }

    fun projectRoot(): File {
        val root = if (currentProjectDir.isNotEmpty()) File(workspaceRoot, currentProjectDir).also { it.mkdirs() }
        else workspaceRoot
        android.util.Log.e("NuclearBoy", "[FileOps] projectRoot() currentProjectDir='$currentProjectDir' resolvedRoot=${root.absolutePath}")
        return root
    }

    /**
     * Whether a path already exists in the current project.  This is used by
     * write tools to report CREATED versus MODIFIED based on the state before
     * the write, rather than guessing from the new file size.
     */
    fun pathExists(path: String): Boolean = try {
        resolvePath(path).exists()
    } catch (_: Exception) {
        false
    }

    // ──────────────────────────────────────────────
    //  Basic file operations
    // ──────────────────────────────────────────────

    /**
     * Create an empty file (and any ancestor directories) at [path] relative
     * to the workspace root. If [content] is provided, the file is written
     * immediately after creation.
     *
     * Returns an error if the file already exists.
     */
    suspend fun createFile(
        path: String,
        content: String = "",
    ): AppResult<FileInfo> = withContext(Dispatchers.IO) {
        android.util.Log.e("NuclearBoy", "[FileOps] createFile() path='$path' contentLen=${content.length}")
        try {
            val file = resolvePath(path)

            if (file.exists()) {
                android.util.Log.e("NuclearBoy", "[FileOps] createFile() FAILED: already exists ${file.absolutePath}")
                return@withContext AppResult.failure(
                    AppError.FileWriteDenied,
                    "File already exists: ${file.absolutePath}"
                )
            }

            file.parentFile?.mkdirs()

            if (!file.createNewFile()) {
                return@withContext AppResult.failure(
                    AppError.FileWriteDenied,
                    "Failed to create file: ${file.absolutePath}"
                )
            }

            if (content.isNotEmpty()) {
                file.writeText(content, Charsets.UTF_8)
            }

            android.util.Log.e("NuclearBoy", "[FileOps] createFile() success: $path size=${file.length()}")
            AppResult.success(buildFileInfo(file))
        } catch (e: SecurityException) {
            android.util.Log.e("NuclearBoy", "[FileOps] createFile() SecurityException: ${e.message}")
            AppResult.failure(AppError.FileWriteDenied, e.message)
        } catch (e: IOException) {
            android.util.Log.e("NuclearBoy", "[FileOps] createFile() IOException: ${e.message}")
            AppResult.failure(AppError.FileWriteDenied, e.message)
        }
    }

    /**
     * Read a file at [path] relative to the workspace root.
     * Text files are read with UTF-8 encoding; binary files return
     * metadata-only FileInfo.
     */
    suspend fun readFile(path: String): AppResult<FileInfo> = withContext(Dispatchers.IO) {
        android.util.Log.e("NuclearBoy", "[FileOps] readFile() path='$path'")
        try {
            val file = resolvePath(path)

            if (!file.exists()) {
                android.util.Log.e("NuclearBoy", "[FileOps] readFile() FAILED: not found ${file.absolutePath}")
                return@withContext AppResult.failure(
                    AppError.FileNotFound,
                    "File not found: ${file.absolutePath}"
                )
            }

            if (!file.isFile) {
                return@withContext AppResult.failure(
                    AppError.FileReadError,
                    "Path is not a file: ${file.absolutePath}"
                )
            }

            val info = buildFileInfo(file)
            val fileInfo = if (file.name.isTextFile()) {
                info.copy(content = file.readText(Charsets.UTF_8))
            } else {
                info
            }

            android.util.Log.e("NuclearBoy", "[FileOps] readFile() success: $path size=${fileInfo.size} contentLen=${fileInfo.content?.length ?: 0}")
            AppResult.success(fileInfo)
        } catch (e: SecurityException) {
            android.util.Log.e("NuclearBoy", "[FileOps] readFile() SecurityException: ${e.message}")
            AppResult.failure(AppError.FileReadError, e.message)
        } catch (e: IOException) {
            android.util.Log.e("NuclearBoy", "[FileOps] readFile() IOException: ${e.message}")
            AppResult.failure(AppError.FileReadError, e.message)
        }
    }

    /**
     * Write [content] to a file at [path] relative to the workspace root.
     * Creates the file and any ancestor directories if they do not exist.
     * Overwrites an existing file.
     */
    suspend fun writeFile(
        path: String,
        content: String,
    ): AppResult<FileInfo> = withContext(Dispatchers.IO) {
        android.util.Log.e("NuclearBoy", "[FileOps] writeFile() path='$path' contentLen=${content.length}")
        try {
            val file = resolvePath(path)

            file.parentFile?.mkdirs()

            file.writeText(content, Charsets.UTF_8)

            android.util.Log.e("NuclearBoy", "[FileOps] writeFile() success: $path size=${file.length()}")
            AppResult.success(buildFileInfo(file))
        } catch (e: SecurityException) {
            android.util.Log.e("NuclearBoy", "[FileOps] writeFile() SecurityException: ${e.message}")
            AppResult.failure(AppError.FileWriteDenied, e.message)
        } catch (e: IOException) {
            android.util.Log.e("NuclearBoy", "[FileOps] writeFile() IOException: ${e.message}")
            AppResult.failure(AppError.FileWriteDenied, e.message)
        }
    }

    /**
     * Delete a file or directory at [path] relative to the workspace root.
     * Directories are deleted recursively.
     */
    suspend fun deleteFile(path: String): AppResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val file = resolvePath(path)
            android.util.Log.e("NuclearBoy", "[FileOps] deleteFile() path=$path resolved=${file.absolutePath} exists=${file.exists()}")

            if (!file.exists()) {
                return@withContext AppResult.failure(
                    AppError.FileNotFound,
                    "Path not found: ${file.absolutePath}"
                )
            }

            // Safety: never allow deletion of the workspace root itself
            if (file.canonicalFile == workspaceRoot.canonicalFile) {
                return@withContext AppResult.failure(
                    AppError.FileWriteDenied,
                    "Cannot delete the workspace root directory"
                )
            }

            val deleted = file.deleteRecursively()
            android.util.Log.e("NuclearBoy", "[FileOps] deleteFile() result=$deleted for ${file.absolutePath}")

            if (!deleted) {
                return@withContext AppResult.failure(
                    AppError.FileWriteDenied,
                    "Failed to delete: ${file.absolutePath}"
                )
            }

            AppResult.success(true)
        } catch (e: SecurityException) {
            android.util.Log.e("NuclearBoy", "[FileOps] deleteFile() SecurityException: ${e.message}")
            AppResult.failure(AppError.FileWriteDenied, e.message)
        } catch (e: IOException) {
            android.util.Log.e("NuclearBoy", "[FileOps] deleteFile() IOException: ${e.message}")
            AppResult.failure(AppError.FileWriteDenied, e.message)
        }
    }

    /**
     * List the contents of a directory at [path] relative to the workspace
     * root. Returns FileInfo entries sorted by type (directories first) and
     * then alphabetically.
     */
    suspend fun listDirectory(path: String): AppResult<List<FileInfo>> =
        withContext(Dispatchers.IO) {
            android.util.Log.e("NuclearBoy", "[FileOps] listDirectory() path='$path'")
            try {
                val dir = resolvePath(path)

                if (!dir.exists()) {
                    return@withContext AppResult.failure(
                        AppError.FileNotFound,
                        "Directory not found: ${dir.absolutePath}"
                    )
                }

                if (!dir.isDirectory) {
                    return@withContext AppResult.failure(
                        AppError.FileReadError,
                        "Path is not a directory: ${dir.absolutePath}"
                    )
                }

                val entries = dir.listFiles()
                    ?.map { buildFileInfo(it) }
                    ?.sortedWith(compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
                    ?: emptyList()

                android.util.Log.e("NuclearBoy", "[FileOps] listDirectory() success: $path entries=${entries.size}")
                AppResult.success(entries)
            } catch (e: SecurityException) {
                android.util.Log.e("NuclearBoy", "[FileOps] listDirectory() SecurityException: ${e.message}")
                AppResult.failure(AppError.FileReadError, e.message)
            }
        }

    /**
     * Recursively search for files whose name or path contains [query].
     * If [rootPath] is provided, search starts from that subdirectory;
     * otherwise the entire workspace is searched.
     *
     * Completely skips dot-directories (`.git`, `.agent`, etc.) for speed.
     */
    suspend fun searchFiles(
        query: String,
        rootPath: String? = null,
    ): AppResult<List<FileInfo>> = withContext(Dispatchers.IO) {
        android.util.Log.e("NuclearBoy", "[FileOps] searchFiles() query='$query' rootPath='$rootPath'")
        try {
            val startDir = if (rootPath != null) resolvePath(rootPath) else workspaceRoot

            if (!startDir.isDirectory) {
                return@withContext AppResult.failure(
                    AppError.FileNotFound,
                    "Search root is not a directory: ${startDir.absolutePath}"
                )
            }

            val lowerQuery = query.lowercase().trim()
            if (lowerQuery.isEmpty()) {
                return@withContext AppResult.success(emptyList())
            }

            val results = mutableListOf<FileInfo>()

            startDir.walkTopDown()
                .maxDepth(20)
                .onEnter { dir ->
                    // Skip hidden/dot directories
                    val name = dir.name
                    !name.startsWith(".") &&
                            name != "node_modules" &&
                            name != "__pycache__" &&
                            name != "build" &&
                            name != ".gradle"
                }
                .filter { it.isFile }
                .forEach { file ->
                    if (file.name.lowercase().contains(lowerQuery)) {
                        results.add(buildFileInfo(file))
                    }
                }

            android.util.Log.e("NuclearBoy", "[FileOps] searchFiles() success: '$query' results=${results.size}")
            AppResult.success(results)
        } catch (e: SecurityException) {
            android.util.Log.e("NuclearBoy", "[FileOps] searchFiles() SecurityException: ${e.message}")
            AppResult.failure(AppError.FileReadError, e.message)
        } catch (e: IOException) {
            android.util.Log.e("NuclearBoy", "[FileOps] searchFiles() IOException: ${e.message}")
            AppResult.failure(AppError.FileReadError, e.message)
        }
    }

    /**
     * Create a new project directory structure under the workspace root.
     * Generates standard scaffolding directories and a README.md.
     *
     * @param name Project name (used as the directory name)
     * @param techStack List of technologies (optional, used to customize scaffolding)
     */
    suspend fun createProject(
        name: String,
        techStack: List<String> = emptyList(),
    ): AppResult<Project> = withContext(Dispatchers.IO) {
        android.util.Log.e("NuclearBoy", "[FileOps] createProject() name='$name' techStack=${techStack.joinToString()}")
        try {
            val sanitizedName = sanitizeProjectName(name)
            val projectDir = File(workspaceRoot, sanitizedName)

            if (projectDir.exists()) {
                return@withContext AppResult.failure(
                    AppError.FileWriteDenied,
                    "Project '${sanitizedName}' already exists"
                )
            }

            // Create standard directory structure
            val directories = buildProjectDirectories(sanitizedName, techStack)
            for (dir in directories) {
                val d = File(projectDir, dir)
                if (!d.mkdirs()) {
                    return@withContext AppResult.failure(
                        AppError.FileWriteDenied,
                        "Failed to create directory: ${d.absolutePath}"
                    )
                }
            }

            // Create README.md
            val readmeContent = buildReadme(sanitizedName, techStack)
            File(projectDir, "README.md").writeText(readmeContent, Charsets.UTF_8)

            // Create .gitignore
            File(projectDir, ".gitignore").writeText(buildGitignore(techStack), Charsets.UTF_8)

            val project = Project(
                id = UUID.randomUUID().toString(),
                name = sanitizedName,
                rootPath = projectDir.absolutePath,
                description = "项目 $sanitizedName",
                techStack = techStack,
                createdAt = System.currentTimeMillis(),
                lastOpenedAt = System.currentTimeMillis(),
                fileCount = projectDir.walkTopDown().count { it.isFile },
            )

            android.util.Log.e("NuclearBoy", "[FileOps] createProject() success: $sanitizedName fileCount=${project.fileCount}")
            AppResult.success(project)
        } catch (e: SecurityException) {
            android.util.Log.e("NuclearBoy", "[FileOps] createProject() SecurityException: ${e.message}")
            AppResult.failure(AppError.FileWriteDenied, e.message)
        } catch (e: IOException) {
            android.util.Log.e("NuclearBoy", "[FileOps] createProject() IOException: ${e.message}")
            AppResult.failure(AppError.FileWriteDenied, e.message)
        }
    }

    /**
     * 重命名项目：把项目目录改名。新名清洗后不能为空、不能与已有项目重名。
     */
    suspend fun renameProject(oldName: String, newName: String): AppResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val sanitized = sanitizeProjectName(newName)
                if (sanitized.isBlank()) {
                    return@withContext AppResult.failure(AppError.InvalidRequest, "项目名不能为空")
                }
                val src = File(workspaceRoot, oldName)
                if (!src.exists() || !src.isDirectory) {
                    return@withContext AppResult.failure(AppError.FileNotFound, "项目不存在: $oldName")
                }
                if (sanitized == oldName) return@withContext AppResult.success(sanitized)
                val dst = File(workspaceRoot, sanitized)
                if (dst.exists()) {
                    return@withContext AppResult.failure(AppError.FileWriteDenied, "已存在同名项目: $sanitized")
                }
                if (!src.renameTo(dst)) {
                    return@withContext AppResult.failure(AppError.FileWriteDenied, "重命名失败")
                }
                AppResult.success(sanitized)
            } catch (e: SecurityException) {
                AppResult.failure(AppError.FileWriteDenied, e.message)
            } catch (e: IOException) {
                AppResult.failure(AppError.FileWriteDenied, e.message)
            }
        }

    /**
     * Get the absolute File for a project directory by name.
     */
    fun getProjectPath(projectName: String): File {
        val result = File(workspaceRoot, sanitizeProjectName(projectName))
        android.util.Log.e("NuclearBoy", "[FileOps] getProjectPath() projectName='$projectName' path=${result.absolutePath}")
        return result
    }

    // ──────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────

    private fun resolvePath(path: String): File {
        // Normalize: trim, strip leading slashes, resolve against project root
        val clean = path.trim().trimStart('/').trimStart('\\')
        val root = projectRoot()
        val resolved = File(root, clean).canonicalFile

        // Security: ensure the resolved path stays inside the workspace
        val rootCanonical = root.canonicalFile
        val isSafe = resolved.path.startsWith(rootCanonical.path + File.separator) ||
                     resolved == rootCanonical
        android.util.Log.e("NuclearBoy", "[FileOps] resolvePath() raw='$path' clean='$clean' resolved=${resolved.absolutePath} root=${rootCanonical.absolutePath} safe=$isSafe")
        if (!isSafe) {
            android.util.Log.e("NuclearBoy", "[FileOps] resolvePath() SECURITY ALERT: path traversal detected! path='$path' resolved='${resolved.absolutePath}'")
            throw SecurityException(
                "Path traversal detected: $path resolves outside workspace root"
            )
        }

        return resolved
    }

    private fun buildFileInfo(file: File): FileInfo {
        return FileInfo(
            path = file.absolutePath,
            name = file.name,
            extension = file.name.fileExtension(),
            size = if (file.isFile) file.length() else 0L,
            lastModified = file.lastModified(),
            isDirectory = file.isDirectory,
        )
    }

    private fun sanitizeProjectName(name: String): String {
        val result = name.trim()
            .replace(invalidProjectNameCharsRegex, "")
            .replace(projectNameWhitespaceRegex, "-")
            .take(100)
            .ifEmpty { "untitled-project" }
        android.util.Log.e("NuclearBoy", "[FileOps] sanitizeProjectName() input='$name' output='$result'")
        return result
    }

    private fun buildProjectDirectories(
        projectName: String,
        techStack: List<String>,
    ): List<String> {
        val dirs = mutableListOf(
            "src",
            "docs",
            "tests",
            "assets",
            AppConstants.PROJECT_MEMORY_DIR.removePrefix(".agent/"),
            AppConstants.PROJECT_SKILLS_DIR.removePrefix(".agent/"),
        )

        val hasPython = techStack.any {
            it.equals("python", ignoreCase = true) ||
                    it.equals("flask", ignoreCase = true) ||
                    it.equals("fastapi", ignoreCase = true) ||
                    it.equals("django", ignoreCase = true)
        }
        val hasKotlin = techStack.any {
            it.equals("kotlin", ignoreCase = true) ||
                    it.equals("android", ignoreCase = true) ||
                    it.equals("kmp", ignoreCase = true)
        }
        val hasJs = techStack.any {
            it.equals("javascript", ignoreCase = true) ||
                    it.equals("typescript", ignoreCase = true) ||
                    it.equals("react", ignoreCase = true) ||
                    it.equals("vue", ignoreCase = true) ||
                    it.equals("node", ignoreCase = true)
        }

        if (hasPython) {
            dirs.add("src/$projectName")
            dirs.add("tests/unit")
        }
        if (hasKotlin) {
            dirs.add("src/main/kotlin/com/$projectName")
            dirs.add("src/test/kotlin/com/$projectName")
        }
        if (hasJs) {
            dirs.add("src/components")
            dirs.add("src/utils")
            dirs.add("public")
        }

        return dirs
    }

    private fun buildReadme(projectName: String, techStack: List<String>): String {
        val techLine = if (techStack.isNotEmpty()) {
            "**技术栈**: ${techStack.joinToString(", ")}"
        } else {
            "**技术栈**: 未指定"
        }

        return """
# $projectName

$techLine

## 项目概述

TODO: 添加项目描述

## 快速开始

TODO: 添加安装和运行说明

## 目录结构

TODO: 说明主要目录和文件的作用

---

*由 核弹男孩 (Nuclear Boy) 生成*
        """.trimIndent()
    }

    private fun buildGitignore(techStack: List<String>): String {
        val base = mutableListOf(
            "# Nuclear Boy generated .gitignore",
            "",
            "# IDE",
            ".idea/",
            "*.iml",
            ".vscode/",
            "*.swp",
            "*.swo",
            "*~",
            "",
            "# OS",
            ".DS_Store",
            "Thumbs.db",
            "",
            "# Build outputs",
            "build/",
            "dist/",
            "target/",
            "out/",
            "",
            "# Dependencies",
            "node_modules/",
            "__pycache__/",
            "*.pyc",
            "*.pyo",
            ".gradle/",
            "",
            "# Environment",
            ".env",
            ".env.local",
            ".env.*.local",
            "",
        )

        val hasPython = techStack.any {
            it.equals("python", ignoreCase = true) ||
                    it.equals("flask", ignoreCase = true) ||
                    it.equals("fastapi", ignoreCase = true) ||
                    it.equals("django", ignoreCase = true)
        }
        if (hasPython) {
            base.addAll(
                listOf(
                    "# Python",
                    "venv/",
                    ".venv/",
                    "env/",
                    "*.egg-info/",
                    "dist/",
                    "*.whl",
                    "",
                )
            )
        }

        val hasKotlin = techStack.any {
            it.equals("kotlin", ignoreCase = true) ||
                    it.equals("android", ignoreCase = true) ||
                    it.equals("kmp", ignoreCase = true)
        }
        if (hasKotlin) {
            base.addAll(
                listOf(
                    "# Kotlin / Android",
                    ".gradle/",
                    "local.properties",
                    "*.apk",
                    "*.aab",
                    "*.jks",
                    "",
                )
            )
        }

        val hasJs = techStack.any {
            it.equals("javascript", ignoreCase = true) ||
                    it.equals("typescript", ignoreCase = true) ||
                    it.equals("react", ignoreCase = true) ||
                    it.equals("vue", ignoreCase = true) ||
                    it.equals("node", ignoreCase = true)
        }
        if (hasJs) {
            base.addAll(
                listOf(
                    "# JavaScript / TypeScript",
                    "node_modules/",
                    ".next/",
                    ".nuxt/",
                    "dist/",
                    ".cache/",
                    "",
                )
            )
        }

        return base.joinToString("\n")
    }

    // Companion for shared constants from common module
    private object AppConstants {
        const val PROJECT_MEMORY_DIR = ".agent/memory"
        const val PROJECT_SKILLS_DIR = ".agent/skills"
    }
}
