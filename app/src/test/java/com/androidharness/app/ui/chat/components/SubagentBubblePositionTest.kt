package com.androidharness.app.ui.chat.components

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class SubagentBubblePositionTest {
    @Test fun `expanding keeps the round button fixed above the composer accessories`() {
        val position = SubagentBubblePositionProvider(gap = 16, edge = 16)
        val anchor = IntRect(0, 500, 380, 500)
        val collapsedSize = IntSize(48, 48)
        val expandedSize = IntSize(320, 240)
        val collapsed = position.calculatePosition(anchor, IntSize(380, 700), LayoutDirection.Ltr, collapsedSize)
        val expanded = position.calculatePosition(anchor, IntSize(380, 700), LayoutDirection.Ltr, expandedSize)
        assertEquals(IntOffset(316, 436), collapsed)
        assertEquals(collapsed.x + collapsedSize.width, expanded.x + expandedSize.width)
        assertEquals(collapsed.y + collapsedSize.height, expanded.y + expandedSize.height)
        assertEquals(anchor.top - 16, expanded.y + expandedSize.height)
    }

    @Test fun `desktop bubble follows the chat column instead of the window edge`() {
        val position = SubagentBubblePositionProvider(16, 16)
        val offset = position.calculatePosition(IntRect(200, 500, 1080, 500), IntSize(1280, 800),
            LayoutDirection.Ltr, IntSize(48, 48))
        assertEquals(IntOffset(1016, 436), offset)
    }
}
