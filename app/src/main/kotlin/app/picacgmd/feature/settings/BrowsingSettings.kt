package app.picacgmd.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import app.picacgmd.core.domain.repository.BlockPreset
import app.picacgmd.core.domain.repository.BlockPresets
import app.picacgmd.core.domain.repository.findKeyword
import app.picacgmd.core.domain.repository.minusKeywords
import app.picacgmd.core.domain.repository.plusKeywords
import app.picacgmd.core.network.Category
import app.picacgmd.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
                        summary = "连续滚动或分页跳转",
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
                        summary = "封面样式每行的漫画数", value = columnLabel(settings.listColumnCount),
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
                        summary = "封印分类、隐藏关键词",
                        value = if (count == 0) "未设置" else "$count 项",
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "屏蔽 AI 作品", settings.hideAiComics,
                        onCheckedChange = { writer.update { setHideAiComics(it) } },
                        summary = "隐藏 AI 生成的作品",
                    )
                }
                row { shapes ->
                    SettingsSwitchRow(
                        shapes, "隐藏看过的漫画", settings.hideViewedComics,
                        onCheckedChange = { writer.update { setHideViewedComics(it) } },
                        summary = "按浏览记录隐藏",
                    )
                }
            }
        }
        item {
            SettingsGroup("首页") {
                row { shapes ->
                    SettingsNavRow(
                        shapes, "分类排序与隐藏", onClick = { onOpen(BrowsingSub.HomeCategories) },
                        summary = "固定、排序和隐藏分类",
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
                    SettingsSwitchRow(shapes, "按作者搜索", settings.showAuthorSearch, { writer.update { setShowAuthorSearch(it) } }, summary = "点按详情页作者名搜索")
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "追更", settings.showSubscriptions, { writer.update { setShowSubscriptions(it) } }, summary = "显示书架追更和更新角标")
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "标签收藏", settings.showTagFavourites, { writer.update { setShowTagFavourites(it) } })
                }
                row { shapes ->
                    SettingsSwitchRow(shapes, "在线收藏", settings.showOnlineFavourites, { writer.update { setShowOnlineFavourites(it) } })
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
        item { HiddenKeywordsGroup(settings.hiddenKeywords, snackbar, scope) }
    }
}

/**
 * 隐藏关键词: single words, plus 「快速添加预设组」 that add or remove a whole group at once. Writes go
 * straight to the repository, so a group change can confirm itself with a count once it is saved. They
 * run in the page's [scope], so scrolling this group away does not cancel one.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HiddenKeywordsGroup(keywords: List<String>, snackbar: SnackbarHostState, scope: CoroutineScope) {
    val repository = LocalAppContainer.current.settingsRepository
    var draft by rememberSaveable { mutableStateOf("") }
    var draftError by rememberSaveable { mutableStateOf<String?>(null) }
    var openPreset by rememberSaveable { mutableStateOf<String?>(null) }
    val save: (List<String>, String?) -> Unit = { next, confirmation ->
        scope.launch {
            try {
                repository.setHiddenKeywords(next)
                if (confirmation != null) snackbar.showSnackbar(confirmation)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                snackbar.showSnackbar(exception.message ?: "保存失败")
            }
        }
    }
    val add = {
        val word = draft.trim()
        val existing = keywords.findKeyword(word)
        when {
            word.isEmpty() -> draftError = "请输入关键词"
            // Same word in another case: it would hide exactly the same comics.
            existing != null -> draftError = "「$existing」已添加"
            else -> {
                save(keywords.plusKeywords(listOf(word)), null)
                draft = ""
            }
        }
    }

    SettingsGroup("隐藏关键词") {
        row { shapes ->
            SegmentedListItem(
                onClick = {},
                shapes = shapes,
                supportingContent = {
                    Column {
                        Text("标题、作者、分类或标签含关键词时隐藏，纯英文及数字按整词匹配")
                        OutlinedTextField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                draftError = null
                            },
                            singleLine = true,
                            placeholder = { Text("输入关键词") },
                            isError = draftError != null,
                            supportingText = draftError?.let { message -> { Text(message) } },
                            trailingIcon = {
                                IconButton(onClick = add) { Icon(Icons.Outlined.Add, contentDescription = "添加关键词") }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        if (keywords.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                keywords.forEach { word ->
                                    InputChip(
                                        selected = false,
                                        onClick = { save(keywords - word, null) },
                                        label = { Text(word) },
                                        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "删除「$word」") },
                                    )
                                }
                            }
                        }
                    }
                },
            ) {
                Text("关键词")
            }
        }
        row { shapes ->
            SegmentedListItem(
                onClick = {},
                shapes = shapes,
                supportingContent = {
                    Column {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            BlockPresets.all.forEach { preset ->
                                PresetChip(preset, keywords, onClick = { openPreset = preset.name })
                            }
                        }
                    }
                },
            ) {
                Text("预设关键词")
            }
        }
    }

    BlockPresets.all.firstOrNull { it.name == openPreset }?.let { preset ->
        PresetDialog(preset, keywords, onDismiss = { openPreset = null }, onConfirm = save)
    }
}

/** A preset group with its word count: ✓ once every word is in, 「2/4」 while only some are. */
@Composable
private fun PresetChip(preset: BlockPreset, keywords: List<String>, onClick: () -> Unit) {
    val total = preset.keywords.size
    val added = total - preset.missingFrom(keywords).size
    val complete = added == total
    FilterChip(
        selected = complete,
        onClick = onClick,
        label = { Text(if (added in 1 until total) "${preset.name} · $added/$total" else "${preset.name} · $total") },
        leadingIcon = if (complete) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = Modifier.semantics {
            contentDescription = "${preset.name}，$total 个关键词"
            stateDescription = when {
                complete -> "已全部添加"
                added > 0 -> "已添加 $added 个"
                else -> "未添加"
            }
        },
    )
}

/** Adds the words of a group that are missing, or removes the whole group once all of it is in. */
@Composable
private fun PresetDialog(
    preset: BlockPreset,
    keywords: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (next: List<String>, confirmation: String) -> Unit,
) {
    val total = preset.keywords.size
    val missing = preset.missingFrom(keywords)
    val removing = missing.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (removing) "移除「${preset.name}」？" else "添加「${preset.name}」？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        removing -> "从屏蔽列表移除 $total 个关键词"
                        missing.size == total -> "添加 $total 个屏蔽关键词"
                        else -> "共 $total 个关键词，已添加 ${total - missing.size} 个，本次添加 ${missing.size} 个"
                    },
                )
                Text(
                    (if (removing) preset.keywords else missing).joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (removing) {
                        val next = keywords.minusKeywords(preset.keywords)
                        onConfirm(next, "已移除 ${keywords.size - next.size} 个关键词")
                    } else {
                        onConfirm(keywords.plusKeywords(missing), "已添加 ${missing.size} 个关键词")
                    }
                    onDismiss()
                },
            ) { Text(if (removing) "移除" else "添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
                        SettingsNoteRow(shapes, "还没有固定分类，点图钉即可添加", Icons.Outlined.PushPin)
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
                                        Icon(Icons.Outlined.Close, contentDescription = "取消固定「$name」")
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
                "隐藏仅影响分类网格，保留固定入口",
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
                                                Icon(Icons.Outlined.PushPin, contentDescription = if (pinned) "取消固定「$name」" else "固定「$name」到首页")
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
                                                    contentDescription = if (hidden) "显示「$name」" else "隐藏「$name」",
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
