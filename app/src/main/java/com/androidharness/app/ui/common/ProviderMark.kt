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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A small routing mark: multiple model providers converging on one agent. */
val ProviderGlyph: ImageVector = ImageVector.Builder(
    name = "ProviderGlyph",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.65f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(7.35f, 7.45f)
        lineTo(10.15f, 10.25f)
        moveTo(16.65f, 7.45f)
        lineTo(13.85f, 10.25f)
        moveTo(12f, 15.25f)
        lineTo(12f, 17.1f)
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 8.7f)
        lineTo(15.3f, 12f)
        lineTo(12f, 15.3f)
        lineTo(8.7f, 12f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(8.3f, 6.1f)
        curveTo(8.3f, 7.37f, 7.27f, 8.4f, 6f, 8.4f)
        curveTo(4.73f, 8.4f, 3.7f, 7.37f, 3.7f, 6.1f)
        curveTo(3.7f, 4.83f, 4.73f, 3.8f, 6f, 3.8f)
        curveTo(7.27f, 3.8f, 8.3f, 4.83f, 8.3f, 6.1f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(20.3f, 6.1f)
        curveTo(20.3f, 7.37f, 19.27f, 8.4f, 18f, 8.4f)
        curveTo(16.73f, 8.4f, 15.7f, 7.37f, 15.7f, 6.1f)
        curveTo(15.7f, 4.83f, 16.73f, 3.8f, 18f, 3.8f)
        curveTo(19.27f, 3.8f, 20.3f, 4.83f, 20.3f, 6.1f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(14.3f, 19.4f)
        curveTo(14.3f, 20.67f, 13.27f, 21.7f, 12f, 21.7f)
        curveTo(10.73f, 21.7f, 9.7f, 20.67f, 9.7f, 19.4f)
        curveTo(9.7f, 18.13f, 10.73f, 17.1f, 12f, 17.1f)
        curveTo(13.27f, 17.1f, 14.3f, 18.13f, 14.3f, 19.4f)
        close()
    }
}.build()

@Composable
fun ProviderMark(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(size * 0.3f),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                ProviderGlyph,
                contentDescription = null,
                modifier = Modifier.size(size * 0.62f),
            )
        }
    }
}
