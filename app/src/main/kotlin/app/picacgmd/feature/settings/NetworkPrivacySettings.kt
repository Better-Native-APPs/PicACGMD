package app.picacgmd.feature.settings

import app.picacgmd.core.designsystem.window.secureDialogProperties

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Icon
import app.picacgmd.feature.routing.RoutePickerDialog
import app.picacgmd.feature.routing.rememberRouteSummary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.picacgmd.core.data.settings.ImageQuality
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.reader.rememberReaderNetworkMonitor
import app.picacgmd.reader.rememberReaderNetworkState
import kotlinx.coroutines.launch

private enum class NetworkDialog { Quality, ConnectTimeout, ReadTimeout, TimeOffset }

private val Timeouts = listOf(10, 15, 30, 60)

/**
 * Board 27 网络: the route picker (分流 and 图片分流 together, with the official sync), image quality,
 * timeouts and time offset. There is no proxy setting: requests follow the system proxy.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NetworkSettingsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    val network = rememberReaderNetworkState(rememberReaderNetworkMonitor())
    val routes = rememberRouteSummary()
    var picker by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<NetworkDialog?>(null) }

    SettingsPageScaffold(SettingsPage.Network.title, onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            SettingsGroup("分流") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "分流", { picker = true },
                        summary = routes?.line ?: "分流和图片分流",
                        leading = { Icon(Icons.Outlined.Route, contentDescription = null) },
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "自动同步分流", settings.autoSyncApiRoutes, { writer.update { setAutoSyncApiRoutes(it) } },
                        summary = "打开分流窗口时，距上次从官方同步超过 24 小时就自动同步一次",
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "用 API 加载图片", settings.useApiClientForImages, { writer.update { setUseApiClientForImages(it) } },
                        summary = "图片分流不可用时启用",
                    )
                }
            }
        }
        item {
            SettingsGroup("看图") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "图片质量", { dialog = NetworkDialog.Quality },
                        summary = if (network.dataSaverEnabled) "省流量模式开启中，在线阅读使用低画质" else "用于在线阅读；下载画质在下载设置中选择",
                        value = if (network.dataSaverEnabled) null else settings.imageQuality.label(),
                    )
                }
            }
        }
        item {
            SettingsGroup("连接") {
                row { shapes -> SettingsNavRow(shapes, "连接超时", { dialog = NetworkDialog.ConnectTimeout }, value = "${settings.connectTimeoutSeconds} 秒") }
                row { shapes -> SettingsNavRow(shapes, "读取超时", { dialog = NetworkDialog.ReadTimeout }, summary = null, value = "${settings.readTimeoutSeconds} 秒") }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "时区", { dialog = NetworkDialog.TimeOffset },
                        summary = "时间校验失败时调整",
                        value = if (settings.timeOffsetHours == 0) "跟随系统" else offsetLabel(settings.timeOffsetHours),
                    )
                }
            }
        }
    }

    val dismiss = { dialog = null }
    when (dialog) {
        NetworkDialog.Quality -> ChoiceDialog("图片质量", ImageQuality.entries, settings?.imageQuality, { it.label() }, { writer.update { setImageQuality(it) } }, dismiss)
        NetworkDialog.ConnectTimeout -> ChoiceDialog("连接超时", Timeouts, settings?.connectTimeoutSeconds, { "$it 秒" }, { writer.update { setConnectTimeoutSeconds(it) } }, dismiss)
        NetworkDialog.ReadTimeout -> ChoiceDialog("读取超时", Timeouts, settings?.readTimeoutSeconds, { "$it 秒" }, { writer.update { setReadTimeoutSeconds(it) } }, dismiss)
        NetworkDialog.TimeOffset -> ChoiceDialog(
            "时区", (-12..12).toList(), settings?.timeOffsetHours,
            { if (it == 0) "跟随系统" else offsetLabel(it) },
            { writer.update { setTimeOffsetHours(it) } }, dismiss,
            description = "时间校验失败时调整",
        )
        null -> Unit
    }
    if (picker) RoutePickerDialog { picker = false }
}

private fun offsetLabel(hours: Int) = if (hours > 0) "+$hours 小时" else "$hours 小时"

private fun ImageQuality.label() = when (this) {
    ImageQuality.ORIGINAL -> "原图"
    ImageQuality.HIGH -> "高"
    ImageQuality.MEDIUM -> "中"
    ImageQuality.LOW -> "低"
}

@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    validate: (String) -> String? = { null },
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    label = { Text(label) },
                    singleLine = singleLine,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            AppTextButton(onClick = {
                val problem = validate(value)
                if (problem != null) {
                    error = problem
                } else {
                    onSave(value)
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val LockTimeouts = listOf(0, 1, 10, 60, 180, 600, 3_600)

private fun lockTimeoutLabel(seconds: Int) = when (seconds) {
    0 -> "立即"
    1 -> "1 秒"
    10 -> "10 秒"
    60 -> "1 分钟"
    180 -> "3 分钟"
    600 -> "10 分钟"
    else -> "1 小时"
}

/** Board 27 隐私与安全: status card, app lock, screenshot prevention, history recording. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PrivacySettingsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    val context = LocalContext.current
    var timeoutDialog by rememberSaveable { mutableStateOf(false) }

    SettingsPageScaffold(SettingsPage.Privacy.title, onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            val colors = MaterialTheme.colorScheme
            val on = settings.appLockEnabled
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = if (on) colors.primaryContainer else colors.surfaceContainerHigh,
                    contentColor = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant,
                ),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ShapeBadge(
                        icon = if (on) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        shape = MaterialShapes.Cookie9Sided,
                        container = if (on) colors.primary else colors.surfaceContainerHighest,
                        content = if (on) colors.onPrimary else colors.onSurfaceVariant,
                        size = 52,
                    )
                    Column {
                        Text(if (on) "应用锁已开启" else "应用锁未开启", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (on) "打开应用时验证，离开${lockTimeoutLabel(settings.appLockTimeoutSeconds).let { if (it == "立即") "后立即" else " $it 后" }}返回需验证" else "启用后打开应用及返回时需验证",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup("应用锁") {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "打开应用时验证身份", settings.appLockEnabled,
                        onCheckedChange = { enable ->
                            val keyguard = context.getSystemService(KeyguardManager::class.java)
                            if (enable && keyguard?.isDeviceSecure != true) {
                                scope.launch { snackbar.showSnackbar("请先设置系统锁屏密码") }
                            } else {
                                writer.update { setAppLockEnabled(enable) }
                            }
                        },
                        summary = "指纹、面容或锁屏密码",
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "允许生物识别解锁", settings.biometricUnlockEnabled, { writer.update { setBiometricUnlockEnabled(it) } },
                        summary = "关闭后仅用锁屏密码", enabled = settings.appLockEnabled,
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "离开后重新验证", { timeoutDialog = true },
                        value = lockTimeoutLabel(settings.appLockTimeoutSeconds), enabled = settings.appLockEnabled,
                    )
                }
            }
        }
        item {
            SettingsGroup("屏幕") {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "防止截屏", settings.preventScreenshots, { writer.update { setPreventScreenshots(it) } },
                        summary = "同时隐藏最近任务画面",
                    )
                }
            }
        }
        item {
            SettingsGroup("记录") {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "保存浏览记录", settings.historyEnabled, { writer.update { setHistoryEnabled(it) } },
                        summary = "关闭后停止记录，保留已有记录",
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "记录详情页浏览", settings.recordDetailViews, { writer.update { setRecordDetailViews(it) } },
                        summary = null, enabled = settings.historyEnabled,
                    )
                }
            }
        }
    }

    if (timeoutDialog) {
        ChoiceDialog(
            "离开后重新验证", LockTimeouts, settings?.appLockTimeoutSeconds, ::lockTimeoutLabel,
            { writer.update { setAppLockTimeoutSeconds(it) } }, { timeoutDialog = false },
            detail = { if (it == 0) "每次切回应用都验证" else null },
        )
    }
}
