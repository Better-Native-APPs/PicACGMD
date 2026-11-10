package app.picacgmd.feature.reader.ui

import androidx.compose.runtime.key
import app.picacgmd.core.designsystem.component.AppSwitch
import app.picacgmd.core.designsystem.component.withoutSelectedShape
import app.picacgmd.core.designsystem.component.withoutSelectedTint
import app.picacgmd.core.designsystem.window.secureModalBottomSheetProperties
import app.picacgmd.core.designsystem.window.secureWindowPolicy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.West
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.ImageQuality
import app.picacgmd.feature.reader.ReaderRouteControls
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Image
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.ReaderBackground
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.isContinuous
import app.picacgmd.feature.reader.ReaderDirection
import app.picacgmd.feature.reader.ReaderFilter
import app.picacgmd.feature.reader.ReaderMode
import app.picacgmd.feature.reader.TapMode
import app.picacgmd.feature.reader.TwoPageArrangement
import app.picacgmd.feature.reader.logic.availableDirections
import app.picacgmd.feature.reader.logic.effectiveDirection
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A bottom sheet whose content can close it with the usual slide before something else happens.
 * [content] receives `hide`, which animates the sheet away and then runs its argument.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSheet(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    scrim: Color = BottomSheetDefaults.ScrimColor,
    content: @Composable ColumnScope.(hide: (then: () -> Unit) -> Unit) -> Unit,
) {
    val state = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = if (skipPartiallyExpanded) {
            setOf(SheetValue.Hidden, SheetValue.Expanded)
        } else {
            setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
        },
    )
    val scope = rememberCoroutineScope()
    val hide: (() -> Unit) -> Unit = { then ->
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, scrimColor = scrim, properties = secureModalBottomSheetProperties()) {
        content(hide)
    }
}

/** Lighter than the usual scrim: every change applies at once and shows through behind the sheet. */
private val SettingsScrim = Color(0x1F000000)

/**
 * 阅读设置: the settings people change while reading, one screen, applied at once. Everything else is
 * behind 全部设置.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderSettingsSheet(
    config: ReaderConfig,
    onConfigChange: (ReaderConfig) -> Unit,
    onDismiss: () -> Unit,
    onOpenAllSettings: (() -> Unit)?,
    onPreviewZones: () -> Unit,
    routes: ReaderRouteControls? = null,
) {
    ReaderSheet(onDismiss = onDismiss, scrim = SettingsScrim) { hide ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                    .heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reader_settings_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                if (onOpenAllSettings != null) {
                    SmallTextButton(stringResource(R.string.reader_all_settings)) { hide(onOpenAllSettings) }
                }
            }
            SettingRow(Icons.Rounded.AutoStories, stringResource(R.string.reader_setting_mode)) {
                ChoiceGroup(
                    options = ReaderMode.entries,
                    selected = config.mode,
                    onSelect = { onConfigChange(config.copy(mode = it)) },
                ) { mode ->
                    Text(
                        text = stringResource(
                            when (mode) {
                                ReaderMode.Strip -> R.string.reader_mode_strip
                                ReaderMode.Paged -> R.string.reader_mode_paged
                                ReaderMode.DoublePage -> R.string.reader_mode_double
                                ReaderMode.FreeZoom -> R.string.reader_mode_free
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
            SettingRow(Icons.Rounded.SwapVert, stringResource(R.string.reader_setting_direction)) {
                val allowed = availableDirections(config.mode)
                ChoiceGroup(
                    options = ReaderDirection.entries,
                    selected = effectiveDirection(config.mode, config.direction),
                    onSelect = { onConfigChange(config.copy(direction = it)) },
                    enabled = { it in allowed },
                ) { direction ->
                    val (icon, label) = when (direction) {
                        ReaderDirection.TopToBottom -> Icons.Rounded.South to R.string.reader_direction_ttb
                        ReaderDirection.LeftToRight -> Icons.Rounded.East to R.string.reader_direction_ltr
                        ReaderDirection.RightToLeft -> Icons.Rounded.West to R.string.reader_direction_rtl
                    }
                    Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(18.dp))
                }
            }
            // Settings that only mean something in the chosen mode sit right under it.
            ExpandingRow(visible = config.mode == ReaderMode.DoublePage) {
                Column {
                    SettingRow(Icons.AutoMirrored.Rounded.MenuBook, stringResource(R.string.reader_setting_arrangement)) {
                        ChoiceGroup(
                            options = TwoPageArrangement.entries,
                            selected = config.twoPageArrangement,
                            onSelect = { onConfigChange(config.copy(twoPageArrangement = it)) },
                        ) { arrangement ->
                            Text(
                                text = stringResource(
                                    when (arrangement) {
                                        TwoPageArrangement.CloseTo -> R.string.reader_arrangement_close
                                        TwoPageArrangement.PullAway -> R.string.reader_arrangement_away
                                        TwoPageArrangement.EachCentered -> R.string.reader_arrangement_centered
                                    },
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.reader_arrangement_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = SettingContentStart, end = 20.dp),
                    )
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        SwitchRow(
                            icon = Icons.Rounded.Book,
                            title = stringResource(R.string.reader_setting_cover_alone),
                            supporting = stringResource(R.string.reader_setting_cover_alone_detail),
                            checked = config.twoPageCoverAlone,
                            onCheckedChange = { onConfigChange(config.copy(twoPageCoverAlone = it)) },
                        )
                    }
                }
            }
            ExpandingRow(visible = config.mode == ReaderMode.FreeZoom) {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    SwitchRow(
                        icon = Icons.Rounded.CropFree,
                        title = stringResource(R.string.reader_setting_lock_drag),
                        supporting = stringResource(R.string.reader_setting_lock_drag_detail),
                        checked = config.lockDragBounds,
                        onCheckedChange = { onConfigChange(config.copy(lockDragBounds = it)) },
                    )
                }
            }
            SettingRow(Icons.Rounded.FormatColorFill, stringResource(R.string.reader_setting_background)) {
                BackgroundChoice(config.background) { onConfigChange(config.copy(background = it)) }
            }
            SettingRow(Icons.Rounded.PhotoFilter, stringResource(R.string.reader_setting_filter)) {
                ChoiceGroup(
                    options = ReaderFilter.entries,
                    selected = config.filter,
                    onSelect = { onConfigChange(config.copy(filter = it)) },
                ) { filter ->
                    Text(
                        text = stringResource(
                            when (filter) {
                                ReaderFilter.Normal -> R.string.reader_filter_normal
                                ReaderFilter.Grayscale -> R.string.reader_filter_gray
                                ReaderFilter.Sepia -> R.string.reader_filter_sepia
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
            SettingRow(Icons.Rounded.HighQuality, stringResource(R.string.reader_setting_quality)) {
                ChoiceGroup(
                    options = ImageQuality.entries,
                    selected = config.quality,
                    onSelect = { onConfigChange(config.copy(quality = it)) },
                ) { quality ->
                    Text(
                        text = stringResource(
                            when (quality) {
                                ImageQuality.Original -> R.string.reader_quality_original
                                ImageQuality.High -> R.string.reader_quality_high
                                ImageQuality.Medium -> R.string.reader_quality_medium
                                ImageQuality.Low -> R.string.reader_quality_low
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
            if (routes != null) {
                SettingRow(Icons.Rounded.Dns, stringResource(R.string.reader_setting_api_route)) {
                    RoutePill(routes.apiLabel, routes.onOpenApiRoutes)
                }
                SettingRow(Icons.Rounded.Image, stringResource(R.string.reader_setting_image_route)) {
                    RoutePill(routes.imageLabel, routes.onOpenImageRoutes)
                }
            }
            SettingRow(Icons.Rounded.TouchApp, stringResource(R.string.reader_setting_tap)) {
                Column {
                    TapModeDropdown(config.tapMode) { onConfigChange(config.copy(tapMode = it)) }
                    SmallTextButton(stringResource(R.string.reader_preview_zones)) { hide(onPreviewZones) }
                }
            }
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp)) {
                SwitchRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.reader_setting_volume),
                    checked = config.volumeKeyPaging,
                    onCheckedChange = { onConfigChange(config.copy(volumeKeyPaging = it)) },
                )
                ExpandingRow(visible = config.volumeKeyPaging) {
                    SwitchRow(
                        icon = null,
                        title = stringResource(R.string.reader_setting_volume_jump),
                        checked = config.volumeKeyChapterJump,
                        onCheckedChange = { onConfigChange(config.copy(volumeKeyChapterJump = it)) },
                    )
                }
                SwitchRow(
                    icon = Icons.Rounded.Fullscreen,
                    title = stringResource(R.string.reader_setting_auto_fullscreen),
                    checked = config.autoFullscreen,
                    onCheckedChange = { onConfigChange(config.copy(autoFullscreen = it)) },
                )
                if (config.mode.isContinuous) {
                    AutoScrollRows(config, onConfigChange)
                } else {
                    SwitchRow(
                        icon = Icons.Rounded.Timer,
                        title = stringResource(R.string.reader_setting_auto_turn),
                        supporting = stringResource(R.string.reader_setting_auto_turn_every, config.autoTurnSeconds),
                        checked = config.autoTurn,
                        onCheckedChange = { onConfigChange(config.copy(autoTurn = it)) },
                    )
                    ExpandingRow(visible = config.autoTurn) {
                        AutoTurnSlider(config.autoTurnSeconds) { onConfigChange(config.copy(autoTurnSeconds = it)) }
                    }
                }
                SwitchRow(
                    icon = Icons.Rounded.Animation,
                    title = stringResource(R.string.reader_setting_no_animation),
                    checked = config.noPageAnimation,
                    onCheckedChange = { onConfigChange(config.copy(noPageAnimation = it)) },
                )
            }
        }
    }
}

/** 条漫 / 自由缩放: 自动滚动 with its speed and touch pause, and 自动翻章. */
@Composable
private fun ColumnScope.AutoScrollRows(config: ReaderConfig, onConfigChange: (ReaderConfig) -> Unit) {
    SwitchRow(
        icon = Icons.Rounded.Timer,
        title = stringResource(R.string.reader_setting_auto_scroll),
        supporting = stringResource(R.string.reader_setting_auto_scroll_speed, autoScrollSpeedLabel(config.autoScrollSpeed)),
        checked = config.autoTurn,
        onCheckedChange = { onConfigChange(config.copy(autoTurn = it)) },
    )
    ExpandingRow(visible = config.autoTurn) {
        Column {
            AutoScrollSpeedSlider(config.autoScrollSpeed) { onConfigChange(config.copy(autoScrollSpeed = it)) }
            SwitchRow(
                icon = null,
                title = stringResource(R.string.reader_setting_auto_scroll_pause_on_touch),
                checked = config.autoScrollPauseOnTouch,
                onCheckedChange = { onConfigChange(config.copy(autoScrollPauseOnTouch = it)) },
            )
        }
    }
    SwitchRow(
        icon = Icons.Rounded.SkipNext,
        title = stringResource(R.string.reader_setting_auto_next_chapter),
        supporting = stringResource(R.string.reader_setting_auto_next_chapter_supporting),
        checked = config.autoNextChapter,
        onCheckedChange = { onConfigChange(config.copy(autoNextChapter = it)) },
    )
}

@Composable
private fun autoScrollSpeedLabel(step: Int): String = stringResource(
    when (step.coerceIn(ReaderConfig.AUTO_SCROLL_SPEED_RANGE)) {
        1 -> R.string.reader_auto_scroll_speed_1
        2 -> R.string.reader_auto_scroll_speed_2
        3 -> R.string.reader_auto_scroll_speed_3
        4 -> R.string.reader_auto_scroll_speed_4
        else -> R.string.reader_auto_scroll_speed_5
    },
)

/** Five steps, 很慢 to 很快, under the switch while 自动滚动 is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoScrollSpeedSlider(step: Int, onChange: (Int) -> Unit) {
    val range = ReaderConfig.AUTO_SCROLL_SPEED_RANGE
    val slider = remember {
        SliderState(
            value = step.toFloat(),
            steps = range.last - range.first - 1,
            trackRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
    val label = stringResource(R.string.reader_setting_auto_scroll)
    Slider(
        state = slider,
        onValueChange = { value ->
            slider.value = value
            val next = value.roundToInt()
            if (next != step) onChange(next)
        },
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label },
    )
}

/** A row that belongs to the setting above it (a switch, or the mode) and only shows while it applies. */
@Composable
private fun ColumnScope.ExpandingRow(visible: Boolean, content: @Composable () -> Unit) {
    val reduceMotion = LocalReaderReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}

/** 2–30 秒, under the switch while 自动翻页 is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoTurnSlider(seconds: Int, onChange: (Int) -> Unit) {
    val range = ReaderConfig.AUTO_TURN_SECONDS_RANGE
    val slider = remember {
        SliderState(
            value = seconds.toFloat(),
            steps = range.last - range.first - 1,
            trackRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
    val label = stringResource(R.string.reader_setting_auto_turn)
    Slider(
        state = slider,
        onValueChange = { value ->
            slider.value = value
            val next = value.roundToInt()
            if (next != seconds) onChange(next)
        },
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = label },
    )
}

/** The current route as a pill, in the shape of the tap-mode field; a tap opens the app's picker. */
@Composable
private fun RoutePill(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SmallTextButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
        contentPadding = ButtonDefaults.ExtraSmallContentPadding,
    ) {
        Text(text, style = ButtonDefaults.textStyleFor(ButtonDefaults.ExtraSmallContainerHeight))
    }
}

/** Where a [SettingRow]'s content starts: padding 20, icon 24, gap 12, label 64, gap 12. */
private val SettingContentStart = 132.dp

@Composable
private fun SettingRow(icon: ImageVector, label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** Connected buttons, one of which is on; the selected one rounds out into a pill. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
    content: @Composable RowScope.(T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val last = options.lastIndex
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { if (it) onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                enabled = enabled(option),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    last -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
            ) {
                content(option)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackgroundChoice(selected: ReaderBackground, onSelect: (ReaderBackground) -> Unit) {
    val reduceMotion = LocalReaderReduceMotion.current
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReaderBackground.entries.forEach { background ->
            val isSelected = background == selected
            val corner by animateDpAsState(
                targetValue = if (isSelected) 12.dp else 18.dp,
                animationSpec = if (reduceMotion) snap() else MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "background shape",
            )
            val shape = RoundedCornerShape(corner)
            Column(
                modifier = Modifier.selectable(
                    selected = isSelected,
                    role = Role.RadioButton,
                    onClick = { onSelect(background) },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(shape)
                        .background(background.palette().background)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = shape,
                        ),
                )
                Text(
                    text = stringResource(
                        when (background) {
                            ReaderBackground.Black -> R.string.reader_background_black
                            ReaderBackground.Gray -> R.string.reader_background_gray
                            ReaderBackground.Paper -> R.string.reader_background_paper
                            ReaderBackground.White -> R.string.reader_background_white
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun TapMode.label(): Int = when (this) {
    TapMode.TapToToggle -> R.string.reader_tap_mode_tap
    TapMode.Controller -> R.string.reader_tap_mode_controller
    TapMode.DoubleTapToToggle -> R.string.reader_tap_mode_double
    TapMode.DoubleTapToggleTapNext -> R.string.reader_tap_mode_double_next
    TapMode.ThreeZones -> R.string.reader_tap_mode_zones
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TapModeDropdown(selected: TapMode, onSelect: (TapMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Surface(
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .height(40.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(selected.label()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }
        // This API exposes no properties; recreate its inherited window when the policy changes.
        key(secureWindowPolicy()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                TapMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(stringResource(mode.label())) },
                        onClick = {
                            expanded = false
                            onSelect(mode)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchRow(
    icon: ImageVector?,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    val shapes = ListItemDefaults.shapes()
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    // The switch carries the state; a row that is on keeps the sheet's own surface and shape.
    ListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        supportingContent = supporting?.let { text -> { Text(text) } },
        leadingContent = {
            if (icon != null) {
                Icon(icon, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        trailingContent = { AppSwitch(checked = checked, onCheckedChange = null) },
        shapes = remember(shapes) { shapes.withoutSelectedShape() },
        colors = remember(colors) { colors.withoutSelectedTint() },
    ) {
        Text(title)
    }
}
