package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.theme.defaultEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.ui.theme.fastEffectsSpec

/**
 * One line of agent activity, drawn inline with the transcript instead of in a card.
 *
 * A small icon carries the state (it breathes while the step runs, turns red when it
 * fails), the title shimmers while it works, and a muted detail follows on the same
 * line. Tapping the line opens [content] underneath, hung off a thin guide line so the
 * nesting reads at a glance without any boxes.
 */
@Composable
internal fun InlineActivityRow(
    icon: ImageVector,
    title: String,
    state: WorkState,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailMono: Boolean = false,
    count: Int? = null,
    /** Slides each new title in from below, for rows that follow a live step. */
    animateTitle: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** Shown under the header whether or not the row is open, such as screenshots. */
    always: (@Composable ColumnScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val working = state == WorkState.Running || state == WorkState.Waiting
    val tint = when (state) {
        WorkState.Failed -> scheme.error
        WorkState.Waiting -> scheme.tertiary
        WorkState.Running -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    val breathe = if (working) {
        val pulse by rememberInfiniteTransition(label = "activity icon").animateFloat(0.45f, 1f,
            infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "activity icon pulse")
        pulse
    } else 1f
    val turn by animateFloatAsState(if (expanded) 180f else 0f, bouncySpec(), label = "activity chevron")
    val status = when (state) {
        WorkState.Failed -> context.getString(R.string.ui_failed_64)
        WorkState.Waiting, WorkState.Running -> context.getString(R.string.chat_working)
        else -> null
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
                .then(if (content != null) Modifier.clickable(role = Role.Button,
                    onClickLabel = context.getString(if (expanded) R.string.ui_collapse else R.string.ui_expand), onClick = onToggle) else Modifier)
                .semantics { status?.let { stateDescription = it } }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, Modifier.size(18.dp).graphicsLayer { alpha = breathe }, tint = tint)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val titleStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                Box(Modifier.weight(1f, fill = false)) {
                    if (animateTitle) AnimatedContent(title, transitionSpec = {
                        (slideInVertically(tween(260)) { it } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(200)) { -it } + fadeOut(tween(140))) using SizeTransform(clip = true)
                    }, label = "activity title") { text -> InlineTitle(text, working, state, titleStyle) }
                    else InlineTitle(title, working, state, titleStyle)
                }
                // Some titles already quote their detail ("Run ls -la"); never say it twice.
                val extra = detail?.trim()?.takeIf { it.isNotEmpty() && !title.contains(it.removeSuffix("…").take(16)) }
                if (extra != null) Text(extra, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall.let { if (detailMono) it.copy(fontFamily = FontFamily.Monospace) else it },
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (count != null && count > 1) Text("$count", style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant, modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(scheme.surfaceContainerHigh).padding(horizontal = 7.dp, vertical = 2.dp))
            if (state == WorkState.Failed) Text(context.getString(R.string.ui_failed_64),
                style = MaterialTheme.typography.labelSmall, color = scheme.error)
            trailing?.invoke()
            if (content != null) Icon(Icons.Filled.KeyboardArrowDown, null,
                Modifier.size(18.dp).graphicsLayer { rotationZ = turn }, tint = scheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        if (always != null) Column(Modifier.padding(start = 34.dp, top = 2.dp, bottom = 4.dp), content = always)
        if (content != null) AnimatedVisibility(
            expanded,
            enter = expandVertically(animationSpec = bouncySpec()) + fadeIn(defaultEffectsSpec()),
            exit = shrinkVertically(animationSpec = expressiveSpatialSpec()) + fadeOut(fastEffectsSpec()),
        ) {
            // The guide line is drawn behind the content, not sized from it: asking the content for
            // its intrinsic height crashes as soon as it holds a lazy list (an expanded activity card).
            val guide = scheme.outlineVariant.copy(alpha = 0.7f)
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, top = 2.dp, bottom = 6.dp)
                    .drawBehind {
                        val w = 1.5.dp.toPx()
                        drawRoundRect(guide, size = Size(w, size.height), cornerRadius = CornerRadius(1.dp.toPx()))
                    }
                    .padding(start = 13.5.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun InlineTitle(text: String, working: Boolean, state: WorkState, style: androidx.compose.ui.text.TextStyle) {
    if (working) ChatShimmerText(text, running = true, style = style)
    else Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (state == WorkState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}
