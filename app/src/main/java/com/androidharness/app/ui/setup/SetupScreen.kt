@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.androidharness.app.ui.setup

import android.Manifest
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.HarnessMark
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.rememberMediaAccess
import com.androidharness.app.ui.common.rememberStorageAccess
import com.androidharness.app.ui.settings.BackupRestoreSheet
import com.androidharness.app.ui.settings.BackupSheetMode
import com.androidharness.app.ui.settings.SettingsRestoreDialogs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** First-run setup, one idea per page. Every step can be skipped and done later in Settings. */
private enum class Step { WELCOME, STORAGE, RESTORE, POWERUPS, READY }

@Composable
fun SetupScreen(
    container: AppContainer,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    val shizukuState by container.shizuku.state.collectAsStateWithLifecycle()
    val serviceState by container.shizuku.serviceState.collectAsStateWithLifecycle()
    val envState by container.linuxEnv.state.collectAsStateWithLifecycle()

    val storageAccess = rememberStorageAccess()
    val storageGranted = storageAccess.granted
    val mediaAccess = rememberMediaAccess()
    // Before Android 11 the everyday dialog already is full access, so it only shows once.
    val legacyStorage = android.os.Build.VERSION.SDK_INT < 30
    val allFilesAvailable = remember(context) { legacyStorage || SystemGrants.isAllFilesAccessAvailable(context) }
    var notifGranted by remember { mutableStateOf(SystemGrants.isPostNotificationsGranted(context)) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
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

    var stepName by rememberSaveable { mutableStateOf(Step.WELCOME.name) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    var forward by rememberSaveable { mutableStateOf(true) }
    var storageRequested by rememberSaveable { mutableStateOf(false) }
    var chatsRestored by rememberSaveable { mutableIntStateOf(0) }
    var chatsDone by rememberSaveable { mutableStateOf(false) }
    var settingsDone by rememberSaveable { mutableStateOf(false) }
    var settingsError by rememberSaveable { mutableStateOf<String?>(null) }
    var chatSource by remember { mutableStateOf<Uri?>(null) }
    var settingsSource by remember { mutableStateOf<Uri?>(null) }

    val step = Step.valueOf(stepName)
    val steps = if (restoring) Step.entries else Step.entries - Step.RESTORE
    val index = steps.indexOf(step).coerceAtLeast(0)

    fun goTo(target: Step) {
        forward = steps.indexOf(target) >= index
        stepName = target.name
    }
    fun next() { steps.getOrNull(index + 1)?.let(::goTo) }
    fun back() { steps.getOrNull(index - 1)?.let(::goTo) }

    fun finish() {
        scope.launch {
            container.settings.setOnboardingDone(true)
            onFinish()
        }
    }

    BackHandler(enabled = index > 0 && chatSource == null && settingsSource == null) { back() }

    // Granting storage from this page moves on by itself once the badge has turned into a check.
    LaunchedEffect(storageGranted, storageRequested, stepName) {
        if (storageGranted && storageRequested && step == Step.STORAGE) {
            delay(750)
            storageRequested = false
            next()
        }
    }

    val pickChats = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) chatSource = uri }
    val pickSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) settingsSource = uri }

    val shizukuDone = shizukuState == ShizukuState.GRANTED && serviceState.isReady
    val envDone = envState is EnvState.Ready

    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                ShapeField(progress = index.toFloat() / (steps.size - 1).coerceAtLeast(1))
                Column(
                    Modifier.fillMaxSize().widthIn(max = 600.dp).align(Alignment.TopCenter),
                ) {
                    if (step != Step.WELCOME) {
                        StepDots(count = steps.size, current = index,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
                    }
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = {
                            val enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) {
                                if (forward) it / 3 else -it / 3
                            } + fadeIn(tween(240, delayMillis = 40)) + scaleIn(spring(stiffness = Spring.StiffnessMediumLow), initialScale = 0.96f)
                            val exit = slideOutHorizontally(tween(200)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(140)) +
                                scaleOut(tween(200), targetScale = 0.98f)
                            enter togetherWith exit
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        label = "setup step",
                    ) { page ->
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (page) {
                                Step.WELCOME -> WelcomePage(
                                    onFresh = { restoring = false; forward = true; stepName = Step.STORAGE.name },
                                    onRestore = { restoring = true; forward = true; stepName = Step.STORAGE.name },
                                )
                                Step.STORAGE -> StoragePage(
                                    allFiles = storageGranted,
                                    media = mediaAccess.granted,
                                    legacy = legacyStorage,
                                    allFilesAvailable = allFilesAvailable,
                                    onAllFiles = { storageRequested = true; storageAccess.request() },
                                    onMedia = mediaAccess.request,
                                )
                                Step.RESTORE -> RestorePage(
                                    chatsDone = chatsDone,
                                    chatsRestored = chatsRestored,
                                    settingsDone = settingsDone,
                                    settingsError = settingsError,
                                    onChats = { pickChats.launch(arrayOf("*/*")) },
                                    onSettings = { settingsError = null; pickSettings.launch(arrayOf("application/octet-stream", "*/*")) },
                                )
                                Step.POWERUPS -> PowerUpsPage(
                                    notifGranted = notifGranted,
                                    onNotifications = {
                                        if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        else notifGranted = true
                                    },
                                    shizukuState = shizukuState,
                                    shizukuDone = shizukuDone,
                                    shizukuReady = serviceState.isReady,
                                    onShizukuGrant = { container.shizuku.requestPermission() },
                                    onShizukuRefresh = { container.shizuku.refresh() },
                                    envState = envState,
                                    onInstallEnv = { scope.launch { container.linuxEnv.install(container.linuxEnv.fullPackages) } },
                                    batteryExempt = batteryExempt,
                                    onBattery = {
                                        SystemGrants.requestBatteryExemption(context)
                                        batteryExempt = SystemGrants.isIgnoringBatteryOptimizations(context)
                                    },
                                )
                                Step.READY -> ReadyPage(buildList {
                                    if (storageGranted) add(stringResource(R.string.setup_ready_storage))
                                    else if (mediaAccess.granted) add(stringResource(R.string.setup_ready_media))
                                    if (chatsRestored > 0) add(pluralStringResource(R.plurals.setup_restored_chats, chatsRestored, chatsRestored))
                                    if (settingsDone) add(stringResource(R.string.setup_ready_settings))
                                    if (notifGranted) add(stringResource(R.string.setup_ready_notifications))
                                    if (shizukuDone) add(stringResource(R.string.setup_ready_shizuku))
                                    if (envDone) add(stringResource(R.string.setup_ready_linux))
                                    if (batteryExempt) add(stringResource(R.string.setup_ready_battery))
                                })
                            }
                        }
                    }
                    if (step != Step.WELCOME) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { back() }) { Text(stringResource(R.string.setup_back)) }
                            Spacer(Modifier.weight(1f))
                            val (label, action, enabled) = when (step) {
                                Step.STORAGE -> Triple(
                                    stringResource(if (storageGranted || mediaAccess.granted) R.string.setup_continue else R.string.setup_storage_skip),
                                    { next() }, true)
                                Step.RESTORE -> Triple(
                                    stringResource(if (chatsDone || settingsDone) R.string.setup_continue else R.string.setup_skip), { next() }, true)
                                Step.READY -> Triple(stringResource(R.string.ui_start_harness), { haptics.performHapticFeedback(HapticFeedbackType.Confirm); finish() }, true)
                                else -> Triple(stringResource(R.string.setup_continue), { next() }, true)
                            }
                            Button(onClick = action, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                                AnimatedContent(label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "setup action") { text ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text, style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        chatSource?.let { uri ->
            BackupRestoreSheet(container, BackupSheetMode.Restore(uri), onDismiss = { chatSource = null },
                onRestored = { result -> chatsRestored += result.sessions; chatsDone = true })
        }
        settingsSource?.let { uri ->
            SettingsRestoreDialogs(container, uri) { ok, message ->
                settingsSource = null
                when (ok) {
                    true -> { settingsDone = true; settingsError = null }
                    false -> settingsError = message
                    null -> Unit
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun WelcomePage(onFresh: () -> Unit, onRestore: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HeroMark() }
    Spacer(Modifier.height(8.dp))
    Rise(1) {
        Text(stringResource(R.string.setup_welcome_title), style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Rise(2) {
        Text(stringResource(R.string.setup_welcome_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(12.dp))
    Rise(3) {
        ChoiceCard(Icons.Rounded.RocketLaunch, stringResource(R.string.setup_start_fresh), stringResource(R.string.setup_start_fresh_body),
            emphasized = true, onClick = onFresh)
    }
    Rise(4) {
        ChoiceCard(Icons.Rounded.SettingsBackupRestore, stringResource(R.string.setup_restore), stringResource(R.string.setup_restore_body),
            emphasized = false, onClick = onRestore)
    }
}

@Composable
private fun StoragePage(
    allFiles: Boolean,
    media: Boolean,
    legacy: Boolean,
    allFilesAvailable: Boolean,
    onAllFiles: () -> Unit,
    onMedia: () -> Unit,
) {
    val sdk = android.os.Build.VERSION.SDK_INT
    Spacer(Modifier.height(16.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        MorphBadge(done = allFiles || media, icon = Icons.Rounded.SdStorage, size = 132.dp, seed = MaterialShapes.Cookie9Sided)
    }
    AnimatedContent(allFiles, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "storage title") { on ->
        Text(stringResource(if (on) R.string.setup_storage_granted else R.string.setup_storage_title),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Text(stringResource(R.string.setup_storage_body), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    when {
        // Android 8 to 10: one runtime dialog covers everything.
        legacy -> Rise(1) {
            PowerCard(
                icon = Icons.Rounded.Folder, title = stringResource(R.string.setup_storage_legacy),
                status = stringResource(R.string.setup_storage_legacy_body),
                done = allFiles, seed = MaterialShapes.Clover4Leaf,
            ) { Button(onClick = onAllFiles) { Text(stringResource(R.string.ui_allow)) } }
        }
        allFilesAvailable -> Rise(1) {
            PowerCard(
                icon = Icons.Rounded.Folder, title = stringResource(R.string.setup_storage_all_files),
                status = stringResource(R.string.setup_storage_all_files_body),
                done = allFiles, seed = MaterialShapes.Clover4Leaf,
            ) { Button(onClick = onAllFiles) { Text(stringResource(R.string.ui_allow)) } }
        }
        else -> Rise(1) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.setup_storage_unavailable), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    // Full access already reads everything the media dialog would.
    if (!legacy && !allFiles) {
        Rise(2) {
            val modern = sdk >= 33
            PowerCard(
                icon = Icons.Rounded.PhotoLibrary,
                title = stringResource(if (modern) R.string.setup_storage_media else R.string.setup_storage_read),
                status = stringResource(if (modern) R.string.setup_storage_media_body else R.string.setup_storage_read_body),
                done = media, seed = MaterialShapes.Flower,
            ) {
                if (allFilesAvailable) OutlinedButton(onClick = onMedia) { Text(stringResource(R.string.ui_allow)) }
                else Button(onClick = onMedia) { Text(stringResource(R.string.ui_allow)) }
            }
        }
    }
}

@Composable
private fun RestorePage(
    chatsDone: Boolean,
    chatsRestored: Int,
    settingsDone: Boolean,
    settingsError: String?,
    onChats: () -> Unit,
    onSettings: () -> Unit,
) {
    PageHeader(stringResource(R.string.setup_restore_title), stringResource(R.string.setup_restore_subtitle))
    Rise(1) {
        RestoreCard(
            icon = Icons.Rounded.Forum,
            title = stringResource(R.string.setup_restore_chats),
            body = if (chatsDone) pluralStringResource(R.plurals.setup_restored_chats, chatsRestored, chatsRestored)
                else stringResource(R.string.setup_restore_chats_body),
            done = chatsDone,
            action = stringResource(if (chatsDone) R.string.setup_restore_another else R.string.setup_restore_choose),
            onClick = onChats,
            seed = MaterialShapes.Clover4Leaf,
        )
    }
    Rise(2) {
        RestoreCard(
            icon = Icons.Rounded.Tune,
            title = stringResource(R.string.setup_restore_settings),
            body = settingsError ?: stringResource(if (settingsDone) R.string.setup_restore_settings_done else R.string.setup_restore_settings_body),
            done = settingsDone,
            action = stringResource(if (settingsDone) R.string.setup_restore_another else R.string.setup_restore_choose),
            onClick = onSettings,
            seed = MaterialShapes.SoftBurst,
            error = settingsError != null,
        )
    }
}

@Composable
private fun PowerUpsPage(
    notifGranted: Boolean,
    onNotifications: () -> Unit,
    shizukuState: ShizukuState,
    shizukuDone: Boolean,
    shizukuReady: Boolean,
    onShizukuGrant: () -> Unit,
    onShizukuRefresh: () -> Unit,
    envState: EnvState,
    onInstallEnv: () -> Unit,
    batteryExempt: Boolean,
    onBattery: () -> Unit,
) {
    val context = LocalContext.current
    PageHeader(stringResource(R.string.setup_powerups_title), stringResource(R.string.setup_powerups_subtitle))
    Rise(1) {
        PowerCard(
            icon = Icons.Rounded.Notifications, title = stringResource(R.string.ui_notifications),
            status = stringResource(if (notifGranted) R.string.ui_runs_report_progress_and_approvals else R.string.ui_progress_and_approvals_outside_the_app),
            done = notifGranted, seed = MaterialShapes.Cookie6Sided,
        ) { Button(onClick = onNotifications) { Text(stringResource(R.string.ui_allow)) } }
    }
    Rise(2) {
        PowerCard(
            icon = Icons.Rounded.Shield, title = "Shizuku",
            status = when (shizukuState) {
                ShizukuState.NOT_INSTALLED -> stringResource(R.string.ui_optional_full_shell_toolchain_background_servers)
                ShizukuState.NOT_RUNNING -> stringResource(R.string.ui_open_the_shizuku_app_and_start_it)
                ShizukuState.RUNNING_NO_PERMISSION -> stringResource(R.string.ui_grant_access_to_unlock_adb_level_commands)
                ShizukuState.GRANTED -> stringResource(if (shizukuReady) R.string.ui_connected_privileged_shell_ready else R.string.ui_granted_waiting_for_user_service)
            },
            done = shizukuDone, seed = MaterialShapes.Pentagon,
        ) {
            when (shizukuState) {
                ShizukuState.RUNNING_NO_PERMISSION -> Button(onClick = onShizukuGrant) { Text(stringResource(R.string.settings_action_grant)) }
                ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING -> OutlinedButton(onClick = onShizukuRefresh) { Text(stringResource(R.string.ui_refresh)) }
                ShizukuState.GRANTED -> Unit
            }
        }
    }
    Rise(3) {
        val busy = envState is EnvState.Downloading || envState is EnvState.Installing || envState is EnvState.Preparing
        PowerCard(
            icon = Icons.Rounded.Terminal, title = stringResource(R.string.settings_search_linux_title),
            status = when (envState) {
                EnvState.Ready -> stringResource(R.string.ui_ready_bash_git_python_node_and_npm)
                is EnvState.Downloading, is EnvState.Installing, EnvState.Preparing -> ""
                is EnvState.Failed -> envState.message.take(80)
                EnvState.NotInstalled -> stringResource(R.string.ui_optional_bash_git_python_pip_node_npm)
            },
            done = envState is EnvState.Ready, seed = MaterialShapes.Cookie12Sided,
            progress = if (busy) ({ com.androidharness.app.ui.common.PackageInstallProgress(envState) }) else null,
        ) {
            if (envState is EnvState.NotInstalled || envState is EnvState.Failed) {
                OutlinedButton(onClick = onInstallEnv) {
                    Text(context.getString(if (envState is EnvState.Failed) R.string.ui_retry else R.string.settings_action_install))
                }
            }
        }
    }
    Rise(4) {
        PowerCard(
            icon = Icons.Rounded.BatteryChargingFull, title = stringResource(R.string.settings_search_battery_title),
            status = stringResource(if (batteryExempt) R.string.ui_exempted_long_runs_survive_screen_off else R.string.ui_optional_keeps_long_runs_alive_on_aggressive),
            done = batteryExempt, seed = MaterialShapes.Flower,
        ) { OutlinedButton(onClick = onBattery) { Text(stringResource(R.string.settings_action_fix)) } }
    }
}

@Composable
private fun ReadyPage(chips: List<String>) {
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(180); landed = true }
    Spacer(Modifier.height(32.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        MorphBadge(done = landed, icon = Icons.Rounded.RocketLaunch, size = 156.dp, seed = MaterialShapes.Sunny)
    }
    Spacer(Modifier.height(8.dp))
    Rise(1) {
        Text(stringResource(R.string.setup_ready_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Rise(2) {
        Text(stringResource(R.string.setup_ready_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { chips.forEachIndexed { i, chip -> ReadyChip(chip, i) } }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun animationsOff(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/** Content rises and fades in, each [order] a beat after the one before. */
@Composable
private fun Rise(order: Int, content: @Composable () -> Unit) {
    val still = animationsOff()
    val shown = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shown.value < 1f) { delay(70L * order); shown.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
    }
    Box(Modifier.graphicsLayer {
        alpha = shown.value.coerceIn(0f, 1f)
        translationY = (1f - shown.value) * 36.dp.toPx()
    }) { content() }
}

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The logo sits on a slowly turning cookie that springs in on first launch. */
@Composable
private fun HeroMark() {
    val still = animationsOff()
    val pop = remember { Animatable(if (still) 1f else 0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
    val spin = if (still) 0f else rememberInfiniteTransition(label = "hero spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "hero angle").value
    Box(
        Modifier.size(168.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin }
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()))
        HarnessMark(size = 76.dp)
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, body: String, emphasized: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "choice press")
    val corner by animateDpAsState(if (pressed) 20.dp else 32.dp, spring(stiffness = Spring.StiffnessMedium), label = "choice corner")
    val (fill, ink) = if (emphasized) scheme.primary to scheme.onPrimary else scheme.secondaryContainer to scheme.onSecondaryContainer
    Surface(
        onClick = onClick, interactionSource = interaction, shape = RoundedCornerShape(corner), color = fill, contentColor = ink,
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(ink.copy(alpha = 0.14f), MaterialShapes.Cookie6Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.8f))
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * A shape that morphs from [seed] into a circle as the step completes, while its icon turns into
 * a check. Completion springs with a little overshoot and a confirm tap.
 */
@Composable
private fun MorphBadge(done: Boolean, icon: ImageVector, size: Dp, seed: RoundedPolygon, compact: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val still = animationsOff()
    val morph = remember(seed) { Morph(seed, MaterialShapes.Circle) }
    val progress = remember { Animatable(if (done) 1f else 0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(done) {
        if (!first && done) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        first = false
        val target = if (done) 1f else 0f
        if (still) progress.snapTo(target) else progress.animateTo(target, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
    }
    val spin = if (still || done) 0f else rememberInfiniteTransition(label = "badge spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (compact) 18_000 else 30_000, easing = LinearEasing)), label = "badge angle").value
    val fill by animateColorAsState(if (done) scheme.primary else scheme.primaryContainer, label = "badge fill")
    val ink by animateColorAsState(if (done) scheme.onPrimary else scheme.onPrimaryContainer, label = "badge ink")
    Box(
        Modifier.size(size).drawWithCache {
            val path = Path()
            val matrix = Matrix()
            onDrawBehind {
                val p = progress.value
                morph.toPath(p.coerceIn(0f, 1f), path)
                matrix.reset()
                matrix.scale(this.size.width, this.size.height)
                path.transform(matrix)
                rotate(spin + 90f * p) { drawPath(path, fill) }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            done,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.3f) + fadeIn()) togetherWith
                    (scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(120)))
            },
            label = "badge icon",
        ) { on ->
            Icon(if (on) Icons.Rounded.Check else icon, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.42f))
        }
    }
}

@Composable
private fun RestoreCard(
    icon: ImageVector,
    title: String,
    body: String,
    done: Boolean,
    action: String,
    onClick: () -> Unit,
    seed: RoundedPolygon,
    error: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (done) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerHigh, label = "restore fill")
    Surface(shape = RoundedCornerShape(28.dp), color = fill, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MorphBadge(done = done, icon = icon, size = 52.dp, seed = seed, compact = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    AnimatedContent(body, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) }, label = "restore body") { text ->
                        Text(text, style = MaterialTheme.typography.bodyMedium,
                            color = if (error) scheme.error else scheme.onSurfaceVariant)
                    }
                }
            }
            if (done) OutlinedButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(action) }
            else Button(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

@Composable
private fun PowerCard(
    icon: ImageVector,
    title: String,
    status: String,
    done: Boolean,
    seed: RoundedPolygon,
    progress: (@Composable () -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (done) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surfaceContainerHigh, label = "power fill")
    Surface(shape = RoundedCornerShape(24.dp), color = fill, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MorphBadge(done = done, icon = icon, size = 48.dp, seed = seed, compact = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (status.isNotEmpty()) {
                        Text(status, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!done) {
                    Spacer(Modifier.width(8.dp))
                    action()
                }
            }
            progress?.invoke()
        }
    }
}

@Composable
private fun ReadyChip(text: String, index: Int) {
    val still = animationsOff()
    val pop = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (pop.value < 1f) { delay(420L + index * 90L); pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
    }
    Surface(
        shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** The current step's dot stretches into a pill. */
@Composable
private fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.setup_step_count, current + 1, count)
    Row(modifier.semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 28.dp else 8.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "dot width")
            val color by animateColorAsState(
                if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, label = "dot color")
            Box(Modifier.height(8.dp).width(width).background(color, CircleShape))
        }
    }
}

/** Soft expressive shapes drift behind every page and slide a little as setup moves forward. */
@Composable
private fun ShapeField(progress: Float) {
    val still = animationsOff()
    val scheme = MaterialTheme.colorScheme
    val shift by animateFloatAsState(progress, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessVeryLow), label = "field shift")
    val turn = if (still) 0f else rememberInfiniteTransition(label = "field").animateFloat(
        0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "field turn").value
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        data class Blob(val shape: RoundedPolygon, val x: Float, val y: Float, val size: Dp, val color: Color, val speed: Float)
        val blobs = listOf(
            Blob(MaterialShapes.Cookie9Sided, 0.78f, -0.06f, 220.dp, scheme.primaryContainer.copy(alpha = 0.45f), 1f),
            Blob(MaterialShapes.Flower, -0.18f, 0.38f, 180.dp, scheme.tertiaryContainer.copy(alpha = 0.35f), -0.7f),
            Blob(MaterialShapes.SoftBurst, 0.62f, 0.82f, 200.dp, scheme.secondaryContainer.copy(alpha = 0.4f), 0.5f),
        )
        blobs.forEachIndexed { i, blob ->
            val drift = (if (i % 2 == 0) -1f else 1f) * shift * 48f
            Box(
                Modifier
                    .offset(x = w * blob.x - blob.size / 2 + drift.dp, y = h * blob.y - blob.size / 2)
                    .size(blob.size)
                    .graphicsLayer { rotationZ = turn * blob.speed }
                    .background(blob.color, blob.shape.toShape()),
            )
        }
    }
}
