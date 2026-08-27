package com.androidharness.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `tag styles used by this repo parse correctly`() {
        // "Alpha-v0.3" is the actual tag shape on GitHub Releases.
        assertTrue(UpdateManager.isNewer("Alpha-v0.3", "0.2-alpha"))
        assertTrue(UpdateManager.isNewer("Alpha-v1.0", "Alpha-v0.31"))
        assertFalse(UpdateManager.isNewer("Alpha-v0.3", "0.3-alpha"))
        assertFalse(UpdateManager.isNewer("Alpha-v0.3", "Alpha-v0.3"))
    }

    @Test
    fun `version names with suffixes compare by numeric core`() {
        assertTrue(UpdateManager.isNewer("0.4-alpha", "0.3-alpha"))
        // Numeric compare: segment 30 beats segment 4, so 0.30 counts as
        // newer than 0.4 - standard dot-segment semantics.
        assertTrue(UpdateManager.isNewer("0.30-alpha", "0.4-alpha"))
        assertTrue(UpdateManager.isNewer("v1.2.3", "1.2.2"))
        assertFalse(UpdateManager.isNewer("1.2.10", "1.3")) // minor wins over patch size
    }

    @Test
    fun `garbage versions never prompt`() {
        assertFalse(UpdateManager.isNewer("nightly-build", "0.3-alpha"))
        assertFalse(UpdateManager.isNewer("", "0.3-alpha"))
        assertFalse(UpdateManager.isNewer("0.5-alpha", ""))
    }

    @Test
    fun `patch-level bumps are detected`() {
        assertTrue(UpdateManager.isNewer("Alpha-v0.31", "Alpha-v0.3"))
    }
}
