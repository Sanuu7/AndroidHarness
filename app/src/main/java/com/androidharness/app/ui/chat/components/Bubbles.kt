package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.core.ImageRef
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.common.DotLoading

/**
 * User messages are neutral gray bubbles (not the old accent-filled ones) so the
 * conversation's color budget stays spent on status, not chrome. Assistant text
 * is un-bubbled markdown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserBubble(
    text: String,
    images: List<ImageRef> = emptyList(),
    fileChips: List<com.androidharness.app.ui.chat.FileAttachments.Block> = emptyList(),
    onLongPress: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp).let { m ->
                if (onLongPress != null) {
                    m.combinedClickable(onClick = {}, onLongClick = onLongPress)
                } else m
            },
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                images.forEach { img ->
                    Text(
                        "📎 ${img.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                fileChips.forEach { chip ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "📄 ${chip.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            chip.sizeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
                if (text.isNotBlank()) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Agent text is directly selectable: hold and drag to mark a range, copy via
 * the system toolbar, no dialogs between the user and the content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantText(
    text: String,
    streaming: Boolean = false,
    onOpenUrl: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (text.isBlank() && streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotLoading()
                Spacer(Modifier.width(10.dp))
                Text(
                    "Working on it…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    SelectionContainer { MarkdownText(text, streaming = streaming, onOpenUrl = onOpenUrl) }
                }
                if (streaming) {
                    Spacer(Modifier.width(4.dp))
                    BlinkingCursor()
                }
            }
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(480), repeatMode = RepeatMode.Reverse),
        label = "cursor alpha",
    )
    Text(
        "▏",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
internal fun RewindButton(
    hasCheckpoints: Boolean,
    onRewind: () -> Unit,
) {
    if (hasCheckpoints) {
        IconButton(onClick = onRewind, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Outlined.History,
                contentDescription = "Rewind",
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}