package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.llm.ProviderHeader
import com.androidharness.app.llm.ProviderHeaders
import java.util.UUID
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

internal data class ProviderHeaderDraft(val header: ProviderHeader, val id: String = UUID.randomUUID().toString())

@Composable
internal fun ProviderHeadersEditor(rows: List<ProviderHeaderDraft>, onChange: (List<ProviderHeaderDraft>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var preview by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_headers_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (rows.isNotEmpty()) Text("${rows.size}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            TextButton(onClick = { onChange(rows + ProviderHeaderDraft(ProviderHeader("", ""))) },
                enabled = rows.size < ProviderHeaders.MAX_HEADERS) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_headers_add))
            }
        }
        rows.forEachIndexed { index, row ->
            key(row.id) {
                var visible by remember { mutableStateOf(false) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(row.id) { if (row.header.name.isEmpty()) focus.requestFocus() }
                fun update(header: ProviderHeader) = onChange(rows.map { if (it.id == row.id) it.copy(header = header) else it })
                Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val headerNo = (index + 1).toString()
                        val enableCd = stringResource(R.string.settings_headers_enable_cd, headerNo)
                        val nameCd = stringResource(R.string.settings_headers_name_cd, headerNo)
                        val valueCd = stringResource(R.string.settings_headers_value_cd, headerNo)
                        val hideValueCd = stringResource(R.string.settings_headers_hide_value_cd, headerNo)
                        val showValueCd = stringResource(R.string.settings_headers_show_value_cd, headerNo)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(row.header.enabled, role = Role.Checkbox,
                                onValueChange = { update(row.header.copy(enabled = it)) })
                                .semantics { contentDescription = enableCd },
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = row.header.enabled, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_headers_enabled), style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { onChange(rows.filterNot { it.id == row.id }) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_headers_remove_cd, (index + 1).toString()))
                            }
                        }
                        OutlinedTextField(value = row.header.name, onValueChange = { update(row.header.copy(name = it)) },
                            label = { Text(stringResource(R.string.settings_headers_name)) }, placeholder = { Text(stringResource(R.string.settings_headers_placeholder_name)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                                .semantics { contentDescription = nameCd })
                        OutlinedTextField(value = row.header.value, onValueChange = { update(row.header.copy(value = it)) },
                            label = { Text(stringResource(R.string.settings_headers_value)) }, singleLine = true,
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { visible = !visible }) {
                                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (visible) hideValueCd else showValueCd)
                            } },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = valueCd })
                    }
                }
            }
        }
        val error = ProviderHeaders.error(rows.map { it.header })
        Text(error ?: stringResource(R.string.settings_headers_secure_note),
            style = MaterialTheme.typography.labelSmall, color = if (error == null) scheme.onSurfaceVariant else scheme.error)
        if (rows.isNotEmpty()) {
            TextButton(onClick = { preview = !preview }) { Text(if (preview) stringResource(R.string.settings_headers_hide_preview) else stringResource(R.string.settings_headers_show_preview)) }
            if (preview) {
                val enabled = rows.filter { it.header.enabled && it.header.name.isNotBlank() }
                Text(if (enabled.isEmpty()) stringResource(R.string.settings_headers_none_enabled) else enabled.joinToString("\n") { "${it.header.name.trim()}: ••••••••" },
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
