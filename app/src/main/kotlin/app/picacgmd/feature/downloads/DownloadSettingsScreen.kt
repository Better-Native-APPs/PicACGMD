package app.picacgmd.feature.downloads

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.picacgmd.PikapikaApplication
import app.picacgmd.core.designsystem.window.secureModalBottomSheetProperties
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

private const val ThrottleHint = 8
private val QuickThreads = listOf(1, 4, 8, 16)

/**
 * Board 18 下载设置: 1–16 threads (device-dependent default, applies to running work immediately), automation and
 * network rules. Reached from the downloads top bar and from 设置 › 下载.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    val manager = rememberDownloadManager()
    val downloads by remember(manager) { manager.observeQueue() }.collectAsStateWithLifecycle(initialValue = null)
    val treeUri = settings?.downloadTreeUri
    val downloadBytes = downloads?.filter {
        if (treeUri == null) it.storageRoot?.startsWith("content://") != true else it.storageRoot == treeUri
    }?.sumOf { it.downloadedBytes }
    var choosingLocation by remember { mutableStateOf(false) }
    val folderName by produceState<String?>(null, treeUri) {
        value = treeUri?.let { selected -> withContext(Dispatchers.IO) {
            try {
                val tree = Uri.parse(selected)
                val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else "目录不可用" } ?: "目录不可用"
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) { "目录不可用" }
        } }
    }

    fun changeLocation(tree: String?) {
        scope.launch {
            try {
                manager.setDownloadLocation(tree)
                choosingLocation = false
                snackbar.showSnackbar("下载位置已更改，已有下载保持原位置")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                snackbar.showSnackbar("更改下载位置失败：${exception.message ?: "请重试"}")
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val tree = data?.data
            val resolver = context.applicationContext.contentResolver
            // Persisting the grant and switching the location run in the application scope so they still
            // complete if this screen leaves composition (e.g. the app lock takes over); only the snackbar
            // depends on the screen staying around.
            val applied = (context.applicationContext as PikapikaApplication).applicationScope.async {
                if (data == null || tree == null) throw IOException("未获取到下载目录，请重试")
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                    throw IOException("请选择允许读写的下载目录")
                }
                resolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                manager.setDownloadLocation(tree.toString())
            }
            scope.launch {
                try {
                    applied.await()
                    snackbar.showSnackbar("下载位置已更改，已有下载保持原位置")
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    snackbar.showSnackbar("更改下载位置失败：${exception.message ?: "请重新选择"}")
                }
            }
        }
    }
    DownloadStorageNotice(manager, snackbar)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            try { manager.validateDownloadLocation() } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) { snackbar.showSnackbar("检查下载位置失败：${exception.message ?: "请重试"}") }
        }
    }

    SettingsPageScaffold("下载设置", onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            ThreadCountCard(
                threads = settings.downloadThreads,
                defaultThreads = container.defaultDownloadConcurrency,
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
                        shapes, "下载位置", { choosingLocation = true },
                        summary = "${if (treeUri == null) "应用内部存储" else folderName ?: "所选文件夹"} · 已下载 ${downloadBytes?.let(context::formatBytes) ?: "…"}",
                        leading = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    )
                }
            }
        }
    }

    if (choosingLocation) ModalBottomSheet(
        onDismissRequest = { choosingLocation = false },
        properties = secureModalBottomSheetProperties(),
    ) {
        Text("下载位置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text("只影响新下载的漫画，已有下载保留原位置。重新选择原目录可恢复失效的授权。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        ListItem(
            onClick = { changeLocation(null) },
            content = { Text("应用内部存储") },
            leadingContent = { RadioButton(selected = treeUri == null, onClick = null) },
        )
        ListItem(
            content = { Text(if (treeUri == null) "选择文件夹" else "更改文件夹") },
            supportingContent = folderName?.let { name -> { Text(name) } },
            leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
            onClick = {
                choosingLocation = false
                picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    treeUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
                })
            },
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Big number + 1–16 slider with ticks + quick presets; the warning appears only above 8. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThreadCountCard(threads: Int, defaultThreads: Int, onCommit: (Int) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(threads.toFloat()) }
    val shown = if (dragging) local.roundToInt() else threads
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
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
                            "1–16，默认 $defaultThreads",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        "$shown",
                        style = MaterialTheme.typography.displayMediumEmphasized,
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
                    listOf(1, defaultThreads, 8, 12, 16).forEach { tick ->
                        val fraction = (tick - 1) / 15f
                        Text(
                            if (tick == defaultThreads) "$tick · 默认" else "$tick",
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
            enter = expandVertically(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
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
