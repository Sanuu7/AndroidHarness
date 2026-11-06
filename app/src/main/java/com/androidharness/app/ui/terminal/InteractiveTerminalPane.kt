package com.androidharness.app.ui.terminal

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.terminal.InteractiveTerminalManager
import com.androidharness.app.terminal.KeyModifiers
import com.androidharness.app.terminal.TermKey
import com.androidharness.app.terminal.TerminalSession
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Sticky modifier on the extra-keys row: off, on for the next key, or locked on. */
enum class Latch { OFF, ONCE, LOCKED }

@Stable
class TerminalKeyState {
    var ctrl by mutableStateOf(Latch.OFF)
    var alt by mutableStateOf(Latch.OFF)
    var showFunctionKeys by mutableStateOf(false)

    val modifiers: KeyModifiers get() = KeyModifiers(ctrl = ctrl != Latch.OFF, alt = alt != Latch.OFF)

    /** One-shot modifiers turn off after a key; locked ones stay. */
    fun consume() {
        if (ctrl == Latch.ONCE) ctrl = Latch.OFF
        if (alt == Latch.ONCE) alt = Latch.OFF
    }

    companion object {
        fun next(latch: Latch) = when (latch) {
            Latch.OFF -> Latch.ONCE
            Latch.ONCE -> Latch.LOCKED
            Latch.LOCKED -> Latch.OFF
        }
    }
}

/** Handle the screen uses to reach the view for header actions. */
@Stable
class TerminalViewHandle {
    internal var view: TerminalCanvasView? = null
    fun showKeyboard() { view?.showKeyboard() }
}

@Composable
fun rememberTerminalColors(): TerminalColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(scheme, dark) {
        val background = scheme.surfaceContainerLowest.toArgb()
        val ansi = (if (dark) DARK_ANSI else LIGHT_ANSI).map { (0xFF shl 24) or it }.toIntArray()
        TerminalColors(
            background = background,
            foreground = scheme.onSurface.toArgb(),
            cursor = scheme.primary.toArgb(),
            cursorText = scheme.onPrimary.toArgb(),
            ansi = ansi,
        )
    }
}

private val DARK_ANSI = intArrayOf(
    0x45434A, 0xF28B82, 0x81C995, 0xFDD663, 0x8AB4F8, 0xD7AEFB, 0x78D9EC, 0xDADCE0,
    0x9AA0A6, 0xF6AEA9, 0xA8DAB5, 0xFDE293, 0xAECBFA, 0xE9D2FD, 0xA1E4F2, 0xFFFFFF,
)
private val LIGHT_ANSI = intArrayOf(
    0x202124, 0xC5221F, 0x188038, 0x9A6700, 0x1A73E8, 0x9334E6, 0x007B83, 0x6E7176,
    0x80868B, 0xD93025, 0x1E8E3E, 0xB06000, 0x4285F4, 0xA142F4, 0x129EAF, 0x3C4043,
)

/**
 * The interactive terminal: the PTY session's screen, a start/exit overlay and
 * the extra-keys row. The shell starts once the view knows its grid size, so
 * it begins with the right number of rows and columns.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InteractiveTerminalPane(
    manager: InteractiveTerminalManager,
    sessionKey: String,
    cwd: File,
    handle: TerminalViewHandle,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessions by manager.sessions.collectAsStateWithLifecycle()
    val session = sessions[sessionKey]
    val exitCode = session?.exitCode?.collectAsStateWithLifecycle()?.value
    val fontSize by manager.fontSizeSp.collectAsStateWithLifecycle()
    val colors = rememberTerminalColors()
    val keys = remember { TerminalKeyState() }
    var startError by remember(sessionKey) { mutableStateOf<String?>(null) }
    var lastGrid by remember { mutableStateOf<IntArray?>(null) }
    val scheme = MaterialTheme.colorScheme

    val scope = rememberCoroutineScope()
    var starting by remember(sessionKey) { mutableStateOf(false) }
    fun startIfNeeded(rows: Int, cols: Int) {
        if (starting || manager.session(sessionKey) != null || startError != null) return
        starting = true
        // Writing the shell's rc file and refreshing the shims touches storage, so stay off the main thread.
        scope.launch(Dispatchers.IO) {
            runCatching { manager.start(sessionKey, cwd, rows, cols) }
                .onFailure { startError = it.message ?: it.javaClass.simpleName }
            starting = false
        }
    }

    Column(modifier.fillMaxSize()) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = Color(colors.background),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        TerminalCanvasView(context).apply {
                            handle.view = this
                            latchedModifiers = { keys.modifiers }
                            onModifiersUsed = { keys.consume() }
                            onPasteRequest = onPaste
                            onCopyRequest = onCopy
                            onFontSizeChange = { manager.fontSizeSp.value = it }
                        }
                    },
                    update = { view ->
                        handle.view = view
                        view.colors = colors
                        view.fontSizeSp = fontSize
                        view.onPasteRequest = onPaste
                        view.onCopyRequest = onCopy
                        view.onGridSize = { rows, cols, w, h ->
                            lastGrid = intArrayOf(rows, cols, w, h)
                            val current = manager.session(sessionKey)
                            if (current == null) startIfNeeded(rows, cols) else current.resize(rows, cols, w, h)
                        }
                        view.attach(session)
                    },
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
                when {
                    startError != null -> TerminalNotice(
                        message = stringResource(R.string.terminal_start_failed, startError.orEmpty()),
                        action = stringResource(R.string.terminal_try_again),
                        onAction = {
                            startError = null
                            lastGrid?.let { startIfNeeded(it[0], it[1]) }
                        },
                    )
                    session == null -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LoadingIndicator(Modifier.size(48.dp), color = scheme.primary)
                        Text(stringResource(R.string.terminal_starting), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    exitCode != null -> TerminalNotice(
                        message = stringResource(R.string.terminal_exited, exitCode),
                        action = stringResource(R.string.terminal_restart),
                        onAction = {
                            manager.close(sessionKey)
                            lastGrid?.let { startIfNeeded(it[0], it[1]) }
                        },
                    )
                }
            }
        }
        if (session != null && session.shellName == "sh") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                Text(stringResource(R.string.terminal_no_linux_hint), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        ExtraKeysBar(keys, onKey = { handle.view?.pressKey(it) }, onChar = { handle.view?.pressChar(it) })
    }

    DisposableEffect(Unit) {
        onDispose { handle.view = null }
    }
    LaunchedEffect(session) {
        // Give the hardware keyboard a target as soon as the shell is there.
        if (session != null) handle.view?.requestFocus()
    }
    LaunchedEffect(session == null, lastGrid) {
        // A restart from the menu removes the session; start the next shell at
        // the size the view already measured instead of waiting for a layout pass.
        val grid = lastGrid
        if (session == null && grid != null) startIfNeeded(grid[0], grid[1])
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TerminalNotice(message: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 520.dp).fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = scheme.tertiaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp), tint = scheme.onTertiaryContainer)
                }
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * Keys a phone keyboard lacks: Esc, Tab, sticky Ctrl and Alt, arrows and
 * paging, Ctrl+C, and a function-key row behind Fn. Arrow and paging keys
 * repeat while held.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExtraKeysBar(state: TerminalKeyState, onKey: (TermKey) -> Unit, onChar: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimatedVisibility(
                visible = state.showFunctionKeys,
                enter = expandVertically(expressiveSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(expressiveSpatialSpec()) + fadeOut(),
            ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val fnKeys = listOf(TermKey.F1, TermKey.F2, TermKey.F3, TermKey.F4, TermKey.F5, TermKey.F6,
                        TermKey.F7, TermKey.F8, TermKey.F9, TermKey.F10, TermKey.F11, TermKey.F12)
                    fnKeys.forEachIndexed { i, key -> KeyCap("F${i + 1}", Modifier.width(52.dp)) { onKey(key) } }
                    KeyCap(stringResource(R.string.terminal_key_ins), Modifier.width(52.dp)) { onKey(TermKey.INSERT) }
                    KeyCap(stringResource(R.string.terminal_key_del), Modifier.width(52.dp), repeat = true) { onKey(TermKey.DELETE) }
                    listOf("|", "~", "`", "\\", "{", "}", "[", "]", "<", ">", "&", "$", "*").forEach { symbol ->
                        KeyCap(symbol, Modifier.width(44.dp)) { onChar(symbol.codePointAt(0)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyCap(stringResource(R.string.terminal_key_esc)) { onKey(TermKey.ESCAPE) }
                ModifierKey(stringResource(R.string.terminal_key_ctrl), state.ctrl) { state.ctrl = TerminalKeyState.next(state.ctrl) }
                ModifierKey(stringResource(R.string.terminal_key_alt), state.alt) { state.alt = TerminalKeyState.next(state.alt) }
                KeyCap(stringResource(R.string.terminal_key_tab)) { onKey(TermKey.TAB) }
                KeyCap(stringResource(R.string.terminal_key_home)) { onKey(TermKey.HOME) }
                KeyCap(null, icon = Icons.Filled.KeyboardArrowUp, description = stringResource(R.string.terminal_key_up), repeat = true) { onKey(TermKey.UP) }
                KeyCap(stringResource(R.string.terminal_key_end)) { onKey(TermKey.END) }
                KeyCap(stringResource(R.string.terminal_key_pgup), repeat = true) { onKey(TermKey.PAGE_UP) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModifierKey(stringResource(R.string.terminal_key_fn), if (state.showFunctionKeys) Latch.ONCE else Latch.OFF) {
                    state.showFunctionKeys = !state.showFunctionKeys
                }
                KeyCap("^C", description = stringResource(R.string.terminal_key_interrupt), emphasized = true) { onChar(3) }
                KeyCap("/") { onChar('/'.code) }
                KeyCap("-") { onChar('-'.code) }
                KeyCap(null, icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, description = stringResource(R.string.terminal_key_left), repeat = true) { onKey(TermKey.LEFT) }
                KeyCap(null, icon = Icons.Filled.KeyboardArrowDown, description = stringResource(R.string.terminal_key_down), repeat = true) { onKey(TermKey.DOWN) }
                KeyCap(null, icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, description = stringResource(R.string.terminal_key_right), repeat = true) { onKey(TermKey.RIGHT) }
                KeyCap(stringResource(R.string.terminal_key_pgdn), repeat = true) { onKey(TermKey.PAGE_DOWN) }
            }
        }
    }
}

/** A key on the extra-keys row with the expressive press morph. Repeats while held when [repeat] is set. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.KeyCap(
    label: String?,
    modifier: Modifier = Modifier.weight(1f),
    icon: ImageVector? = null,
    description: String? = null,
    repeat: Boolean = false,
    emphasized: Boolean = false,
    onPress: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 6.dp else 14.dp, expressiveSpatialSpec(), label = "keyCorner")
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val container = when {
        pressed -> scheme.primary
        emphasized -> scheme.tertiaryContainer
        else -> scheme.surfaceContainerHighest
    }
    val content = when {
        pressed -> scheme.onPrimary
        emphasized -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = content,
        modifier = modifier
            .height(42.dp)
            .semantics {
                role = Role.Button
                (description ?: label)?.let { contentDescription = it }
            }
            .indication(interactions, ripple())
            .pointerInput(onPress, repeat) {
                detectTapGestures(onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interactions.emit(press)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPress()
                    val repeater = if (repeat) scope.launch {
                        delay(420)
                        while (true) {
                            onPress()
                            delay(50)
                        }
                    } else null
                    val released = tryAwaitRelease()
                    repeater?.cancel()
                    interactions.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                })
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(22.dp))
            } else if (label != null) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontFamily = HarnessMono, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Ctrl, Alt or Fn as an expressive toggle: one tap for the next key, a second to lock, a third to release. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.ModifierKey(label: String, latch: Latch, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    val description = when (latch) {
        Latch.OFF -> label
        Latch.ONCE -> stringResource(R.string.terminal_modifier_once, label)
        Latch.LOCKED -> stringResource(R.string.terminal_modifier_locked, label)
    }
    ToggleButton(
        checked = latch != Latch.OFF,
        onCheckedChange = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onToggle()
        },
        modifier = Modifier.weight(1f).height(42.dp).semantics { contentDescription = description },
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            checkedContainerColor = if (latch == Latch.LOCKED) scheme.tertiary else scheme.primary,
            checkedContentColor = if (latch == Latch.LOCKED) scheme.onTertiary else scheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        if (latch == Latch.LOCKED) Icon(Icons.Filled.Lock, null, Modifier.size(12.dp).padding(end = 2.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}
