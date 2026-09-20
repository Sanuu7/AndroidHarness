package com.androidharness.app.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.data.update.UpdateManager
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.theme.HarnessMono
import java.io.File

/**
 * Update dialog driven by [UpdateManager.Step]. Renders release notes with
 * full markdown formatting, download progress, Shizuku-silent vs system-installer
 * paths, and retryable errors.
 */
@Composable
fun UpdateDialog(
    step: UpdateManager.Step,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onOpenSystemInstaller: (File) -> Unit = {},
    onOpenUnknownSources: () -> Unit = {},
) {
    var lastRelease by remember { mutableStateOf<UpdateManager.LatestRelease?>(null) }
    val currentRelease = when (step) {
        is UpdateManager.Step.Available -> step.release
        is UpdateManager.Step.Downloading -> step.release
        is UpdateManager.Step.Installing -> step.release
        is UpdateManager.Step.Error -> step.release
        else -> null
    }
    LaunchedEffect(currentRelease) {
        if (currentRelease != null) lastRelease = currentRelease
    }
    val release = currentRelease ?: lastRelease

    val visible = when (step) {
        is UpdateManager.Step.Available -> true
        is UpdateManager.Step.Downloading -> true
        is UpdateManager.Step.Installing -> true
        is UpdateManager.Step.Done -> true
        is UpdateManager.Step.Error -> release != null
        else -> false
    }
    if (!visible) return

    val isBusy = step is UpdateManager.Step.Downloading || step is UpdateManager.Step.Installing

    Dialog(
        onDismissRequest = {
            if (!isBusy) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 480.dp)
                .heightIn(max = 660.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                UpdateHeader(
                    step = step,
                    release = release,
                    isBusy = isBusy,
                    onDismiss = onDismiss,
                )

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        UpdateBodyContent(step = step, release = release)
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(14.dp))

                UpdateActions(
                    step = step,
                    release = release,
                    onDismiss = onDismiss,
                    onUpdate = onUpdate,
                )
            }
        }
    }
}

@Composable
private fun UpdateHeader(
    step: UpdateManager.Step,
    release: UpdateManager.LatestRelease?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
) {
    val icon = when (step) {
        is UpdateManager.Step.Downloading -> Icons.Outlined.CloudDownload
        is UpdateManager.Step.Installing -> Icons.Outlined.SystemUpdate
        is UpdateManager.Step.Done -> Icons.Outlined.RocketLaunch
        is UpdateManager.Step.Error -> Icons.Outlined.WarningAmber
        else -> Icons.Outlined.SystemUpdate
    }
    val iconColor = when (step) {
        is UpdateManager.Step.Done -> MaterialTheme.colorScheme.primary
        is UpdateManager.Step.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val iconBg = when (step) {
        is UpdateManager.Step.Done -> MaterialTheme.colorScheme.primaryContainer
        is UpdateManager.Step.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = titleFor(step, release),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "GitHub Releases",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isBusy) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun UpdateBodyContent(
    step: UpdateManager.Step,
    release: UpdateManager.LatestRelease?,
) {
    when (step) {
        is UpdateManager.Step.Available -> {
            if (release != null) {
                ReleaseAvailableContent(release)
            }
        }
        is UpdateManager.Step.Downloading -> {
            DownloadProgressContent(step)
        }
        is UpdateManager.Step.Installing -> {
            InstallingContent(step, release)
        }
        is UpdateManager.Step.Done -> {
            DoneContent(step, release)
        }
        is UpdateManager.Step.Error -> {
            ErrorContent(step, release)
        }
        else -> {}
    }
}

@Composable
private fun ReleaseAvailableContent(release: UpdateManager.LatestRelease) {
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = release.tag,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = HarnessMono,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (release.apkBytes > 0L) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "%.1f MB".format(release.apkBytes / 1_048_576f),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = HarnessMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "GitHub",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            if (release.name.isNotBlank() && release.name != release.tag) {
                Text(
                    text = release.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    Text(
        text = "What's new",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )

    val body = release.body.trim()
    if (body.isBlank()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "No release notes provided.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    } else {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(14.dp)) {
                MarkdownText(
                    text = body,
                    onOpenUrl = { url ->
                        val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            "https://github.com/Sanuu7/AndroidHarness/blob/main/$url"
                        } else url
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressContent(step: UpdateManager.Step.Downloading) {
    val animatedProgress by animateFloatAsState(
        targetValue = step.percent / 100f,
        animationSpec = tween(220),
        label = "update-download",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Fetching APK asset from GitHub Releases…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${step.percent}%",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = HarnessMono,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "%.1f / %.1f MB".format(step.mb, step.totalMb),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = HarnessMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstallingContent(step: UpdateManager.Step.Installing, release: UpdateManager.LatestRelease?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (step.viaShizuku) "Installing via Shizuku…" else "Opening system installer…",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (step.viaShizuku) {
                        "Shizuku is installing ${release?.tag ?: "update"} silently. The app will restart when done."
                    } else {
                        "Confirm the package installer prompt to finish updating."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DoneContent(step: UpdateManager.Step.Done, release: UpdateManager.LatestRelease?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (step.viaShizuku) "Installation complete!" else "Ready to install",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (step.viaShizuku) {
                        "${release?.tag ?: "Update"} was installed via Shizuku."
                    } else {
                        "APK staged. Follow installer instructions."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(step: UpdateManager.Step.Error, release: UpdateManager.LatestRelease?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Something went wrong",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = step.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
    if (release != null) {
        Spacer(Modifier.height(8.dp))
        LinkLine(release.htmlUrl, "Open release on GitHub →")
    }
}

@Composable
private fun UpdateActions(
    step: UpdateManager.Step,
    release: UpdateManager.LatestRelease?,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (step) {
            is UpdateManager.Step.Available -> {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Later")
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onUpdate,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Update to ${release?.tag ?: "latest"}")
                }
            }

            is UpdateManager.Step.Downloading -> {
                Text(
                    "Downloading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is UpdateManager.Step.Installing -> {
                Text(
                    "Installing update…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is UpdateManager.Step.Done -> {
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Done")
                }
            }

            is UpdateManager.Step.Error -> {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Close")
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onUpdate,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (step.message == UpdateManager.NEED_INSTALL_PERMISSION) "Try again" else "Retry")
                }
            }

            else -> {
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Close")
                }
            }
        }
    }
}

private fun titleFor(step: UpdateManager.Step, r: UpdateManager.LatestRelease?): String = when (step) {
    is UpdateManager.Step.Checking -> "Checking for updates…"
    is UpdateManager.Step.UpToDate -> "You're up to date"
    is UpdateManager.Step.Available -> "Update available"
    is UpdateManager.Step.Downloading -> "Downloading update"
    is UpdateManager.Step.Installing -> if (step.viaShizuku) "Installing via Shizuku…" else "Opening installer…"
    is UpdateManager.Step.Done -> if (step.viaShizuku) "Installed!" else "Handed to installer"
    is UpdateManager.Step.Error -> "Something went wrong"
    else -> "Update"
}

@Composable
internal fun LinkLine(url: String, label: String) {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.size(4.dp))
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
        )
    }
}

data class LinkSpan(val text: String, val url: String?)

/**
 * Minimal markdown-link parser retained for backwards compatibility:
 * `[label](url)` spans become url-carrying chunks; bare http(s) URLs become
 * their own links. Everything else is text.
 */
internal fun parseLinks(block: String): List<LinkSpan> {
    val out = mutableListOf<LinkSpan>()
    val md = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")
    var cursor = 0
    for (m in md.findAll(block)) {
        if (m.range.first > cursor) {
            out += plainWithUrls(block.substring(cursor, m.range.first))
        }
        out += LinkSpan(m.groupValues[1], m.groupValues[2])
        cursor = m.range.last + 1
    }
    if (cursor < block.length) out += plainWithUrls(block.substring(cursor))
    return out.filter { it.text.isNotBlank() }
}

private fun plainWithUrls(text: String): List<LinkSpan> {
    if (!text.contains("http")) return listOf(LinkSpan(text, null))
    val out = mutableListOf<LinkSpan>()
    val bare = Regex("""https?://[^\s)<>"']+""")
    var cursor = 0
    for (m in bare.findAll(text)) {
        if (m.range.first > cursor) out += LinkSpan(text.substring(cursor, m.range.first), null)
        out += LinkSpan(m.value, m.value)
        cursor = m.range.last + 1
    }
    if (cursor < text.length) out += LinkSpan(text.substring(cursor), null)
    return out
}

/** Renders [spans]; linked spans are colored+underlined and open the browser. */
@Composable
fun SelectionAwareLinkText(spans: List<LinkSpan>, baseStyle: androidx.compose.ui.text.TextStyle) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val annotated = buildAnnotatedString {
        for (span in spans) {
            val start = length
            append(span.text)
            if (span.url != null) {
                addStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = scheme.primary,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    ),
                    start,
                    length,
                )
                addStringAnnotation("URL", span.url, start, length)
            }
        }
    }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = baseStyle,
        maxLines = 8,
        overflow = TextOverflow.Ellipsis,
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset)
                .firstOrNull()
                ?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.item))) }
        },
    )
}

