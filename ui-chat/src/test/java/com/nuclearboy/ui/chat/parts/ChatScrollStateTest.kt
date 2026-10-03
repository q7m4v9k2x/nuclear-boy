package com.nuclearboy.ui.chat.parts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollStateTest {
    @Test
    fun streamingLastItemStopsFollowingWhenUserScrolledInsideIt() {
        assertFalse(
            shouldFollowChatScroll(
                totalItemsCount = 8,
                lastVisibleItemIndex = 7,
                distanceFromBottomPx = 640,
            ),
        )
        assertTrue(
            shouldShowJumpToBottom(
                totalItemsCount = 8,
                lastVisibleItemIndex = 7,
                distanceFromBottomPx = 640,
            ),
        )
    }

    @Test
    fun nearBottomStillFollowsStreamingChunks() {
        assertTrue(
            shouldFollowChatScroll(
                totalItemsCount = 8,
                lastVisibleItemIndex = 7,
                distanceFromBottomPx = 32,
            ),
        )
        assertFalse(
            shouldShowJumpToBottom(
                totalItemsCount = 8,
                lastVisibleItemIndex = 7,
                distanceFromBottomPx = 32,
            ),
        )
    }

    @Test
    fun earlierVisibleItemNeverCountsAsConversationBottom() {
        assertFalse(
            shouldFollowChatScroll(
                totalItemsCount = 9,
                lastVisibleItemIndex = 4,
                distanceFromBottomPx = 0,
            ),
        )
        assertTrue(
            shouldShowJumpToBottom(
                totalItemsCount = 9,
                lastVisibleItemIndex = 4,
                distanceFromBottomPx = 0,
            ),
        )
    }

    @Test
    fun coarseIndexFallbackRemainsCompatible() {
        assertTrue(
            shouldFollowChatScroll(
                totalItemsCount = 8,
                lastVisibleItemIndex = 7,
            ),
        )
        assertFalse(
            shouldFollowChatScroll(
                totalItemsCount = 8,
                lastVisibleItemIndex = 5,
            ),
        )
    }
}
