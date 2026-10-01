package com.androidharness.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable

/**
 * Chat rows change shape while streaming and while history metadata loads.
 * Issues #5/#6 crash in AndroidPrefetchScheduler while those rows are reused.
 * Keep this list lazy, but compose rows only when needed by visible measurement.
 * Apply this for the list's whole lifetime, including stream commits and history
 * loading, rather than switching strategies with prefetch work already queued.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberChatListState(): LazyListState =
    rememberLazyListState(prefetchStrategy = ChatListPrefetchStrategy)

@OptIn(ExperimentalFoundationApi::class)
private object ChatListPrefetchStrategy : LazyListPrefetchStrategy {
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}

/** Only reuse a composition for the same kind of chat row. */
internal enum class ChatListContentType {
    Empty, SearchTarget, User, Thinking, AssistantText, Activity,
    Subagents, Subagent, Tools, Tool, System, Approval, Environment, Question, Plan,
}
