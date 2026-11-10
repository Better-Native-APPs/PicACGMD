package app.picacgmd.feature.routing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.domain.session.RouteLatencyGrade
import app.picacgmd.core.domain.session.RouteOption
import app.picacgmd.core.domain.session.RouteSource
import app.picacgmd.core.domain.session.fastestReachableRoute
import app.picacgmd.core.domain.session.formatRouteLatency
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

/** Current selection of both route lists, for the settings row, menus and the reader. */
@Immutable
data class RouteSummary(val apiLabel: String, val imageLabel: String) {
    /** 「分流 2 · 图片分流 1」「不分流 · 图片不分流」 */
    val line: String get() = "$apiLabel · 图片$imageLabel"
}

@Composable
fun rememberRouteSummary(): RouteSummary? {
    val routing = LocalAppContainer.current.routingRepository
    val api by routing.apiRoutes.collectAsState(initial = null)
    val image by routing.imageRoutes.collectAsState(initial = null)
    return RouteSummary(api?.selected?.label ?: return null, image?.selected?.label ?: return null)
}

/** 「今天 14:05」「11月2日 09:30」「2025年3月1日」. */
internal fun formatSyncTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val pattern = when {
        then.get(Calendar.YEAR) != today.get(Calendar.YEAR) -> "yyyy年M月d日"
        then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天 HH:mm"
        else -> "M月d日 HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.CHINA).format(Date(millis))
}

private enum class RouteWork { Switching, Syncing }

private enum class RouteTone { Hint, Done, Proxy, Error }

private data class RouteMessage(val text: String, val tone: RouteTone = RouteTone.Done)

/**
 * The one route picker, shared by the home search bar, list app bars, settings, login and the reader.
 * 分流 and 图片分流 sit in the same dialog; every route is probed as soon as it opens, a tap switches
 * at once and the dialog stays open so both lists can be changed in one visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePickerDialog(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val routing = container.routingRepository
    val settings by container.settingsRepository.settings.collectAsState(initial = null)
    val apiState by routing.apiRoutes.collectAsState(initial = null)
    val imageState by routing.imageRoutes.collectAsState(initial = null)
    val api = apiState ?: return
    val image = imageState ?: return
    val proxyOn = settings?.proxyUrl?.isNotBlank() == true
    val usingProxy by rememberUpdatedState(proxyOn)

    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    var work by remember { mutableStateOf<RouteWork?>(null) }
    var message by remember { mutableStateOf<RouteMessage?>(null) }

    // One writer at a time; a failed write is reported in the status line instead of closing.
    val perform: (RouteWork, String, suspend () -> RouteMessage?) -> Unit = { next, failure, block ->
        if (work == null) {
            work = next
            message = null
            scope.launch {
                try {
                    message = block()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    message = RouteMessage(exception.message ?: failure, RouteTone.Error)
                } finally {
                    work = null
                }
            }
        }
    }
    // After a round, an unreachable selection moves to the fastest route that answered.
    val recover: suspend (String, suspend () -> RouteOption<*>?) -> Unit = { name, fallback ->
        if (!usingProxy) {
            try {
                fallback()?.let { message = RouteMessage("当前${name}不可用，已切换至${it.label}") }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                message = RouteMessage(exception.message ?: "自动切换失败，请点按分流重试", RouteTone.Error)
            }
        }
    }
    val apiLatencies = rememberRouteLatencies(api.options, round, routing::measureApiRoute) { results ->
        recover("分流") { routing.fallbackApiRoute(results) }
    }
    val imageLatencies = rememberRouteLatencies(image.options, round, routing::measureImageRoute) { results ->
        recover("图片分流") { routing.fallbackImageRoute(results) }
    }
    val apiMeasured = apiLatencies.size == api.options.size
    val imageMeasured = imageLatencies.size == image.options.size
    val measured = apiMeasured && imageMeasured
    val apiFastest = if (apiMeasured) fastestReachableRoute(api.options, apiLatencies) else null
    val imageFastest = if (imageMeasured) fastestReachableRoute(image.options, imageLatencies) else null
    // Switching is a quick local write, so only a sync visibly disables the controls.
    val syncing = work == RouteWork.Syncing

    val status = message ?: when {
        !measured -> RouteMessage("正在同时测量所有分流…", RouteTone.Hint)
        apiFastest == null && imageFastest == null -> RouteMessage("暂无可用分流，请检查网络或代理后重新测速", RouteTone.Error)
        proxyOn -> RouteMessage("直连测速；当前请求使用代理", RouteTone.Proxy)
        else -> RouteMessage("点按立即切换", RouteTone.Hint)
    }

    BasicAlertDialog(onDismissRequest = onDismiss, properties = secureDialogProperties()) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 280.dp, max = 460.dp),
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("分流", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            status.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (status.tone) {
                                RouteTone.Hint -> MaterialTheme.colorScheme.onSurfaceVariant
                                RouteTone.Done -> latencyColor(RouteLatencyGrade.GOOD)
                                RouteTone.Proxy -> MaterialTheme.colorScheme.tertiary
                                RouteTone.Error -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    IconButton(onClick = { message = null; round++ }, enabled = !syncing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新测速")
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Button(
                        onClick = {
                            perform(RouteWork.Switching, "切换失败") {
                                val changed = buildList {
                                    apiFastest?.takeIf { it != api.selected }?.let {
                                        routing.selectApiRoute(it.value)
                                        add(it.label)
                                    }
                                    imageFastest?.takeIf { it != image.selected }?.let {
                                        routing.selectImageRoute(it.value)
                                        add("图片${it.label}")
                                    }
                                }
                                RouteMessage(if (changed.isEmpty()) "当前已是最快的分流" else "已切换至${changed.joinToString("、")}")
                            }
                        },
                        enabled = measured && (apiFastest != null || imageFastest != null) && !syncing,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    ) {
                        Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("自动选择最快")
                    }
                    RouteSection(
                        title = "分流",
                        caption = api.lastSyncedAtMillis?.let { "上次同步：${formatSyncTime(it)}" } ?: "尚未从官方同步",
                        options = api.options,
                        selected = api.selected,
                        latencies = apiLatencies,
                        fastest = apiFastest,
                        enabled = !syncing,
                        onSelect = { option -> perform(RouteWork.Switching, "切换失败") { routing.selectApiRoute(option.value); null } },
                    ) {
                        FilledTonalButton(
                            onClick = {
                                perform(RouteWork.Syncing, "同步失败") {
                                    routing.syncApiRoutes()
                                    val count = routing.apiRoutes.first().options.count { it.source == RouteSource.SERVER }
                                    RouteMessage("已从官方同步 $count 条分流")
                                }
                            },
                            enabled = !syncing,
                            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                        ) {
                            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                if (syncing) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                                } else {
                                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("从官方同步")
                        }
                    }
                    RouteSection(
                        title = "图片分流",
                        caption = "封面和漫画图片",
                        options = image.options,
                        selected = image.selected,
                        latencies = imageLatencies,
                        fastest = imageFastest,
                        enabled = !syncing,
                        onSelect = { option -> perform(RouteWork.Switching, "切换失败") { routing.selectImageRoute(option.value); null } },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

/**
 * Probes every option at once and returns the results as they arrive; a missing key is still being
 * measured. A new list or [round] starts over. [onMeasured] runs once per completed round.
 */
@Composable
private fun <T> rememberRouteLatencies(
    options: List<RouteOption<T>>,
    round: Int,
    measure: suspend (T) -> Long?,
    onMeasured: suspend (Map<T, Long?>) -> Unit,
): Map<T, Long?> {
    var latencies by remember(options, round) { mutableStateOf<Map<T, Long?>>(emptyMap()) }
    val probe by rememberUpdatedState(measure)
    val done by rememberUpdatedState(onMeasured)
    LaunchedEffect(options, round) {
        coroutineScope {
            options.forEach { option ->
                launch {
                    // Read the map only after the probe resumes: `latencies + (k to probe(k))` would
                    // capture the map from before the suspension and drop every concurrent result.
                    val latency = probe(option.value)
                    latencies = latencies + (option.value to latency)
                }
            }
        }
        done(latencies)
    }
    return latencies
}

/** A titled two-column grid of routes. The order never changes, so a tile cannot move under the finger. */
@Composable
private fun <T> RouteSection(
    title: String,
    caption: String,
    options: List<RouteOption<T>>,
    selected: RouteOption<T>,
    latencies: Map<T, Long?>,
    fastest: RouteOption<T>?,
    enabled: Boolean,
    onSelect: (RouteOption<T>) -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { option ->
                    key(option.value) {
                        RouteTile(
                            label = option.label,
                            outdated = option.source == RouteSource.SAVED,
                            selected = option == selected,
                            enabled = enabled,
                            probing = !latencies.containsKey(option.value),
                            latency = latencies[option.value],
                            fastest = option == fastest,
                            onClick = { onSelect(option) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One route: name and measured latency, never its address. The current route is marked by the radio
 * dot, a primary outline and rounder corners rather than a filled background, so the latency colours
 * read the same on every tile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteTile(
    label: String,
    outdated: Boolean,
    selected: Boolean,
    enabled: Boolean,
    probing: Boolean,
    latency: Long?,
    fastest: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 28.dp else 14.dp, label = "route-corner")
    val outline by animateColorAsState(if (selected) colors.primary else Color.Transparent, label = "route-outline")
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(colors.surface)
            .border(2.dp, outline, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RadioButton(selected = selected, onClick = null)
        }
        // Tags wrap under the latency on narrow tiles or with large fonts instead of being cut off.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            LatencyStatus(probing, latency)
            if (fastest) RouteTag("最快", warn = false)
            // The selected address is no longer in the official or built-in list.
            if (outdated) RouteTag("旧地址", warn = true)
        }
    }
}

@Composable
private fun RouteTag(text: String, warn: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (warn) colors.errorContainer else colors.tertiaryContainer,
        contentColor = if (warn) colors.onErrorContainer else colors.onTertiaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
private fun LatencyStatus(probing: Boolean, latency: Long?) {
    AnimatedContent(
        targetState = if (probing) null else routeLatencyGrade(latency) to formatRouteLatency(latency),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentAlignment = Alignment.CenterStart,
        label = "latency",
    ) { result ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (result == null) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("测速中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val color = latencyColor(result.first)
                SignalBars(result.first, color)
                Text(result.second, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
            }
        }
    }
}

/** App-bar action: one tap opens the picker with both route lists. */
@Composable
fun RouteMenuButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) { Icon(Icons.Outlined.Route, contentDescription = "切换分流") }
    if (open) RoutePickerDialog { open = false }
}

/** The route entry for an existing overflow menu; the caller shows [RoutePickerDialog] on click. */
@Composable
fun RouteMenuItem(onClick: () -> Unit) {
    val summary = rememberRouteSummary()
    DropdownMenuItem(
        text = { Text("切换分流") },
        onClick = onClick,
        leadingIcon = { Icon(Icons.Outlined.Route, contentDescription = null) },
        trailingIcon = {
            Text(summary?.apiLabel.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        },
    )
}
