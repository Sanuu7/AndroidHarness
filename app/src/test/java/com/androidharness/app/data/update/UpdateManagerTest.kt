package com.androidharness.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

class UpdateManagerTest {
    @Test fun `release tag wins over marketing name and trailing dates`() {
        assertEquals("v3.2-release", UpdateManager.releaseVersion("v3.2-release", "AndroidHarness October 31 2026"))
        assertTrue(UpdateManager.isNewer("3.2 (build 2026-10-31)", "3.1"))
        assertFalse(UpdateManager.isNewer("3.1 (build 2026-10-31)", "3.2"))
    }

    @Test fun `selects highest compatible version including prereleases instead of first entry`() {
        val entries = Json.parseToJsonElement("""[
          {"tag_name":"v2.9","name":"Older release republished today","assets":[{"name":"app.apk","browser_download_url":"https://github.com/app.apk"}]},
          {"tag_name":"v3.2","name":"October 31 2026","prerelease":true,"assets":[{"name":"app.apk","browser_download_url":"https://github.com/app.apk"}]},
          {"tag_name":"v9.0","draft":true,"assets":[{"name":"app.apk","browser_download_url":"https://github.com/app.apk"}]},
          {"tag_name":"v8.0","assets":[{"name":"app-x86_64.apk","browser_download_url":"https://github.com/app.apk"}]},
          {"tag_name":"v7.0","assets":[]}
        ]""").jsonArray
        assertEquals("3.2", UpdateManager.selectRelease(entries, listOf("arm64-v8a"))?.tag)
    }

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
