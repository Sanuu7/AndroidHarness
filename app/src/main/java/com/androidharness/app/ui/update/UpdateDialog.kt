package com.androidharness.app.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.update.UpdateManager
import java.io.File

/**
 * Update dialog driven by [UpdateManager.Step]. Renders release notes with
 * markdown links turned into clickable spans that open in the browser,
 * download progress, Shizuku-silent vs system-installer paths, and errors.
 */
@Composable
fun UpdateDialog(
    step: UpdateManager.Step,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onOpenSystemInstaller: (File) -> Unit,
    onOpenUnknownSources: () -> Unit,
) {
    val release = when (step) {
        is UpdateManager.Step.Available -> step.release
        is UpdateManager.Step.Downloading -> step.release
        is UpdateManager.Step.Installing -> step.release
        is UpdateManager.Step.Error -> step.release
        else -> null
    } ?: return

    AlertDialog(
        onDismissRequest = {
            // Don't let a stray tap cancel an in-flight install.
            if (step !is UpdateManager.Step.Downloading && step !is UpdateManager.Step.Installing) {
                onDismiss()
            }
        },
        icon = {
            Icon(
                Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(titleFor(step, release)) },
        text = { UpdateBody(step, release) },
        confirmButton = {
            when (step) {
                is UpdateManager.Step.Available -> Button(onClick = onUpdate) {
                    Text("Update to ${release.tag}")
                }
                is UpdateManager.Step.Error -> if (step.message == UpdateManager.NEED_INSTALL_PERMISSION) {
                    Button(onClick = onUpdate) { Text("Try again") }
                } else Button(onClick = onUpdate) { Text("Retry") }
                else -> {}
            }
        },
        dismissButton = {
            when (step) {
                is UpdateManager.Step.Downloading, is UpdateManager.Step.Installing -> {}
                else -> OutlinedButton(onClick = onDismiss) { Text(dismissLabel(step)) }
            }
        },
    )
}

private fun titleFor(step: UpdateManager.Step, r: UpdateManager.LatestRelease): String = when (step) {
    is UpdateManager.Step.Checking -> "Checking for updates…"
    is UpdateManager.Step.UpToDate -> "You're up to date"
    is UpdateManager.Step.Available -> "Update available"
    is UpdateManager.Step.Downloading -> "Downloading ${r.tag}"
    is UpdateManager.Step.Installing -> if (step.viaShizuku) "Installing via Shizuku…" else "Opening installer…"
    is UpdateManager.Step.Done -> if (step.viaShizuku) "Installed!" else "Handed to installer"
    is UpdateManager.Step.Error -> "Something went wrong"
    else -> "Update"
}

private fun dismissLabel(step: UpdateManager.Step): String = when (step) {
    is UpdateManager.Step.UpToDate -> "Nice"
    is UpdateManager.Step.Done -> "Done"
    is UpdateManager.Step.Error -> "Close"
    else -> "Later"
}

@Composable
private fun UpdateBody(step: UpdateManager.Step, r: UpdateManager.LatestRelease) {
    Column(Modifier.fillMaxWidth()) {
        when (step) {
            is UpdateManager.Step.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text("Asking GitHub for the latest release…")
            }

            is UpdateManager.Step.UpToDate -> Text(
                "This build (${step.current}) matches the latest published release.",
            )

            is UpdateManager.Step.Available -> ReleaseNotes(step.release)

            is UpdateManager.Step.Downloading -> DownloadProgress(step.percent, step.mb, step.totalMb)

            is UpdateManager.Step.Installing -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (!step.viaShizuku) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                }
                Text(
                    if (step.viaShizuku) {
                        "Shizuku is installing ${r.tag} silently. The app restarts when it's done."
                    } else {
                        "Confirm the platform prompt to finish installing ${r.tag}."
                    },
                )
            }

            is UpdateManager.Step.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.RocketLaunch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(if (step.viaShizuku) "${r.tag} installed via Shizuku." else "${r.tag} staged.")
            }

            is UpdateManager.Step.Error -> {
                Text(step.message, color = MaterialTheme.colorScheme.error)
                LinkLine(r.htmlUrl, "Open the release page on GitHub →")
            }

            else -> {}
        }
    }
}

@Composable
private fun DownloadProgress(percent: Int, mb: Float, totalMb: Float) {
    val animated by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(220),
        label = "update-download",
    )
    Text("Fetching the new APK from GitHub Releases…", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))
    LinearProgressIndicator(
        progress = { animated },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "$mb / $totalMb MB",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Title + clickable-link rendering of the release body. */
@Composable
private fun ReleaseNotes(release: UpdateManager.LatestRelease) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "${release.name.ifBlank { release.tag }} · ${release.tag}",
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
    }
    Spacer(Modifier.height(10.dp))

    val body = release.body.trim()
    if (body.isBlank()) {
        Text("No release notes provided.", style = MaterialTheme.typography.bodySmall)
    } else {
        val blocks = body.split("\n\n").take(4)
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .heightIn(max = 210.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            blocks.forEach { block ->
                val linkSpans = remember(block) { parseLinks(block) }
                SelectionAwareLinkText(linkSpans, baseStyle = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (release.apkName != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "${release.apkName} · %.1f MB".format(release.apkBytes / 1_048_576f),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    LinkLine(release.htmlUrl, "Full release on GitHub →")
}

@Composable
internal fun LinkLine(url: String, label: String) {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .padding(vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.size(4.dp))
        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp),
        )
    }
}

data class LinkSpan(val text: String, val url: String?)

/**
 * Minimal markdown-link parser: `[label](url)` spans become url-carrying
 * chunks; bare http(s) URLs become their own links. Everything else is text.
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
                // Annotation marks WHICH url this exact range maps to.
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
