package com.androidharness.app.ui.common

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidharness.app.core.Diff
import com.androidharness.app.core.DiffHunk
import com.androidharness.app.core.DiffLine
import com.androidharness.app.core.DiffLineType
import com.androidharness.app.core.ParsedDiff
import com.androidharness.app.ui.files.DiffStatText
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastEffectsSpec

/**
 * Mobile-native visual diff viewer designed specifically for touchscreen code review:
 * - Fixed dual gutter with old/new line numbers + diff markers (+ / -)
 * - Full-width tinted rows (success green for additions, error red for deletions)
 * - Fluid horizontal scroll for code content without wrapping or layout jitter
 * - Hunk headers with collapsible section support
 * - Copy raw diff button & summary chips
 */
@Composable
fun VisualDiffViewer(
    diffText: String,
    modifier: Modifier = Modifier,
    filePath: String? = null,
    maxHeight: Dp = 380.dp,
    initiallyExpanded: Boolean = true,
    showFileHeader: Boolean = true,
    onOpenFile: ((String) -> Unit)? = null,
) {
    val parsed = remember(diffText) { Diff.parseUnified(diffText) }
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val displayPath = filePath ?: parsed.newPath ?: parsed.oldPath

    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (showFileHeader && displayPath != null) {
                DiffHeaderBar(
                    filePath = displayPath,
                    added = parsed.totalAdded.toLong(),
                    removed = parsed.totalRemoved.toLong(),
                    onCopy = {
                        clipboard.setText(AnnotatedString(diffText))
                        Toast.makeText(context, "Diff copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onOpenFile = onOpenFile?.let { cb -> { cb(displayPath) } },
                )
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            }

            if (parsed.hunks.isEmpty()) {
                // Fallback for non-standard diffs or empty patches
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Text(
                        diffText.ifBlank { "(empty diff)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                val verticalScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .verticalScroll(verticalScroll),
                ) {
                    parsed.hunks.forEachIndexed { hunkIndex, hunk ->
                        if (hunkIndex > 0) {
                            HorizontalDivider(
                                color = scheme.outlineVariant.copy(alpha = 0.3f),
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        DiffHunkSection(hunk = hunk)
                    }

                    if (parsed.isTruncated) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(scheme.surfaceContainerHigh.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                "Diff truncated for display performance",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffHeaderBar(
    filePath: String,
    added: Long,
    removed: Long,
    onCopy: () -> Unit,
    onOpenFile: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fileName = filePath.substringAfterLast('/')
    val dir = filePath.substringBeforeLast('/', "")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow)
            .then(if (onOpenFile != null) Modifier.clickable { onOpenFile() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Outlined.Code,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fileName,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (dir.isNotBlank()) {
                Text(
                    dir,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DiffStatText(added, removed)
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy Diff",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun DiffHunkSection(hunk: DiffHunk) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxWidth()) {
        if (hunk.header.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainerHigh.copy(alpha = 0.45f))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    hunk.header,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse hunk" else "Expand hunk",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(fastEffectsSpec()) + fadeIn(fastEffectsSpec()),
            exit = shrinkVertically(fastEffectsSpec()) + fadeOut(fastEffectsSpec()),
        ) {
            val horizontalScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScroll),
            ) {
                hunk.lines.forEach { line ->
                    DiffLineRow(line = line)
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalStatusColors.current

    val (bg, gutterColor, symbol) = when (line.type) {
        DiffLineType.ADD -> Triple(
            status.success.copy(alpha = 0.13f),
            status.success,
            "+",
        )
        DiffLineType.REMOVE -> Triple(
            scheme.error.copy(alpha = 0.13f),
            scheme.error,
            "−",
        )
        DiffLineType.HEADER -> Triple(
            scheme.surfaceContainerHigh.copy(alpha = 0.35f),
            scheme.primary,
            "~",
        )
        DiffLineType.CONTEXT -> Triple(
            Color.Transparent,
            scheme.onSurfaceVariant.copy(alpha = 0.5f),
            " ",
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(vertical = 1.5.dp),
    ) {
        // Gutter: Old Line Number (30dp) + New Line Number (30dp) + Marker (12dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(scheme.surfaceContainerLow.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = line.oldNum?.toString().orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.End,
                modifier = Modifier.width(26.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = line.newNum?.toString().orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.End,
                modifier = Modifier.width(26.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = symbol,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                fontFamily = FontFamily.Monospace,
                color = gutterColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(12.dp),
            )
        }

        Spacer(Modifier.width(6.dp))

        // Code line text
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            fontFamily = FontFamily.Monospace,
            color = when (line.type) {
                DiffLineType.ADD -> status.success
                DiffLineType.REMOVE -> scheme.error
                DiffLineType.HEADER -> scheme.primary
                DiffLineType.CONTEXT -> scheme.onSurface
            },
            softWrap = false,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}
