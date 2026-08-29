package com.androidharness.app.ui.common

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.androidharness.app.AppContainer

/**
 * Marks the current screen as secret-bearing for the FLAG_SECURE policy
 * (see ScreenshotPolicy): while [active], screenshots and the recents preview
 * are blocked even when the user allowed screenshots. Call once at the top of
 * any surface that puts an API key, token, or OAuth state on screen, with
 * [active] true exactly while the secret is visible.
 */
@Composable
fun SecureScreenEffect(container: AppContainer, active: Boolean = true) {
    DisposableEffect(active) {
        if (active) container.screenshotPolicy.enter()
        onDispose { if (active) container.screenshotPolicy.exit() }
    }
}

/**
 * FLAG_SECURE for the content of a Compose [androidx.compose.ui.window.Dialog]
 * or [androidx.compose.material3.ModalBottomSheet]. Dialogs live in their own
 * window, so the activity-level flag never covers them; without this, a
 * screenshot over a secret-entry dialog captures the dialog on a black
 * background. Call inside the dialog's content slot.
 */
@Composable
fun SecureDialogEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = runCatching { view.parent as? DialogWindowProvider }.getOrNull()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
