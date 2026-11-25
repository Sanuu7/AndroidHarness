package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import com.androidharness.app.ui.common.ExpressiveIconBadge
import com.androidharness.app.ui.theme.expressiveDefaultSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.chat.ChatUiState
import com.androidharness.app.ui.chat.SlashCommands

/** One row of the "/" menu. [usage] names the text the command takes after it, if any. */
internal data class SlashEntry(
    val command: String,
    val title: String,
    val subtitle: String,
    val kind: SlashCommands.Kind,
    val icon: ImageVector,
    val usage: String? = null,
    val aliases: List<String> = emptyList(),
)

private enum class SlashGroup { COMMANDS, SKILLS, SNIPPETS }

private val SlashEntry.group: SlashGroup get() = when (kind) {
    SlashCommands.Kind.SKILL -> SlashGroup.SKILLS
    SlashCommands.Kind.SNIPPET -> SlashGroup.SNIPPETS
    else -> SlashGroup.COMMANDS
}

/**
 * How well [entry] matches what was typed after "/": 0 is the exact name, then
 * names that start with it, names that contain it, and last titles or descriptions.
 * Null leaves the entry out.
 */
internal fun slashRank(entry: SlashEntry, query: String): Int? {
    if (query.isEmpty()) return 4
    val names = (listOf(entry.command) + entry.aliases).map { it.removePrefix("/").lowercase() }
    return when {
        names.any { it == query } -> 0
        names.any { it.startsWith(query) } -> 1
        names.any { it.contains(query) } -> 2
        entry.title.lowercase().startsWith(query) -> 3
        entry.title.lowercase().contains(query) || entry.subtitle.lowercase().contains(query) -> 4
        else -> null
    }
}

@Composable
private fun slashEntries(state: ChatUiState): List<SlashEntry> {
    val ui = LocalContext.current
    fun s(id: Int) = ui.getString(id)
    return remember(state.skills, state.snippets) {
        val ownGoal = state.skills.any { it.enabled && it.name == "goal" } || state.snippets.any { it.name.equals("goal", ignoreCase = true) }
        val ownReview = state.skills.any { it.enabled && it.name == "review" } || state.snippets.any { it.name.equals("review", ignoreCase = true) }
        buildList {
            if (!ownGoal) add(SlashEntry(SlashCommands.GOAL_COMMAND, s(R.string.goal_command_title), s(R.string.slash_goal_hint),
                SlashCommands.Kind.GOAL, Icons.Outlined.Flag, s(R.string.slash_usage_goal)))
            add(SlashEntry("/plan", s(R.string.slash_plan_title), s(R.string.slash_plan_hint), SlashCommands.Kind.PLAN,
                Icons.Outlined.Checklist, s(R.string.slash_usage_plan)))
            add(SlashEntry(SlashCommands.SIDE_COMMAND, s(R.string.slash_btw_title), s(R.string.slash_btw_hint), SlashCommands.Kind.BTW,
                Icons.Outlined.ChatBubbleOutline, s(R.string.slash_usage_btw)))
            if (!ownReview) add(SlashEntry("/review", s(R.string.slash_review_title), s(R.string.slash_review_hint), SlashCommands.Kind.REVIEW,
                Icons.Outlined.RateReview, s(R.string.slash_usage_review)))
            add(SlashEntry("/clear", s(R.string.slash_clear_title), s(R.string.slash_clear_hint), SlashCommands.Kind.CLEAR, Icons.Outlined.AddComment))
            add(SlashEntry("/compact", s(R.string.slash_compact_title), s(R.string.slash_compact_hint), SlashCommands.Kind.COMPACT, Icons.Outlined.Compress))
            add(SlashEntry("/autocompact", s(R.string.auto_compact_title), s(R.string.slash_autocompact_hint), SlashCommands.Kind.AUTO_COMPACT,
                Icons.Outlined.AutoMode, s(R.string.slash_usage_autocompact), aliases = listOf("/auto-compact", "/auto_compact")))
            add(SlashEntry("/context", s(R.string.context_command_title), s(R.string.context_command_hint), SlashCommands.Kind.CONTEXT, Icons.Outlined.DataUsage))
            add(SlashEntry("/cost", s(R.string.slash_cost_title), s(R.string.slash_cost_hint), SlashCommands.Kind.COST, Icons.Outlined.Payments))
            add(SlashEntry("/skills", s(R.string.slash_skills_title), s(R.string.slash_skills_hint), SlashCommands.Kind.SKILLS, Icons.Outlined.AutoAwesome))
            add(SlashEntry("/init", s(R.string.slash_init_title), s(R.string.slash_init_hint), SlashCommands.Kind.INIT, Icons.Outlined.Description))
            add(SlashEntry("/env", s(R.string.slash_env_title), s(R.string.slash_env_hint), SlashCommands.Kind.ENV, Icons.Outlined.Terminal))
            add(SlashEntry("/doctor", s(R.string.slash_doctor_title), s(R.string.slash_doctor_hint), SlashCommands.Kind.DOCTOR, Icons.Outlined.HealthAndSafety))
            // The built-in /plan already loads the plan skill, so it is not listed twice.
            state.skills.filter { it.enabled && it.name != "plan" }.forEach { skill ->
                add(SlashEntry("/${skill.name}", skill.name, skill.description, SlashCommands.Kind.SKILL, Icons.Outlined.Bolt,
                    s(R.string.slash_usage_skill)))
            }
            state.snippets.forEach { snippet ->
                add(SlashEntry("/${snippet.name}", snippet.name, snippet.body.lineSequence().firstOrNull().orEmpty()
                    .ifBlank { s(R.string.slash_snippet_hint) }, SlashCommands.Kind.SNIPPET, Icons.Outlined.ShortText,
                    if ("\$ARG" in snippet.body) s(R.string.slash_usage_snippet) else null))
            }
        }
    }
}

/**
 * The "/" menu, docked on top of the composer like a sheet growing out of it. While a
 * "/word" is typed (at the start or anywhere in the message) it lists commands, skills
 * and snippets, best match first, filtered by the chips at the top. Once a whole
 * command is followed by text, it turns into one row showing what that command takes,
 * instead of a "no matches" error.
 */
@Composable
internal fun SlashSuggestions(
    state: ChatUiState,
    menu: SlashCommands.MenuQuery?,
    expanded: Boolean,
    onPick: (SlashEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val entries = slashEntries(state)
    val typing = menu as? SlashCommands.MenuQuery.Typing
    var filter by rememberSaveable { mutableIntStateOf(-1) }
    val groups = remember(entries) { entries.map { it.group }.distinct().sortedBy { it.ordinal } }
    val ranked = remember(entries, typing?.query) {
        typing?.let { t ->
            entries.mapNotNull { e -> slashRank(e, t.query)?.let { e to it } }
                .sortedWith(compareBy({ if (t.query.isEmpty()) it.first.group.ordinal else 0 }, { it.second }))
                .map { it.first }
        }.orEmpty()
    }
    val shown = remember(ranked, filter) { if (filter < 0) ranked else ranked.filter { it.group.ordinal == filter } }
    val argumentEntry = (menu as? SlashCommands.MenuQuery.Arguments)?.let { args ->
        entries.firstOrNull { e -> (listOf(e.command) + e.aliases).any { it == args.command } }
    }
    val unknownCommand = (menu as? SlashCommands.MenuQuery.Arguments)?.command?.takeIf { argumentEntry == null && it != "/auto" }
    val show = expanded && menu != null && (typing != null || argumentEntry?.usage != null || unknownCommand != null)
    val query = typing?.query.orEmpty()

    AnimatedVisibility(
        visible = show,
        enter = expandVertically(expressiveDefaultSpatialSpec(), expandFrom = Alignment.Bottom) + fadeIn(expressiveEffectsSpec()) +
            scaleIn(expressiveDefaultSpatialSpec(), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)),
        exit = shrinkVertically(expressiveSpatialSpec(), shrinkTowards = Alignment.Bottom) + fadeOut(expressiveEffectsSpec()) +
            scaleOut(expressiveSpatialSpec(), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            color = scheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 10.dp, bottomEnd = 10.dp),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp).animateContentSize(expressiveDefaultSpatialSpec())) {
                // A short handle, so the panel reads as a sheet attached to the composer.
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).size(width = 32.dp, height = 4.dp)
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
                when {
                    argumentEntry != null -> SlashRow(argumentEntry, typed = "", highlighted = false, hint = true,
                        shape = segmentShape(first = true, last = true), onClick = null)
                    unknownCommand != null -> MenuNote(ui.getString(R.string.slash_unknown, unknownCommand))
                    else -> {
                        if (groups.size > 1 && typing != null) Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChipItem(ui.getString(R.string.slash_filter_all), filter < 0, ranked.size) { filter = -1 }
                            groups.forEach { group ->
                                FilterChipItem(ui.getString(group.label), filter == group.ordinal, ranked.count { it.group == group }) {
                                    filter = if (filter == group.ordinal) -1 else group.ordinal
                                }
                            }
                        }
                        if (shown.isEmpty()) MenuNote(ui.getString(R.string.slash_no_match, "/$query"))
                        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 296.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            itemsIndexed(shown, key = { _, e -> e.kind.name + e.command }) { index, entry ->
                                val previous = shown.getOrNull(index - 1)
                                val nextEntry = shown.getOrNull(index + 1)
                                // While browsing, each group is its own block of rows.
                                val first = previous == null || (query.isEmpty() && previous.group != entry.group)
                                val last = nextEntry == null || (query.isEmpty() && nextEntry.group != entry.group)
                                SlashRow(
                                    entry, typed = query, highlighted = query.isNotEmpty() && index == 0, hint = false,
                                    shape = segmentShape(first, last),
                                    onClick = { onPick(entry) },
                                    modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(),
                                        fadeOutSpec = expressiveEffectsSpec()).padding(top = if (first && index > 0) 8.dp else 0.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val SlashGroup.label: Int get() = when (this) {
    SlashGroup.COMMANDS -> R.string.slash_section_commands
    SlashGroup.SKILLS -> R.string.slash_section_skills
    SlashGroup.SNIPPETS -> R.string.slash_section_snippets
}

/** Rows of one block share a container: round outer corners, tight inner ones. */
private fun segmentShape(first: Boolean, last: Boolean): RoundedCornerShape {
    val outer = 20.dp
    val inner = 5.dp
    return RoundedCornerShape(
        topStart = if (first) outer else inner, topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner, bottomEnd = if (last) outer else inner,
    )
}

@Composable
private fun FilterChipItem(label: String, selected: Boolean, count: Int, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$label  $count", maxLines = 1) },
        shape = CircleShape,
        leadingIcon = {
            AnimatedVisibility(selected, enter = scaleIn(expressiveSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
                exit = scaleOut(expressiveEffectsSpec()) + fadeOut(expressiveEffectsSpec())) {
                Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
            }
        },
    )
}

@Composable
private fun SlashRow(
    entry: SlashEntry,
    typed: String,
    highlighted: Boolean,
    hint: Boolean,
    shape: RoundedCornerShape,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, tint) = when (entry.group) {
        SlashGroup.COMMANDS -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SlashGroup.SKILLS -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SlashGroup.SNIPPETS -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    val fill by animateColorAsState(if (highlighted) scheme.secondaryContainer else scheme.surfaceBright, expressiveEffectsSpec(), label = "slash-row")
    Row(
        modifier.fillMaxWidth().clip(shape).background(fill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExpressiveIconBadge(entry.icon, size = 36.dp, selected = highlighted, container = container, tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(buildAnnotatedString {
                val name = entry.command
                val at = if (typed.isEmpty()) -1 else name.lowercase().indexOf(typed, startIndex = 1)
                if (at < 0) append(name)
                else {
                    append(name.substring(0, at))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.primary)) { append(name.substring(at, at + typed.length)) }
                    append(name.substring(at + typed.length))
                }
            }, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = if (hint) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        entry.usage?.let { usage ->
            Surface(shape = CircleShape, color = if (hint) scheme.primaryContainer else scheme.surfaceContainerHighest,
                contentColor = if (hint) scheme.onPrimaryContainer else scheme.onSurfaceVariant) {
                Text(usage, Modifier.padding(horizontal = 10.dp, vertical = 4.dp).widthIn(max = 128.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MenuNote(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceBright)
        .padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.HelpOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
