package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R

/**
 * Where each heading of one answer sits, measured from the top of the answer.
 * The markdown renderer records headings as they are placed; the outline
 * button reads them to jump.
 */
internal class HeadingAnchors {
    var root: LayoutCoordinates? = null
    private val headings = mutableStateMapOf<Int, Pair<String, Float>>()

    fun record(index: Int, title: String, coordinates: LayoutCoordinates) {
        val base = root?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        val y = base.localPositionOf(coordinates, Offset.Zero).y
        if (headings[index]?.second != y || headings[index]?.first != title) headings[index] = title to y
    }

    val outline: List<Pair<String, Float>> get() = headings.toSortedMap().values.toList()
}

/** Set by a committed Chat answer, so its headings report their positions. */
internal val LocalHeadingAnchors = compositionLocalOf<HeadingAnchors?> { null }

/** A small "Jump to section" control for answers with three or more headings. */
@Composable
internal fun AnswerOutlineButton(anchors: HeadingAnchors, onJump: (Float) -> Unit) {
    val outline = anchors.outline
    if (outline.size < 3) return
    var open by remember { mutableStateOf(false) }
    Box {
        com.androidharness.app.ui.common.FilledTonalButton(
            onClick = { open = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.height(32.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.List, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.chat_answer_outline), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            outline.forEach { (title, y) ->
                DropdownMenuItem(
                    text = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(12.dp)),
                    onClick = { open = false; onJump(y) },
                )
            }
        }
    }
}
