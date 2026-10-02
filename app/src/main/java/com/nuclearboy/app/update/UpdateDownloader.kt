package com.nuclearboy.app.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 下载并安装 GitHub Release APK。
 *
 * DownloadManager 的完成广播只在进程存活且动态 receiver 仍注册时可靠，
 * 所以下载记录会持久化；MainActivity 恢复时会查询 DownloadManager 并补处理。
 */
object UpdateDownloader {

    private const val TAG = "NuclearBoy"
    private const val TAG_D = "[Downloader]"

    private const val CHANNEL_ID = "download_channel"
    private const val CHANNEL_NAME = "下载进度"
    private const val NOTIFICATION_ID = 2001
    private const val PREFS_NAME = "nuclear_update_download"
    private const val KEY_ID = "download_id"
    private const val KEY_FILE = "file_path"
    private const val KEY_VERSION = "version"
    private const val KEY_SIZE = "expected_size"
    private const val KEY_DIGEST = "expected_digest"

    private val activeReceiverIds = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var permissionPromptedPath: String? = null

    /** 开始下载 APK。expected* 来自同一份 GitHub Release API 响应。 */
    fun download(
        context: Context,
        url: String,
        version: String,
        expectedSize: Long = 0L,
        expectedDigest: String = "",
    ): Long {
        val appContext = context.applicationContext
        if (!isAllowedGitHubUrl(url)) {
            Log.e(TAG, "$TAG_D 拒绝非 GitHub HTTPS 更新下载地址: $url")
            return -1L
        }
        val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir == null) {
            Log.e(TAG, "$TAG_D 外部下载目录不可用")
            return -1L
        }
        createNotificationChannel(appContext)

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // Only one update may own the persisted destination. Cancel the old job
        // first; its receiver will see the new persisted id and ignore its event.
        val previousId = prefs.getLong(KEY_ID, -1L)
        if (previousId > 0L) dm.remove(previousId)

        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(downloadDir, "nuclear-boy-v$safeVersion.apk")
        file.parentFile?.mkdirs()
        file.delete()
        permissionPromptedPath = null

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("☢️ NUCLEAR BOY v$version")
            setDescription("正在下载更新…")
            setDestinationUri(Uri.fromFile(file))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setMimeType("application/vnd.android.package-archive")
        }

        val downloadId = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_D 入队失败: ${e.message}")
            return -1L
        }
        prefs.edit()
            .putLong(KEY_ID, downloadId)
            .putString(KEY_FILE, file.absolutePath)
            .putString(KEY_VERSION, version)
            .putLong(KEY_SIZE, expectedSize.coerceAtLeast(0L))
            .putString(KEY_DIGEST, expectedDigest)
            .commit()

        Log.e(TAG, "$TAG_D 开始下载: id=$downloadId, url=$url, dest=${file.absolutePath}")

        val receiver = DownloadCompleteReceiver(
            expectedId = downloadId,
            file = file,
            version = version,
            expectedSize = expectedSize,
            expectedDigest = expectedDigest,
        )
        try {
            // The completion broadcast is sent by the system DownloadManager, so
            // use an exported dynamic receiver. The expected download id plus
            // status/file verification below prevents spoofed broadcasts from
            // causing an install. ContextCompat also supplies the required flag
            // on Android U while keeping API 26 devices working.
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED,
            )
            activeReceiverIds.add(downloadId)
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_D 注册完成广播失败，将由下次启动恢复: ${e.message}")
        }
        return downloadId
    }

    /**
     * Re-register the in-process completion receiver after an app restart. The
     * DownloadManager job itself survives process death; without this, an update
     * that finishes while the app remains open would wait for the next relaunch.
     */
    fun ensurePendingDownloadReceiver(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_ID, -1L)
        if (id <= 0L || activeReceiverIds.contains(id)) return
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val status = queryStatus(dm, id)
        // Keep listening while queued, running, or temporarily paused. Only
        // terminal states need no receiver.
        if (status == null || status == DownloadManager.STATUS_SUCCESSFUL ||
            status == DownloadManager.STATUS_FAILED
        ) {
            if (status == null) clearPending(appContext)
            return
        }
        val path = prefs.getString(KEY_FILE, null)?.trim().orEmpty()
        if (path.isBlank()) return
        val receiver = DownloadCompleteReceiver(
            expectedId = id,
            file = File(path),
            version = prefs.getString(KEY_VERSION, "latest").orEmpty(),
            expectedSize = prefs.getLong(KEY_SIZE, 0L),
            expectedDigest = prefs.getString(KEY_DIGEST, "").orEmpty(),
        )
        try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED,
            )
            activeReceiverIds.add(id)
            Log.d(TAG, "$TAG_D 已恢复下载完成监听: id=$id")
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_D 恢复下载监听失败: ${e.message}")
        }
    }

    /**
     * 返回已下载且校验通过、等待安装的文件；用于应用重新启动或从未知来源设置返回。
     * 该方法只做文件/包校验，调用方应在后台线程执行。
     */
    fun pendingInstallFile(context: Context): File? {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_FILE, null)?.trim().orEmpty()
        if (path.isBlank()) return null
        val file = File(path)
        val id = prefs.getLong(KEY_ID, -1L)
        if (id > 0L) {
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val status = queryStatus(dm, id)
            if (status == null) {
                clearPending(appContext)
                return null
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                if (status == DownloadManager.STATUS_FAILED) clearPending(appContext)
                return null
            }
        }
        val valid = isValidApk(
            appContext,
            file,
            prefs.getLong(KEY_SIZE, 0L),
            prefs.getString(KEY_DIGEST, "").orEmpty(),
        )
        if (!valid) {
            Log.e(TAG, "$TAG_D 待安装 APK 校验失败: ${file.absolutePath}")
            clearPending(appContext)
            file.delete()
            return null
        }
        return file
    }

    /** 安装已下载的 APK；无未知来源授权时跳转系统授权页，授权后由 onResume 恢复。 */
    fun install(context: Context, file: File): Boolean {
        val appContext = context.applicationContext
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !appContext.packageManager.canRequestPackageInstalls()
            ) {
                rememberPendingFile(appContext, file)
                if (permissionPromptedPath == file.absolutePath) return false
                permissionPromptedPath = file.absolutePath
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(settingsIntent)
                Log.e(TAG, "$TAG_D 尚未允许安装未知应用，已打开系统授权页")
                return false
            }
            permissionPromptedPath = null
            val pendingPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!isValidApk(
                    appContext,
                    file,
                    pendingPrefs.getLong(KEY_SIZE, 0L),
                    pendingPrefs.getString(KEY_DIGEST, "").orEmpty(),
                )
            ) {
                Log.e(TAG, "$TAG_D 安装前 APK 校验失败: ${file.absolutePath}")
                clearPending(appContext)
                return false
            }
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Do not auto-open the installer repeatedly after the user cancels it.
            clearPending(appContext)
            appContext.startActivity(intent)
            Log.e(TAG, "$TAG_D 打开安装界面: ${file.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_D 安装失败: ${e.message}")
            return false
        }
    }

    private fun rememberPendingFile(context: Context, file: File) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_FILE, file.absolutePath)
            .apply()
    }

    private fun clearPending(context: Context) {
        permissionPromptedPath = null
        // This state controls whether the next process launch can reopen the
        // installer. Commit it before starting an external installer so a
        // sudden process death cannot resurrect a stale APK prompt.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun queryStatus(dm: DownloadManager, id: Long): Int? {
        return dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }

    private fun isAllowedGitHubUrl(rawUrl: String): Boolean {
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            (host == "github.com" || host == "api.github.com")
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "APK 下载进度" }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private class DownloadCompleteReceiver(
        private val expectedId: Long,
        private val file: File,
        private val version: String,
        private val expectedSize: Long,
        private val expectedDigest: String,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId != expectedId) return
            val appContext = context.applicationContext
            // A newer manual check may have replaced this download. Do not let
            // the old receiver delete the new file or clear its metadata.
            val currentId = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_ID, -1L)
            if (currentId != expectedId) {
                try { appContext.unregisterReceiver(this) } catch (_: IllegalArgumentException) { }
                return
            }
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    activeReceiverIds.remove(expectedId)
                    val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val status = queryStatus(dm, downloadId)
                    if (status == DownloadManager.STATUS_SUCCESSFUL &&
                        isValidApk(appContext, file, expectedSize, expectedDigest)
                    ) {
                        Log.e(TAG, "$TAG_D 下载成功并通过校验: ${file.length()} bytes")
                        showInstallNotification(appContext, file, version)
                        install(appContext, file)
                    } else {
                        Log.e(TAG, "$TAG_D 下载失败或校验失败: status=$status size=${file.length()}")
                        file.delete()
                        clearPending(appContext)
                    }
                } finally {
                    try {
                        appContext.unregisterReceiver(this@DownloadCompleteReceiver)
                    } catch (_: IllegalArgumentException) {
                        // Receiver may already have been removed during process teardown.
                    }
                    pendingResult.finish()
                }
            }
        }
    }

    /** APK 是 ZIP 容器，并且必须是同包名、比当前版本更新的 Android 包。 */
    private fun isValidApk(
        context: Context,
        file: File,
        expectedSize: Long,
        expectedDigest: String,
    ): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        if (expectedSize > 0L && file.length() != expectedSize) return false
        val digestSpec = expectedDigest.trim()
        if (digestSpec.isNotBlank() && !digestSpec.startsWith("sha256:", ignoreCase = true)) {
            Log.e(TAG, "$TAG_D 不支持的 GitHub digest 算法")
            return false
        }
        val digest = digestSpec.substringAfter(':', "").trim().lowercase()
        if (digest.isNotBlank()) {
            val actual = try {
                MessageDigest.getInstance("SHA-256").let { md ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            md.update(buffer, 0, read)
                        }
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "$TAG_D SHA-256 校验失败: ${e.message}")
                return false
            }
            if (!actual.equals(digest, ignoreCase = true)) return false
        } else {
            try {
                file.inputStream().use { input ->
                    if (input.read() != 'P'.code || input.read() != 'K'.code) return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "$TAG_D APK 文件头校验失败: ${e.message}")
                return false
            }
        }
        val archive = try {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_META_DATA,
            )
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_D APK 包信息读取失败: ${e.message}")
            null
        } ?: return false
        if (archive.packageName != context.packageName) return false
        val current = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_D 当前包信息读取失败: ${e.message}")
            return false
        }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION") archive.versionCode.toLong()
        }
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            current.longVersionCode
        } else {
            @Suppress("DEPRECATION") current.versionCode.toLong()
        }
        return archiveVersion > currentVersion
    }

    private fun showInstallNotification(context: Context, file: File, version: String) {
        val installIntent = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("☢️ v$version 下载完成")
            .setContentText("点击安装 NUCLEAR BOY v$version")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (canPostNotifications(context)) nm.notify(NOTIFICATION_ID + 1, notification)
    }
}
