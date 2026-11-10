package app.picacgmd.feature.settings

import app.picacgmd.core.designsystem.window.secureDialogProperties

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.designsystem.component.AppSwitch
import app.picacgmd.core.designsystem.component.withoutSelectedShape
import app.picacgmd.core.designsystem.component.withoutSelectedTint
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared building blocks for every settings page, the download settings and 「我的」 (boards 18,
 * 24, 25–28b): segmented list groups, connected button groups and single-choice dialogs.
 */

/** Persists one change and reports a failed write in the page snackbar instead of crashing. */
internal class SettingsWriter(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) {
    fun update(block: suspend SettingsRepository.() -> Unit) {
        scope.launch {
            try {
                repository.block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                snackbar.showSnackbar(exception.message ?: "保存失败")
            }
        }
    }
}

/** Current settings, or null until the first DataStore read so switches never flash defaults. */
@Composable
internal fun rememberAppSettings(): AppSettings? {
    val container = LocalAppContainer.current
    val settings by remember(container) { container.settingsRepository.settings }
        .collectAsStateWithLifecycle(initialValue = null)
    return settings
}

/** Collects segmented rows so each one can receive its position-dependent shape. */
internal class SettingsGroupBuilder {
    internal val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun row(content: @Composable (shapes: ListItemShapes) -> Unit) {
        rows += content
    }
}

/** A titled block of segmented rows: big corners on the ends, small ones between (board 25). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    build: SettingsGroupBuilder.() -> Unit,
) {
    val builder = SettingsGroupBuilder().apply(build)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (title != null) SettingsGroupTitle(title)
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            builder.rows.forEachIndexed { index, row ->
                row(ListItemDefaults.segmentedShapes(index = index, count = builder.rows.size))
            }
        }
    }
}

@Composable
internal fun SettingsGroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsNavRow(
    shapes: ListItemShapes,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = value == null,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        modifier = modifier,
        enabled = enabled,
        leadingContent = leading,
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = if (value != null || showChevron) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value != null) {
                        Text(
                            value,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (showChevron) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                }
            }
        } else {
            null
        },
    ) {
        Text(title)
    }
}

/**
 * A row that is on looks like its neighbours: the switch (with its check mark) carries the state,
 * the container keeps the group's colour and corners.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsSwitchRow(
    shapes: ListItemShapes,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = ListItemDefaults.segmentedColors()
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = remember(shapes) { shapes.withoutSelectedShape() },
        modifier = modifier,
        enabled = enabled,
        leadingContent = leading,
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { AppSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = remember(colors) { colors.withoutSelectedTint() },
    ) {
        Text(title)
    }
}

/** A non-interactive explanation row inside a group (warnings, notes). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsNoteRow(
    shapes: ListItemShapes,
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    SegmentedListItem(
        onClick = {},
        shapes = shapes,
        modifier = modifier,
        enabled = false,
        leadingContent = { Icon(icon, contentDescription = null, tint = content) },
        colors = ListItemDefaults.segmentedColors(
            containerColor = container,
            disabledContainerColor = container,
            disabledContentColor = content,
            disabledSupportingContentColor = content,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

/** 40dp MaterialShapes badge used as the leading identity of a settings group (board 25). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShapeBadge(
    icon: ImageVector,
    shape: RoundedPolygon,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Int = 40,
) {
    val clip = shape.toShape()
    Box(
        modifier = modifier
            .size(size.dp)
            .background(container, clip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size((size / 2).dp))
    }
}

/** Connected toggle buttons acting as a single-choice group (M3 Expressive ButtonGroup). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> ConnectedChoice(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                val vector = icon?.invoke(option)
                if (vector != null) {
                    Icon(vector, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                }
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A labelled ButtonGroup sitting on a segmented container, for the most changed choices. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> SettingsChoiceRow(
    shapes: ListItemShapes,
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    summary: String? = null,
    icon: ((T) -> ImageVector?)? = null,
) {
    SegmentedListItem(
        onClick = {},
        shapes = shapes,
        enabled = true,
        supportingContent = {
            Column {
                if (summary != null) Text(summary)
                ConnectedChoice(
                    options = options,
                    selected = selected,
                    onSelect = onSelect,
                    label = label,
                    icon = icon,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
        },
    ) {
        Text(title)
    }
}

/** Radio list in an AlertDialog; picking an option applies it immediately and closes. */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
    detail: ((T) -> String?)? = null,
) {
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(option)
                                    onDismiss()
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(label(option), style = MaterialTheme.typography.bodyLarge)
                            detail?.invoke(option)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_action_cancel)) }
        },
    )
}

/** Small-title page with back navigation; content goes in a LazyColumn with bottom breathing room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton(onBack) },
                actions = { actions() },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    app.picacgmd.core.designsystem.component.BackButton(onBack)
}

/** Vertical gap item for LazyColumns built out of groups. */
internal fun LazyListScope.gap(height: Int = 8) {
    item { Spacer(Modifier.height(height.dp)) }
}
