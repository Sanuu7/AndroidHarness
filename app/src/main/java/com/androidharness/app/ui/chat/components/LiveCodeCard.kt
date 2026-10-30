package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.common.localizedOptionLabel
import com.androidharness.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.CodeDraftStage
import com.androidharness.app.agent.LiveCodeDraft
import com.androidharness.app.agent.LiveCodeFile
import com.androidharness.app.agent.LiveCodeLine
import com.androidharness.app.ui.chat.UserScrollObserver
import com.androidharness.app.ui.common.formatTokens
import com.androidharness.app.ui.files.CodeTokenColors
import com.androidharness.app.ui.files.CodeTokenizer
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.theme.fastEffectsSpec
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun LiveCodeCard(draft: LiveCodeDraft, awaitingApproval: Boolean = false,
    onOpenFile: ((String, Int?) -> Unit)? = null) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val action = when {
        awaitingApproval -> uiContext.getString(R.string.ui_awaiting_approval)
        draft.stage == CodeDraftStage.FAILED -> uiContext.getString(R.string.ui_not_saved)
        draft.tool == "write_file" -> if (draft.stage == CodeDraftStage.SAVED) uiContext.getString(R.string.ui_write) else uiContext.getString(R.string.ui_writing)
        else -> if (draft.stage == CodeDraftStage.SAVED) uiContext.getString(R.string.settings_action_edit) else uiContext.getString(R.string.ui_editing)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        draft.workspaceName?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            color = scheme.primary, modifier = Modifier.padding(horizontal = 2.dp)) }
        draft.files.ifEmpty { listOf(LiveCodeFile(uiContext.getString(R.string.ui_file_name_arriving), 0, 0, emptyList())) }.forEach { file ->
            key(draft.id, file.path) {
                var expanded by rememberSaveable(draft.id, file.path) { mutableStateOf(false) }
                Column {
                    Surface(onClick = { expanded = !expanded }, color = Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().semantics { stateDescription = uiContext.localizedOptionLabel(draft.stage.label) }) {
                        BoxWithConstraints(Modifier.padding(horizontal = 2.dp, vertical = 6.dp).heightIn(min = 36.dp)) {
                            val narrow = maxWidth < 360.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                                    Text(action, style = MaterialTheme.typography.bodySmall,
                                        color = if (draft.stage == CodeDraftStage.FAILED) scheme.error else scheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = if (narrow) Modifier.weight(1f) else Modifier)
                                    if (!narrow) CodeFileName(file.path, Modifier.weight(1f), showFolder = true)
                                    if (draft.comparisonAvailable || draft.stage != CodeDraftStage.SAVED) {
                                        if (file.added > 0 || file.removed == 0) AnimatedLineCount(file.added, '+', LocalStatusColors.current.success)
                                        if (file.removed > 0) AnimatedLineCount(file.removed, '−', scheme.error)
                                    }
                                    Icon(Icons.Default.KeyboardArrowDown,
                                        if (expanded) uiContext.getString(R.string.ui_collapse_code_preview) else uiContext.getString(R.string.ui_expand_code_preview),
                                        Modifier.size(16.dp).rotate(if (expanded) 180f else 0f), tint = scheme.onSurfaceVariant)
                                }
                                if (narrow) CodeFileName(file.path, Modifier.padding(start = 24.dp), showFolder = false)
                            }
                        }
                    }
                    AnimatedVisibility(expanded, enter = fadeIn(fastEffectsSpec()) + expandVertically(fastEffectsSpec()),
                        exit = fadeOut(fastEffectsSpec()) + shrinkVertically(fastEffectsSpec())) {
                        val vertical = rememberScrollState()
                        val horizontal = rememberScrollState()
                        var following by remember(draft.id, file.path) { mutableStateOf(true) }
                        val observer = remember(draft.id, file.path) { UserScrollObserver { following = false } }
                        LaunchedEffect(following, draft.stage) {
                            if (following && draft.stage == CodeDraftStage.GENERATING) snapshotFlow { vertical.maxValue }.collect {
                                if (following && !vertical.isScrollInProgress && it != Int.MAX_VALUE) vertical.scrollTo(it)
                            }
                        }
                        Column(Modifier.padding(start = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${codeLanguage(file.path)} · ${file.path}", style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant)
                            Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                                com.androidharness.app.ui.common.LeftToRightContent {
                                    SelectionContainer {
                                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).nestedScroll(observer)
                                            .verticalScroll(vertical).horizontalScroll(horizontal).padding(vertical = 6.dp)) {
                                            file.lines.forEach { CodePreviewLine(it, file.path) }
                                        }
                                    }
                                }
                            }
                            Text(uiContext.getString(R.string.ui_tool_tokens_estimated, (formatTokens(draft.estimatedTokens)).toString()),
                                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            draft.responseOutputTokens?.let { tokens ->
                                Text(uiContext.getString(R.string.ui_response_output_tokens, (formatTokens(tokens)).toString()), style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant)
                            }
                            if (!draft.comparisonAvailable) Text(uiContext.getString(R.string.ui_generated_code_original_file_comparison_unavailable),
                                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            if (draft.limited || file.lines.size >= 80) Text(uiContext.getString(R.string.ui_showing_a_bounded_code_preview),
                                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            if (draft.stage != CodeDraftStage.SAVED) Text(uiContext.getString(R.string.ui_draft_not_saved_yet),
                                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            if (draft.responseOutputTokens != null) Text(uiContext.getString(R.string.ui_response_tokens_include_reasoning_and_all_generated),
                                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            if (draft.stage == CodeDraftStage.SAVED && onOpenFile != null) TextButton(onClick = { onOpenFile(file.path, null) }) {
                                Text(uiContext.getString(R.string.ui_open_file))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeFileName(path: String, modifier: Modifier, showFolder: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val name = path.substringAfterLast('/')
    val folder = path.substringBeforeLast('/', "")
    val label = androidx.compose.ui.text.buildAnnotatedString {
        append(name)
        if (showFolder && folder.isNotBlank()) {
            pushStyle(SpanStyle(color = scheme.onSurfaceVariant)); append("  $folder/"); pop()
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if ('.' in name) Text(name.substringAfterLast('.').uppercase().take(3),
            style = MaterialTheme.typography.labelSmall, fontFamily = HarnessMono, color = scheme.primary)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!showFolder && folder.isNotBlank()) Text("$folder/", style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AnimatedLineCount(count: Int, sign: Char, color: Color) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val displayed by animateIntAsState(count, fastEffectsSpec(), label = "live line count")
    Text("$sign$displayed", style = MaterialTheme.typography.labelMedium, fontFamily = HarnessMono, color = color,
        modifier = Modifier.semantics { contentDescription = uiContext.getString(R.string.ui_lines, (count).toString(), (if (sign == '+') uiContext.getString(R.string.ui_added_suffix) else uiContext.getString(R.string.ui_removed_suffix)).toString()) })
}

@Composable
private fun CodePreviewLine(line: LiveCodeLine, path: String) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (line.sign) { '+' -> LocalStatusColors.current.success; '-' -> scheme.error; else -> scheme.onSurface }
    val dark = CodeTokenColors.isDarkSurface(scheme.surface)
    val highlighted = remember(line.text, path, dark, scheme.onSurface) {
        AnnotatedString.Builder(line.text).apply {
            CodeTokenizer.tokenizeLine(CodeTokenizer.langFor(path.lowercase()), line.text).forEach { token ->
                if (token.start >= 0 && token.end <= line.text.length) addStyle(
                    SpanStyle(color = CodeTokenColors.of(token.type, dark, scheme.onSurface)), token.start, token.end)
            }
        }.toAnnotatedString()
    }
    Row(Modifier.fillMaxWidth().background(if (line.sign == ' ') Color.Transparent else tint.copy(alpha = 0.08f))
        .padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(if (line.sign == '-') "− " else "${line.sign} ", color = tint, fontFamily = HarnessMono,
            style = MaterialTheme.typography.bodySmall)
        Text(highlighted, fontFamily = HarnessMono, style = MaterialTheme.typography.bodySmall, softWrap = false)
    }
}

private fun codeLanguage(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "Kotlin"; "java" -> "Java"; "py", "pyw" -> "Python"
    "ts", "tsx" -> "TypeScript"; "js", "jsx", "mjs", "cjs" -> "JavaScript"
    "html", "htm", "vue", "svelte" -> "HTML"; "xml", "svg" -> "XML"
    "css", "scss", "sass", "less" -> "CSS"; "json", "jsonc", "json5" -> "JSON"
    "yml", "yaml" -> "YAML"; "sh", "bash", "zsh" -> "Shell"; "md" -> "Markdown"
    "rs" -> "Rust"; "go" -> "Go"; "c", "h" -> "C"; "cpp", "hpp", "cc" -> "C++"
    else -> "Code"
}
