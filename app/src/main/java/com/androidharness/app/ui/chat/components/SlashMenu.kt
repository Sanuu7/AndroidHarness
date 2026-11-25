package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
 * The "/" menu, anchored right above the composer. While a "/word" is typed (at the
 * start or anywhere in the message) it lists commands, skills and snippets, best match
 * first. Once a whole command is followed by text, it turns into one line showing
 * what that command takes, instead of a "no matches" error.
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
    val ranked = remember(entries, typing?.query) {
        typing?.let { t ->
            entries.mapNotNull { e -> slashRank(e, t.query)?.let { e to it } }
                .sortedWith(compareBy({ it.second }, { if (t.query.isEmpty()) it.first.group.ordinal else 0 }))
                .map { it.first }
        }.orEmpty()
    }
    val argumentEntry = (menu as? SlashCommands.MenuQuery.Arguments)?.let { args ->
        entries.firstOrNull { e -> (listOf(e.command) + e.aliases).any { it == args.command } }
    }
    val unknownCommand = (menu as? SlashCommands.MenuQuery.Arguments)?.command?.takeIf { argumentEntry == null && it != "/auto" }
    val show = expanded && menu != null && (typing != null || argumentEntry?.usage != null || unknownCommand != null)

    AnimatedVisibility(
        visible = show,
        enter = fadeIn(tween(140)) + expandVertically(tween(220), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(110)) + shrinkVertically(tween(160), shrinkTowards = Alignment.Bottom),
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            color = scheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(6.dp)) {
                when {
                    argumentEntry != null -> SlashRow(argumentEntry, typed = "", highlighted = false, hint = true, onClick = null)
                    unknownCommand != null -> MenuNote(ui.getString(R.string.slash_unknown, unknownCommand))
                    ranked.isEmpty() -> MenuNote(ui.getString(R.string.slash_no_match, "/" + typing?.query.orEmpty()))
                    else -> {
                        val query = typing?.query.orEmpty()
                        var lastGroup: SlashGroup? = null
                        ranked.forEachIndexed { index, entry ->
                            // Sections only while browsing; a search is one ranked list.
                            if (query.isEmpty() && entry.group != lastGroup) {
                                lastGroup = entry.group
                                Text(ui.getString(when (entry.group) {
                                    SlashGroup.COMMANDS -> R.string.slash_section_commands
                                    SlashGroup.SKILLS -> R.string.slash_section_skills
                                    SlashGroup.SNIPPETS -> R.string.slash_section_snippets
                                }), style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                                    modifier = Modifier.padding(start = 12.dp, top = if (index == 0) 6.dp else 12.dp, bottom = 4.dp))
                            }
                            SlashRow(entry, typed = query, highlighted = query.isNotEmpty() && index == 0, hint = false,
                                onClick = { onPick(entry) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashRow(entry: SlashEntry, typed: String, highlighted: Boolean, hint: Boolean, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val (container, tint) = when (entry.group) {
        SlashGroup.COMMANDS -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SlashGroup.SKILLS -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SlashGroup.SNIPPETS -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (highlighted) scheme.secondaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(container, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(entry.icon, null, Modifier.size(19.dp), tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(buildAnnotatedString {
                val name = entry.command
                val at = if (typed.isEmpty()) -1 else name.lowercase().indexOf(typed, startIndex = 1)
                if (at < 0) withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(name) }
                else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(name.substring(0, at)) }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.primary)) { append(name.substring(at, at + typed.length)) }
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(name.substring(at + typed.length)) }
                }
                entry.usage?.let {
                    withStyle(SpanStyle(color = scheme.onSurfaceVariant, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal)) { append("  "); append(it) }
                }
            }, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (hint || entry.group != SlashGroup.COMMANDS) entry.subtitle else "${entry.title} · ${entry.subtitle}",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = if (hint) 2 else 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MenuNote(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.HelpOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
