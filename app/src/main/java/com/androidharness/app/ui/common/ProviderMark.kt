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

/** A compact provider hub mark: external model endpoints orbit one shared agent. */
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
        strokeLineWidth = 1.55f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(7.15f, 7.15f)
        curveTo(8.35f, 6.0f, 10.0f, 5.35f, 12f, 5.35f)
        curveTo(14.0f, 5.35f, 15.65f, 6.0f, 16.85f, 7.15f)
        moveTo(6.7f, 16.15f)
        curveTo(8.0f, 17.75f, 9.75f, 18.65f, 12f, 18.65f)
        curveTo(14.25f, 18.65f, 16.0f, 17.75f, 17.3f, 16.15f)
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 7.65f)
        lineTo(13.25f, 10.75f)
        lineTo(16.35f, 12f)
        lineTo(13.25f, 13.25f)
        lineTo(12f, 16.35f)
        lineTo(10.75f, 13.25f)
        lineTo(7.65f, 12f)
        lineTo(10.75f, 10.75f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(6.85f, 4.0f)
        curveTo(8.15f, 4.0f, 9.2f, 5.05f, 9.2f, 6.35f)
        curveTo(9.2f, 7.65f, 8.15f, 8.7f, 6.85f, 8.7f)
        curveTo(5.55f, 8.7f, 4.5f, 7.65f, 4.5f, 6.35f)
        curveTo(4.5f, 5.05f, 5.55f, 4.0f, 6.85f, 4.0f)
        close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(17.15f, 4.0f)
        curveTo(18.45f, 4.0f, 19.5f, 5.05f, 19.5f, 6.35f)
        curveTo(19.5f, 7.65f, 18.45f, 8.7f, 17.15f, 8.7f)
        curveTo(15.85f, 8.7f, 14.8f, 7.65f, 14.8f, 6.35f)
        curveTo(14.8f, 5.05f, 15.85f, 4.0f, 17.15f, 4.0f)
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
        shape = RoundedCornerShape(size * 0.32f),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                ProviderGlyph,
                contentDescription = null,
                modifier = Modifier.size(size * 0.64f),
            )
        }
    }
}
