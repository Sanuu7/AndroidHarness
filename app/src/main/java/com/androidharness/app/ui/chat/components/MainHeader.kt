package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.theme.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.ui.common.ExpressiveDropdownMenu as DropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem as DropdownMenuItem

import com.androidharness.app.ui.common.localizedOption
import com.androidharness.app.ui.common.localizedThinkingOption
import androidx.compose.material.icons.filled.Add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import com.androidharness.app.R
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.common.BiometricAuth
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.TextButton

/**
 * Phones keep the model and thinking controls together in one row.
 * Desktop uses its own compact header.
 * Labels and touch targets grow with the system font size.
 */
@Composable
internal fun MainHeader(
    sessionTitle: String,
    busy: Boolean,
    pickerLabel: String,
    mode: AgentMode,
    dualPlanning: Boolean = false,
    thinkingLevel: ThinkingLevel,
    /** Choices for this provider; an empty list means no configurable effort. */
    thinkingLevels: List<ThinkingLevel>,
    permissionMode: PermissionMode,
    canUndo: Boolean,
    onOpenDrawer: () -> Unit,
    onPickModel: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSetThinking: (ThinkingLevel) -> Unit,
    onSetPermission: (PermissionMode) -> Unit,
    onSetMode: (AgentMode) -> Unit,
    onToggleDualPlanning: () -> Unit = {},
    onOpenContext: () -> Unit,
    onOpenUndo: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenGitHub: () -> Unit,
    canOpenGitHub: Boolean = true,
    onOpenWebPreview: () -> Unit = {},
    showWebPreviewShortcut: Boolean = false,
    onFind: (() -> Unit)? = null,
    modelScopeLabel: String? = null,
    workspaceName: String? = null,
    thinkingResolved: com.androidharness.app.agent.ThinkingSpecs.ResolvedThinking? = null,
    onCustomizeThinking: (() -> Unit)? = null,
    onAddThinkingLevel: (() -> Unit)? = null,
    onSetCustomThinking: (String) -> Unit = {},
    conversation: com.androidharness.app.data.ConversationConfig = com.androidharness.app.data.ConversationConfig(),
    searchDepth: com.androidharness.app.data.SearchDepth = com.androidharness.app.data.SearchDepth.AUTO,
    onSetSearchDepth: (com.androidharness.app.data.SearchDepth) -> Unit = {},
    onOpenChatSettings: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = null,
    chatPinned: Boolean = false,
    chatArchived: Boolean = false,
    onToggleChatPin: (() -> Unit)? = null,
    onToggleChatArchive: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val thinkingOn = thinkingLevel != ThinkingLevel.OFF
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF

    if (com.androidharness.app.ui.LocalDesktopLayout.current) {
        Column(if (glass) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(8.dp) else Modifier) {
            DesktopConversationHeader(sessionTitle, busy, pickerLabel, thinkingLevel, thinkingLevels, mode,
            permissionMode, onPickModel, onSetThinking, onSetMode, onSetPermission,
            onOpenContext, onOpenUndo, canUndo, onOpenGitHub, canOpenGitHub, onOpenWebPreview, thinkingResolved, onCustomizeThinking, onSetCustomThinking, onAddThinkingLevel,
            showPreviewShortcut = showWebPreviewShortcut, conversation = conversation,
            workspaceName = workspaceName, onOpenDrawer = onOpenDrawer,
            searchDepth = searchDepth, onSetSearchDepth = onSetSearchDepth, onFind = onFind,
            onOpenChatSettings = onOpenChatSettings, onOpenSources = onOpenSources,
            chatPinned = chatPinned, chatArchived = chatArchived, onToggleChatPin = onToggleChatPin, onToggleChatArchive = onToggleChatArchive)
        }
        return
    }

    Column(
        Modifier
            .then(if (glass) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 12.dp, vertical = 8.dp)
                else Modifier.harnessGlassBar(scheme.surface).statusBarsPadding()),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = if (glass) 0.dp else 10.dp),
                ) {
                    ExpressiveIconButton(glassChrome = true, onClick = onOpenDrawer) {
                        Icon(painterResource(R.drawable.ic_chat_menu), contentDescription = uiContext.getString(R.string.ui_open_navigation), modifier = Modifier.size(18.dp))
                    }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        HeaderModelPicker(pickerLabel, onPickModel, Modifier.weight(1f, fill = false), scopeLabel = modelScopeLabel,
                            compact = conversation.isChat)
                        if (thinkingResolved?.customConfig != null || thinkingLevels.isNotEmpty() || thinkingResolved?.spec?.style == com.androidharness.app.agent.ThinkingSpecs.Style.AUTOMATIC) {
                            Spacer(Modifier.width(4.dp))
                            HeaderThinkingControl(thinkingLevel, thinkingLevels, thinkingMenu,
                                { thinkingMenu = it }, onSetThinking, onPickModel, thinkingResolved, busy, onCustomizeThinking, onSetCustomThinking, onAddThinkingLevel)
                        }
                    }

                    // Workspace files explorer, migrated here from the drawer;
                    // workspace switching itself lives inside the file manager.
                    if (!conversation.isChat) ExpressiveIconButton(glassChrome = true, onClick = onOpenFiles) {
                        Icon(
                            painterResource(R.drawable.ic_chat_folder),
                            modifier = Modifier.size(18.dp),
                            contentDescription = uiContext.getString(R.string.ui_workspace_files),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                    if (!conversation.isChat) ExpressiveIconButton(glassChrome = true, onClick = onOpenGitHub, enabled = canOpenGitHub) {
                        Icon(painterResource(R.drawable.ic_github), contentDescription = uiContext.getString(R.string.ui_open_github),
                            modifier = Modifier.size(18.dp))
                    }
                    if (conversation.isChat) {
                        if (glass) Spacer(Modifier.width(4.dp))
                        SearchDepthControl(searchDepth, onSetSearchDepth)
                        Spacer(Modifier.width(2.dp))
                    }
                    Box {
                        ExpressiveIconButton(glassChrome = true, onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_chat_ellipsis_vertical), contentDescription = uiContext.getString(R.string.ui_more_options), modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!conversation.isChat) {
                                WorkspaceMenuEntries { menu = false }
                                DropdownMenuItem(
                                    text = { Text("GitHub") }, enabled = canOpenGitHub,
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_github), null, Modifier.size(18.dp)) },
                                    onClick = { menu = false; onOpenGitHub() },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (mode == AgentMode.PLAN && !dualPlanning) uiContext.getString(R.string.ui_act_mode) else uiContext.getString(R.string.ui_plan_mode)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.EditNote,
                                            contentDescription = null,
                                            tint = if (mode == AgentMode.PLAN && !dualPlanning) scheme.primary else scheme.onSurfaceVariant,
                                        )
                                    },
                                    trailingIcon = {
                                        if (mode == AgentMode.PLAN && !dualPlanning) {
                                            Icon(Icons.Filled.Check, contentDescription = uiContext.getString(R.string.settings_headers_enabled), tint = scheme.primary)
                                        }
                                    },
                                    onClick = {
                                        menu = false
                                        onSetMode(if (mode == AgentMode.PLAN && !dualPlanning) AgentMode.ACT else AgentMode.PLAN)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.ui_dual_planning)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.ForkRight,
                                            contentDescription = null,
                                            tint = if (dualPlanning) scheme.primary else scheme.onSurfaceVariant,
                                        )
                                    },
                                    trailingIcon = {
                                        if (dualPlanning) {
                                            Icon(Icons.Filled.Check, contentDescription = uiContext.getString(R.string.settings_headers_enabled), tint = scheme.primary)
                                        }
                                    },
                                    onClick = {
                                        menu = false
                                        onToggleDualPlanning()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(uiContext.getString(R.string.ui_thinking_152, (localizedThinkingOption(thinkingResolved?.label ?: thinkingLevel.label)).toString())) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_brain),
                                        contentDescription = null,
                                        tint = if (thinkingOn) scheme.primary else scheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = scheme.onSurfaceVariant,
                                    )
                                },
                                onClick = { menu = false; thinkingMenu = true },
                            )
                            if (!conversation.isChat) DropdownMenuItem(
                                text = { Text(uiContext.getString(R.string.ui_permission, (localizedOption(permissionMode.label)).toString())) },
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
                            if (conversation.isChat) {
                                ChatOrganizationMenuItems(chatPinned, chatArchived, onToggleChatPin, onToggleChatArchive) { menu = false }
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.chat_settings_title)) },
                                    leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                                    enabled = onOpenChatSettings != null,
                                    onClick = { menu = false; onOpenChatSettings?.invoke() },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.chat_sources_library)) },
                                    leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                                    enabled = onOpenSources != null,
                                    onClick = { menu = false; onOpenSources?.invoke() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(uiContext.getString(R.string.ui_find_in_chat)) },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                enabled = onFind != null,
                                onClick = { menu = false; onFind?.invoke() },
                            )
                            DropdownMenuItem(
                                text = { Text(uiContext.getString(R.string.ui_context_limits)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.QueryStats, contentDescription = null)
                                },
                                onClick = { menu = false; onOpenContext() },
                            )
                            if (!conversation.isChat) {
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.ui_terminal)) },
                                    leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                                    onClick = { menu = false; onOpenTerminal() },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.ui_web_preview_localhost)) },
                                    leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                                    onClick = { menu = false; onOpenWebPreview() },
                                )
                                DropdownMenuItem(
                                    text = { Text(uiContext.getString(R.string.ui_undo_file_changes)) },
                                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                                    enabled = canUndo,
                                    onClick = { menu = false; onOpenUndo() },
                                )
                            }
                        }
                        DropdownMenu(expanded = permissionMenu, onDismissRequest = { permissionMenu = false }) {
                            PermissionMode.entries.forEach { entry ->
                                DropdownMenuItem(text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            localizedOption(entry.label),
                                            Modifier.weight(1f),
                                            color = if (entry == PermissionMode.FULL_ACCESS) FullAccessOrange
                                            else Color.Unspecified,
                                        )
                                        if (entry == permissionMode) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = uiContext.getString(R.string.settings_selected),
                                                tint = if (entry == PermissionMode.FULL_ACCESS) FullAccessOrange
                                                else scheme.primary,
                                            )
                                        }
                                    }
                                }, onClick = {
                                    permissionMenu = false
                                    if (entry == PermissionMode.FULL_ACCESS) {
                                        BiometricAuth.requireFullAccessApproval(
                                            uiContext,
                                            uiContext.getString(R.string.settings_full_access_auth_title),
                                            uiContext.getString(R.string.settings_full_access_auth_subtitle),
                                            onApproved = { onSetPermission(entry) },
                                        )
                                    } else {
                                        onSetPermission(entry)
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
        if (!glass) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun DesktopConversationHeader(
    title: String, busy: Boolean, model: String, thinking: ThinkingLevel, levels: List<ThinkingLevel>,
    mode: AgentMode, permission: PermissionMode,
    onModel: () -> Unit, onThinking: (ThinkingLevel) -> Unit, onMode: (AgentMode) -> Unit,
    onPermission: (PermissionMode) -> Unit, onContext: () -> Unit, onUndo: () -> Unit,
    canUndo: Boolean, onGitHub: () -> Unit, canGitHub: Boolean, onPreview: () -> Unit,
    thinkingResolved: com.androidharness.app.agent.ThinkingSpecs.ResolvedThinking?,
    onCustomizeThinking: (() -> Unit)?,
    onSetCustomThinking: (String) -> Unit,
    onAddThinkingLevel: (() -> Unit)?,
    showPreviewShortcut: Boolean,
    conversation: com.androidharness.app.data.ConversationConfig,
    workspaceName: String?,
    onOpenDrawer: () -> Unit,
    searchDepth: com.androidharness.app.data.SearchDepth,
    onSetSearchDepth: (com.androidharness.app.data.SearchDepth) -> Unit,
    onFind: (() -> Unit)? = null,
    onOpenChatSettings: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = null,
    chatPinned: Boolean = false,
    chatArchived: Boolean = false,
    onToggleChatPin: (() -> Unit)? = null,
    onToggleChatArchive: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    val chatOnly = conversation.isChat
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (chatOnly) ExpressiveIconButton(glassChrome = true, onClick = onOpenDrawer, modifier = Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.ic_chat_menu), uiContext.getString(R.string.ui_open_navigation), Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ConversationModeLabel(conversation, workspaceName)
            }
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            HeaderModelPicker(model, onModel, Modifier.widthIn(max = 180.dp), compact = chatOnly)
            if (thinkingResolved?.customConfig != null || levels.isNotEmpty() || thinkingResolved?.spec?.style == com.androidharness.app.agent.ThinkingSpecs.Style.AUTOMATIC)
                HeaderThinkingControl(thinking, levels, thinkingMenu, { thinkingMenu = it }, onThinking, onModel, thinkingResolved, busy, onCustomizeThinking, onSetCustomThinking, onAddThinkingLevel)
            if (!chatOnly) TextButton(onClick = { onMode(if (mode == AgentMode.PLAN) AgentMode.ACT else AgentMode.PLAN) }) {
                Text(if (mode == AgentMode.PLAN) uiContext.getString(R.string.settings_telegram_plan) else uiContext.getString(R.string.settings_telegram_act), style = MaterialTheme.typography.labelMedium)
            }
            if (!chatOnly) ExpressiveIconButton(glassChrome = true, onClick = onGitHub, enabled = canGitHub, modifier = Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.ic_github), uiContext.getString(R.string.ui_open_github), Modifier.size(18.dp))
            }
            if (!chatOnly && showPreviewShortcut) ExpressiveIconButton(glassChrome = true, onClick = onPreview, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Language, uiContext.getString(R.string.ui_open_web_browser), Modifier.size(18.dp))
            }
            if (chatOnly) {
                Spacer(Modifier.width(6.dp))
                SearchDepthControl(searchDepth, onSetSearchDepth)
            }
            Box {
                ExpressiveIconButton(glassChrome = true, onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, uiContext.getString(R.string.ui_conversation_actions), Modifier.size(18.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    if (!chatOnly) WorkspaceMenuEntries { menu = false }
                    if (chatOnly) ChatOrganizationMenuItems(chatPinned, chatArchived, onToggleChatPin, onToggleChatArchive) { menu = false }
                    DropdownMenuItem(
                        text = { Text(uiContext.getString(R.string.ui_find_in_chat)) },
                        enabled = onFind != null,
                        onClick = { menu = false; onFind?.invoke() },
                    )
                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_context_limits)) }, onClick = { menu = false; onContext() })
                    if (chatOnly) DropdownMenuItem(
                        text = { Text(uiContext.getString(R.string.chat_settings_title)) },
                        onClick = { menu = false; onOpenChatSettings?.invoke() },
                    )
                    if (chatOnly) DropdownMenuItem(
                        text = { Text(uiContext.getString(R.string.chat_sources_library)) },
                        onClick = { menu = false; onOpenSources?.invoke() },
                    )
                    if (!chatOnly) DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_web_preview)) }, onClick = { menu = false; onPreview() })
                    if (!chatOnly) DropdownMenuItem(text = { Text("GitHub") }, enabled = canGitHub, onClick = { menu = false; onGitHub() })
                    if (!chatOnly) DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_undo_file_changes_165)) }, enabled = canUndo, onClick = { menu = false; onUndo() })
                    HorizontalDivider()
                    if (!chatOnly) PermissionMode.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(localizedOption(option.label)) }, onClick = {
                            menu = false
                            if (option == PermissionMode.FULL_ACCESS) {
                                BiometricAuth.requireFullAccessApproval(
                                    uiContext,
                                    uiContext.getString(R.string.settings_full_access_auth_title),
                                    uiContext.getString(R.string.settings_full_access_auth_subtitle),
                                    onApproved = { onPermission(option) },
                                )
                            } else {
                                onPermission(option)
                            }
                        },
                            trailingIcon = { if (permission == option) Icon(Icons.Filled.Check, uiContext.getString(R.string.settings_selected), Modifier.size(16.dp)) })
                    }
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun HeaderModelPicker(label: String, onPickModel: () -> Unit, modifier: Modifier = Modifier, scopeLabel: String? = null,
    compact: Boolean = false) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier.then(if (compact) Modifier.widthIn(max = 136.dp).semantics { contentDescription = label } else Modifier)
            .then(if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF)
                Modifier.harnessGlassChrome(Color.Transparent, RoundedCornerShape(50)) else Modifier)
            .clip(if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) RoundedCornerShape(50) else RoundedCornerShape(10.dp)).clickable(onClick = onPickModel)
            .heightIn(min = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 40.dp else 36.dp).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface)
            scopeLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = uiContext.getString(R.string.ui_switch_model),
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun ChatOrganizationMenuItems(
    pinned: Boolean,
    archived: Boolean,
    onTogglePin: (() -> Unit)?,
    onToggleArchive: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (onTogglePin != null) DropdownMenuItem(
        text = { Text(context.getString(if (pinned) R.string.ui_unpin else R.string.ui_pin)) },
        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
        onClick = { onDismiss(); onTogglePin() },
    )
    if (onToggleArchive != null) DropdownMenuItem(
        text = { Text(context.getString(if (archived) R.string.ui_unarchive else R.string.ui_archive)) },
        leadingIcon = { Icon(Icons.Outlined.Archive, null) },
        onClick = { onDismiss(); onToggleArchive() },
    )
    if (onTogglePin != null || onToggleArchive != null) HorizontalDivider()
}

/** Compact badge for Chat's research depth, next to the conversation menu. */
@Composable
private fun SearchDepthControl(
    depth: com.androidharness.app.data.SearchDepth,
    onSet: (com.androidharness.app.data.SearchDepth) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val neutral = depth == com.androidharness.app.data.SearchDepth.AUTO || depth == com.androidharness.app.data.SearchDepth.OFF
    val label = uiContext.getString(searchDepthShort(depth))
    Box {
        Surface(
            chrome = true,
            shape = RoundedCornerShape(50),
            color = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) Color.Transparent else if (neutral) scheme.surfaceContainerHigh else scheme.primaryContainer,
            contentColor = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) scheme.onSurface else if (neutral) scheme.onSurfaceVariant else scheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { menu = true }
                .semantics { contentDescription = uiContext.getString(R.string.ui_search_depth_current, label) },
        ) {
            Row(
                Modifier.heightIn(min = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 40.dp else 32.dp).padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.TravelExplore, contentDescription = null, modifier = Modifier.size(if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 18.dp else 15.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            com.androidharness.app.data.SearchDepth.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(uiContext.getString(searchDepthLabel(option)), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                uiContext.getString(searchDepthHint(option)),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        if (option == depth) {
                            Icon(Icons.Filled.Check, uiContext.getString(R.string.settings_selected), Modifier.size(16.dp))
                        }
                    },
                    onClick = { menu = false; onSet(option) },
                )
            }
        }
    }
}

private fun searchDepthShort(depth: com.androidharness.app.data.SearchDepth): Int = when (depth) {
    com.androidharness.app.data.SearchDepth.OFF -> R.string.ui_search_depth_off_short
    com.androidharness.app.data.SearchDepth.INSTANT -> R.string.ui_search_depth_instant_short
    com.androidharness.app.data.SearchDepth.DEEP -> R.string.ui_search_depth_deep_short
    com.androidharness.app.data.SearchDepth.AUTO -> R.string.ui_search_depth_auto_short
    com.androidharness.app.data.SearchDepth.DEEP_RESEARCH -> R.string.ui_search_depth_research_short
    com.androidharness.app.data.SearchDepth.MAX_RESEARCH -> R.string.ui_search_depth_max_short
}

private fun searchDepthLabel(depth: com.androidharness.app.data.SearchDepth): Int = when (depth) {
    com.androidharness.app.data.SearchDepth.OFF -> R.string.ui_search_depth_off
    com.androidharness.app.data.SearchDepth.INSTANT -> R.string.ui_search_depth_instant
    com.androidharness.app.data.SearchDepth.DEEP -> R.string.ui_search_depth_deep
    com.androidharness.app.data.SearchDepth.AUTO -> R.string.ui_search_depth_auto
    com.androidharness.app.data.SearchDepth.DEEP_RESEARCH -> R.string.ui_search_depth_research
    com.androidharness.app.data.SearchDepth.MAX_RESEARCH -> R.string.ui_search_depth_max
}

private fun searchDepthHint(depth: com.androidharness.app.data.SearchDepth): Int = when (depth) {
    com.androidharness.app.data.SearchDepth.OFF -> R.string.ui_search_depth_off_hint
    com.androidharness.app.data.SearchDepth.INSTANT -> R.string.ui_search_depth_instant_hint
    com.androidharness.app.data.SearchDepth.DEEP -> R.string.ui_search_depth_deep_hint
    com.androidharness.app.data.SearchDepth.AUTO -> R.string.ui_search_depth_auto_hint
    com.androidharness.app.data.SearchDepth.DEEP_RESEARCH -> R.string.ui_search_depth_research_hint
    com.androidharness.app.data.SearchDepth.MAX_RESEARCH -> R.string.ui_search_depth_max_hint
}

@Composable
internal fun HeaderThinkingControl(
    thinkingLevel: ThinkingLevel,
    thinkingLevels: List<ThinkingLevel>,
    thinkingMenu: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onSetThinking: (ThinkingLevel) -> Unit,
    onPickModel: () -> Unit,
    resolved: com.androidharness.app.agent.ThinkingSpecs.ResolvedThinking? = null,
    appliesNext: Boolean = false,
    onCustomize: (() -> Unit)? = null,
    onCustomLevel: (String) -> Unit = {},
    onAddLevel: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val thinkingOn = resolved?.active ?: (thinkingLevel != ThinkingLevel.OFF)
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val edgeMargin = with(density) { 12.dp.roundToPx() }
    val maxMenuWidth = with(density) { (windowWidth - 2 * edgeMargin).coerceAtLeast(0).toDp() }.coerceAtMost(320.dp)
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    var menuWidth by remember(density) { mutableIntStateOf(with(density) { 248.dp.roundToPx() }) }
    // Center on the badge, then keep the menu inset from either window edge.
    // The default start/end candidates can both fail and snap a wide menu to x = 0.
    val menuLeft = (anchorBounds.center.x - menuWidth / 2f)
        .coerceIn(edgeMargin.toFloat(), (windowWidth - edgeMargin - menuWidth).coerceAtLeast(edgeMargin).toFloat())
    val horizontalOffset = if (layoutDirection == LayoutDirection.Ltr) menuLeft - anchorBounds.left
        else anchorBounds.right - menuLeft - menuWidth
    Box(Modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() }) {
        // The visible badge stays small; clickable expands its touch target as needed.
        Box(
            Modifier.widthIn(max = 148.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 100.dp else 8.dp)).clickable { onMenuChange(true) },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                chrome = true,
                shape = RoundedCornerShape(if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 100.dp else 8.dp),
                color = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) Color.Transparent else if (thinkingOn) scheme.primaryContainer else scheme.surfaceContainerHigh,
                contentColor = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) scheme.onSurface else if (thinkingOn) scheme.primary else scheme.onSurfaceVariant,
            ) {
                Row(Modifier.heightIn(min = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 40.dp else 28.dp).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_brain), contentDescription = uiContext.getString(R.string.ui_thinking_level), modifier = Modifier.size(if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 18.dp else 14.dp),
                        tint = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) scheme.onSurface else if (thinkingOn) scheme.primary else scheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(resolved?.label ?: localizedOption(thinkingLevel.label), maxLines = 1, overflow = TextOverflow.Ellipsis, style = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
        DropdownMenu(
            expanded = thinkingMenu,
            onDismissRequest = { onMenuChange(false) },
            offset = DpOffset(with(density) { horizontalOffset.toDp() }, 4.dp),
            modifier = Modifier.widthIn(min = 248.dp.coerceAtMost(maxMenuWidth), max = maxMenuWidth)
                .onSizeChanged { menuWidth = it.width }
                .semantics { paneTitle = uiContext.getString(R.string.ui_thinking_options) },
        ) {
            Text(uiContext.getString(R.string.ui_thinking_level), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            if (appliesNext) {
                Text(uiContext.getString(R.string.ui_applies_to_your_next_message), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
            if (resolved?.customConfig == null && thinkingLevels.isEmpty()) {
                Text(if (resolved?.spec?.reasoningSupported == true) uiContext.getString(R.string.ui_this_model_manages_its_own_effort) else uiContext.getString(R.string.ui_levels_aren_t_reported_for_this_model),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            resolved?.customConfig?.levels?.forEach { name ->
                ThinkingMenuOption(com.androidharness.app.agent.CustomReasoning.label(name),
                    resolved.customLevel == com.androidharness.app.agent.CustomReasoning.wire(name)) {
                    onCustomLevel(name); onMenuChange(false)
                }
            }
            if (resolved?.customConfig == null) thinkingLevels.forEach { entry ->
                ThinkingMenuOption(localizedThinkingOption(resolved?.optionLabel(entry) ?: entry.label), entry == (resolved?.level ?: thinkingLevel)) {
                    onSetThinking(entry); onMenuChange(false)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (onCustomize != null) DropdownMenuItem(
                text = { Text(uiContext.getString(R.string.ui_customize_thinking)) },
                leadingIcon = { Icon(Icons.Outlined.EditNote, null, tint = scheme.onSurfaceVariant) },
                onClick = { onMenuChange(false); onCustomize() },
            )
            val addLevel = onAddLevel ?: onCustomize
            if (addLevel != null) DropdownMenuItem(
                text = { Text(uiContext.getString(R.string.ui_add_thinking_level)) },
                leadingIcon = { Icon(Icons.Filled.Add, null, tint = scheme.primary) },
                onClick = { onMenuChange(false); addLevel() },
            )
            DropdownMenuItem(
                text = { Text(uiContext.getString(R.string.ui_switch_model_174)) },
                leadingIcon = {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = scheme.primary)
                },
                onClick = {
                    onMenuChange(false)
                    onPickModel()
                },
            )
        }
    }
}

@Composable
private fun ThinkingMenuOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    DropdownMenuItem(
        modifier = Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) scheme.secondaryContainer else Color.Transparent)
            .semantics { selected = isSelected },
        text = { Text(label, color = if (isSelected) scheme.onSecondaryContainer else scheme.onSurface,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
        trailingIcon = { if (isSelected) Icon(Icons.Filled.Check, uiContext.getString(R.string.settings_selected), tint = scheme.onSecondaryContainer) },
        onClick = onClick,
    )
}

/** Accent for the Full access permission mode, warning orange, distinct from error red. */
internal val FullAccessOrange = Color(0xFFF59E0B)

@Composable
private fun WorkspaceMenuEntries(onDismiss: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val launcher = com.androidharness.app.ui.files.LocalWorkspaceToolLauncher.current ?: return
    listOf(
        com.androidharness.app.ui.files.WorkspaceToolMode.FILES to uiContext.getString(R.string.ui_quick_open),
        com.androidharness.app.ui.files.WorkspaceToolMode.SEARCH to uiContext.getString(R.string.ui_search_project),
        com.androidharness.app.ui.files.WorkspaceToolMode.COMMANDS to uiContext.getString(R.string.ui_command_palette),
    ).forEach { (mode, label) ->
        DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); launcher(mode) })
    }
    HorizontalDivider()
}
