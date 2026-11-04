package com.androidharness.app.ui.chat.components

import androidx.compose.ui.unit.*
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class SubagentBubblePositionTest {
    @Test fun `text button fits at the edge and stays fixed when its panel opens`() {
        val button = IntSize(120, 48)
        val provider = SubagentBubblePositionProvider(16, 16, button)
        val anchor = IntRect(0, 500, 380, 500)
        val small = provider.calculatePosition(anchor, IntSize(380, 700), LayoutDirection.Ltr, button)
        val large = provider.calculatePosition(anchor, IntSize(380, 700), LayoutDirection.Ltr, IntSize(320, 240))
        assertEquals(IntOffset(244, 436), small)
        assertEquals(small.x + button.width, large.x + 320)
        assertEquals(small.y + button.height, large.y + 240)
    }
    @Test fun `dragged bubble stays fixed when expanded near either screen edge`() {
        for (fraction in listOf(SubagentBubblePosition(0f, 0f), SubagentBubblePosition(.4f, .4f), SubagentBubblePosition(1f, 1f))) {
            var placement: SubagentBubblePlacement? = null
            val provider = SubagentBubblePositionProvider(16, 16, saved = { fraction }, onPlaced = { placement = it })
            val anchor = IntRect(0, 500, 380, 500)
            val small = provider.calculatePosition(anchor, IntSize(380, 700), LayoutDirection.Ltr, IntSize(48, 48))
            val large = provider.calculatePosition(anchor, IntSize(380, 700), LayoutDirection.Ltr, IntSize(320, 240))
            val info = requireNotNull(placement)
            assertEquals(small.x, large.x + info.bubbleOffset(320))
            assertEquals(small.y, large.y + if (info.panelAbove) 240 - 48 else 0)
            assertTrue(large.x >= 0 && large.x + 320 <= 380)
            assertEquals(fraction.y >= .5f, info.panelAbove)
        }
    }

    @Test fun `dragging clamps to the available area and normalizes invalid stored values`() {
        val area = IntRect(16, 16, 316, 436)
        assertEquals(SubagentBubblePosition(0f, 0f), SubagentBubblePosition().moved(Offset(-1000f, -1000f), area))
        assertEquals(SubagentBubblePosition(1f, 1f), SubagentBubblePosition(0f, 0f).moved(Offset(1000f, 1000f), area))
        assertEquals(SubagentBubblePosition(), SubagentBubblePosition(Float.NaN, Float.POSITIVE_INFINITY).normalized())
    }
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
