package com.androidharness.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Checkbox as M3Checkbox
import androidx.compose.material3.FilledIconButton as M3FilledIconButton
import androidx.compose.material3.FilledTonalButton as M3FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton as M3FilledTonalIconButton
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.IconToggleButton as M3IconToggleButton
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.RadioButton as M3RadioButton
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.TextButton as M3TextButton

/*
 * Drop-in replacements for the Material 3 buttons and toggles. Signatures and defaults match the
 * classic overloads so every call site keeps its size, colors and touch target; the only change is
 * the expressive shape-morph on press and the expressive spring motion. The scheme is applied per
 * control (the app-wide scheme stays as is), and the surrounding text style is re-provided because
 * a nested MaterialTheme would otherwise reset it.
 */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveMotion(content: @Composable () -> Unit) {
    val textStyle = LocalTextStyle.current
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        ProvideTextStyle(textStyle, content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun resolveButtonShapes(shape: Shape?, shapes: ButtonShapes?): ButtonShapes =
    shapes ?: if (shape == null) ButtonDefaults.shapes() else ButtonDefaults.shapes(shape, ButtonDefaults.pressedShape)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun resolveIconButtonShapes(shape: Shape?, shapes: IconButtonShapes?): IconButtonShapes =
    shapes ?: if (shape == null) IconButtonDefaults.shapes() else IconButtonDefaults.shapes(shape, IconButtonDefaults.smallPressedShape)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    shapes: ButtonShapes? = null,
    content: @Composable RowScope.() -> Unit,
) = ExpressiveMotion {
    M3Button(onClick, resolveButtonShapes(shape, shapes), modifier, enabled, colors, elevation, border, contentPadding, interactionSource, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    shapes: ButtonShapes? = null,
    content: @Composable RowScope.() -> Unit,
) = ExpressiveMotion {
    M3FilledTonalButton(onClick, resolveButtonShapes(shape, shapes), modifier, enabled, colors, elevation, border, contentPadding, interactionSource, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    shapes: ButtonShapes? = null,
    content: @Composable RowScope.() -> Unit,
) = ExpressiveMotion {
    M3OutlinedButton(onClick, resolveButtonShapes(shape, shapes), modifier, enabled, colors, elevation, border, contentPadding, interactionSource, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    shapes: ButtonShapes? = null,
    content: @Composable RowScope.() -> Unit,
) = ExpressiveMotion {
    M3TextButton(onClick, resolveButtonShapes(shape, shapes), modifier, enabled, colors, elevation, border, contentPadding, interactionSource, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    shapes: IconButtonShapes? = null,
    content: @Composable () -> Unit,
) = ExpressiveMotion {
    M3IconButton(onClick, resolveIconButtonShapes(shape, shapes), modifier, enabled, colors, interactionSource, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    shapes: IconButtonShapes? = null,
    content: @Composable () -> Unit,
) = ExpressiveMotion {
    M3FilledIconButton(onClick, resolveIconButtonShapes(shape, shapes), modifier, enabled, colors, interactionSource, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    shapes: IconButtonShapes? = null,
    content: @Composable () -> Unit,
) = ExpressiveMotion {
    M3FilledTonalIconButton(onClick, resolveIconButtonShapes(shape, shapes), modifier, enabled, colors, interactionSource, content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconToggleButtonColors = IconButtonDefaults.iconToggleButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    shapes: IconToggleButtonShapes? = null,
    content: @Composable () -> Unit,
) = ExpressiveMotion {
    M3IconToggleButton(checked, onCheckedChange, shapes ?: IconButtonDefaults.toggleableShapes(), modifier, enabled, colors, interactionSource, content)
}

@Composable
internal fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) = ExpressiveMotion {
    M3Switch(checked, onCheckedChange, modifier, thumbContent, enabled, colors, interactionSource)
}

@Composable
internal fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) = ExpressiveMotion {
    M3Checkbox(checked, onCheckedChange, modifier, enabled, colors, interactionSource)
}

@Composable
internal fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) = ExpressiveMotion {
    M3RadioButton(selected, onClick, modifier, enabled, colors, interactionSource)
}
