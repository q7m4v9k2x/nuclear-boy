package com.nuclearboy.ui.chat

import com.nuclearboy.common.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.util.UUID

/** Metadata for a conversation which is no longer the active conversation. */
data class SavedConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * File-backed archive for conversations in one project.
 *
 * The active conversation remains `.agent/conversation.json`; archived turns
 * are immutable JSON snapshots under `.agent/conversations`. Keeping this
 * small and independent from the ViewModel makes the destructive "new chat"
 * action testable without a device or a Hilt graph.
 */
internal object ConversationArchiveStore {
    private const val ARCHIVE_DIR = "conversations"
    private const val JSON_SUFFIX = ".json"
    private const val MAX_LISTED_ARCHIVES = 100

    fun archive(
        workspaceRoot: File,
        projectId: String,
        messages: List<ChatMessage>,
        json: Json,
        now: Long = System.currentTimeMillis(),
    ): SavedConversation? {
        if (messages.isEmpty()) return null
        val dir = File(workspaceRoot, "$projectId/.agent/$ARCHIVE_DIR")
        if (!dir.exists() && !dir.mkdirs()) return null
        val id = "${now}_${UUID.randomUUID()}"
        val file = File(dir, "$id$JSON_SUFFIX")
        val tmp = File(dir, "$id.tmp")
        return runCatching {
            tmp.writeText(json.encodeToString(serializer(), messages))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return@runCatching null
            }
            SavedConversation(
                id = id,
                title = titleOf(messages),
                updatedAt = now,
                messageCount = messages.size,
            )
        }.getOrElse {
            tmp.delete()
            null
        }
    }

    fun list(
        workspaceRoot: File,
        projectId: String,
        json: Json,
    ): List<SavedConversation> {
        val dir = File(workspaceRoot, "$projectId/.agent/$ARCHIVE_DIR")
        return dir.listFiles { file -> file.isFile && file.name.endsWith(JSON_SUFFIX) }
            .orEmpty()
            .mapNotNull { file ->
                val messages = read(file, json) ?: return@mapNotNull null
                SavedConversation(
                    id = file.name.removeSuffix(JSON_SUFFIX),
                    title = titleOf(messages),
                    updatedAt = file.lastModified(),
                    messageCount = messages.size,
                )
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_LISTED_ARCHIVES)
    }

    fun restore(
        workspaceRoot: File,
        projectId: String,
        conversationId: String,
        json: Json,
    ): List<ChatMessage>? {
        if (conversationId.isBlank() || conversationId.contains('/') || conversationId.contains('\\')) return null
        return read(File(workspaceRoot, "$projectId/.agent/$ARCHIVE_DIR/$conversationId$JSON_SUFFIX"), json)
    }

    private fun read(file: File, json: Json): List<ChatMessage>? = runCatching {
        if (!file.isFile) return@runCatching null
        json.decodeFromString(serializer<List<ChatMessage>>(), file.readText())
    }.getOrNull()

    private fun titleOf(messages: List<ChatMessage>): String =
        messages.firstOrNull { it.role == com.nuclearboy.common.MessageRole.USER }
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            ?.takeIf { it.isNotBlank() }
            ?: "未命名对话"
}
