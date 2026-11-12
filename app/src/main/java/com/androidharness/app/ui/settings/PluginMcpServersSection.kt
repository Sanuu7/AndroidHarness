package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.config.HarnessConfig
import com.androidharness.app.config.HarnessConfigRepository
import com.androidharness.app.plugins.PluginManager
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses the same resolved configuration as Code; plugin servers never become duplicate global entries. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PluginMcpServersSection(container: AppContainer, onOpenPlugins: () -> Unit, onOpenConfiguration: () -> Unit,
    workspaceOverride: WorkspaceFs? = null, plugins: PluginManager = container.pluginManager,
    configuration: HarnessConfigRepository = container.configuration) {
    val current by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val workspace = workspaceOverride ?: current
    val pluginRevision by plugins.revision.collectAsStateWithLifecycle()
    val configRevision by configuration.revision.collectAsStateWithLifecycle()
    val statuses by container.mcp.statuses.collectAsStateWithLifecycle()
    var snapshot by remember(workspace) { mutableStateOf<HarnessConfig?>(null) }
    var approved by remember(workspace) { mutableStateOf(false) }
    var error by remember(workspace) { mutableStateOf<String?>(null) }
    LaunchedEffect(workspace, pluginRevision, configRevision) {
        snapshot = null; error = null
        if (workspace != null) try {
            val resolved = withContext(Dispatchers.IO) { configuration.load(workspace) }
            approved = withContext(Dispatchers.IO) { container.mcp.isWorkspaceApproved(workspace, resolved) }
            snapshot = resolved
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    val resolved = snapshot
    val servers = resolved?.servers.orEmpty().filter { resolved?.serverSources?.get(it.name)?.plugin != null }
    if (servers.isEmpty() && error == null) return
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pm_mcp_from_plugins), style = MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            servers.forEach { server ->
                val origin = requireNotNull(resolved?.serverSources?.get(server.name))
                val global = origin.source.startsWith("Managed/Global/") || origin.source.startsWith("Global/")
                val scopeName = if (global) stringResource(R.string.config_global) else if (workspaceOverride == null)
                    project?.name ?: stringResource(R.string.ui_app_workspace) else stringResource(R.string.config_project)
                val status = workspace?.let { statuses[container.mcp.workspaceConnectionName(it, server)] } ?: statuses[server.name]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Hub, null, Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(origin.name, style = MaterialTheme.typography.titleSmall)
                        Text("${origin.plugin} · $scopeName", style = MaterialTheme.typography.bodySmall)
                        Text(when {
                            !server.enabled -> stringResource(R.string.ui_off_enable_to_use_its_tools)
                            !approved -> stringResource(R.string.pm_mcp_approval_pending)
                            status?.state == "connected" -> stringResource(R.string.ui_connected_tools, status.toolCount.toString())
                            status?.state == "connecting" -> stringResource(R.string.ui_connecting)
                            status?.state == "auth" -> stringResource(R.string.ui_sign_in_to_use_this_server)
                            status?.state == "failed" || status?.state == "blocked" -> stringResource(R.string.ui_failed, resolved?.redact(status.error.orEmpty()).orEmpty().take(160))
                            else -> stringResource(R.string.pm_mcp_ready)
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            Text(stringResource(R.string.pm_mcp_managed_hint), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenPlugins) { Text(stringResource(R.string.pm_manage_plugins)) }
                TextButton(onClick = onOpenConfiguration) { Text(stringResource(R.string.pm_mcp_permissions)) }
            }
        }
    }
}
