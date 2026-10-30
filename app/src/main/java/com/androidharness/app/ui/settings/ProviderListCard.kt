package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import com.androidharness.app.ui.common.ExpressiveDropdownMenu as DropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem as DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.common.ProviderMark
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProviderListCard(
    provider: ProviderConfig, active: Boolean, canMoveUp: Boolean, canMoveDown: Boolean,
    onSelect: () -> Unit, onEnabledChange: (Boolean) -> Unit, onMove: (Int) -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onBrowseModels: (() -> Unit)? = null,
    catalogSize: Int? = null, selectedModelCount: Int? = null,
    selectionMode: Boolean = false, selected: Boolean = false,
    onToggleSelection: () -> Unit = {}, onLongPress: () -> Unit = {},
    onControls: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val editable = deletableProvider(provider)
    val selectLabel = stringResource(R.string.settings_provider_select_label)
    val selectCd = if (selectionMode)
        stringResource(R.string.settings_provider_select_provider_cd, provider.name)
    else stringResource(R.string.settings_provider_select_cd, provider.name)
    val enableCd = stringResource(R.string.settings_provider_enable_cd, provider.name)
    Surface(color = if (selected || active && !selectionMode) scheme.secondaryContainer else scheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = .4f)),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 56.dp)
                    .combinedClickable(onClick = { if (selectionMode) onToggleSelection() else if (provider.enabled) onSelect() },
                        onLongClick = onLongPress, onLongClickLabel = selectLabel)
                    .semantics {
                        contentDescription = selectCd
                        if (selectionMode) {
                            role = Role.Checkbox
                            toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically) {
                    if (selectionMode) Checkbox(checked = selected, onCheckedChange = null)
                    else ProviderMark(size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(when {
                            provider.id == com.androidharness.app.llm.HarnessProvider.ID -> stringResource(R.string.settings_provider_kind_builtin)
                            com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(provider.id) -> "ChatGPT"
                            com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id) -> stringResource(R.string.settings_provider_kind_on_device)
                            provider.type == ProviderType.ANTHROPIC -> "Anthropic API"
                            provider.type == ProviderType.GEMINI -> "Gemini API"
                            else -> "API"
                        }, catalogSize?.let { stringResource(R.string.settings_provider_models_count, (selectedModelCount ?: it).toString(), it.toString()) },
                            if (!provider.enabled) stringResource(R.string.settings_provider_disabled)
                            else if (active) stringResource(R.string.settings_provider_active) else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!selectionMode) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.settings_provider_actions_cd, provider.name)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_provider_select)) }, onClick = { menu = false; onLongPress() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_action_move_up)) }, enabled = canMoveUp, onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_action_move_down)) }, enabled = canMoveDown, onClick = { menu = false; onMove(1) })
                        onControls?.let { controls -> DropdownMenuItem(text = { Text(stringResource(R.string.provider_controls_title)) }, onClick = { menu = false; controls() }) }
                        if (editable) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings_action_edit)) }, onClick = { menu = false; onEdit() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings_action_delete), color = scheme.error) }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
            if (!selectionMode) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.heightIn(min = 48.dp)
                    .toggleable(provider.enabled, role = Role.Switch, onValueChange = onEnabledChange)
                    .semantics { contentDescription = enableCd }, verticalAlignment = Alignment.CenterVertically) {
                    Switch(provider.enabled, onCheckedChange = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (provider.enabled) stringResource(R.string.settings_provider_enabled)
                        else stringResource(R.string.settings_provider_disabled),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (!provider.enabled) TextButton(onClick = onSelect) { Text(stringResource(R.string.settings_provider_enable_use)) }
                onBrowseModels?.let { browse -> TextButton(onClick = browse) { Text(stringResource(R.string.settings_provider_manage_models)) } }
            }
        }
    }
}
