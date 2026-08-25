package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.ui.theme.fastEffectsSpec

/**
 * The chat header: a single flat row.
 *
 * The old design spent two rows here (title + status, then a provider chip row).
 * Now the subtitle line IS the provider switcher — "Provider · Model", tap to
 * change — and doubles as the live status line while the agent works. Plan mode
 * shows one small accent icon instead of a pill, and context usage gets its own
 * icon so the overflow menu stays short.
 */
@Composable
internal fun MainHeader(
    sessionTitle: String,
    busy: Boolean,
    statusText: String,
    pickerLabel: String,
    mode: AgentMode,
    thinkingLevel: ThinkingLevel,
    /** Native tiers for the active model — non-native ones are hidden, not dimmed. */
    thinkingLevels: List<ThinkingLevel>,
    permissionMode: PermissionMode,
    canUndo: Boolean,
    onOpenDrawer: () -> Unit,
    onPickModel: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSetThinking: (ThinkingLevel) -> Unit,
    onSetPermission: (PermissionMode) -> Unit,
    onSetMode: (AgentMode) -> Unit,
    onOpenContext: () -> Unit,
    onOpenUndo: () -> Unit,
    onSwitchWorkspace: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .background(scheme.surface)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(start = 4.dp, end = 4.dp),
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            ) {
                Text(
                    sessionTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !busy) { onPickModel() },
                ) {
                    // Idle: the active provider · model (tap to switch). Busy:
                    // what the agent is doing right now.
                    Crossfade(
                        targetState = if (busy) statusText else pickerLabel,
                        animationSpec = fastEffectsSpec(),
                        label = "header status",
                        modifier = Modifier.weight(1f, fill = false),
                    ) { currentStatus ->
                        Text(
                            currentStatus,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (busy) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                    if (!busy) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Switch model",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = mode == AgentMode.PLAN,
                enter = fadeIn(fastEffectsSpec()) + scaleIn(fastEffectsSpec(), initialScale = 0.8f),
                exit = fadeOut(fastEffectsSpec()) + scaleOut(fastEffectsSpec(), targetScale = 0.8f),
            ) {
                IconButton(onClick = { onSetMode(AgentMode.ACT) }) {
                    Icon(
                        Icons.Outlined.EditNote,
                        contentDescription = "Plan mode on — switch to Act",
                        tint = scheme.primary,
                    )
                }
            }
            IconButton(onClick = onOpenContext) {
                Icon(
                    Icons.Outlined.QueryStats,
                    contentDescription = "Context usage",
                    tint = scheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (mode == AgentMode.PLAN) "Act mode" else "Plan mode") },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.EditNote,
                                contentDescription = null,
                                tint = if (mode == AgentMode.PLAN) scheme.primary else scheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (mode == AgentMode.PLAN) {
                                Icon(Icons.Filled.Check, contentDescription = "Enabled", tint = scheme.primary)
                            }
                        },
                        onClick = {
                            menu = false
                            onSetMode(if (mode == AgentMode.PLAN) AgentMode.ACT else AgentMode.PLAN)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Thinking · ${thinkingLevel.label}") },
                        leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        trailingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                            )
                        },
                        onClick = { menu = false; thinkingMenu = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Permission · ${permissionMode.label}") },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = when (permissionMode) {
                                    PermissionMode.FULL_AUTO -> scheme.error
                                    PermissionMode.CONFIRM_RISKY -> scheme.primary
                                    PermissionMode.CONFIRM_ALL -> scheme.onSurfaceVariant
                                },
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                            )
                        },
                        onClick = { menu = false; permissionMenu = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Workspace") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = scheme.onSurfaceVariant)
                        },
                        onClick = { menu = false; onSwitchWorkspace() },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    DropdownMenuItem(
                        text = { Text("Terminal") },
                        leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                        onClick = { menu = false; onOpenTerminal() },
                    )
                    DropdownMenuItem(
                        text = { Text("Undo file changes…") },
                        leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                        enabled = canUndo,
                        onClick = { menu = false; onOpenUndo() },
                    )
                }
                DropdownMenu(expanded = thinkingMenu, onDismissRequest = { thinkingMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Thinking level",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    thinkingLevels.forEach { entry ->
                        DropdownMenuItem(text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.label, Modifier.weight(1f))
                                if (entry == thinkingLevel) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = scheme.primary)
                                }
                            }
                        }, onClick = { onSetThinking(entry); thinkingMenu = false })
                    }
                }
                DropdownMenu(expanded = permissionMenu, onDismissRequest = { permissionMenu = false }) {
                    PermissionMode.entries.forEach { entry ->
                        DropdownMenuItem(text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.label, Modifier.weight(1f))
                                if (entry == permissionMode) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = scheme.primary)
                                }
                            }
                        }, onClick = { onSetPermission(entry); permissionMenu = false })
                    }
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
    }
}
