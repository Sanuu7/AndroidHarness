package com.androidharness.app.ui.chat.rich

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.ui.chat.LocalChatImagesEnabled
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.chat.RemoteContentLoader
import com.androidharness.app.ui.chat.components.ImageViewerDialog
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ResponseProfile(profile: RichResponse.Node.Profile, onOpenUrl: ((String) -> Unit)?) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val open: (String) -> Unit = onOpenUrl ?: { url -> runCatching { uri.openUri(url) }; Unit }
    val colors = MaterialTheme.colorScheme
    val showImage = LocalChatImagesEnabled.current && profile.image.isNotBlank()
    var imageExpanded by rememberSaveable(profile.image) { mutableStateOf(false) }
    var details by rememberSaveable(profile.name) { mutableStateOf(false) }
    var sources by rememberSaveable(profile.name) { mutableStateOf(false) }
    LaunchedEffect(showImage) { if (!showImage) imageExpanded = false }
    val photo by produceState<Bitmap?>(null, profile.image, showImage) {
        value = if (showImage) RemoteContentLoader.loadImage(context, profile.image) else null
    }
    if (imageExpanded && showImage) ImageViewerDialog(profile.image, profile.imageSource, { imageExpanded = false }, { url -> imageExpanded = false; open(url) })
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .35f)),
        modifier = Modifier.fillMaxWidth().animateContentSize(expressiveSpatialSpec())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                photo?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), profile.name, Modifier.size(width = 80.dp, height = 96.dp).clip(RoundedCornerShape(18.dp))
                        .clickable(onClickLabel = context.getString(R.string.rich_expand_image)) { imageExpanded = true }, contentScale = ContentScale.Crop)
                }
                SelectionContainer(Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleLarge)
                        if (profile.subtitle.isNotBlank()) Text(profile.subtitle, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                }
            }
            if (profile.description.isNotBlank()) SelectionContainer {
                Text(profile.description.replace("**", "").replace("__", ""), style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (details) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
            if (profile.facts.isNotEmpty() || profile.description.length > 180 || profile.sources.isNotEmpty() || profile.imageSource.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (profile.facts.isNotEmpty() || profile.description.length > 180) TextButton(onClick = { details = !details }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(stringResource(R.string.rich_profile_details))
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    }
                    if (profile.sources.isNotEmpty() || profile.imageSource.isNotBlank()) TextButton(onClick = { sources = !sources }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.rich_profile_sources))
                    }
                }
            }
            AnimatedVisibility(details && profile.facts.isNotEmpty(), enter = fadeIn() + expandVertically(expressiveSpatialSpec()),
                exit = fadeOut() + shrinkVertically(expressiveSpatialSpec())) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    profile.facts.forEach { fact ->
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .35f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(fact.label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    Text(fact.value, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (fact.source.isNotBlank()) IconButton(onClick = { open(fact.source) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.rich_open_source), Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(sources) {
                Column {
                    profile.sources.forEach { source -> TextButton(onClick = { open(source.url) }) { Text(source.title) } }
                    if (showImage && profile.imageSource.isNotBlank() && profile.imageSource !in profile.sources.map { it.url })
                        TextButton(onClick = { open(profile.imageSource) }) { Text(stringResource(R.string.rich_open_source)) }
                }
            }
        }
    }
}
