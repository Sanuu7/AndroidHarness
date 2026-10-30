package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androidharness.app.R
import com.androidharness.app.llm.ProviderApiKey
import com.androidharness.app.llm.ProviderKeyPool
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton

/** The same draft editor is used by Add, Edit and Connection controls. */
@Composable
internal fun ProviderKeysEditor(pool: ProviderKeyPool, onChange: (ProviderKeyPool) -> Unit, enabled: Boolean = true) {
    var revealed by remember { mutableStateOf(emptySet<String>()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) revealed = emptySet() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun update(id: String, transform: (ProviderApiKey) -> ProviderApiKey) =
        onChange(pool.copy(keys = pool.keys.map { if (it.id == id) transform(it) else it }))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.provider_api_keys), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.provider_key_count, pool.keys.size, ProviderKeyPool.MAX_KEYS),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(pool.rotate, enabled = enabled, role = Role.Switch,
            onValueChange = { onChange(pool.copy(rotate = it)) }), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.provider_rotate_keys))
                Text(stringResource(R.string.provider_rotate_keys_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(pool.rotate, onCheckedChange = null, enabled = enabled)
        }
        pool.keys.forEachIndexed { index, apiKey -> key(apiKey.id) {
            val visible = apiKey.id in revealed
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(apiKey.label, { value -> update(apiKey.id) { it.copy(label = value.take(80)) } },
                        enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.provider_key_name, index + 1)) },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey.value, { value -> update(apiKey.id) { it.copy(value = value.trim().take(8193)) } },
                        enabled = enabled, singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = if (visible) KeyboardType.Ascii else KeyboardType.Password, autoCorrectEnabled = false),
                        label = { Text(stringResource(R.string.provider_api_key_number, index + 1)) },
                        trailingIcon = {
                            IconButton(enabled = enabled, onClick = {
                                revealed = if (visible) revealed - apiKey.id else revealed + apiKey.id
                            }) {
                                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    stringResource(if (visible) R.string.provider_hide_key else R.string.provider_show_key, index + 1))
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(apiKey.enabled, enabled = enabled, role = Role.Switch,
                            onValueChange = { value -> update(apiKey.id) { it.copy(enabled = value) } }), verticalAlignment = Alignment.CenterVertically) {
                            Switch(apiKey.enabled, onCheckedChange = null, enabled = enabled)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.provider_use_key, index + 1))
                        }
                        ExpressiveIconButton(enabled = enabled, onClick = {
                            revealed = revealed - apiKey.id
                            onChange(pool.copy(keys = pool.keys.filterNot { it.id == apiKey.id }))
                        }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.provider_remove_key, index + 1)) }
                    }
                }
            }
        } }
        OutlinedButton(enabled = enabled && pool.keys.size < ProviderKeyPool.MAX_KEYS,
            onClick = { onChange(pool.copy(keys = pool.keys + ProviderApiKey(value = ""))) }) {
            Text(stringResource(R.string.provider_add_key))
        }
    }
}
