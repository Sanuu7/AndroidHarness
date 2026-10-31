package com.androidharness.app.ui.learn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.IconToggleButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LearnEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.padding(if (compact) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp, 24.dp, 16.dp, 24.dp), color = colors.primary, contentColor = colors.onPrimary) {
                    Icon(Icons.Outlined.AutoStories, null, Modifier.padding(12.dp).size(24.dp))
                }
                if (!compact) Text(stringResource(R.string.learn_entry_hint), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
            }
            Text(stringResource(R.string.learn_title), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            if (!compact) Text(stringResource(R.string.learn_description), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LearnSection(
    lessonId: String?, query: String, category: String?, savedOnly: Boolean,
    onQueryChange: (String) -> Unit, onCategoryChange: (String?) -> Unit,
    onSavedOnlyChange: (Boolean) -> Unit, onLesson: (String?) -> Unit,
    onOpenSettings: (SettingsPage) -> Unit, onOpenDestination: (LearnDestination) -> Unit,
) {
    val context = LocalContext.current
    val catalogResult by produceState<Result<LearnCatalog>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("learn/guide.json").bufferedReader().use { LearnCatalog.parse(it.readText()) } }
        }
    }
    val prefs = remember(context) { context.getSharedPreferences("learn_guide", Context.MODE_PRIVATE) }
    var completed by remember(prefs) { mutableStateOf(prefs.getStringSet("completed", emptySet()).orEmpty().toSet()) }
    var saved by remember(prefs) { mutableStateOf(prefs.getStringSet("saved", emptySet()).orEmpty().toSet()) }
    fun toggle(id: String, bookmark: Boolean) {
        val current = if (bookmark) saved else completed
        val next = if (id in current) current - id else current + id
        prefs.edit().putStringSet(if (bookmark) "saved" else "completed", next).apply()
        if (bookmark) saved = next else completed = next
    }
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        val result = catalogResult
        when {
            result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            result.isFailure -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.learn_load_failed), style = MaterialTheme.typography.bodyLarge)
            }
            else -> {
                val catalog = result.getOrThrow()
                val lesson = catalog.lesson(lessonId)
                val readerState = rememberSaveableStateHolder()
                readerState.SaveableStateProvider(lesson?.id ?: "library") {
                    if (lesson == null) LearnLibrary(catalog, query, category, savedOnly, saved, completed,
                        onQueryChange, onCategoryChange, onSavedOnlyChange, onLesson)
                    else LearnReader(lesson, catalog, lesson.id in completed, lesson.id in saved,
                        onComplete = { toggle(lesson.id, false) }, onBookmark = { toggle(lesson.id, true) },
                        onLesson = onLesson, onOpenSettings = onOpenSettings, onOpenDestination = onOpenDestination)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LearnLibrary(
    catalog: LearnCatalog, query: String, category: String?, savedOnly: Boolean, saved: Set<String>, completed: Set<String>,
    onQueryChange: (String) -> Unit, onCategoryChange: (String?) -> Unit,
    onSavedOnlyChange: (Boolean) -> Unit, onLesson: (String?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val searchLabel = stringResource(R.string.learn_search)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun openLesson(id: String) {
        focus.clearFocus()
        keyboard?.hide()
        onLesson(id)
    }
    val lessons = remember(catalog, query, category, savedOnly, saved) { catalog.search(query, category, saved.takeIf { savedOnly }) }
    val listState = rememberLazyListState()
    val filters = listOf(query, category.orEmpty(), savedOnly.toString())
    var previousFilters by rememberSaveable { mutableStateOf(filters) }
    LaunchedEffect(filters) {
        if (filters != previousFilters) { listState.scrollToItem(0); previousFilters = filters }
    }
    val showIntro = query.isBlank() && category == null && !savedOnly
    Column(Modifier.fillMaxSize().imePadding()) {
        OutlinedTextField(query, onQueryChange, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)
            .semantics { contentDescription = searchLabel },
            placeholder = { Text(searchLabel) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.learn_clear_search))
            } }, singleLine = true, shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = category == null && !savedOnly, onClick = { onCategoryChange(null); onSavedOnlyChange(false) },
                label = { Text(stringResource(R.string.learn_all)) })
            FilterChip(selected = savedOnly, onClick = { onSavedOnlyChange(!savedOnly); onCategoryChange(null) },
                label = { Text(stringResource(R.string.learn_saved)) }, leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp)) })
            catalog.categories.forEach { entry ->
                FilterChip(selected = entry.id == category, onClick = { onCategoryChange(if (category == entry.id) null else entry.id); onSavedOnlyChange(false) },
                    label = { Text(entry.title) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showIntro) {
                item(key = "welcome") {
                    Surface(shape = RoundedCornerShape(32.dp, 32.dp, 56.dp, 32.dp), color = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Outlined.AutoStories, null, Modifier.size(36.dp))
                            Text(stringResource(R.string.learn_hero_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.learn_hero_body), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.learn_offline), style = MaterialTheme.typography.labelMedium)
                            val count = catalog.lessons.count { it.id in completed }
                            Text(stringResource(R.string.learn_progress, count, catalog.lessons.size), style = MaterialTheme.typography.labelLarge)
                            LinearProgressIndicator(progress = { count.toFloat() / catalog.lessons.size.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(), color = scheme.primary, trackColor = scheme.primary.copy(alpha = .12f))
                        }
                    }
                }
                item(key = "start") {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LearnHeading(stringResource(R.string.learn_start))
                        Text(stringResource(R.string.learn_start_hint), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
                items(catalog.lessons.take(3), key = { "start:${it.id}" }) { lesson ->
                    LessonCard(lesson, lesson.id in completed, lesson.id in saved, onClick = { openLesson(lesson.id) }, featured = true)
                }
            }
            if (lessons.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (savedOnly) Icons.Outlined.BookmarkBorder else Icons.Outlined.Search, null, Modifier.size(40.dp), tint = scheme.primary)
                    Text(stringResource(if (savedOnly && query.isBlank()) R.string.learn_saved_empty_title else R.string.learn_empty_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (savedOnly && query.isBlank()) R.string.learn_saved_empty_body else R.string.learn_empty_body),
                        style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { onQueryChange(""); onCategoryChange(null); onSavedOnlyChange(false) }) { Text(stringResource(R.string.learn_reset_filters)) }
                }
            }
            catalog.categories.forEach { group ->
                val groupLessons = lessons.filter { it.category == group.id && (!showIntro || it !in catalog.lessons.take(3)) }
                if (groupLessons.isNotEmpty()) {
                    item(key = "group:${group.id}") {
                        Column(Modifier.padding(top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LearnHeading(group.title)
                            Text(group.description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    items(groupLessons, key = { it.id }) { lesson ->
                        LessonCard(lesson, lesson.id in completed, lesson.id in saved, onClick = { openLesson(lesson.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun categoryIcon(category: String): ImageVector = when (category) {
    "start" -> Icons.Outlined.WavingHand
    "providers" -> Icons.Outlined.Key
    "chat" -> Icons.Outlined.ChatBubbleOutline
    "local" -> Icons.Outlined.Memory
    "code" -> Icons.Outlined.Code
    "github" -> Icons.Outlined.AccountTree
    "extensions" -> Icons.Outlined.Extension
    "device" -> Icons.Outlined.Terminal
    "automation" -> Icons.Outlined.Schedule
    "personal" -> Icons.Outlined.Palette
    "data" -> Icons.Outlined.Folder
    else -> Icons.AutoMirrored.Outlined.HelpOutline
}

@Composable
private fun LessonCard(lesson: LearnLesson, complete: Boolean, saved: Boolean, onClick: () -> Unit, featured: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = if (featured) scheme.secondaryContainer else scheme.surfaceContainerLow,
        contentColor = if (featured) scheme.onSecondaryContainer else scheme.onSurface) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = if (featured) scheme.secondary else scheme.surfaceContainerHighest,
                contentColor = if (featured) scheme.onSecondary else scheme.primary) {
                Icon(categoryIcon(lesson.category), null, Modifier.padding(12.dp).size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(lesson.title, style = MaterialTheme.typography.titleMedium)
                Text(lesson.summary, style = MaterialTheme.typography.bodyMedium,
                    color = if (featured) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
                if (complete) Text(stringResource(R.string.learn_completed_label), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
            Icon(if (complete) Icons.Outlined.CheckCircle else if (saved) Icons.Outlined.Bookmark else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null, Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LearnReader(
    lesson: LearnLesson, catalog: LearnCatalog, complete: Boolean, bookmarked: Boolean,
    onComplete: () -> Unit, onBookmark: () -> Unit, onLesson: (String?) -> Unit,
    onOpenSettings: (SettingsPage) -> Unit, onOpenDestination: (LearnDestination) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val category = catalog.categories.first { it.id == lesson.category }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "navigation") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onLesson(null) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.AutoStories, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.learn_all))
                }
                IconToggleButton(checked = bookmarked, onCheckedChange = { onBookmark() }) {
                    Icon(if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        stringResource(if (bookmarked) R.string.learn_bookmarked else R.string.learn_bookmark))
                }
            }
        }
        item(key = "title") {
            Surface(shape = RoundedCornerShape(32.dp, 32.dp, 48.dp, 32.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(categoryIcon(lesson.category), null, Modifier.size(20.dp))
                        Text(category.title, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(lesson.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(lesson.summary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item(key = "location") {
            ReaderPanel(Icons.Outlined.Place, stringResource(R.string.learn_location), scheme.surfaceContainerHigh) { LearnBody(lesson.location) }
        }
        item(key = "before") {
            ReaderPanel(Icons.Outlined.Checklist, stringResource(R.string.learn_before)) {
                lesson.before.forEach { Bullet(it) }
            }
        }
        item(key = "steps-heading") { LearnHeading(stringResource(R.string.learn_steps)) }
        items(lesson.steps.size, key = { "step:$it" }) { index ->
            val step = lesson.steps[index]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = CircleShape, color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.titleSmall) }
                }
                Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(step.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    LearnBody(step.body)
                }
            }
        }
        lesson.example?.let { example -> item(key = "example") {
            ReaderPanel(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.learn_example), scheme.secondaryContainer) {
                LearnBody(example)
                OutlinedButton(onClick = {
                    copyText(context, example, context.getString(R.string.learn_example))
                    Toast.makeText(context, R.string.learn_copied, Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.learn_copy_example)) }
            }
        } }
        item(key = "outcome") {
            ReaderPanel(Icons.Outlined.CheckCircle, stringResource(R.string.learn_outcome), scheme.tertiaryContainer) { LearnBody(lesson.outcome) }
        }
        item(key = "limits") {
            ReaderPanel(Icons.Outlined.Info, stringResource(R.string.learn_limits)) { lesson.limits.forEach { Bullet(it) } }
        }
        item(key = "fixes") {
            ReaderPanel(Icons.Outlined.Build, stringResource(R.string.learn_fixes)) {
                lesson.fixes.forEach { fix ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(fix.problem, style = MaterialTheme.typography.titleSmall)
                        LearnBody(fix.solution)
                    }
                }
            }
        }
        if (lesson.actions.isNotEmpty()) item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LearnHeading(stringResource(R.string.learn_open_section))
                lesson.actions.forEach { action ->
                    FilledTonalButton(onClick = {
                        when (action.kind) {
                            "settings" -> SettingsPage.entries.firstOrNull { it.name == action.target }?.let(onOpenSettings)
                            "screen" -> LearnDestination.entries.firstOrNull { it.name == action.target }?.let(onOpenDestination)
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(action.label, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        if (lesson.links.isNotEmpty()) item(key = "links") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LearnHeading(stringResource(R.string.learn_links))
                Text(stringResource(R.string.learn_links_hint), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                lesson.links.forEach { link -> OfficialLink(link) }
            }
        }
        item(key = "complete") {
            Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = if (complete) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors()) {
                Icon(if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.Check, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text(stringResource(if (complete) R.string.learn_complete else R.string.learn_mark_complete))
            }
        }
        if (lesson.related.isNotEmpty()) {
            item(key = "related-heading") { LearnHeading(stringResource(R.string.learn_related)) }
            items(lesson.related.mapNotNull(catalog::lesson), key = { "related:${it.id}" }) { related ->
                LessonCard(related, complete = false, saved = false, onClick = { onLesson(related.id) })
            }
        }
    }
}

@Composable
private fun ReaderPanel(icon: ImageVector, title: String, color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val foreground = when (color) {
        scheme.primaryContainer -> scheme.onPrimaryContainer
        scheme.secondaryContainer -> scheme.onSecondaryContainer
        scheme.tertiaryContainer -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    Surface(shape = RoundedCornerShape(24.dp), color = color, contentColor = foreground) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

@Composable
private fun LearnHeading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge,
    modifier = Modifier.semantics { heading() })

@Composable
private fun LearnBody(text: String) {
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 9.dp).size(5.dp).background(LocalContentColor.current, CircleShape))
        Box(Modifier.weight(1f)) { LearnBody(text) }
    }
}

@Composable
private fun OfficialLink(link: LearnLink) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    OutlinedButton(onClick = {
        failed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }.isFailure
    }, modifier = Modifier.fillMaxWidth()) {
        Text(link.label, Modifier.weight(1f)); Spacer(Modifier.width(10.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
    }
    if (failed) {
        Text(stringResource(R.string.learn_link_failed), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { copyText(context, link.url, link.label) }) { Text(stringResource(R.string.learn_copy_link)) }
    }
}

private fun copyText(context: Context, text: String, label: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}
