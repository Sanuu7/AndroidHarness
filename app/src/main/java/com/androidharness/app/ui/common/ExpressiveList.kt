package com.androidharness.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.settings.SettingsInnerCorner
import com.androidharness.app.ui.settings.SettingsOuterCorner
import com.androidharness.app.ui.settings.settingsHeroShape
import com.androidharness.app.ui.settings.settingsPanelBorder
import com.androidharness.app.ui.theme.HarnessGlassSurface
import com.androidharness.app.ui.theme.expressiveDefaultSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.launch

/*
 * Expressive list pieces for the workspace, project and file surfaces. They follow the
 * settings vocabulary (tonal segments a hair apart, corners that round off under the
 * finger) and add what those lists need on top: long press, a dense variant for file
 * listings, a sheet header and an empty state.
 */

/**
 * One row of a segmented list. Row [index] of [count] takes the big outer corners at
 * the ends of the group; pressing or selecting it springs every corner out to the
 * full radius.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExpressiveListRow(
    index: Int,
    count: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    dense: Boolean = false,
    titleColor: Color = Color.Unspecified,
    subtitleFont: FontFamily? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val round = pressed || selected
    val top by animateDpAsState(if (round || index == 0) SettingsOuterCorner else SettingsInnerCorner,
        expressiveSpatialSpec(), label = "rowTop")
    val bottom by animateDpAsState(if (round || index == count - 1) SettingsOuterCorner else SettingsInnerCorner,
        expressiveSpatialSpec(), label = "rowBottom")
    // A spring can undershoot, and a corner size below zero is rejected.
    val t = top.coerceAtLeast(0.dp)
    val b = bottom.coerceAtLeast(0.dp)
    val shape = RoundedCornerShape(topStart = t, topEnd = t, bottomStart = b, bottomEnd = b)
    val fill by animateColorAsState(if (selected) scheme.secondaryContainer else color, expressiveEffectsSpec(), label = "rowFill")
    val onFill by animateColorAsState(if (selected) scheme.onSecondaryContainer else scheme.onSurface, expressiveEffectsSpec(), label = "rowContent")
    HarnessGlassSurface(modifier = modifier.fillMaxWidth(), shape = shape, color = fill, contentColor = onFill, border = settingsPanelBorder()) {
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                    interactionSource = interaction, indication = ripple(), enabled = enabled,
                    onClick = { onClick?.invoke() }, onLongClick = onLongClick,
                ) else Modifier)
                .heightIn(min = if (dense) 56.dp else 64.dp)
                .padding(start = 16.dp, end = if (dense) 6.dp else 14.dp, top = if (dense) 8.dp else 12.dp, bottom = if (dense) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (dense) 12.dp else 14.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = if (dense) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                    color = titleColor, maxLines = if (dense) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, fontFamily = subtitleFont,
                    color = if (selected) onFill.copy(alpha = 0.8f) else scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

/** Tonal badge behind a leading icon. The shape squares off a little while its row is selected. */
@Composable
internal fun ExpressiveIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    selected: Boolean = false,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    tint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) size * 0.3f else size * 0.5f, expressiveSpatialSpec(), label = "badgeCorner")
    val fill by animateColorAsState(if (selected) scheme.primary else container, expressiveEffectsSpec(), label = "badgeFill")
    val on by animateColorAsState(if (selected) scheme.onPrimary else tint, expressiveEffectsSpec(), label = "badgeTint")
    Box(modifier.size(size).background(fill, RoundedCornerShape(corner.coerceAtLeast(0.dp))), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = on)
    }
}

/** Short state next to a title, as a tonal pill. */
@Composable
internal fun ExpressivePill(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.background(if (strong) scheme.primary else scheme.surfaceContainerHighest, CircleShape)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1,
            style = MaterialTheme.typography.labelMedium, color = if (strong) scheme.onPrimary else scheme.onSurfaceVariant)
    }
}

/** Title block of a bottom sheet, with a tonal close button. */
@Composable
internal fun ExpressiveSheetHeader(title: String, subtitle: String?, closeLabel: String, onClose: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ExpressiveIconButton(onClick = onClose, enabled = enabled,
            colors = IconButtonDefaults.filledTonalIconButtonColors()) { Icon(Icons.Rounded.Close, closeLabel) }
    }
}

/** A small caption above a group of rows. */
@Composable
internal fun ExpressiveSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary)
}

/** Refresh button whose icon takes one springy turn per tap, so the tap is acknowledged even when nothing changes. */
@Composable
internal fun ExpressiveRefreshButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val turn = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    ExpressiveIconButton(onClick = {
        onClick()
        scope.launch { turn.snapTo(0f); turn.animateTo(360f, expressiveDefaultSpatialSpec()) }
    }, modifier = modifier) {
        Icon(Icons.Outlined.Refresh, contentDescription, Modifier.graphicsLayer { rotationZ = turn.value })
    }
}

/** An empty list: a Material shape that springs in with a small turn, and one line of text. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveEmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, seed: Int = 0) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, expressiveDefaultSpatialSpec()) }
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(88.dp).graphicsLayer {
            val p = entrance.value
            scaleX = 0.6f + 0.4f * p; scaleY = 0.6f + 0.4f * p; rotationZ = -40f * (1f - p)
        }.background(MaterialTheme.colorScheme.secondaryContainer, settingsHeroShape(seed)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(36.dp).graphicsLayer { rotationZ = 40f * (1f - entrance.value) },
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

internal fun workspaceKindIcon(kind: String): ImageVector = when (kind) {
    "SSH" -> Icons.Outlined.Dns
    "APP" -> Icons.Outlined.Home
    "SHELL" -> Icons.Outlined.Folder
    else -> Icons.Outlined.FolderOpen
}
