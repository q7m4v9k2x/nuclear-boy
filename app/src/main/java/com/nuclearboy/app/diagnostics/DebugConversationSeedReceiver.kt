package com.nuclearboy.app.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.nuclearboy.app.BuildConfig
import com.nuclearboy.common.AppConstants
import com.nuclearboy.common.ChatMessage
import com.nuclearboy.common.MessageRole
import com.nuclearboy.common.MessageStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

class DebugConversationSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEED_CONVERSATION && intent.action != ACTION_CLEAR_CONVERSATION) return
        if (!BuildConfig.DEBUG) {
            Log.e(TAG, "ignored in non-debug build")
            return
        }

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "__general__"
        val userContent = intent.decodedStringExtra(EXTRA_USER_CONTENT_B64)
            ?: intent.getStringExtra(EXTRA_USER_CONTENT)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_USER_CONTENT
        val assistantContent = intent.decodedStringExtra(EXTRA_ASSISTANT_CONTENT_B64)
            ?: intent.getStringExtra(EXTRA_ASSISTANT_CONTENT)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TOOL_LIMIT_CONTENT

        try {
            val root = File(context.getExternalFilesDir(null), AppConstants.APP_DOCUMENTS_DIR)
            val file = File(root, "$projectId/.agent/conversation.json")
            if (intent.action == ACTION_CLEAR_CONVERSATION) {
                if (file.exists() && !file.delete()) {
                    throw IllegalStateException("conversation file could not be deleted")
                }
                Log.e(TAG, "cleared conversation project=$projectId")
                return
            }
            file.parentFile?.mkdirs()
            val messages = listOf(
                ChatMessage(
                    role = MessageRole.USER,
                    content = userContent,
                    status = MessageStatus.COMPLETE,
                ),
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = assistantContent,
                    status = MessageStatus.COMPLETE,
                ),
            )
            file.writeText(Json.encodeToString(serializer(), messages))
            Log.e(
                TAG,
                "seeded conversation project=$projectId messages=${messages.size} userLen=${userContent.length} assistantLen=${assistantContent.length} path=${file.absolutePath}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "seed failed project=$projectId error=${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "NuclearBoyDebugConversation"
        const val ACTION_SEED_CONVERSATION = "com.nuclearboy.app.DEBUG_SEED_CONVERSATION"
        const val ACTION_CLEAR_CONVERSATION = "com.nuclearboy.app.DEBUG_CLEAR_CONVERSATION"
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_USER_CONTENT = "user_content"
        const val EXTRA_USER_CONTENT_B64 = "user_content_b64"
        const val EXTRA_ASSISTANT_CONTENT = "assistant_content"
        const val EXTRA_ASSISTANT_CONTENT_B64 = "assistant_content_b64"

        private const val DEFAULT_USER_CONTENT = "请真实读取 skills/app-dialog-smoke/SKILL.md"
        private const val DEFAULT_TOOL_LIMIT_CONTENT =
            "工具受限，未真实执行。当前第三方网关不支持工具调用协议，本轮不能读取、写入、运行或测试；请切换支持工具调用的模型/网关后重试。"
    }
}

private fun Intent.decodedStringExtra(name: String): String? {
    val raw = getStringExtra(name)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()
}
