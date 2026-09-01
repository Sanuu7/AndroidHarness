package com.androidharness.app.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.chat.GroqRecordState
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.defaultSpatialSpec
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec
import kotlin.math.abs

/** How far left the finger travels before releasing cancels the take. */
private val CancelDistance = 92.dp

/** How far up the finger travels before the recording keeps itself open. */
private val LockDistance = 68.dp

/**
 * The message composer styled with the Hulia floating layout:
 * - Floating pill input with focus border
 * - Soft-square leading button for attachments & skills
 * - Morphing send/mic action button
 * - LockRail and RecordingStrip animations for Groq Whisper
 * - Inbuilt audio fallback
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
    recordingDurationMs: Long = 0L,
    levels: List<Float> = emptyList(),
    cancelArmed: Boolean = false,
    onCancelArmedChange: (Boolean) -> Unit = {},
    onToggleInbuiltVoice: () -> Unit = {},
    isInbuiltListening: Boolean = false,
    onStartGroqRecord: (locked: Boolean) -> Unit = {},
    onLockGroqRecord: () -> Unit = {},
    onCancelGroqRecord: () -> Unit = {},
    onStopAndTranscribeGroq: () -> Unit = {},
    hasAttachments: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var focused by remember { mutableStateOf(false) }

    val isGroq = voiceEngine == AppSettings.VOICE_ENGINE_GROQ
    val isGroqActive = isGroq && groqRecordState != GroqRecordState.IDLE
    val isGroqLocked = groqRecordState == GroqRecordState.LOCKED
    val isGroqTranscribing = groqRecordState == GroqRecordState.TRANSCRIBING

    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val cancelDistancePx = with(density) { CancelDistance.toPx() }
    val lockDistancePx = with(density) { LockDistance.toPx() }

    val cancelProgress = (-dragX / cancelDistancePx).coerceIn(0f, 1f)
    val lockProgress = (-dragY / lockDistancePx).coerceIn(0f, 1f)

    val canSend = value.text.isNotBlank() || !attachedSkill.isNullOrBlank() || hasAttachments
    val showQueueSend = busy && canSend
    val showStopOnly = busy && !canSend

    val ringAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = defaultEffectsSpec(),
        label = "composerRing",
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            showStopOnly -> MaterialTheme.colorScheme.error
            canSend || isGroqLocked -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = defaultEffectsSpec(),
        label = "sendColor",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            showStopOnly -> MaterialTheme.colorScheme.onError
            canSend || isGroqLocked -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = defaultEffectsSpec(),
        label = "sendIconColor",
    )
    val corner by animateDpAsState(
        targetValue = if (canSend || isGroqLocked || showStopOnly) 26.dp else 17.dp,
        animationSpec = defaultSpatialSpec(),
        label = "sendCorner",
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (canSend || isGroqLocked || showStopOnly) 1f else 0.86f,
        animationSpec = fastSpatialSpec(),
        label = "sendScale",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Leading button: discard recording when locked, otherwise attachments/skills
        if (isGroqActive && isGroqLocked) {
            DiscardRecordingButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCancelGroqRecord()
                },
            )
        } else {
            ComposerLeadingButton(
                onPickImage = onAttachImage,
                onPickFile = onAttachFile,
            )
        }

        if (isGroqActive) {
            if (isGroqTranscribing) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 18.dp),
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Transcribing audio with Groq Whisper…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                RecordingStrip(
                    elapsedMillis = recordingDurationMs.toInt(),
                    levels = levels,
                    cancelArmed = cancelArmed,
                    locked = isGroqLocked,
                    cancelProgress = cancelProgress,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // Floating text pill with soft ring on focus
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.Transparent),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    if (!attachedSkill.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
                                    modifier = Modifier.widthIn(max = 110.dp),
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

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.text.isEmpty()) {
                            Text(
                                text = when {
                                    isInbuiltListening -> "Listening…"
                                    !attachedSkill.isNullOrBlank() -> "Add a note, or send…"
                                    busy -> "Queue message"
                                    else -> "Message your agent…"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isInbuiltListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp, max = 148.dp)
                                .verticalScroll(rememberScrollState())
                                .onFocusChanged { focused = it.isFocused }
                                .padding(vertical = 12.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Default,
                            ),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(
                            width = 1.dp,
                            color = if (ringAlpha > 0f) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f * ringAlpha)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            },
                            shape = RoundedCornerShape(26.dp),
                        ),
                )
            }
        }

        // Action button on the right
        when {
            showQueueSend -> {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .scale(buttonScale)
                        .clip(RoundedCornerShape(corner))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSend()
                        }
                        .semantics { contentDescription = "Queue message" },
                    contentAlignment = Alignment.Center,
                ) {
                    SendGlyph(color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            isGroqActive && isGroqLocked -> {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onStopAndTranscribeGroq()
                        }
                        .semantics { contentDescription = "Transcribe voice message" },
                    contentAlignment = Alignment.Center,
                ) {
                    SendGlyph(color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            showStopOnly -> {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .scale(buttonScale)
                        .clip(RoundedCornerShape(corner))
                        .background(buttonColor)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onStop()
                        }
                        .semantics { contentDescription = "Stop" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Stop",
                        tint = iconColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            canSend -> {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .scale(buttonScale)
                        .clip(RoundedCornerShape(corner))
                        .background(buttonColor)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSend()
                        }
                        .semantics { contentDescription = "Send message" },
                    contentAlignment = Alignment.Center,
                ) {
                    SendGlyph(color = iconColor)
                }
            }

            isGroq -> {
                // Groq Whisper mode: Hold to talk, swipe up to lock, swipe left to cancel
                Box(contentAlignment = Alignment.Center) {
                    LockRail(
                        visible = isGroqActive && !cancelArmed && !isGroqLocked,
                        progress = lockProgress,
                    )
                    HuliaMicButton(
                        recording = isGroqActive,
                        cancelArmed = cancelArmed,
                        available = true,
                        level = levels.lastOrNull() ?: 0f,
                        dragX = dragX,
                        dragY = dragY,
                        onStart = { onStartGroqRecord(false) },
                        onFinish = onStopAndTranscribeGroq,
                        onCancel = onCancelGroqRecord,
                        onLock = onLockGroqRecord,
                        onCancelArmedChange = onCancelArmedChange,
                        onDrag = { x, y ->
                            dragX = x
                            dragY = y
                        },
                    )
                }
            }

            else -> {
                // Inbuilt voice mode: Simple tap toggle without gesture overlays
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .scale(buttonScale)
                        .clip(RoundedCornerShape(corner))
                        .background(if (isInbuiltListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onToggleInbuiltVoice() }
                        .semantics { contentDescription = if (isInbuiltListening) "Stop voice input" else "Voice input" },
                    contentAlignment = Alignment.Center,
                ) {
                    MicGlyph(color = if (isInbuiltListening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Lock Rail
// ---------------------------------------------------------------------------

@Composable
private fun LockRail(
    visible: Boolean,
    progress: Float,
) {
    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = defaultEffectsSpec(),
        label = "lockRailAppear",
    )
    if (appear <= 0.01f) return

    val fill = progress * progress * (3f - 2f * progress)

    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val filled = MaterialTheme.colorScheme.primary
    val glyphColor = if (progress > 0.72f) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val hop = rememberInfiniteTransition(label = "lockRailHop")
    val chevron by hop.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lockRailChevron",
    )

    Box(
        modifier = Modifier
            .offset(y = (-84).dp)
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 24.dp.toPx()
                scaleX = 0.9f + 0.1f * appear + 0.08f * progress
                scaleY = scaleX
            }
            .size(width = 42.dp, height = 76.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(track),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction = fill.coerceIn(0f, 1f))
                .background(filled),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            LockGlyph(color = glyphColor, size = 18.dp, open = 1f - progress)
            UpGlyph(
                color = glyphColor.copy(alpha = 0.55f + 0.45f * progress),
                size = 16.dp,
                modifier = Modifier.graphicsLayer { translationY = chevron * (1f - progress) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Recording Strip
// ---------------------------------------------------------------------------

@Composable
private fun RecordingStrip(
    elapsedMillis: Int,
    levels: List<Float>,
    cancelArmed: Boolean,
    locked: Boolean,
    cancelProgress: Float,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = when {
            cancelArmed -> MaterialTheme.colorScheme.errorContainer
            locked -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = defaultEffectsSpec(),
        label = "recordingContainer",
    )
    val accent by animateColorAsState(
        targetValue = when {
            cancelArmed -> MaterialTheme.colorScheme.error
            locked -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = defaultEffectsSpec(),
        label = "recordingAccent",
    )
    val onContainer by animateColorAsState(
        targetValue = when {
            cancelArmed -> MaterialTheme.colorScheme.onErrorContainer
            locked -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = defaultEffectsSpec(),
        label = "recordingOnContainer",
    )
    val corner by animateDpAsState(
        targetValue = if (locked) 20.dp else 26.dp,
        animationSpec = defaultSpatialSpec(),
        label = "recordingCorner",
    )

    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(corner))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HuliaRecordingDot(color = accent, steady = locked)

            Text(
                text = formatClipDuration(elapsedMillis),
                style = MaterialTheme.typography.labelLarge,
                color = onContainer,
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (cancelArmed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TrashGlyph(color = onContainer, size = 18.dp)
                        Text(
                            text = "Release to cancel",
                            style = MaterialTheme.typography.labelMedium,
                            color = onContainer,
                        )
                    }
                } else {
                    HuliaLiveLevels(
                        levels = levels,
                        color = accent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (locked) {
                Text(
                    text = "Hands free",
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer.copy(alpha = 0.8f),
                )
            } else if (!cancelArmed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    modifier = Modifier.graphicsLayer {
                        translationX = -cancelProgress * 22.dp.toPx()
                        alpha = 1f - cancelProgress
                    },
                ) {
                    BackGlyph(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 13.dp,
                    )
                    Text(
                        text = "cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HuliaRecordingDot(color: Color, steady: Boolean = false) {
    val period = if (steady) 1600 else 900
    val transition = rememberInfiniteTransition(label = "recordingDot")
    val coreAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (steady) 0.75f else 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = period, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordingDotCore",
    )
    val haloScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (steady) 1.7f else 2.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = period, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordingDotHaloScale",
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = period, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordingDotHaloAlpha",
    )

    Box(
        modifier = Modifier.size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .graphicsLayer {
                    scaleX = haloScale
                    scaleY = haloScale
                }
                .clip(CircleShape)
                .background(color.copy(alpha = haloAlpha)),
        )
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = coreAlpha)),
        )
    }
}

@Composable
private fun HuliaLiveLevels(
    levels: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(levels) {
        slide.snapTo(1f)
        slide.animateTo(0f, tween(durationMillis = 160, easing = LinearEasing))
    }

    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        if (levels.isEmpty()) return@Canvas
        val step = size.width / levels.size
        val weight = (step * 0.46f).coerceIn(2f, 4f)
        val shift = step * slide.value

        levels.forEachIndexed { index, level ->
            val scaled = kotlin.math.sqrt(level.coerceIn(0f, 1f))
            val position = (index + 1f) / levels.size
            val presence = 0.55f + 0.45f * position
            val extent = (0.16f + 0.84f * scaled) * size.height * presence
            val x = step * (index + 0.5f) + shift

            drawLine(
                color = color,
                start = Offset(x, (size.height - extent) / 2f),
                end = Offset(x, (size.height + extent) / 2f),
                strokeWidth = weight * (0.8f + 0.2f * position),
                cap = StrokeCap.Round,
                alpha = (0.30f + 0.70f * position) * if (index == 0) 1f - slide.value else 1f,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Mic Button with Gestures
// ---------------------------------------------------------------------------

@Composable
private fun HuliaMicButton(
    recording: Boolean,
    cancelArmed: Boolean,
    available: Boolean,
    level: Float,
    dragX: Float,
    dragY: Float,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onCancelArmedChange: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    val background by animateColorAsState(
        targetValue = when {
            cancelArmed -> MaterialTheme.colorScheme.error
            recording -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = defaultEffectsSpec(),
        label = "micColor",
    )
    val glyphColor by animateColorAsState(
        targetValue = when {
            cancelArmed -> MaterialTheme.colorScheme.onError
            recording -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = defaultEffectsSpec(),
        label = "micGlyphColor",
    )
    val corner by animateDpAsState(
        targetValue = if (recording) 26.dp else 17.dp,
        animationSpec = defaultSpatialSpec(),
        label = "micCorner",
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (recording) 1.12f else 0.86f,
        animationSpec = fastSpatialSpec(),
        label = "micScale",
    )

    val haloScale by animateFloatAsState(
        targetValue = if (recording) 1f + 0.55f * kotlin.math.sqrt(level.coerceIn(0f, 1f)) else 1f,
        animationSpec = fastSpatialSpec(),
        label = "micHalo",
    )

    val density = LocalDensity.current
    val cancelDistancePx = with(density) { CancelDistance.toPx() }
    val lockDistancePx = with(density) { LockDistance.toPx() }

    Box(contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = haloScale * 1.25f
                        scaleY = haloScale * 1.25f
                        alpha = 0.22f
                        translationX = dragX.coerceIn(-cancelDistancePx * 1.15f, 0f)
                        translationY = dragY.coerceIn(-lockDistancePx, 0f)
                    }
                    .clip(CircleShape)
                    .background(background),
            )
        }

        Box(
            modifier = Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                    translationX = dragX.coerceIn(-cancelDistancePx * 1.15f, 0f)
                    translationY = dragY.coerceIn(-lockDistancePx, 0f)
                }
                .clip(RoundedCornerShape(corner))
                .background(background)
                .pointerInput(available) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStart()

                        var armed = false
                        var locked = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()

                            val dy = change.position.y - down.position.y
                            val dx = change.position.x - down.position.x
                            onDrag(dx, dy)

                            val vertical = -dy > abs(dx)

                            if (vertical && -dy >= lockDistancePx) {
                                locked = true
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                if (armed) onCancelArmedChange(false)
                                onLock()
                                break
                            }

                            val away = !vertical && dx <= -cancelDistancePx
                            if (away != armed) {
                                armed = away
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCancelArmedChange(away)
                            }
                        }

                        onDrag(0f, 0f)
                        if (!locked) {
                            if (armed) onCancel() else onFinish()
                            onCancelArmedChange(false)
                        }
                    }
                }
                .semantics {
                    contentDescription = if (recording) {
                        "Recording. Release to send, slide left to cancel, slide up to lock"
                    } else {
                        "Hold to record a voice message"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            MicGlyph(color = glyphColor)
        }
    }
}

// ---------------------------------------------------------------------------
// Leading Attachment Button
// ---------------------------------------------------------------------------

@Composable
private fun ComposerLeadingButton(
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(52.dp)
                .scale(0.86f)
                .clip(RoundedCornerShape(17.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    expanded = true
                }
                .semantics { contentDescription = "Attach file or image" },
            contentAlignment = Alignment.Center,
        ) {
            PlusGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Photo or image") },
                leadingIcon = { ImageGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp) },
                onClick = {
                    expanded = false
                    onPickImage()
                },
            )
            DropdownMenuItem(
                text = { Text("Any file") },
                leadingIcon = { PlusGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp) },
                onClick = {
                    expanded = false
                    onPickFile()
                },
            )
        }
    }
}

@Composable
private fun DiscardRecordingButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Discard recording" },
        contentAlignment = Alignment.Center,
    ) {
        TrashGlyph(color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

// ---------------------------------------------------------------------------
// Glyphs (Cleanly drawn with round-capped strokes)
// ---------------------------------------------------------------------------

@Composable
fun SendGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f
        val tip = Offset(w / 2f, h * 0.16f)

        drawLine(color, Offset(w / 2f, h * 0.86f), tip, weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.20f, h * 0.48f), tip, weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.80f, h * 0.48f), tip, weight, StrokeCap.Round)
    }
}

@Composable
fun MicGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f
        val stroke = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round)

        drawLine(
            color,
            Offset(w * 0.5f, h * 0.22f),
            Offset(w * 0.5f, h * 0.50f),
            w * 0.24f,
            StrokeCap.Round,
        )

        val cradle = Path().apply {
            moveTo(w * 0.24f, h * 0.48f)
            cubicTo(w * 0.24f, h * 0.74f, w * 0.76f, h * 0.74f, w * 0.76f, h * 0.48f)
        }
        drawPath(cradle, color, style = stroke)

        drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.86f), weight, StrokeCap.Round)
    }
}

@Composable
fun PlusGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f

        drawLine(color, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.78f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), weight, StrokeCap.Round)
    }
}

@Composable
fun TrashGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.11f

        drawLine(color, Offset(w * 0.20f, h * 0.28f), Offset(w * 0.80f, h * 0.28f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.16f), Offset(w * 0.60f, h * 0.16f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.30f, h * 0.40f), Offset(w * 0.35f, h * 0.82f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.70f, h * 0.40f), Offset(w * 0.65f, h * 0.82f), weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.35f, h * 0.82f), Offset(w * 0.65f, h * 0.82f), weight, StrokeCap.Round)
    }
}

@Composable
fun UpGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.12f
        val tip = Offset(w / 2f, h * 0.36f)

        drawLine(color, Offset(w * 0.24f, h * 0.60f), tip, weight, StrokeCap.Round)
        drawLine(color, tip, Offset(w * 0.76f, h * 0.60f), weight, StrokeCap.Round)
    }
}

@Composable
fun BackGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f
        val tip = Offset(w * 0.34f, h / 2f)

        drawLine(color, Offset(w * 0.84f, h / 2f), tip, weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.58f, h * 0.24f), tip, weight, StrokeCap.Round)
        drawLine(color, Offset(w * 0.58f, h * 0.76f), tip, weight, StrokeCap.Round)
    }
}

@Composable
fun LockGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    open: Float = 0f,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.11f
        val lift = h * 0.10f * open.coerceIn(0f, 1f)

        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.30f, h * 0.16f - lift),
            size = Size(w * 0.40f, h * 0.36f),
            style = Stroke(width = weight, cap = StrokeCap.Round),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.20f, h * 0.46f),
            size = Size(w * 0.60f, h * 0.38f),
            cornerRadius = CornerRadius(w * 0.13f),
        )
    }
}

@Composable
fun ImageGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.1f

        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.16f, h * 0.2f),
            size = Size(w * 0.68f, h * 0.6f),
            cornerRadius = CornerRadius(w * 0.1f, w * 0.1f),
            style = Stroke(width = weight, join = StrokeJoin.Round),
        )
        drawCircle(color, w * 0.06f, Offset(w * 0.36f, h * 0.38f))
        val ridge = Path().apply {
            moveTo(w * 0.2f, h * 0.72f)
            lineTo(w * 0.42f, h * 0.52f)
            lineTo(w * 0.56f, h * 0.62f)
            lineTo(w * 0.72f, h * 0.44f)
            lineTo(w * 0.8f, h * 0.52f)
        }
        drawPath(ridge, color, style = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun formatClipDuration(durationMs: Int): String {
    val totalSec = (durationMs / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
