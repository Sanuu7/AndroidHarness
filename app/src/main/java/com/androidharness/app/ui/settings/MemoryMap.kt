package com.androidharness.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.R
import com.androidharness.app.data.MemoryCategory
import com.androidharness.app.data.MemoryGraph
import com.androidharness.app.data.MemoryKeywords
import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.hostedDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A living map of every memory: folders are glowing hubs, memories are the
 * neurons around them, and memories that share trigger words or topics are
 * wired together, with small signals travelling along the wires.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MemoryMapDialog(
    memories: List<MemoryEntity>,
    folderNames: List<String>,
    builtInLabels: Map<MemoryCategory, String>,
    onDismiss: () -> Unit,
    onEdit: (MemoryEntity) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss,
        properties = hostedDialog(DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))) {
        val graph by produceState<MemoryGraph?>(null, memories, folderNames) {
            value = withContext(Dispatchers.Default) { MemoryGraph.build(memories, folderNames) }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            val current = graph
            if (current == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else MemoryMap(current, folderNames, builtInLabels, onDismiss, onEdit)
        }
    }
}

/** Positions settle by a small force simulation: hubs push apart, memories orbit their hub, links pull related memories close. */
private class MapLayout(val graph: MemoryGraph) {
    val hubs = graph.hubs.size
    val count = hubs + graph.nodes.size
    val x = FloatArray(count)
    val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val owner = IntArray(count) { if (it < hubs) -1 else graph.nodes[it - hubs].hub }
    private val orbit = FloatArray(hubs) { 46f + 7f * sqrt(graph.hubs[it].size.toFloat()) }
    var heat = 1f
        private set

    init {
        val ring = if (hubs == 1) 0f else 90f * sqrt(hubs.toFloat()) + 70f
        for (h in 0 until hubs) {
            val angle = 2 * PI * h / hubs - PI / 2
            x[h] = (ring * cos(angle)).toFloat(); y[h] = (ring * sin(angle)).toFloat()
        }
        for (i in hubs until count) {
            // Seeded by the memory id so the map opens the same way each time.
            val seed = graph.nodes[i - hubs].memory.id.hashCode()
            val angle = (seed and 0xffff) / 65535.0 * 2 * PI
            val reach = 18f + ((seed ushr 16) and 0xff) / 255f * 50f
            x[i] = x[owner[i]] + (reach * cos(angle)).toFloat(); y[i] = y[owner[i]] + (reach * sin(angle)).toFloat()
        }
    }

    fun running() = heat > .004f

    fun step() {
        if (!running()) return
        val fx = FloatArray(count); val fy = FloatArray(count)
        // Nearby repulsion through a grid, so the cost grows with the number of memories, not its square.
        val cell = 70f
        val grid = HashMap<Long, MutableList<Int>>()
        fun cellOf(i: Int): Long = (Math.floorDiv(x[i].toInt(), cell.toInt()).toLong() shl 32) or (Math.floorDiv(y[i].toInt(), cell.toInt()).toLong() and 0xffffffffL)
        for (i in hubs until count) grid.getOrPut(cellOf(i)) { ArrayList(4) }.add(i)
        for (i in hubs until count) {
            val cx = Math.floorDiv(x[i].toInt(), cell.toInt()); val cy = Math.floorDiv(y[i].toInt(), cell.toInt())
            for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) {
                grid[(gx.toLong() shl 32) or (gy.toLong() and 0xffffffffL)]?.forEach { j ->
                    if (j <= i) return@forEach
                    var dx = x[i] - x[j]; var dy = y[i] - y[j]
                    var d2 = dx * dx + dy * dy
                    if (d2 > cell * cell) return@forEach
                    if (d2 < .01f) { dx = .1f * ((i % 7) - 3); dy = .1f * ((j % 5) - 2); d2 = .02f }
                    val push = 260f / max(d2, 9f)
                    fx[i] += dx * push; fy[i] += dy * push; fx[j] -= dx * push; fy[j] -= dy * push
                }
            }
        }
        // Hubs keep their distance from each other and from memories of other folders.
        // The push is sized so two hubs settle about one orbit apart against the pull to the centre.
        for (a in 0 until hubs) for (b in a + 1 until hubs) {
            val dx = x[a] - x[b]; val dy = y[a] - y[b]
            val d2 = max(dx * dx + dy * dy, 100f)
            val gap = orbit[a] + orbit[b] + 80f
            val push = HUB_GRAVITY * .6f * gap * gap / d2
            fx[a] += dx * push; fy[a] += dy * push; fx[b] -= dx * push; fy[b] -= dy * push
        }
        for (i in hubs until count) {
            val h = owner[i]
            for (other in 0 until hubs) {
                if (other == h) continue
                val dx = x[i] - x[other]; val dy = y[i] - y[other]
                val reach = orbit[other] + 30f
                val d2 = dx * dx + dy * dy
                if (d2 >= reach * reach) continue
                val d = max(sqrt(d2), .1f)
                val push = .08f * (reach - d) / d
                fx[i] += dx * push; fy[i] += dy * push
            }
            val dx = x[i] - x[h]; val dy = y[i] - y[h]
            val d = max(sqrt(dx * dx + dy * dy), .1f)
            val pull = .06f * (d - orbit[h]) / d
            fx[i] -= dx * pull; fy[i] -= dy * pull
            fx[h] += dx * pull * .15f; fy[h] += dy * pull * .15f
        }
        graph.links.forEach { link ->
            val a = hubs + link.a; val b = hubs + link.b
            val dx = x[a] - x[b]; val dy = y[a] - y[b]
            val d = max(sqrt(dx * dx + dy * dy), .1f)
            val pull = .012f * link.strength * (d - 40f) / d
            fx[a] -= dx * pull; fy[a] -= dy * pull; fx[b] += dx * pull; fy[b] += dy * pull
        }
        for (i in 0 until count) {
            val gravity = if (i < hubs) HUB_GRAVITY else .002f
            fx[i] -= x[i] * gravity; fy[i] -= y[i] * gravity
            vx[i] = ((vx[i] + fx[i] * heat) * .62f).coerceIn(-18f, 18f)
            vy[i] = ((vy[i] + fy[i] * heat) * .62f).coerceIn(-18f, 18f)
            x[i] += vx[i]; y[i] += vy[i]
        }
        heat *= .988f
    }

    private companion object {
        const val HUB_GRAVITY = .012f
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MemoryMap(
    graph: MemoryGraph,
    folderNames: List<String>,
    builtInLabels: Map<MemoryCategory, String>,
    onDismiss: () -> Unit,
    onEdit: (MemoryEntity) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val motion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val dark = scheme.surface.luminanceIsDark()
    val layout = remember(graph) { MapLayout(graph) }
    val hubColors = remember(graph, scheme.primary, dark) { graph.hubs.map { folderHue(scheme.primary, memoryFolderIndex(it.key, folderNames), dark) } }
    val hubNames = graph.hubs.map { hub ->
        if (hub.key.startsWith("c:")) builtInLabels.getValue(MemoryCategory.fromStored(hub.key.removePrefix("c:"))) else hub.key.removePrefix("f:")
    }
    val neighbours = remember(graph) {
        val lists = Array(graph.nodes.size) { ArrayList<Int>() }
        graph.links.forEach { lists[it.a].add(it.b); lists[it.b].add(it.a) }
        lists
    }
    // A handful of wires carry a travelling signal, so the map feels alive without turning into noise.
    val pulsing = remember(graph) { graph.links.indices.filter { (graph.links[it].a * 31 + graph.links[it].b) % 3 == 0 }.take(90) }
    val radii = remember(graph) {
        FloatArray(graph.nodes.size) { i ->
            val memory = graph.nodes[i].memory
            (3.6f + 1.5f * ln(1f + memory.useCount) + (if (memory.pinned) 1.6f else 0f) + (if (memory.isSummary) 1.2f else 0f)).coerceAtMost(10f)
        }
    }
    val hubRadii = remember(graph) { FloatArray(graph.hubs.size) { (17f + 3.2f * sqrt(graph.hubs[it].size.toFloat())).coerceAtMost(40f) } }
    val phase = remember(graph) { FloatArray(graph.nodes.size) { ((graph.nodes[it].memory.id.hashCode() and 0x3ff) / 1023f) * 2 * PI.toFloat() } }

    var time by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var fitted by remember { mutableStateOf(true) } // follow the growing map until the user takes over
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var focus by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(layout, motion) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time = (now - start) / 1_000_000_000f
                if (layout.running()) { layout.step(); layout.step() }
                frame++
            }
            if (!motion && !layout.running()) break
        }
    }

    fun world(i: Int) = Offset(layout.x[i], layout.y[i])
    fun toScreen(p: Offset): Offset = Offset(canvasSize.width / 2f, canvasSize.height / 2f) + pan + p * (density * zoom)

    fun fit() {
        if (canvasSize == IntSize.Zero || layout.count == 0) return
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until layout.count) { minX = min(minX, layout.x[i]); maxX = max(maxX, layout.x[i]); minY = min(minY, layout.y[i]); maxY = max(maxY, layout.y[i]) }
        val w = (maxX - minX + 200f) * density; val h = (maxY - minY + 220f) * density
        zoom = min(canvasSize.width / w, canvasSize.height / h).coerceIn(.15f, 2.4f)
        pan = -Offset((minX + maxX) / 2f, (minY + maxY) / 2f) * (density * zoom)
    }

    fun flyTo(point: Offset, targetZoom: Float = max(zoom, 1.5f)) {
        fitted = false
        val fromPan = pan; val fromZoom = zoom
        val toPan = -point * (density * targetZoom)
        scope.launch {
            animate(0f, 1f, animationSpec = tween(if (motion) 520 else 0, easing = FastOutSlowInEasing)) { v, _ ->
                zoom = fromZoom + (targetZoom - fromZoom) * v
                pan = lerp(fromPan, toPan, v)
            }
        }
    }

    BackHandler(enabled = selected != null || focus != null) { if (selected != null) selected = null else focus = null }

    val measurer = rememberTextMeasurer(cacheSize = 256)
    val labelStyle = MaterialTheme.typography.labelMedium
    val hubStyle = MaterialTheme.typography.labelLarge
    val nodeLabels = remember(graph, labelStyle) { HashMap<Int, TextLayoutResult>() }
    val hubShape = MaterialShapes.Cookie9Sided.toShape()
    val signal = if (dark) Color.White else scheme.primary
    val ink = scheme.onSurface
    val labelBack = scheme.surfaceContainerHigh

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(graph) {
                    detectTransformGestures { centroid, move, scale, _ ->
                        fitted = false
                        val newZoom = (zoom * scale).coerceIn(.12f, 6f)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        pan = centroid - center - (centroid - center - pan) * (newZoom / zoom) + move
                        zoom = newZoom
                    }
                }
                .pointerInput(graph) {
                    detectTapGestures(
                        onDoubleTap = { selected = null; focus = null; fitted = true },
                        onTap = { tap ->
                            var best = -1; var bestDistance = Float.MAX_VALUE
                            for (i in 0 until layout.count) {
                                val p = toScreen(world(i))
                                val reach = (if (i < layout.hubs) hubRadii[i] else radii[i - layout.hubs]) * density * zoom + 14f * density
                                val d = hypot(p.x - tap.x, p.y - tap.y)
                                if (d < reach && d < bestDistance) { best = i; bestDistance = d }
                            }
                            when {
                                best < 0 -> selected = null
                                best < layout.hubs -> { selected = null; focus = if (focus == best) null else best.also { flyTo(world(it), max(zoom, 1.2f)) } }
                                else -> { selected = best - layout.hubs; focus = null; flyTo(world(best)) }
                            }
                        },
                    )
                },
        ) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())
            frame // redraw on every simulation or animation frame
            if (fitted) fit()
            val s = density * zoom
            val t = if (motion) time else 0f
            val chosen = selected
            val lit: Set<Int>? = chosen?.let { neighbours[it].toSet() + it }
            fun nodeDim(i: Int): Float = when {
                lit != null -> if (i in lit) 1f else .14f
                focus != null -> if (graph.nodes[i].hub == focus) 1f else .14f
                else -> 1f
            }

            // A soft glow behind the whole map.
            drawRect(Brush.radialGradient(listOf(scheme.primary.copy(alpha = if (dark) .10f else .07f), Color.Transparent),
                center = toScreen(Offset.Zero), radius = max(size.width, size.height) * .75f))

            // Wires from each memory to its folder.
            for (i in graph.nodes.indices) {
                val node = graph.nodes[i]
                val a = toScreen(world(node.hub)); val b = toScreen(world(layout.hubs + i))
                drawLine(hubColors[node.hub].copy(alpha = .16f * nodeDim(i)), a, b, strokeWidth = max(.8f, .9f * s))
            }

            // Synapses between related memories, gently curved.
            val path = Path()
            graph.links.forEach { link ->
                val a = toScreen(world(layout.hubs + link.a)); val b = toScreen(world(layout.hubs + link.b))
                val mid = (a + b) / 2f
                val bend = Offset(-(b.y - a.y), b.x - a.x) * .12f
                path.reset(); path.moveTo(a.x, a.y); path.quadraticTo(mid.x + bend.x, mid.y + bend.y, b.x, b.y)
                val onChosen = chosen != null && (link.a == chosen || link.b == chosen)
                val dim = if (onChosen) 1f else min(nodeDim(link.a), nodeDim(link.b))
                val color = lerp(hubColors[graph.nodes[link.a].hub], hubColors[graph.nodes[link.b].hub], .5f)
                drawPath(path, color.copy(alpha = ((if (onChosen) .75f else .22f) + .35f * link.strength) * dim),
                    style = Stroke(width = max(1f, (if (onChosen) 2.2f else 1.1f + link.strength) * s)))
            }

            // Signals travelling along the wires.
            if (motion) {
                val active = if (chosen != null) graph.links.indices.filter { graph.links[it].a == chosen || graph.links[it].b == chosen } else pulsing
                active.forEach { index ->
                    val link = graph.links[index]
                    val progress = ((t * .32f + index * .618f) % 1f)
                    val a = toScreen(world(layout.hubs + link.a)); val b = toScreen(world(layout.hubs + link.b))
                    val mid = (a + b) / 2f
                    val c = mid + Offset(-(b.y - a.y), b.x - a.x) * .12f
                    val u = 1 - progress
                    val p = a * (u * u) + c * (2 * u * progress) + b * (progress * progress)
                    val fade = sin(progress * PI.toFloat())
                    val dim = if (chosen != null) 1f else min(nodeDim(link.a), nodeDim(link.b))
                    drawCircle(signal.copy(alpha = .22f * fade * dim), radius = 5f * density, center = p)
                    drawCircle(signal.copy(alpha = .85f * fade * dim), radius = 1.8f * density, center = p)
                }
            }

            // Neurons.
            for (i in graph.nodes.indices) {
                val memory = graph.nodes[i].memory
                val p = toScreen(world(layout.hubs + i))
                if (p.x < -40f || p.y < -40f || p.x > size.width + 40f || p.y > size.height + 40f) continue
                val color = hubColors[graph.nodes[i].hub]
                val dim = nodeDim(i) * if (memory.enabled) 1f else .4f
                val breath = 1f + .09f * sin(t * 1.7f + phase[i])
                val r = max(2.2f * density, radii[i] * s * breath)
                drawCircle(color.copy(alpha = .18f * dim), radius = r * 2.6f, center = p)
                if (memory.triggerOnly) {
                    drawCircle(color.copy(alpha = .35f * dim), radius = r, center = p)
                    drawCircle(color.copy(alpha = dim), radius = r, center = p, style = Stroke(width = max(1.2f, r * .28f)))
                } else drawCircle(color.copy(alpha = dim), radius = r, center = p)
                drawCircle(Color.White.copy(alpha = .45f * dim), radius = r * .35f, center = p - Offset(r * .28f, r * .28f))
                if (memory.pinned) drawCircle(ink.copy(alpha = .7f * dim), radius = r + 2.6f * density, center = p, style = Stroke(width = 1.4f * density))
                if (i == chosen) drawCircle(ink, radius = r + 6f * density + 2f * density * sin(t * 4f), center = p, style = Stroke(width = 2f * density))
            }

            // Folder hubs: slowly turning cookie shapes with a halo.
            for (h in 0 until layout.hubs) {
                val p = toScreen(world(h))
                val dim = if (focus != null && focus != h || lit != null) .45f else 1f
                val r = hubRadii[h] * s * (1f + .03f * sin(t * 1.1f + h))
                drawCircle(Brush.radialGradient(listOf(hubColors[h].copy(alpha = .38f * dim), Color.Transparent), center = p, radius = r * 2.8f),
                    radius = r * 2.8f, center = p)
                val outline = hubShape.createOutline(Size(r * 2, r * 2), layoutDirection, this)
                translate(p.x - r, p.y - r) {
                    rotate(t * 9f + h * 40f, pivot = Offset(r, r)) {
                        drawOutline(outline, hubColors[h].copy(alpha = dim))
                    }
                }
                if (focus == h) drawCircle(ink, radius = r * 1.25f, center = p, style = Stroke(width = 2f * density))
            }

            // Labels last so they sit above everything.
            fun label(text: TextLayoutResult, at: Offset, alpha: Float) {
                val w = text.size.width.toFloat(); val h = text.size.height.toFloat()
                val topLeft = Offset(at.x - w / 2f, at.y)
                drawRoundRect(labelBack.copy(alpha = .82f * alpha), topLeft - Offset(6f * density, 2f * density),
                    Size(w + 12f * density, h + 4f * density), CornerRadius(8f * density))
                drawText(text, color = ink.copy(alpha = alpha), topLeft = topLeft)
            }
            for (h in 0 until layout.hubs) {
                val p = toScreen(world(h))
                val r = hubRadii[h] * s
                val text = measurer.measure("${hubNames[h]}  ${graph.hubs[h].size}", hubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = (180 * density).toInt()))
                label(text, p + Offset(0f, r + 6f * density), if (focus != null && focus != h || lit != null) .5f else 1f)
            }
            val width = size.width; val height = size.height
            val labelled = buildList {
                if (lit != null) addAll(lit)
                else if (zoom > 1.5f) for (i in graph.nodes.indices) {
                    if (focus != null && graph.nodes[i].hub != focus) continue
                    val p = toScreen(world(layout.hubs + i))
                    if (p.x in 0f..width && p.y in 0f..height) add(i)
                    if (this.size >= 60) break
                }
            }
            labelled.forEach { i ->
                val text = nodeLabels.getOrPut(i) {
                    measurer.measure(graph.nodes[i].memory.text.lineSequence().first().take(60), labelStyle, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (150 * density).toInt()))
                }
                val p = toScreen(world(layout.hubs + i))
                label(text, p + Offset(0f, radii[i] * s + 5f * density), if (i == chosen || lit == null) 1f else .85f)
            }
        }

        // Top bar and folder legend.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_action_close)) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(stringResource(R.string.memory_map), style = MaterialTheme.typography.titleLarge)
                    Text(pluralStringResource(R.plurals.memory_map_memories, graph.nodes.size, graph.nodes.size) + " · " +
                        pluralStringResource(R.plurals.memory_map_links, graph.links.size, graph.links.size),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                IconButton(onClick = { selected = null; focus = null; fitted = true }) {
                    Icon(Icons.Rounded.CenterFocusStrong, contentDescription = stringResource(R.string.memory_map_fit))
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(graph.hubs.size) { h ->
                    FilterChip(
                        selected = focus == h,
                        onClick = { selected = null; focus = if (focus == h) null else h.also { flyTo(world(it), max(zoom, 1.2f)) } },
                        label = { Text("${hubNames[h]}  ${graph.hubs[h].size}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(hubColors[h], CircleShape)) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = scheme.surfaceContainerHigh.copy(alpha = .9f)),
                    )
                }
            }
        }

        // What the tapped memory says, and where it leads.
        val chosenNode = selected?.let { graph.nodes.getOrNull(it) }
        AnimatedVisibility(chosenNode != null, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            var shown by remember { mutableStateOf<Int?>(null) }
            if (selected != null) shown = selected
            shown?.let { index ->
                MemoryMapCard(
                    memory = graph.nodes[index].memory,
                    folder = hubNames[graph.nodes[index].hub],
                    color = hubColors[graph.nodes[index].hub],
                    related = neighbours[index].map { graph.nodes[it].memory to hubColors[graph.nodes[it].hub] },
                    onRelated = { position -> val next = neighbours[index][position]; selected = next; flyTo(world(layout.hubs + next)) },
                    onClose = { selected = null },
                    onEdit = { onEdit(graph.nodes[index].memory) },
                )
            }
        }
        if (chosenNode == null) Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            shape = CircleShape, color = scheme.surfaceContainerHigh.copy(alpha = .9f)) {
            Text(stringResource(R.string.memory_map_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MemoryMapCard(
    memory: MemoryEntity,
    folder: String,
    color: Color,
    related: List<Pair<MemoryEntity, Color>>,
    onRelated: (Int) -> Unit,
    onClose: () -> Unit,
    onEdit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).background(color.copy(alpha = .25f), MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
                }
                Text(folder, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (memory.pinned) Icon(Icons.Rounded.Star, contentDescription = stringResource(R.string.settings_memory_pin), tint = scheme.primary,
                    modifier = Modifier.size(20.dp))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_action_close)) }
            }
            Text(memory.text, style = MaterialTheme.typography.bodyLarge,
                color = if (memory.enabled) scheme.onSurface else scheme.onSurfaceVariant)
            val words = MemoryKeywords.parse(memory.keywords)
            if (words.isNotEmpty()) Text(
                stringResource(if (memory.triggerOnly) R.string.memory_trigger_only_row else R.string.memory_triggers_row, words.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall, color = scheme.primary)
            if (related.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.memory_map_connected, related.size, related.size), style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    related.forEachIndexed { position, (other, otherColor) ->
                        AssistChip(onClick = { onRelated(position) },
                            label = { Text(other.text.lineSequence().first(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
                            leadingIcon = { Box(Modifier.size(8.dp).background(otherColor, CircleShape)) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_action_edit))
                }
            }
        }
    }
}
