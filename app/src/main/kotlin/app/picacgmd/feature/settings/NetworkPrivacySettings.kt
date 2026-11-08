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
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import app.picacgmd.core.domain.session.formatRouteLatency
import app.picacgmd.core.domain.session.apiRouteState
import app.picacgmd.core.domain.session.RouteSource
import app.picacgmd.core.network.ApiRoute
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
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
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.launch
import java.net.URI

private enum class NetworkDialog { ApiCustom, ImageCustom, Proxy, Quality, ConnectTimeout, ReadTimeout, TimeOffset }

private val Timeouts = listOf(10, 15, 30, 60)

/**
 * Board 27 网络: API / image routing, proxy, image quality,
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
    val apiRoutes = settings?.let(::apiRouteState)
    var dialog by rememberSaveable { mutableStateOf<NetworkDialog?>(null) }
    var latencies by remember { mutableStateOf<Map<ApiRoute, Long?>?>(null) }
    var measuring by remember { mutableStateOf(false) }
    val measure: () -> Unit = {
        if (!measuring) {
            measuring = true
            scope.launch {
                try {
                    latencies = emptyMap()
                    kotlinx.coroutines.coroutineScope {
                        apiRoutes?.options?.forEach { option ->
                            launch {
                                val latency = container.routingRepository.measureApiRoute(option.value)
                                latencies = latencies.orEmpty() + (option.value to latency)
                            }
                        }
                    }
                } catch (exception: kotlinx.coroutines.CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    snackbar.showSnackbar("测速失败：${exception.message ?: "请重试"}")
                } finally {
                    measuring = false
                }
            }
        }
    }

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
            SettingsGroup("API 分流") {
                apiRoutes?.options?.forEach { option ->
                    row { shapes ->
                        RadioRow(
                            shapes = shapes,
                            title = option.label,
                            summary = option.address,
                            latency = run {
                                val route = option.value
                                when {
                                    latencies?.containsKey(route) == true -> formatRouteLatency(latencies?.get(route))
                                    measuring -> "测速中…"
                                    else -> null
                                }
                            },
                            latencyFailed = latencies?.containsKey(option.value) == true && latencies?.get(option.value) == null,
                            selected = apiRoutes.selected == option,
                            enabled = !proxyOn,
                            onClick = {
                                writer.update { container.routingRepository.selectApiRoute(option.value) }
                            },
                            trailing = if (option.source == RouteSource.CUSTOM) {
                                { TextButton(onClick = { dialog = NetworkDialog.ApiCustom }, enabled = !proxyOn) { Text("填写") } }
                            } else {
                                null
                            },
                        )
                    }
                }
                if (settings.customApiIp.isBlank()) row { shapes ->
                    RadioRow(
                        shapes, "自定义", false, { dialog = NetworkDialog.ApiCustom },
                        summary = "填入 IP，例如 104.21.0.1", enabled = !proxyOn,
                    )
                }
            }
        }
        item {
            SettingsGroup(null, Modifier.padding(top = 8.dp)) {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "线路测速", measure,
                        summary = if (proxyOn) {
                            "测速直接连接各条线路，不经过代理；结果只作参考，不会切换线路"
                        } else {
                            "分别测试各条线路的响应时间，超过 5 秒算失败；不会切换线路"
                        },
                        value = if (measuring) "测速中" else if (latencies == null) "开始" else "重新测速",
                        enabled = !measuring,
                        leading = { Icon(Icons.Outlined.Speed, contentDescription = null) },
                    )
                }
            }
        }
        item {
            SettingsGroup("图片分流") {
                RoutingMode.entries.forEach { mode ->
                    row { shapes ->
                        RadioRow(
                            shapes = shapes,
                            title = mode.label(),
                            summary = if (mode == RoutingMode.CUSTOM) {
                                settings.customImageHost.ifBlank { "填入图片服务器地址" }
                            } else {
                                null
                            },
                            selected = settings.imageRoutingMode == mode,
                            enabled = !proxyOn,
                            onClick = {
                                if (mode == RoutingMode.CUSTOM && settings.customImageHost.isBlank()) dialog = NetworkDialog.ImageCustom
                                else writer.update { setImageRouting(mode) }
                            },
                            trailing = if (mode == RoutingMode.CUSTOM) {
                                { TextButton(onClick = { dialog = NetworkDialog.ImageCustom }, enabled = !proxyOn) { Text("填写") } }
                            } else {
                                null
                            },
                        )
                    }
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
                text = "只会连接哔咔服务器、图片服务器，以及你自己设置的代理服务器。分流可从哔咔服务器同步。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }

    val dismiss = { dialog = null }
    when (dialog) {
        NetworkDialog.ApiCustom -> TextInputDialog(
            title = "自定义 API 分流",
            description = "填入 IP 地址，和内置分流一样使用；证书仍按官方域名校验。",
            label = "IP 地址",
            initial = settings?.customApiIp.orEmpty(),
            keyboardType = KeyboardType.Uri,
            validate = { if (it.isBlank()) "请填写 IP 地址" else null },
            onSave = { value -> writer.update { container.routingRepository.setCustomApiRoute(value) } },
            onDismiss = dismiss,
        )
        NetworkDialog.ImageCustom -> TextInputDialog(
            title = "自定义图片分流",
            description = "填入图片服务器地址，例如 https://example.com。",
            label = "图片服务器",
            initial = settings?.customImageHost.orEmpty(),
            keyboardType = KeyboardType.Uri,
            validate = { if (it.isBlank()) "请填写地址" else null },
            onSave = { value -> writer.update { container.routingRepository.setCustomImageHost(value) } },
            onDismiss = dismiss,
        )
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
}

private fun offsetLabel(hours: Int) = if (hours > 0) "+$hours 小时" else "$hours 小时"

private fun RoutingMode.label() = when (this) {
    RoutingMode.NONE -> "不分流"
    RoutingMode.ROUTE_1 -> "分流 1"
    RoutingMode.ROUTE_2 -> "分流 2"
    RoutingMode.ROUTE_3 -> "分流 3"
    RoutingMode.CUSTOM -> "自定义"
}

private fun ImageQuality.label() = when (this) {
    ImageQuality.ORIGINAL -> "原图"
    ImageQuality.HIGH -> "高"
    ImageQuality.MEDIUM -> "中"
    ImageQuality.LOW -> "低"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RadioRow(
    shapes: ListItemShapes,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    latency: String? = null,
    latencyFailed: Boolean = false,
) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = trailing ?: latency?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (latencyFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) { Text(title) }
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
