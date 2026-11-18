package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.shape.CircleShape

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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawOutline
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.workspaceName?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            color = scheme.primary, modifier = Modifier.padding(horizontal = 2.dp)) }
        draft.files.ifEmpty { listOf(LiveCodeFile(uiContext.getString(R.string.ui_file_name_arriving), 0, 0, emptyList())) }.forEach { file ->
            key(draft.id, file.path) {
                var expanded by rememberSaveable(draft.id, file.path) { mutableStateOf(false) }
                val workState = when {
                    awaitingApproval -> WorkState.Waiting
                    draft.stage == CodeDraftStage.FAILED -> WorkState.Failed
                    draft.stage == CodeDraftStage.SAVED -> WorkState.Done
                    else -> WorkState.Running
                }
                val streaming = !awaitingApproval && (draft.stage == CodeDraftStage.GENERATING || draft.stage == CodeDraftStage.WRITING)
                CodeCard(onClick = { expanded = !expanded }, expanded = expanded,
                    accent = workState.takeIf { it == WorkState.Failed || it == WorkState.Waiting },
                    modifier = Modifier.liveCodeGlow(streaming, if (expanded) 28.dp else 22.dp)
                        .semantics { stateDescription = uiContext.localizedOptionLabel(draft.stage.label) }) {
                    BoxWithConstraints(Modifier.padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
                        val narrow = maxWidth < 360.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MorphingStatusBadge(workState, Icons.Outlined.Edit)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                CodeFileName(file.path, Modifier, showFolder = !narrow)
                                val liveAction = when {
                                    !streaming -> action
                                    draft.stage == CodeDraftStage.GENERATING && file.lines.isEmpty() -> uiContext.getString(R.string.code_live_waiting_for_code)
                                    draft.tool == "write_file" || file.removed == 0 -> uiContext.getString(R.string.code_live_writing_line, file.added + 1)
                                    else -> uiContext.getString(R.string.code_live_editing_lines, file.removed)
                                }
                                androidx.compose.animation.AnimatedContent(liveAction, transitionSpec = {
                                    (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it / 2 } +
                                        fadeIn(androidx.compose.animation.core.tween(160))) togetherWith fadeOut(androidx.compose.animation.core.tween(90))
                                }, label = "Live code action") { text ->
                                    if (streaming) ChatShimmerText(text, running = true, style = MaterialTheme.typography.bodySmall)
                                    else Text(text, style = MaterialTheme.typography.bodySmall,
                                        color = if (draft.stage == CodeDraftStage.FAILED) scheme.error else scheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (draft.comparisonAvailable || draft.stage != CodeDraftStage.SAVED) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.background(scheme.surfaceContainerHighest, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                    if (file.added > 0 || file.removed == 0) AnimatedLineCount(file.added, '+', LocalStatusColors.current.success)
                                    if (file.removed > 0) AnimatedLineCount(file.removed, '−', scheme.error)
                                }
                            }
                            ExpandChevron(expanded,
                                if (expanded) uiContext.getString(R.string.ui_collapse_code_preview) else uiContext.getString(R.string.ui_expand_code_preview))
                        }
                    }
                    // Collapsed while generating: the newest lines stream past like an editor.
                    AnimatedVisibility(streaming && !expanded && file.lines.isNotEmpty(),
                        enter = fadeIn(fastEffectsSpec()) + expandVertically(bouncySpec()),
                        exit = fadeOut(fastEffectsSpec()) + shrinkVertically(fastEffectsSpec())) {
                        LiveCodeTail(file)
                    }
                    AnimatedVisibility(expanded, enter = fadeIn(fastEffectsSpec()) + expandVertically(bouncySpec()),
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
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${codeLanguage(file.path)} · ${file.path}", style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant)
                            Surface(color = scheme.surfaceContainerLowest, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
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
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!showFolder && folder.isNotBlank()) Text("$folder/", style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Odometer count: each changed digit rolls up (or down) on its own; a change also gives the number a small kick. */
@Composable
private fun AnimatedLineCount(count: Int, sign: Char, color: Color) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val kick = remember { androidx.compose.animation.core.Animatable(1f) }
    var seen by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count == seen) return@LaunchedEffect
        seen = count
        kick.snapTo(1.18f)
        kick.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f))
    }
    val style = MaterialTheme.typography.labelMedium.copy(fontFamily = HarnessMono, color = color)
    Row(Modifier.graphicsLayer { scaleX = kick.value; scaleY = kick.value }
        .semantics(mergeDescendants = true) { contentDescription = uiContext.getString(R.string.ui_lines, (count).toString(), (if (sign == '+') uiContext.getString(R.string.ui_added_suffix) else uiContext.getString(R.string.ui_removed_suffix)).toString()) }) {
        Text("$sign", style = style)
        val digits = count.toString()
        digits.forEachIndexed { index, digit ->
            // Keyed from the right so the ones digit stays the same slot as the number grows.
            key(digits.length - index) {
                androidx.compose.animation.AnimatedContent(digit, transitionSpec = {
                    val up = targetState > initialState || (initialState == '9' && targetState == '0')
                    (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(160)) { if (up) it else -it } +
                        fadeIn(androidx.compose.animation.core.tween(140))) togetherWith
                        (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(140)) { if (up) -it else it } +
                            fadeOut(androidx.compose.animation.core.tween(100)))
                }, label = "Line count digit") { Text("$it", style = style) }
            }
        }
    }
}

/** A light sweeping along the card's edge while code is still arriving, so a write never reads as just another tool row. */
@Composable
private fun Modifier.liveCodeGlow(active: Boolean, radius: androidx.compose.ui.unit.Dp): Modifier {
    if (!active) return this
    val scheme = MaterialTheme.colorScheme
    val sweep by androidx.compose.animation.core.rememberInfiniteTransition(label = "Live code glow").animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800,
            easing = androidx.compose.animation.core.LinearEasing)), label = "Live code sweep")
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(radius)
    return this.drawWithCache {
        val w = size.width
        val x = -w * 0.4f + sweep * w * 1.8f
        val brush = androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(scheme.primary.copy(alpha = 0.18f), scheme.primary, scheme.tertiary, scheme.primary.copy(alpha = 0.18f)),
            start = androidx.compose.ui.geometry.Offset(x - w * 0.35f, 0f), end = androidx.compose.ui.geometry.Offset(x + w * 0.35f, size.height),
            tileMode = androidx.compose.ui.graphics.TileMode.Clamp)
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            drawOutline(outline, brush, style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
        }
    }
}

/** The last few generated lines, newest at the bottom with a blinking caret; each new line slides up into place. */
@Composable
private fun LiveCodeTail(file: LiveCodeFile) {
    val scheme = MaterialTheme.colorScheme
    val tail = file.lines.takeLast(4)
    val firstIndex = file.lines.size - tail.size
    val caret by androidx.compose.animation.core.rememberInfiniteTransition(label = "Live code caret").animateFloat(
        1f, 0f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.keyframes {
            durationMillis = 1000
            1f at 0; 1f at 499; 0f at 500; 0f at 999
        }), label = "Live code caret blink")
    Surface(color = scheme.surfaceContainerLowest, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
        com.androidharness.app.ui.common.LeftToRightContent {
            Column(Modifier.fillMaxWidth().height(86.dp).padding(vertical = 6.dp), verticalArrangement = Arrangement.Bottom) {
                tail.forEachIndexed { offset, line ->
                    key(firstIndex + offset) {
                        val enter = remember { androidx.compose.animation.core.Animatable(if (file.lines.size > 1) 0f else 1f) }
                        LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f)) }
                        // Older lines fade back so the eye stays on the one being written.
                        val age = tail.size - 1 - offset
                        Box(Modifier.graphicsLayer {
                            alpha = enter.value * (1f - age * 0.2f)
                            translationY = (1f - enter.value) * 14.dp.toPx()
                        }) {
                            CodePreviewLine(line, file.path, caret = if (age == 0) ({ caret }) else null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodePreviewLine(line: LiveCodeLine, path: String, caret: (() -> Float)? = null) {
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
        if (caret != null) Box(Modifier.align(Alignment.CenterVertically).padding(start = 1.dp).width(7.dp).height(14.dp)
            .graphicsLayer { alpha = caret() }.background(scheme.primary))
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
