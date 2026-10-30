package com.androidharness.app.ui.terminal

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import com.androidharness.app.ui.common.localizedOptionLabel
import com.androidharness.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/**
 * Full-screen interactive terminal. The shell process lives in TerminalManager
 * (app-wide scope + keepalive), so it keeps running while the app is minimized.
 */
@Composable
fun TerminalScreen(
    container: AppContainer,
    onBack: () -> Unit,
    sessionId: String? = null,
    projectId: String? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val terminal = container.terminal
    val workspace by remember(sessionId, projectId) { container.workspace.forTools(sessionId, projectId) }.collectAsStateWithLifecycle(initialValue = null)
    val remote = workspace as? com.androidharness.app.workspace.SshFs
    val state by terminal.state.collectAsStateWithLifecycle()
    val accessPrefs by container.deviceAccess.preferences.collectAsStateWithLifecycle()
    val accessScope = rememberCoroutineScope()
    var showAccess by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    var inputValue by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val input = inputValue.text
    fun setInput(text: String) { inputValue = TextFieldValue(text, TextRange(text.length)) }
    val desktopInput = com.androidharness.app.ui.LocalDesktopInput.current
    var history by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(emptyList<String>()) }
    var historyIndex by remember { androidx.compose.runtime.mutableIntStateOf(history.size) }
    var historyDraft by remember { mutableStateOf("") }
    fun submit() {
        if (input.isBlank() || state.busy || state.stopping || workspace == null) return
        history = (history + input).takeLast(100)
        historyIndex = history.size
        terminal.send(input, workspace, sessionId, projectId)
        setInput("")
        historyDraft = ""
    }
    val active = com.androidharness.app.ui.LocalWorkspacePaneActive.current
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(workspace?.displayPath, state.busy, active) {
        if (!active) return@LaunchedEffect
        workspace?.let { terminal.useWorkspace(it) }
        if (workspace != null) terminal.ensureStarted()
    }
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.size - 1)
    }

    DisposableEffect(Unit) {
        onDispose {
            // The shell keeps running in the background (by design). The user
            // closes it explicitly with the trash icon.
        }
    }

    if (showAccess) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showAccess = false },
        title = { Text(uiContext.getString(R.string.settings_root_access_mode)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.androidharness.app.ui.settings.AccessModeChoices(accessPrefs.mode) { mode ->
                accessScope.launch { container.deviceAccess.store.setMode(mode) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiContext.getString(R.string.settings_root_auto_fallback), Modifier.weight(1f))
                Switch(accessPrefs.mode == com.androidharness.app.data.env.AccessMode.AUTO || accessPrefs.autoFallback, enabled = accessPrefs.mode != com.androidharness.app.data.env.AccessMode.AUTO, onCheckedChange = { enabled -> accessScope.launch { container.deviceAccess.store.setFallback(enabled) } })
            }
            Text(uiContext.getString(R.string.ui_auto_root_shizuku_normal_both_shizuku_for), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { showAccess = false }) { Text(uiContext.getString(R.string.settings_action_done)) } },
    )

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = uiContext.getString(R.string.ui_terminal),
                subtitle = if (remote != null) uiContext.getString(R.string.ui_ssh_workspace) else uiContext.getString(R.string.ui_preferred, state.backendLabel, uiContext.localizedOptionLabel(accessPrefs.mode.label)),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showAccess = true }, enabled = remote == null) {
                        Icon(Icons.Outlined.Shield, contentDescription = uiContext.getString(R.string.settings_root_access_mode), tint = scheme.primary)
                    }
                    IconButton(onClick = { terminal.clear() }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = uiContext.getString(R.string.ui_clear_output),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        com.androidharness.app.ui.common.LeftToRightContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.lines) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = HarnessMono,
                            color = if (line.startsWith("\$ ") || line.startsWith("# ")) {
                                scheme.primary
                            } else {
                                scheme.onSurface
                            },
                            softWrap = false,
                        )
                    }
                }

                Text(
                    state.cwd,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = HarnessMono,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    ) {
                        BasicTextField(
                            value = inputValue,
                            onValueChange = { next ->
                                if (next.text != inputValue.text) { historyIndex = history.size; historyDraft = next.text }
                                inputValue = next
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onPreviewKeyEvent { event ->
                                    if (!desktopInput) false else when (event.key) {
                                        Key.C -> if (event.isCtrlPressed && state.busy) {
                                            if (event.type == KeyEventType.KeyDown) terminal.interrupt()
                                            true
                                        } else false
                                        Key.Enter, Key.NumPadEnter -> {
                                            if (event.type == KeyEventType.KeyUp) submit()
                                            true
                                        }
                                        Key.DirectionUp -> if (history.isNotEmpty()) {
                                            if (event.type == KeyEventType.KeyDown) {
                                                historyIndex = (historyIndex - 1).coerceAtLeast(0)
                                                setInput(history[historyIndex])
                                            }
                                            true
                                        } else false
                                        Key.DirectionDown -> if (history.isNotEmpty()) {
                                            if (event.type == KeyEventType.KeyDown) {
                                                historyIndex = (historyIndex + 1).coerceAtMost(history.size)
                                                setInput(if (historyIndex == history.size) historyDraft else history[historyIndex])
                                            }
                                            true
                                        } else false
                                        else -> false
                                    }
                                }
                                .padding(vertical = 8.dp),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (input.isEmpty()) {
                                        Text(
                                            uiContext.getString(R.string.ui_command),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = HarnessMono,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = scheme.onSurface,
                                fontFamily = HarnessMono,
                            ),
                            cursorBrush = SolidColor(scheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { if (state.busy) terminal.interrupt() else submit() },
                            enabled = !state.stopping && (state.busy || input.isNotBlank()),
                        ) {
                            Icon(
                                if (state.busy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                contentDescription = uiContext.getString(if (state.busy) R.string.settings_action_stop else R.string.ui_run),
                                modifier = Modifier.size(18.dp),
                                tint = if (state.busy || input.isNotBlank()) scheme.primary
                                else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
