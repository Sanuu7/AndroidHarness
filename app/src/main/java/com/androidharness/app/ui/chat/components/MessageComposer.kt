package com.androidharness.app.ui.chat.components

import androidx.compose.runtime.CompositionLocalProvider
import com.androidharness.app.ui.theme.LocalHarnessGlassBackdrop
import com.androidharness.app.ui.theme.LocalHarnessGlassChromeBackdrop
import com.androidharness.app.ui.theme.harnessGlassChrome
import com.androidharness.app.ui.theme.LocalHarnessGlass
import com.androidharness.app.ui.theme.HarnessGlassMode
import com.androidharness.app.ui.common.ExpressiveDropdownMenu as DropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem as DropdownMenuItem

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent

import androidx.compose.ui.input.key.type

import androidx.compose.ui.input.key.key

import androidx.compose.ui.input.key.isCtrlPressed

import androidx.compose.ui.input.key.KeyEventType

import androidx.compose.ui.input.key.Key

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import com.androidharness.app.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.chat.GroqRecordState
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.fastSpatialSpec
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlin.math.abs

/** How far left the finger travels before releasing cancels the take. */
private val CancelDistance = 92.dp

/** How far up the finger travels before the recording keeps itself open. */
private val LockDistance = 68.dp

/**
 * The message composer with a floating mobile pill that gently opens on focus:
 * - Theme-colored Send, separate voice and attachment controls
 * - Soft-square leading button for attachments & skills
 * - Morphing send/mic action button
 * - Compact waveform capsule and gesture guidance for Groq Whisper
 * - Inbuilt audio fallback
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    sendAllowed: Boolean = true,
    onSchedule: (() -> Unit)? = null,
    scheduleUnavailableReason: String? = null,
    chatOnly: Boolean = false,
    /** Shown beside the queue button while busy: ask the typed text as a /btw side question. */
    onAskAside: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    attachments: @Composable () -> Unit = {},
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val desktop = com.androidharness.app.ui.LocalDesktopLayout.current
    val desktopInput = com.androidharness.app.ui.LocalDesktopInput.current
    val inputFocus = com.androidharness.app.ui.LocalComposerFocus.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    var focused by remember { mutableStateOf(false) }
    var inputLineCount by remember { mutableIntStateOf(1) }
    val inputAlignment = if (inputLineCount > 1) Alignment.Bottom else Alignment.CenterVertically

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
        animationSpec = expressiveEffectsSpec(),
        label = "sendColor",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            showStopOnly -> MaterialTheme.colorScheme.onError
            canSend || isGroqLocked -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = expressiveEffectsSpec(),
        label = "sendIconColor",
    )
    val corner by animateDpAsState(
        targetValue = if (canSend || isGroqLocked || showStopOnly) 26.dp else 17.dp,
        animationSpec = expressiveSpatialSpec(),
        label = "sendCorner",
    )

    val actionSize = if (desktopInput) 36.dp else if (desktop) 52.dp else 44.dp
    val leading: @Composable () -> Unit = {
        // Leading button: discard recording when locked, otherwise attachments/skills
        if (isGroqActive) {
            DiscardRecordingButton(
                transcribing = isGroqTranscribing,
                size = actionSize,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCancelGroqRecord()
                },
            )
        } else {
            ComposerLeadingButton(
                onPickImage = onAttachImage,
                onPickFile = onAttachFile,
                onSchedule = onSchedule,
                scheduleUnavailableReason = scheduleUnavailableReason,
                size = actionSize,
            )
        }

    }
    val field: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (isGroqActive) {
            VoiceRecorderCapsule(groqRecordState, recordingDurationMs, levels, cancelArmed, Modifier.weight(1f))
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
                        .padding(horizontal = if (!desktop || desktopInput) 8.dp else 16.dp),
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
                                    contentDescription = uiContext.getString(R.string.ui_remove_skill),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }

                    val inputTextStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                    )
                    val inputPadding = if (desktop && !desktopInput) 12.dp else 10.dp
                    val heightLimit = if (desktopInput || !desktop) 140.dp else 180.dp
                    var measuredLineHeight by remember(density, inputTextStyle) { mutableStateOf<Dp?>(null) }
                    val lineHeight = measuredLineHeight ?: with(density) { inputTextStyle.lineHeight.toDp() }
                    val visibleLines = ((heightLimit - inputPadding * 2) / lineHeight).toInt().coerceIn(1, 6)
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = lineHeight * visibleLines + inputPadding * 2)
                            .then(inputFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                            .semantics { contentDescription = uiContext.getString(R.string.ui_message_input) }
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (desktopInput && !event.isCtrlPressed && event.key == Key.Tab) {
                                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(
                                        if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                                    true
                                } else if (desktop && event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                    if (event.type == KeyEventType.KeyUp && canSend && sendAllowed) onSend()
                                    true
                                } else false
                            },
                        textStyle = inputTextStyle,
                        onTextLayout = {
                            inputLineCount = it.lineCount
                            if (it.lineCount > 0) measuredLineHeight = with(density) {
                                (it.getLineBottom(0) - it.getLineTop(0)).toDp()
                            }
                        },
                        minLines = 1,
                        maxLines = visibleLines,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        decorationBox = { innerTextField ->
                            // Placeholder, glyphs and cursor share one padded viewport.
                            // BasicTextField owns scrolling, selection and IME composition.
                            Box(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = if (desktop && !desktopInput) 72.dp else 44.dp)
                                    .padding(vertical = inputPadding),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (value.text.isEmpty()) Text(
                                    text = when {
                                        isInbuiltListening -> uiContext.getString(R.string.ui_listening)
                                        !attachedSkill.isNullOrBlank() -> uiContext.getString(R.string.ui_add_a_note_or_send)
                                        busy -> uiContext.getString(R.string.ui_queue_message)
                                        desktop && !chatOnly -> uiContext.getString(R.string.ui_describe_a_task)
                                        else -> uiContext.getString(R.string.ui_message)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = inputTextStyle,
                                    color = if (isInbuiltListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                innerTextField()
                            }
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(
                            width = 1.dp,
                            color = if (desktop || !isGroqActive) Color.Transparent else if (ringAlpha > 0f) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f * ringAlpha)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            },
                            shape = RoundedCornerShape(26.dp),
                        ),
                )
            }
        }

    }
    val voice: @Composable () -> Unit = {
        when {
            isGroq -> {
                // Groq Whisper mode: Hold to talk, swipe up to lock, swipe left to cancel
                Box(Modifier.size(actionSize), contentAlignment = Alignment.Center) {
                    LockRail(
                        visible = isGroqActive && !isGroqTranscribing && !cancelArmed && !isGroqLocked,
                        progress = lockProgress,
                    )
                    HuliaMicButton(
                        size = actionSize,
                        recording = isGroqActive,
                        cancelArmed = cancelArmed,
                        available = true,
                        level = levels.lastOrNull() ?: 0f,
                        dragX = dragX,
                        dragY = dragY,
                        onStart = { onStartGroqRecord(false) },
                        onTapLock = { onStartGroqRecord(true) },
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
                ExpressiveIconButton(
                    onClick = onToggleInbuiltVoice,
                    glassDecoration = false,
                    modifier = Modifier.size(actionSize)
                        .semantics { contentDescription = if (isInbuiltListening) uiContext.getString(R.string.ui_stop_voice_input) else uiContext.getString(R.string.settings_page_voice_title) },
                    shape = RoundedCornerShape(corner),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isInbuiltListening) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (isInbuiltListening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(painterResource(R.drawable.ic_chat_mic), null, Modifier.size(18.dp))
                }
            }
        }
    }
    val trailing: @Composable () -> Unit = {
        // Action button on the right.
        //
        // Native press feedback changes the painted shape, keeping actionSize
        // and the clickable area steady while the input grows or shrinks.
        when {
            isGroqActive && isGroqLocked -> {
                ExpressiveIconButton(preserveFill = true,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onStopAndTranscribeGroq()
                    },
                    modifier = Modifier.size(actionSize)
                        .semantics { contentDescription = uiContext.getString(R.string.ui_transcribe_voice_message) },
                    shape = CircleShape,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    StopGlyph(color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            isGroqActive -> voice()

            showQueueSend -> {
                ExpressiveIconButton(preserveFill = true,
                    onClick = { if (sendAllowed) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSend() } },
                    enabled = sendAllowed,
                    modifier = Modifier.size(actionSize)
                        .semantics { contentDescription = uiContext.getString(R.string.ui_queue_message) },
                    shape = RoundedCornerShape(corner),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(painterResource(R.drawable.ic_chat_arrow_up), null, Modifier.size(20.dp))
                }
            }


            showStopOnly -> {
                ExpressiveIconButton(preserveFill = true,
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onStop() },
                    modifier = Modifier.size(actionSize)
                        .semantics { contentDescription = uiContext.getString(R.string.settings_action_stop) },
                    shape = RoundedCornerShape(corner),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = buttonColor, contentColor = iconColor),
                ) {
                    StopGlyph(color = iconColor)
                }
            }

            canSend -> {
                ExpressiveIconButton(preserveFill = true,
                    onClick = { if (sendAllowed) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSend() } },
                    enabled = sendAllowed,
                    modifier = Modifier.size(actionSize)
                        .semantics { contentDescription = uiContext.getString(R.string.ui_send_message) },
                    shape = RoundedCornerShape(corner),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = buttonColor, contentColor = iconColor),
                ) {
                    Icon(painterResource(R.drawable.ic_chat_arrow_up), null, Modifier.size(20.dp))
                }
            }

            else -> voice()
        }
    }
    val slotSize = actionSize.coerceAtLeast(48.dp)
    val voiceSlot = updateTransition(isGroqLocked || isGroqTranscribing, label = "voice slot")
    val voiceSlotWidth by voiceSlot.animateDp(
        transitionSpec = { expressiveSpatialSpec() }, label = "voiceSlotWidth",
    ) { collapsed -> if (collapsed) 0.dp else slotSize }
    val recorderContent: @Composable () -> Unit = {
        CompositionLocalProvider(LocalHarnessGlassBackdrop provides (LocalHarnessGlassChromeBackdrop.current ?: LocalHarnessGlassBackdrop.current)) {
        // animateContentSize clips drawing outside its bounds, including the hold-to-lock rail.
        Column(if (isGroqActive) Modifier else Modifier.animateContentSize(
            animationSpec = expressiveSpatialSpec(), alignment = Alignment.BottomCenter,
        )) {
            if (!isGroqActive) attachments()
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (isGroqActive) 64.dp else 0.dp).padding(horizontal = 4.dp,
                    vertical = if (isGroqActive || focused && !desktop && LocalHarnessGlass.current.mode == HarnessGlassMode.OFF) 8.dp else 4.dp),
                verticalAlignment = if (isGroqActive) Alignment.CenterVertically else inputAlignment,
            ) {
                Box(Modifier.width(slotSize), contentAlignment = Alignment.Center) { leading() }
                field()
                // The held mic retains its node and position until release or lock.
                Box(Modifier.width(voiceSlotWidth.coerceAtLeast(0.dp)), contentAlignment = Alignment.Center) {
                    // Expose a touch target only after the slot has fully reopened.
                    if (!voiceSlot.currentState && !voiceSlot.targetState) {
                        if (showQueueSend && !isGroqActive && onAskAside != null) AskAsideButton(actionSize, onAskAside) else voice()
                    }
                }
                Box(Modifier.width(slotSize), contentAlignment = Alignment.Center) {
                    when {
                        isGroqLocked -> trailing()
                        isGroqActive -> Spacer(Modifier.size(actionSize))
                        canSend || showStopOnly -> trailing()
                        else -> ExpressiveIconButton(preserveFill = true, onClick = {}, enabled = false,
                            modifier = Modifier.size(actionSize).semantics { contentDescription = uiContext.getString(R.string.ui_send_message) },
                            shape = CircleShape,
                            colors = IconButtonDefaults.iconButtonColors(
                                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .38f),
                                disabledContentColor = if (com.androidharness.app.ui.theme.LocalHarnessGlass.current.mode != com.androidharness.app.ui.theme.HarnessGlassMode.OFF) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary)) {
                            Icon(painterResource(R.drawable.ic_chat_arrow_up), null, Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    if (!desktop) {
        val expanded = focused && !isGroqActive
        val inset by animateDpAsState(if (glass) 16.dp else if (expanded) 10.dp else 16.dp,
            expressiveSpatialSpec(), label = "composerInset")
        val pillCorner by animateDpAsState(if (glass || isGroqActive) 32.dp else if (expanded) 30.dp else 28.dp,
            expressiveSpatialSpec(), label = "composerCorner")
        val elevation by animateDpAsState(if (expanded && !glass) 2.dp else 0.dp,
            expressiveSpatialSpec(), label = "composerElevation")
        val outline by animateColorAsState(when {
            glass -> if (expanded) scheme.primary.copy(alpha = .18f) else Color.Transparent
            isGroqActive -> Color.Transparent
            expanded -> scheme.primary.copy(alpha = .55f)
            else -> scheme.outlineVariant.copy(alpha = .55f)
        }, expressiveEffectsSpec(), label = "composerOutline")
        Column(modifier.fillMaxWidth().padding(horizontal = inset, vertical = 8.dp)
            .testTag("message-composer")
            .shadow(elevation, RoundedCornerShape(pillCorner), clip = false)
            .harnessGlassChrome(if (glass) Color.Transparent else if (isGroqActive) scheme.surfaceContainer else scheme.surfaceContainerHigh,
                RoundedCornerShape(pillCorner), centerOpacityFloor = if (glass) .32f else 0f)
            .border(1.dp, outline, RoundedCornerShape(pillCorner))) { recorderContent() }
    } else {
        Column(modifier.fillMaxWidth()
            .padding(horizontal = if (desktopInput) 12.dp else 20.dp, vertical = if (desktopInput) 6.dp else 12.dp)
            .harnessGlassChrome(if (glass) Color.Transparent else scheme.surfaceContainerHigh, RoundedCornerShape(if (glass || isGroqActive) 24.dp else 14.dp),
                centerOpacityFloor = if (glass) .32f else 0f)
            .border(1.dp, if (isGroqActive) Color.Transparent else if (focused) scheme.primary else scheme.outlineVariant,
                RoundedCornerShape(if (isGroqActive) 24.dp else 14.dp))) { recorderContent() }
    }

}

/** Tonal twin of the queue button; the answer opens in the /btw sheet and never joins the chat. */
@Composable
private fun AskAsideButton(size: Dp, onClick: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHapticFeedback.current
    ExpressiveIconButton(preserveFill = true,
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onClick() },
        modifier = Modifier.size(size).testTag("composer-ask-aside")
            .semantics { contentDescription = uiContext.getString(R.string.btw_composer_action) },
        shape = RoundedCornerShape(size / 2),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Icon(painterResource(R.drawable.ic_chat_btw), null, Modifier.size(19.dp))
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

    val chevron by animateFloatAsState(-4f * progress, fastSpatialSpec(), label = "lockRailChevron")

    Box(
        modifier = Modifier
            .offset(y = (-84).dp)
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 24.dp.toPx()
                scaleX = 0.9f + 0.1f * appear + 0.08f * progress
                scaleY = scaleX
            }
            .requiredSize(width = 42.dp, height = 76.dp)
            .shadow(3.dp, RoundedCornerShape(21.dp), clip = false)
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
    onTapLock: () -> Unit = {},
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onCancelArmedChange: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Unit,
    size: Dp = 52.dp,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHapticFeedback.current

    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    var windowPosition by remember { mutableStateOf(Offset.Zero) }
    val start by rememberUpdatedState(onStart)
    val tapLock by rememberUpdatedState(onTapLock)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    val lock by rememberUpdatedState(onLock)
    val armCancel by rememberUpdatedState(onCancelArmedChange)
    val drag by rememberUpdatedState(onDrag)

    val background by animateColorAsState(
        targetValue = when {
            cancelArmed -> MaterialTheme.colorScheme.error
            recording -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = expressiveEffectsSpec(),
        label = "micColor",
    )
    val glyphColor by animateColorAsState(
        targetValue = when {
            cancelArmed -> MaterialTheme.colorScheme.onError
            recording -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = expressiveEffectsSpec(),
        label = "micGlyphColor",
    )
    val corner by animateDpAsState(
        targetValue = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) size / 2 else if (recording) 26.dp else 17.dp,
        animationSpec = expressiveSpatialSpec(),
        label = "micCorner",
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed || recording) 1.03f else 1f,
        animationSpec = expressiveSpatialSpec(),
        label = "micScale",
    )

    val density = LocalDensity.current
    val cancelDistancePx = with(density) { CancelDistance.toPx() }
    val lockDistancePx = with(density) { LockDistance.toPx() }

    Box(contentAlignment = Alignment.Center) {

        Box(
            modifier = Modifier
                .size(size)
                // Track window coordinates because opening the recording panel
                // changes the input height while this gesture is still in progress.
                .onGloballyPositioned { windowPosition = it.positionInWindow() }
                .pointerInput(available) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        if (!available) return@awaitEachGesture
                        val press = PressInteraction.Press(down.position)
                        interactions.tryEmit(press)
                        val origin = down.position + windowPosition
                        val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        var armed = false
                        var locked = false
                        var started = false
                        try {
                            while (true) {
                                val event = if (started) awaitPointerEvent() else withTimeoutOrNull(
                                    (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1L)
                                ) { awaitPointerEvent() }
                                if (event == null) {
                                    started = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    start()
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.position + windowPosition - origin
                                if (!started && delta.getDistance() > viewConfiguration.touchSlop) {
                                    started = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    start()
                                }
                                if (!started) continue
                                change.consume()
                                drag(delta.x, delta.y)
                                val vertical = -delta.y > abs(delta.x)
                                if (vertical && -delta.y >= lockDistancePx) {
                                    locked = true
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    lock()
                                    break
                                }
                                val away = !vertical && delta.x <= -cancelDistancePx
                                if (away != armed) {
                                    armed = away
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    armCancel(away)
                                }
                            }
                            if (!started) {
                                down.consume()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                tapLock()
                            } else if (!locked) {
                                if (armed) cancel() else finish()
                            }
                        } finally {
                            interactions.tryEmit(PressInteraction.Release(press))
                            drag(0f, 0f)
                            armCancel(false)
                        }
                    }
                }
                .semantics {
                    onClick {
                        if (available && !recording) onTapLock()
                        available && !recording
                    }
                    contentDescription = if (recording) {
                        uiContext.getString(R.string.ui_recording_release_to_send_slide_left_to)
                    } else {
                        uiContext.getString(R.string.ui_tap_to_lock_recording_hold_to_record)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = buttonScale
                        scaleY = buttonScale
                        translationX = dragX.coerceIn(-cancelDistancePx * 1.15f, 0f)
                        translationY = dragY.coerceIn(-lockDistancePx, 0f)
                    }
                    .clip(RoundedCornerShape(corner))
                    .then(if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF && !recording && !cancelArmed) Modifier
                        else Modifier.harnessGlassChrome(background, RoundedCornerShape(corner)))
                    .indication(interactions, ripple(color = glyphColor)),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = cancelArmed, animationSpec = expressiveEffectsSpec(), label = "micCancelGlyph") { cancelling ->
                    if (cancelling) TrashGlyph(color = glyphColor) else MicGlyph(color = glyphColor)
                }
            }
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
    onSchedule: (() -> Unit)?,
    scheduleUnavailableReason: String?,
    size: Dp = 52.dp,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        ExpressiveIconButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); expanded = true },
            glassDecoration = false,
            modifier = Modifier.size(size)
                .semantics { contentDescription = uiContext.getString(R.string.ui_message_actions) },
            shape = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) CircleShape else RoundedCornerShape(17.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (expanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (expanded) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_chat_plus), null, Modifier.size(18.dp))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(uiContext.getString(R.string.ui_photo_or_image)) },
                leadingIcon = { ImageGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp) },
                onClick = {
                    expanded = false
                    onPickImage()
                },
            )
            DropdownMenuItem(
                text = { Text(uiContext.getString(R.string.ui_any_file)) },
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onPickFile()
                },
            )
            if (onSchedule != null) {
                androidx.compose.material3.HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        androidx.compose.foundation.layout.Column {
                            Text(uiContext.getString(R.string.ui_schedule_message))
                            scheduleUnavailableReason?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                    },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Schedule, null) },
                    enabled = scheduleUnavailableReason == null,
                    onClick = { expanded = false; onSchedule() },
                )
            }
        }
    }
}

@Composable
private fun DiscardRecordingButton(onClick: () -> Unit, size: Dp = 52.dp, transcribing: Boolean = false) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    ExpressiveIconButton(
        onClick = onClick,
        modifier = Modifier.size(size)
            .semantics { contentDescription = uiContext.getString(if (transcribing) R.string.settings_action_cancel else R.string.ui_discard_recording) },
        shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(Icons.Filled.Close, null, Modifier.size(22.dp))
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

/**
 * Stop, drawn as a rounded outline like [SendGlyph] and [MicGlyph] rather than
 * dropped in as a filled Material icon, so the send/stop/mic states of the same
 * button read as one set instead of two.
 */
@Composable
fun StopGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val weight = w * 0.115f
        val inset = w * 0.22f

        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2f, h - inset * 2f),
            cornerRadius = CornerRadius(w * 0.16f),
            style = Stroke(width = weight, join = StrokeJoin.Round),
        )
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
