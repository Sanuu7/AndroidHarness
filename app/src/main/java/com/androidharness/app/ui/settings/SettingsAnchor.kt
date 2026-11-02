package com.androidharness.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.theme.defaultEffectsSpec

/**
 * The label of the setting a search result or deep link is pointing at, if any.
 *
 * A settings search result used to open its page at the very top with no
 * indication of which control the user asked for, which made searching for
 * something like "Whisper model" a dead end on a long page. The page sets this
 * to the target label and every [SettingsAnchor] with a matching title scrolls
 * itself into view and flashes a tint.
 * Held as a string resource id rather than the rendered text, so an anchor and
 * the search entry pointing at it stay the same value in every language.
 */
internal val LocalSettingsHighlight = staticCompositionLocalOf<Int?> { null }

/**
 * Labels of the collapsed sections that hold the highlighted setting. A
 * [SettingsDisclosure] whose title is one of them opens by itself, so a result
 * for something tucked inside "Advanced device access" is visible on arrival.
 */
internal val LocalSettingsReveal = staticCompositionLocalOf<Set<Int>> { emptySet() }

/** The scrolling page around the anchors, so a target can land near the top instead of the bottom edge. */
internal class SettingsScroller(val state: ScrollState) {
    var viewport: LayoutCoordinates? = null
}

internal val LocalSettingsScroller = staticCompositionLocalOf<SettingsScroller?> { null }

/** True inside an anchor that is the current target, so a row it wraps does not scroll and tint a second time. */
private val LocalAnchorClaimed = compositionLocalOf { false }

/** Provides [highlight] and the sections to open for it to everything below, see [LocalSettingsHighlight]. */
@Composable
internal fun SettingsHighlightScope(
    @StringRes highlight: Int?,
    reveal: Set<Int> = emptySet(),
    scroller: SettingsScroller? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSettingsHighlight provides highlight,
        LocalSettingsReveal provides reveal,
        LocalSettingsScroller provides scroller,
    ) { content() }
}

/** Whether a section titled [title] holds the highlighted setting and should open. */
@Composable
internal fun settingsRevealed(title: String): Boolean {
    val reveal = LocalSettingsReveal.current
    if (reveal.isEmpty()) return false
    val context = LocalContext.current
    return reveal.any { context.getString(it) == title }
}

/**
 * Marks the control a deep link can land on. The anchor title must be the same
 * string resource the control's visible label uses, which is also the resource
 * the search entry carries, so the three can never drift apart.
 */
@Composable
internal fun SettingsAnchor(
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    val highlight = LocalSettingsHighlight.current
    AnchorBox(highlight != null && title == highlight && !LocalAnchorClaimed.current, content)
}

/**
 * Anchor for shared rows that only know their rendered label. It matches when
 * the highlighted resource renders to the same text, so common rows become
 * search targets without every call site wrapping them.
 */
@Composable
internal fun SettingsAnchor(
    title: String,
    content: @Composable () -> Unit,
) {
    val highlight = LocalSettingsHighlight.current
    val context = LocalContext.current
    val isTarget = highlight != null && !LocalAnchorClaimed.current &&
        remember(highlight, context) { runCatching { context.getString(highlight) }.getOrNull() } == title
    AnchorBox(isTarget, content)
}

/**
 * Modifier form of [SettingsAnchor] for a control that is a single row or
 * card: the row itself scrolls into view and takes the tint.
 */
internal fun Modifier.settingsAnchor(@StringRes title: Int): Modifier = composed {
    val highlight = LocalSettingsHighlight.current
    anchorModifier(highlight != null && title == highlight && !LocalAnchorClaimed.current)
}

/** Modifier form of the text-matched [SettingsAnchor], for shared rows. */
internal fun Modifier.settingsAnchor(title: String): Modifier = composed {
    val highlight = LocalSettingsHighlight.current
    val context = LocalContext.current
    anchorModifier(highlight != null && !LocalAnchorClaimed.current &&
        remember(highlight, context) { runCatching { context.getString(highlight) }.getOrNull() } == title)
}

@Composable
private fun AnchorBox(isTarget: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().then(anchorModifier(isTarget))) {
        CompositionLocalProvider(LocalAnchorClaimed provides (isTarget || LocalAnchorClaimed.current)) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun anchorModifier(isTarget: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scroller = LocalSettingsScroller.current
    val margin = with(LocalDensity.current) { 72.dp.toPx() }
    val position = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val tint by animateColorAsState(
        targetValue = if (isTarget) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else Color.Transparent,
        animationSpec = defaultEffectsSpec(),
        label = "settingsAnchorTint",
    )

    LaunchedEffect(isTarget) {
        if (!isTarget) return@LaunchedEffect
        // Sections above may still be opening. Wait until the anchor stops
        // moving, otherwise the scroll aims at where it used to be.
        var last = Float.NaN
        var still = 0
        val start = System.nanoTime()
        while (still < 3 && System.nanoTime() - start < 1_200_000_000L) {
            withFrameNanos { }
            val viewport = scroller?.viewport
            val anchor = position[0]
            val top = when {
                anchor == null || !anchor.isAttached -> Float.NaN
                viewport != null && viewport.isAttached -> viewport.localPositionOf(anchor, Offset.Zero).y
                else -> anchor.positionInRoot().y
            }
            still = if (top == last && System.nanoTime() - start > 120_000_000L) still + 1 else 0
            last = top
        }
        val viewport = scroller?.viewport
        val anchor = position[0]
        if (scroller != null && viewport != null && anchor != null && anchor.isAttached && viewport.isAttached) {
            val top = viewport.localPositionOf(anchor, Offset.Zero).y
            val target = (scroller.state.value + top - margin).toInt().coerceIn(0, scroller.state.maxValue)
            runCatching { scroller.state.animateScrollTo(target) }
        } else {
            runCatching { requester.bringIntoView() }
        }
    }

    return Modifier
        .onGloballyPositioned { position[0] = it }
        .bringIntoViewRequester(requester)
        .background(tint, MaterialTheme.shapes.medium)
}
