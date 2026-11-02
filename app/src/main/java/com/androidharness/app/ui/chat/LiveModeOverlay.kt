package com.androidharness.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.data.LiveModeAccess
import com.androidharness.app.ui.chat.components.LiveGlyph

/**
 * Full-screen Live Mode. There is no send button: the microphone reopens by
 * itself after every reply. The orb is the only control most people need,
 * tapping it stops a reply or pauses listening.
 */
@Composable
internal fun LiveModeOverlay(session: LiveModeSession, onClose: () -> Unit) {
    val ui by session.state.collectAsStateWithLifecycle()
    val listener = session.listener
    // The microphone may not stay open in the background without a foreground service.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.pause() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                LiveHeader(ui, onClose)
                LiveConversation(ui, Modifier.weight(1f))
                LiveStatus(ui, listener)
                LiveControls(ui, listener, session)
            }
        }
    }
}

@Composable
private fun LiveHeader(ui: LiveUi, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.live_end)) }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleMedium)
                ExperimentalTag()
            }
            if (ui.model.isNotBlank()) Text(ui.model, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(stringResource(accessLabel(ui.access)), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

@Composable
internal fun ExperimentalTag() {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(stringResource(R.string.live_experimental), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

internal fun accessLabel(access: LiveModeAccess): Int = when (access) {
    LiveModeAccess.TALK -> R.string.live_access_talk_short
    LiveModeAccess.READ -> R.string.live_access_read_short
    LiveModeAccess.START_TASKS -> R.string.live_access_tasks_short
}

@Composable
private fun LiveConversation(ui: LiveUi, modifier: Modifier) {
    val list = rememberLazyListState()
    val count = ui.exchanges.size + if (ui.asking != null) 1 else 0
    LaunchedEffect(count, ui.streaming.length / 80) { if (count > 0) list.animateScrollToItem(count - 1) }
    if (count == 0) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.live_empty_hint), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp))
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(ui.exchanges) { exchange -> LiveExchangeItem(exchange.user, exchange.reply) }
        ui.asking?.let { asking -> item { LiveExchangeItem(asking, ui.streaming, pending = true) } }
    }
}

@Composable
private fun LiveExchangeItem(user: String, reply: String, pending: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(Modifier.align(Alignment.End).widthIn(max = 320.dp), shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(user, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        if (reply.isNotBlank()) Text(reply, style = MaterialTheme.typography.bodyLarge)
        else if (pending) Text("…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LiveStatus(ui: LiveUi, listener: LiveListener) {
    val error = ui.error ?: listener.error
    val text = when {
        error != null -> error
        ui.phase == LivePhase.LISTENING && listener.partial.isNotBlank() -> listener.partial
        else -> stringResource(statusLabel(ui, listener.hearing))
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        ui.notice?.let {
            Spacer(Modifier.size(4.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
    }
}

private fun statusLabel(ui: LiveUi, hearing: LiveHearing): Int = when (ui.phase) {
    LivePhase.STARTING -> R.string.live_status_starting
    LivePhase.THINKING -> if (ui.tool != null) R.string.live_status_reading else R.string.live_status_thinking
    LivePhase.SPEAKING -> R.string.live_status_speaking
    LivePhase.PAUSED -> R.string.live_status_paused
    LivePhase.LISTENING -> when (hearing) {
        LiveHearing.HEARING -> R.string.live_status_hearing
        LiveHearing.TRANSCRIBING -> R.string.live_status_transcribing
        else -> R.string.live_status_listening
    }
}

@Composable
private fun LiveControls(ui: LiveUi, listener: LiveListener, session: LiveModeSession) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        val paused = ui.phase == LivePhase.PAUSED
        FilledTonalIconButton(onClick = { if (paused) session.resume() else session.pause() }, modifier = Modifier.size(56.dp)) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                stringResource(if (paused) R.string.live_resume else R.string.live_pause))
        }
        LiveOrb(ui, listener, onTap = session::interrupt)
        if (ui.access == LiveModeAccess.START_TASKS) {
            FilledTonalIconButton(onClick = session::sendPlan, enabled = ui.exchanges.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                Icon(Icons.Outlined.Send, stringResource(R.string.live_send_plan))
            }
        } else Spacer(Modifier.size(56.dp))
    }
}

@Composable
private fun LiveOrb(ui: LiveUi, listener: LiveListener, onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(when (ui.phase) {
        LivePhase.LISTENING -> scheme.primary
        LivePhase.THINKING -> scheme.tertiary
        LivePhase.SPEAKING -> scheme.secondary
        else -> scheme.outline
    }, label = "orbColor")
    val level by animateFloatAsState(if (ui.phase == LivePhase.LISTENING) listener.level else 0f, tween(90), label = "orbLevel")
    val breathing = rememberInfiniteTransition(label = "orbBreath")
    val breath by breathing.animateFloat(0.96f, 1.04f,
        infiniteRepeatable(tween(if (ui.phase == LivePhase.THINKING) 520 else 1_400), RepeatMode.Reverse), label = "breath")
    val busy = ui.phase == LivePhase.THINKING || ui.phase == LivePhase.SPEAKING
    val description = stringResource(when (ui.phase) {
        LivePhase.LISTENING -> R.string.live_pause
        LivePhase.PAUSED -> R.string.live_resume
        else -> R.string.live_interrupt
    })
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(132.dp).scale(0.72f + level * 0.28f).clip(CircleShape).background(color.copy(alpha = 0.18f)))
        Box(
            Modifier.size(92.dp).scale(if (busy) breath else 1f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.7f))))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { LiveGlyph(color = scheme.surface, size = 34.dp) }
    }
}
