package app.picacgmd.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.CategoryShieldMode
import app.picacgmd.core.data.settings.FailedReloadAction
import app.picacgmd.core.data.settings.ListLayout
import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.network.Category
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException

private enum class BrowsingSub { Shield, HomeCategories }
private enum class BrowsingDialog { Layout, ListColumns, CategoryColumns, FailedReload, CopyTemplate }

private val ColumnOptions = listOf(0, 2, 3, 4, 5, 6, 7)
private fun columnLabel(value: Int) = if (value == 0) "自动" else "$value 列"

/** Board 26 列表与浏览: list behaviour, shielding entry points, home categories and feature switches. */
@Composable
internal fun BrowsingSettingsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var sub by rememberSaveable { mutableStateOf<BrowsingSub?>(null) }
    BackHandler(enabled = sub != null) { sub = null }
    when (sub) {
        BrowsingSub.Shield -> ShieldSettingsPage(onBack = { sub = null }, modifier = modifier)
        BrowsingSub.HomeCategories -> HomeCategoriesPage(onBack = { sub = null }, modifier = modifier)
        null -> BrowsingRoot(onBack, onOpen = { sub = it }, modifier)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BrowsingRoot(onBack: () -> Unit, onOpen: (BrowsingSub) -> Unit, modifier: Modifier) {
    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    var dialog by rememberSaveable { mutableStateOf<BrowsingDialog?>(null) }

    SettingsPageScaffold(SettingsPage.Browsing.title, onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            SettingsGroup("列表") {
                row { shapes ->
                    SettingsChoiceRow(
                        shapes = shapes,
                        title = "加载方式",
                        summary = "连续滚动到底自动加载下一页；分页可以直接跳页，没有页数限制",
                        options = PaginationMode.entries,
                        selected = settings.paginationMode,
                        onSelect = { writer.update { setPaginationMode(it) } },
                        label = { if (it == PaginationMode.INFINITE) "连续滚动" else "分页" },
                    )
                }
                row { shapes ->
                    SettingsNavRow(shapes, "列表样式", onClick = { dialog = BrowsingDialog.Layout }, value = settings.listLayout.label())
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "列表列数", onClick = { dialog = BrowsingDialog.ListColumns },
                        summary = "封面样式下每行显示几本", value = columnLabel(settings.listColumnCount),
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "加载失败时", onClick = { dialog = BrowsingDialog.FailedReload },
                        value = if (settings.failedReloadAction == FailedReloadAction.TAP) "点击重试" else "下拉重试",
                    )
                }
            }
        }
        item {
            SettingsGroup("封印") {
                row { shapes ->
                    val count = settings.shieldedCategories.size + settings.hiddenKeywords.size
                    SettingsNavRow(
                        shapes, "封印与隐藏", onClick = { onOpen(BrowsingSub.Shield) },
                        summary = "封印分类、隐藏关键词；列表会显示隐藏了几本",
                        value = if (count == 0) "未设置" else "$count 项",
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "隐藏看过的漫画", settings.hideViewedComics,
                        onCheckedChange = { writer.update { setHideViewedComics(it) } },
                        summary = "浏览记录里有的漫画不再出现在列表里",
                    )
                }
            }
        }
        item {
            SettingsGroup("首页") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "分类排序与隐藏", onClick = { onOpen(BrowsingSub.HomeCategories) },
                        summary = "固定到首页、调整顺序、隐藏不看的分类",
                        value = "固定 ${settings.pinnedHomeCategories.size} 个",
                    )
                }
                row { shapes ->
                    SettingsNavRow(
                        shapes, "分类列数", onClick = { dialog = BrowsingDialog.CategoryColumns },
                        value = columnLabel(settings.categoryColumnCount),
                    )
                }
            }
        }
        item {
            SettingsGroup("功能入口") {
                row { shapes ->
                    SettingsSwitchRow(shapes, "按作者搜索", settings.showAuthorSearch, { writer.update { setShowAuthorSearch(it) } }, summary = "在详情页点作者名搜索 TA 的作品")
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "追更", settings.showSubscriptions, { writer.update { setShowSubscriptions(it) } }, summary = "书架里的追更视图和更新角标")
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "标签收藏", settings.showTagFavourites, { writer.update { setShowTagFavourites(it) } })
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "在线收藏", settings.showOnlineFavourites, { writer.update { setShowOnlineFavourites(it) } })
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "本地收藏", settings.showLocalFavourites, { writer.update { setShowLocalFavourites(it) } })
                }
            }
        }
        item {
            SettingsGroup("复制") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "复制名称模板", onClick = { dialog = BrowsingDialog.CopyTemplate },
                        summary = settings.copyNameTemplate,
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "复制前确认", settings.confirmCopy, { writer.update { setConfirmCopy(it) } })
                }
            }
        }
    }

    when (dialog) {
        BrowsingDialog.Layout -> ChoiceDialog(
            "列表样式", ListLayout.entries, settings?.listLayout, { it.label() },
            onSelect = { writer.update { setListLayout(it) } }, onDismiss = { dialog = null },
        )
        BrowsingDialog.ListColumns -> ChoiceDialog(
            "列表列数", ColumnOptions, settings?.listColumnCount, ::columnLabel,
            onSelect = { writer.update { setListColumnCount(it) } }, onDismiss = { dialog = null },
            description = "「自动」按屏幕宽度决定。",
        )
        BrowsingDialog.CategoryColumns -> ChoiceDialog(
            "分类列数", ColumnOptions, settings?.categoryColumnCount, ::columnLabel,
            onSelect = { writer.update { setCategoryColumnCount(it) } }, onDismiss = { dialog = null },
        )
        BrowsingDialog.FailedReload -> ChoiceDialog(
            "加载失败时", FailedReloadAction.entries, settings?.failedReloadAction,
            { if (it == FailedReloadAction.TAP) "点击重试" else "下拉重试" },
            onSelect = { writer.update { setFailedReloadAction(it) } }, onDismiss = { dialog = null },
        )
        BrowsingDialog.CopyTemplate -> if (settings != null) {
            CopyTemplateDialog(
                initial = settings.copyNameTemplate,
                onSave = { writer.update { setCopyNameTemplate(it) } },
                onDismiss = { dialog = null },
            )
        }
        null -> Unit
    }
}

private fun ListLayout.label() = when (this) {
    ListLayout.DETAILS -> "详情"
    ListLayout.COVER -> "封面"
    ListLayout.COVER_AND_TITLE -> "封面和标题"
}

private val TemplateTokens = listOf("{title}" to "标题", "{author}" to "作者", "{id}" to "编号")

/** Placeholder chips insert at the cursor; the preview below updates live (board 26). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CopyTemplateDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val preview = value.text.replace("{title}", "星屑列车").replace("{author}", "白川凪").replace("{id}", "5821…")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("复制名称模板") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("模板") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TemplateTokens.forEach { (token, name) ->
                        AssistChip(
                            onClick = {
                                val start = value.selection.min
                                val end = value.selection.max
                                val text = value.text.replaceRange(start, end, token)
                                value = TextFieldValue(text, TextRange(start + token.length))
                            },
                            label = { Text("$name $token") },
                        )
                    }
                }
                Text("预览：$preview", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.text.isNotBlank(),
                onClick = {
                    onSave(value.text)
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { value = TextFieldValue("[{author}] {title}") }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** Loads the server category list once (from cache when possible) for the shield and order pages. */
@Composable
private fun rememberCategories(): Triple<List<Category>?, String?, () -> Unit> {
    val container = LocalAppContainer.current
    var categories by remember { mutableStateOf<List<Category>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            categories = container.comicRepository.categories(forceRefresh = attempt > 0).filterNot { it.isWeb }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.message ?: "分类加载失败"
        }
    }
    return Triple(categories, error) { attempt++ }
}

/** Board 09 entry: shielded categories (black/white list), hidden keywords. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShieldSettingsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    val (categories, error, retry) = rememberCategories()
    var keyword by rememberSaveable { mutableStateOf("") }

    SettingsPageScaffold("封印与隐藏", onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            SettingsGroup("封印分类") {
                row { shapes ->
                    SettingsChoiceRow(
                        shapes = shapes,
                        title = "封印方式",
                        summary = if (settings.categoryShieldMode == CategoryShieldMode.BLACKLIST) {
                            "选中的分类不显示"
                        } else {
                            "只显示选中的分类"
                        },
                        options = CategoryShieldMode.entries,
                        selected = settings.categoryShieldMode,
                        onSelect = { writer.update { setCategoryShieldMode(it) } },
                        label = { if (it == CategoryShieldMode.BLACKLIST) "黑名单" else "白名单" },
                    )
                }
            }
        }
        item {
            when {
                categories != null -> FlowRow(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val names = (categories.map { it.title } + settings.shieldedCategories).distinct()
                    names.forEach { name ->
                        val checked = name in settings.shieldedCategories
                        FilterChip(
                            selected = checked,
                            onClick = {
                                val next = if (checked) settings.shieldedCategories - name else settings.shieldedCategories + name
                                writer.update { setShieldedCategories(next) }
                            },
                            label = { Text(name) },
                        )
                    }
                }
                error != null -> ErrorState(onRetry = retry, description = error)
                else -> LoadingState()
            }
        }
        item {
            SettingsGroup("隐藏关键词") {
                row { shapes ->
                    SegmentedListItem(
                        onClick = {},
                        shapes = shapes,
                        supportingContent = {
                            Column {
                                Text("标题或作者里包含这些词的漫画会被隐藏")
                                val add = {
                                    val word = keyword.trim()
                                    if (word.isNotEmpty()) {
                                        writer.update { setHiddenKeywords(settings.hiddenKeywords + word) }
                                        keyword = ""
                                    }
                                }
                                OutlinedTextField(
                                    value = keyword,
                                    onValueChange = { keyword = it },
                                    singleLine = true,
                                    placeholder = { Text("输入关键词") },
                                    trailingIcon = {
                                        IconButton(onClick = add, enabled = keyword.isNotBlank()) {
                                            Icon(Icons.Outlined.Add, contentDescription = "添加")
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { add() }),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 8.dp),
                                ) {
                                    settings.hiddenKeywords.forEach { word ->
                                        InputChip(
                                            selected = false,
                                            onClick = { writer.update { setHiddenKeywords(settings.hiddenKeywords - word) } },
                                            label = { Text(word) },
                                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "删除 $word") },
                                        )
                                    }
                                }
                            }
                        },
                    ) {
                        Text("关键词")
                    }
                }
            }
        }
    }
}

/** Pins (ordered shortcuts after 排行榜 / 随机 / 游戏专区), grid order and hidden categories. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeCategoriesPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writer = remember(container) { SettingsWriter(container.settingsRepository, scope, snackbar) }
    val settings = rememberAppSettings()
    val (categories, error, retry) = rememberCategories()

    SettingsPageScaffold("分类排序与隐藏", onBack, snackbar, modifier) {
        if (settings == null) return@SettingsPageScaffold
        item {
            SettingsGroup("固定到首页") {
                if (settings.pinnedHomeCategories.isEmpty()) {
                    row { shapes ->
                        SettingsNoteRow(shapes, "还没有固定分类。在下面的分类上点图钉，它就会出现在排行榜、随机、游戏专区后面。", Icons.Outlined.PushPin)
                    }
                }
                settings.pinnedHomeCategories.forEachIndexed { index, name ->
                    row { shapes ->
                        SegmentedListItem(
                            onClick = {},
                            shapes = shapes,
                            leadingContent = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                            trailingContent = {
                                Row {
                                    MoveButtons(
                                        index = index,
                                        count = settings.pinnedHomeCategories.size,
                                        onMove = { to -> writer.update { setPinnedHomeCategories(settings.pinnedHomeCategories.move(index, to)) } },
                                    )
                                    IconButton(onClick = { writer.update { setPinnedHomeCategories(settings.pinnedHomeCategories - name) } }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "取消固定 $name")
                                    }
                                }
                            },
                        ) { Text(name) }
                    }
                }
            }
        }
        item {
            Text(
                "隐藏的分类不出现在分类网格里，固定的快捷入口不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item {
            when {
                categories != null -> {
                    val ordered = orderedNames(categories.map { it.title }, settings)
                    SettingsGroup("全部分类") {
                        ordered.forEachIndexed { index, name ->
                            row { shapes ->
                                val hidden = name in settings.hiddenCategories
                                val pinned = name in settings.pinnedHomeCategories
                                SegmentedListItem(
                                    onClick = {},
                                    shapes = shapes,
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            MoveButtons(index, ordered.size) { to ->
                                                writer.update { setCategoryOrder(ordered.move(index, to)) }
                                            }
                                            IconToggleButton(
                                                checked = pinned,
                                                onCheckedChange = {
                                                    val next = if (pinned) settings.pinnedHomeCategories - name else settings.pinnedHomeCategories + name
                                                    writer.update { setPinnedHomeCategories(next) }
                                                },
                                            ) {
                                                Icon(Icons.Outlined.PushPin, contentDescription = if (pinned) "取消固定 $name" else "固定 $name 到首页")
                                            }
                                            IconToggleButton(
                                                checked = !hidden,
                                                onCheckedChange = {
                                                    val next = if (hidden) settings.hiddenCategories - name else settings.hiddenCategories + name
                                                    writer.update { setHiddenCategories(next) }
                                                },
                                            ) {
                                                Icon(
                                                    if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                    contentDescription = if (hidden) "显示 $name" else "隐藏 $name",
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        name,
                                        color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
                error != null -> ErrorState(onRetry = retry, description = error)
                else -> LoadingState()
            }
        }
    }
}

@Composable
private fun MoveButtons(index: Int, count: Int, onMove: (Int) -> Unit) {
    IconButton(onClick = { onMove(index - 1) }, enabled = index > 0) {
        Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移")
    }
    IconButton(onClick = { onMove(index + 1) }, enabled = index < count - 1) {
        Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移")
    }
}

private fun orderedNames(names: List<String>, settings: AppSettings): List<String> {
    val order = settings.categoryOrder.withIndex().associate { it.value to it.index }
    return names.distinct().sortedBy { order[it] ?: Int.MAX_VALUE }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
