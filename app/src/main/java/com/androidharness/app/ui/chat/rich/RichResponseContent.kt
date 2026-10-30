package com.androidharness.app.ui.chat.rich

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.ui.theme.harnessGlassControl
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ResponseContextChip(title: String, onRemove: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    InputChip(selected = false, onClick = onRemove,
        label = { Text(uiContext.getString(R.string.ui_response, (title).toString()), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Filled.Close, uiContext.getString(R.string.ui_remove_response_context), Modifier.size(14.dp)) },
        modifier = Modifier.padding(horizontal = 10.dp).widthIn(max = 340.dp))
}

/** One assistant response can alternate prose and native/interactive content, in source order. */
@Composable
internal fun RichResponseContent(text: String, streaming: Boolean, onOpenUrl: ((String) -> Unit)?, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val parts = remember(text, streaming) { RichResponse.parse(text, streaming) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEachIndexed { index, part -> key(index) {
            when (part) {
                // Keep the same composition when streaming finishes. Changing this
                // wrapper used to destroy and reload every math WebView in the reply.
                is RichResponse.Part.Markdown -> SelectionContainer {
                    MarkdownText(part.text, streaming && index == parts.lastIndex, onOpenUrl)
                }
                is RichResponse.Part.Content -> ResponseNode(part.node, onOpenUrl, if (streaming) null else onFollowUp)
                RichResponse.Part.Pending -> Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text(uiContext.getString(R.string.ui_preparing_visual), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is RichResponse.Part.PendingVisual -> PendingVisualCard(part.title, part.loading)
                RichResponse.Part.Unavailable -> ResponsePanel {
                    Text(uiContext.getString(R.string.ui_this_content_couldn_t_be_displayed), style = MaterialTheme.typography.titleSmall)
                    Text(uiContext.getString(R.string.ui_you_can_ask_for_a_simpler_version), style = MaterialTheme.typography.bodySmall)
                    onFollowUp?.let { TextButton(onClick = { it(RichResponse.followUp("Please show this as a clear text explanation.", "Response content", "A rich content block couldn’t be displayed.")) }) { Text(uiContext.getString(R.string.ui_ask_for_a_text_version)) } }
                }
            }
        } }
    }
}

/** Holds the visual's place while its markup streams, so the reply doesn't jump when it renders. */
@Composable
private fun PendingVisualCard(title: String, loading: String) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.ifBlank { uiContext.getString(R.string.ui_preparing_visual) }, style = MaterialTheme.typography.labelLarge)
            Box(Modifier.fillMaxWidth().height(140.dp).background(scheme.surfaceContainerLow, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                com.androidharness.app.ui.common.CatalogLoadingIndicator(Modifier.size(36.dp))
            }
            Text(loading.ifBlank { uiContext.getString(R.string.ui_preparing_visual) }, style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
internal fun ResponsePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun ResponseNode(node: RichResponse.Node, onOpenUrl: ((String) -> Unit)?, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    val contextChange = LocalResponseContext.current
    when (node) {
        is RichResponse.Node.Text -> SelectionContainer { MarkdownText(node.text, onOpenUrl = onOpenUrl) }
        is RichResponse.Node.Callout -> {
            val accent = when (node.tone) { "warning" -> colors.tertiary; "success" -> colors.primary; else -> colors.primary }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.07f), border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(node.title, style = MaterialTheme.typography.titleSmall, color = accent)
                    SelectionContainer { MarkdownText(node.text, onOpenUrl = onOpenUrl) }
                }
            }
        }
        is RichResponse.Node.Card -> ResponsePanel {
            Text(node.title, style = MaterialTheme.typography.titleSmall)
            if (node.text.isNotBlank()) SelectionContainer { MarkdownText(node.text, onOpenUrl = onOpenUrl) }
            node.children.forEachIndexed { index, child -> key(index) { ResponseNode(child, onOpenUrl, onFollowUp) } }
            if (node.actions.isNotEmpty() && onFollowUp != null) {
                // Stack at narrow widths and large text rather than squeezing action labels.
                node.actions.forEach { action -> OutlinedButton(onClick = { onFollowUp(RichResponse.followUp(action.prompt, node.title, RichResponse.describe(node))) }, modifier = Modifier.fillMaxWidth().harnessGlassControl()) { Text(action.label) } }
            }
        }
        is RichResponse.Node.Metrics -> AdaptiveItems(node.items.size) { index ->
            val metric = node.items[index]
            Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(metric.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(metric.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                    if (metric.detail.isNotBlank()) Text(metric.detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        is RichResponse.Node.Chart -> ResponseChart(node, onFollowUp)
        is RichResponse.Node.Choices -> {
            var selected by rememberSaveable(node) { mutableIntStateOf(0) }
            ResponsePanel {
                Text(node.title, style = MaterialTheme.typography.titleSmall)
                node.options.forEachIndexed { index, option ->
                    Surface(shape = RoundedCornerShape(10.dp), color = if (selected == index) colors.primary.copy(alpha = 0.07f) else colors.surface,
                        border = BorderStroke(1.dp, if (selected == index) colors.primary.copy(alpha = 0.45f) else colors.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().selectable(selected == index, role = Role.RadioButton, onClick = { selected = index; contextChange?.invoke(node.title, "Selected: ${option.label}\n${option.description}") }).padding(10.dp), verticalAlignment = Alignment.Top) {
                            RadioButton(selected == index, null, Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).heightIn(min = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(option.label, style = MaterialTheme.typography.titleSmall)
                                if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                onFollowUp?.let { follow -> val option = node.options[selected.coerceIn(node.options.indices)]
                    OutlinedButton(onClick = { follow(RichResponse.followUp(option.prompt.ifBlank { "Tell me more about ${option.label}." }, node.title, "Selected: ${option.label}\n${option.description}")) }, Modifier.fillMaxWidth().harnessGlassControl()) { Text(uiContext.getString(R.string.ui_discuss_selection)) }
                }
            }
        }
        is RichResponse.Node.Steps -> {
            var checked by rememberSaveable(node) { mutableStateOf(node.items.map { it.done }) }
            val contextTitle = node.title.ifBlank { node.items.first().title }
            ResponsePanel {
                if (node.title.isNotBlank()) Text(node.title, style = MaterialTheme.typography.titleSmall)
                node.items.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth().then(if (node.checkable) Modifier.toggleable(checked[index], role = Role.Checkbox,
                        onValueChange = { value -> checked = checked.toMutableList().also { it[index] = value }; contextChange?.invoke(contextTitle, node.items.mapIndexed { i, step -> "${if (checked[i]) "Done" else "Remaining"}: ${step.title}" }.joinToString("\n")) }) else Modifier).padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        if (node.checkable) Checkbox(checked[index], null, Modifier.size(40.dp))
                        else Text("${index + 1}.", Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f).heightIn(min = 44.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(step.title, style = MaterialTheme.typography.bodyMedium)
                            if (step.text.isNotBlank()) Text(step.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                if (node.checkable) onFollowUp?.let { follow -> TextButton(onClick = {
                    val state = node.items.mapIndexed { i, step -> "${if (checked[i]) uiContext.getString(R.string.settings_action_done) else uiContext.getString(R.string.ui_remaining)}: ${step.title}" }.joinToString("\n")
                    follow(RichResponse.followUp("Help me with the next step.", contextTitle, state))
                }) { Text(uiContext.getString(R.string.ui_continue_from_here)) } }
            }
        }
        is RichResponse.Node.Details -> {
            var expanded by rememberSaveable(node) { mutableStateOf(false) }
            ResponsePanel {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    Text(node.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (expanded) uiContext.getString(R.string.ui_collapse_details) else uiContext.getString(R.string.ui_expand_details))
                }
                if (expanded) SelectionContainer { MarkdownText(node.text, onOpenUrl = onOpenUrl) }
            }
        }
        is RichResponse.Node.Link -> ResponsePanel {
            Row(Modifier.fillMaxWidth().let { if (onOpenUrl == null) it else it.clickable { onOpenUrl(node.url) } }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(node.title, style = MaterialTheme.typography.titleSmall)
                    if (node.text.isNotBlank()) Text(node.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text(java.net.URI(node.url).host, style = MaterialTheme.typography.labelSmall, color = colors.primary)
                }
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, uiContext.getString(R.string.ui_open_link), Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
        is RichResponse.Node.Preview -> InlineResponsePreview(node, onFollowUp)
        is RichResponse.Node.Layout -> if (node.columns) AdaptiveItems(node.children.size) { ResponseNode(node.children[it], onOpenUrl, onFollowUp) }
            else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { node.children.forEachIndexed { i, child -> key(i) { ResponseNode(child, onOpenUrl, onFollowUp) } } }
        is RichResponse.Node.Table -> ResponseTable(node, onFollowUp)
        is RichResponse.Node.Tabs -> ResponseTabs(node, onOpenUrl, onFollowUp)
        is RichResponse.Node.Gallery -> ResponseGallery(node, onOpenUrl, onFollowUp)
        is RichResponse.Node.Profile -> ResponseProfile(node, onOpenUrl)
        is RichResponse.Node.Calculator -> ResponseCalculator(node, onFollowUp)
        is RichResponse.Node.Evidence -> ResponseEvidence(node, onOpenUrl)
        is RichResponse.Node.Diff -> ResponseDiffView(node, onFollowUp)
        is RichResponse.Node.Writing -> ResponseWriting(node)
        RichResponse.Node.Divider -> HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
private fun AdaptiveItems(count: Int, item: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val columns = if (maxWidth >= (360 * fontScale).dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (start in 0 until count step columns) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (index in start until minOf(start + columns, count)) Box(Modifier.weight(1f)) { item(index) }
                if (columns == 2 && start + 1 == count) Spacer(Modifier.weight(1f))
            }
        }
    }
}
