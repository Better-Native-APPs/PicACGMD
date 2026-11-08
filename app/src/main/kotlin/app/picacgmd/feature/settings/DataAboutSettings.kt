package app.picacgmd.feature.settings

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import app.picacgmd.core.data.db.PikapikaDatabase
import app.picacgmd.core.data.settings.CacheCleanupInterval
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Bytes per storage bucket for the 28 storage card; downloads are never cleaned from here. */
internal data class StorageUsage(
    val downloads: Long,
    val imageCache: Long,
    val otherCache: Long,
    val database: Long,
    val available: Long,
) {
    val total: Long get() = downloads + imageCache + otherCache + database
}

internal suspend fun measureStorage(context: Context, imageLoader: ImageLoader): StorageUsage = withContext(Dispatchers.IO) {
    val external = context.getExternalFilesDir(null)
    val downloads = external?.let { File(it, "downloads").treeSize() } ?: 0L
    val imageCache = runCatching { imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L)
    val cacheTotal = context.cacheDir.treeSize() + (context.externalCacheDir?.treeSize() ?: 0L)
    val databasePath = context.getDatabasePath(PikapikaDatabase.DATABASE_NAME)
    val database = listOf("", "-wal", "-shm", "-journal").sumOf { File(databasePath.path + it).takeIf(File::exists)?.length() ?: 0L }
    val available = runCatching { StatFs((external ?: context.filesDir).path).availableBytes }.getOrDefault(0L)
    StorageUsage(downloads, imageCache, (cacheTotal - imageCache).coerceAtLeast(0), database, available)
}

private fun File.treeSize(): Long = if (!exists()) 0L else walkBottomUp().filter(File::isFile).sumOf(File::length)

internal fun Context.formatBytes(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

/** Board 28 数据与存储 (backup / import / export removed per SPEC §0; WebDAV comes later). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DataSettingsPage(onBack: () -> Unit, onOpenDownloads: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    var refresh by remember { mutableIntStateOf(0) }
    val usage by produceState<StorageUsage?>(null, refresh) { value = measureStorage(context, container.imageLoader) }
    var cleaning by rememberSaveable { mutableStateOf(false) }
    var intervalDialog by rememberSaveable { mutableStateOf(false) }

    SettingsPageScaffold(SettingsPage.Data.title, onBack, snackbar, modifier) {
        item {
            StorageCard(
                usage = usage,
                onClean = { cleaning = true },
                onManageDownloads = onOpenDownloads,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (settings != null) {
            item {
                SettingsGroup("缓存") {
                    row { shapes ->
                        SettingsNavRow(
                            shapes, "自动清理缓存", { intervalDialog = true },
                            summary = "仅清理图片和接口缓存",
                            value = settings.autoCleanCacheInterval.label(),
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup("同步") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "WebDAV 云同步", {},
                        summary = "同步浏览记录",
                        value = "稍后提供",
                        enabled = false,
                        leading = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
                    )
                }
            }
        }
    }

    if (cleaning) {
        CleanCacheSheet(
            usage = usage,
            onDismiss = { cleaning = false },
            onClean = { images, api, history ->
                scope.launch {
                    val message = try {
                        withContext(Dispatchers.IO) {
                            if (images) {
                                container.imageLoader.memoryCache?.clear()
                                container.imageLoader.diskCache?.clear()
                            }
                            if (api) container.database.apiCacheDao().clear()
                            if (history) container.historyRepository.clear()
                        }
                        "已清理"
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        "清理失败：${exception.message ?: "请重试"}"
                    }
                    refresh++
                    snackbar.showSnackbar(message)
                }
            },
        )
    }
    if (intervalDialog) {
        ChoiceDialog(
            "自动清理缓存", CacheCleanupInterval.entries, settings?.autoCleanCacheInterval, { it.label() },
            { writer.update { setAutoCleanCacheInterval(it) } }, { intervalDialog = false },
        )
    }
}

private fun CacheCleanupInterval.label() = when (this) {
    CacheCleanupInterval.NEVER -> "不自动清理"
    CacheCleanupInterval.DAY -> "每天"
    CacheCleanupInterval.WEEK -> "每周"
    CacheCleanupInterval.MONTH -> "每月"
}

/** Total in display type, a segmented usage bar and a legend; actions live on the card (board 28). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun StorageCard(
    usage: StorageUsage?,
    onClean: () -> Unit,
    onManageDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val buckets = listOf(
        Triple("下载", usage?.downloads ?: 0L, colors.primary),
        Triple("图片缓存", usage?.imageCache ?: 0L, colors.tertiary),
        Triple("其他缓存", usage?.otherCache ?: 0L, colors.secondary),
        Triple("数据库", usage?.database ?: 0L, colors.outline),
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (usage == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                return@Column
            }
            val total = context.formatBytes(usage.total)
            val number = total.takeWhile { it.isDigit() || it == '.' || it == ',' }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(number, style = MaterialTheme.typography.displaySmall, color = colors.onSurface)
                Text(
                    "${total.removePrefix(number).trim()} 本应用占用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp).weight(1f),
                )
                Text(
                    "可用 ${context.formatBytes(usage.available)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val sum = usage.total.coerceAtLeast(1)
                buckets.forEach { (_, bytes, color) ->
                    val fraction by animateFloatAsState(
                        (bytes.toFloat() / sum).coerceAtLeast(if (bytes > 0) 0.02f else 0f),
                        MaterialTheme.motionScheme.slowSpatialSpec(),
                        label = "storageSegment",
                    )
                    if (fraction > 0f) {
                        Box(
                            Modifier
                                .weight(fraction)
                                .height(12.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
                if (usage.total == 0L) {
                    Box(Modifier.weight(1f).height(12.dp).clip(CircleShape).background(colors.surfaceContainerHighest))
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                buckets.forEach { (label, bytes, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Text(
                            "$label ${context.formatBytes(bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onClean) {
                    Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("清理缓存")
                }
                TextButton(onClick = onManageDownloads) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("管理下载")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanCacheSheet(
    usage: StorageUsage?,
    onDismiss: () -> Unit,
    onClean: (images: Boolean, api: Boolean, history: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var images by rememberSaveable { mutableStateOf(true) }
    var api by rememberSaveable { mutableStateOf(true) }
    var history by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("清理缓存？", style = MaterialTheme.typography.headlineSmall)
            Text(
                "已下载的漫画会保留",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            CheckRow("图片缓存", "已浏览的封面和图片", usage?.imageCache?.let(context::formatBytes), images) { images = it }
            CheckRow("接口缓存", "列表临时数据", null, api) { api = it }
            CheckRow("浏览记录和阅读进度", "清除后无法恢复", null, history) { history = it }
            Button(
                onClick = {
                    onClean(images, api, history)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
                enabled = images || api || history,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 48.dp),
            ) { Text("清理") }
        }
    }
}

@Composable
private fun CheckRow(title: String, summary: String, trailing: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge)
    }
}

private enum class AboutDialog { Licenses, Privacy, Usage }

private val Libraries = listOf(
    "Kotlin 标准库与协程" to "Apache License 2.0",
    "kotlinx.serialization" to "Apache License 2.0",
    "Jetpack Compose 与 Material 3" to "Apache License 2.0",
    "AndroidX（Activity、Lifecycle、Navigation、Room、DataStore、WorkManager 等）" to "Apache License 2.0",
    "OkHttp / Okio" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
)

/** Board 28b 关于: local information only — no external links and no update check (SPEC §0). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AboutSettingsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by rememberSaveable { mutableStateOf<AboutDialog?>(null) }
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val icon = remember(context) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    SettingsPageScaffold(SettingsPage.About.title, onBack, snackbar, modifier) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()))
                }
                Spacer(Modifier.height(16.dp))
                Text("PicACG", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "版本 ${version?.versionName ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsGroup(null) {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "版本", {}, value = version?.versionName ?: "—", showChevron = false,
                        leading = { Icon(Icons.Outlined.Tag, contentDescription = null) },
                    )
                }
                row { shapes ->
                    SettingsNavRow(shapes, "开源许可", { dialog = AboutDialog.Licenses }, leading = { Icon(Icons.Outlined.Gavel, contentDescription = null) })
                }
                row { shapes ->
                    SettingsNavRow(shapes, "隐私说明", { dialog = AboutDialog.Privacy }, leading = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) })
                }
                row { shapes ->
                    SettingsNavRow(shapes, "使用说明", { dialog = AboutDialog.Usage }, leading = { Icon(Icons.Outlined.Description, contentDescription = null) })
                }
            }
        }
        item {
            Text(
                "不检查更新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
    when (dialog) {
        AboutDialog.Licenses -> InfoDialog("开源许可", onDismiss = { dialog = null }) {
            Libraries.forEach { (name, license) ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(license, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AboutDialog.Privacy -> InfoDialog("隐私说明", onDismiss = { dialog = null }) {
            Text(
                "不收集、不上传使用数据，无统计和广告。\n\n" +
                    "仅连接哔咔接口、图片服务器和已设置的代理；账号密码经系统密钥库加密，仅保存在本机。\n\n" +
                    "浏览记录、收藏、下载和设置仅保存在本机，卸载后会一并删除。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        AboutDialog.Usage -> InfoDialog("使用说明", onDismiss = { dialog = null }) {
            Text(
                "第三方客户端，仅供个人学习使用，与哔咔官方无关。\n\n" +
                    "内容来自哔咔服务器，版权归原作者所有；请遵守当地法律法规，勿传播下载内容。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        null -> Unit
    }
}

@Composable
private fun InfoDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
