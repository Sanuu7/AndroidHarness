package com.androidharness.app.core.response

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichResponseBuildingVisualTest {
    @Test fun `plain prose and ordinary code fences are not visuals`() {
        assertFalse(RichResponse.isBuildingVisual("Here is the answer so far"))
        assertFalse(RichResponse.isBuildingVisual("```kotlin\nval x = 1"))
    }

    @Test fun `an open rich fence is a visual being written`() {
        assertTrue(RichResponse.isBuildingVisual("Sure.\n\n```harness-html title=\"Orbit\"\n<div>"))
        assertTrue(RichResponse.isBuildingVisual("```harness-ui\n{\"type\":\"card\""))
        assertTrue(RichResponse.isBuildingVisual("Sure.\n```harness-"))
    }

    @Test fun `a closed visual followed by prose is writing again`() {
        val done = "```harness-svg title=\"Dot\"\n<svg viewBox=\"0 0 1 1\"><circle r=\"1\"/></svg>\n```\n\nHere is how it works."
        assertFalse(RichResponse.isBuildingVisual(done))
        assertTrue(RichResponse.isBuildingVisual("$done\n```harness-html title=\"Next\"\n<p>"))
    }
}
