package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

/** Default content padding of [ConnectedChoiceGroup] buttons: tight enough for four options per row. */
private val DefaultOptionPadding = PaddingValues(horizontal = 8.dp)

/**
 * Connected single-choice group (`cbg()` in the design spec): the library's `ButtonGroup` shapes
 * over [ToggleButton]s, which is the documented Expressive pattern — the selected option rounds out
 * into a pill, the ends keep their outer corner, and a press tightens the inner corner.
 *
 * One component replaces the four near-identical copies the audit found (rankings time range,
 * settings choices, the view switchers of shelf and downloads, and the reader sheet's groups), so
 * that "connected choice" looks and reads the same everywhere.
 *
 * @param options the mutually exclusive choices, in display order.
 * @param selected the current choice, or `null` when nothing is chosen yet.
 * @param role the semantics role; [Role.RadioButton] for a choice within a form, [Role.Tab] for a
 *   same-page view switcher.
 * @param enabled per-option enablement, e.g. an option that is not available yet.
 * @param content the option's content; a label, or a label with an [Icon] / a count badge. The
 *   group supplies nothing itself, so each screen keeps its own wording.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    enabled: (T) -> Boolean = { true },
    contentPadding: PaddingValues = DefaultOptionPadding,
    content: @Composable RowScope.(T) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val last = options.lastIndex
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { if (it) onSelect(option) },
                enabled = enabled(option),
                modifier = Modifier
                    .weight(1f)
                    .semantics { this.role = role },
                shapes = when {
                    options.size == 1 -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == last -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = contentPadding,
            ) {
                content(option)
            }
        }
    }
}

/**
 * [ConnectedChoiceGroup] for the common case of a plain label with an optional leading icon.
 *
 * @param label the option's text.
 * @param icon an optional icon shown before the label, e.g. a reading mode glyph.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    icon: ((T) -> ImageVector?)? = null,
    enabled: (T) -> Boolean = { true },
) {
    ConnectedChoiceGroup(
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        role = role,
        enabled = enabled,
    ) { option ->
        val vector = icon?.invoke(option)
        if (vector != null) {
            Icon(vector, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class RangePreview(val label: String) { Day24("24 小时"), Week7("7 天"), Month30("30 天") }

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun ConnectedChoiceGroupPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        ConnectedChoiceGroup(
            options = RangePreview.entries,
            selected = RangePreview.Week7,
            onSelect = {},
            label = { it.label },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
