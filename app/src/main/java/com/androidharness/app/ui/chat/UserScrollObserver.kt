package com.androidharness.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** Observe child drags before they move anything, without stealing scroll or edge handoff. */
internal class UserScrollObserver(private val onUserScroll: () -> Unit) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) onUserScroll()
        return Offset.Zero
    }
}
