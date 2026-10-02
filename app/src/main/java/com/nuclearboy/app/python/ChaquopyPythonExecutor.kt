package com.nuclearboy.app.python

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.nuclearboy.python.PythonExecutor
import com.nuclearboy.python.PythonResult
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Python executor backed by Chaquopy's embedded CPython runtime.
 *
 * This is created in the :app module because Chaquopy API classes
 * are only available where the Chaquopy Gradle plugin is applied.
 */
class ChaquopyPythonExecutor : PythonExecutor {

    private val installedPackages = ConcurrentHashMap.newKeySet<String>()
    // builtins 模块引用缓存：Chaquopy 每次 getModule() 都创建新引用对象，
    // run() 是热路径，缓存后避免重复查找
    @Volatile private var cachedBuiltins: com.chaquo.python.PyObject? = null

    private fun builtins(): com.chaquo.python.PyObject =
        cachedBuiltins ?: Python.getInstance().getModule("builtins").also { cachedBuiltins = it }

    override fun start(context: Context) {
        android.util.Log.e("NuclearBoy", "[Chaquopy] start — entry")
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        Timber.d("☢️ Chaquopy Python runtime started")

        // Discover pre-installed packages
        try {
            val py = Python.getInstance()
            val pkgResources = py.getModule("pkg_resources")
            // working_set is a module attribute (an iterable WorkingSet), not
            // a callable function. Calling it raises TypeError on startup and
            // leaves installed package discovery empty.
            val workingSet = pkgResources.get("working_set")
            var count = 0
            if (workingSet != null) {
                // WorkingSet implements iteration rather than sequence
                // indexing, so convert it through Python's list() first.
                val distributions = py.getModule("builtins").callAttr("list", workingSet)
                for (pkg in distributions.asList()) {
                    val projectName = pkg.callAttr("__getattribute__", "project_name")
                    installedPackages.add(projectName.toString())
                    count++
                }
            }
            android.util.Log.e("NuclearBoy", "[Chaquopy] start — enumerated $count installed packages")
        } catch (e: Exception) {
            Timber.w(e, "Failed to enumerate installed packages")
            android.util.Log.e("NuclearBoy", "[Chaquopy] start — failed to enumerate packages: ${e.message}")
        }
    }

    override fun isStarted(): Boolean = Python.isStarted()

    override fun run(
        script: String,
        workingDir: String,
        env: Map<String, String>,
    ): PythonResult {
        val startTime = System.currentTimeMillis()
        android.util.Log.e("NuclearBoy", "[Chaquopy] run — scriptLen=${script.length}, workingDir=$workingDir, envKeys=${env.keys}")
        var outFile: java.io.File? = null
        var errFile: java.io.File? = null
        return try {
            val py = Python.getInstance()

            // Write output to known files — most reliable approach for Chaquopy
            outFile = java.io.File.createTempFile("nb_out_", ".txt")
            errFile = java.io.File.createTempFile("nb_err_", ".txt")
            val outPath = outFile.absolutePath.replace("\\", "/")
            val errPath = errFile.absolutePath.replace("\\", "/")

            val wrappedScript = buildString {
                appendLine("import sys, os, traceback")
                // Add skills directory to sys.path so skill modules are importable
                appendLine("try:")
                appendLine("    from com.chaquo.python import Python")
                appendLine("    _skills_dir = Python.getPlatform().getApplication().getFilesDir().getAbsolutePath() + '/skills'")
                appendLine("    if _skills_dir not in sys.path: sys.path.insert(0, _skills_dir)")
                appendLine("except: pass")
                // Safe escaping: replace backslashes and single quotes to prevent
                // Python string injection from malicious paths
                if (workingDir.isNotBlank()) {
                    val safeDir = workingDir.replace("\\", "\\\\").replace("'", "\\'")
                    appendLine("os.chdir('$safeDir')")
                }
                env.forEach { (key, value) ->
                    val safeKey = key.replace("\\", "\\\\").replace("'", "\\'")
                    val safeValue = value.replace("\\", "\\\\").replace("'", "\\'")
                    appendLine("os.environ['$safeKey'] = '$safeValue'")
                }
                appendLine()
                // Open output files, overwrite any old content
                appendLine("_out_fp = open('$outPath', 'w', encoding='utf-8')")
                appendLine("_err_fp = open('$errPath', 'w', encoding='utf-8')")
                appendLine("_out_fp.write('')  # Touch file")
                appendLine("_err_fp.write('')")
                appendLine()
                // Replace sys.stdout/stderr with our file handles
                appendLine("_old_out = sys.stdout")
                appendLine("_old_err = sys.stderr")
                appendLine("sys.stdout = _out_fp")
                appendLine("sys.stderr = _err_fp")
                appendLine("_nb_exit_code = 0")
                appendLine("try:")
                // Execute the raw script object injected via the namespace dict —
                // indenting source lines here would corrupt the bodies of
                // triple-quoted strings inside the user script.
                appendLine("    exec(compile(_nb_user_script, '<nuclear-boy-script>', 'exec'), globals())")
                appendLine("except SystemExit as e:")
                appendLine("    _nb_exit_code = e.code if e.code is not None else 0")
                appendLine("except:")
                appendLine("    traceback.print_exc()")
                appendLine("    _nb_exit_code = 1")
                appendLine("finally:")
                appendLine("    _out_fp.flush()")
                appendLine("    _err_fp.flush()")
                appendLine("    _out_fp.close()")
                appendLine("    _err_fp.close()")
                appendLine("    sys.stdout = _old_out")
                appendLine("    sys.stderr = _old_err")
                // Undo any SandboxPolicy monkey-patches this run may have installed
                // (open/subprocess/os.system/socket/requests) — this runs unconditionally
                // so restrictions from a sandboxed skill/tool call never leak into the
                // next script executed in this shared, long-lived Chaquopy interpreter.
                com.nuclearboy.python.PolicyEnforcer.RESTORE_SNIPPET.lineSequence().forEach {
                    appendLine("    $it")
                }
            }


            val locals = builtins().callAttr("dict")
            locals.callAttr("__setitem__", "__name__", "__main__")  // Fix: exec() runs as __main__
            locals.callAttr("__setitem__", "_nb_user_script", script)  // Raw script — no source rewriting
            builtins().callAttr("exec", wrappedScript, locals)

            val exitCode = (locals.callAttr("get", "_nb_exit_code", 0) as? Int) ?: 0

            // Read output directly from files (bypass exec() dict)
            val stdout = try { outFile.readText() } catch (_: Exception) { "" }
            val stderr = try { errFile.readText() } catch (_: Exception) { "" }

            val duration = System.currentTimeMillis() - startTime
            android.util.Log.e("NuclearBoy", "[Chaquopy] run — duration=${duration}ms, exitCode=$exitCode, stdoutLen=${stdout.length}, stderrLen=${stderr.length}")

            PythonResult(exitCode, stdout, stderr, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.e("NuclearBoy", "[Chaquopy] run — EXCEPTION after ${duration}ms: ${e.message}")
            PythonResult.failure(
                e.message ?: "Chaquopy 执行错误",
                duration,
            )
        } finally {
            // Always clean up temp files, even if an exception happened before the
            // success-path cleanup would have run (e.g. Python.getInstance()/exec()
            // throwing) — otherwise they leak into the temp dir permanently.
            try { outFile?.delete() } catch (_: Exception) {}
            try { errFile?.delete() } catch (_: Exception) {}
        }
    }

    override fun installPackage(packageName: String): Boolean {
        android.util.Log.e("NuclearBoy", "[Chaquopy] installPackage — name=$packageName")
        return try {
            val pip = Python.getInstance().getModule("pip")
            pip.callAttr("main", listOf("install", packageName))
            installedPackages.add(packageName)
            android.util.Log.e("NuclearBoy", "[Chaquopy] installPackage SUCCESS — $packageName")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to pip install $packageName")
            android.util.Log.e("NuclearBoy", "[Chaquopy] installPackage FAILED — $packageName, error=${e.message}")
            false
        }
    }

    override fun getVersion(): String {
        return try {
            val sys = Python.getInstance().getModule("sys")
            // sys.version is a string attribute, not a callable function.
            val version = "Python ${sys.get("version") ?: "3.11 (Chaquopy)"}"
            android.util.Log.e("NuclearBoy", "[Chaquopy] getVersion — $version")
            version
        } catch (e: Exception) {
            android.util.Log.e("NuclearBoy", "[Chaquopy] getVersion — fallback (Chaquopy not started)")
            "Python 3.11 (Chaquopy)"
        }
    }

    override fun getInstalledPackages(): Set<String> = installedPackages.toSet()
}
