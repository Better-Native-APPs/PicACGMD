package app.picacgmd.feature.routing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppFilledTonalButton
import app.picacgmd.core.designsystem.component.AppIconButton
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.core.domain.session.RouteLatencyGrade
import app.picacgmd.core.domain.session.RouteOption
import app.picacgmd.core.domain.session.RouteSource
import app.picacgmd.core.domain.session.RoutingRepository
import app.picacgmd.core.domain.session.fastestReachableRoute
import app.picacgmd.core.domain.session.formatRouteLatency
import app.picacgmd.core.domain.session.routeLatencyGrade
import app.picacgmd.core.network.ApiRoute
import app.picacgmd.core.network.ImageServer
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val api by routing.apiRoutes.collectAsStateWithLifecycle(initialValue = null)
    val image by routing.imageRoutes.collectAsStateWithLifecycle(initialValue = null)
    return RouteSummary(api?.selected?.label ?: return null, image?.selected?.label ?: return null)
}

/** 「今天 14:05」「11月2日 09:30」「2025年3月1日」. */
internal fun formatSyncTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone)
    val pattern = when {
        then.year != today.year -> "yyyy年M月d日"
        then.dayOfYear == today.dayOfYear -> "今天 HH:mm"
        else -> "M月d日 HH:mm"
    }
    return DateTimeFormatter.ofPattern(pattern, Locale.CHINA).format(then)
}

private enum class RouteWork { Switching, Syncing }

private enum class RouteTone { Hint, Done, Error }

private data class RouteMessage(val text: String, val tone: RouteTone = RouteTone.Done)

/**
 * The one route picker, shared by the home search bar, list app bars, settings, login and the reader.
 * 分流 and 图片分流 sit in the same dialog; every route is probed as soon as it opens. A tap on a route
 * switches at once and the dialog stays open so both lists can be changed in one visit; 「自动选择最快」
 * picks for both lists and closes the dialog.
 */
@Composable
fun RoutePickerDialog(onDismiss: () -> Unit) {
    val routing = LocalAppContainer.current.routingRepository
    RoutePickerDialog(routing, routing::measureApiRoute, routing::measureImageRoute, onDismiss)
}

/** The picker with its two probes passed in, so it can be shown without touching the network. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RoutePickerDialog(
    routing: RoutingRepository,
    measureApiRoute: suspend (ApiRoute) -> Long?,
    measureImageRoute: suspend (ImageServer) -> Long?,
    onDismiss: () -> Unit,
) {
    val apiState by routing.apiRoutes.collectAsStateWithLifecycle(initialValue = null)
    val imageState by routing.imageRoutes.collectAsStateWithLifecycle(initialValue = null)
    val api = apiState ?: return
    val image = imageState ?: return

    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
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
        try {
            fallback()?.let { message = RouteMessage("当前${name}不可用，已切换至${it.label}") }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            message = RouteMessage(exception.message ?: "自动切换失败，请点按分流重试", RouteTone.Error)
        }
    }
    val apiLatencies = rememberRouteLatencies(api.options, round, measureApiRoute) { results ->
        recover("分流") { routing.fallbackApiRoute(results) }
    }
    val imageLatencies = rememberRouteLatencies(image.options, round, measureImageRoute) { results ->
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
        !measured -> RouteMessage("正在测速…", RouteTone.Hint)
        apiFastest == null && imageFastest == null -> RouteMessage("暂无可用分流，请检查网络后重新测速", RouteTone.Error)
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
                                RouteTone.Error -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (syncing) LoadingIndicator(Modifier.size(24.dp))
                    AppIconButton(onClick = { message = null; round++ }, enabled = !syncing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新测速")
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    AppButton(
                        onClick = {
                            perform(RouteWork.Switching, "切换失败") {
                                apiFastest?.takeIf { it != api.selected }?.let { routing.selectApiRoute(it.value) }
                                imageFastest?.takeIf { it != image.selected }?.let { routing.selectImageRoute(it.value) }
                                // The fastest routes are in use now, so there is nothing left to do here.
                                // A failed write throws before this line and keeps the dialog open.
                                dismiss()
                                null
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
                        AppFilledTonalButton(
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
                            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
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
                    AppTextButton(onClick = onDismiss) { Text("完成") }
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

/** A titled list of routes. The order never changes, so a row cannot move under the finger. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        options.forEachIndexed { index, option ->
            key(option.value) {
                RouteTile(
                    label = option.label,
                    outdated = option.source == RouteSource.SAVED,
                    selected = option == selected,
                    enabled = enabled,
                    probing = !latencies.containsKey(option.value),
                    latency = latencies[option.value],
                    fastest = option == fastest,
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = options.size),
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/**
 * One route on a single line: the radio and the name at the start, latency and signal bars at the
 * end, never its address. Built on the library's single-selection [SegmentedListItem], so the row
 * gets radio semantics, the group's corner rhythm and the pressed shape morph for free instead of a
 * hand-rolled tile that rebuilt a shape every animation frame.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RouteTile(
    label: String,
    outdated: Boolean,
    selected: Boolean,
    enabled: Boolean,
    probing: Boolean,
    latency: Long?,
    fastest: Boolean,
    shapes: ListItemShapes,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        modifier = modifier.heightIn(min = RouteRowMinHeight),
        contentPadding = RouteRowPadding,
        leadingContent = { RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics {}) },
        trailingContent = { LatencyStatus(probing, latency) },
    ) {
        // Everything shares the line while it fits. On a narrow dialog or with a large font a tag
        // moves under the name instead of being cut off or squeezing the name into an ellipsis.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (fastest) RouteTag("最快", warn = false)
            // The selected address is no longer in the official or built-in list.
            if (outdated) RouteTag("旧地址", warn = true)
        }
    }
}

/** The smallest comfortable touch target; a row only grows when a tag has to wrap. */
private val RouteRowMinHeight = 48.dp
private val RouteRowPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
private fun RouteTag(text: String, warn: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (warn) colors.errorContainer else colors.tertiaryContainer,
        contentColor = if (warn) colors.onErrorContainer else colors.onTertiaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** What a screen reader says for a result: the bars and the colour are not spoken, so the grade is named. */
internal fun routeLatencyDescription(latencyMillis: Long?): String = when (routeLatencyGrade(latencyMillis)) {
    RouteLatencyGrade.GOOD -> "延迟 $latencyMillis 毫秒，快"
    RouteLatencyGrade.OK -> "延迟 $latencyMillis 毫秒，中等"
    RouteLatencyGrade.SLOW -> "延迟 $latencyMillis 毫秒，慢"
    RouteLatencyGrade.FAILED -> "测速失败"
}

/**
 * The number sits against the bars and both against the row's end, so down the list the bars form
 * one column and the numbers line up on their last digit.
 */
@Composable
private fun LatencyStatus(probing: Boolean, latency: Long?) {
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = if (probing) null else RouteResult(latency),
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        contentAlignment = Alignment.CenterEnd,
        label = "latency",
    ) { result ->
        if (result == null) {
            Text("测速中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        } else {
            val grade = routeLatencyGrade(result.latency)
            val color = latencyColor(grade)
            val spoken = AnnotatedString(routeLatencyDescription(result.latency))
            Row(
                // Spoken as text, next to the name: a content description would replace the row's name.
                Modifier.clearAndSetSemantics { text = spoken },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    formatRouteLatency(result.latency),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = color,
                    maxLines = 1,
                )
                SignalBars(grade, color)
            }
        }
    }
}

/** A finished probe; a null [latency] is a failed one. */
private data class RouteResult(val latency: Long?)

/** App-bar action: one tap opens the picker with both route lists. */
@Composable
fun RouteMenuButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    AppIconButton(onClick = { open = true }, modifier = modifier) { Icon(Icons.Outlined.Route, contentDescription = "切换分流") }
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
