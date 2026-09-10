package com.androidharness.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.*
import org.junit.Test

class UserScrollObserverTest {
    @Test fun `child drag pauses follow before scrolling without consuming its movement`() {
        var following = true
        val observer = UserScrollObserver { following = false }
        assertEquals(Offset.Zero, observer.onPreScroll(Offset(0f, 8f), NestedScrollSource.UserInput))
        assertFalse(following)
        following = true
        assertEquals(Offset.Zero, observer.onPreScroll(Offset(0f, -8f), NestedScrollSource.UserInput))
        assertFalse(following)
    }

    @Test fun `automatic following flings and horizontal swipes do not trigger a new user drag`() {
        var calls = 0
        val observer = UserScrollObserver { calls++ }
        observer.onPreScroll(Offset(0f, 8f), NestedScrollSource.SideEffect)
        observer.onPreScroll(Offset(8f, 0f), NestedScrollSource.UserInput)
        observer.onPreScroll(Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(0, calls)
    }
}
