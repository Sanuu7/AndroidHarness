package com.androidharness.app.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.agent.ThinkingSpecs
import com.androidharness.app.core.ChatActivityPresentation
import com.androidharness.app.core.ChatExperience
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ChatResponseImages
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.response.ChatRichResponse
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.core.response.UserMessageContent
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ImageFrequency
import com.androidharness.app.data.RumbleCue
import com.androidharness.app.data.audio.TtsPhase
import com.androidharness.app.data.audio.TtsState
import com.androidharness.app.ui.chat.CopyIconButton
import com.androidharness.app.ui.chat.FileAttachments
import com.androidharness.app.ui.chat.GroqRecordState
import com.androidharness.app.ui.chat.LocalChatImagesEnabled
import com.androidharness.app.ui.chat.SpeakIconButton
import com.androidharness.app.ui.chat.VoiceInputController
import com.androidharness.app.ui.chat.components.ApprovalCard
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.RumbleDirector
import com.androidharness.app.ui.chat.components.ChatActivity
import com.androidharness.app.ui.chat.components.FollowUpChips
import com.androidharness.app.ui.chat.components.LiveSourceChips
import com.androidharness.app.ui.chat.components.LocalChatImageOpen
import com.androidharness.app.ui.chat.components.LocalChatImageSources
import com.androidharness.app.ui.chat.components.QuestionCard
import com.androidharness.app.ui.chat.keepLiveHeight
import com.androidharness.app.ui.chat.rememberPacedText
import com.androidharness.app.ui.chat.rememberVoiceInputController
import com.androidharness.app.ui.common.FilledIconButton
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.RumbleFlow
import com.androidharness.app.ui.common.rememberRumble
import com.androidharness.app.ui.common.rememberRumbleFlow
import com.androidharness.app.ui.common.rumbleStyle
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** Applies the user's own theme, then shows the panel. */
@Composable
internal fun AssistantPanelRoot(
    container: AppContainer,
    controller: AssistantController,
    showKey: Int,
    visible: Boolean,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    HarnessTheme(
        themeMode = s.themeMode,
        dynamicColor = s.dynamicColor,
        progressBarStyle = s.progressBarStyle,
        colorPalette = s.colorPalette,
        customPalette = s.customPalettes.firstOrNull { it.id == s.activeCustomPaletteId }
            .takeUnless { s.dynamicColor || s.harnessCanvas.adaptColors },
        chatLayout = s.chatLayout,
    ) {
        val state by controller.state.collectAsStateWithLifecycle()
        val tts by container.tts.state.collectAsStateWithLifecycle()
        val voice = rememberVoiceInputController(container)
        AssistantPanel(state, s, tts, voice, controller, showKey, visible, onDismiss, onContinue, onOpenUrl)
    }
}

/** The shade behind the panel eases in from nothing, so it has no visible start line. */
private val ScrimStops = List(11) { i ->
    val at = 0.35f + 0.65f * i / 10f
    val x = i / 10f
    at to 0.32f * x * x * (3f - 2f * x)
}.let { listOf(0f to 0f) + it }

/** How the panel grows with its answer: no bounce, just a soft glide. */
private val HugSpec = spring<Float>(dampingRatio = 1f, stiffness = 260f)

/** Sizes the panel's gestures need, kept current for the remembered scroll handler. */
private data class Sizing(val full: Float, val half: Float, val fit: Float, val conversation: Boolean)

/** Voice input for the panel, using the same engine and silence setting as Chat. */
private class PanelVoice(
    private val voice: VoiceInputController,
    private val settings: () -> AppSettings,
    private val hasMic: () -> Boolean,
    private val onText: (String) -> Unit,
    private val onNoMic: () -> Unit,
) {
    var heard by mutableStateOf("")
    val native get() = settings().voiceEngine == "inbuilt"
    val listening get() = voice.isListening
    val transcribing get() = voice.isTranscribing

    /** 0..1 loudness for the halo, from either engine. */
    fun level(): Float = if (!voice.isListening) 0f
        else if (native) ((voice.rmsDb + 2f) / 12f).coerceIn(0f, 1f) else sqrt(voice.rmsDb.coerceIn(0f, 1f))

    fun start() {
        if (!hasMic()) { onNoMic(); return }
        if (voice.isListening || voice.isTranscribing) return
        heard = ""
        val silence = settings().voiceAutoSendTimeoutMs.toLong()
        if (native) {
            voice.startNativeListening({ text, final ->
                if (text.isNotBlank()) heard = text
                if (final) send()
            }, silenceTimeoutMs = silence)
        } else {
            voice.startGroqRecording(locked = true, autoSilenceTimeoutMs = silence) { transcribe() }
        }
    }

    /** Stops listening and sends what was heard. */
    fun finish() {
        if (native) { voice.stopNativeListening(); send() } else if (voice.groqRecordState == GroqRecordState.LOCKED) transcribe()
    }

    /** Stops listening and hands back what was heard so far, without sending. */
    fun cancel(): String {
        val kept = heard
        heard = ""
        voice.releaseAll()
        return kept
    }

    private fun transcribe() = voice.stopAndTranscribe(settings().groqWhisperModel, settings().voiceEngine) { text ->
        heard = text; send()
    }

    private fun send() {
        val text = heard.trim()
        heard = ""
        if (text.isNotEmpty()) onText(text)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AssistantPanel(
    state: AssistantUiState,
    settings: AppSettings,
    tts: TtsState,
    voiceController: VoiceInputController,
    controller: AssistantController,
    showKey: Int,
    visible: Boolean,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val reduceMotion = remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val latestSettings by rememberUpdatedState(settings)

    var draft by remember(showKey) { mutableStateOf("") }
    // Typing, dragging or picking a suggestion means the user is busy with the panel, so the halo settles down.
    var engaged by remember(showKey) { mutableStateOf(false) }
    var voiceMode by remember(showKey) { mutableStateOf(settings.assistantVoiceFirst) }
    val focus = remember { FocusRequester() }
    var wantsKeyboard by remember(showKey) { mutableStateOf(false) }
    // The user sized the panel by hand; it stops following its content until the next question.
    var userSized by remember(showKey) { mutableStateOf(false) }
    // The conversation keeps its newest lines in view until the user scrolls back to read.
    var following by remember(showKey) { mutableStateOf(true) }
    // When the latest question was sent, so its bubble rises in once, even when the saved copy replaces it.
    var sentAt by remember(showKey) { mutableLongStateOf(0L) }

    fun send(text: String, spoken: Boolean = false, withScreen: Boolean = false): Boolean {
        val sent = controller.send(text, spoken, withScreen) != null
        if (sent) {
            userSized = false
            following = true
            sentAt = if (reduceMotion) 0L else SystemClock.uptimeMillis()
        }
        return sent
    }
    val noMic = stringResource(R.string.assistant_mic_permission)
    val voice = remember(voiceController) {
        PanelVoice(voiceController, { latestSettings },
            hasMic = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            onText = { send(it, spoken = true) },
            onNoMic = { voiceMode = false; controller.showError(noMic) })
    }
    voiceController.errorMessage?.let { message -> LaunchedEffect(message) { controller.showError(message) } }

    // Entrance, on Material's expressive spatial spring: a small circle rises with the light,
    // then stretches sideways into the pill.
    val rise = remember { Animatable(0f) }
    val grow = remember { Animatable(0f) }
    // The light shows at once and leaves with the pill.
    val glow = remember { Animatable(0f) }
    LaunchedEffect(showKey) {
        rise.snapTo(0f); grow.snapTo(0f); glow.snapTo(0f)
        if (reduceMotion) { glow.snapTo(1f); rise.snapTo(1f); grow.animateTo(1f, tween(160)) }
        else {
            launch { glow.animateTo(1f, tween(200)) }
            launch { rise.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f)) }
            delay(110)
            grow.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f))
        }
    }
    // Voice first: start listening once the pill has opened.
    LaunchedEffect(showKey) {
        if (settings.assistantVoiceFirst) { delay(if (reduceMotion) 0 else 350); voice.start() }
    }
    // Android hid the panel (back, home, another app): let go of the microphone.
    LaunchedEffect(visible) { if (!visible) voice.cancel() }
    // Switching to the keyboard focuses the field once it is on screen.
    LaunchedEffect(wantsKeyboard, voiceMode) {
        if (wantsKeyboard && !voiceMode) { delay(60); runCatching { focus.requestFocus() }; keyboard?.show(); wantsKeyboard = false }
    }

    var leaving by remember { mutableStateOf(false) }
    fun dismiss() {
        if (leaving) return
        leaving = true
        voice.cancel()
        scope.launch {
            launch { grow.animateTo(0f, tween(if (reduceMotion) 60 else 180)) }
            launch { glow.animateTo(0f, tween(if (reduceMotion) 80 else 260, delayMillis = if (reduceMotion) 0 else 60)) }
            rise.animateTo(0f, tween(if (reduceMotion) 80 else 240, delayMillis = if (reduceMotion) 0 else 80))
            onDismiss()
            leaving = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Soft shade at the bottom that keeps the panel readable over any app. Tapping it closes.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = rise.value.coerceIn(0f, 1f) }
                .background(Brush.verticalGradient(colorStops = ScrimStops.map { (at, a) -> at to scheme.scrim.copy(alpha = a) }.toTypedArray()))
                .pointerInput(Unit) { detectTapGestures { dismiss() } },
        )
        HarnessHalo(
            showKey,
            engaged = (engaged || state.hasConversation) && !voice.listening,
            listening = voice.listening,
            working = state.busy,
            reduceMotion = reduceMotion,
            level = voice::level,
            fade = { glow.value.coerceIn(0f, 1f) },
            rise = { rise.value },
        )

        BoxWithConstraints(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val fullPx = with(density) { (maxHeight - 150.dp).coerceAtLeast(160.dp).toPx() }
            val halfPx = minOf(fullPx, with(density) { (maxHeight * 0.42f).toPx() })

            var body by remember(showKey) { mutableFloatStateOf(0f) }
            val bodySpeed = remember { FloatArray(1) }
            var pull by remember(showKey) { mutableFloatStateOf(0f) }
            var settle by remember { mutableStateOf<Job?>(null) }
            // Natural height of everything in the body; the panel hugs it until the user sizes it.
            var contentPx by remember(showKey) { mutableIntStateOf(0) }
            val fitPx = contentPx.toFloat().coerceIn(with(density) { 56.dp.toPx() }, fullPx)
            val sizing = rememberUpdatedState(Sizing(fullPx, halfPx, fitPx, state.hasConversation))

            fun animateBody(target: Float, velocity: Float = bodySpeed[0], spec: AnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 420f)) {
                settle?.cancel()
                settle = scope.launch {
                    animate(body, target, velocity, spec) { v, speed -> body = v; bodySpeed[0] = speed }
                    bodySpeed[0] = 0f
                }
            }
            /** Lets go of a drag: the nearest resting height, or the next one in the fling's direction. */
            fun settleBody(fingerVelocity: Float) {
                val s = sizing.value
                val stops = buildList { add(0f); add(s.half); add(s.full); if (s.conversation) add(s.fit) }.distinct().sorted()
                val target = when {
                    fingerVelocity < -1200f -> stops.firstOrNull { it > body + 1f } ?: s.full
                    fingerVelocity > 1200f -> stops.lastOrNull { it < body - 1f } ?: 0f
                    else -> stops.minBy { abs(it - body) }
                }
                animateBody(target, -fingerVelocity)
            }
            // A new question folds the panel back to the pill.
            LaunchedEffect(state.hasConversation) {
                if (!state.hasConversation && body > 0f) animateBody(0f)
            }
            // While the user has not sized it, the panel grows and shrinks with its content.
            LaunchedEffect(state.hasConversation, userSized, fitPx) {
                if (state.hasConversation && !userSized && abs(fitPx - body) > 0.5f) animateBody(fitPx, spec = if (reduceMotion) tween<Float>(0) else HugSpec)
            }

            val scroll = remember(showKey) { ScrollState(0) }
            var questionTop by remember(showKey) { mutableIntStateOf(0) }
            val gapPx = with(density) { 8.dp.roundToPx() }
            // Speed of the follow glide, carried into the next one so a new line never makes it stutter.
            val followSpeed = remember { FloatArray(1) }
            // Follow the newest text once the panel cannot grow any more, but never push the
            // latest question off the top: from there the answer reads on below it.
            LaunchedEffect(scroll) {
                snapshotFlow {
                    val settled = userSized || body >= sizing.value.full - 1f
                    if (following && settled) minOf(scroll.maxValue, (questionTop - gapPx).coerceAtLeast(0)) else -1
                }.collectLatest { target ->
                    if (target <= scroll.value) { followSpeed[0] = 0f; return@collectLatest }
                    if (reduceMotion) { scroll.scrollTo(target); return@collectLatest }
                    scroll.scroll {
                        var at = scroll.value.toFloat()
                        animate(at, target.toFloat(), followSpeed[0], spring(dampingRatio = 1f, stiffness = 240f)) { v, speed ->
                            scrollBy(v - at)
                            at = v
                            followSpeed[0] = speed
                        }
                    }
                    followSpeed[0] = 0f
                }
            }

            // Dragging the conversation moves the panel first, like a sheet: up grows it to full
            // height before the text scrolls, and down at the top of the text shrinks it.
            val sheetScroll = remember(showKey) {
                object : NestedScrollConnection {
                    var moved = false
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        val dy = available.y
                        if (dy > 0f) following = false
                        val full = sizing.value.full
                        if (dy < 0f && body < full - 0.5f) {
                            settle?.cancel()
                            userSized = true; engaged = true; moved = true
                            val grown = minOf(full, body - dy)
                            val used = grown - body
                            body = grown
                            return Offset(0f, -used)
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        if (scroll.value >= scroll.maxValue - 1) following = true
                        val dy = available.y
                        if (dy > 0f && body > 0f) {
                            settle?.cancel()
                            userSized = true; moved = true
                            val shrunk = maxOf(0f, body - dy)
                            val used = body - shrunk
                            body = shrunk
                            return Offset(0f, used)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (!moved) return Velocity.Zero
                        moved = false
                        // At full height an upward fling belongs to the text.
                        if (body >= sizing.value.full - 0.5f && available.y < 0f) return Velocity.Zero
                        settleBody(available.y)
                        return available
                    }
                }
            }

            val drag = rememberDraggableState { delta ->
                settle?.cancel()
                engaged = true
                if (body <= 0f && delta > 0f || pull > 0f) {
                    // Pulling the closed pill down: follow the finger with resistance, then close on release.
                    pull = (pull + delta * 0.5f).coerceAtLeast(0f)
                } else {
                    userSized = true
                    body = (body - delta).coerceIn(0f, fullPx)
                }
            }
            // The keyboard can shrink the room after the panel opened; never grow past the top.
            val bodyDp = with(density) { body.coerceAtMost(fullPx).toDp() }
            val open = (body / halfPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val corner = lerp(32.dp, 28.dp, open)
            val shape = RoundedCornerShape(corner)
            val revealPx = with(density) { 48.dp.toPx() }

            Column(
                Modifier.fillMaxWidth().widthIn(max = 640.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedVisibility(
                    // They stay while the mic listens too: a tap stops listening and asks that instead.
                    visible = !state.hasConversation && body < 1f && !leaving,
                    enter = fadeIn(tween(160)) + expandVertically(spring(stiffness = 500f)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                ) { Suggestions(grow = { grow.value }, onPick = { engaged = true; voice.cancel(); send(it, withScreen = true) }) }

                Surface(
                    shape = shape,
                    color = scheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val t = rise.value
                            translationY = (1f - t) * 72.dp.toPx() + pull
                            alpha = t.coerceIn(0f, 1f)
                            scaleX = lerp(0.6f, 1f, t.coerceIn(0f, 1f)); scaleY = scaleX
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .graphicsLayer {
                            val g = grow.value
                            this.shape = RevealShape(g, corner)
                            clip = true
                            shadowElevation = 10.dp.toPx() * g.coerceIn(0f, 1f)
                        }
                        .nestedScroll(sheetScroll)
                        .draggable(
                            drag, Orientation.Vertical,
                            onDragStopped = { velocity ->
                                if (pull > 0f) {
                                    if (pull > with(density) { 72.dp.toPx() } || velocity > 2400f) dismiss()
                                    animate(pull, 0f, animationSpec = spring(stiffness = 600f)) { v, _ -> pull = v }
                                    return@draggable
                                }
                                settleBody(velocity)
                            },
                        ),
                ) {
                    Column(Modifier.graphicsLayer { alpha = ((grow.value - 0.45f) / 0.5f).coerceIn(0f, 1f) }) {
                        DragHandle(Modifier.fillMaxWidth())
                        if (body > 0.5f || state.hasConversation) {
                            Box(Modifier.fillMaxWidth().height(bodyDp).graphicsLayer { alpha = (body / revealPx).coerceIn(0f, 1f) }) {
                                if (state.hasConversation) {
                                    PanelBody(
                                        state = state, settings = settings, tts = tts, controller = controller, scroll = scroll,
                                        sentAt = sentAt.takeIf { it > 0L },
                                        onContentHeight = { contentPx = it },
                                        onQuestionTop = { questionTop = it },
                                        onAsk = { send(it) },
                                        onOpenUrl = onOpenUrl,
                                        onContinue = onContinue,
                                        onNewQuestion = { voice.cancel(); controller.newQuestion() },
                                    )
                                } else {
                                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        ModelLine(state, controller::setThinking)
                                        ScreenPreview(state, Modifier.weight(1f).fillMaxWidth())
                                    }
                                }
                            }
                        }
                        InputRow(
                            state = state, draft = draft, onDraft = { draft = it }, voiceMode = voiceMode, voice = voice,
                            focus = focus,
                            onFocused = { engaged = true },
                            // The keyboard steps aside after sending so the answer has the room.
                            onSend = { if (send(draft)) { draft = ""; keyboard?.hide() } },
                            onStop = controller::stop,
                            onClose = ::dismiss,
                            onMic = {
                                if (voice.listening) voice.finish()
                                else { voiceMode = true; engaged = false; keyboard?.hide(); controller.quiet(); voice.start() }
                            },
                            onKeyboard = {
                                val heard = voice.cancel()
                                if (heard.isNotBlank()) draft = heard
                                voiceMode = false; engaged = true; wantsKeyboard = true
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A pill that starts as a circle in the middle and stretches out to its full width. */
private class RevealShape(private val progress: Float, private val corner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = progress.coerceIn(0f, 1.05f)
        val h = size.height
        val start = minOf(h, size.width)
        val w = (start + (size.width - start) * p).coerceIn(start, size.width)
        val left = (size.width - w) / 2f
        val r = lerp(h / 2f, with(density) { corner.toPx() }, p.coerceIn(0f, 1f)).coerceAtMost(minOf(h, w) / 2f)
        return Outline.Rounded(RoundRect(left, 0f, left + w, h, CornerRadius(r)))
    }
}

@Composable
private fun DragHandle(modifier: Modifier) {
    val label = stringResource(R.string.assistant_drag_handle)
    Box(modifier.height(18.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.padding(top = 6.dp).size(width = 36.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
    }
}

@Composable
private fun Suggestions(grow: () -> Float, onPick: (String) -> Unit) {
    val picks = listOf(R.string.assistant_suggest_summarize, R.string.assistant_suggest_explain, R.string.assistant_suggest_reply)
        .map { stringResource(it) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        picks.forEachIndexed { i, text ->
            Surface(
                onClick = { onPick(text) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = 3.dp,
                modifier = Modifier.graphicsLayer {
                    // Each chip pops up a beat after the one before it, once the pill has opened.
                    val t = ((grow() - 0.5f - 0.1f * i) / 0.3f).coerceIn(0f, 1f)
                    translationY = (1f - t) * 18.dp.toPx()
                    scaleX = lerp(0.8f, 1f, t); scaleY = scaleX
                    alpha = t
                },
            ) {
                Text(text, Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InputRow(
    state: AssistantUiState,
    draft: String,
    onDraft: (String) -> Unit,
    voiceMode: Boolean,
    voice: PanelVoice,
    focus: FocusRequester,
    onFocused: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onMic: () -> Unit,
    onKeyboard: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.assistant_close)) }

        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            AnimatedContent(voiceMode, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "assistantMode") { speaking ->
                if (speaking) VoiceLine(voice)
                else Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) Text(
                        stringResource(if (state.sessionId == null) R.string.assistant_input_hint else R.string.assistant_input_hint_follow_up),
                        style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1,
                    )
                    BasicTextField(
                        draft, onDraft,
                        Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { if (it.isFocused) onFocused() },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                    )
                }
            }
        }

        if (voiceMode && !state.busy) {
            IconButton(onClick = onKeyboard) { Icon(Icons.Rounded.Keyboard, stringResource(R.string.assistant_keyboard)) }
        }
        // One main button that changes job: talk, send, or stop.
        val job = when {
            state.busy -> ButtonJob.STOP
            !voiceMode && draft.isNotBlank() -> ButtonJob.SEND
            else -> ButtonJob.MIC
        }
        val listening = voice.listening
        val pulse by animateFloatAsState(if (listening) 1f + voice.level() * 0.18f else 1f, spring(stiffness = 300f), label = "micPulse")
        FilledIconButton(
            onClick = { when (job) { ButtonJob.STOP -> onStop(); ButtonJob.SEND -> onSend(); ButtonJob.MIC -> onMic() } },
            enabled = !voice.transcribing,
            colors = if (job == ButtonJob.MIC && !listening) IconButtonDefaults.filledTonalIconButtonColors()
                else IconButtonDefaults.filledIconButtonColors(),
            modifier = Modifier.size(48.dp).graphicsLayer { scaleX = pulse; scaleY = pulse },
        ) {
            AnimatedContent(job to listening, transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.55f, stiffness = 500f)) + fadeIn()) togetherWith (scaleOut() + fadeOut())
            }, label = "assistantButton") { (shown, live) ->
                when (shown) {
                    ButtonJob.STOP -> Icon(Icons.Rounded.Stop, stringResource(R.string.assistant_stop))
                    ButtonJob.SEND -> Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.assistant_send))
                    ButtonJob.MIC -> Icon(if (live) Icons.Rounded.ArrowUpward else Icons.Rounded.Mic,
                        stringResource(if (live) R.string.assistant_send else R.string.assistant_mic))
                }
            }
        }
    }
}

private enum class ButtonJob { MIC, SEND, STOP }

/** What the microphone is doing, in the space where the text field usually sits. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoiceLine(voice: PanelVoice) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            voice.transcribing -> {
                LoadingIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.voice_transcribing), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            }
            voice.heard.isNotBlank() -> Text(voice.heard, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            voice.listening -> Text(stringResource(R.string.assistant_listening), style = MaterialTheme.typography.bodyLarge,
                color = scheme.primary)
            else -> Text(stringResource(R.string.assistant_tap_to_talk), style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant)
        }
    }
}

/** The model that answers here, and how hard it thinks, which the user can change in place. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelLine(state: AssistantUiState, onThinking: (ThinkingLevel) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val thinking = state.thinking
    val levels = thinking?.spec?.levels.orEmpty()
    val pickable = thinking != null && thinking.customLevel == null && levels.size > 1
    var picking by remember { mutableStateOf(false) }
    val chip = when {
        thinking == null || thinking.spec.style == ThinkingSpecs.Style.NONE -> null
        thinking.customLevel != null -> stringResource(R.string.assistant_thinking_level, thinking.label)
        thinking.spec.style == ThinkingSpecs.Style.AUTOMATIC ->
            if (thinking.spec.reasoningSupported) stringResource(R.string.assistant_thinking_auto) else null
        else -> stringResource(R.string.assistant_thinking_level, thinking.label)
    }
    if (state.modelLabel == null && chip == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.modelLabel.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chip != null) {
                val turn by animateFloatAsState(if (picking) 180f else 0f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "thinkingChevron")
                val pickLabel = stringResource(R.string.assistant_thinking_pick)
                Surface(
                    onClick = { picking = !picking },
                    enabled = pickable,
                    shape = CircleShape,
                    color = if (thinking!!.active) scheme.secondaryContainer else scheme.surfaceContainerHighest,
                    contentColor = if (thinking.active) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    modifier = Modifier.semantics { if (pickable) contentDescription = "$chip. $pickLabel" },
                ) {
                    Row(Modifier.padding(start = 10.dp, end = if (pickable) 6.dp else 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(R.drawable.ic_brain), null, Modifier.size(16.dp))
                        Text(chip, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        if (pickable) Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
                    }
                }
            }
        }
        AnimatedVisibility(
            picking && pickable,
            enter = fadeIn(tween(160)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 520f)),
            exit = fadeOut(tween(110)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 620f)),
        ) {
            val options = remember(thinking, levels) { levels.map { it to thinking!!.optionLabel(it) }.distinctBy { it.second } }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (level, label) ->
                    val chosen = level == thinking!!.level ||
                        (thinking.spec.style == ThinkingSpecs.Style.TOGGLE && level != ThinkingLevel.OFF && thinking.level != ThinkingLevel.OFF)
                    ToggleButton(
                        checked = chosen,
                        onCheckedChange = { onThinking(level); picking = false },
                        modifier = Modifier.semantics { role = SemanticsRole.RadioButton },
                        colors = ToggleButtonDefaults.toggleButtonColors(containerColor = scheme.surfaceContainerHighest),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * Rises a just-sent question into place. The motion runs on the clock from when it was
 * sent, so when the saved copy takes over from the pending one it carries on mid-flight.
 */
@Composable
private fun rememberRise(startedAt: Long?): State<Float> {
    val value = remember(startedAt) { mutableFloatStateOf(riseAt(startedAt)) }
    LaunchedEffect(startedAt) {
        while (value.floatValue < 1f) withFrameMillis { value.floatValue = riseAt(startedAt) }
    }
    return value
}

private fun riseAt(startedAt: Long?): Float {
    if (startedAt == null) return 1f
    val t = ((SystemClock.uptimeMillis() - startedAt) / RISE_MS).coerceIn(0f, 1f)
    // Ease out with a small overshoot, like a soft spring settling.
    val x = t - 1f
    return 1f + 2.2f * x * x * x + 1.2f * x * x
}

private const val RISE_MS = 520f

@Composable
private fun QuestionBubble(text: String, startedAt: Long?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val rise = rememberRise(startedAt)
    Column(
        modifier.fillMaxWidth().graphicsLayer {
            val p = rise.value
            translationY = (1f - p) * 40.dp.toPx()
            scaleX = 0.92f + 0.08f * p; scaleY = scaleX
            transformOrigin = TransformOrigin(1f, 1f)
            alpha = (0.2f + 0.8f * p).coerceIn(0f, 1f)
        },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, Modifier.widthIn(max = 320.dp)
            .background(scheme.primaryContainer, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
            color = scheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The question's own words, without the screen note that rode along with it. */
private fun questionText(message: ChatMessage): String =
    FileAttachments.splitForDisplay(UserMessageContent.visibleText(message.text)).first.trim()

/**
 * The conversation, laid out the way Chat lays out a turn: the question, the live
 * activity (thinking, searching, reading), then the answer with its sources and actions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PanelBody(
    state: AssistantUiState,
    settings: AppSettings,
    tts: TtsState,
    controller: AssistantController,
    scroll: ScrollState,
    sentAt: Long?,
    onContentHeight: (Int) -> Unit,
    onQuestionTop: (Int) -> Unit,
    onAsk: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onContinue: () -> Unit,
    onNewQuestion: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val messages = state.messages
    val shown = remember(messages) { messages.filterNot { it.role == Role.ASSISTANT && it.toolCallId != null } }
    val turns = remember(shown) { shown.groupBy { it.turnId } }
    val lastTurn = shown.lastOrNull { it.turnId != null }?.turnId
    val latestQuestionTurn = shown.lastOrNull { it.role == Role.USER }?.turnId
    val streamTurn = state.currentTurnId ?: lastTurn
    val streamSaved = state.streamingMessageId != null && messages.any { it.id == state.streamingMessageId }
    val imageSources = remember(messages) { ChatActivityPresentation.imageSources(messages) }
    val responseImages = remember(messages) { ChatResponseImages.available(messages) }
    val imagesOn = ChatExperience.effectiveImageFrequency(ImageFrequency.DEFAULT, settings.chatImageFrequency) != ImageFrequency.NONE
    val links = remember(onOpenUrl) { object : UriHandler { override fun openUri(uri: String) = onOpenUrl(uri) } }
    // Each new step of a live turn is felt once, with the same haptics setting as Chat.
    val rumble = rememberRumble(settings.rumbleStyle)
    val rumbleOn by rememberUpdatedState(settings.rumbleInChat)
    val onStep = remember(rumble) { { cue: RumbleCue -> if (rumbleOn) rumble(cue) } }
    // The end of an answer, a waiting approval, and the slow breath while prose streams in, as in Chat.
    val breathe = rememberRumbleFlow(settings.rumbleStyle)
    val flowing = state.busy && !state.streamingCommitted && !state.streamingText.isNullOrBlank()
    val visual = flowing && remember(state.streamingText) {
        RichResponse.isBuildingVisual(ChatRichResponse.normalize(state.streamingText.orEmpty(), true))
    }
    RumbleDirector(
        busy = state.busy,
        failed = state.error != null,
        waitingPrompt = state.approval ?: state.question,
        runningCalls = state.runningCalls,
        codeTools = false,
        play = onStep,
        flow = when {
            !flowing || !settings.rumbleFlow || !settings.rumbleInChat -> null
            visual -> RumbleFlow.VISUAL
            else -> RumbleFlow.WRITING
        },
        breathe = breathe,
    )
    // Where the scrolled content sits, so the latest question's place in it can be measured.
    val content = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun Modifier.marksQuestion(): Modifier = onGloballyPositioned { coords ->
        content[0]?.takeIf { it.isAttached }?.let { onQuestionTop(it.localPositionOf(coords, Offset.Zero).y.toInt()) }
    }

    CompositionLocalProvider(
        LocalUriHandler provides links,
        LocalChatImageSources provides imageSources,
        LocalChatImageOpen provides onOpenUrl,
        LocalChatImagesEnabled provides imagesOn,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(scroll)
                .onSizeChanged { onContentHeight(it.height) }
                .onGloballyPositioned { content[0] = it }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModelLine(state, controller::setThinking)

            turns.forEach { (turnId, items) ->
                key(turnId ?: "none") {
                    val running = state.busy && turnId != null && turnId == state.currentTurnId
                    val latest = turnId == latestQuestionTurn
                    items.filter { it.role == Role.USER }.forEach { question ->
                        val text = remember(question.text) { questionText(question) }
                        if (text.isNotEmpty()) QuestionBubble(text, if (latest) sentAt else null,
                            if (latest) Modifier.marksQuestion() else Modifier)
                    }
                    TurnActivity(state, settings, items, running, onOpenUrl, onStep)
                    val final = items.lastOrNull { it.role == Role.ASSISTANT }
                        ?.takeIf { it.text.isNotBlank() && it.toolCalls.isEmpty() && it.id != null }
                    val streaming = state.streamingText
                    val answer = when {
                        turnId == streamTurn && !streamSaved && !streaming.isNullOrBlank() ->
                            Triple(state.streamingMessageId ?: "live", streaming, !state.streamingCommitted)
                        final != null -> Triple(final.id!!, final.text, false)
                        else -> null
                    }
                    answer?.let { (id, text, live) ->
                        key(id) {
                            Answer(
                                id = id, text = text, live = live, done = final != null && final.id == id && !running,
                                turnId = turnId, items = items, final = final, latest = latest, busy = state.busy,
                                responseImages = responseImages, tts = tts, onOpenUrl = onOpenUrl, onAsk = onAsk,
                                onDigDeeper = { controller.digDeeper() }, onSpeak = controller::speak,
                            )
                        }
                    }
                    if (running) state.retryStatus?.let { status ->
                        Text(status, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }

            // The question just asked, until its saved copy arrives, then the run's first steps.
            state.sending?.let { text ->
                key("sending") {
                    QuestionBubble(text, sentAt,
                        Modifier.marksQuestion())
                }
            }
            if (state.busy && (state.currentTurnId == null || state.currentTurnId !in turns.keys)) {
                key("live-activity") { TurnActivity(state, settings, emptyList(), running = true, onOpenUrl, onStep) }
            }

            state.approval?.let { approval ->
                key("approval-${approval.call.id}") {
                    ApprovalCard(approval, onApprove = controller::approve, onDeny = controller::deny)
                }
            }
            state.question?.let { question ->
                key("question-${question.callId}") {
                    QuestionCard(question, onAnswer = controller::answer, autoContinueAt = state.questionAutoAt)
                }
            }

            state.error?.takeIf { !state.busy }?.let { error ->
                Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
                        Text(error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
                        Row(Modifier.align(Alignment.End)) {
                            TextButton(onClick = controller::dismissError) { Text(stringResource(R.string.assistant_close)) }
                            TextButton(onClick = { controller.retry() }) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.assistant_ask_again))
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                state.sessionId != null && !state.busy,
                enter = fadeIn(tween(200, delayMillis = 120)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = 420f)),
                exit = fadeOut(tween(90)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 700f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onContinue) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.assistant_continue_in_app))
                    }
                    TextButton(onClick = onNewQuestion) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.assistant_new_chat))
                    }
                }
            }
        }
    }
}

/** Chat's own disclosure for a turn: live thinking, searches and reads, then a short summary. */
@Composable
private fun TurnActivity(
    state: AssistantUiState,
    settings: AppSettings,
    items: List<ChatMessage>,
    running: Boolean,
    onOpenUrl: (String) -> Unit,
    onStep: (RumbleCue) -> Unit,
) {
    val fresh = running && !state.streamingCommitted
    ChatActivity(
        items, live = running,
        showThinkingMetrics = true,
        thinkingTiming = state.thinkingTiming.takeIf { fresh },
        reasoningTokens = state.reasoningTokens.takeIf { fresh },
        turnStartedAt = state.turnStartedAt.takeIf { running },
        autoExpand = !settings.chatKeepThinkingCollapsed,
        runningCalls = if (running) state.runningCalls else emptyList(),
        streamingThinking = if (fresh) state.streamingThinking.orEmpty() else "",
        writing = running && !state.streamingText.isNullOrBlank(),
        streamId = state.streamingMessageId ?: "live",
        onOpenUrl = onOpenUrl,
        onStep = onStep,
    )
}

/**
 * One answer, live or saved, in a single place so the streaming text turns into the saved
 * one without a blink. Words arrive at Chat's steady pace and fade in; when it is done, the
 * follow-ups, sources and actions unfold under it.
 */
@Composable
private fun Answer(
    id: String,
    text: String,
    live: Boolean,
    done: Boolean,
    turnId: String?,
    items: List<ChatMessage>,
    final: ChatMessage?,
    latest: Boolean,
    busy: Boolean,
    responseImages: Map<String, ImageRef>,
    tts: TtsState,
    onOpenUrl: (String) -> Unit,
    onAsk: (String) -> Unit,
    onDigDeeper: () -> Unit,
    onSpeak: (String, String) -> Unit,
) {
    val paced = rememberPacedText(text, live)
    val reply = remember(paced, live) { ChatRichResponse.normalize(paced, live) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.keepLiveHeight(live, paced.length)) {
            AssistantText(reply, streaming = live, onOpenUrl = onOpenUrl, chatImages = responseImages,
                onFollowUp = { onAsk(it.prompt) })
            if (live) {
                val sources = remember(items) { ChatActivityPresentation.sources(items) }
                LiveSourceChips(sources, turnId, onOpenUrl = onOpenUrl, modifier = Modifier.padding(top = 6.dp))
            }
        }
        AnimatedVisibility(
            done,
            enter = fadeIn(tween(240, delayMillis = 80)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = 380f)),
            exit = fadeOut(tween(90)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 700f)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (latest && final != null) {
                    val followUps = final.generation.followUps
                    val searched = remember(items) { items.any { m -> m.toolCalls.any { it.name == "web_search" } } }
                    val digDeeper: (() -> Unit)? = if (searched && !busy) onDigDeeper else null
                    if (followUps.isNotEmpty() || digDeeper != null) FollowUpChips(followUps, digDeeper) { onAsk(it) }
                }
                PanelSources(items, onOpenUrl)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CopyIconButton(reply)
                    SpeakIconButton(
                        active = tts.messageId == id,
                        preparing = tts.messageId == id && tts.phase == TtsPhase.PREPARING,
                        onClick = { onSpeak(id, reply) },
                    )
                }
            }
        }
    }
}

/** The pages an answer drew on, opened in place instead of in a sheet the panel cannot host. */
@Composable
private fun PanelSources(items: List<ChatMessage>, onOpenUrl: (String) -> Unit) {
    val sources = remember(items) { ChatActivityPresentation.sources(items) }
    if (sources.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Icon(Icons.Rounded.Public, null, Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (open) stringResource(R.string.assistant_sources_hide) else stringResource(R.string.chat_source_count, sources.size),
                style = MaterialTheme.typography.labelMedium)
        }
        AnimatedVisibility(
            open,
            enter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = 460f)),
            exit = fadeOut(tween(110)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 620f)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sources.forEach { source ->
                    Surface(onClick = { onOpenUrl(source.url) }, shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerHighest) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(source.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(source.host, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Before the first question, dragging the pill up shows what it can see. */
@Composable
private fun ScreenPreview(state: AssistantUiState, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(state.screen.appName ?: stringResource(R.string.assistant_screen_this_screen),
            style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}

private fun lerp(a: Dp, b: Dp, t: Float): Dp = Dp(lerp(a.value, b.value, t))
