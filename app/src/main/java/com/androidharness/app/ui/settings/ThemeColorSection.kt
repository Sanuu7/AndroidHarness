package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.data.ThemePalette
import com.androidharness.app.ui.theme.amoledSurfaces
import com.androidharness.app.ui.theme.colorScheme
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec

@Composable
internal fun ThemeColorSection(
    palette: ThemePalette,
    useWallpaper: Boolean,
    themeMode: ThemeMode,
    onWallpaperChange: (Boolean) -> Unit,
    onPaletteChange: (ThemePalette) -> Unit,
    presetActive: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val wallpaper = if (wallpaperAvailable) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else palette.colorScheme(dark)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().settingsAnchor(R.string.theme_wallpaper_colors).toggleable(
            value = useWallpaper && wallpaperAvailable, enabled = wallpaperAvailable,
            role = Role.Switch, onValueChange = onWallpaperChange,
        ).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaletteSwatch(wallpaper, Modifier.size(42.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.theme_wallpaper_colors), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (wallpaperAvailable) R.string.theme_wallpaper_hint else R.string.theme_wallpaper_unavailable),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = useWallpaper && wallpaperAvailable, onCheckedChange = null, enabled = wallpaperAvailable)
        }
        Column(Modifier.fillMaxWidth().settingsAnchor(R.string.theme_color_palette), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.theme_color_palette), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.theme_color_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.25f) 2 else 3
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemePalette.entries.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { option ->
                            val scheme = option.colorScheme(dark).let { if (themeMode == ThemeMode.AMOLED) it.amoledSurfaces() else it }
                            PaletteChoice(option, scheme, presetActive && palette == option && (!useWallpaper || !wallpaperAvailable),
                                onClick = { onPaletteChange(option) }, modifier = Modifier.weight(1f))
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteChoice(palette: ThemePalette, scheme: ColorScheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant.copy(alpha = .6f), expressiveEffectsSpec(), label = "Palette border")
    val background by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainerLow, expressiveEffectsSpec(), label = "Palette background")
    val corner by animateDpAsState(if (selected) 26.dp else 18.dp, expressiveSpatialSpec(), label = "Palette shape")
    Surface(modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(corner), color = background,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(contentAlignment = Alignment.Center) {
                PaletteSwatch(scheme, Modifier.size(48.dp))
                androidx.compose.animation.AnimatedVisibility(selected, enter = fadeIn() + scaleIn(expressiveSpatialSpec()), exit = fadeOut() + scaleOut(expressiveSpatialSpec())) {
                    Surface(shape = CircleShape, color = scheme.primary, contentColor = scheme.onPrimary) {
                        Icon(Icons.Rounded.Check, null, Modifier.padding(4.dp).size(18.dp))
                    }
                }
            }
            Text(stringResource(palette.labelRes()), style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
internal fun PaletteSwatch(scheme: ColorScheme, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawArc(scheme.primary, 180f, 180f, useCenter = true)
        drawArc(scheme.secondaryContainer, 0f, 90f, useCenter = true)
        drawArc(scheme.tertiary, 90f, 90f, useCenter = true)
    }
}

internal fun ThemePalette.labelRes(): Int = when (this) {
    ThemePalette.BLUE -> R.string.theme_palette_blue
    ThemePalette.OCEAN -> R.string.theme_palette_ocean
    ThemePalette.INDIGO -> R.string.theme_palette_indigo
    ThemePalette.LAVENDER -> R.string.theme_palette_lavender
    ThemePalette.ORCHID -> R.string.theme_palette_orchid
    ThemePalette.ROSE -> R.string.theme_palette_rose
    ThemePalette.CORAL -> R.string.theme_palette_coral
    ThemePalette.AMBER -> R.string.theme_palette_amber
    ThemePalette.LIME -> R.string.theme_palette_lime
    ThemePalette.FOREST -> R.string.theme_palette_forest
    ThemePalette.TEAL -> R.string.theme_palette_teal
    ThemePalette.SLATE -> R.string.theme_palette_slate
}
