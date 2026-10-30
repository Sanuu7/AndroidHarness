package com.androidharness.app.ui.common

import com.androidharness.app.ui.theme.*
import com.androidharness.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The single shared screen header: flat surface, one row, hairline divider.
 *
 * Every screen uses this so chat, terminal, files, settings and providers share
 * the same chrome. [onBack] shows a back arrow, [onMenu] a menu button (never
 * both); [subtitle] is a single muted line under the title.
 *
 * The row is `heightIn(min = 60.dp)` rather than a fixed 60 so that a large
 * system font scale grows the header instead of clipping the title line.
 */
@Composable
fun AppHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val desktop = com.androidharness.app.ui.LocalDesktopLayout.current
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    Column(
        modifier = modifier
            .then(if (glass) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp, vertical = 8.dp)
                else Modifier.harnessGlassBar(if (desktop) scheme.surfaceContainerLow else scheme.surface).statusBarsPadding()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (desktop) 42.dp else 60.dp)
                .padding(start = 4.dp, end = 8.dp),
        ) {
            when {
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(if (desktop) Icons.Outlined.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (desktop) uiContext.getString(R.string.ui_close_panel) else uiContext.getString(R.string.settings_action_back),
                        modifier = if (desktop) Modifier.padding(4.dp) else Modifier)
                }
                onMenu != null -> IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, contentDescription = uiContext.getString(R.string.ui_open_navigation))
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (desktop) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMediumEmphasized,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            actions()
        }
        if (!glass) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
    }
}
