package com.androidharness.app.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.androidharness.app.ui.common.Switch

/** Keep the standard control when glass is off; glass tracks retain switch semantics and dragging. */
@Composable
internal fun HarnessGlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    if (LocalHarnessGlass.current.mode == HarnessGlassMode.OFF) {
        Switch(checked, onCheckedChange, modifier, thumbContent, enabled, colors, interactionSource)
        return
    }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var drag by remember { mutableFloatStateOf(0f) }
    val position by animateDpAsState(if (checked) 26.dp else 2.dp, defaultSpatialSpec(), label = "glass switch thumb")
    val track = if (checked) colors.checkedTrackColor else colors.uncheckedTrackColor
    val thumb = if (checked) colors.checkedThumbColor else colors.uncheckedThumbColor
    val behavior = if (onCheckedChange == null) Modifier else Modifier
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .draggable(rememberDraggableState { drag += if (rtl) -it else it }, Orientation.Horizontal,
            enabled = enabled, interactionSource = interactionSource,
            onDragStarted = { drag = 0f }, onDragStopped = {
                if (abs(drag) > with(density) { 8.dp.toPx() }) onCheckedChange(drag > 0f)
                drag = 0f
            })
    Box(modifier.minimumInteractiveComponentSize().then(behavior), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp, 32.dp).alpha(if (enabled) 1f else .38f).harnessGlass(track, CircleShape)) {
            val offset = (position + with(density) { drag.toDp() }).coerceIn(2.dp, 26.dp)
            Box(Modifier.offset(x = offset, y = 4.dp).size(24.dp).harnessGlass(thumb, CircleShape)
                .background(thumb.copy(alpha = .42f), CircleShape), contentAlignment = Alignment.Center) {
                thumbContent?.invoke()
            }
        }
    }
}
