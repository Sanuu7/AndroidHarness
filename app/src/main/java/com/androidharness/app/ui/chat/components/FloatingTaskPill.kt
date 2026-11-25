package com.androidharness.app.ui.chat.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.androidharness.app.R
import com.androidharness.app.agent.TodoItem
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.expressiveDefaultSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** When the floating task list opens by itself, and for how long. */
internal object TaskPeek {
    const val CREATED_MS = 5_000L
    /** How long the pill names a task that just finished before it moves on to the next one. */
    const val ANNOUNCE_MS = 2_200L
    /** A finished list stays on screen this long before the pill leaves. */
    const val DONE_MS = 3_500L

    /** Only a new list opens by itself; later changes animate the pill instead. */
    fun duration(before: List<TodoItem>, after: List<TodoItem>): Long? =
        if (before.isEmpty() && after.isNotEmpty()) CREATED_MS else null

    /** True for a change to a list that was already showing. */
    fun isUpdate(before: List<TodoItem>, after: List<TodoItem>): Boolean =
        before.isNotEmpty() && after.isNotEmpty() && before != after

    /** The task this update finished, if any: the last one that turned completed. */
    fun finished(before: List<TodoItem>, after: List<TodoItem>): String? =
        if (before.isEmpty()) null else after.indices.lastOrNull { i ->
            after[i].status == TodoItem.Status.COMPLETED && before.getOrNull(i)?.let {
                it.content == after[i].content && it.status != TodoItem.Status.COMPLETED
            } == true
        }?.let { after[it].content }

    /** Rows that are new or changed status, so the open list can point them out. */
    fun changed(before: List<TodoItem>, after: List<TodoItem>): Set<Int> =
        if (before.isEmpty()) emptySet() else after.indices.filter { before.getOrNull(it) != after[it] }.toSet()
}

/**
 * The agent's task list as a draggable floating pill above the composer. It
 * opens by itself for 5s when the agent writes a new list, then folds back
 * into the pill. Later changes stay in the pill: the ring bounces, a finished
 * task flashes a check with its name, and the label ticks over to the next
 * task. A tap opens or closes the list, and touching the open list keeps it
 * open until the pill is tapped again.
 */
@Composable
internal fun FloatingTaskDock(sessionKey: String?, todos: List<TodoItem>) {
    if (sessionKey == null) return
    var shownTodos by remember(sessionKey) { mutableStateOf(todos) }
    // Reopening a chat shows its list quietly; only changes from here on open it.
    var previous by remember(sessionKey) { mutableStateOf(todos) }
    var changed by remember(sessionKey) { mutableStateOf(emptySet<Int>()) }
    var changeId by remember(sessionKey) { mutableIntStateOf(0) }
    var peekUntil by remember(sessionKey) { mutableLongStateOf(0L) }
    var bump by remember(sessionKey) { mutableIntStateOf(0) }
    var announce by remember(sessionKey) { mutableStateOf<TaskAnnouncement?>(null) }
    var pinned by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var peeking by remember(sessionKey) { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val allDone = todos.isNotEmpty() && todos.all { it.status == TodoItem.Status.COMPLETED }
    var lingering by remember(sessionKey) { mutableStateOf(false) }
    val now = { android.os.SystemClock.uptimeMillis() }

    LaunchedEffect(todos) {
        if (todos.isNotEmpty()) shownTodos = todos
        TaskPeek.duration(previous, todos)?.let { span -> peekUntil = now() + span }
        if (TaskPeek.isUpdate(previous, todos)) {
            changed = TaskPeek.changed(previous, todos)
            changeId++
            bump++
            TaskPeek.finished(previous, todos)?.let { announce = TaskAnnouncement(it, bump) }
        }
        previous = todos
    }
    LaunchedEffect(announce) {
        if (announce == null) return@LaunchedEffect
        delay(TaskPeek.ANNOUNCE_MS)
        announce = null
    }
    LaunchedEffect(peekUntil) {
        if (peekUntil <= now()) return@LaunchedEffect
        peeking = true
        delay(peekUntil - now())
        peeking = false
    }
    // A finished list says so for a moment, then the pill leaves.
    LaunchedEffect(allDone, peekUntil) {
        if (!allDone) { lingering = false; return@LaunchedEffect }
        lingering = true
        delay(maxOf(peekUntil - now(), 0L) + (if (announce != null) TaskPeek.ANNOUNCE_MS else 0L) + TaskPeek.DONE_MS)
        lingering = false
        pinned = false
    }
    val expanded = (pinned || peeking) && !dragging
    // A tap on the pill while the list is open reaches the list's window first as a tap
    // outside, which already closes it; the pill must not open it again right after.
    var dismissedAt by remember { mutableLongStateOf(0L) }
    fun toggle() {
        if (expanded) { pinned = false; peeking = false }
        else if (now() - dismissedAt > 400) pinned = true
    }
    val visible = todos.isNotEmpty() && (!allDone || lingering || pinned)

    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(keyboardOpen) { if (keyboardOpen) { pinned = false; peeking = false } }
    BackHandler(enabled = expanded) { pinned = false; peeking = false }

    val context = LocalContext.current
    val store = remember(context.applicationContext) {
        SubagentBubblePositionStore(context, name = "task-pill-position", default = SubagentBubblePosition(1f, .78f))
    }
    var savedPosition by remember(sessionKey, store) { mutableStateOf(store.read()) }
    var placement by remember(sessionKey) { mutableStateOf<SubagentBubblePlacement?>(null) }
    var anchorTop by remember { mutableIntStateOf(0) }
    val popupVisibility = remember(sessionKey) { MutableTransitionState(false) }
    popupVisibility.targetState = visible && !keyboardOpen

    val active = shownTodos.firstOrNull { it.status == TodoItem.Status.IN_PROGRESS }
        ?: shownTodos.firstOrNull { it.status == TodoItem.Status.PENDING }
    val finishedTask = announce?.text
    val allCompleted = stringResource(R.string.ui_all_tasks_completed)
    val label = finishedTask ?: if (allDone || active == null) allCompleted else active.content
    // One width for the whole list, fitting its longest name: a pill window that changes size
    // has to move to stay in place, and that move shows as a shake.
    val pillSize = taskPillSize(shownTodos.map { it.content } + allCompleted)
    val gap = with(density) { 16.dp.roundToPx() }
    val edge = with(density) { 16.dp.roundToPx() }
    val topInset = maxOf(WindowInsets.safeDrawing.getTop(density), ViewCompat.getRootWindowInsets(LocalView.current)
        ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0)
    val position = remember(sessionKey, gap, edge, pillSize, topInset) {
        SubagentBubblePositionProvider(gap, edge, pillSize, topInset, { savedPosition }) { placement = it }
    }
    val panelHeight = with(density) { (placement?.maxPanelHeight ?: 0).toDp() }.coerceAtLeast(0.dp)

    Box(Modifier.fillMaxWidth().height(0.dp).onGloballyPositioned { anchorTop = it.positionInWindow().y.toInt() }) {
        if (anchorTop >= with(density) { 64.dp.roundToPx() } &&
            (popupVisibility.currentState || popupVisibility.targetState || !popupVisibility.isIdle)) {
            // The pill's window keeps one size. The open list lives in a window of its own,
            // so neither window has to move while the list animates.
            Popup(popupPositionProvider = position, onDismissRequest = { pinned = false; peeking = false },
                properties = PopupProperties(focusable = false, dismissOnBackPress = true,
                    dismissOnClickOutside = false, clippingEnabled = true, usePlatformDefaultWidth = false)) {
                CompositionLocalProvider(LocalDensity provides density) {
                    AnimatedVisibility(popupVisibility,
                        enter = fadeIn(expressiveEffectsSpec()) + scaleIn(expressiveSpatialSpec(), initialScale = .55f),
                        exit = fadeOut(tween(180)) + scaleOut(tween(180, easing = FastOutLinearInEasing), targetScale = .8f)) {
                        FloatingTaskPill(
                            todos = shownTodos, label = label, expanded = expanded,
                            changed = changed, changeId = changeId, pillSize = pillSize,
                            bump = bump, finishedTask = finishedTask != null,
                            onToggle = { toggle() },
                            onTouchList = { pinned = true },
                            maxPanelHeight = panelHeight,
                            pillOffset = { width -> placement?.bubbleOffset(width) ?: 0 },
                            panelAbove = placement?.panelAbove ?: true,
                            onDragStart = { dragging = true; pinned = false; peeking = false },
                            onDrag = { delta -> placement?.let { savedPosition = savedPosition.moved(delta, it.bounds) } },
                            onDragEnd = { store.save(savedPosition); dragging = false },
                            showPanel = false,
                        )
                    }
                }
            }
            FloatingPanelPopup(expanded && popupVisibility.targetState, placement, taskPanelWidth(), panelHeight,
                dismissOnClickOutside = pinned, onDismiss = { dismissedAt = now(); pinned = false; peeking = false }) { open ->
                CompositionLocalProvider(LocalDensity provides density) {
                    FloatingTaskPill(
                        todos = shownTodos, label = label, expanded = open,
                        changed = changed, changeId = changeId, pillSize = pillSize,
                        bump = bump, finishedTask = finishedTask != null,
                        onToggle = { toggle() },
                        onTouchList = { pinned = true },
                        maxPanelHeight = panelHeight,
                        pillOffset = { 0 },
                        panelAbove = placement?.panelAbove ?: true,
                        onDragStart = {}, onDrag = {}, onDragEnd = {},
                        showPill = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun taskPillSize(labels: List<String>): IntSize {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
    val widest = labels.maxOf { measurer.measure(AnnotatedString(it), style = style, maxLines = 1).size.width }
    val maxWidth = (LocalConfiguration.current.screenWidthDp.dp - 32.dp - 57.dp).coerceIn(100.dp, 160.dp)
    return with(density) {
        val textWidth = widest.coerceAtMost(maxWidth.roundToPx())
        IntSize(5.dp.roundToPx() + RING.roundToPx() + 8.dp.roundToPx() + textWidth + 14.dp.roundToPx(), 40.dp.roundToPx())
    }
}

/** The open task list is a little narrower than the subagent panel, so it covers less of the chat. */
@Composable
private fun taskPanelWidth(): Dp = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceIn(0.dp, 280.dp)

private val RING = 30.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FloatingTaskPill(
    todos: List<TodoItem>,
    label: String,
    expanded: Boolean,
    changed: Set<Int>,
    changeId: Int,
    pillSize: IntSize,
    bump: Int,
    finishedTask: Boolean,
    onToggle: () -> Unit,
    onTouchList: () -> Unit,
    maxPanelHeight: Dp,
    pillOffset: (Int) -> Int,
    panelAbove: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    showPanel: Boolean = true,
    showPill: Boolean = true,
) {
    if (todos.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val density = LocalDensity.current
    val done = todos.count { it.status == TodoItem.Status.COMPLETED }
    val allDone = done == todos.size
    val summary = stringResource(R.string.ui_of_completed, done.toString(), todos.size.toString())
    val headerSummary = stringResource(R.string.task_list_header_count, done, todos.size)
    val progress by animateFloatAsState(done.toFloat() / todos.size, expressiveSpatialSpec(), label = "Task ring progress")
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val touchList by rememberUpdatedState(onTouchList)
    val width = taskPanelWidth()
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
                if (!panelAbove) Spacer(Modifier.height(8.dp))
                Surface(Modifier.width(width).heightIn(max = maxPanelHeight)
                    // Any touch on the open list keeps it open; the pill closes it.
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); touchList() } },
                    shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh, shadowElevation = 9.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 6.dp)) {
                        // One line of header: the title and the count, then the collapse button.
                        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AnimatedContent(headerSummary, Modifier.weight(1f), label = "Task summary", transitionSpec = {
                                (slideInVertically(expressiveDefaultSpatialSpec()) { it / 2 } + fadeIn(expressiveEffectsSpec())) togetherWith
                                    (slideOutVertically(expressiveEffectsSpec()) { -it / 2 } + fadeOut(tween(90)))
                            }) { text -> Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                                color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Remove, stringResource(R.string.ui_collapse_task_list), Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                            }
                        }
                        Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            todos.forEachIndexed { index, todo ->
                                key(index, todo.content) {
                                    TaskRow(todo, index, first = index == 0, last = index == todos.lastIndex,
                                        changed = index in changed, changeId = changeId)
                                }
                            }
                        }
                    }
                }
                if (panelAbove) Spacer(Modifier.height(8.dp))
            }
        }
    }

    val pill: @Composable () -> Unit = {
        val toggleLabel = stringResource(if (expanded) R.string.ui_collapse_task_list else R.string.ui_expand_task_list)
        val title = stringResource(R.string.ui_task_list)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        var lifted by remember { mutableStateOf(false) }
        // Squish on press, rise while dragged, square off a little while open.
        val scale = animateFloatAsState(when { lifted -> 1.08f; pressed -> .93f; else -> 1f }, expressiveSpatialSpec(), label = "Task pill scale")
        // The curve settles without overshoot; a bouncy corner reads as the pill wobbling.
        val corner by animateDpAsState(if (expanded) 14.dp else 20.dp,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "Task pill corner")
        val elevation by animateDpAsState(if (lifted) 10.dp else 3.dp, expressiveEffectsSpec(), label = "Task pill elevation")
        val edge by animateColorAsState(if (expanded) scheme.primary.copy(alpha = .8f) else scheme.outline.copy(alpha = .45f),
            expressiveEffectsSpec(), label = "Task pill edge")
        val shape = RoundedCornerShape(corner)
        Box(Modifier.size(with(density) { pillSize.width.toDp() }, with(density) { pillSize.height.toDp() })
            .pointerInput(Unit) { detectDragGestures(onDragStart = { lifted = true; dragStart() }, onDragEnd = { lifted = false; dragEnd() },
                onDragCancel = { lifted = false; dragEnd() }, onDrag = { change, delta -> change.consume(); dragMove(delta) }) }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = "$summary. $label"
                role = Role.Button
                onClick(label = toggleLabel) { onToggle(); true }
            },
            contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .border(1.dp, edge, shape),
                shape = shape, color = scheme.surfaceContainerHighest, shadowElevation = elevation) {
                Row(Modifier.padding(start = 5.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskRing(progress, done, todos.size, allDone, finishedTask, bump)
                    AnimatedContent(label, Modifier.weight(1f), label = "Task pill label", transitionSpec = {
                        (slideInVertically(expressiveDefaultSpatialSpec()) { it } + fadeIn(expressiveEffectsSpec())) togetherWith
                            (slideOutVertically(expressiveEffectsSpec()) { -it } + fadeOut(tween(90))) using
                            SizeTransform(clip = true) { _, _ -> sizeSpec }
                    }) { text ->
                        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                            color = if (allDone || finishedTask) success else scheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    // Measure pill and panel in one pass so a growing panel never nudges a dragged pill sideways.
    Layout(content = { Box { if (showPanel) panel() }; Box { if (showPill) pill() } }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val panelPlaceable = measurables[0].measure(loose)
        val pillPlaceable = measurables[1].measure(loose)
        val w = constraints.constrainWidth(maxOf(panelPlaceable.width, pillPlaceable.width))
        val h = constraints.constrainHeight(panelPlaceable.height + pillPlaceable.height)
        layout(w, h) {
            panelPlaceable.place(w - panelPlaceable.width, if (panelAbove) 0 else pillPlaceable.height)
            pillPlaceable.place(pillOffset(w), if (panelAbove) panelPlaceable.height else 0)
        }
    }
}

/** A finished task the pill names for a moment; [id] makes a repeat name start over. */
internal data class TaskAnnouncement(val text: String, val id: Int)

private enum class RingFace { PROGRESS, JUST_FINISHED, ALL_DONE }

/**
 * Wavy ring that fills as tasks finish, with the count inside. Each update
 * bounces it and sends a ring of color outward; a finished task pops a check
 * in before the count rolls over, and a finished list turns into a check cookie.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TaskRing(progress: Float, done: Int, total: Int, allDone: Boolean, justFinished: Boolean, bump: Int) {
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val bounce = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val twist = remember { Animatable(0f) }
    LaunchedEffect(bump) {
        if (bump == 0) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch {
                bounce.animateTo(1.22f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh))
                bounce.animateTo(1f, spring(dampingRatio = .38f, stiffness = Spring.StiffnessMediumLow))
            }
            launch { burst.snapTo(0f); burst.animateTo(1f, tween(650, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
            // The ring winds back a little and springs forward as it fills.
            launch { twist.snapTo(-50f); twist.animateTo(0f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessLow)) }
        }
    }
    val face = when { allDone && !justFinished -> RingFace.ALL_DONE; justFinished -> RingFace.JUST_FINISHED; else -> RingFace.PROGRESS }
    val accent = if (justFinished || allDone) success else scheme.primary
    Box(Modifier.size(RING), contentAlignment = Alignment.Center) {
        // A ring of color that rises out of the dial and fades on every update.
        Box(Modifier.size(RING).graphicsLayer {
            val p = burst.value
            val grow = 1f + .55f * p
            scaleX = grow; scaleY = grow
            alpha = if (p >= 1f) 0f else (1f - p) * .6f
        }.border(2.dp, accent, CircleShape))
        Box(Modifier.size(RING).graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }, contentAlignment = Alignment.Center) {
            AnimatedContent(face, label = "Task ring", transitionSpec = {
                (scaleIn(expressiveSpatialSpec(), initialScale = .3f) + fadeIn(expressiveEffectsSpec())) togetherWith
                    (scaleOut(tween(140), targetScale = .6f) + fadeOut(tween(120)))
            }) { state ->
                when (state) {
                    RingFace.ALL_DONE -> Box(Modifier.size(RING).background(success, MaterialShapes.Cookie9Sided.toShape()),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, Modifier.size(15.dp), tint = scheme.surface)
                    }
                    RingFace.JUST_FINISHED -> Box(Modifier.size(RING), contentAlignment = Alignment.Center) {
                        com.androidharness.app.ui.common.AppCircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(),
                            color = success, trackColor = scheme.surfaceContainerLow)
                        Box(Modifier.size(20.dp).background(success, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = scheme.surface)
                        }
                    }
                    RingFace.PROGRESS -> Box(Modifier.size(RING), contentAlignment = Alignment.Center) {
                        com.androidharness.app.ui.common.AppCircularProgressIndicator(progress = { progress },
                            modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = twist.value },
                            color = scheme.primary, trackColor = scheme.surfaceContainerLow)
                        AnimatedContent(done, label = "Task ring count", transitionSpec = {
                            (slideInVertically(expressiveDefaultSpatialSpec()) { it } + fadeIn(expressiveEffectsSpec())) togetherWith
                                (slideOutVertically(expressiveEffectsSpec()) { -it } + fadeOut(tween(90)))
                        }) { count ->
                            Text("$count/$total", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp), fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TaskRow(todo: TodoItem, index: Int, first: Boolean, last: Boolean, changed: Boolean, changeId: Int) {
    val scheme = MaterialTheme.colorScheme
    val success = LocalStatusColors.current.success
    val status = stringResource(when (todo.status) {
        TodoItem.Status.COMPLETED -> R.string.ui_completed
        TodoItem.Status.IN_PROGRESS -> R.string.ui_in_progress
        TodoItem.Status.PENDING -> R.string.ui_pending
    })
    val base = if (todo.status == TodoItem.Status.IN_PROGRESS) scheme.secondaryContainer else scheme.surfaceContainerLowest
    // A changed row glows and pops, then settles into its normal color.
    val glow = remember { androidx.compose.animation.Animatable(base) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(changeId, changed) {
        if (changed) {
            glow.snapTo(scheme.primaryContainer)
            pop.snapTo(.94f)
            pop.animateTo(1f, expressiveSpatialSpec())
        }
        glow.animateTo(base, tween(1200))
    }
    LaunchedEffect(base) { glow.animateTo(base, tween(300)) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40L + index.coerceAtMost(6) * 45L)
        rise.animateTo(1f, expressiveDefaultSpatialSpec())
    }
    val lift = with(LocalDensity.current) { 14.dp.toPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer {
                val p = rise.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * lift
                scaleX = pop.value * (.94f + .06f * p)
                scaleY = pop.value * (.94f + .06f * p)
            }
            .clip(RoundedCornerShape(topStart = if (first) 14.dp else 5.dp, topEnd = if (first) 14.dp else 5.dp,
                bottomStart = if (last) 14.dp else 5.dp, bottomEnd = if (last) 14.dp else 5.dp))
            .background(glow.value)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { stateDescription = status },
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(todo.status, label = "Task status", transitionSpec = {
                (scaleIn(expressiveSpatialSpec(), initialScale = .3f) + fadeIn(expressiveEffectsSpec())) togetherWith
                    (scaleOut(tween(120), targetScale = .5f) + fadeOut(tween(100)))
            }) { state ->
                when (state) {
                    TodoItem.Status.COMPLETED -> Box(Modifier.size(18.dp).background(success, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = scheme.surface)
                    }
                    TodoItem.Status.IN_PROGRESS -> MorphingStatusBadge(WorkState.Running, Icons.Outlined.PlayArrow, size = 18.dp, showOutcome = false)
                    TodoItem.Status.PENDING -> Box(Modifier.size(18.dp).border(1.5.dp, scheme.outlineVariant, CircleShape))
                }
            }
        }
        Text(todo.content, style = MaterialTheme.typography.bodySmall,
            fontWeight = if (todo.status == TodoItem.Status.IN_PROGRESS) FontWeight.Medium else FontWeight.Normal,
            textDecoration = if (todo.status == TodoItem.Status.COMPLETED) TextDecoration.LineThrough else null,
            color = when (todo.status) {
                TodoItem.Status.COMPLETED -> scheme.onSurfaceVariant
                TodoItem.Status.IN_PROGRESS -> scheme.onSecondaryContainer
                TodoItem.Status.PENDING -> scheme.onSurface
            },
            modifier = Modifier.weight(1f))
    }
}
