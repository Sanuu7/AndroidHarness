package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** Puts the panel window right against its pill: above it or below it, right edges lined up. */
internal class FloatingPanelPositionProvider(private val placement: SubagentBubblePlacement) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val y = if (placement.panelAbove) placement.bubbleY - popupContentSize.height
            else placement.bubbleY + placement.bubbleHeight
        return IntOffset(placement.popupX(popupContentSize.width), y.coerceAtLeast(0))
    }
}

/**
 * The open panel of a floating pill, in a window of its own with one fixed
 * size for as long as it is up. A popup window that changes size has to move
 * to stay in place, and that move lands a frame after the new drawing, so a
 * panel growing inside the pill's window made the pill shake while it opened
 * and closed. Here the pill's window never changes and this one never moves;
 * the panel animates inside it.
 *
 * Empty space in the window, between the panel and the far edge, closes the
 * panel like a tap outside does.
 */
@Composable
internal fun FloatingPanelPopup(
    expanded: Boolean,
    placement: SubagentBubblePlacement?,
    width: Dp,
    maxPanelHeight: Dp,
    dismissOnClickOutside: Boolean,
    onDismiss: () -> Unit,
    panel: @Composable (visible: Boolean) -> Unit,
) {
    // Stay up a little after collapsing so the panel's exit animation can play.
    var up by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) up = true else { delay(PANEL_EXIT_MS); up = false }
    }
    if (!up || placement == null || maxPanelHeight < 80.dp) return
    // A new window starts with the panel closed, so it opens with its enter animation.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val density = LocalDensity.current
    val position = remember(placement) { FloatingPanelPositionProvider(placement) }
    val gap = PANEL_GAP
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnClickOutside && expanded, clippingEnabled = true, usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            val empty = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            Column(Modifier.size(width, maxPanelHeight + gap), horizontalAlignment = AbsoluteAlignment.Right) {
                if (placement.panelAbove) Spacer(empty.weight(1f))
                panel(entered && expanded)
                if (!placement.panelAbove) Spacer(empty.weight(1f))
            }
        }
    }
}

/** Exit animations of the panels run under 200ms. */
internal const val PANEL_EXIT_MS = 320L

/** Space the panel keeps from its pill, already part of each panel's layout. */
internal val PANEL_GAP = 12.dp
