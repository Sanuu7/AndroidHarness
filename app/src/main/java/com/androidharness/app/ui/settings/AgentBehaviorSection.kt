package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.chat.components.FullAccessOrange
import com.androidharness.app.ui.common.formatTokenCount

private enum class BehaviorPicker { PERMISSIONS, CONTEXT, ITERATIONS }

private data class BehaviorChoice(val key: String, val title: String, val description: String)

private fun PermissionMode.description(): String = when (this) {
    PermissionMode.CONFIRM_ALL -> "Ask before each tool action, including reads."
    PermissionMode.CONFIRM_RISKY -> "Read freely. Ask before edits and commands."
    PermissionMode.FULL_AUTO -> "Run automatically with workspace and command safeguards."
    PermissionMode.FULL_ACCESS -> "Run without approvals, workspace limits or command safeguards."
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
) {
    var pickerName by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = pickerName?.let(BehaviorPicker::valueOf)

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            "Choose how the agent works. You can also change permissions from chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BehaviorGroup("Permissions", Icons.Outlined.Security) {
            SettingsAnchor("Default permission mode") {
                BehaviorValueRow(
                    title = "Default permission mode",
                    value = settings.permissionMode.label,
                    description = settings.permissionMode.description(),
                    onClick = { pickerName = BehaviorPicker.PERMISSIONS.name },
                    caution = settings.permissionMode == PermissionMode.FULL_ACCESS,
                )
            }
        }
        BehaviorGroup("Run limits", Icons.Outlined.Tune) {
            SettingsAnchor("Max context window") {
                BehaviorValueRow(
                    title = "Max context window",
                    value = "${formatTokenCount(settings.maxContextTokens.toLong())} tokens",
                    description = "Cap the context sent to the model.",
                    onClick = { pickerName = BehaviorPicker.CONTEXT.name },
                )
            }
            BehaviorDivider()
            SettingsAnchor("Tool-call iteration limit") {
                BehaviorValueRow(
                    title = "Tool-call iteration limit",
                    value = if (settings.maxIterations <= 0) "Unlimited" else "${settings.maxIterations} rounds",
                    description = "How many tool rounds a run can take.",
                    onClick = { pickerName = BehaviorPicker.ITERATIONS.name },
                )
            }
        }
        BehaviorGroup("Project instructions", Icons.Outlined.Description) {
            SettingsAnchor("Project instructions (AGENTS.md)") {
                BehaviorValueRow(
                    title = "Project instructions (AGENTS.md)",
                    description = "View or edit the rules for your current workspace.",
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
                title = "Default permission mode"
                description = "Choose when the agent asks for approval. Package installs always ask; remembered approvals skip repeat prompts."
                selected = settings.permissionMode.name
                choices = PermissionMode.entries.map {
                    BehaviorChoice(it.name, it.label, it.description())
                }
            }
            BehaviorPicker.CONTEXT -> {
                title = "Max context window"
                description = "A larger context can keep more history, but uses more tokens. The model's own limit still applies."
                selected = settings.maxContextTokens.toString()
                choices = (listOf(131_072, 262_144, 400_000, 1_000_000, 2_000_000) + settings.maxContextTokens)
                    .distinct().sorted().map {
                        BehaviorChoice(it.toString(), "${formatTokenCount(it.toLong())} tokens", "")
                    }
            }
            BehaviorPicker.ITERATIONS -> {
                title = "Tool-call iteration limit"
                description = "A round can include several tool calls. Unlimited keeps going until the task finishes or you stop it."
                selected = settings.maxIterations.coerceAtLeast(0).toString()
                choices = (listOf(0, 25, 100, 250) + settings.maxIterations.coerceAtLeast(0))
                    .distinct().sorted().map {
                        BehaviorChoice(it.toString(), if (it == 0) "Unlimited" else "$it rounds", "")
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
                                            BehaviorPicker.PERMISSIONS -> onPermissionMode(PermissionMode.valueOf(choice.key))
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
                TextButton(onClick = { pickerName = null }, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
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
