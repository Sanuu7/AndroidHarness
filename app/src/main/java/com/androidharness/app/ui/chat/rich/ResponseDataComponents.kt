package com.androidharness.app.ui.chat.rich

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.ui.theme.*

import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.androidharness.app.ui.chat.AnswerTableSurface
import com.androidharness.app.ui.chat.AnswerTableScrollHint
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/** Latest explicitly changed component state can accompany a normally typed follow-up. */
internal val LocalResponseContext = compositionLocalOf<((String, String) -> Unit)?> { null }
internal val LocalResponseFileOpen = compositionLocalOf<((String, Int?) -> Unit)?> { null }

@Composable
internal fun ResponseTable(table: RichResponse.Node.Table, onFollowUp: ((RichResponse.FollowUp) -> Unit)?, allowExpand: Boolean = true) {
    var query by rememberSaveable(table) { mutableStateOf("") }
    var sort by rememberSaveable(table) { mutableIntStateOf(-1) }
    var ascending by rememberSaveable(table) { mutableStateOf(true) }
    var selected by rememberSaveable(table) { mutableStateOf(List(table.rows.size) { false }) }
    var selecting by rememberSaveable(table) { mutableStateOf(false) }
    var showFilter by rememberSaveable(table) { mutableStateOf(false) }
    var showSort by rememberSaveable(table) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var page by rememberSaveable(table) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(table) { mutableStateOf(false) }
    if (expanded) ExpandedResponseTable(table, onDismiss = { expanded = false }, onFollowUp = onFollowUp)
    val contextChange = LocalResponseContext.current
    val ui = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val tableScroll = rememberScrollState()
    fun context(): String = buildString {
        append("Filter: ").append(query.ifBlank { "all" }).append("\n")
        if (sort >= 0) append("Sort: ${table.columns[sort].ifBlank { (sort + 1).toString() }} ${if (ascending) "ascending" else "descending"}\n")
        append(table.columns.joinToString(" | ")).append('\n')
        val chosen = table.rows.indices.filter { selected[it] }
        append(if (chosen.isEmpty()) "No rows selected." else "Selected rows:\n" + chosen.joinToString("\n") { table.rows[it].joinToString(" | ") })
    }
    fun select(index: Int, checked: Boolean) {
        selected = selected.toMutableList().also { it[index] = checked }
        contextChange?.invoke(table.title, context())
    }
    val filtered = remember(table, query, sort, ascending) { com.androidharness.app.core.response.ResponseTableData.indices(table, query, sort, ascending) }
    val pages = ((filtered.size + 9) / 10).coerceAtLeast(1)
    val visible = filtered.drop(page.coerceAtMost(pages - 1) * 10).take(10)
    AnswerTableSurface {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(table.title, style = MaterialTheme.typography.titleSmall)
                Text(table.columns.filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(androidx.compose.material.icons.Icons.Outlined.MoreHoriz,
                    stringResource(R.string.rich_table_options)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (allowExpand) DropdownMenuItem(text = { Text(stringResource(R.string.answer_table_expand)) }, onClick = { expanded = true; menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rich_filter_rows)) }, onClick = { showFilter = !showFilter; menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rich_sort_rows)) }, onClick = { showSort = !showSort; menu = false })
                    DropdownMenuItem(text = { Text(stringResource(if (selecting) R.string.rich_stop_selecting_rows else R.string.rich_select_rows)) }, onClick = {
                        selecting = !selecting; menu = false
                        if (!selecting && selected.any { it }) { selected = List(table.rows.size) { false }; contextChange?.invoke(table.title, context()) }
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.rich_copy)) }, onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.androidharness.app.core.response.ResponseTableData.csv(table, filtered))); menu = false
                        android.widget.Toast.makeText(ui, ui.getString(R.string.rich_copied), android.widget.Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rich_share)) }, onClick = {
                        menu = false
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/csv"; putExtra(android.content.Intent.EXTRA_SUBJECT, table.title)
                            putExtra(android.content.Intent.EXTRA_TEXT, com.androidharness.app.core.response.ResponseTableData.csv(table, filtered))
                        }
                        runCatching { ui.startActivity(android.content.Intent.createChooser(intent, table.title).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { android.widget.Toast.makeText(ui, ui.getString(R.string.rich_share_unavailable), android.widget.Toast.LENGTH_SHORT).show() }
                    })
                }
            }
        }
        if (showFilter || query.isNotBlank()) OutlinedTextField(query, {
            query = it.take(120); page = 0; contextChange?.invoke(table.title, context())
        }, label = { Text(stringResource(R.string.rich_filter_rows)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
        if (showSort) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            table.columns.forEachIndexed { index, label ->
                FilterChip(selected = sort == index, onClick = {
                    if (sort == index) ascending = !ascending else { sort = index; ascending = true }
                    page = 0; contextChange?.invoke(table.title, context())
                }, label = { Text(label.ifBlank { (index + 1).toString() } + if (sort == index) (if (ascending) " ↑" else " ↓") else "") })
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val stacked = maxWidth / fontScale < 500.dp && table.columns.size <= 3 &&
                (fontScale > 1.25f || table.rows.any { row -> row.drop(1).any { it.length > 64 } })
            if (stacked) Column(Modifier.fillMaxWidth()) {
                visible.forEach { index -> key(index) {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                    val row = table.rows[index]
                    val status = row.getOrNull(1)?.let(com.androidharness.app.core.response.ResponseTableData::status)
                    Column(Modifier.fillMaxWidth().background(if (selected[index]) colors.primary.copy(alpha = .06f) else androidx.compose.ui.graphics.Color.Transparent)
                        .then(if (selecting) Modifier.toggleable(selected[index], role = Role.Checkbox, onValueChange = { select(index, it) }) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selecting) Checkbox(selected[index], null, Modifier.size(24.dp))
                            Text(row.firstOrNull().orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            status?.let { ResponseTableStatus(it.kind) }
                        }
                        row.drop(1).forEachIndexed { column, value ->
                            if (table.columns.size > 2) Text(table.columns[column + 1], style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            val text = if (column == 0 && status != null) status.detail else value
                            if (text.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
                                com.androidharness.app.ui.chat.MarkdownText(text, media = false)
                            }
                        }
                    }
                } }
            } else {
                val selectionWidth = if (selecting) 48.dp else 0.dp
                val cellWidth = maxOf((128 * fontScale).dp, (maxWidth - selectionWidth) / table.columns.size)
                Column(Modifier.horizontalScroll(tableScroll).width(selectionWidth + cellWidth * table.columns.size)) {
                    Row(Modifier.fillMaxWidth()) {
                        if (selecting) Spacer(Modifier.width(selectionWidth))
                        table.columns.forEach { label -> Text(label, Modifier.width(cellWidth).padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                    }
                    visible.forEach { index -> key(index) {
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                        Row(Modifier.fillMaxWidth().background(if (selected[index]) colors.primary.copy(alpha = .06f) else androidx.compose.ui.graphics.Color.Transparent)
                            .then(if (selecting) Modifier.toggleable(selected[index], role = Role.Checkbox, onValueChange = { select(index, it) }) else Modifier), verticalAlignment = Alignment.Top) {
                            if (selecting) Checkbox(selected[index], null, Modifier.size(48.dp))
                            table.rows[index].forEach { cell -> Text(cell, Modifier.width(cellWidth).padding(14.dp), style = MaterialTheme.typography.bodyMedium.copy(
                                lineHeight = 22.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content)) }
                        }
                    } }
                }
            }
        }
        AnswerTableScrollHint(tableScroll)
        if (filtered.isEmpty()) Text(stringResource(R.string.rich_no_rows), Modifier.padding(16.dp))
        if (selected.any { it }) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.rich_selected_count, selected.count { it }), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { selected = List(table.rows.size) { false }; contextChange?.invoke(table.title, context()) }) { Text(stringResource(R.string.rich_clear_selection)) }
        }
        if (pages > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text(stringResource(R.string.rich_previous)) }
            Text("${page.coerceAtMost(pages - 1) + 1} / $pages", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { page++ }, enabled = page < pages - 1) { Text(stringResource(R.string.rich_next)) }
        }
        if (table.caption.isNotBlank()) Text(table.caption, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (selected.any { it }) onFollowUp?.let { follow -> TextButton(onClick = {
            follow(RichResponse.followUp(ui.getString(R.string.rich_discuss_rows_prompt), table.title, context()))
        }, modifier = Modifier.padding(horizontal = 6.dp)) { Text(stringResource(R.string.rich_discuss_rows)) } }
    }
}

/** Expanded view reuses the same filtering, sorting, selection and copy actions. */
@Composable
internal fun ExpandedResponseTable(table: RichResponse.Node.Table, onDismiss: () -> Unit, onFollowUp: ((RichResponse.FollowUp) -> Unit)? = null) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(table.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.answer_table_done)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    ResponseTable(table, onFollowUp = onFollowUp?.let { follow -> { request -> onDismiss(); follow(request) } }, allowExpand = false)
                }
            }
        }
    }
}

@Composable
private fun ResponseTableStatus(kind: String) {
    val colors = MaterialTheme.colorScheme
    val color = when (kind) {
        "passed" -> com.androidharness.app.ui.theme.LocalStatusColors.current.success
        "failed" -> colors.error
        else -> colors.tertiary
    }
    Surface(color = color.copy(alpha = .10f), contentColor = color, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (kind == "passed") androidx.compose.material.icons.Icons.Outlined.CheckCircle else if (kind == "failed") androidx.compose.material.icons.Icons.Outlined.ErrorOutline
                else androidx.compose.material.icons.Icons.Outlined.Info, null, Modifier.size(13.dp))
            Text(stringResource(when (kind) { "passed" -> R.string.rich_passed; "failed" -> R.string.rich_failed; else -> R.string.rich_unverified }), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResponseTabs(tabs: RichResponse.Node.Tabs, onOpenUrl: ((String) -> Unit)?, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    var selected by rememberSaveable(tabs) { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    var contexts by rememberSaveable(tabs) { mutableStateOf(List(tabs.tabs.size) { "" }) }
    val contextChange = LocalResponseContext.current
    val tab = tabs.tabs[selected.coerceIn(tabs.tabs.indices)]
    val ui = LocalContext.current
    ResponsePanel {
        Text(tabs.title, style = MaterialTheme.typography.titleSmall)
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp,
            modifier = Modifier.harnessGlassControl(),
            containerColor = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) {
            tabs.tabs.forEachIndexed { index, item -> Tab(selected == index, onClick = {
                selected = index
                contextChange?.invoke(tabs.title, "Selected tab: ${item.title}\n" + contexts[index].ifBlank { item.children.joinToString("\n", transform = RichResponse::describe) })
            }, text = { Text(item.title) }) }
        }
        holder.SaveableStateProvider(selected) {
            CompositionLocalProvider(LocalResponseContext provides { title, context ->
                val childContext = "$title\n$context".take(1500)
                contexts = contexts.toMutableList().also { it[selected] = childContext }
                contextChange?.invoke(tabs.title, "Selected tab: ${tab.title}\n$childContext")
            }) {
                tab.children.forEachIndexed { index, child -> key(index) { ResponseNode(child, onOpenUrl, onFollowUp) } }
            }
        }
        onFollowUp?.let { follow -> TextButton(onClick = {
            follow(RichResponse.followUp(ui.getString(R.string.rich_discuss_tab_prompt, tab.title), tabs.title,
                "Selected tab: ${tab.title}\n" + contexts[selected].ifBlank { tab.children.joinToString("\n", transform = RichResponse::describe) }))
        }) { Text(stringResource(R.string.rich_discuss_tab)) } }
    }
}
