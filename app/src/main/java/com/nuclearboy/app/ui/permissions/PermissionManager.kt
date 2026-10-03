package com.nuclearboy.app.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.util.Log

/**
 * 权限分批请求管理器
 *
 * 策略：按信任等级分四批申请，避免一次性吓跑用户：
 *   第一阶段（首次打开）— 通知 + 相机 + 闪光灯
 *   第二阶段（用户信任后）— 日历 + 位置 + 录音 + 电话状态
 *   第三阶段（深度使用）— 短信 + 联系人 + 悬浮窗
 *   第四阶段（专业模式）— 无障碍 + 通知监听 + 设备管理
 */
object PermissionManager {

    private const val TAG = "NuclearBoy"
    private const val TAG_P = "[PermissionMgr]"

    // ── 权限分组 ───────────────────────────────────

    /** 第一阶段：基础功能必须的 */
    val PHASE1_BASIC = buildList {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.CAMERA)
        // 闪光灯在 Manifest 中声明即可，Android 把它归入 CAMERA 权限组
    }

    /** 第二阶段：增强功能 */
    val PHASE2_ENHANCED = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
    )

    /** 第三阶段：深度交互 */
    val PHASE3_DEEP = listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
    )

    /** 第四阶段：系统级控制（需跳转设置页手动开启） */
    val PHASE4_SYSTEM = listOf(
        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,   // 通知监听
        Settings.ACTION_ACCESSIBILITY_SETTINGS,            // 无障碍
        Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, // 勿扰
        Settings.ACTION_USAGE_ACCESS_SETTINGS,             // 使用统计
    )

    // ── 检查 ───────────────────────────────────────

    /** 检查一组权限是否全部授予 */
    fun allGranted(context: Context, permissions: List<String>): Boolean {
        return permissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 获取尚未授权的权限列表 */
    fun notGranted(context: Context, permissions: List<String>): List<String> {
        return permissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** 检查已授权权限的摘要 */
    fun grantedSummary(context: Context, permissions: List<String>): String {
        val granted = permissions.count {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        return "$granted/${permissions.size}"
    }

    /** 检查系统级权限是否已启用 */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(context.packageName) == true
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(context.packageName) == true
    }

    fun canWriteSettings(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }

    /**
     * Whether the app can access arbitrary shared-storage paths.  Android's
     * scoped-storage rules make READ/WRITE_EXTERNAL_STORAGE insufficient on
     * Android 11+; the explicit "all files access" special permission is
     * required for the optional global Python scope.
     */
    fun isAllFilesAccessGranted(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    /** Open the system page where the user can grant all-files access. */
    fun openAllFilesAccessSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Some vendor ROMs expose only the global storage settings page.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    // ── 打开系统设置页 ─────────────────────────────

    /** 打开应用详情设置页 */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 打开通知监听设置 */
    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 打开无障碍设置 */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 打开悬浮窗设置 */
    fun openOverlaySettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 打开写入系统设置 */
    fun openWriteSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ── 全量权限状态日志 ──────────────────────────

    /** 打印所有关键权限的状态到 logcat */
    fun logAllPermissions(context: Context) {
        val all = listOf(
            "CAMERA" to Manifest.permission.CAMERA,
            "RECORD_AUDIO" to Manifest.permission.RECORD_AUDIO,
            "FINE_LOCATION" to Manifest.permission.ACCESS_FINE_LOCATION,
            "COARSE_LOCATION" to Manifest.permission.ACCESS_COARSE_LOCATION,
            "READ_CONTACTS" to Manifest.permission.READ_CONTACTS,
            "SEND_SMS" to Manifest.permission.SEND_SMS,
            "READ_SMS" to Manifest.permission.READ_SMS,
            "CALL_PHONE" to Manifest.permission.CALL_PHONE,
            "READ_PHONE_STATE" to Manifest.permission.READ_PHONE_STATE,
            "READ_CALENDAR" to Manifest.permission.READ_CALENDAR,
            "BODY_SENSORS" to Manifest.permission.BODY_SENSORS,
            "POST_NOTIFICATIONS" to Manifest.permission.POST_NOTIFICATIONS,
        )
        Log.e(TAG, "$TAG_P ═══ 权限状态 ═══")
        for ((name, perm) in all) {
            val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            Log.e(TAG, "$TAG_P   ${if (granted) "✅" else "❌"} $name")
        }
        Log.e(TAG, "$TAG_P 通知监听: ${if (isNotificationListenerEnabled(context)) "✅" else "❌"}")
        Log.e(TAG, "$TAG_P 写入设置: ${if (canWriteSettings(context)) "✅" else "❌"}")
        Log.e(TAG, "$TAG_P 全部文件访问: ${if (isAllFilesAccessGranted(context)) "✅" else "❌"}")
        Log.e(TAG, "$TAG_P 无障碍: ${if (isAccessibilityServiceEnabled(context)) "✅" else "❌"}")
    }
}
