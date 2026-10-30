package com.androidharness.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.theme.HarnessGlassMode
import com.androidharness.app.ui.theme.LocalHarnessGlass
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec

/**
 * The Chat sidebar's two collections as Material 3 Expressive toggle buttons: the shape morphs
 * on press and on selection, and the colors, icon and count animate with the expressive springs.
 * Saved only exists while something is saved, so it grows in and shrinks away with the list;
 * Archived is always reachable.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SidebarCollectionButtons(
    savedCount: Int,
    showSaved: Boolean,
    showArchived: Boolean,
    onToggleSaved: () -> Unit,
    onToggleArchived: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    // Scoped like ExpressiveIconButton so the sidebar keeps its own motion elsewhere.
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        FlowRow(
            modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = savedCount > 0,
                enter = fadeIn(expressiveEffectsSpec()) +
                    expandHorizontally(expressiveSpatialSpec<IntSize>(), expandFrom = Alignment.Start) +
                    scaleIn(expressiveSpatialSpec<Float>(), initialScale = .6f),
                exit = fadeOut(expressiveEffectsSpec()) +
                    shrinkHorizontally(expressiveSpatialSpec<IntSize>(), shrinkTowards = Alignment.Start) +
                    scaleOut(expressiveSpatialSpec<Float>(), targetScale = .6f),
            ) {
                CollectionToggle(
                    checked = showSaved,
                    onCheckedChange = { onToggleSaved() },
                    label = context.getString(R.string.sidebar_saved),
                    description = context.getString(R.string.sidebar_saved_count_description, savedCount),
                    icon = Icons.Outlined.BookmarkBorder,
                    checkedIcon = Icons.Filled.Bookmark,
                    count = savedCount,
                    container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer,
                    checkedContainer = scheme.tertiary, checkedContent = scheme.onTertiary,
                )
            }
            CollectionToggle(
                checked = showArchived,
                onCheckedChange = { onToggleArchived() },
                label = context.getString(R.string.ui_archived),
                description = context.getString(R.string.ui_archived_chats),
                icon = Icons.Outlined.Archive,
                checkedIcon = Icons.Filled.Archive,
                count = null,
                container = scheme.secondaryContainer, content = scheme.onSecondaryContainer,
                checkedContainer = scheme.secondary, checkedContent = scheme.onSecondary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollectionToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String,
    icon: ImageVector,
    checkedIcon: ImageVector,
    count: Int?,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    checkedContainer: androidx.compose.ui.graphics.Color,
    checkedContent: androidx.compose.ui.graphics.Color,
) {
    // Glass sidebars keep the resting button translucent so the backdrop still reads through.
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = if (glass) container.copy(alpha = .62f) else container,
            contentColor = content,
            checkedContainerColor = checkedContainer,
            checkedContentColor = checkedContent,
        ),
    ) {
        AnimatedContent(
            targetState = checked,
            transitionSpec = {
                (fadeIn(expressiveEffectsSpec()) + scaleIn(expressiveSpatialSpec<Float>(), initialScale = .4f)) togetherWith
                    (fadeOut(expressiveEffectsSpec()) + scaleOut(expressiveSpatialSpec<Float>(), targetScale = .4f))
            },
            label = "collection icon",
        ) { on ->
            Icon(if (on) checkedIcon else icon, null, Modifier.size(ToggleButtonDefaults.IconSize))
        }
        Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) {
            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(expressiveSpatialSpec()) { if (up) it else -it } + fadeIn(expressiveEffectsSpec())) togetherWith
                        (slideOutVertically(expressiveSpatialSpec()) { if (up) -it else it } + fadeOut(expressiveEffectsSpec()))
                },
                label = "collection count",
            ) { value ->
                Text(
                    value.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(LocalContentColor.current.copy(alpha = .16f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
}
