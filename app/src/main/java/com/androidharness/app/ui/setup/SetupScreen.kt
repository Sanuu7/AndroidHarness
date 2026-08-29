package com.androidharness.app.ui.setup

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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
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
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.common.HarnessMark
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.settings.ProviderSheet
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * First-run setup: checklist screen. Required steps (Provider, Storage access,
 * and Notifications) must be completed before starting the harness.
 */
@Composable
fun SetupScreen(
    container: AppContainer,
    onFinish: () -> Unit,
) {
    // The provider step is where the first API key is entered.
    SecureScreenEffect(container)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val lifecycleOwner = LocalLifecycleOwner.current

    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.androidharness.app.data.AppSettings(),
    )
    val shizukuState by container.shizuku.state.collectAsStateWithLifecycle()
    val serviceState by container.shizuku.serviceState.collectAsStateWithLifecycle()
    val envState by container.linuxEnv.state.collectAsStateWithLifecycle()

    var showProviderDialog by remember { mutableStateOf(false) }
    var storageGranted by remember {
        mutableStateOf(SystemGrants.isAllFilesAccessGranted(context))
    }
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
                storageGranted = SystemGrants.isAllFilesAccessGranted(context)
                notifGranted = SystemGrants.isPostNotificationsGranted(context)
                batteryExempt = SystemGrants.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun finish() {
        scope.launch {
            container.settings.setOnboardingDone(true)
            onFinish()
        }
    }

    // ---- Step completion -------------------------------------------------
    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId }
    val hasKey = activeProvider?.let { !container.providers.apiKey(it.id).isNullOrBlank() } ?: false
    val providerDone = activeProvider != null && hasKey
    val shizukuDone = shizukuState == ShizukuState.GRANTED && serviceState ==
        com.androidharness.app.data.env.UserServiceState.BOUND_READY
    val envDone = envState is EnvState.Ready
    val requiredDone = providerDone && storageGranted && notifGranted
    val completed = listOf(providerDone, storageGranted, notifGranted, shizukuDone, envDone, batteryExempt).count { it }

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
                Text("Welcome to AndroidHarness", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "An agent that reads, edits and runs code on this device. " +
                        "Complete the required setup to begin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "$completed of 6 complete",
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = scheme.onSurfaceVariant,
                    )
                    ThinLinearProgress(
                        progress = { completed / 6f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.size(2.dp))

                // -- 1. Provider (required) --------------------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.Cloud, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = "Connect an AI provider",
                    status = when {
                        providerDone -> "${activeProvider!!.name} · ${activeProvider.model}"
                        else -> "Required: OpenRouter, Groq, Anthropic, Gemini, Ollama…"
                    },
                    complete = providerDone,
                    optional = false,
                ) {
                    if (!providerDone) {
                        Button(onClick = { showProviderDialog = true }) { Text("Connect") }
                    }
                }

                // -- 2. Storage access (required) --------------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.SdStorage, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = "Storage access",
                    status = if (storageGranted) "All files access granted"
                             else "Required: grant all files access to read and edit project files",
                    complete = storageGranted,
                    optional = false,
                ) {
                    if (!storageGranted) {
                        Button(onClick = { SystemGrants.openAllFilesAccess(context) }) { Text("Grant") }
                    }
                }

                // -- 3. Notifications (required) ---------------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.Notifications, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = "Notifications",
                    status = if (notifGranted) "Runs report progress and approvals" 
                             else "Required: needed for run progress while backgrounded",
                    complete = notifGranted,
                    optional = false,
                ) {
                    if (!notifGranted) {
                        Button(onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else notifGranted = true
                        }) { Text("Allow") }
                    }
                }

                // -- 4. Shizuku (optional) ---------------------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.Shield, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = "Shizuku",
                    status = when (shizukuState) {
                        ShizukuState.NOT_INSTALLED -> "Optional: full shell toolchain & background servers"
                        ShizukuState.NOT_RUNNING -> "Open the Shizuku app and start it"
                        ShizukuState.RUNNING_NO_PERMISSION -> "Grant access to unlock ADB-level commands"
                        ShizukuState.GRANTED ->
                            if (serviceState == com.androidharness.app.data.env.UserServiceState.BOUND_READY)
                                "Connected: privileged shell ready"
                            else "Granted: waiting for user service…"
                    },
                    complete = shizukuDone,
                    optional = true,
                ) {
                    when (shizukuState) {
                        ShizukuState.RUNNING_NO_PERMISSION ->
                            Button(onClick = { container.shizuku.requestPermission() }) { Text("Grant") }
                        ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING ->
                            OutlinedButton(onClick = { container.shizuku.refresh() }) { Text("Refresh") }
                        ShizukuState.GRANTED -> {}
                    }
                }

                // -- 5. Linux environment (optional) -----------------------
                SetupStep(
                    icon = { Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp), scheme.onSurfaceVariant) },
                    title = "Linux environment",
                    status = when (val s = envState) {
                        EnvState.Ready -> "Ready: bash, git, python, node and npm for real commands"
                        is EnvState.Downloading -> "Downloading ${s.pkg} (${s.index}/${s.total})"
                        is EnvState.Installing -> "Installing ${s.pkg} (${s.index}/${s.total})"
                        is EnvState.Preparing -> "Resolving packages…"
                        is EnvState.Failed -> s.message.take(80)
                        EnvState.NotInstalled -> "Optional: bash, git, python, pip, node, npm for real commands"
                    },
                    complete = envDone,
                    optional = true,
                    busy = envState is EnvState.Downloading || envState is EnvState.Installing || envState is EnvState.Preparing,
                ) {
                    when (envState) {
                        EnvState.NotInstalled, is EnvState.Failed ->
                            OutlinedButton(onClick = {
                                scope.launch { container.linuxEnv.install(container.linuxEnv.fullPackages) }
                            }) { Text(if (envState is EnvState.Failed) "Retry" else "Install") }
                        else -> {}
                    }
                }

                // -- 6. Battery optimization (optional) --------------------
                SetupStep(
                    icon = {
                        Icon(Icons.Outlined.BatteryChargingFull, null, Modifier.size(16.dp), scheme.onSurfaceVariant)
                    },
                    title = "Battery optimization",
                    status = if (batteryExempt) "Exempted: long runs survive screen-off"
                             else "Optional: keeps long runs alive on aggressive OEMs",
                    complete = batteryExempt,
                    optional = true,
                ) {
                    if (!batteryExempt) {
                        OutlinedButton(onClick = {
                            SystemGrants.requestBatteryExemption(context)
                            batteryExempt = SystemGrants.isIgnoringBatteryOptimizations(context)
                        }) { Text("Fix") }
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
                    if (requiredDone) "All required steps complete" else "Complete required steps (*)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (requiredDone) success else scheme.onSurfaceVariant,
                )
                Button(onClick = ::finish, enabled = requiredDone) { Text("Start harness") }
            }
        }
    }

    if (showProviderDialog) {
        ProviderSheet(
            existing = null,
            existingKey = null,
            onDismiss = { showProviderDialog = false },
            onSave = { name, type: ProviderType, baseUrl, model, apiKey ->
                scope.launch {
                    val created = container.providers.add(name, type, baseUrl, model, apiKey)
                    if (settings.activeProviderId == null) {
                        container.settings.setActiveProvider(created.id)
                    }
                    showProviderDialog = false
                }
            },
        )
    }
}

@Composable
private fun SetupStep(
    icon: @Composable () -> Unit,
    title: String,
    status: String,
    complete: Boolean,
    optional: Boolean,
    busy: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
                    if (optional) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Optional",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (complete) success else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (busy) {
                    ThinLinearProgress(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.width(8.dp))
            if (complete) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Done",
                    tint = success,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Box { trailing() }
            }
        }
    }
}
