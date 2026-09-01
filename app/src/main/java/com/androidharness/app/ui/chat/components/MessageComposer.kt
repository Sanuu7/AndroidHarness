package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec

/**
 * Message composer: a flat hairline-outlined pill.
 *
 * While the agent is idle the circular action is send. While it is running
 * with an empty field the circle is stop. Type while it is running and send
 * stays send (queues for the next iteration) with a smaller stop next to it.
 */
@Composable
internal fun MessageComposer(
    busy: Boolean,
    value: TextFieldValue,
    attachedSkill: String?,
    onValueChange: (TextFieldValue) -> Unit,
    onClearSkill: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    onToggleVoice: () -> Unit = {},
    isListening: Boolean = false,
    hasAttachments: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val fxSpec = defaultEffectsSpec<Float>()
    val hasText = value.text.isNotBlank()
    val canSend = hasText || !attachedSkill.isNullOrBlank() || hasAttachments
    val showQueueSend = busy && canSend
    val showStopOnly = busy && !canSend
    val actionColor by animateColorAsState(
        targetValue = when {
            showStopOnly -> scheme.error
            canSend -> scheme.primary
            else -> scheme.surfaceContainerHighest
        },
        animationSpec = fastEffectsSpec(),
        label = "composer action color",
    )
    val actionContentColor = when {
        showStopOnly -> scheme.onError
        canSend -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    val actionScale by animateFloatAsState(
        targetValue = if (busy || canSend) 1f else 0.94f,
        animationSpec = fastEffectsSpec(),
        label = "composer action scale",
    )

    Surface(color = scheme.surface) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 2.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            ) {
                // Attach menu: images keep the vision pipeline, every other
                // file type goes through the generic attachment flow.
                var attachMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { attachMenu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = "Attach",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenu,
                        onDismissRequest = { attachMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photo or image") },
                            leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                            onClick = {
                                attachMenu = false
                                onAttachImage()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Any file") },
                            leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                            onClick = {
                                attachMenu = false
                                onAttachFile()
                            },
                        )
                    }
                }
                if (!attachedSkill.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.secondaryContainer,
                        contentColor = scheme.onSecondaryContainer,
                        onClick = onClearSkill,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text(
                                attachedSkill,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove skill",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) {
                                Text(
                                    when {
                                        isListening -> "Listening…"
                                        !attachedSkill.isNullOrBlank() -> "Add a note, or send…"
                                        busy -> "Queue a message, or stop…"
                                        else -> "Message your agent…"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isListening) scheme.primary else scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    maxLines = 5,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onToggleVoice,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = if (isListening) "Stop voice input" else "Voice input",
                        tint = if (isListening) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (showQueueSend) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.errorContainer,
                        contentColor = scheme.onErrorContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            IconButton(
                                onClick = onStop,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = "Stop",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Surface(
                    shape = CircleShape,
                    color = actionColor,
                    contentColor = actionContentColor,
                    modifier = Modifier
                        .size(40.dp)
                        .scale(actionScale),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        IconButton(
                            enabled = busy || canSend,
                            onClick = {
                                when {
                                    showQueueSend || canSend -> onSend()
                                    showStopOnly -> onStop()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            AnimatedContent(
                                targetState = showStopOnly,
                                transitionSpec = {
                                    (fadeIn(fxSpec) + scaleIn(fxSpec, initialScale = 0.7f))
                                        .togetherWith(fadeOut(fxSpec) + scaleOut(fxSpec, targetScale = 0.7f))
                                },
                                label = "composer action",
                            ) { stopOnly ->
                                Icon(
                                    if (stopOnly) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                    contentDescription = when {
                                        stopOnly -> "Stop"
                                        busy -> "Queue message"
                                        else -> "Send"
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
