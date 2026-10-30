package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.common.ExpressiveDropdownMenu as DropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem as DropdownMenuItem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.androidharness.app.R
import com.androidharness.app.ui.common.TextButton

/** Outside the transcript so scrolling content can never cover its touch target. */
@Composable
internal fun ChatPersonalizationDropdown(
    personalized: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    ChatStartDropdown(stringResource(if (personalized) R.string.chat_personalized else R.string.chat_unpersonalized), enabled, modifier) { dismiss ->
        for (option in listOf(true, false)) {
            ChatStartDropdownItem(
                stringResource(if (option) R.string.chat_personalized else R.string.chat_unpersonalized),
                stringResource(if (option) R.string.chat_settings_personalization_desc else R.string.chat_unpersonalized_desc),
                option == personalized,
            ) { dismiss(); onSelect(option) }
        }
    }
}

@Composable
internal fun ChatStartDropdown(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    trigger: (@Composable (Boolean, () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, expressiveSpatialSpec(), label = "personalizationChevron")
    val menuWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() - 32.dp
    }.coerceIn(48.dp, 360.dp).coerceAtMost(320.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(if (trigger != null) Modifier.fillMaxWidth() else Modifier.width(menuWidth), contentAlignment = Alignment.Center) {
            if (trigger != null) trigger(expanded) { expanded = true }
            else {
                TextButton(onClick = { expanded = true }, enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
                    Text(label, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp).graphicsLayer { rotationZ = chevron })
                }
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
                modifier = Modifier.width(menuWidth).heightIn(max = 440.dp)) {
                content { expanded = false }
            }
        }
    }
}

@Composable
internal fun ChatStartDropdownItem(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Column {
            Text(title)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        leadingIcon = { if (selected) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
        modifier = Modifier.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(16.dp))
            .semantics { this.selected = selected },
        onClick = onClick,
    )
}
