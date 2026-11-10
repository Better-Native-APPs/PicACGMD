package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's switch: the thumb grows and shows a check mark while on, so the control itself says
 * "on" by shape as well as by colour. Pass a null [onCheckedChange] when the whole row toggles.
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
    )
}

/**
 * A toggle list item is "selected" while it is on, which by default recolours and reshapes the
 * whole row. In a list of switches the switch already carries that state, so these keep the row
 * exactly as it is when off; its toggle semantics are untouched.
 */
fun ListItemColors.withoutSelectedTint(): ListItemColors = copy(
    selectedContainerColor = containerColor,
    selectedContentColor = contentColor,
    selectedLeadingContentColor = leadingContentColor,
    selectedTrailingContentColor = trailingContentColor,
    selectedOverlineContentColor = overlineContentColor,
    selectedSupportingContentColor = supportingContentColor,
)

/** See [withoutSelectedTint]: a row that is on keeps its place in the group's corner rhythm. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun ListItemShapes.withoutSelectedShape(): ListItemShapes = copy(selectedShape = shape)
