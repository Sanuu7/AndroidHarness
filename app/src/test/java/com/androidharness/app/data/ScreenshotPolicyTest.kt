package com.androidharness.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPolicyTest {

    @Test
    fun `blocked is the not-allow or credential truth table`() {
        assertTrue(ScreenshotPolicy.blocked(allowScreenshots = false, credentialScreenVisible = false))
        assertTrue(ScreenshotPolicy.blocked(allowScreenshots = false, credentialScreenVisible = true))
        assertTrue(ScreenshotPolicy.blocked(allowScreenshots = true, credentialScreenVisible = true))
        assertFalse(ScreenshotPolicy.blocked(allowScreenshots = true, credentialScreenVisible = false))
    }

    @Test
    fun `enter and exit track a single screen`() {
        val policy = ScreenshotPolicy()
        assertFalse(policy.credentialScreenVisible.value)

        policy.enter()
        assertTrue(policy.credentialScreenVisible.value)

        policy.exit()
        assertFalse(policy.credentialScreenVisible.value)
    }

    @Test
    fun `overlapping navigation transition stays blocked until the last screen leaves`() {
        val policy = ScreenshotPolicy()

        // Settings on screen, then Providers pushes on top before Settings disposes.
        policy.enter()
        policy.enter()
        policy.exit() // Settings disposes while Providers is still composed.
        assertTrue(policy.credentialScreenVisible.value)

        policy.exit()
        assertFalse(policy.credentialScreenVisible.value)
    }

    @Test
    fun `excess exits do not go negative`() {
        val policy = ScreenshotPolicy()
        policy.exit()
        assertFalse(policy.credentialScreenVisible.value)

        policy.enter()
        assertTrue(policy.credentialScreenVisible.value)
    }
}
