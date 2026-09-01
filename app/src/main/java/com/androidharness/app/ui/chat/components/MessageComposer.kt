package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.chat.GroqRecordState
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Message composer: a flat hairline-outlined pill.
 *
 * Supports native speech recognition and Groq Whisper audio recording
 * with hold-to-talk, swipe up to lock, and slide left to cancel gestures.
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
    voiceEngine: String = AppSettings.VOICE_ENGINE_INBUILT,
    groqRecordState: GroqRecordState = GroqRecordState.IDLE,
    rmsDb: Float = 0f,
    recordingDurationMs: Long = 0L,
    onToggleInbuiltVoice: () -> Unit = {},
    isInbuiltListening: Boolean = false,
    onStartGroqRecord: (locked: Boolean) -> Unit = {},
    onLockGroqRecord: () -> Unit = {},
    onCancelGroqRecord: () -> Unit = {},
    onStopAndTranscribeGroq: () -> Unit = {},
    hasAttachments: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val fxSpec = defaultEffectsSpec<Float>()
    val haptic = LocalHapticFeedback.current
    val isGroqActive = groqRecordState != GroqRecordState.IDLE
    val isGroqLocked = groqRecordState == GroqRecordState.LOCKED
    val isGroqHolding = groqRecordState == GroqRecordState.HOLDING
    val isGroqTranscribing = groqRecordState == GroqRecordState.TRANSCRIBING

    val hasText = value.text.isNotBlank()
    val canSend = hasText || !attachedSkill.isNullOrBlank() || hasAttachments
    val showQueueSend = busy && canSend
    val showStopOnly = busy && !canSend

    val actionColor by animateColorAsState(
        targetValue = when {
            showStopOnly -> scheme.error
            canSend || isGroqLocked -> scheme.primary
            else -> scheme.surfaceContainerHighest
        },
        animationSpec = fastEffectsSpec(),
        label = "composer action color",
    )
    val actionContentColor = when {
        showStopOnly -> scheme.onError
        canSend || isGroqLocked -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    val actionScale by animateFloatAsState(
        targetValue = if (busy || canSend || isGroqLocked) 1f else 0.94f,
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
                if (isGroqActive) {
                    // -------------------------------------------------------
                    // Active Groq Whisper Recording / Transcribing Bar
                    // -------------------------------------------------------
                    if (isGroqLocked) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCancelGroqRecord()
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Discard recording",
                                tint = scheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else if (isGroqTranscribing) {
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (isGroqTranscribing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(horizontal = 4.dp),
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                    color = scheme.primary,
                                )
                                Text(
                                    "Transcribing audio with Groq Whisper…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                PulsingRecordingDot()
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    formatDuration(recordingDurationMs),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = scheme.onSurface,
                                )
                                Spacer(Modifier.width(12.dp))
                                WaveformVisualizer(
                                    rms = rmsDb,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(24.dp),
                                    tint = scheme.primary,
                                )
                                if (isGroqHolding) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Slide \u2190 cancel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    if (isGroqLocked) {
                        Surface(
                            shape = CircleShape,
                            color = scheme.primary,
                            contentColor = scheme.onPrimary,
                            modifier = Modifier.size(40.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onStopAndTranscribeGroq()
                                },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Transcribe recording",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    } else if (isGroqHolding) {
                        // While holding, render the pulsating mic button
                        HoldMicAction(
                            rms = rmsDb,
                            onRelease = onStopAndTranscribeGroq,
                            onCancel = onCancelGroqRecord,
                            onLock = onLockGroqRecord,
                        )
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }
                } else {
                    // -------------------------------------------------------
                    // Standard Composer (Idle / Text Entry / Inbuilt Voice)
                    // -------------------------------------------------------
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
                                            isInbuiltListening -> "Listening…"
                                            !attachedSkill.isNullOrBlank() -> "Add a note, or send…"
                                            busy -> "Queue a message, or stop…"
                                            else -> "Message your agent…"
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isInbuiltListening) scheme.primary else scheme.onSurfaceVariant,
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

                    // Voice Mic Button
                    if (voiceEngine == AppSettings.VOICE_ENGINE_GROQ) {
                        GroqMicTrigger(
                            onStartHold = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStartGroqRecord(false)
                            },
                            onTapToggle = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStartGroqRecord(true)
                            },
                        )
                    } else {
                        IconButton(
                            onClick = onToggleInbuiltVoice,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = if (isInbuiltListening) "Stop voice input" else "Voice input",
                                tint = if (isInbuiltListening) scheme.primary else scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
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
}

// ---------------------------------------------------------------------------
// Subcomponents & Gestures
// ---------------------------------------------------------------------------

@Composable
private fun GroqMicTrigger(
    onStartHold: () -> Unit,
    onTapToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val released = tryAwaitRelease()
                        if (!released) {
                            // Cancelled
                        }
                    },
                    onTap = { onTapToggle() },
                    onLongPress = { onStartHold() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = "Voice input",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HoldMicAction(
    rms: Float,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var totalOffsetX by remember { mutableFloatStateOf(0f) }
    var totalOffsetY by remember { mutableFloatStateOf(0f) }

    val scale by animateFloatAsState(
        targetValue = 1f + (rms * 0.35f),
        animationSpec = fastEffectsSpec(),
        label = "mic pulse",
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .offset { IntOffset(totalOffsetX.roundToInt(), totalOffsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        if (totalOffsetX < -180f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCancel()
                        } else if (totalOffsetY < -180f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLock()
                        } else {
                            onRelease()
                        }
                        totalOffsetX = 0f
                        totalOffsetY = 0f
                    },
                    onDragCancel = {
                        onCancel()
                        totalOffsetX = 0f
                        totalOffsetY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalOffsetX += dragAmount.x
                        totalOffsetY += dragAmount.y
                        if (totalOffsetX < -180f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCancel()
                        } else if (totalOffsetY < -180f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLock()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.primary,
            modifier = Modifier
                .size(38.dp)
                .scale(scale),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Release to send",
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PulsingRecordingDot() {
    val transition = rememberInfiniteTransition(label = "recording dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot alpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(0xFFE53935).copy(alpha = alpha)),
    )
}

@Composable
private fun WaveformVisualizer(
    rms: Float,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "wave idle")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier = modifier) {
        val barCount = 18
        val spacing = size.width / barCount
        val barWidth = (spacing * 0.45f).coerceIn(2.dp.toPx(), 4.dp.toPx())
        val centerY = size.height / 2f

        for (i in 0 until barCount) {
            val wave = (sin(phase + i * 0.5) + 1f) / 2f
            val dynamicHeight = (4.dp.toPx() + (rms * 16.dp.toPx()) + (wave.toFloat() * 6.dp.toPx()))
                .coerceIn(3.dp.toPx(), size.height)

            val x = i * spacing + (spacing - barWidth) / 2f
            val top = centerY - dynamicHeight / 2f

            drawRoundRect(
                color = tint.copy(alpha = (0.4f + rms * 0.6f).coerceIn(0.4f, 1f)),
                topLeft = Offset(x, top),
                size = Size(barWidth, dynamicHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = (durationMs / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
