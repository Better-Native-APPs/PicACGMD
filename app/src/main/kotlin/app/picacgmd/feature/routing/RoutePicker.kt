package app.picacgmd.feature.routing

import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.designsystem.window.securePopupProperties

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.core.domain.session.RouteOption
import app.picacgmd.core.domain.session.RouteSource
import app.picacgmd.core.domain.session.formatRouteLatency
import app.picacgmd.core.domain.session.fastestReachableRoute
import app.picacgmd.core.domain.session.routeLatencyGrade
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The two independent route lists: API requests and image downloads. */
enum class RouteKind(val title: String) { Api("分流"), Image("图片分流") }

/** Current selection of both lists, for menus, the reader and the settings rows. */
@Immutable
data class RouteSummary(
    val apiLabel: String,
    val apiAddress: String?,
    val imageLabel: String,
    val imageAddress: String?,
    val lastSyncedAtMillis: Long?,
)

@Composable
fun rememberRouteSummary(): RouteSummary? {
    val routing = LocalAppContainer.current.routingRepository
    val api by routing.apiRoutes.collectAsState(initial = null)
    val image by routing.imageRoutes.collectAsState(initial = null)
    val a = api ?: return null
    val i = image ?: return null
    return RouteSummary(a.selected.label, a.selected.address, i.selected.label, i.selected.address, a.lastSyncedAtMillis)
}

/** 「今天 14:05」「11月2日 09:30」「2025年3月1日」. */
fun formatSyncTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val pattern = when {
        then.get(Calendar.YEAR) != today.get(Calendar.YEAR) -> "yyyy年M月d日"
        then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天 HH:mm"
        else -> "M月d日 HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.CHINA).format(Date(millis))
}

/**
 * The route picker shared by settings, login, list app bars and the reader.
 * Every row probes its own route as soon as the dialog opens; a tap switches and closes.
 */
@Composable
fun RoutePickerDialog(kind: RouteKind, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val routing = container.routingRepository
    val settings by container.settingsRepository.settings.collectAsState(initial = null)
    var custom by remember { mutableStateOf(false) }
    var sync by remember { mutableStateOf(false) }
    when (kind) {
        RouteKind.Api -> {
            val state by routing.apiRoutes.collectAsState(initial = null)
            val current = state ?: return
            RoutePickerContent(
                title = kind.title,
                options = current.options,
                selected = current.selected,
                proxyOn = settings?.proxyUrl?.isNotBlank() == true,
                synced = current.lastSyncedAtMillis != null,
                measure = routing::measureApiRoute,
                select = routing::selectApiRoute,
                fallback = routing::fallbackApiRoute,
                onDismiss = onDismiss,
                customSummary = current.customIp.ifBlank { "输入 IP 地址" },
                onCustom = { custom = true },
                syncSummary = current.lastSyncedAtMillis?.let { "上次同步：${formatSyncTime(it)}" } ?: "使用内置分流列表",
                onSync = { sync = true },
            )
            if (custom) CustomRouteDialog(kind, current.customIp, onDismiss = { custom = false }, onSaved = onDismiss)
            if (sync) RouteSyncDialog(onDismiss = { sync = false })
        }
        RouteKind.Image -> {
            val state by routing.imageRoutes.collectAsState(initial = null)
            val current = state ?: return
            RoutePickerContent(
                title = kind.title,
                options = current.options,
                selected = current.selected,
                proxyOn = settings?.proxyUrl?.isNotBlank() == true,
                synced = false,
                measure = routing::measureImageRoute,
                select = routing::selectImageRoute,
                fallback = routing::fallbackImageRoute,
                onDismiss = onDismiss,
                customSummary = current.customHost.ifBlank { "输入图片服务器地址" },
                onCustom = { custom = true },
            )
            if (custom) CustomRouteDialog(kind, current.customHost, onDismiss = { custom = false }, onSaved = onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RoutePickerContent(
    title: String,
    options: List<RouteOption<T>>,
    selected: RouteOption<T>,
    proxyOn: Boolean,
    synced: Boolean,
    measure: suspend (T) -> Long?,
    select: suspend (T) -> Unit,
    fallback: suspend (Map<T, Long?>) -> RouteOption<T>?,
    onDismiss: () -> Unit,
    customSummary: String,
    onCustom: () -> Unit,
    syncSummary: String? = null,
    onSync: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var latencies by remember(options, round) { mutableStateOf<Map<T, Long?>>(emptyMap()) }
    val probe by rememberUpdatedState(measure)
    val recover by rememberUpdatedState(fallback)
    val usingProxy by rememberUpdatedState(proxyOn)
    val complete = latencies.size == options.size
    val fastest = fastestReachableRoute(options, latencies)
    val sortedOptions = options.sortedBy { latencies[it.value] ?: Long.MAX_VALUE }
    LaunchedEffect(options, round) {
        notice = null
        error = null
        // Probes belong to the dialog, not to rows that can move while results are sorted.
        coroutineScope {
            options.forEach { option ->
                launch { latencies = latencies + (option.value to probe(option.value)) }
            }
        }
        if (!usingProxy && !busy) {
            busy = true
            try {
                recover(latencies)?.let { notice = "当前分流不可用，已切换至${it.label}" }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: "自动切换失败，请点按分流重试"
            } finally {
                busy = false
            }
        }
    }
    val choose: (RouteOption<T>) -> Unit = choose@{ option ->
        if (busy) return@choose
        if (option == selected) {
            onDismiss()
            return@choose
        }
        busy = true
        error = null
        scope.launch {
            try {
                select(option.value)
                onDismiss()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: "切换失败"
                busy = false
            }
        }
    }
    BasicAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, properties = secureDialogProperties()) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 280.dp, max = 460.dp),
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (proxyOn) "直连测速；当前请求使用代理" else "点按立即切换，无需确认",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (proxyOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { round++ }, enabled = !busy) { Icon(Icons.Outlined.Refresh, contentDescription = "重新测速") }
                }
                FilledTonalButton(
                    onClick = { fastest?.let(choose) },
                    enabled = complete && fastest != null && !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("自动选择最快")
                }
                val status = notice ?: when {
                    !complete -> "正在同时测量所有分流…"
                    fastest == null -> "暂无可用分流，请检查网络或代理后重新测速"
                    else -> null
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    sortedOptions.forEach { option ->
                        key(option.value) {
                            RouteRow(
                                option = option,
                                selected = option == selected,
                                synced = synced,
                                enabled = !busy,
                                probing = !latencies.containsKey(option.value),
                                latency = latencies[option.value],
                                fastest = complete && option == fastest,
                            ) { choose(option) }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                FooterAction(Icons.Outlined.Edit, "自定义地址…", customSummary, !busy, onCustom)
                if (onSync != null && syncSummary != null) FooterAction(Icons.Outlined.Sync, "分流同步", syncSummary, !busy, onSync)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun <T> RouteRow(
    option: RouteOption<T>,
    selected: Boolean,
    synced: Boolean,
    enabled: Boolean,
    probing: Boolean,
    latency: Long?,
    fastest: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(horizontal = 12.dp))
        CompositionLocalProvider(LocalContentColor provides if (selected) colors.onSecondaryContainer else colors.onSurface) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                    if (fastest) SourceHint("最快", false)
                    sourceHint(option.source, synced)?.let { (text, warn) -> SourceHint(text, warn) }
                }
                Text(
                    option.address?.removePrefix("https://") ?: "直连哔咔服务器",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onSecondaryContainer.copy(alpha = 0.78f) else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        LatencyStatus(probing, latency)
    }
}

private fun sourceHint(source: RouteSource, synced: Boolean): Pair<String, Boolean>? = when (source) {
    RouteSource.SERVER -> "服务器同步" to false
    // A saved address that the latest sync no longer lists has gone stale; otherwise it is just kept.
    RouteSource.SAVED -> if (synced) "已失效" to true else "已保存" to false
    else -> null
}

@Composable
private fun SourceHint(text: String, warn: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (warn) colors.errorContainer else colors.tertiaryContainer,
        contentColor = if (warn) colors.onErrorContainer else colors.onTertiaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
private fun LatencyStatus(probing: Boolean, latency: Long?) {
    AnimatedContent(
        targetState = if (probing) null else routeLatencyGrade(latency) to formatRouteLatency(latency),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentAlignment = Alignment.CenterEnd,
        label = "latency",
    ) { result ->
        Row(
            Modifier.widthIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            if (result == null) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("测速中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val color = latencyColor(result.first)
                SignalBars(result.first, color)
                Text(result.second, style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
    }
}

@Composable
private fun FooterAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Input for 自定义; repository validation messages show inline under the field. Saving also selects it. */
@Composable
fun CustomRouteDialog(kind: RouteKind, initial: String, onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val routing = LocalAppContainer.current.routingRepository
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val api = kind == RouteKind.Api
    val save = {
        if (value.isBlank()) {
            error = if (api) "请输入 IP 地址" else "请输入图片服务器地址"
        } else if (!busy) {
            busy = true
            scope.launch {
                try {
                    if (api) routing.setCustomApiRoute(value.trim()) else routing.setCustomImageHost(value.trim())
                    onDismiss()
                    onSaved()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    error = exception.message ?: "保存失败"
                    busy = false
                }
            }
        }
    }
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (api) "自定义分流" else "自定义图片分流") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (api) Text(
                    "证书按哔咔域名校验",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text(if (api) "IP 地址" else "图片服务器") },
                    placeholder = { Text(if (api) "例如 104.21.0.1" else "https://") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("保存并使用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

private enum class SyncWork { Sync, Reset }

/** 分流同步: pull the latest list from PicACG's own server, or fall back to the built-in one. */
@Composable
fun RouteSyncDialog(onDismiss: () -> Unit) {
    val routing = LocalAppContainer.current.routingRepository
    val state by routing.apiRoutes.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var work by remember { mutableStateOf<SyncWork?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val run: (SyncWork) -> Unit = { next ->
        if (work == null) {
            work = next
            message = null
            scope.launch {
                message = try {
                    when (next) {
                        SyncWork.Sync -> {
                            routing.syncApiRoutes()
                            val count = routing.apiRoutes.first().options.count { it.source == RouteSource.SERVER }
                            "已获取 $count 条分流地址" to false
                        }
                        SyncWork.Reset -> {
                            routing.resetApiRoutes()
                            "已恢复内置分流列表" to false
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    (exception.message ?: "操作失败") to true
                } finally {
                    work = null
                }
            }
        }
    }
    val syncedAt = state?.lastSyncedAtMillis
    AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = { if (work == null) onDismiss() },
        icon = { Icon(Icons.Outlined.Sync, contentDescription = null) },
        title = { Text("分流同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "同步不切换当前分流",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    syncedAt?.let { "上次同步：${formatSyncTime(it)}" } ?: "使用内置分流列表",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { run(SyncWork.Sync) }, enabled = work == null, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (work == SyncWork.Sync) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Sync, contentDescription = null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (work == SyncWork.Sync) "正在获取…" else "获取分流地址")
                }
                OutlinedButton(
                    onClick = { run(SyncWork.Reset) },
                    enabled = work == null && syncedAt != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Restore, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("恢复内置分流")
                }
                message?.let { (text, failed) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed) MaterialTheme.colorScheme.error else latencyColor(app.picacgmd.core.domain.session.RouteLatencyGrade.GOOD),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = work == null) { Text("完成") } },
    )
}

/** App-bar action: a route icon opening 分流 / 图片分流, each opening the picker. */
@Composable
fun RouteMenuButton(modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<RouteKind?>(null) }
    Box(modifier) {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Route, contentDescription = "切换分流") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = securePopupProperties(focusable = true)) {
            RouteMenuItems { menu = false; picker = it }
        }
    }
    picker?.let { RoutePickerDialog(it) { picker = null } }
}

/** The two route entries, for embedding in an existing overflow menu. */
@Composable
fun RouteMenuItems(onPick: (RouteKind) -> Unit) {
    val summary = rememberRouteSummary()
    RouteKind.entries.forEach { kind ->
        DropdownMenuItem(
            text = { Text(kind.title) },
            onClick = { onPick(kind) },
            leadingIcon = { Icon(if (kind == RouteKind.Api) Icons.Outlined.Dns else Icons.Outlined.Image, contentDescription = null) },
            trailingIcon = {
                Text(
                    (if (kind == RouteKind.Api) summary?.apiLabel else summary?.imageLabel).orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}
