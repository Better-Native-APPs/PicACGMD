package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * Buttons for the app: every one defaults `shapes` to the Expressive set
 * (design/SPEC.md 142, 165), so a press tightens the corners instead of only tinting.
 * The morph runs on the interaction source, i.e. only while a finger is down, so it costs
 * nothing while the screen is idle (audit §"low-end rule").
 *
 * The parameter lists mirror the Material 3 overloads one-for-one, so swapping a call site is
 * `Button(` → `AppButton(` with everything else left as it is.
 */

/** [Button] with the Expressive press shape-morph. */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [FilledTonalButton] with the Expressive press shape-morph. */
@Composable
fun AppFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [OutlinedButton] with the Expressive press shape-morph. */
@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [TextButton] with the Expressive press shape-morph. */
@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [IconButton] with the Expressive press shape-morph. */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [FilledIconButton] with the Expressive press shape-morph. */
@Composable
fun AppFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [FilledTonalIconButton] with the Expressive press shape-morph. */
@Composable
fun AppFilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [IconToggleButton] with the Expressive press shape-morph. */
@Composable
fun AppIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconToggleButtonColors = IconButtonDefaults.iconToggleButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}

/** [FilledIconToggleButton] with the Expressive press shape-morph. */
@Composable
fun AppFilledIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconToggleButtonColors = IconButtonDefaults.filledIconToggleButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}
