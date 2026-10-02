package com.nuclearboy.ui.chat

import com.nuclearboy.common.ChatMessage
import com.nuclearboy.common.MessageRole
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ConversationArchiveStoreTest {
    private lateinit var root: File
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        root = createTempDir(prefix = "nuclear-boy-archive-")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun archiveListsAndRestoresMessagesWithStableTitle() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "  保留这段上下文  "),
            ChatMessage(role = MessageRole.ASSISTANT, content = "已完成"),
        )

        val archived = ConversationArchiveStore.archive(root, "__general__", messages, json, now = 1234L)

        assertNotNull(archived)
        assertEquals("保留这段上下文", archived?.title)
        val listed = ConversationArchiveStore.list(root, "__general__", json)
        assertEquals(1, listed.size)
        assertEquals(archived?.id, listed.single().id)
        assertEquals(messages, ConversationArchiveStore.restore(root, "__general__", archived!!.id, json))
    }

    @Test
    fun rejectsPathTraversalAndDoesNotArchiveEmptyConversation() {
        assertNull(ConversationArchiveStore.archive(root, "__general__", emptyList(), json))
        assertNull(ConversationArchiveStore.restore(root, "__general__", "../conversation", json))
        assertTrue(ConversationArchiveStore.list(root, "__general__", json).isEmpty())
    }
}
