package com.androidharness.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Screenshot blocking (FLAG_SECURE) decisions for the activity window.
 *
 * Credential screens (settings, providers, setup) raise the counter while
 * composed so keys and tokens never appear in screenshots or the recents
 * preview, whatever the user's setting says. A counter rather than a flag
 * because navigation transitions overlap the outgoing and incoming screen,
 * and a plain boolean would let the outgoing screen's dispose clobber the
 * incoming screen's enter.
 */
class ScreenshotPolicy {

    private val activeScreens = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while at least one credential-bearing screen is composed. */
    val credentialScreenVisible = MutableStateFlow(false)

    /** The screen entered composition. */
    fun enter() {
        credentialScreenVisible.value = activeScreens.incrementAndGet() > 0
    }

    /** The screen left composition. */
    fun exit() {
        // Clamp at zero so an unbalanced exit can't eat a later screen's enter.
        val n = activeScreens.updateAndGet { prev -> maxOf(prev - 1, 0) }
        credentialScreenVisible.value = n > 0
    }

    companion object {
        /** Blocked unless the user allowed screenshots AND no credential screen is up. */
        fun blocked(allowScreenshots: Boolean, credentialScreenVisible: Boolean): Boolean =
            !allowScreenshots || credentialScreenVisible
    }
}
