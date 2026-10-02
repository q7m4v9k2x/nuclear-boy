package com.nuclearboy.common

import android.content.Context

/**
 * 轻量应用设置存储（SharedPreferences）。目前承载"自定义指令"——
 * 用户可写入自己的人设/规则，追加进系统提示，让核弹男孩按个人偏好回复。
 */
class AppSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nuclearboy_app_settings", Context.MODE_PRIVATE)

    /** 自定义指令（追加到系统提示）。 */
    fun customInstructions(): String = prefs.getString(KEY_CUSTOM_INSTRUCTIONS, "") ?: ""

    fun setCustomInstructions(text: String) {
        prefs.edit().putString(KEY_CUSTOM_INSTRUCTIONS, text.trim()).apply()
    }

    /**
     * The project that was open when the app was last used.  Chat history is
     * stored per project, so restoring this value on launch keeps the user in
     * the same conversation instead of silently opening the empty general
     * chat every time the process is recreated.
     */
    fun lastProjectId(): String? = prefs.getString(KEY_LAST_PROJECT_ID, null)

    fun setLastProjectId(projectId: String) {
        val normalized = projectId.trim()
        if (normalized.isEmpty()) return
        // This value is written exactly when the user changes projects and is
        // needed on the very next cold start. Commit it synchronously so an
        // immediate force-stop cannot lose the selection queued by apply().
        prefs.edit().putString(KEY_LAST_PROJECT_ID, normalized).commit()
    }

    private companion object {
        const val KEY_CUSTOM_INSTRUCTIONS = "custom_instructions"
        const val KEY_LAST_PROJECT_ID = "last_project_id"
    }
}
