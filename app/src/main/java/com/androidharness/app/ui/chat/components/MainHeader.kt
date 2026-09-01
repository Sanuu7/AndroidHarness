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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.graphics.Color
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
import com.androidharness.app.agent.ThinkingSpecs
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.ui.theme.fastEffectsSpec

/**
 * The chat header: a single flat row.
 *
 * The old design spent two rows here (title + status, then a provider chip row).
 * Now the subtitle line IS the provider switcher, "Provider · Model", tap to
 * change, and doubles as the live status line while the agent works. Plan mode
 * shows one small accent icon instead of a pill, the workspace-files explorer
 * (migrated from the drawer) gets the header icon slot, and context usage
 * lives in the overflow menu.
 */
@Composable
internal fun MainHeader(
    sessionTitle: String,
    busy: Boolean,
    pickerLabel: String,
    mode: AgentMode,
    thinkingLevel: ThinkingLevel,
    /** Full global ladder, every model offers every rung (Hermes-style). */
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
    onOpenFiles: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sessionTitle.startsWith("Fork of ")) {
                        Icon(
                            Icons.Outlined.ForkRight,
                            contentDescription = "Forked session",
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        sessionTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onPickModel() },
                ) {
                    Text(
                        pickerLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Switch model",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
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
                        contentDescription = "Plan mode on: switch to Act",
                        tint = scheme.primary,
                    )
                }
            }
            // Thinking Level Badge + Switcher Dropdown (to the left of Context button)
            var thinkingBadgeMenu by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier.padding(end = 2.dp),
            ) {
                androidx.compose.material3.Surface(
                    onClick = { thinkingBadgeMenu = true },
                    shape = RoundedCornerShape(8.dp),
                    color = if (thinkingLevel != ThinkingLevel.OFF) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = "Thinking level",
                            modifier = Modifier.size(13.dp),
                            tint = if (thinkingLevel != ThinkingLevel.OFF) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            thinkingLevel.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (thinkingLevel != ThinkingLevel.OFF) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = if (thinkingLevel != ThinkingLevel.OFF) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                        )
                    }
                }

                DropdownMenu(
                    expanded = thinkingBadgeMenu,
                    onDismissRequest = { thinkingBadgeMenu = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Thinking Level",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    thinkingLevels.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(entry.label, Modifier.weight(1f))
                                    if (entry == thinkingLevel) {
                                        Icon(Icons.Filled.Check, contentDescription = "Selected", tint = scheme.primary)
                                    }
                                }
                            },
                            onClick = {
                                onSetThinking(entry)
                                thinkingBadgeMenu = false
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text("Switch model…") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = scheme.primary)
                        },
                        onClick = {
                            thinkingBadgeMenu = false
                            onPickModel()
                        },
                    )
                }
            }

            // Workspace files explorer, migrated here from the drawer;
            // workspace switching itself lives inside the file manager.
            IconButton(onClick = onOpenFiles) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = "Workspace files",
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
                                    PermissionMode.FULL_ACCESS -> FullAccessOrange
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
                        text = { Text("Context usage") },
                        leadingIcon = {
                            Icon(Icons.Outlined.QueryStats, contentDescription = null)
                        },
                        onClick = { menu = false; onOpenContext() },
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
                                Text(
                                    entry.label,
                                    Modifier.weight(1f),
                                    color = if (entry == PermissionMode.FULL_ACCESS) FullAccessOrange
                                    else Color.Unspecified,
                                )
                                if (entry == permissionMode) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = if (entry == PermissionMode.FULL_ACCESS) FullAccessOrange
                                        else scheme.primary,
                                    )
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

/** Accent for the Full access permission mode, warning orange, distinct from error red. */
internal val FullAccessOrange = Color(0xFFF59E0B)
