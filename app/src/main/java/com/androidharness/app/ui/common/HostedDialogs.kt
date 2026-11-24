package com.androidharness.app.ui.common

import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.DialogProperties

/**
 * A window that is not an activity, such as the assistant panel over other apps, can only
 * show a dialog that names it as its parent; without that Android refuses the dialog and
 * the app crashes. Such a window provides its token here. Activities provide nothing.
 */
internal class DialogHost(val windowType: Int, val token: () -> IBinder?)

internal val LocalDialogHost = staticCompositionLocalOf<DialogHost?> { null }

/** [base] unchanged inside an activity, or attached to the hosting window when there is one. */
@Composable
internal fun hostedDialog(base: DialogProperties = DialogProperties()): DialogProperties {
    val host = LocalDialogHost.current ?: return base
    return DialogProperties(
        dismissOnBackPress = base.dismissOnBackPress,
        dismissOnClickOutside = base.dismissOnClickOutside,
        securePolicy = base.securePolicy,
        usePlatformDefaultWidth = base.usePlatformDefaultWidth,
        decorFitsSystemWindows = base.decorFitsSystemWindows,
        windowTitle = base.windowTitle,
        windowType = host.windowType,
        windowToken = host.token(),
    )
}
