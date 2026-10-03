package com.nuclearboy.ui.chat.parts

internal fun shouldFollowChatScroll(
    totalItemsCount: Int,
    lastVisibleItemIndex: Int?,
    distanceFromBottomPx: Int? = null,
    trailingItemThreshold: Int = 1,
    bottomDistanceThresholdPx: Int = 96,
): Boolean {
    if (totalItemsCount <= 0) return true
    if (lastVisibleItemIndex == null) return true
    // Seeing an earlier item at the bottom of the viewport means the user is
    // above the conversation tail. Its bottom can still be flush with the
    // viewport, so checking only the pixel gap would incorrectly keep
    // following a growing assistant bubble.
    if (lastVisibleItemIndex < totalItemsCount - 1) return false
    // The streaming assistant bubble can be the last visible item while it is
    // growing.  An item-index-only check therefore says "at bottom" even when
    // the user has scrolled well up inside that same bubble, causing every SSE
    // chunk to yank the viewport back down.  Prefer the measured pixel distance
    // whenever Compose provides it; retain the index fallback for callers that
    // only have coarse list information.
    distanceFromBottomPx?.let { distance ->
        return distance <= bottomDistanceThresholdPx
    }
    val lastIndex = totalItemsCount - 1
    return lastIndex - lastVisibleItemIndex <= trailingItemThreshold
}

internal fun shouldShowJumpToBottom(
    totalItemsCount: Int,
    lastVisibleItemIndex: Int?,
    distanceFromBottomPx: Int? = null,
    trailingItemThreshold: Int = 1,
    bottomDistanceThresholdPx: Int = 96,
): Boolean {
    return totalItemsCount > 0 && !shouldFollowChatScroll(
        totalItemsCount = totalItemsCount,
        lastVisibleItemIndex = lastVisibleItemIndex,
        distanceFromBottomPx = distanceFromBottomPx,
        trailingItemThreshold = trailingItemThreshold,
        bottomDistanceThresholdPx = bottomDistanceThresholdPx,
    )
}
