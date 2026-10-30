package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.llm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton

/** A null connection edits the global proxy. Secrets stay local until Save succeeds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderControlsSheet(container: AppContainer, connection: ProviderConfig?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pool by remember { mutableStateOf(ProviderKeyPool()) }
    var proxy by remember { mutableStateOf(ProviderProxy()) }
    var port by remember { mutableStateOf("8080") }
    val canEditKeys = connection?.let(ProviderHeaders::editable) == true
    LaunchedEffect(connection?.id) {
        try {
            val saved = withContext(Dispatchers.IO) {
                (if (canEditKeys) container.keys.providerKeyPool(connection!!.id) else ProviderKeyPool()) to
                    container.keys.providerProxy(connection?.id)
            }
            pool = saved.first; proxy = saved.second; port = proxy.port.toString(); loaded = true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failure = "Saved provider controls could not be read. Reopen this screen before saving." }
    }
    val draftProxy = proxy.copy(port = port.toIntOrNull() ?: 0,
        username = if (proxy.type == ProviderProxyType.HTTP) proxy.username else "",
        password = if (proxy.type == ProviderProxyType.HTTP) proxy.password else "")
    val invalid = (if (canEditKeys) pool.error() else null) ?: draftProxy.error()
    val close = { if (!saving) onDismiss() }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f)) {
            Text(stringResource(if (connection == null) R.string.provider_global_proxy else R.string.provider_controls_title),
                Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
            Text(connection?.name ?: stringResource(R.string.provider_proxy_global_hint), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!loaded && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loaded && canEditKeys) {
                    ProviderKeysEditor(pool, onChange = { pool = it }, enabled = !saving)
                    HorizontalDivider()
                }
                if (loaded) {
                    Text(stringResource(R.string.provider_proxy_title), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup()) {
                        ProviderProxyMode.entries.forEach { mode ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(proxy.mode == mode, enabled = !saving, role = Role.RadioButton,
                                onClick = { proxy = proxy.copy(mode = mode) }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(proxy.mode == mode, onClick = null, enabled = !saving)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(when (mode) {
                                    ProviderProxyMode.INHERIT -> if (connection == null) R.string.provider_proxy_system else R.string.provider_proxy_inherit
                                    ProviderProxyMode.DIRECT -> R.string.provider_proxy_direct
                                    ProviderProxyMode.CUSTOM -> R.string.provider_proxy_custom
                                }))
                            }
                        }
                    }
                    if (proxy.mode == ProviderProxyMode.CUSTOM) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderProxyType.entries.forEach { type -> FilterChip(proxy.type == type, enabled = !saving,
                                onClick = { proxy = proxy.copy(type = type) }, label = { Text(if (type == ProviderProxyType.HTTP) "HTTP" else "SOCKS5") }) }
                        }
                        OutlinedTextField(proxy.host, { proxy = proxy.copy(host = it.take(254).trim()) }, enabled = !saving, singleLine = true,
                            label = { Text(stringResource(R.string.provider_proxy_host)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, enabled = !saving, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.provider_proxy_port)) }, modifier = Modifier.fillMaxWidth())
                        if (proxy.type == ProviderProxyType.HTTP) {
                            OutlinedTextField(proxy.username, { proxy = proxy.copy(username = it.take(1025)) }, enabled = !saving, singleLine = true,
                                label = { Text(stringResource(R.string.provider_proxy_username)) }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(proxy.password, { proxy = proxy.copy(password = it.take(4097)) }, enabled = !saving, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                label = { Text(stringResource(R.string.provider_proxy_password)) }, modifier = Modifier.fillMaxWidth())
                        } else Text(stringResource(R.string.provider_socks_no_login), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.provider_controls_next_request), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                (failure ?: invalid)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = close, enabled = !saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_action_cancel)) }
                Button(enabled = loaded && !saving && invalid == null, modifier = Modifier.weight(1f), onClick = {
                    saving = true; failure = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (connection == null) container.providers.saveGlobalProxy(draftProxy)
                                else container.providers.saveControls(connection, if (canEditKeys) pool else null, draftProxy)
                            }
                            onDismiss()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { failure = "Provider controls could not be saved. Your changes are still here; retry." }
                        finally { saving = false }
                    }
                }) { Text(stringResource(R.string.settings_action_save)) }
            }
        }
    }
}
