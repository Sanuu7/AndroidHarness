package com.androidharness.app.ui.settings

import com.androidharness.app.ui.common.localizedOption
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.BuildConfig
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.rememberStorageAccess
import com.androidharness.app.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun TroubleshootingSection(container: AppContainer, onOpen: (SettingsPage) -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = rememberStorageAccess()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val env by container.linuxEnv.state.collectAsStateWithLifecycle()
    val shizuku by container.shizuku.state.collectAsStateWithLifecycle()
    val service by container.shizuku.serviceState.collectAsStateWithLifecycle()
    val connectionFailure by container.shizuku.connectionFailure.collectAsStateWithLifecycle()
    val ssh by container.sshConnections.statuses.collectAsStateWithLifecycle()
    val servers by container.mcp.servers.collectAsStateWithLifecycle()
    val tools by container.mcp.statuses.collectAsStateWithLifecycle()
    val tampered by container.mcp.configTampered.collectAsStateWithLifecycle()
    val provider = providers.firstOrNull { it.id == settings.activeProviderId }
    var catalog by remember(provider) { mutableStateOf(CatalogCheck.NOT_CHECKED) }
    var folder by remember(project) { mutableStateOf(DiagnosticState.NOT_CHECKED) }
    var revision by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf((SystemGrants.isPostNotificationsGranted(context) && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())) }
    val owner = LocalLifecycleOwner.current
    fun refresh() {
        storage.refresh(); container.shizuku.refresh()
        notifications = (SystemGrants.isPostNotificationsGranted(context) && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
        revision++
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    suspend fun checkFolder(): DiagnosticState = withContext(Dispatchers.IO) {
        try {
            val selected = project ?: return@withContext DiagnosticState.ISSUE
            val fs = container.workspace.fsFor(selected)
            val node = fs.resolve(".")
            if (!node.exists || !node.isDirectory || fs.shellRoot?.canRead() == false) DiagnosticState.ISSUE
            else { node.list(); DiagnosticState.READY }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { DiagnosticState.ISSUE }
    }
    LaunchedEffect(project, revision, storage.granted) {
        folder = if (project?.kind == WorkspaceManager.KIND_SSH) DiagnosticState.NOT_CHECKED else {
            folder = DiagnosticState.CHECKING
            checkFolder()
        }
    }
    val providerState = when {
        provider == null -> DiagnosticState.ISSUE
        catalog == CatalogCheck.CHECKING -> DiagnosticState.CHECKING
        catalog == CatalogCheck.REACHABLE -> DiagnosticState.REACHABLE
        catalog == CatalogCheck.NOT_CHECKED -> DiagnosticState.NOT_CHECKED
        else -> DiagnosticState.ISSUE
    }
    val linuxState = when (env) {
        EnvState.NotInstalled -> DiagnosticState.OFF
        EnvState.Ready -> DiagnosticState.INSTALLED
        is EnvState.Failed -> DiagnosticState.ISSUE
        else -> DiagnosticState.CHECKING
    }
    val shizukuState = shizukuDiagnostic(shizuku == ShizukuState.GRANTED,
        service.isReady, service == UserServiceState.BINDING, service == UserServiceState.BIND_FAILED)
    val enabledServers = servers.filter { it.enabled }
    val mcpState = when {
        tampered -> DiagnosticState.ISSUE
        enabledServers.isEmpty() -> DiagnosticState.OFF
        enabledServers.any { tools[it.name]?.state in setOf("failed", "auth") } -> DiagnosticState.ISSUE
        enabledServers.any { tools[it.name]?.state == "connecting" } -> DiagnosticState.CHECKING
        enabledServers.all { tools[it.name]?.state == "connected" } -> DiagnosticState.READY
        else -> DiagnosticState.NOT_CHECKED
    }
    val sshState = when {
        ssh.isEmpty() -> DiagnosticState.NOT_CHECKED
        ssh.values.any { it.uncertain } -> DiagnosticState.ISSUE
        ssh.values.any { it.connected } -> DiagnosticState.READY
        else -> DiagnosticState.OFF
    }
    val states = linkedMapOf(DiagnosticArea.PROVIDER to providerState, DiagnosticArea.STORAGE to
        if (storage.granted) DiagnosticState.READY else DiagnosticState.ISSUE,
        DiagnosticArea.WORKSPACE to folder, DiagnosticArea.LINUX to linuxState, DiagnosticArea.SHIZUKU to shizukuState,
        DiagnosticArea.SSH to sshState, DiagnosticArea.MCP to mcpState,
        DiagnosticArea.NOTIFICATIONS to if (notifications) DiagnosticState.READY else DiagnosticState.OFF)
    val report = diagnosticReport(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, states, providers.size, enabledServers.size)
    LaunchedEffect(report) { copied = false }
    Text(stringResource(R.string.settings_troubleshooting_intro),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = ::refresh) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.settings_env_refresh_status)) }
        OutlinedButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(uiContext.getString(R.string.settings_page_troubleshooting_title), report))
            copied = true
        }) { Text(if (copied) uiContext.getString(R.string.settings_shizuku_copied) else uiContext.getString(R.string.settings_root_copy_report)) }
        TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
        }, uiContext.getString(R.string.ui_share_troubleshooting_report))) }) { Text(stringResource(R.string.settings_troubleshooting_share)) }
    }
    Text(stringResource(R.string.settings_troubleshooting_share_note), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    DiagnosticCard(localizedOption(DiagnosticArea.PROVIDER.label), providerState,
        if (provider == null) uiContext.getString(R.string.ui_choose_a_provider_to_use_the_assistant) else "${provider.name}\n${localizedOption(catalog.detail)}") {
        val local = provider?.let { com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) } == true
        if (provider != null && !local) TextButton(enabled = catalog != CatalogCheck.CHECKING, onClick = {
            scope.launch {
                catalog = CatalogCheck.CHECKING
                try {
                    val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
                    val result = withTimeout(25_000) { ModelCatalog.listModels(provider,
                        provider.effectiveApiKey(container.providers.apiKey(provider.id)), client) }
                    catalog = when (result) {
                        is ModelCatalog.Result.Models -> if (result.models.isEmpty()) CatalogCheck.EMPTY else CatalogCheck.REACHABLE
                        is ModelCatalog.Result.Failed -> catalogFailure(result.message)
                    }
                } catch (_: TimeoutCancellationException) { catalog = CatalogCheck.TIMEOUT }
                catch (e: kotlinx.coroutines.CancellationException) { catalog = CatalogCheck.NOT_CHECKED; throw e }
                catch (_: Exception) { catalog = CatalogCheck.FAILED }
            }
        }) { Text(stringResource(R.string.settings_troubleshooting_check_models)) }
        TextButton(onClick = { onOpen(if (local) SettingsPage.LOCAL_MODELS else SettingsPage.MODELS) }) { Text(stringResource(R.string.settings_troubleshooting_manage_provider)) }
    }
    DiagnosticCard(uiContext.getString(R.string.ui_storage_access_required), states.getValue(DiagnosticArea.STORAGE),
        if (storage.granted) uiContext.getString(R.string.ui_the_app_has_storage_permission) else uiContext.getString(R.string.ui_grant_storage_access_to_complete_setup_and)) {
        if (!storage.granted) TextButton(onClick = storage.request) { Text(stringResource(R.string.settings_troubleshooting_grant)) }
    }
    DiagnosticCard(localizedOption(DiagnosticArea.WORKSPACE.label), folder, (project?.name?.let { "$it\n" } ?: "") + when (folder) {
        DiagnosticState.READY -> uiContext.getString(R.string.ui_the_folder_can_be_read_write_access)
        DiagnosticState.ISSUE -> uiContext.getString(R.string.ui_the_folder_is_unavailable_choose_it_again)
        DiagnosticState.CHECKING -> uiContext.getString(R.string.ui_checking_folder_access)
        else -> uiContext.getString(R.string.ui_remote_folder_access_has_not_been_checked)
    }) {
        if (project?.kind == WorkspaceManager.KIND_SSH) TextButton(enabled = folder != DiagnosticState.CHECKING, onClick = {
            scope.launch { folder = DiagnosticState.CHECKING; folder = checkFolder() }
        }) { Text(stringResource(R.string.settings_troubleshooting_check_folder)) }
        TextButton(onClick = { onOpen(SettingsPage.WORKSPACE) }) { Text(stringResource(R.string.settings_troubleshooting_open_workspaces)) }
    }
    DiagnosticCard(uiContext.getString(R.string.ui_linux_tools_optional), linuxState, when (linuxState) {
        DiagnosticState.INSTALLED -> uiContext.getString(R.string.ui_toolchain_installed_individual_commands_have_not_been)
        DiagnosticState.ISSUE -> uiContext.getString(R.string.ui_installation_needs_attention_open_terminal_settings_to)
        DiagnosticState.CHECKING -> uiContext.getString(R.string.ui_installation_is_in_progress)
        else -> uiContext.getString(R.string.ui_install_linux_tools_to_run_local_commands)
    }) { TextButton(onClick = { onOpen(SettingsPage.ENVIRONMENT) }) { Text(stringResource(R.string.settings_troubleshooting_open_terminal)) } }
    DiagnosticCard(uiContext.getString(R.string.ui_shizuku_optional), shizukuState, when {
        service == UserServiceState.COMPAT_READY -> uiContext.getString(R.string.ui_permission_granted_commands_are_ready_in_compatibility)
        shizukuState == DiagnosticState.READY -> uiContext.getString(R.string.ui_permission_granted_and_command_service_ready)
        shizuku == ShizukuState.RUNNING_NO_PERMISSION -> uiContext.getString(R.string.ui_shizuku_is_running_grant_permission_in_terminal)
        shizukuState == DiagnosticState.CHECKING -> uiContext.getString(R.string.ui_connecting_to_the_command_service)
        shizukuState == DiagnosticState.ISSUE -> uiContext.getString(R.string.ui_the_command_service_failed_to_connect_reconnect)
        shizuku == ShizukuState.GRANTED -> uiContext.getString(R.string.ui_permission_granted_the_command_service_is_not)
        else -> uiContext.getString(R.string.ui_start_shizuku_to_use_commands_with_system)
    }, extraDetails = { ShizukuFailureDetails(connectionFailure, container.shizuku::connectionReport,
        container.shizuku::startupReport, recovered = service == UserServiceState.COMPAT_READY) }) {
        TextButton(onClick = { onOpen(SettingsPage.ENVIRONMENT) }) { Text(stringResource(R.string.settings_troubleshooting_open_shizuku)) }
    }
    DiagnosticCard(uiContext.getString(R.string.ui_ssh_workspaces_optional), sshState, when (sshState) {
        DiagnosticState.READY -> uiContext.getString(R.string.ui_connection_s_active_check_a_folder_separately, (ssh.values.count { it.connected }).toString())
        DiagnosticState.ISSUE -> uiContext.getString(R.string.ui_a_connection_needs_attention_review_it_in)
        else -> uiContext.getString(R.string.ui_no_active_ssh_connections_connect_through_workspaces)
    }) { TextButton(onClick = { onOpen(SettingsPage.WORKSPACE) }) { Text(stringResource(R.string.settings_troubleshooting_manage_connections)) } }
    DiagnosticCard(uiContext.getString(R.string.ui_connected_tools_optional), mcpState, when (mcpState) {
        DiagnosticState.READY -> uiContext.getString(R.string.ui_all_enabled_servers_are_connected_tool_execution, (enabledServers.size).toString())
        DiagnosticState.ISSUE -> if (tampered) uiContext.getString(R.string.ui_server_configuration_failed_its_integrity_check_review) else uiContext.getString(R.string.ui_a_server_needs_sign_in_reconnection_or)
        DiagnosticState.OFF -> uiContext.getString(R.string.ui_no_enabled_mcp_servers)
        DiagnosticState.CHECKING -> uiContext.getString(R.string.ui_a_server_is_connecting)
        else -> uiContext.getString(R.string.ui_some_enabled_servers_have_not_connected_yet)
    }) { TextButton(onClick = { onOpen(SettingsPage.MCP) }) { Text(stringResource(R.string.settings_troubleshooting_manage_tools)) } }
    DiagnosticCard(uiContext.getString(R.string.ui_notifications_optional), states.getValue(DiagnosticArea.NOTIFICATIONS),
        if (notifications) uiContext.getString(R.string.ui_notifications_are_allowed_by_android) else uiContext.getString(R.string.ui_you_can_use_the_app_with_notifications)) {
        TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text(stringResource(R.string.settings_troubleshooting_notifications)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            TextButton(onClick = { context.openLiveUpdateSettings() }) {
                Text(stringResource(R.string.settings_troubleshooting_live_updates))
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, state: DiagnosticState, detail: String,
    extraDetails: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (state) {
        DiagnosticState.ISSUE -> scheme.error
        DiagnosticState.READY, DiagnosticState.REACHABLE -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(when (state) {
                    DiagnosticState.ISSUE -> Icons.Outlined.ErrorOutline
                    DiagnosticState.READY, DiagnosticState.REACHABLE -> Icons.Outlined.CheckCircleOutline
                    else -> Icons.Outlined.Info
                }, null, Modifier.size(18.dp), tint = tint)
                Text(localizedOption(state.label), style = MaterialTheme.typography.labelMedium, color = tint)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            extraDetails()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    }
}
