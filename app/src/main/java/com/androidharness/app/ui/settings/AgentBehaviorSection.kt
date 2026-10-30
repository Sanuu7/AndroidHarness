package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import com.androidharness.app.ui.common.localizedOption
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.R
import com.androidharness.app.ui.chat.components.FullAccessOrange
import com.androidharness.app.ui.common.BiometricAuth
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

private enum class BehaviorPicker { PERMISSIONS, CONTEXT, ITERATIONS }

private data class BehaviorChoice(val key: String, val title: String, val description: String)

@Composable
private fun PermissionMode.description(): String = when (this) {
    PermissionMode.CONFIRM_ALL -> stringResource(R.string.settings_agent_perm_confirm_all)
    PermissionMode.CONFIRM_RISKY -> stringResource(R.string.settings_agent_perm_confirm_risky)
    PermissionMode.FULL_AUTO -> stringResource(R.string.settings_agent_perm_full_auto)
    PermissionMode.FULL_ACCESS -> stringResource(R.string.settings_agent_perm_full_access)
}

/** Presentation only: all changes still go through the existing settings repository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentBehaviorSection(
    settings: AppSettings,
    onPermissionMode: (PermissionMode) -> Unit,
    onContextLimit: (Int) -> Unit,
    onIterationLimit: (Int) -> Unit,
    onProjectInstructions: () -> Unit,
    onLiveUpdates: (Boolean) -> Unit,
    showLiveUpdates: Boolean = true,
    /**
     * Test/preview override for the device-owner check guarding full access.
     * Production callers leave this null so the real biometric/PIN prompt runs.
     */
    fullAccessApproval: ((onApproved: () -> Unit) -> Unit)? = null,
) {
    var pickerName by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = pickerName?.let(BehaviorPicker::valueOf)
    val context = LocalContext.current
    val fullAccessTitle = stringResource(R.string.settings_full_access_auth_title)
    val fullAccessSubtitle = stringResource(R.string.settings_full_access_auth_subtitle)

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            stringResource(R.string.settings_agent_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BehaviorGroup(stringResource(R.string.settings_agent_group_permissions), Icons.Outlined.Security) {
            SettingsAnchor(R.string.settings_anchor_default_permission) {
                BehaviorValueRow(
                    title = stringResource(R.string.settings_anchor_default_permission),
                    value = localizedOption(settings.permissionMode.label),
                    description = settings.permissionMode.description(),
                    onClick = { pickerName = BehaviorPicker.PERMISSIONS.name },
                    caution = settings.permissionMode == PermissionMode.FULL_ACCESS,
                )
            }
        }
        BehaviorGroup(stringResource(R.string.settings_agent_group_run_limits), Icons.Outlined.Tune) {
            SettingsAnchor(R.string.settings_anchor_max_context) {
                BehaviorValueRow(
                    title = stringResource(R.string.settings_anchor_max_context),
                    value = stringResource(R.string.settings_agent_context_value, formatTokenCount(settings.maxContextTokens.toLong())),
                    description = stringResource(R.string.settings_agent_context_cap_subtitle),
                    onClick = { pickerName = BehaviorPicker.CONTEXT.name },
                )
            }
            BehaviorDivider()
            SettingsAnchor(R.string.settings_anchor_tool_iterations) {
                BehaviorValueRow(
                    title = stringResource(R.string.settings_anchor_tool_iterations),
                    value = if (settings.maxIterations <= 0) stringResource(R.string.settings_agent_unlimited)
                    else stringResource(R.string.settings_agent_rounds_value, settings.maxIterations),
                    description = stringResource(R.string.settings_agent_tool_rounds_subtitle),
                    onClick = { pickerName = BehaviorPicker.ITERATIONS.name },
                )
            }
        }
        if (showLiveUpdates) LiveUpdatesSetting(settings, onLiveUpdates)
        BehaviorGroup(stringResource(R.string.settings_agent_instructions_group), Icons.Outlined.Description) {
            SettingsAnchor(R.string.settings_anchor_project_instructions) {
                BehaviorValueRow(
                    title = stringResource(R.string.settings_anchor_project_instructions),
                    description = stringResource(R.string.settings_agent_instructions_subtitle),
                    onClick = onProjectInstructions,
                )
            }
        }
    }

    if (picker != null) {
        val title: String
        val description: String
        val selected: String
        val choices: List<BehaviorChoice>
        when (picker) {
            BehaviorPicker.PERMISSIONS -> {
                title = stringResource(R.string.settings_anchor_default_permission)
                description = stringResource(R.string.settings_agent_permission_subtitle)
                selected = settings.permissionMode.name
                choices = PermissionMode.entries.map {
                    BehaviorChoice(it.name, localizedOption(it.label), it.description())
                }
            }
            BehaviorPicker.CONTEXT -> {
                title = stringResource(R.string.settings_anchor_max_context)
                description = stringResource(R.string.settings_agent_context_cap_detail)
                selected = settings.maxContextTokens.toString()
                choices = (listOf(131_072, 262_144, 400_000, 1_000_000, 2_000_000) + settings.maxContextTokens)
                    .distinct().sorted().map {
                        BehaviorChoice(it.toString(), stringResource(R.string.settings_agent_context_value, formatTokenCount(it.toLong())), "")
                    }
            }
            BehaviorPicker.ITERATIONS -> {
                title = stringResource(R.string.settings_anchor_tool_iterations)
                description = stringResource(R.string.settings_agent_tool_rounds_detail)
                selected = settings.maxIterations.coerceAtLeast(0).toString()
                choices = (listOf(0, 25, 100, 250) + settings.maxIterations.coerceAtLeast(0))
                    .distinct().sorted().map {
                        BehaviorChoice(
                            it.toString(),
                            if (it == 0) stringResource(R.string.settings_agent_unlimited)
                            else stringResource(R.string.settings_agent_rounds_value, it),
                            "",
                        )
                    }
            }
        }
        ModalBottomSheet(
            onDismissRequest = { pickerName = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    choices.forEach { choice ->
                        val isSelected = choice.key == selected
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        when (picker) {
                                            BehaviorPicker.PERMISSIONS -> {
                                                val mode = PermissionMode.valueOf(choice.key)
                                                val apply = { onPermissionMode(mode) }
                                                if (mode == PermissionMode.FULL_ACCESS) {
                                                    val override = fullAccessApproval
                                                    if (override != null) override(apply)
                                                    else BiometricAuth.requireFullAccessApproval(
                                                        context, fullAccessTitle, fullAccessSubtitle, apply,
                                                    )
                                                } else {
                                                    apply()
                                                }
                                            }
                                            BehaviorPicker.CONTEXT -> onContextLimit(choice.key.toInt())
                                            BehaviorPicker.ITERATIONS -> onIterationLimit(choice.key.toInt())
                                        }
                                        pickerName = null
                                    },
                                ).padding(horizontal = 12.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = isSelected, onClick = null)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(choice.title, style = MaterialTheme.typography.titleSmall)
                                    if (choice.description.isNotEmpty()) Text(
                                        choice.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (picker == BehaviorPicker.PERMISSIONS && choice.key == PermissionMode.FULL_ACCESS.name)
                                            FullAccessOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { pickerName = null }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.settings_action_cancel)) }
            }
        }
    }
}

@Composable
private fun BehaviorGroup(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.padding(start = 4.dp).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsPanel(Modifier.fillMaxWidth(), content)
    }
}

@Composable
private fun BehaviorValueRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    value: String? = null,
    caution: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (value != null) Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (caution) FullAccessOrange else MaterialTheme.colorScheme.primary,
            )
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = if (caution) FullAccessOrange else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BehaviorDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
internal fun LiveUpdatesSetting(settings: AppSettings, onLiveUpdates: (Boolean) -> Unit) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        BehaviorGroup(stringResource(R.string.settings_agent_group_live_updates), Icons.Outlined.Notifications) {
            SettingsAnchor(R.string.settings_agent_live_updates) {
                val vendor = remember { liveUpdateVendor }
                var state by remember { mutableStateOf(liveUpdateState(context)) }
                // The user usually returns here after granting the appop in system settings.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = liveUpdateState(context) }
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.settings_agent_live_updates), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.settings_agent_live_updates_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.liveUpdatesEnabled, onCheckedChange = onLiveUpdates)
                    }
                    Text(
                        stringResource(
                            R.string.settings_agent_live_updates_device,
                            liveUpdateDeviceName,
                            stringResource(vendor.label),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val tint = when (state) {
                            LiveUpdateState.READY -> MaterialTheme.colorScheme.primary
                            LiveUpdateState.BLOCKED -> MaterialTheme.colorScheme.error
                            LiveUpdateState.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(Modifier.size(8.dp).background(tint, CircleShape))
                        Text(
                            stringResource(state.description),
                            style = MaterialTheme.typography.bodySmall,
                            color = tint,
                        )
                    }
                    Text(
                        stringResource(vendor.steps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_agent_live_updates_usage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { context.openLiveUpdateSettings() },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.settings_agent_live_updates_open))
                    }
                }
            }
        }
    }
}
