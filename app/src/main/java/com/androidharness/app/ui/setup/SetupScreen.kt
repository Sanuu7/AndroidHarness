package com.androidharness.app.ui.setup

import com.androidharness.app.R
import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.ui.common.HarnessMark
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.common.rememberStorageAccess
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.OutlinedButton

/**
 * First-run setup requires storage access. Notifications and additional tools are optional.
 */
@Composable
fun SetupScreen(
    container: AppContainer,
    onFinish: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val lifecycleOwner = LocalLifecycleOwner.current

    val shizukuState by container.shizuku.state.collectAsStateWithLifecycle()
    val serviceState by container.shizuku.serviceState.collectAsStateWithLifecycle()
    val envState by container.linuxEnv.state.collectAsStateWithLifecycle()

    val storageAccess = rememberStorageAccess()
    val storageGranted = storageAccess.granted
    var notifGranted by remember {
        mutableStateOf(SystemGrants.isPostNotificationsGranted(context))
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notifGranted = granted }
    var batteryExempt by remember { mutableStateOf(SystemGrants.isIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = SystemGrants.isPostNotificationsGranted(context)
                batteryExempt = SystemGrants.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun finish() {
        if (!storageGranted) return
        scope.launch {
            container.settings.setOnboardingDone(true)
            onFinish()
        }
    }

    // ---- Step completion -------------------------------------------------
    val shizukuDone = shizukuState == ShizukuState.GRANTED && serviceState.isReady
    val envDone = envState is EnvState.Ready
    val completed = listOf(storageGranted, notifGranted, shizukuDone, envDone, batteryExempt).count { it }

    Scaffold(containerColor = scheme.surface) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                HarnessMark(size = 44.dp)
                Spacer(Modifier.size(2.dp))
                Text(uiContext.getString(R.string.ui_welcome_to_androidharness), style = MaterialTheme.typography.headlineSmall)
                Text(
                    uiContext.getString(R.string.ui_grant_storage_access_to_start_coding_notifications),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        uiContext.getString(R.string.ui_of_5_complete, (completed).toString()),
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = scheme.onSurfaceVariant,
                    )
                    ThinLinearProgress(
                        progress = { completed / 5f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.size(2.dp))

                // -- 1. Storage access (required) --------------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.SdStorage, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = uiContext.getString(R.string.settings_env_storage_access),
                    status = if (storageGranted) uiContext.getString(R.string.ui_device_storage_access_granted)
                             else uiContext.getString(R.string.ui_required_allow_access_to_device_files_and),
                    complete = storageGranted,
                    optional = false,
                ) {
                    if (!storageGranted) {
                        Button(onClick = storageAccess.request) { Text(uiContext.getString(R.string.settings_action_grant)) }
                    }
                }

                // -- 2. Notifications (optional) ---------------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.Notifications, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = uiContext.getString(R.string.ui_notifications),
                    status = if (notifGranted) uiContext.getString(R.string.ui_runs_report_progress_and_approvals)
                             else uiContext.getString(R.string.ui_progress_and_approvals_outside_the_app),
                    complete = notifGranted,
                    optional = true,
                ) {
                    if (!notifGranted) {
                        Button(onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else notifGranted = true
                        }) { Text(uiContext.getString(R.string.ui_allow)) }
                    }
                }

                // -- 3. Shizuku (optional) ---------------------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.Shield, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = "Shizuku",
                    status = when (shizukuState) {
                        ShizukuState.NOT_INSTALLED -> uiContext.getString(R.string.ui_optional_full_shell_toolchain_background_servers)
                        ShizukuState.NOT_RUNNING -> uiContext.getString(R.string.ui_open_the_shizuku_app_and_start_it)
                        ShizukuState.RUNNING_NO_PERMISSION -> uiContext.getString(R.string.ui_grant_access_to_unlock_adb_level_commands)
                        ShizukuState.GRANTED ->
                            if (serviceState.isReady)
                                uiContext.getString(R.string.ui_connected_privileged_shell_ready)
                            else uiContext.getString(R.string.ui_granted_waiting_for_user_service)
                    },
                    complete = shizukuDone,
                    optional = true,
                ) {
                    when (shizukuState) {
                        ShizukuState.RUNNING_NO_PERMISSION ->
                            Button(onClick = { container.shizuku.requestPermission() }) { Text(uiContext.getString(R.string.settings_action_grant)) }
                        ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING ->
                            OutlinedButton(onClick = { container.shizuku.refresh() }) { Text(uiContext.getString(R.string.ui_refresh)) }
                        ShizukuState.GRANTED -> {}
                    }
                }

                // -- 4. Linux environment (optional) -----------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = uiContext.getString(R.string.settings_search_linux_title),
                    status = when (val s = envState) {
                        EnvState.Ready -> uiContext.getString(R.string.ui_ready_bash_git_python_node_and_npm)
                        is EnvState.Downloading, is EnvState.Installing, EnvState.Preparing -> ""
                        is EnvState.Failed -> s.message.take(80)
                        EnvState.NotInstalled -> uiContext.getString(R.string.ui_optional_bash_git_python_pip_node_npm)
                    },
                    complete = envDone,
                    optional = true,
                    busy = envState is EnvState.Downloading || envState is EnvState.Installing || envState is EnvState.Preparing,
                    progress = { com.androidharness.app.ui.common.PackageInstallProgress(envState) },
                ) {
                    when (envState) {
                        EnvState.NotInstalled, is EnvState.Failed ->
                            OutlinedButton(onClick = {
                                scope.launch { container.linuxEnv.install(container.linuxEnv.fullPackages) }
                            }) { Text(if (envState is EnvState.Failed) uiContext.getString(R.string.ui_retry) else uiContext.getString(R.string.settings_action_install)) }
                        else -> {}
                    }
                }

                // -- 5. Battery optimization (optional) --------------------
                SetupStep(
                    icon = {
                        Icon(Icons.Outlined.BatteryChargingFull, null, Modifier.size(16.dp), scheme.onSurfaceVariant)
                    },
                    title = uiContext.getString(R.string.settings_search_battery_title),
                    status = if (batteryExempt) uiContext.getString(R.string.ui_exempted_long_runs_survive_screen_off)
                             else uiContext.getString(R.string.ui_optional_keeps_long_runs_alive_on_aggressive),
                    complete = batteryExempt,
                    optional = true,
                ) {
                    if (!batteryExempt) {
                        OutlinedButton(onClick = {
                            SystemGrants.requestBatteryExemption(context)
                            batteryExempt = SystemGrants.isIgnoringBatteryOptimizations(context)
                        }) { Text(uiContext.getString(R.string.settings_action_fix)) }
                    }
                }
            }

            // Footer actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (storageGranted) uiContext.getString(R.string.ui_ready_to_begin) else uiContext.getString(R.string.ui_allow_storage_access_to_begin),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (storageGranted) success else scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Button(onClick = ::finish, enabled = storageGranted) { Text(uiContext.getString(R.string.ui_start_harness)) }
            }
        }
    }

}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun SetupStep(
    icon: @Composable () -> Unit,
    title: String,
    status: String,
    complete: Boolean,
    optional: Boolean,
    busy: Boolean = false,
    progress: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(9.dp)),
            ) { icon() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
                    if (optional) {
                        Text(
                            uiContext.getString(R.string.ui_optional),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (status.isNotEmpty()) Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (complete) success else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (busy) {
                    if (progress != null) progress() else ThinLinearProgress(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.width(8.dp))
            if (complete) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = uiContext.getString(R.string.settings_action_done),
                    tint = success,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Box { trailing() }
            }
        }
    }
}
