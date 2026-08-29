package com.androidharness.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.androidharness.app.AppContainer

/**
 * Marks the current screen as credential-bearing for the FLAG_SECURE policy
 * (see ScreenshotPolicy): while composed, screenshots and the recents preview
 * are blocked regardless of the allow-screenshots setting. Call once at the
 * top of any screen that shows API keys, tokens, or OAuth state.
 */
@Composable
fun SecureScreenEffect(container: AppContainer) {
    DisposableEffect(Unit) {
        container.screenshotPolicy.enter()
        onDispose { container.screenshotPolicy.exit() }
    }
}
