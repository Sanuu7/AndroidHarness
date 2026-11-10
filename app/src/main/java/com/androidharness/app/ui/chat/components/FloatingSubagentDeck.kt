package com.androidharness.app.ui.chat.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.agent.RunManager
import com.androidharness.app.agent.SubagentRun
import com.androidharness.app.agent.SubagentStatus
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.expressiveDefaultSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The anchor belongs ABOVE all composer accessories, not immediately above the text field. */
@Composable
internal fun FloatingSubagentDock(
    sessionId: String?,
    manager: RunManager,
    onOpen: (String) -> Unit,
    composerText: String = "",
) {
    if (sessionId == null) return
    val source = remember(sessionId, manager) { manager.subagents.forSession(sessionId) }
    val runs by source.collectAsStateWithLifecycle(initialValue = emptyList())
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var selectedId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var previousText by remember(sessionId) { mutableStateOf(composerText) }
    var anchorTop by remember { mutableIntStateOf(0) }
    var lifecycle by remember(sessionId) { mutableStateOf(SubagentBubbleLifecycle()) }
    var expiryTick by remember(sessionId) { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val store = remember(context.applicationContext) { SubagentBubblePositionStore(context) }
    var savedPosition by remember(sessionId, store) { mutableStateOf(store.read()) }
    var placement by remember(sessionId) { mutableStateOf<SubagentBubblePlacement?>(null) }
    fun collapse() { expanded = false; selectedId = null }
    LaunchedEffect(runs) { lifecycle = lifecycle.update(runs, android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(lifecycle.finishedAt) {
        lifecycle.finishedAt?.let { finished ->
            delay((finished + SubagentBubbleLifecycle.COMPLETION_MS - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L))
            expiryTick = android.os.SystemClock.uptimeMillis()
        }
    }
    val visible = lifecycle.visible(maxOf(expiryTick, android.os.SystemClock.uptimeMillis()), expanded || dragging)
    LaunchedEffect(visible) {
        if (!visible) {
            delay(200)
            if (lifecycle.active.isEmpty() && !dragging) { lifecycle = SubagentBubbleLifecycle(); collapse() }
        }
    }
    val popupVisibility = remember(sessionId) { MutableTransitionState(false) }
    popupVisibility.targetState = visible
    val shown = runs.filter { it.id in lifecycle.tracked }
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(keyboardOpen) { if (keyboardOpen) collapse() }
    LaunchedEffect(composerText) {
        if (previousText != composerText) collapse()
        previousText = composerText
    }
    BackHandler(enabled = expanded) { collapse() }
    val gap = with(density) { 16.dp.roundToPx() }
    val edge = with(density) { 16.dp.roundToPx() }
    val bubbleSize = subagentButtonSize()
    // A recreated Compose host can expose consumed insets; the popup needs the window's raw safe edge.
    val topInset = maxOf(WindowInsets.safeDrawing.getTop(density), ViewCompat.getRootWindowInsets(LocalView.current)
        ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0)
    val position = remember(sessionId, gap, edge, bubbleSize, topInset) {
        SubagentBubblePositionProvider(gap, edge, bubbleSize, topInset, { savedPosition }) { placement = it }
    }
    val panelHeight = with(density) { (placement?.maxPanelHeight ?: 0).toDp() }.coerceAtLeast(0.dp)
    Box(Modifier.fillMaxWidth().height(0.dp).onGloballyPositioned { anchorTop = it.positionInWindow().y.toInt() }) {
        // IME panning can move the text field independently of this popup window.
        // Restore the bubble after typing, rather than drawing over the input controls.
        if (!keyboardOpen && anchorTop >= with(density) { 64.dp.roundToPx() } &&
            (popupVisibility.currentState || popupVisibility.targetState || !popupVisibility.isIdle)) {
            Popup(popupPositionProvider = position, onDismissRequest = { collapse() },
                properties = PopupProperties(focusable = false, dismissOnBackPress = true,
                    dismissOnClickOutside = expanded, clippingEnabled = true, usePlatformDefaultWidth = false)) {
                CompositionLocalProvider(LocalDensity provides density) {
                    AnimatedVisibility(popupVisibility,
                        enter = fadeIn(expressiveEffectsSpec()) + scaleIn(expressiveSpatialSpec(), initialScale = .55f),
                        exit = fadeOut(tween(180)) + scaleOut(tween(180, easing = FastOutLinearInEasing), targetScale = .8f)) {
                    FloatingSubagentDeck(shown, expanded, { if (expanded) collapse() else expanded = true },
                        onOpen = onOpen, onStop = { manager.subagents.stop(sessionId, it) },
                        selectedId = selectedId, onSelected = { selectedId = it }, maxPanelHeight = panelHeight,
                        bubbleOffset = { width -> placement?.bubbleOffset(width) ?: 0 }, panelAbove = placement?.panelAbove ?: true,
                        onDragStart = { dragging = true; collapse() },
                        onDrag = { delta -> placement?.let { savedPosition = savedPosition.moved(delta, it.bounds) } },
                        onDragEnd = { store.save(savedPosition); dragging = false }, buttonSize = bubbleSize)
                    }
                }
            }
        }
    }
}

internal data class SubagentBubblePlacement(val bounds: IntRect, val bubbleX: Int, val windowWidth: Int,
    val bubbleWidth: Int, val panelAbove: Boolean, val maxPanelHeight: Int) {
    fun popupX(width: Int) = (bubbleX + bubbleWidth - width).coerceIn(0, (windowWidth - width).coerceAtLeast(0))
    fun bubbleOffset(width: Int) = bubbleX - popupX(width)
}

/** Position the panel around the saved bubble without moving its touch target. */
internal class SubagentBubblePositionProvider(private val gap: Int, private val edge: Int, private val bubbleSize: IntSize = IntSize(48, 48),
    private val topInset: Int = 0, private val saved: () -> SubagentBubblePosition = { SubagentBubblePosition() },
    private val onPlaced: (SubagentBubblePlacement) -> Unit = {}) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = (anchorBounds.left + edge).coerceIn(0, (windowSize.width - bubbleSize.width).coerceAtLeast(0))
        val right = (minOf(anchorBounds.right, windowSize.width) - edge - bubbleSize.width).coerceAtLeast(left)
        val top = (topInset + edge).coerceIn(0, (windowSize.height - bubbleSize.height).coerceAtLeast(0))
        val bottom = (minOf(anchorBounds.top - gap, windowSize.height - edge) - bubbleSize.height).coerceAtLeast(top)
        val bounds = IntRect(left, top, right, bottom)
        val fraction = saved().normalized()
        val x = (left + bounds.width * fraction.x).roundToInt()
        val y = (top + bounds.height * fraction.y).roundToInt()
        val above = y - top >= bottom - y
        val placement = SubagentBubblePlacement(bounds, x, windowSize.width, bubbleSize.width, above,
            (if (above) y - top else bottom - y) - gap)
        onPlaced(placement)
        return IntOffset(placement.popupX(popupContentSize.width), if (above) (y + bubbleSize.height - popupContentSize.height).coerceAtLeast(0) else y)
    }
}

@Composable
private fun subagentButtonSize(): IntSize {
    val density = LocalDensity.current
    val label = stringResource(R.string.subagents_deck_title)
    val text = rememberTextMeasurer().measure(AnnotatedString(label), style = MaterialTheme.typography.labelLarge, maxLines = 1).size
    return with(density) {
        val maxWidth = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceIn(48.dp, 320.dp).roundToPx()
        IntSize((text.width + 28.dp.roundToPx()).coerceIn(48.dp.roundToPx(), maxWidth),
            maxOf(48.dp.roundToPx(), text.height + 16.dp.roundToPx()))
    }
}

/** The collapsed popup fits its text button; it never reserves an invisible full-width bar. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FloatingSubagentDeck(
    runs: List<SubagentRun>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    selectedId: String? = null,
    onSelected: (String?) -> Unit = {},
    maxPanelHeight: Dp = 380.dp,
    modifier: Modifier = Modifier,
    bubbleOffset: ((Int) -> Int)? = null,
    panelAbove: Boolean = true,
    onDragStart: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    buttonSize: IntSize? = null,
) {
    if (runs.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val working = runs.count { it.status == SubagentStatus.RUNNING }
    val queued = runs.count { it.status == SubagentStatus.QUEUED }
    val summary = when {
        working > 0 -> stringResource(R.string.subagents_running_count, working)
        queued > 0 -> stringResource(R.string.subagents_queued_count, queued)
        runs.any { it.status == SubagentStatus.FAILED } -> stringResource(R.string.subagents_failed)
        runs.any { it.status == SubagentStatus.INTERRUPTED } -> stringResource(R.string.subagents_interrupted)
        runs.any { it.status == SubagentStatus.STOPPED } -> stringResource(R.string.subagents_stopped)
        else -> stringResource(R.string.subagents_all_finished)
    }
    val selected = runs.firstOrNull { it.id == selectedId }
    val controlSize = buttonSize ?: subagentButtonSize()
    val density = LocalDensity.current
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val width = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceIn(0.dp, 320.dp)
    // Panel and bubble borders share one clock: an arc runs around both while any agent is working.
    val phase = remember { Animatable(0f) }
    val shimmer = animateFloatAsState(if (working > 0) 1f else 0f, tween(450), label = "Subagent outline shimmer")
    LaunchedEffect(working > 0) {
        if (working > 0) {
            phase.snapTo(0f)
            phase.animateTo(1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart))
        }
    }
    val outline = scheme.outline.copy(alpha = .55f)
    val sizeSpec = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val panel: @Composable () -> Unit = {
        val origin = TransformOrigin(1f, if (panelAbove) 1f else 0f)
        val anchor = if (panelAbove) Alignment.Bottom else Alignment.Top
        AnimatedVisibility(expanded && maxPanelHeight >= 80.dp,
            enter = expandVertically(sizeSpec, expandFrom = anchor) + fadeIn(expressiveEffectsSpec()) +
                scaleIn(expressiveDefaultSpatialSpec(), initialScale = .86f, transformOrigin = origin),
            exit = shrinkVertically(tween(160, easing = FastOutLinearInEasing), shrinkTowards = anchor) + fadeOut(tween(140)) +
                scaleOut(tween(160, easing = FastOutLinearInEasing), targetScale = .92f, transformOrigin = origin)) {
            Column(horizontalAlignment = AbsoluteAlignment.Right) {
                if (!panelAbove) Spacer(Modifier.height(12.dp))
                val panelShape = RoundedCornerShape(22.dp)
                Surface(Modifier.width(width).heightIn(max = maxPanelHeight).shimmerOutline(panelShape, outline, scheme.primary,
                    arcs = 2, intensity = { shimmer.value }, phase = { (phase.value + .5f) % 1f }),
                    shape = panelShape, color = scheme.surfaceContainerHigh, shadowElevation = 9.dp) {
                    AnimatedContent(targetState = selected?.id, label = "Subagent deck page",
                        transitionSpec = { pageTransition(forward = initialState == null, sizeSpec) }) { pageId ->
                        val page = runs.firstOrNull { it.id == pageId }
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 7.dp, end = 7.dp, top = 5.dp, bottom = 9.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 45.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (page != null) IconButton(onClick = { onSelected(null) }, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.subagents_back_to_list), Modifier.size(19.dp))
                                } else Spacer(Modifier.width(8.dp))
                                Text(page?.name ?: stringResource(R.string.subagents_deck_title),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (page == null) AnimatedContent(summary, modifier = Modifier.padding(start = 6.dp), label = "Subagent summary",
                                    transitionSpec = {
                                        (slideInVertically(expressiveDefaultSpatialSpec()) { it / 2 } + fadeIn(expressiveEffectsSpec())) togetherWith
                                            (slideOutVertically(expressiveEffectsSpec()) { -it / 2 } + fadeOut(tween(90)))
                                    }) { text ->
                                    Text(text, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                }
                                IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.Remove, stringResource(R.string.subagents_collapse), Modifier.size(19.dp), tint = scheme.onSurfaceVariant)
                                }
                            }
                            if (page == null) Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                runs.forEachIndexed { index, run ->
                                    key(run.id) { SubagentBubbleRow(run, index, onClick = { onSelected(run.id) }) }
                                }
                            } else {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val preview = (if (page.status.active) page.preview else page.output).trim()
                                    Text(preview.ifBlank { page.prompt }, style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurface, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        SubagentBubbleStatus(page.status)
                                        Text(if (page.status.active) stringResource(R.string.subagents_running_background)
                                            else subagentStatusText(page.status), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                    }
                                    // Keep full conversations and cancellation available inside the requested details view.
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { onOpen(page.id) }) { Text(stringResource(R.string.subagents_open_chat)) }
                                        Spacer(Modifier.weight(1f))
                                        if (page.status.active) TextButton(onClick = { onStop(page.id) }) {
                                            Text(stringResource(R.string.subagents_stop))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (panelAbove) Spacer(Modifier.height(12.dp))
            }
        }
    }
    val bubble: @Composable () -> Unit = {
        val label = stringResource(if (expanded) R.string.subagents_collapse else R.string.subagents_expand)
        val title = stringResource(R.string.subagents_deck_title)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        var lifted by remember { mutableStateOf(false) }
        // Squish on press, rise while dragged, and square off a little while the panel is open.
        val scale = animateFloatAsState(when { lifted -> 1.08f; pressed -> .93f; else -> 1f }, expressiveSpatialSpec(), label = "Subagent bubble scale")
        val corner by animateDpAsState(if (expanded) 16.dp else 24.dp, expressiveSpatialSpec(), label = "Subagent bubble corner")
        val elevation by animateDpAsState(if (lifted) 10.dp else 3.dp, expressiveEffectsSpec(), label = "Subagent bubble elevation")
        val edge by animateColorAsState(if (expanded) scheme.primary.copy(alpha = .8f) else outline, expressiveEffectsSpec(), label = "Subagent bubble edge")
        val bubbleShape = RoundedCornerShape(corner)
        Box(Modifier.size(with(density) { controlSize.width.toDp() }, with(density) { controlSize.height.toDp() })
            .pointerInput(Unit) { detectDragGestures(onDragStart = { lifted = true; dragStart() }, onDragEnd = { lifted = false; dragEnd() },
                onDragCancel = { lifted = false; dragEnd() }, onDrag = { change, delta -> change.consume(); dragMove(delta) }) }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = summary
                role = Role.Button
                onClick(label = label) { onToggle(); true }
            },
            contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .shimmerOutline(bubbleShape, edge, scheme.primary, intensity = { shimmer.value }, phase = { phase.value }),
                shape = bubbleShape, color = scheme.surfaceContainerHighest, shadowElevation = elevation) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    ChatShimmerText(title, running = working > 0)
                }
            }
        }
    }
    // Measure the bubble offset with the panel in the same layout pass. Recomposition after
    // popup placement otherwise lets a growing panel push a dragged bubble sideways for a frame.
    Layout(modifier = modifier, content = { Box { panel() }; bubble() }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val panelPlaceable = measurables[0].measure(loose)
        val bubblePlaceable = measurables[1].measure(loose)
        val width = constraints.constrainWidth(maxOf(panelPlaceable.width, bubblePlaceable.width))
        val height = constraints.constrainHeight(panelPlaceable.height + bubblePlaceable.height)
        layout(width, height) {
            panelPlaceable.place(width - panelPlaceable.width, if (panelAbove) 0 else bubblePlaceable.height)
            bubblePlaceable.place(bubbleOffset?.invoke(width) ?: (width - bubblePlaceable.width), if (panelAbove) panelPlaceable.height else 0)
        }
    }
}

/** List to detail slides in from the right, and back out the other way. */
private fun pageTransition(forward: Boolean, sizeSpec: FiniteAnimationSpec<IntSize>): ContentTransform {
    val direction = if (forward) 1 else -1
    return ContentTransform(
        targetContentEnter = fadeIn(expressiveEffectsSpec()) + slideInHorizontally(expressiveDefaultSpatialSpec()) { it / 5 * direction },
        initialContentExit = fadeOut(tween(90)) + slideOutHorizontally(expressiveEffectsSpec()) { -it / 5 * direction },
        sizeTransform = SizeTransform(clip = true) { _, _ -> sizeSpec },
    )
}

/** Rows rise and settle one after another when the list opens. */
@Composable
private fun Modifier.staggerIn(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40L + index.coerceAtMost(6) * 45L)
        progress.animateTo(1f, expressiveDefaultSpatialSpec())
    }
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * rise
        val s = .92f + .08f * p
        scaleX = s
        scaleY = s
    }
}

@Composable
private fun SubagentBubbleRow(run: SubagentRun, index: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val running = run.status == SubagentStatus.RUNNING
    val activity = run.steps.lastOrNull { !it.startsWith("Task") && !it.startsWith("Model:") }
        ?: if (run.status.active) run.prompt else subagentStatusText(run.status)
    Surface(onClick = onClick, color = scheme.surfaceContainerHighest, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .7f)),
        modifier = Modifier.fillMaxWidth().staggerIn(index)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            SubagentChip(running)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(run.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChatShimmerText(activity, running = running, style = MaterialTheme.typography.labelSmall)
            }
            SubagentBubbleStatus(run.status)
        }
    }
}

/** A slowly turning cookie while the agent works, a calm circle otherwise. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SubagentChip(running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Crossfade(running, animationSpec = expressiveEffectsSpec(), label = "Subagent chip") { active ->
            if (active) {
                val spin = rememberInfiniteTransition(label = "Subagent chip spin")
                    .animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "Subagent chip angle")
                Box(Modifier.size(32.dp).graphicsLayer { rotationZ = spin.value }
                    .background(scheme.primaryContainer, MaterialShapes.Cookie6Sided.toShape()))
            } else {
                Box(Modifier.size(32.dp).background(scheme.secondaryContainer, CircleShape))
            }
        }
        Icon(Icons.Outlined.Search, null, Modifier.size(16.dp),
            tint = if (running) scheme.onPrimaryContainer else scheme.onSecondaryContainer)
    }
}

@Composable
private fun SubagentBubbleStatus(status: SubagentStatus) {
    val color = when (status) {
        SubagentStatus.RUNNING, SubagentStatus.COMPLETED -> LocalStatusColors.current.success
        SubagentStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (status) {
            SubagentStatus.COMPLETED -> {
                val pop = remember { Animatable(0f) }
                LaunchedEffect(Unit) { pop.animateTo(1f, expressiveSpatialSpec()) }
                Icon(Icons.Default.Check, null, Modifier.size(14.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = color)
            }
            SubagentStatus.RUNNING -> {
                val pulse = rememberInfiniteTransition(label = "Subagent status pulse")
                    .animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearOutSlowInEasing)), label = "Subagent status ring")
                Box(Modifier.size(6.dp).graphicsLayer {
                    val grow = 1f + 1.8f * pulse.value
                    scaleX = grow
                    scaleY = grow
                    alpha = (1f - pulse.value) * .55f
                }.background(color, CircleShape))
                Box(Modifier.size(6.dp).background(color, CircleShape))
            }
            else -> Box(Modifier.size(6.dp).background(color, CircleShape))
        }
    }
}

@Composable
internal fun subagentStatusText(status: SubagentStatus): String = stringResource(when (status) {
    SubagentStatus.QUEUED -> R.string.subagents_queued
    SubagentStatus.RUNNING -> R.string.subagents_working
    SubagentStatus.COMPLETED -> R.string.subagents_completed
    SubagentStatus.FAILED -> R.string.subagents_failed
    SubagentStatus.STOPPED -> R.string.subagents_stopped
    SubagentStatus.INTERRUPTED -> R.string.subagents_interrupted
})
