package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.ChatExperience
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.RadioButton

/** Colors and dimensions from the approved Context card, independent of wallpaper accents. */
private data class HandoffColors(
    val background: Color, val surface: Color, val text: Color, val muted: Color,
    val border: Color, val blue: Color, val tint: Color, val ink: Color,
    val icon: Color, val divider: Color, val onBlue: Color,
)

private val LightHandoff = HandoffColors(
    Color(0xFFFBF8FF), Color(0xFFF0EDF5), Color(0xFF202127), Color(0xFF5E616C),
    Color(0xFFDEDEE7), Color(0xFF1159CB), Color(0xFFE8EFFF), Color(0xFF183559),
    Color(0xFFD0E0FF), Color(0xFFB6C8E5), Color.White,
)
private val DarkHandoff = HandoffColors(
    Color(0xFF101318), Color(0xFF20242B), Color(0xFFE3E3EA), Color(0xFFAEB3C0),
    Color(0xFF383D47), Color(0xFFA9C7FF), Color(0xFF202F49), Color(0xFFD9E7FF),
    Color(0xFF334B6E), Color(0xFF455B7B), Color(0xFF102F60),
)

@Composable
private fun handoffColors() = if (MaterialTheme.colorScheme.surface.luminance() < .5f) DarkHandoff else LightHandoff

private fun handoffType(size: Int, line: Int, medium: Boolean = false) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = line.sp,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
)

@Composable
internal fun ChatCodeHandoffCard(offer: ChatExperience.CodeHandoffOffer, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = handoffColors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .99f else 1f, tween(150), label = "handoff-press")
    Surface(onClick = onClick, enabled = enabled, interactionSource = interaction,
        shape = RoundedCornerShape(22.dp), color = colors.tint, contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).background(colors.icon, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_handoff_square_code), null, Modifier.size(20.dp), tint = colors.blue)
                }
                Text(stringResource(R.string.chat_continue_code), style = handoffType(13, 20, true))
            }
            Spacer(Modifier.height(14.dp))
            Text(offer.title ?: stringResource(R.string.chat_handoff_default_title), style = handoffType(19, 25, true))
            Spacer(Modifier.height(7.dp))
            Text(offer.reason ?: stringResource(R.string.chat_handoff_default_reason), style = handoffType(14, 20), color = colors.ink.copy(alpha = .85f))
            Spacer(Modifier.height(15.dp))
            HorizontalDivider(color = colors.divider, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.chat_handoff_choose_workspace), style = handoffType(13, 20, true), modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_handoff_arrow_up_right), null, Modifier.size(20.dp))
            }
        }
    }
}

/** Compact origin badge attached to the first Code message after a Chat handoff. */
@Composable
internal fun ChatFromChatBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(50),
        color = scheme.secondaryContainer.copy(alpha = .6f), contentColor = scheme.onSecondaryContainer,
        modifier = modifier) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.chat_from_chat), style = MaterialTheme.typography.labelSmall)
            Icon(painterResource(R.drawable.ic_chat_chevron_right), null, Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatCodeHandoffSheet(projects: List<ProjectEntity>, initialProjectId: String?, enabled: Boolean,
    onDismiss: () -> Unit, onContinue: (String) -> Unit) {
    val colors = handoffColors()
    var selected by rememberSaveable { mutableStateOf(initialProjectId) }
    val selectedId = projects.firstOrNull { it.id == selected }?.id
        ?: projects.firstOrNull { it.id == initialProjectId }?.id ?: projects.firstOrNull()?.id
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 560.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.background, contentColor = colors.text,
        dragHandle = {
            Box(Modifier.padding(top = 16.dp, bottom = 20.dp).size(32.dp, 4.dp)
                .background(colors.muted.copy(alpha = .6f), RoundedCornerShape(2.dp)))
        }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_continue_code), style = handoffType(20, 28, true), modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                    Box(Modifier.size(40.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_handoff_close), stringResource(R.string.chat_handoff_close), Modifier.size(20.dp), tint = colors.text)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.chat_handoff_picker_note), style = handoffType(14, 21), color = colors.muted)
            Spacer(Modifier.height(18.dp))
            if (projects.isEmpty()) Text(stringResource(R.string.chat_handoff_no_workspace), style = handoffType(14, 21), color = colors.muted)
            projects.forEach { project ->
                val checked = selectedId == project.id
                Surface(shape = RoundedCornerShape(16.dp), color = if (checked) colors.tint else colors.background,
                    border = BorderStroke(1.dp, if (checked) colors.blue else colors.border),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Row(Modifier.selectable(checked, enabled = enabled, role = Role.RadioButton, onClick = { selected = project.id })
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = checked, onClick = null, modifier = Modifier.size(18.dp),
                            colors = RadioButtonDefaults.colors(selectedColor = colors.blue, unselectedColor = colors.muted))
                        Text(project.name, style = handoffType(15, 23), modifier = Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_handoff_folder), null, Modifier.size(20.dp), tint = colors.muted)
                    }
                }
            }
            Button(enabled = enabled && selectedId != null,
                onClick = { selectedId?.let(onContinue) }, shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue, contentColor = colors.onBlue),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.chat_continue_code), style = handoffType(15, 23, true))
            }
        }
    }
}
