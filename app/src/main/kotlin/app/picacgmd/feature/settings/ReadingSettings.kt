package app.picacgmd.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ListItemShapes
import app.picacgmd.core.data.settings.ReaderBackground
import app.picacgmd.core.data.settings.ReaderDirection
import app.picacgmd.core.data.settings.ReaderFilter
import app.picacgmd.core.data.settings.ReaderMode
import app.picacgmd.core.data.settings.ReaderScrollMode
import app.picacgmd.core.data.settings.ReaderSliderPosition
import app.picacgmd.core.data.settings.ReaderTapZones
import app.picacgmd.core.data.settings.TwoPagePlacement
import app.picacgmd.di.LocalAppContainer
import java.util.Locale
import kotlin.math.roundToInt

private enum class ReadingDialog { Mode, Direction, TapZones, Background, Filter, ScrollMode, SliderPosition, TwoPage, Zoom }

/**
 * Board 26 阅读: reader defaults only — while reading, the reader's own sheet changes them
 * temporarily. The e-ink group sits last.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReadingSettingsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    var dialog by rememberSaveable { mutableStateOf<ReadingDialog?>(null) }

    SettingsPageScaffold(SettingsPage.Reading.title, onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            SettingsGroup("默认阅读方式") {
                row { shapes ->
                    SettingsNavRow(shapes, "阅读模式", { dialog = ReadingDialog.Mode }, value = settings.readerMode.label())
                }
                row { shapes ->
                    SettingsNavRow(shapes, "阅读方向", { dialog = ReadingDialog.Direction }, value = settings.readerDirection.label())
                }
                row { shapes ->
                    SettingsNavRow(shapes, "双页排列", { dialog = ReadingDialog.TwoPage }, summary = "双页相册模式下两页怎么摆", value = settings.twoPagePlacement.label())
                }
                row { shapes ->
                    SettingsNavRow(shapes, "阅读器背景", { dialog = ReadingDialog.Background }, value = settings.readerBackground.label())
                }
                row { shapes ->
                    SettingsNavRow(shapes, "图片滤镜", { dialog = ReadingDialog.Filter }, value = settings.readerFilter.label())
                }
                row { shapes ->
                    SettingsNavRow(shapes, "进度条位置", { dialog = ReadingDialog.SliderPosition }, value = settings.readerSliderPosition.label())
                }
            }
        }
        item {
            SettingsGroup("显示") {
                row { shapes -> SettingsSwitchRow(shapes, "屏幕常亮", settings.keepScreenOn, { writer.update { setKeepScreenOn(it) } }) }
                row { shapes ->
                    SettingsSwitchRow(shapes, "全屏阅读", settings.readerFullScreen, { writer.update { setReaderFullScreen(it) } }, summary = "进入阅读器时隐藏状态栏和导航栏")
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "向后翻页时自动全屏", settings.fullScreenOnForward, { writer.update { setFullScreenOnForward(it) } })
                }
                row { shapes -> SettingsSwitchRow(shapes, "翻页动画", settings.readerPageAnimation, { writer.update { setReaderPageAnimation(it) } }) }
            }
        }
        item {
            SettingsGroup("操控") {
                row { shapes ->
                    SettingsNavRow(shapes, "操控方式", { dialog = ReadingDialog.TapZones }, summary = "点屏幕哪里翻页、呼出菜单", value = settings.readerTapZones.label())
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "三区操控时右侧是下一页", settings.threeZoneNextOnRight, { writer.update { setThreeZoneNextOnRight(it) } },
                        enabled = settings.readerTapZones == ReaderTapZones.THREE_ZONES,
                    )
                }
                row { shapes -> SettingsSwitchRow(shapes, "音量键翻页", settings.volumeKeyPaging, { writer.update { setVolumeKeyPaging(it) } }) }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "连按两次音量键切换章节", settings.volumeKeyChapterPaging, { writer.update { setVolumeKeyChapterPaging(it) } },
                        enabled = settings.volumeKeyPaging,
                    )
                }
                row { shapes ->
                    SettingsNavRow(shapes, "滚动方式", { dialog = ReadingDialog.ScrollMode }, value = settings.readerScrollMode.label())
                }
                row { shapes ->
                    SliderRow(
                        shapes = shapes,
                        title = "每次滚动的屏幕比例",
                        value = settings.scrollScreenFraction,
                        range = 0.1f..1f,
                        steps = 8,
                        format = { "${(it * 100).roundToInt()}%" },
                        enabled = settings.readerScrollMode == ReaderScrollMode.SCREEN_DISTANCE,
                        onCommit = { writer.update { setScrollScreenFraction(it) } },
                    )
                }
                row { shapes ->
                    SliderRow(
                        shapes = shapes,
                        title = "手势速度",
                        value = settings.gestureSpeed,
                        range = 0.5f..3f,
                        steps = 9,
                        format = { "${it.oneDecimal()}×" },
                        onCommit = { writer.update { setGestureSpeed(it) } },
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "缩放范围", { dialog = ReadingDialog.Zoom },
                        summary = "最小 ${settings.minZoom.oneDecimal()}× · 最大 ${settings.maxZoom.oneDecimal()}× · 双击 ${settings.doubleTapZoom.oneDecimal()}×",
                        leading = null,
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "锁定拖动边界", settings.lockDragBounds, { writer.update { setLockDragBounds(it) } }, summary = "放大后不会把图片拖出屏幕")
                }
            }
        }
        item {
            SettingsGroup("电子墨水屏") {
                row { shapes ->
                    SettingsSwitchRow(shapes, "减少动画", settings.eInkReduceAnimations, { writer.update { setEInkReduceAnimations(it) } }, summary = "减少残影")
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "按屏滚动", settings.eInkScrollByScreen, { writer.update { setEInkScrollByScreen(it) } }, summary = "点按翻页时一次滚动一屏，不做平滑滚动")
                }
                row { shapes ->
                    SliderRow(
                        shapes = shapes,
                        title = "滚动幅度",
                        value = settings.eInkScrollAmount,
                        range = 0.1f..1f,
                        steps = 8,
                        format = { "${(it * 100).roundToInt()}%" },
                        enabled = settings.eInkScrollByScreen,
                        onCommit = { writer.update { setEInkScrollAmount(it) } },
                    )
                }
                row { shapes ->
                    SliderRow(
                        shapes = shapes,
                        title = "触发距离",
                        value = settings.eInkTriggerDistanceCm,
                        range = 0.1f..2f,
                        steps = 18,
                        format = { "${it.oneDecimal()} 厘米" },
                        onCommit = { writer.update { setEInkTriggerDistanceCm(it) } },
                    )
                }
            }
        }
    }

    val dismiss = { dialog = null }
    when (dialog) {
        ReadingDialog.Mode -> ChoiceDialog("阅读模式", ReaderMode.entries, settings?.readerMode, { it.label() }, { writer.update { setReaderMode(it) } }, dismiss)
        ReadingDialog.Direction -> ChoiceDialog("阅读方向", ReaderDirection.entries, settings?.readerDirection, { it.label() }, { writer.update { setReaderDirection(it) } }, dismiss)
        ReadingDialog.TapZones -> ChoiceDialog(
            "操控方式", ReaderTapZones.entries, settings?.readerTapZones, { it.label() }, { writer.update { setReaderTapZones(it) } }, dismiss,
            detail = { it.detail() },
        )
        ReadingDialog.Background -> ChoiceDialog("阅读器背景", ReaderBackground.entries, settings?.readerBackground, { it.label() }, { writer.update { setReaderBackground(it) } }, dismiss)
        ReadingDialog.Filter -> ChoiceDialog("图片滤镜", ReaderFilter.entries, settings?.readerFilter, { it.label() }, { writer.update { setReaderFilter(it) } }, dismiss)
        ReadingDialog.ScrollMode -> ChoiceDialog("滚动方式", ReaderScrollMode.entries, settings?.readerScrollMode, { it.label() }, { writer.update { setReaderScrollMode(it) } }, dismiss)
        ReadingDialog.SliderPosition -> ChoiceDialog("进度条位置", ReaderSliderPosition.entries, settings?.readerSliderPosition, { it.label() }, { writer.update { setReaderSliderPosition(it) } }, dismiss)
        ReadingDialog.TwoPage -> ChoiceDialog("双页排列", TwoPagePlacement.entries, settings?.twoPagePlacement, { it.label() }, { writer.update { setTwoPagePlacement(it) } }, dismiss)
        ReadingDialog.Zoom -> if (settings != null) {
            ZoomDialog(
                minimum = settings.minZoom,
                maximum = settings.maxZoom,
                doubleTap = settings.doubleTapZoom,
                onSave = { min, max, double -> writer.update { setReaderZoom(min, max, double) } },
                onDismiss = dismiss,
            )
        }
        null -> Unit
    }
}

/** Slider row that previews while dragging and writes once on release. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SliderRow(
    shapes: ListItemShapes,
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) local else value
    SegmentedListItem(
        onClick = {},
        shapes = shapes,
        enabled = enabled,
        trailingContent = {
            Text(format(shown), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        },
        supportingContent = {
            Slider(
                value = shown,
                onValueChange = {
                    dragging = true
                    local = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onCommit(local)
                },
                valueRange = range,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) { Text(title) }
}

@Composable
private fun ZoomDialog(
    minimum: Float,
    maximum: Float,
    doubleTap: Float,
    onSave: (Float, Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var min by remember { mutableFloatStateOf(minimum) }
    var max by remember { mutableFloatStateOf(maximum) }
    var double by remember { mutableFloatStateOf(doubleTap) }
    val valid = min <= double && double <= max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("缩放范围") },
        text = {
            Column {
                ZoomSlider("最小缩放", min, 0.5f..2f, 5) { min = it }
                ZoomSlider("最大缩放", max, 2f..5f, 5) { max = it }
                ZoomSlider("双击缩放", double, 1.5f..3f, 2) { double = it }
                if (!valid) {
                    Text("双击缩放需要在最小和最大之间", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(min, max, double); onDismiss() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ZoomSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${value.oneDecimal()}×", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

internal fun Float.oneDecimal(): String = String.format(Locale.ROOT, "%.1f", this)

private fun ReaderMode.label() = when (this) {
    ReaderMode.WEBTOON -> "条漫"
    ReaderMode.WEBTOON_ZOOM -> "条漫（可缩放）"
    ReaderMode.GALLERY -> "相册"
    ReaderMode.WEBTOON_FREE_ZOOM -> "条漫（自由缩放）"
    ReaderMode.TWO_PAGE_GALLERY -> "双页相册"
}

private fun ReaderDirection.label() = when (this) {
    ReaderDirection.TOP_TO_BOTTOM -> "从上到下"
    ReaderDirection.LEFT_TO_RIGHT -> "从左到右"
    ReaderDirection.RIGHT_TO_LEFT -> "从右到左（日漫）"
}

private fun ReaderTapZones.label() = when (this) {
    ReaderTapZones.SINGLE_TAP -> "单击呼出菜单"
    ReaderTapZones.CONTROLLER -> "控制器"
    ReaderTapZones.DOUBLE_TAP -> "双击呼出菜单"
    ReaderTapZones.DOUBLE_TAP_NEXT -> "双击下一页"
    ReaderTapZones.THREE_ZONES -> "三区点按"
}

private fun ReaderTapZones.detail() = when (this) {
    ReaderTapZones.SINGLE_TAP -> "点屏幕任意位置呼出菜单"
    ReaderTapZones.CONTROLLER -> "屏幕边上有翻页按钮"
    ReaderTapZones.DOUBLE_TAP -> "双击呼出菜单，单击不响应"
    ReaderTapZones.DOUBLE_TAP_NEXT -> "双击翻到下一页"
    ReaderTapZones.THREE_ZONES -> "左右点按翻页，中间呼出菜单"
}

private fun ReaderBackground.label() = when (this) {
    ReaderBackground.BLACK -> "黑"
    ReaderBackground.GREY -> "灰"
    ReaderBackground.PAPER -> "纸"
    ReaderBackground.WHITE -> "白"
}

private fun ReaderFilter.label() = when (this) {
    ReaderFilter.NORMAL -> "正常"
    ReaderFilter.GRAYSCALE -> "灰度"
    ReaderFilter.SEPIA -> "复古"
}

private fun ReaderScrollMode.label() = when (this) {
    ReaderScrollMode.IMAGE -> "按图片"
    ReaderScrollMode.SCREEN_DISTANCE -> "按屏幕距离"
}

private fun ReaderSliderPosition.label() = when (this) {
    ReaderSliderPosition.BOTTOM -> "底部"
    ReaderSliderPosition.RIGHT -> "右侧"
    ReaderSliderPosition.LEFT -> "左侧"
}

private fun TwoPagePlacement.label() = when (this) {
    TwoPagePlacement.CLOSE_TO -> "靠拢"
    TwoPagePlacement.PULL_APART -> "分开"
    TwoPagePlacement.EACH_CENTERED -> "各自居中"
}

