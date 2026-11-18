package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ChatAgentProfile

/** Uses the same trigger, popup and selected rows as the personalization choice. */
@Composable
internal fun ChatAgentDropdown(
    selected: ChatAgentProfile?,
    agents: List<ChatAgentProfile>,
    enabled: Boolean,
    onSelect: (ChatAgentProfile?) -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    setupCard: Boolean = false,
) {
    // New Chat shows a compact tonal pill under the headline rather than a list row.
    val setupTrigger: (@Composable (Boolean, () -> Unit) -> Unit)? = if (!setupCard) null else { expanded, open ->
        val scheme = MaterialTheme.colorScheme
        val chevron by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f,
            com.androidharness.app.ui.theme.expressiveSpatialSpec(), label = "agentPillChevron")
        val shape = RoundedCornerShape(50)
        Row(Modifier.clip(shape).background(scheme.secondaryContainer)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.chat_agents_description), onClick = open)
            .heightIn(min = 44.dp).padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = scheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_agents_set), style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp)
                .graphicsLayer { rotationZ = chevron }, tint = scheme.onSecondaryContainer)
        }
    }
    ChatStartDropdown(selected?.name ?: stringResource(R.string.chat_agents_choose), enabled, modifier, setupTrigger) { dismiss ->
        ChatStartDropdownItem(stringResource(R.string.chat_agents_regular), stringResource(R.string.chat_agents_regular_desc), selected == null) {
            dismiss(); if (selected != null) onSelect(null)
        }
        val choices = if (selected != null && agents.none { it.id == selected.id }) listOf(selected) + agents else agents
        choices.forEach { agent ->
            ChatStartDropdownItem(agent.name, agent.description, selected?.id == agent.id) {
                dismiss(); if (selected?.id != agent.id) onSelect(agent)
            }
        }
        HorizontalDivider()
        if (agents.size < ChatAgentProfile.MAX_AGENTS) ChatStartDropdownItem(
            stringResource(R.string.chat_agents_new), stringResource(R.string.chat_agents_description), false,
        ) { dismiss(); onCreate() }
        if (agents.isNotEmpty()) ChatStartDropdownItem(stringResource(R.string.chat_agents_manage), "", false) {
            dismiss(); onManage()
        }
    }
}
