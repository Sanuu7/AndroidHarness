package com.androidharness.app.ui.chat.components

import androidx.compose.runtime.CompositionLocalProvider

import com.androidharness.app.R
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import com.androidharness.app.ui.common.expressivePressFeedback
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.LocalPortProbe
import com.androidharness.app.core.WebPreviewTarget
import com.androidharness.app.core.WebResourceExtractor
import com.androidharness.app.core.WebTargetType
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.common.DotLoading
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.common.IconButton

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
    imageDir: java.io.File? = null,
    onOpenImage: ((ImageRef) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val press = remember { MutableInteractionSource() }
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = minOf(560.dp, maxWidth * 0.88f)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
                shape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp),
                modifier = Modifier.widthIn(max = bubbleMaxWidth).let { m ->
                    if (onLongPress != null) {
                        m.expressivePressFeedback(press).clip(RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                            .combinedClickable(interactionSource = press, indication = ripple(color = scheme.primary),
                                onClick = {}, onLongClick = onLongPress)
                    } else m
                },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                ) {
                    images.forEach { img ->
                        if (imageDir != null) {
                            ImageThumbnail(img, imageDir) { onOpenImage?.invoke(img) }
                        } else {
                            AttachmentChip(
                                name = img.name,
                                detail = uiContext.getString(R.string.ui_image),
                                icon = Icons.Outlined.Image,
                            )
                        }
                    }
                    fileChips.forEach { chip ->
                        AttachmentChip(
                            name = chip.name,
                            detail = chip.sizeLabel,
                            icon = Icons.Outlined.Description,
                        )
                    }
                    if (text.isNotBlank()) {
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AttachmentChip(
    name: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
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
    showPreviewChip: Boolean = false,
    onOpenUrl: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onFollowUp: ((com.androidharness.app.core.response.RichResponse.FollowUp) -> Unit)? = null,
    chatImages: Map<String, com.androidharness.app.core.ImageRef>? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val primaryTarget = remember(text, streaming) {
        if (!streaming && text.isNotBlank()) WebResourceExtractor.findPrimaryPreviewTarget(com.androidharness.app.core.response.RichResponse.markdownText(text)) else null
    }
    var targetLive by remember(text, streaming) { mutableStateOf<Boolean?>(null) }
    if (showPreviewChip && primaryTarget != null && onOpenUrl != null) {
        LaunchedEffect(text) {
            targetLive = LocalPortProbe.isUrlLive(primaryTarget.urlOrPath)
        }
    }

    Column(modifier = modifier) {
        if (text.isBlank() && streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotLoading()
                Spacer(Modifier.width(10.dp))
                Text(
                    uiContext.getString(R.string.ui_working_on_it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // The cursor sits in the transcript's end gutter. Reserving a column only
            // while streaming changes every paragraph's width at completion, which
            // can shrink a long answer enough to clamp a reader back to the bottom.
            Box(Modifier.fillMaxWidth()) {
                CompositionLocalProvider(LocalChatResponseImages provides (chatImages ?: LocalChatResponseImages.current)) {
                    com.androidharness.app.ui.chat.rich.RichResponseContent(text, streaming, onOpenUrl, onFollowUp)
                }
                if (streaming) BlinkingCursor(Modifier.align(Alignment.TopEnd).offset(x = 12.dp))
            }

            if (showPreviewChip && primaryTarget != null && targetLive == true && onOpenUrl != null) {
                Spacer(Modifier.size(6.dp))
                WebPreviewActionChip(
                    target = primaryTarget,
                    onClick = { onOpenUrl(primaryTarget.urlOrPath) },
                )
            }
        }
    }
}

@Composable
fun WebPreviewActionChip(
    target: WebPreviewTarget,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (target.isLive) statusColors.success.copy(alpha = 0.5f)
            else scheme.outlineVariant.copy(alpha = 0.6f),
        ),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                when (target.type) {
                    WebTargetType.WORKSPACE_HTML -> Icons.Outlined.Description
                    else -> Icons.Outlined.Language
                },
                contentDescription = null,
                tint = if (target.isLive) statusColors.success else scheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (target.isLive) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(statusColors.success, CircleShape),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        target.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                }
                Text(
                    target.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    fontFamily = HarnessMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun BlinkingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(480), repeatMode = RepeatMode.Reverse),
        label = "cursor alpha",
    )
    Text(
        "▏",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = 2.dp)
            .graphicsLayer { alpha = cursorAlpha },
    )
}

@Composable
internal fun RewindButton(
    hasCheckpoints: Boolean,
    onRewind: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (hasCheckpoints) {
        IconButton(onClick = onRewind) {
            Icon(
                Icons.Outlined.History,
                contentDescription = uiContext.getString(R.string.ui_rewind),
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
