package com.nuclearboy.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 更新检查器
 *
 * 检查链：GitHub Releases（q7m4v9k2x/nuclear-boy）
 * - 启动时后台静默检查
 * - 发现新版本 → 发送系统通知
 * - 设置页手动检查 + 版本号显示
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "NuclearBoy"
        private const val TAG_U = "[UpdateMgr]"

        private const val GITHUB_API =
            "https://api.github.com/repos/q7m4v9k2x/nuclear-boy/releases/latest"

        private const val CHANNEL_ID = "update_channel"
        private const val CHANNEL_NAME = "应用更新"
        private const val NOTIFICATION_ID = 2000

        // 上次检查时间戳（Sp Key）
        private const val PREFS_NAME = "nuclear_update"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_LAST_VERSION = "last_known_version"

        // 检查间隔：6小时
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 数据模型 ────────────────────────────────────

    /** GitHub Release and its downloadable APK assets. */
    @Serializable
    data class GitHubRelease(
        val tag_name: String = "",
        val name: String = "",
        val body: String = "",
        val html_url: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        val browser_download_url: String = "",
        val content_type: String = "",
        val size: Long = 0L,
        // GitHub's API returns this as `sha256:<hex>` for newly uploaded assets.
        // Older releases may omit it, so verification remains backward compatible.
        val digest: String? = null,
    )

    sealed class UpdateResult {
        data class Available(
            val version: String,
            val url: String,
            val body: String,
            val releaseUrl: String,
            val expectedSize: Long = 0L,
            val expectedDigest: String = "",
            val force: Boolean = false,
        ) : UpdateResult()
        object UpToDate : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    // ── 版本比较 ────────────────────────────────────

    /** 解析版本号：v0.6.0 → [0, 6, 0] */
    private fun parseVersion(tag: String): List<Int> {
        return tag.removePrefix("v").removePrefix("V")
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }

    /** 返回 true 表示 latest 比 current 新 */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai > bi) return true
            if (ai < bi) return false
        }
        return false
    }

    // ── 主逻辑 ──────────────────────────────────────

    /** Check the repository's latest published GitHub Release. */
    suspend fun checkForUpdate(force: Boolean = false): UpdateResult {
        return withContext(Dispatchers.IO) {
            if (!force) {
                val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
                if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                    Log.d(TAG, "$TAG_U 跳过检查：距上次不足6小时")
                    return@withContext UpdateResult.UpToDate
                }
            }

            return@withContext checkGitHub()
        }
    }

    /** Check GitHub and return a direct APK URL for this build variant. */
    private suspend fun checkGitHub(): UpdateResult {
        try {
            Log.e(TAG, "$TAG_U 检查 GitHub: $GITHUB_API")
            val request = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NuclearBoy-UpdateChecker/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (body == null) {
                    return UpdateResult.Error("GitHub 返回空响应")
                }
                if (!response.isSuccessful) {
                    Log.e(TAG, "$TAG_U GitHub API返回: ${response.code}")
                    return UpdateResult.Error("GitHub HTTP ${response.code}")
                }

                val release = json.decodeFromString<GitHubRelease>(body)
                val latestVersion = release.tag_name.trim()
                val currentVersion = getCurrentVersion()
                Log.e(TAG, "$TAG_U GitHub: $latestVersion | 当前: $currentVersion")
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                if (latestVersion.isBlank() || release.draft || release.prerelease || !isNewer(latestVersion, currentVersion)) {
                    Log.e(TAG, "$TAG_U 已是最新或 Release 不可发布")
                    return UpdateResult.UpToDate
                }

                val asset = selectApkAsset(release.assets)
                    ?: return UpdateResult.Error("GitHub Release 未提供适配当前构建的 APK")
                val downloadUrl = asset.browser_download_url.trim()
                if (!downloadUrl.startsWith("https://", ignoreCase = true)) {
                    return UpdateResult.Error("GitHub APK 下载地址不是 HTTPS")
                }

                val lastKnown = prefs.getString(KEY_LAST_VERSION, "")
                if (lastKnown != latestVersion) {
                    prefs.edit().putString(KEY_LAST_VERSION, latestVersion).apply()
                    showUpdateNotification(
                        version = latestVersion,
                        downloadUrl = downloadUrl,
                        body = release.body,
                        expectedSize = asset.size,
                        expectedDigest = asset.digest.orEmpty(),
                    )
                }
                Log.e(TAG, "$TAG_U 发现新版本: $latestVersion APK=${asset.name}")
                return UpdateResult.Available(
                    version = latestVersion,
                    url = downloadUrl,
                    body = release.body,
                    releaseUrl = release.html_url,
                    expectedSize = asset.size,
                    expectedDigest = asset.digest.orEmpty(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "$TAG_U GitHub检查失败: ${e.message}")
            return UpdateResult.Error(e.message ?: "GitHub 检查失败")
        }
    }

    private fun selectApkAsset(assets: List<GitHubAsset>): GitHubAsset? {
        val apkAssets = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                asset.browser_download_url.startsWith("https://", ignoreCase = true)
        }
        if (apkAssets.isEmpty()) return null

        val isDebugBuild = context.packageName.endsWith(".debug")
        val variant = if (isDebugBuild) "debug" else "release"
        return apkAssets.firstOrNull { it.name.contains(variant, ignoreCase = true) }
            ?: apkAssets.firstOrNull { !it.name.contains(if (isDebugBuild) "release" else "debug", ignoreCase = true) }
    }

    /** 自动检查（启动时调用，静默） */
    suspend fun autoCheck() {
        when (val result = checkForUpdate(force = false)) {
            is UpdateResult.Available -> {
                Log.e(TAG, "$TAG_U 自动检查发现新版本: ${result.version}")
            }
            is UpdateResult.UpToDate -> Log.d(TAG, "$TAG_U 自动检查：已是最新")
            is UpdateResult.Error -> Log.d(TAG, "$TAG_U 自动检查失败: ${result.message}")
        }
    }

    // ── 通知 ────────────────────────────────────────

    private fun showUpdateNotification(
        version: String,
        downloadUrl: String,
        body: String,
        expectedSize: Long,
        expectedDigest: String,
    ) {
        createNotificationChannel()

        // 点击通知 → 触发应用内下载
        val downloadIntent = Intent(context, DownloadReceiver::class.java).apply {
            putExtra("url", downloadUrl)
            putExtra("version", version)
            // Keep the values that were checked against the GitHub API with the
            // notification click. The receiver may run much later.
            putExtra("expected_size", expectedSize)
            putExtra("expected_digest", expectedDigest)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("☢️ NUCLEAR BOY $version 可用！")
            .setContentText("点击下载最新版本 — ${body.take(100).replace("\n", " ")}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(500)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
        Log.e(TAG, "$TAG_U 已发送更新通知: $version")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "NUCLEAR BOY 版本更新推送"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    // ── 工具 ────────────────────────────────────────

    fun getCurrentVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }
}
