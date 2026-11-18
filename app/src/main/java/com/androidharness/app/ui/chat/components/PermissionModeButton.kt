package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.ui.common.BiometricAuth
import com.androidharness.app.ui.common.ExpressiveDropdownMenu as DropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem as DropdownMenuItem
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.localizedOption
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec

/** One glyph per mode, from most careful to least: a raised hand, a shield, a bolt, an open lock. */
internal val PermissionMode.icon: ImageVector
    get() = when (this) {
        PermissionMode.CONFIRM_ALL -> Icons.Outlined.PanTool
        PermissionMode.CONFIRM_RISKY -> Icons.Outlined.Shield
        PermissionMode.FULL_AUTO -> Icons.Outlined.Bolt
        PermissionMode.FULL_ACCESS -> Icons.Outlined.LockOpen
    }

@Composable
internal fun PermissionMode.tint(): Color = when (this) {
    PermissionMode.CONFIRM_ALL -> MaterialTheme.colorScheme.onSurfaceVariant
    PermissionMode.CONFIRM_RISKY -> MaterialTheme.colorScheme.primary
    PermissionMode.FULL_AUTO -> MaterialTheme.colorScheme.error
    PermissionMode.FULL_ACCESS -> FullAccessOrange
}

@Composable
internal fun PermissionMode.description(): String = when (this) {
    PermissionMode.CONFIRM_ALL -> stringResource(R.string.settings_agent_perm_confirm_all)
    PermissionMode.CONFIRM_RISKY -> stringResource(R.string.settings_agent_perm_confirm_risky)
    PermissionMode.FULL_AUTO -> stringResource(R.string.settings_agent_perm_full_auto)
    PermissionMode.FULL_ACCESS -> stringResource(R.string.settings_agent_perm_full_access)
}

/**
 * Icon-only permission switch beside the composer. Tap lists every mode with
 * what it allows; Full access still needs the device credential.
 */
@Composable
internal fun PermissionModeButton(
    mode: PermissionMode,
    onSetMode: (PermissionMode) -> Unit,
    size: Dp = 40.dp,
) {
    val uiContext = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    val tint by animateColorAsState(mode.tint(), expressiveEffectsSpec(), label = "permissionTint")
    val container by animateColorAsState(
        if (expanded) MaterialTheme.colorScheme.secondaryContainer else tint.copy(alpha = .12f),
        expressiveEffectsSpec(), label = "permissionContainer")
    val label = uiContext.getString(R.string.ui_permission, localizedOption(mode.label))

    Box {
        ExpressiveIconButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); expanded = true },
            glassDecoration = false,
            modifier = Modifier.size(size).testTag("composer-permission")
                .semantics { contentDescription = label },
            shape = CircleShape,
            colors = IconButtonDefaults.iconButtonColors(containerColor = container, contentColor = tint),
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (scaleIn(expressiveSpatialSpec(), initialScale = .6f) + fadeIn(expressiveEffectsSpec()))
                        .togetherWith(scaleOut(expressiveSpatialSpec(), targetScale = .6f) + fadeOut(expressiveEffectsSpec()))
                },
                label = "permissionIcon",
            ) { shown ->
                Icon(shown.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 320.dp).testTag("permission-menu")) {
            Text(stringResource(R.string.settings_agent_group_permissions),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            PermissionMode.entries.forEach { entry ->
                val selected = entry == mode
                val entryTint = entry.tint()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(localizedOption(entry.label),
                                color = if (entry == PermissionMode.FULL_ACCESS) FullAccessOrange
                                    else MaterialTheme.colorScheme.onSurface)
                            Text(entry.description(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(entry.icon, contentDescription = null, tint = entryTint) },
                    trailingIcon = {
                        if (selected) Icon(Icons.Filled.Check, stringResource(R.string.settings_selected), tint = entryTint)
                    },
                    modifier = Modifier.semantics { this.selected = selected },
                    onClick = {
                        expanded = false
                        if (selected) return@DropdownMenuItem
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (entry == PermissionMode.FULL_ACCESS) {
                            BiometricAuth.requireFullAccessApproval(
                                uiContext,
                                uiContext.getString(R.string.settings_full_access_auth_title),
                                uiContext.getString(R.string.settings_full_access_auth_subtitle),
                                onApproved = { onSetMode(entry) },
                            )
                        } else {
                            onSetMode(entry)
                        }
                    },
                )
            }
        }
    }
}
