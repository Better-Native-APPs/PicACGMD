package app.picacgmd.feature.downloads

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.settings.SettingsGroup
import app.picacgmd.feature.settings.SettingsNavRow
import app.picacgmd.feature.settings.SettingsPageScaffold
import app.picacgmd.feature.settings.SettingsSwitchRow
import app.picacgmd.feature.settings.SettingsWriter
import app.picacgmd.feature.settings.formatBytes
import app.picacgmd.feature.settings.rememberAppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val DefaultThreads = 4
private const val ThrottleHint = 8
private val QuickThreads = listOf(1, 4, 8, 16)

/**
 * Board 18 下载设置: 1–16 threads (default 4, applies to running work immediately), automation and
 * network rules. Reached from the downloads top bar and from 设置 › 下载.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    val downloadBytes by produceState<Long?>(null) { value = downloadFolderSize(context) }

    SettingsPageScaffold("下载设置", onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            ThreadCountCard(
                threads = settings.downloadThreads,
                onCommit = { value -> writer.update { setDownloadThreads(value) } },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            SettingsGroup("网络") {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "仅 Wi-Fi 下载", settings.downloadWifiOnly, { writer.update { setDownloadWifiOnly(it) } },
                        summary = "手动下载也需连接 Wi-Fi",
                    )
                }
            }
        }
        item {
            SettingsGroup("自动化") {
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "收藏时自动下载", settings.autoDownloadOnFavourite, { writer.update { setAutoDownloadOnFavourite(it) } },
                        summary = "下载全部章节",
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "自动下载仅限 Wi-Fi", settings.autoDownloadWifiOnly, { writer.update { setAutoDownloadWifiOnly(it) } },
                        enabled = settings.autoDownloadOnFavourite,
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "取消收藏时删除下载", settings.autoDeleteDownloadOnUnfavourite,
                        { writer.update { setAutoDeleteDownloadOnUnfavourite(it) } },
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "下载详情显示评论", settings.showCommentsInDownloads,
                        { writer.update { setShowCommentsInDownloads(it) } },
                        summary = "需要联网加载",
                    )
                }
            }
        }
        item {
            SettingsGroup("位置") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "下载位置", {},
                        summary = "应用专属存储 · 已用 ${downloadBytes?.let(context::formatBytes) ?: "…"}",
                        showChevron = false,
                        leading = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    )
                }
            }
        }
    }
}

internal suspend fun downloadFolderSize(context: Context): Long = withContext(Dispatchers.IO) {
    val root = context.getExternalFilesDir(null)?.let { File(it, "downloads") } ?: return@withContext 0L
    if (!root.exists()) 0L else root.walkBottomUp().filter(File::isFile).sumOf(File::length)
}

/** Big number + 1–16 slider with ticks + quick presets; the warning appears only above 8. */
@Composable
private fun ThreadCountCard(threads: Int, onCommit: (Int) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(threads.toFloat()) }
    val shown = if (dragging) local.roundToInt() else threads
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("同时下载线程数", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "1–16，默认 $DefaultThreads",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        "$shown",
                        style = MaterialTheme.typography.displayMedium,
                        color = colors.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Slider(
                    value = if (dragging) local else threads.toFloat(),
                    onValueChange = {
                        dragging = true
                        local = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onCommit(local.roundToInt().coerceIn(1, 16))
                    },
                    valueRange = 1f..16f,
                    steps = 14,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .semantics {
                            contentDescription = "同时下载线程数"
                            stateDescription = "$shown 个线程"
                        },
                )
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                    listOf(1, 4, 8, 12, 16).forEach { tick ->
                        val fraction = (tick - 1) / 15f
                        Text(
                            if (tick == DefaultThreads) "4 · 默认" else "$tick",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tick == shown) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.align(BiasAlignment(fraction * 2 - 1)),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickThreads.forEach { value ->
                        FilterChip(
                            selected = shown == value,
                            onClick = { onCommit(value) },
                            label = { Text("$value", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = shown > ThrottleHint,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val extended = PikapikaTheme.extendedColors
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = extended.warningContainer, contentColor = extended.onWarningContainer),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Speed, contentDescription = null)
                    Text(
                        "超过 $ThrottleHint 个线程容易被限流",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun BiasAlignment(horizontal: Float): Alignment = androidx.compose.ui.BiasAlignment(horizontal, 0f)
