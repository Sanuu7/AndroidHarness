package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.TodoItem
import com.androidharness.app.ui.chat.ChatUiState
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastSpatialSpec

/**
 * Slim live-activity strip above the composer: one small gently-pulsing accent
 * dot and a single muted line of text. No scale wobble, no colored container.
 */
@Composable
internal fun AgentStatusBar(action: String?, busy: Boolean) {
    AnimatedVisibility(
        visible = busy && action != null,
        enter = fadeIn(tween(150)) + expandVertically(animationSpec = fastSpatialSpec()),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = fastSpatialSpec()),
    ) {
        val scheme = MaterialTheme.colorScheme
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .padding(horizontal = 18.dp, vertical = 7.dp),
        ) {
            StatusDot()
            Spacer(Modifier.width(10.dp))
            Text(
                action ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusDot() {
    val transition = rememberInfiniteTransition(label = "status dot")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "status dot alpha",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** Collapsible task checklist. Flat hairline card; status via icon color only. */
@Composable
internal fun TodoCard(todos: List<TodoItem>) {
    var expanded by remember { mutableStateOf(false) }
    val done = todos.count { it.status == TodoItem.Status.COMPLETED }
    val active = todos.firstOrNull { it.status == TodoItem.Status.IN_PROGRESS }
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    Surface(
        color = scheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Tasks · $done/${todos.size}" + (active?.let { " — ${it.content}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    todos.forEach { todo ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            Icon(
                                when (todo.status) {
                                    TodoItem.Status.COMPLETED -> Icons.Filled.CheckCircle
                                    TodoItem.Status.IN_PROGRESS -> Icons.Outlined.PlayArrow
                                    TodoItem.Status.PENDING -> Icons.Outlined.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = when (todo.status) {
                                    TodoItem.Status.COMPLETED -> success
                                    TodoItem.Status.IN_PROGRESS -> scheme.primary
                                    TodoItem.Status.PENDING -> scheme.outline
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                todo.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (todo.status == TodoItem.Status.COMPLETED) scheme.onSurfaceVariant
                                else scheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Send when done" queued-message pill; tap anywhere to cancel. */
@Composable
internal fun QueuedMessageChip(
    text: String,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            DotLoading(dotSize = 3.dp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "Queued: ${text.take(60)}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel queue", modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** Removable image-attachment chips above the composer. */
@Composable
internal fun AttachmentChips(
    attachments: List<String>,
    onRemove: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEachIndexed { idx, name ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                onClick = { onRemove(idx) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(name.take(20), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

/** The "/" command suggestions that appear above the composer while typing. */
@Composable
internal fun SlashSuggestions(
    state: ChatUiState,
    expanded: Boolean,
    onPick: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = expanded && !state.busy,
        enter = fadeIn(tween(150)) + expandVertically(animationSpec = tween(200)),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = tween(150)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("/clear", "/compact", "/cost", "/init").forEach { cmd ->
                Surface(
                    color = scheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                    onClick = { onPick(cmd) },
                ) {
                    Text(
                        cmd,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            state.snippets.take(5).forEach { snippet ->
                Surface(
                    color = scheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
                    onClick = { onPick("/${snippet.name}") },
                ) {
                    Text(
                        "/${snippet.name}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
