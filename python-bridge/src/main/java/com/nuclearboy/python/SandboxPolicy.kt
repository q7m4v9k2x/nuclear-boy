package com.nuclearboy.python

import android.os.Environment
import com.nuclearboy.common.AppConstants
import com.nuclearboy.common.AppError
import com.nuclearboy.common.AppResult
import java.io.File

// ── Sandbox Policy ──────────────────────────────────────

/**
 * Defines the security boundaries for sandbox operations.
 *
 * The sandbox operates on a principle of least privilege:
 * - No file system access outside explicitly allowed paths
 * - No network access unless explicitly permitted
 * - Only pre-approved Python packages
 * - Shell commands blocked by default
 *
 * @param allowedReadPaths Directories the sandbox can read from.
 * @param allowedWritePaths Directories the sandbox can write to.
 * @param networkAllowed Whether outbound network access is permitted.
 * @param allowedPackages Python packages the sandbox may import.
 * @param shellAllowed Whether shell commands (subprocess) are permitted.
 * @param maxFileSize Maximum file size the sandbox can read/write (bytes).
 * @param maxOutputSize Maximum total output from a script (bytes).
 * @param maxMemoryMb Maximum memory a script can use (approximate, MB).
 */
data class SandboxPolicy(
    val allowedReadPaths: List<String>,
    val allowedWritePaths: List<String>,
    val networkAllowed: Boolean = false,
    val allowedPackages: List<String> = emptyList(),
    val shellAllowed: Boolean = false,
    val maxFileSize: Long = 50 * 1024 * 1024, // 50 MB
    val maxOutputSize: Long = 10 * 1024 * 1024, // 10 MB
    val maxMemoryMb: Int = 256,
) {
    companion object {
        /**
         * Create a strict sandbox policy for untrusted script execution.
         * Read-only access to the project directory, write access to a dedicated sandbox tmp.
         */
        fun strict(sandboxDir: String, projectDir: String): SandboxPolicy {
            return SandboxPolicy(
                allowedReadPaths = listOf(
                    projectDir,
                    sandboxDir,
                ),
                allowedWritePaths = listOf(
                    sandboxDir,
                ),
                networkAllowed = false,
                allowedPackages = listOf(
                    "python-docx", "docx",
                    "openpyxl",
                    "Pillow", "PIL",
                    "chardet",
                    "json", "csv", "re", "os", "sys",
                    "math", "random", "datetime",
                    "collections", "itertools", "functools",
                    "pathlib",
                ),
                shellAllowed = false,
            )
        }

        /**
         * Create a standard policy for general agent use.
         * Allows reading the project and documents directories,
         * writing to the sandbox and output directories.
         */
        fun standard(projectDir: String): SandboxPolicy {
            val documentsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                AppConstants.APP_DOCUMENTS_DIR,
            ).absolutePath

            val sandboxDir = "$documentsDir/${AppConstants.SANDBOX_WORKSPACE}"

            return SandboxPolicy(
                allowedReadPaths = listOf(
                    projectDir,
                    documentsDir,
                    sandboxDir,
                ),
                allowedWritePaths = listOf(
                    // Agent scripts run with the active project as their working
                    // directory.  Keep that directory writable so a normal
                    // run_python call can create/update project files.
                    projectDir,
                    sandboxDir,
                    documentsDir,
                ),
                networkAllowed = true,
                allowedPackages = listOf(
                    // Document generation
                    "python-docx", "docx",
                    "openpyxl",
                    "Pillow", "PIL",
                    // Text processing
                    "chardet",
                    "requests",
                    // Standard library (always available)
                    "json", "csv", "re", "os", "sys", "io",
                    "math", "random", "datetime", "time",
                    "collections", "itertools", "functools",
                    "pathlib", "shutil", "tempfile", "glob",
                    "hashlib", "base64", "uuid",
                    "typing", "dataclasses", "enum",
                    "textwrap", "string",
                    "xml", "html",
                ),
                shellAllowed = false,
            )
        }

        /**
         * Create a relaxed policy for trusted user scripts.
         * Allows full file system access and network, no shell.
         */
        fun relaxed(projectDir: String): SandboxPolicy {
            val documentsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                AppConstants.APP_DOCUMENTS_DIR,
            ).absolutePath

            return SandboxPolicy(
                allowedReadPaths = listOf(
                    Environment.getExternalStorageDirectory().absolutePath,
                    projectDir,
                    documentsDir,
                ),
                allowedWritePaths = listOf(
                    Environment.getExternalStorageDirectory().absolutePath,
                    projectDir,
                    documentsDir,
                ),
                networkAllowed = true,
                allowedPackages = emptyList(), // All pre-installed packages allowed
                shellAllowed = false,
            )
        }

        /**
         * Create a policy for document generation tasks.
         * Only allows writing to the documents output directory.
         */
        fun documentGeneration(outputDir: String): SandboxPolicy {
            return SandboxPolicy(
                allowedReadPaths = listOf(outputDir),
                allowedWritePaths = listOf(outputDir),
                networkAllowed = false,
                allowedPackages = listOf(
                    "python-docx", "docx",
                    "openpyxl",
                    "Pillow", "PIL",
                    "json", "csv", "re", "os", "sys", "io",
                    "math", "random", "datetime",
                    "collections", "pathlib",
                ),
                shellAllowed = false,
            )
        }
    }
}

// ── Sandbox Operations ──────────────────────────────────

/**
 * Represents a sandbox operation that must be validated against a [SandboxPolicy].
 */
sealed class SandboxOperation {
    /** Reading a file from disk. */
    data class ReadFile(val path: String) : SandboxOperation()

    /** Writing a file to disk. */
    data class WriteFile(val path: String, val estimatedSize: Long = 0) : SandboxOperation()

    /** Deleting a file. */
    data class DeleteFile(val path: String) : SandboxOperation()

    /** Installing a Python package. */
    data class InstallPackage(val name: String) : SandboxOperation()

    /** Importing/using a Python package. */
    data class ImportPackage(val name: String) : SandboxOperation()

    /** Making a network request. */
    object NetworkAccess : SandboxOperation()

    /** Executing a shell command. */
    data class ShellCommand(val command: String) : SandboxOperation()

    /** Executing a Python script (composite operation). */
    data class ExecuteScript(
        val script: String,
        val workingDir: String,
        val estimatedDuration: Long = 120,
    ) : SandboxOperation()
}

// ── Policy Enforcer ─────────────────────────────────────

/**
 * Validates sandbox operations against a [SandboxPolicy].
 *
 * Each validation method returns [AppResult.Success] if the operation
 * is permitted, or [AppResult.Failure] with [AppError.SandboxPermissionDenied]
 * if it violates the policy.
 */
class PolicyEnforcer {

    /**
     * Validate a single sandbox operation against a policy.
     */
    fun validate(
        policy: SandboxPolicy,
        operation: SandboxOperation,
    ): AppResult<Boolean> {
        return when (operation) {
            is SandboxOperation.ReadFile -> validateReadAccess(policy, operation.path)
            is SandboxOperation.WriteFile -> validateWriteAccess(policy, operation.path, operation.estimatedSize)
            is SandboxOperation.DeleteFile -> validateDeleteAccess(policy, operation.path)
            is SandboxOperation.InstallPackage -> validatePackageInstall(policy, operation.name)
            is SandboxOperation.ImportPackage -> validatePackageImport(policy, operation.name)
            is SandboxOperation.NetworkAccess -> validateNetworkAccess(policy)
            is SandboxOperation.ShellCommand -> validateShellAccess(policy, operation.command)
            is SandboxOperation.ExecuteScript -> validateScriptExecution(policy, operation)
        }
    }

    /**
     * Validate multiple operations. Fails fast on the first violation.
     */
    fun validateAll(
        policy: SandboxPolicy,
        operations: List<SandboxOperation>,
    ): AppResult<Boolean> {
        for (op in operations) {
            val result = validate(policy, op)
            if (result.isFailure) return result
        }
        return AppResult.success(true)
    }

    /**
     * Build environment variables for the sandbox based on the policy.
     * These are injected into the Python runtime to enforce restrictions.
     */
    fun buildEnvironment(policy: SandboxPolicy): Map<String, String> {
        return buildMap {
            put("SANDBOX_READ_PATHS", policy.allowedReadPaths.joinToString(":"))
            put("SANDBOX_WRITE_PATHS", policy.allowedWritePaths.joinToString(":"))
            put("SANDBOX_NETWORK_ALLOWED", if (policy.networkAllowed) "1" else "0")
            put("SANDBOX_SHELL_ALLOWED", if (policy.shellAllowed) "1" else "0")
            put("SANDBOX_ALLOWED_PACKAGES", policy.allowedPackages.joinToString(":"))
            put("SANDBOX_MAX_FILE_SIZE", policy.maxFileSize.toString())
            put("SANDBOX_MAX_OUTPUT_SIZE", policy.maxOutputSize.toString())
            put("SANDBOX_MAX_MEMORY_MB", policy.maxMemoryMb.toString())
            put("SANDBOX_PYTHONPATH", policy.allowedReadPaths.joinToString(":"))
        }
    }

    /**
     * Generate a Python preamble that enforces the policy at the Python level.
     * This is prepended to every script executed in the sandbox.
     */
    fun buildPolicyPreamble(policy: SandboxPolicy): String {
        // Escape backslashes/quotes so Windows-style or quoted paths can't break the Python literal
        fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val readPaths = policy.allowedReadPaths.joinToString(", ") { "\"${escape(it)}\"" }
        val writePaths = policy.allowedWritePaths.joinToString(", ") { "\"${escape(it)}\"" }

        // NOTE: aliased imports (__sb_os / __sb_builtins) keep the executing namespace clean —
        // the Chaquopy wrapper's own `sys` reference must NOT be shadowed or deleted,
        // its finally-block needs it to restore stdout/stderr.
        return """
import os as __sb_os
import builtins as __sb_builtins

# ── Sandbox Policy Enforcement ──
__SANDBOX_READ_PATHS = [$readPaths]
__SANDBOX_WRITE_PATHS = [$writePaths]
__SANDBOX_NETWORK_ALLOWED = ${if (policy.networkAllowed) "True" else "False"}
__SANDBOX_SHELL_ALLOWED = ${if (policy.shellAllowed) "True" else "False"}
__SANDBOX_MAX_FILE_SIZE = ${policy.maxFileSize}
__SANDBOX_MAX_OUTPUT = ${policy.maxOutputSize}

def __sandbox_check_path(path, allowed_list, operation):
    abs_path = __sb_os.path.abspath(__sb_os.path.realpath(path))
    for allowed in allowed_list:
        allowed_abs = __sb_os.path.abspath(__sb_os.path.realpath(allowed))
        if abs_path == allowed_abs or abs_path.startswith(allowed_abs + __sb_os.sep):
            return True
    raise PermissionError("沙箱" + operation + ": " + str(path))

# Wrap builtins.open — always rewrap from the pristine original, never from a
# previously-installed sandbox wrapper, so repeated executions in this shared
# interpreter don't nest wrappers indefinitely. ChaquopyPythonExecutor restores
# the pristine open after every run, so this is mainly a defense-in-depth guard.
if not hasattr(__sb_builtins, '_nb_pristine_open'):
    __sb_builtins._nb_pristine_open = __sb_builtins.open
__original_open = __sb_builtins._nb_pristine_open
def __sandbox_open(file, mode='r', *args, **kwargs):
    if isinstance(file, int):
        return __original_open(file, mode, *args, **kwargs)
    path = file if isinstance(file, str) else str(file)
    mode_lower = str(mode).lower()
    if 'w' in mode_lower or 'a' in mode_lower or '+' in mode_lower or 'x' in mode_lower:
        __sandbox_check_path(path, __SANDBOX_WRITE_PATHS, "写入拒绝")
    else:
        __sandbox_check_path(path, __SANDBOX_READ_PATHS, "读取拒绝")
    return __original_open(file, mode, *args, **kwargs)
__sb_builtins.open = __sandbox_open

# Block shell access if not allowed
if not __SANDBOX_SHELL_ALLOWED:
    def __sb_no_shell(*a, **kw):
        raise PermissionError("Shell 访问被沙箱禁止")
    import subprocess as __sp
    if not hasattr(__sp, '_nb_pristine_call'):
        __sp._nb_pristine_call = __sp.call
        __sp._nb_pristine_run = __sp.run
        __sp._nb_pristine_popen = __sp.Popen
    __sp.call = __sb_no_shell
    __sp.run = __sb_no_shell
    __sp.Popen = __sb_no_shell
    if not hasattr(__sb_os, '_nb_pristine_system'):
        __sb_os._nb_pristine_system = __sb_os.system
        __sb_os._nb_pristine_popen = __sb_os.popen
    __sb_os.system = __sb_no_shell
    __sb_os.popen = __sb_no_shell

# Block socket if network is not allowed
if not __SANDBOX_NETWORK_ALLOWED:
    def __sb_no_net(*a, **kw):
        raise PermissionError("网络访问被沙箱禁止")
    try:
        import socket as __socket
        if not hasattr(__socket, '_nb_pristine_socket'):
            __socket._nb_pristine_socket = __socket.socket
        __socket.socket = __sb_no_net
    except Exception:
        pass
    try:
        import requests as __requests
        if not hasattr(__requests, '_nb_pristine_get'):
            __requests._nb_pristine_get = __requests.get
            __requests._nb_pristine_post = __requests.post
            __requests._nb_pristine_request = __requests.request
        __requests.get = __sb_no_net
        __requests.post = __sb_no_net
        __requests.request = __sb_no_net
    except Exception:
        pass
        """.trimIndent()
    }

    companion object {
        /**
         * Python snippet that undoes every monkey-patch [buildPolicyPreamble] may have
         * installed (open/subprocess/os.system/socket/requests), restoring pristine
         * originals if present. Must run unconditionally after EVERY script execution
         * (sandboxed or not) so restrictions from one run never leak into the next in
         * this shared, long-lived Chaquopy interpreter.
         */
        val RESTORE_SNIPPET: String = """
try:
    import builtins as __nb_restore_builtins
    if hasattr(__nb_restore_builtins, '_nb_pristine_open'):
        __nb_restore_builtins.open = __nb_restore_builtins._nb_pristine_open
except Exception:
    pass
try:
    import subprocess as __nb_restore_sp
    if hasattr(__nb_restore_sp, '_nb_pristine_call'):
        __nb_restore_sp.call = __nb_restore_sp._nb_pristine_call
        __nb_restore_sp.run = __nb_restore_sp._nb_pristine_run
        __nb_restore_sp.Popen = __nb_restore_sp._nb_pristine_popen
except Exception:
    pass
try:
    import os as __nb_restore_os
    if hasattr(__nb_restore_os, '_nb_pristine_system'):
        __nb_restore_os.system = __nb_restore_os._nb_pristine_system
        __nb_restore_os.popen = __nb_restore_os._nb_pristine_popen
except Exception:
    pass
try:
    import socket as __nb_restore_socket
    if hasattr(__nb_restore_socket, '_nb_pristine_socket'):
        __nb_restore_socket.socket = __nb_restore_socket._nb_pristine_socket
except Exception:
    pass
try:
    import requests as __nb_restore_requests
    if hasattr(__nb_restore_requests, '_nb_pristine_get'):
        __nb_restore_requests.get = __nb_restore_requests._nb_pristine_get
        __nb_restore_requests.post = __nb_restore_requests._nb_pristine_post
        __nb_restore_requests.request = __nb_restore_requests._nb_pristine_request
except Exception:
    pass
        """.trimIndent()
    }

    // ── Private Validators ───────────────────────────────

    private fun validateReadAccess(policy: SandboxPolicy, path: String): AppResult<Boolean> {
        if (!isPathWithinAllowed(path, policy.allowedReadPaths)) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "读取路径不被沙箱允许: $path"
            )
        }
        return AppResult.success(true)
    }

    private fun validateWriteAccess(
        policy: SandboxPolicy,
        path: String,
        estimatedSize: Long,
    ): AppResult<Boolean> {
        if (!isPathWithinAllowed(path, policy.allowedWritePaths)) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "写入路径不被沙箱允许: $path"
            )
        }
        if (estimatedSize > policy.maxFileSize) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "文件大小 (${estimatedSize.toFileSizeString()}) 超过限制 (${policy.maxFileSize.toFileSizeString()})"
            )
        }
        return AppResult.success(true)
    }

    private fun validateDeleteAccess(policy: SandboxPolicy, path: String): AppResult<Boolean> {
        // Deletion requires write access to the containing directory
        val parentDir = File(path).parent ?: return AppResult.failure(
            AppError.SandboxPermissionDenied,
            "无法确定父目录: $path"
        )
        if (!isPathWithinAllowed(parentDir, policy.allowedWritePaths)) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "删除操作不被沙箱允许: $path"
            )
        }
        return AppResult.success(true)
    }

    private fun validatePackageInstall(policy: SandboxPolicy, name: String): AppResult<Boolean> {
        // On Chaquopy, runtime pip installs are limited.
        // All packages must be pre-declared in build.gradle.kts.
        // We validate that the package is in the allowed list.
        if (policy.allowedPackages.isNotEmpty() &&
            name !in policy.allowedPackages &&
            name.replace("-", "_") !in policy.allowedPackages
        ) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "包 \"$name\" 不在沙箱允许列表中。请在 build.gradle.kts 中声明。"
            )
        }
        return AppResult.success(true)
    }

    private fun validatePackageImport(policy: SandboxPolicy, name: String): AppResult<Boolean> {
        // Standard library modules are always allowed
        if (isStdlibModule(name)) {
            return AppResult.success(true)
        }

        if (policy.allowedPackages.isNotEmpty() &&
            name !in policy.allowedPackages &&
            name.replace("-", "_") !in policy.allowedPackages
        ) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "导入包 \"$name\" 不被沙箱允许"
            )
        }
        return AppResult.success(true)
    }

    private fun validateNetworkAccess(policy: SandboxPolicy): AppResult<Boolean> {
        if (!policy.networkAllowed) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "网络访问被沙箱禁止"
            )
        }
        return AppResult.success(true)
    }

    private fun validateShellAccess(policy: SandboxPolicy, command: String): AppResult<Boolean> {
        if (!policy.shellAllowed) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "Shell 命令被沙箱禁止: ${command.take(50)}"
            )
        }
        // Block obviously dangerous commands even when shell is allowed
        val dangerousPatterns = listOf(
            "rm -rf /",
            "mkfs.",
            "dd if=",
            "> /dev/",
            ":(){ :|:& };:",  // Fork bomb
        )
        for (pattern in dangerousPatterns) {
            if (command.contains(pattern, ignoreCase = true)) {
                return AppResult.failure(
                    AppError.SandboxPermissionDenied,
                    "已拦截危险命令"
                )
            }
        }
        return AppResult.success(true)
    }

    private fun validateScriptExecution(
        policy: SandboxPolicy,
        operation: SandboxOperation.ExecuteScript,
    ): AppResult<Boolean> {
        // Validate that the working directory is within allowed write paths
        if (!isPathWithinAllowed(operation.workingDir, policy.allowedWritePaths)) {
            return AppResult.failure(
                AppError.SandboxPermissionDenied,
                "脚本工作目录不在允许范围内: ${operation.workingDir}"
            )
        }

        // Check for dangerous imports in the script
        if (!policy.shellAllowed) {
            val dangerousImports = listOf("subprocess", "os.system", "os.popen", "pty", "ctypes")
            for (imp in dangerousImports) {
                if (imp in operation.script) {
                    return AppResult.failure(
                        AppError.SandboxPermissionDenied,
                        "脚本包含不被允许的导入: $imp"
                    )
                }
            }
        }

        if (!policy.networkAllowed) {
            val networkImports = listOf("socket", "urllib", "http.", "requests", "aiohttp", "httpx")
            for (imp in networkImports) {
                if (operation.script.contains("import $imp") || operation.script.contains("from $imp")) {
                    return AppResult.failure(
                        AppError.SandboxPermissionDenied,
                        "脚本包含不被允许的网络导入: $imp"
                    )
                }
            }
        }

        return AppResult.success(true)
    }

    // ── Helpers ──────────────────────────────────────────

    /**
     * Check if a given path is within one of the allowed directories.
     * Uses canonical path resolution to prevent path traversal attacks.
     */
    private fun isPathWithinAllowed(path: String, allowedPaths: List<String>): Boolean {
        val resolved: String = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            return false
        }

        return allowedPaths.any { allowed ->
            try {
                val resolvedAllowed = File(allowed).canonicalPath
                // A path is inside the allowed directory itself or one of its
                // descendants.  A raw prefix check would incorrectly allow
                // siblings such as /workspace-other when /workspace is allowed.
                resolved == resolvedAllowed ||
                    resolved.startsWith(resolvedAllowed + File.separator)
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Check if a module name is a Python standard library module.
     * Covers the most commonly used stdlib modules.
     */
    private fun isStdlibModule(name: String): Boolean {
        val stdlib = setOf(
            "abc", "aifc", "argparse", "array", "ast", "asynchat", "asyncio",
            "asyncore", "atexit", "audioop", "base64", "bdb", "binascii", "binhex",
            "bisect", "builtins", "bz2", "calendar", "cgi", "cgitb", "chunk",
            "cmath", "cmd", "code", "codecs", "codeop", "collections", "colorsys",
            "compileall", "concurrent", "configparser", "contextlib", "contextvars",
            "copy", "copyreg", "cProfile", "crypt", "csv", "ctypes", "curses",
            "dataclasses", "datetime", "dbm", "decimal", "difflib", "dis",
            "distutils", "doctest", "email", "encodings", "enum", "errno",
            "faulthandler", "fcntl", "filecmp", "fileinput", "fnmatch", "formatter",
            "fractions", "ftplib", "functools", "gc", "getopt", "getpass",
            "gettext", "glob", "grp", "gzip", "hashlib", "heapq", "hmac", "html",
            "http", "idlelib", "imaplib", "imghdr", "imp", "importlib", "inspect",
            "io", "ipaddress", "itertools", "json", "keyword", "lib2to3", "linecache",
            "locale", "logging", "lzma", "mailbox", "mailcap", "marshal", "math",
            "mimetypes", "mmap", "modulefinder", "msilib", "msvcrt", "multiprocessing",
            "netrc", "nis", "nntplib", "numbers", "operator", "optparse", "os",
            "ossaudiodev", "parser", "pathlib", "pdb", "pickle", "pickletools",
            "pipes", "pkgutil", "platform", "plistlib", "poplib", "posix", "posixpath",
            "pprint", "profile", "pstats", "pty", "pwd", "py_compile", "pyclbr",
            "pydoc", "queue", "quopri", "random", "re", "readline", "reprlib",
            "resource", "rlcompleter", "runpy", "sched", "secrets", "select",
            "selectors", "shelve", "shlex", "shutil", "signal", "site", "smtpd",
            "smtplib", "sndhdr", "socket", "socketserver", "sqlite3", "ssl",
            "stat", "statistics", "string", "stringprep", "struct", "subprocess",
            "sunau", "symbol", "symtable", "sys", "sysconfig", "syslog", "tabnanny",
            "tarfile", "telnetlib", "tempfile", "termios", "test", "textwrap",
            "threading", "time", "timeit", "tkinter", "token", "tokenize", "trace",
            "traceback", "tracemalloc", "tty", "turtle", "turtledemo", "types",
            "typing", "unicodedata", "unittest", "urllib", "uu", "uuid", "venv",
            "warnings", "wave", "weakref", "webbrowser", "winreg", "winsound",
            "wsgiref", "xdrlib", "xml", "xmlrpc", "zipapp", "zipfile", "zipimport",
            "zlib",
        )
        return name in stdlib || name.split(".").firstOrNull() in stdlib
    }

    /**
     * Human-readable file size (duplicated here for self-contained utility).
     */
    private fun Long.toFileSizeString(): String {
        return when {
            this < 1024 -> "$this B"
            this < 1024 * 1024 -> "${this / 1024} KB"
            this < 1024 * 1024 * 1024 -> "${"%.1f".format(this / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(this / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }
}
