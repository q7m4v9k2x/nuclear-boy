package com.nuclearboy.app.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 应用内 APK 下载器
 *
 * 使用 Android DownloadManager 下载更新：
 * - 自动处理网络中断、重试
 * - 系统通知栏显示下载进度
 * - 下载完成后一键安装（需 REQUEST_INSTALL_PACKAGES 权限）
 */
object UpdateDownloader {

    private const val TAG = "NuclearBoy"
    private const val TAG_D = "[Downloader]"

    private const val CHANNEL_ID = "download_channel"
    private const val CHANNEL_NAME = "下载进度"
    private const val NOTIFICATION_ID = 2001

    /** 开始下载 APK */
    fun download(context: Context, url: String, version: String): Long {
        // 强制 HTTPS：APK 会被直接拉起系统安装器，属于高信任路径。app 的
        // network_security_config 为了兼容用户自建模型网关放开了明文流量，
        // 但更新包绝不能走明文——否则可被中间人替换成任意 APK。
        if (!url.trim().startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "$TAG_D 拒绝非 HTTPS 更新下载地址: $url")
            return -1L
        }
        createNotificationChannel(context)

        val fileName = "nuclear-boy-v$version.apk"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        file.parentFile?.mkdirs()
        // 删除旧文件
        file.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("☢️ NUCLEAR BOY v$version")
            setDescription("正在下载更新…")
            setDestinationUri(Uri.fromFile(file))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setMimeType("application/vnd.android.package-archive")
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        Log.e(TAG, "$TAG_D 开始下载: id=$downloadId, url=$url, dest=${file.absolutePath}")

        // 注册下载完成广播。ACTION_DOWNLOAD_COMPLETE 是系统级广播，本 App 里其它下载
        // （或未来新增的下载）完成时也会触发这个 receiver——必须带上本次的 downloadId，
        // onReceive 里核对后才处理，否则会错在别的下载上、或提前 unregister 漏掉本次安装。
        val receiver = DownloadCompleteReceiver(downloadId, file, version)
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED,
        )

        return downloadId
    }

    /** 安装已下载的 APK */
    fun install(context: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                Log.e(TAG, "$TAG_D 尚未允许安装未知应用，已打开系统授权页")
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.e(TAG, "$TAG_D 打开安装界面: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_D 安装失败: ${e.message}")
        }
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

    /** Android 13+ 需要 POST_NOTIFICATIONS 权限才能发通知；没权限则静默跳过而不崩溃。 */
    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ── 下载完成广播 ────────────────────────────────

    private class DownloadCompleteReceiver(
        private val expectedId: Long,
        private val file: File,
        private val version: String,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            // 不是我们这次 enqueue 的下载：忽略，且绝不 unregister（否则会漏掉本次的完成广播）
            if (downloadId != expectedId) {
                Log.e(TAG, "$TAG_D 忽略无关下载完成广播: id=$downloadId (期待=$expectedId)")
                return
            }
            Log.e(TAG, "$TAG_D 下载完成广播: id=$downloadId")

            // 检查下载状态
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query)

            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                cursor.close()

                if (status == DownloadManager.STATUS_SUCCESSFUL && isApkFile(file)) {
                    Log.e(TAG, "$TAG_D 下载成功: ${file.length()} bytes")
                    showInstallNotification(context, file, version)
                    install(context, file)
                } else {
                    Log.e(TAG, "$TAG_D 下载失败或内容不是 APK: status=$status size=${file.length()}")
                    file.delete()
                }
            } else {
                cursor.close()
            }

            // 注销广播
            context.unregisterReceiver(this)
        }
    }

    /** APK 是 ZIP 容器；拒绝 GitHub 错误页或代理返回的 HTML。 */
    private fun isApkFile(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return try {
            file.inputStream().use { input ->
                input.read() == 'P'.code && input.read() == 'K'.code
            }
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_D APK 文件校验失败: ${e.message}")
            false
        }
    }

    private fun showInstallNotification(context: Context, file: File, version: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pi = PendingIntent.getActivity(
            context, 0, installIntent,
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
