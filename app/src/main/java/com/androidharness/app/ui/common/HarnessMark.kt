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
import com.androidharness.app.ui.theme.LocalHarnessGlass

/**
 * The Harness brand mark: an AH monogram. The A's right leg is also the H's left
 * leg, and one strap crosses both letters.
 *
 * Geometry mirrors `res/drawable/ic_launcher_monochrome.xml` (the launcher icon)
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
        // A diagonal and shared stem
        moveTo(2f, 20.36f)
        lineTo(5.6f, 20.36f)
        lineTo(10.69f, 10.99f)
        lineTo(10.69f, 20.36f)
        lineTo(13.97f, 20.36f)
        lineTo(13.97f, 3.64f)
        lineTo(10.69f, 3.64f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        // H right leg
        moveTo(18.72f, 3.64f)
        lineTo(22f, 3.64f)
        lineTo(22f, 20.36f)
        lineTo(18.72f, 20.36f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        // strap
        moveTo(4.55f, 15.44f)
        lineTo(5.92f, 12.82f)
        lineTo(22f, 12.82f)
        lineTo(22f, 15.44f)
        close()
    }
}.build()

/**
 * Brand tile: the AH monogram on a near-black (light theme) / near-white (dark
 * theme) rounded square. Deliberately monochrome, the dynamic accent is reserved
 * for actions, not logos.
 */
@Composable
fun HarnessMark(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Harness Glass clears the surface role, so the glyph falls back to the glass base color.
    val ink = MaterialTheme.colorScheme.surface.takeIf { it.alpha > 0f } ?: LocalHarnessGlass.current.surface
    Surface(
        color = MaterialTheme.colorScheme.onSurface,
        contentColor = ink,
        shape = RoundedCornerShape(size * 0.28f),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                PromptMark,
                contentDescription = null,
                modifier = Modifier.size(size * 0.7f),
            )
        }
    }
}
