package com.androidharness.app.ui.terminal

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.common.AppHeader

/**
 * Full-screen interactive terminal. The shell process lives in TerminalManager
 * (app-wide scope + keepalive), so it keeps running while the app is minimized.
 */
@Composable
fun TerminalScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val terminal = container.terminal
    val state by terminal.state.collectAsStateWithLifecycle()
    val shizukuState by container.shizuku.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) { terminal.ensureStarted() }
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.size - 1)
    }

    DisposableEffect(Unit) {
        onDispose {
            // The shell keeps running in the background (by design). The user
            // closes it explicitly with the trash icon.
        }
    }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = "Terminal",
                subtitle = if (state.privileged) "Shizuku (shell user)" else "App user",
                onBack = onBack,
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Shield,
                            contentDescription = "Privileged mode",
                            tint = if (state.privileged) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                        Switch(
                            checked = state.privileged && shizukuState == com.androidharness.app.data.env.ShizukuState.GRANTED,
                            onCheckedChange = { terminal.setPrivileged(it) },
                            enabled = shizukuState == com.androidharness.app.data.env.ShizukuState.GRANTED,
                        )
                    }
                    IconButton(onClick = { terminal.clear() }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Clear output",
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
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
                        fontFamily = FontFamily.Monospace,
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
                fontFamily = FontFamily.Monospace,
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
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (input.isEmpty()) {
                                    Text(
                                        "command…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = scheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            terminal.send(input)
                            input = ""
                        }),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            terminal.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() && !state.busy,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Run",
                            modifier = Modifier.size(18.dp),
                            tint = if (input.isNotBlank() && !state.busy) scheme.primary
                            else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
