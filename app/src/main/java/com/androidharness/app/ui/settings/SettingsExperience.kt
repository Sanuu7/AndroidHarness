package com.androidharness.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.theme.expressiveSpatialSpec

@Composable
internal fun SettingsFeatureRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit,
    expanded: Boolean? = null, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f, expressiveSpatialSpec(), label = "Settings press")
    val stateLabel = stringResource(if (expanded == true) com.androidharness.app.R.string.settings_disclosure_expanded else com.androidharness.app.R.string.settings_disclosure_collapsed)
    val rotation by animateFloatAsState(if (expanded == true) 180f else 0f, expressiveSpatialSpec(), label = "Settings disclosure")
    Surface(onClick = onClick, interactionSource = interaction, color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp), modifier = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { if (expanded != null) stateDescription = stateLabel }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .09f)) {
                Icon(icon, null, Modifier.padding(11.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded == null) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore, null,
                Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SettingsDisclosure(icon: ImageVector, title: String, subtitle: String, anchors: Set<Int> = emptySet(),
    initiallyExpanded: Boolean = false, forceExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val highlight = LocalSettingsHighlight.current
    val revealed = settingsRevealed(title)
    LaunchedEffect(highlight, forceExpanded, revealed) { if (forceExpanded || revealed || highlight in anchors) expanded = true }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsAnchor(title) { SettingsFeatureRow(icon, title, subtitle, { expanded = !expanded }, expanded) }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(expressiveSpatialSpec()),
            exit = fadeOut() + shrinkVertically(expressiveSpatialSpec())) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
internal fun SettingsGroupHeading(@StringRes title: Int) {
    Text(stringResource(title), Modifier.settingsAnchor(title).padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
