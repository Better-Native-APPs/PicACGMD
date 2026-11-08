package app.picacgmd.feature.settings

import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.picacgmd.core.data.settings.AppOrientation
import app.picacgmd.core.data.settings.AppTheme
import app.picacgmd.core.data.settings.DarkMode
import app.picacgmd.core.data.settings.FullScreenDisplay
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.designsystem.theme.isDynamicColorSupported
import app.picacgmd.core.designsystem.theme.staticColorScheme
import app.picacgmd.di.LocalAppContainer
import kotlin.math.roundToInt

private enum class AppearanceDialog { RefreshRate, Orientation, FullScreen, StartupImage }

/** Board 25 外观: theme mode, palette preview cards, pure black, display behaviour. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppearanceSettingsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    var dialog by rememberSaveable { mutableStateOf<AppearanceDialog?>(null) }
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            writer.update { setStartupImageUri(uri.toString()) }
        }
    }
    val displayModes = remember(context) {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        display?.supportedModes.orEmpty().sortedBy { it.refreshRate }
    }

    SettingsPageScaffold(title = SettingsPage.Appearance.title, onBack = onBack, snackbar = snackbar, modifier = modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            SettingsGroup("主题") {
                row { shapes ->
                    SettingsChoiceRow(
                        shapes = shapes,
                        title = "主题模式",
                        options = DarkMode.entries,
                        selected = settings.darkMode,
                        onSelect = { writer.update { setDarkMode(it) } },
                        label = { it.label() },
                        icon = {
                            when (it) {
                                DarkMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                                DarkMode.LIGHT -> Icons.Outlined.LightMode
                                DarkMode.DARK -> Icons.Outlined.DarkMode
                            }
                        },
                    )
                }
            }
        }
        item {
            PaletteCards(
                selected = settings.theme,
                onSelect = { writer.update { setTheme(it) } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Text(
                if (isDynamicColorSupported) "「壁纸取色」跟随系统壁纸"
                else "需要 Android 12 及以上",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            SettingsGroup(null, Modifier.padding(top = 12.dp)) {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes = shapes,
                        title = "深色时用纯黑背景",
                        summary = "适合 OLED 屏幕",
                        checked = settings.pureBlack,
                        onCheckedChange = { writer.update { setPureBlack(it) } },
                    )
                }
            }
        }
        item {
            SettingsGroup("显示") {
                row { shapes ->
                    val current = displayModes.firstOrNull { it.modeId == settings.displayModeId }
                    SettingsNavRow(
                        shapes = shapes,
                        title = "屏幕刷新率",
                        summary = "仅对本应用生效",
                        value = current?.let { "${it.refreshRate.roundToInt()} Hz" } ?: "跟随系统",
                        onClick = { dialog = AppearanceDialog.RefreshRate },
                        enabled = displayModes.size > 1,
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes = shapes,
                        title = "屏幕方向",
                        value = settings.appOrientation.label(),
                        onClick = { dialog = AppearanceDialog.Orientation },
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes = shapes,
                        title = "全屏显示",
                        summary = "隐藏状态栏或导航栏",
                        value = settings.fullScreenDisplay.label(),
                        onClick = { dialog = AppearanceDialog.FullScreen },
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes = shapes,
                        title = "连按两次返回退出",
                        summary = "仅在首页生效",
                        checked = settings.doubleBackToExit,
                        onCheckedChange = { writer.update { setDoubleBackToExit(it) } },
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes = shapes,
                        title = "启动图片",
                        summary = "使用自选图片",
                        value = if (settings.startupImageUri == null) "默认" else "已设置",
                        onClick = {
                            if (settings.startupImageUri == null) pickImage.launch(arrayOf("image/*"))
                            else dialog = AppearanceDialog.StartupImage
                        },
                    )
                }
            }
        }
    }

    when (dialog) {
        AppearanceDialog.RefreshRate -> ChoiceDialog(
            title = "屏幕刷新率",
            description = "仅对本应用生效",
            options = listOf<Int?>(null) + displayModes.map { it.modeId },
            selected = settings?.displayModeId,
            label = { id ->
                val mode = displayModes.firstOrNull { it.modeId == id }
                if (mode == null) "跟随系统" else "${mode.refreshRate.roundToInt()} Hz"
            },
            detail = { id ->
                val mode = displayModes.firstOrNull { it.modeId == id }
                if (mode == null) null else "${mode.physicalWidth} × ${mode.physicalHeight}"
            },
            onSelect = { writer.update { setDisplayModeId(it) } },
            onDismiss = { dialog = null },
        )
        AppearanceDialog.Orientation -> ChoiceDialog(
            title = "屏幕方向",
            options = AppOrientation.entries,
            selected = settings?.appOrientation,
            label = { it.label() },
            onSelect = { writer.update { setAppOrientation(it) } },
            onDismiss = { dialog = null },
        )
        AppearanceDialog.FullScreen -> ChoiceDialog(
            title = "全屏显示",
            options = FullScreenDisplay.entries,
            selected = settings?.fullScreenDisplay,
            label = { it.label() },
            onSelect = { writer.update { setFullScreenDisplay(it) } },
            onDismiss = { dialog = null },
        )
        AppearanceDialog.StartupImage -> ChoiceDialog(
            title = "启动图片",
            options = listOf("change", "reset"),
            selected = null,
            label = { if (it == "change") "换一张图片" else "恢复默认" },
            onSelect = {
                if (it == "change") pickImage.launch(arrayOf("image/*")) else writer.update { setStartupImageUri(null) }
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun DarkMode.label() = when (this) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "浅色"
    DarkMode.DARK -> "深色"
}

private fun AppOrientation.label() = when (this) {
    AppOrientation.SYSTEM -> "跟随系统"
    AppOrientation.LANDSCAPE -> "横屏"
    AppOrientation.PORTRAIT -> "竖屏"
}

private fun FullScreenDisplay.label() = when (this) {
    FullScreenDisplay.OFF -> "不隐藏"
    FullScreenDisplay.HIDE_NAVIGATION -> "隐藏导航栏"
    FullScreenDisplay.HIDE_SYSTEM_BARS -> "全部隐藏"
}

private fun AppTheme.label() = when (this) {
    AppTheme.DYNAMIC -> "壁纸取色"
    AppTheme.BRAND_PINK -> "品牌粉"
    AppTheme.BOOK -> "书本"
    AppTheme.GREY_BLUE -> "灰蓝"
}

/** Two-by-two mini previews of each palette in the current brightness (board 25 配色卡). */
@Composable
private fun PaletteCards(selected: AppTheme, onSelect: (AppTheme) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val themes = AppTheme.entries
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        themes.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { theme ->
                    val scheme = when (theme) {
                        AppTheme.DYNAMIC -> if (isDynamicColorSupported) {
                            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                        } else {
                            staticColorScheme(ThemeChoice.Brand, dark)
                        }
                        AppTheme.BRAND_PINK -> staticColorScheme(ThemeChoice.Brand, dark)
                        AppTheme.BOOK -> staticColorScheme(ThemeChoice.Book, dark)
                        AppTheme.GREY_BLUE -> staticColorScheme(ThemeChoice.Dusty, dark)
                    }
                    PaletteCard(
                        label = theme.label(),
                        scheme = scheme,
                        selected = theme == selected,
                        onClick = { onSelect(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun PaletteCard(
    label: String,
    scheme: ColorScheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "paletteBorder",
    )
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(scheme.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer).forEach {
                            Box(Modifier.weight(1f).height(26.dp).background(it, RoundedCornerShape(8.dp)))
                        }
                    }
                    Box(Modifier.width(52.dp).height(6.dp).background(scheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(14.dp)
                        .background(scheme.primary, CircleShape),
                )
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                RadioButton(selected = selected, onClick = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}

