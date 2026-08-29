package com.androidharness.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmoledSurfacesTest {

    @Test
    fun `background and surface go true black`() {
        val amoled = DarkColors.amoledSurfaces()
        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.Black, amoled.surfaceContainerLowest)
    }

    @Test
    fun `container ramp stays ordered and distinct`() {
        val amoled = DarkColors.amoledSurfaces()
        val ramp = listOf(
            amoled.surfaceContainerLowest,
            amoled.surfaceContainerLow,
            amoled.surfaceContainer,
            amoled.surfaceContainerHigh,
            amoled.surfaceContainerHighest,
        )
        ramp.zipWithNext().forEach { (low, high) ->
            assertTrue("$low must be darker than $high", low.luminanceish() < high.luminanceish())
        }
    }

    @Test
    fun `accent roles survive the transform`() {
        val amoled = DarkColors.amoledSurfaces()
        assertEquals(DarkColors.primary, amoled.primary)
        assertEquals(DarkColors.onSurface, amoled.onSurface)
        assertEquals(DarkColors.outline, amoled.outline)
    }

    /** Cheap brightness ordering: max channel, enough for ramp checks. */
    private fun Color.luminanceish(): Float = maxOf(red, green, blue)
}
