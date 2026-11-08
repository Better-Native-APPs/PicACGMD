package app.picacgmd.feature.settings

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import app.picacgmd.feature.routing.RouteKind
import app.picacgmd.feature.routing.RoutePickerDialog
import app.picacgmd.feature.routing.RouteSyncDialog
import app.picacgmd.feature.routing.formatSyncTime
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
import androidx.compose.material3.TextButton
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
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.launch
import java.net.URI

private enum class NetworkDialog { Proxy, Quality, ConnectTimeout, ReadTimeout, TimeOffset }

private val Timeouts = listOf(10, 15, 30, 60)

/**
 * Board 27 网络: route pickers (分流 / 图片分流 / 分流同步), proxy, image quality,
 * timeouts and time offset. A configured proxy carries every request, so routing no longer applies.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NetworkSettingsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    val routes = rememberRouteSummary()
    var picker by rememberSaveable { mutableStateOf<RouteKind?>(null) }
    var syncing by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<NetworkDialog?>(null) }

    SettingsPageScaffold(SettingsPage.Network.title, onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        val proxyOn = settings.proxyUrl.isNotBlank()
        if (proxyOn) {
            item {
                NoticeCard(
                    text = "已设置代理：所有请求都经过代理连接，下面的分流设置暂时不生效。清除代理后恢复。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    warning = true,
                )
            }
        }
        item {
            SettingsGroup("分流") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "分流", { picker = RouteKind.Api },
                        summary = routes?.apiAddress ?: "直接连接官方服务器",
                        value = routes?.apiLabel,
                        leading = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "图片分流", { picker = RouteKind.Image },
                        summary = routes?.imageAddress?.removePrefix("https://"),
                        value = routes?.imageLabel,
                        leading = { Icon(Icons.Outlined.Image, contentDescription = null) },
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "分流同步", { syncing = true },
                        summary = routes?.lastSyncedAtMillis?.let { "上次同步：${formatSyncTime(it)}" }
                            ?: "正在使用内置分流列表，可从哔咔服务器获取最新地址",
                        leading = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "用 API 加载图片", settings.useApiClientForImages, { writer.update { setUseApiClientForImages(it) } },
                        summary = "图片分流不通时打开，速度较慢",
                    )
                }
            }
        }
        item {
            SettingsGroup("代理服务器") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "代理地址", { dialog = NetworkDialog.Proxy },
                        summary = settings.proxyUrl.ifBlank { "例如 socks5://127.0.0.1:1080" },
                        value = if (proxyOn) "已设置" else "未设置",
                    )
                }
            }
        }
        item {
            SettingsGroup("看图") {
                row { shapes ->
                    SettingsNavRow(shapes, "图片质量", { dialog = NetworkDialog.Quality }, summary = "在线看图时的清晰度；下载总是原图", value = settings.imageQuality.label())
                }
            }
        }
        item {
            SettingsGroup("连接") {
                row { shapes -> SettingsNavRow(shapes, "连接超时", { dialog = NetworkDialog.ConnectTimeout }, value = "${settings.connectTimeoutSeconds} 秒") }
                row { shapes -> SettingsNavRow(shapes, "读取超时", { dialog = NetworkDialog.ReadTimeout }, summary = "图片很大时可以调长", value = "${settings.readTimeoutSeconds} 秒") }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "时区", { dialog = NetworkDialog.TimeOffset },
                        summary = "服务器校验时间失败时再调整",
                        value = if (settings.timeOffsetHours == 0) "跟随系统" else offsetLabel(settings.timeOffsetHours),
                    )
                }
            }
        }
        item {
            NoticeCard(
                text = "只会连接哔咔服务器、图片服务器，以及你自己设置的代理服务器。分流地址内置在应用里，也可以在「分流同步」里从哔咔自己的服务器获取最新的地址。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }

    val dismiss = { dialog = null }
    when (dialog) {
        NetworkDialog.Proxy -> ProxyDialog(
            initial = settings?.proxyUrl.orEmpty(),
            onSave = { writer.update { setProxyUrl(it) } },
            onDismiss = dismiss,
        )
        NetworkDialog.Quality -> ChoiceDialog("图片质量", ImageQuality.entries, settings?.imageQuality, { it.label() }, { writer.update { setImageQuality(it) } }, dismiss)
        NetworkDialog.ConnectTimeout -> ChoiceDialog("连接超时", Timeouts, settings?.connectTimeoutSeconds, { "$it 秒" }, { writer.update { setConnectTimeoutSeconds(it) } }, dismiss)
        NetworkDialog.ReadTimeout -> ChoiceDialog("读取超时", Timeouts, settings?.readTimeoutSeconds, { "$it 秒" }, { writer.update { setReadTimeoutSeconds(it) } }, dismiss)
        NetworkDialog.TimeOffset -> ChoiceDialog(
            "时区", (-12..12).toList(), settings?.timeOffsetHours,
            { if (it == 0) "跟随系统" else offsetLabel(it) },
            { writer.update { setTimeOffsetHours(it) } }, dismiss,
            description = "把请求时间按小时偏移，只在服务器提示时间不对时使用。",
        )
        null -> Unit
    }
    picker?.let { RoutePickerDialog(it) { picker = null } }
    if (syncing) RouteSyncDialog(onDismiss = { syncing = false })
}

private fun offsetLabel(hours: Int) = if (hours > 0) "+$hours 小时" else "$hours 小时"

private fun ImageQuality.label() = when (this) {
    ImageQuality.ORIGINAL -> "原图"
    ImageQuality.HIGH -> "高"
    ImageQuality.MEDIUM -> "中"
    ImageQuality.LOW -> "低"
}

@Composable
internal fun NoticeCard(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (warning) colors.tertiaryContainer else colors.surfaceContainerHigh,
            contentColor = if (warning) colors.onTertiaryContainer else colors.onSurfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Icon(Icons.Outlined.Info, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
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
            TextButton(onClick = {
                val problem = validate(value)
                if (problem != null) {
                    error = problem
                } else {
                    onSave(value)
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private enum class ProxyType(val label: String, val scheme: String?) { None("不使用", null), Http("HTTP", "http"), Socks("SOCKS5", "socks5") }

/** Type ButtonGroup + host + port; stored as `scheme://host:port`, empty when off (board 27). */
@Composable
private fun ProxyDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val parsed = remember(initial) { runCatching { URI(initial.trim()) }.getOrNull() }
    var type by remember {
        mutableStateOf(
            when (parsed?.scheme?.lowercase()) {
                "http" -> ProxyType.Http
                "socks", "socks5" -> ProxyType.Socks
                else -> ProxyType.None
            },
        )
    }
    var host by remember { mutableStateOf(parsed?.host.orEmpty()) }
    var port by remember { mutableStateOf(parsed?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("代理服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("接口和图片都会走代理。手机开了 VPN 时一般不用设置。", style = MaterialTheme.typography.bodyMedium)
                ConnectedChoice(ProxyType.entries, type, { type = it; error = null }, { it.label })
                if (type != ProxyType.None) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            value = host, onValueChange = { host = it.trim(); error = null }, label = { Text("地址") },
                            singleLine = true, modifier = Modifier.weight(2f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5); error = null }, label = { Text("端口") },
                            singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (type == ProxyType.None) {
                    onSave("")
                    onDismiss()
                    return@TextButton
                }
                val portNumber = port.toIntOrNull()
                when {
                    host.isBlank() -> error = "请填写代理地址"
                    portNumber == null || portNumber !in 1..65535 -> error = "端口需要在 1–65535 之间"
                    else -> {
                        onSave("${type.scheme}://$host:$portNumber")
                        onDismiss()
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
                            if (on) "离开${lockTimeoutLabel(settings.appLockTimeoutSeconds).let { if (it == "立即") "后立即" else " $it 后" }}回来需要验证" else "打开后，回到应用时需要验证身份",
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
                                scope.launch { snackbar.showSnackbar("请先在系统设置里设置锁屏密码") }
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
                        summary = "关闭后只能用锁屏密码", enabled = settings.appLockEnabled,
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "离开多久后重新验证", { timeoutDialog = true },
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
                        summary = "同时在最近任务里隐藏画面",
                    )
                }
            }
        }
        item {
            SettingsGroup("记录") {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "记录浏览记录", settings.historyEnabled, { writer.update { setHistoryEnabled(it) } },
                        summary = "关闭后不再记录新的阅读进度，已有记录保留",
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "打开详情也算浏览", settings.recordDetailViews, { writer.update { setRecordDetailViews(it) } },
                        summary = "不看正文，只打开详情页也写入记录", enabled = settings.historyEnabled,
                    )
                }
            }
        }
        item {
            NoticeCard(
                "应用不收集任何使用数据。浏览记录、收藏和下载只保存在这台设备上。",
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }

    if (timeoutDialog) {
        ChoiceDialog(
            "离开多久后重新验证", LockTimeouts, settings?.appLockTimeoutSeconds, ::lockTimeoutLabel,
            { writer.update { setAppLockTimeoutSeconds(it) } }, { timeoutDialog = false },
            detail = { if (it == 0) "每次切回应用都验证" else null },
        )
    }
}
