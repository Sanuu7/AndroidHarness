package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Harness brand mark: a terminal prompt ("❯_").
 *
 * Geometry mirrors `res/drawable/ic_launcher_foreground.xml` (the launcher icon)
 * scaled to a 24dp viewport, so the in-app identity and the home-screen icon match.
 * Filled, not stroked, it stays crisp at 14dp and holds up at 56dp.
 */
val PromptMark: ImageVector = ImageVector.Builder(
    name = "PromptMark",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // chevron "❯"
        moveTo(7.1f, 7.56f)
        lineTo(10.67f, 7.56f)
        lineTo(15.11f, 12f)
        lineTo(10.67f, 16.44f)
        lineTo(7.1f, 16.44f)
        lineTo(11.56f, 12f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        // underscore "_"
        moveTo(12.89f, 14.67f)
        lineTo(18.22f, 14.67f)
        lineTo(18.22f, 16.44f)
        lineTo(12.89f, 16.44f)
        close()
    }
}.build()

/**
 * Brand tile: the prompt mark on a near-black (light theme) / near-white (dark
 * theme) rounded square. Deliberately monochrome, the dynamic accent is reserved
 * for actions, not logos.
 */
@Composable
fun HarnessMark(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(size * 0.28f),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                PromptMark,
                contentDescription = null,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
