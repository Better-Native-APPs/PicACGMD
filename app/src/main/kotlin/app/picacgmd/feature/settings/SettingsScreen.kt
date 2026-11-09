package app.picacgmd.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.graphics.shapes.RoundedPolygon
import app.picacgmd.R

/**
 * Settings sub pages (boards 25–28b). The name is stable, so a navigation route can carry it as a
 * string: `SettingsPage.valueOf(route.page)`.
 */
enum class SettingsPage(val title: String, val summary: String) {
    Appearance("外观", "主题、配色、纯黑背景、刷新率、屏幕方向"),
    Browsing("列表与浏览", "加载方式、列数、封印、首页分类、功能入口"),
    Reading("阅读", "默认模式、操控、音量键、缩放、电子墨水屏"),
    Downloads("下载", "线程数 1–16、自动下载、仅 Wi-Fi"),
    Network("网络", "分流、图片分流、分流同步、代理、超时"),
    Privacy("隐私与安全", "应用锁、防截屏、浏览记录"),
    Data("数据与存储", "存储占用、清理缓存、自动清理"),
    About("关于", "版本、开源许可、隐私说明、使用说明"),
}

private data class PageStyle(val icon: ImageVector, val shape: RoundedPolygon)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun SettingsPage.style(): PageStyle = when (this) {
    SettingsPage.Appearance -> PageStyle(Icons.Outlined.Palette, MaterialShapes.Cookie9Sided)
    SettingsPage.Browsing -> PageStyle(Icons.Outlined.ViewAgenda, MaterialShapes.Clover4Leaf)
    SettingsPage.Reading -> PageStyle(Icons.AutoMirrored.Outlined.MenuBook, MaterialShapes.Cookie4Sided)
    SettingsPage.Downloads -> PageStyle(Icons.Outlined.Download, MaterialShapes.Sunny)
    SettingsPage.Network -> PageStyle(Icons.Outlined.Lan, MaterialShapes.Cookie6Sided)
    SettingsPage.Privacy -> PageStyle(Icons.Outlined.Security, MaterialShapes.Clover8Leaf)
    SettingsPage.Data -> PageStyle(Icons.Outlined.Storage, MaterialShapes.Flower)
    SettingsPage.About -> PageStyle(Icons.Outlined.Info, MaterialShapes.Circle)
}

/** One searchable setting; the search page and the sub pages share this static table (board 25). */
private data class SettingsEntry(val title: String, val summary: String, val page: SettingsPage, val section: String)

private val SearchIndex: List<SettingsEntry> = buildList {
    fun add(page: SettingsPage, section: String, vararg pairs: Pair<String, String>) {
        pairs.forEach { (title, summary) -> add(SettingsEntry(title, summary, page, section)) }
    }
    add(
        SettingsPage.Appearance, "主题",
        "主题模式" to "跟随系统、浅色、深色", "配色" to "壁纸取色、品牌粉、书本、灰蓝",
        "深色时用纯黑背景" to "适合 OLED 屏幕",
    )
    add(
        SettingsPage.Appearance, "显示",
        "屏幕刷新率" to "仅对本应用生效", "屏幕方向" to "跟随系统、横屏、竖屏",
        "全屏显示" to "隐藏状态栏或导航栏", "连按两次返回退出" to "仅在首页生效", "启动图片" to "使用自选图片",
    )
    add(
        SettingsPage.Browsing, "列表",
        "加载方式" to "连续滚动或分页跳转", "列表样式" to "详情、封面、封面和标题", "列表列数" to "封面样式每行的漫画数",
        "加载失败时" to "点击重试或下拉重试",
    )
    add(
        SettingsPage.Browsing, "封印",
        "封印与隐藏" to "封印分类、隐藏关键词",
        "隐藏看过的漫画" to "按浏览记录隐藏", "屏蔽 AI 作品" to "隐藏 AI 生成的作品",
    )
    add(
        SettingsPage.Browsing, "封印分类",
        "封印分类" to "不想看到的分类", "封印方式" to "黑名单或白名单",
    )
    add(
        SettingsPage.Browsing, "隐藏关键词",
        "隐藏关键词" to "按标题、作者、分类、标签隐藏", "预设关键词" to "按预设组添加或移除",
    )
    add(
        SettingsPage.Browsing, "首页",
        "分类排序与隐藏" to "固定、排序和隐藏分类", "分类列数" to "自动或 2–7 列",
    )
    add(
        SettingsPage.Browsing, "功能入口",
        "按作者搜索" to "点按详情页作者名搜索", "追更" to "显示书架追更和更新角标", "标签收藏" to "", "在线收藏" to "",
    )
    add(
        SettingsPage.Browsing, "复制",
        "复制名称模板" to "复制漫画名称时使用", "复制前确认" to "",
    )
    add(
        SettingsPage.Reading, "默认阅读方式",
        "阅读模式" to "条漫、相册、双页", "阅读方向" to "从上到下、从左到右、从右到左",
        "双页排列" to "仅用于双页相册", "阅读器背景" to "黑、灰、纸、白", "图片滤镜" to "正常、灰度、复古",
        "进度条位置" to "底部、左侧、右侧",
    )
    add(
        SettingsPage.Reading, "显示",
        "屏幕常亮" to "", "全屏阅读" to "阅读时隐藏状态栏和导航栏",
        "向后翻页时自动全屏" to "翻页后隐藏系统栏", "翻页动画" to "",
    )
    add(
        SettingsPage.Reading, "操控",
        "操控方式" to "翻页和菜单的点按区域", "三区点按右侧为下一页" to "三区点按的翻页方向",
        "音量键翻页" to "", "连按两次音量键切换章节" to "", "滚动方式" to "按图片或按屏幕距离",
        "每次滚动的屏幕比例" to "按屏幕距离滚动的幅度", "手势速度" to "", "缩放范围" to "最小、最大、双击缩放",
        "锁定拖动边界" to "放大后防止图片拖出屏幕",
    )
    add(
        SettingsPage.Reading, "缩放范围",
        "最小缩放" to "缩小图片的下限", "最大缩放" to "放大图片的上限", "双击缩放" to "双击图片的缩放倍数",
    )
    add(
        SettingsPage.Reading, "电子墨水屏",
        "减少动画" to "减少残影", "按屏滚动" to "点按翻页每次一屏，无平滑滚动", "滚动幅度" to "", "触发距离" to "",
    )
    add(
        SettingsPage.Downloads, "下载",
        "同时下载线程数" to "1–16",
    )
    add(
        SettingsPage.Downloads, "网络",
        "仅 Wi-Fi 下载" to "手动下载也需连接 Wi-Fi",
    )
    add(
        SettingsPage.Downloads, "自动化",
        "收藏时自动下载" to "下载全部章节", "自动下载仅限 Wi-Fi" to "", "取消收藏时删除下载" to "",
        "下载详情显示评论" to "需要联网加载",
    )
    add(
        SettingsPage.Downloads, "位置",
        "下载位置" to "应用专属存储",
    )
    add(
        SettingsPage.Network, "分流",
        "分流" to "切换分流、测速、自定义 IP", "图片分流" to "切换图片服务器、测速、自定义地址",
        "分流同步" to "获取最新分流地址、恢复内置分流", "用 API 加载图片" to "图片分流不可用时启用",
    )
    add(
        SettingsPage.Network, "代理服务器",
        "代理地址" to "HTTP / SOCKS5",
    )
    add(
        SettingsPage.Network, "看图",
        "图片质量" to "仅影响在线图片，下载为原图",
    )
    add(
        SettingsPage.Network, "连接",
        "连接超时" to "", "读取超时" to "", "时区" to "时间校验失败时调整",
    )
    add(
        SettingsPage.Privacy, "应用锁",
        "打开应用时验证身份" to "指纹、面容或锁屏密码", "允许生物识别解锁" to "关闭后仅用锁屏密码", "离开后重新验证" to "",
    )
    add(
        SettingsPage.Privacy, "屏幕",
        "防止截屏" to "同时隐藏最近任务画面",
    )
    add(
        SettingsPage.Privacy, "记录",
        "保存浏览记录" to "关闭后停止记录，保留已有记录", "记录详情页浏览" to "",
    )
    add(
        SettingsPage.Data, "数据",
        "存储占用" to "下载、图片缓存、数据库", "清理缓存" to "不影响已下载漫画",
        "管理下载" to "打开下载列表",
    )
    add(
        SettingsPage.Data, "清理缓存",
        "图片缓存" to "已浏览的封面和图片", "接口缓存" to "列表临时数据", "浏览记录和阅读进度" to "清除后无法恢复",
    )
    add(
        SettingsPage.Data, "缓存",
        "自动清理缓存" to "仅清理图片和接口缓存",
    )
    add(
        SettingsPage.Data, "同步",
        "WebDAV 云同步" to "同步浏览记录",
    )
    add(SettingsPage.About, "关于", "版本" to "", "开源许可" to "", "隐私说明" to "", "使用说明" to "")
}

/**
 * Settings home (board 25): large flexible title, search over every item, and the pages grouped as
 * 常用 / 系统 / 关于. [onOpenPage] opens a sub page (see [SettingsPageScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        searching = it.isNotEmpty()
                    },
                    singleLine = true,
                    placeholder = { Text("搜索设置") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = ""; searching = false }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清除")
                            }
                        }
                    } else {
                        null
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (searching) {
                val needle = query.trim()
                val results = SearchIndex.filter {
                    it.title.contains(needle, ignoreCase = true) || it.summary.contains(needle, ignoreCase = true) ||
                        it.page.title.contains(needle, ignoreCase = true)
                }
                item {
                    Text(
                        if (results.isEmpty()) "未找到「$needle」" else "${results.size} 项结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                item {
                    SettingsGroup(title = null) {
                        results.forEach { entry ->
                            row { shapes ->
                                SettingsNavRow(
                                    shapes = shapes,
                                    title = entry.title,
                                    summary = "${entry.page.title} › ${entry.section}" +
                                        if (entry.summary.isNotEmpty()) " · ${entry.summary}" else "",
                                    onClick = { onOpenPage(entry.page) },
                                )
                            }
                        }
                    }
                }
                if (results.isEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text("试试搜索", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("线程", "代理", "音量键", "截屏", "缓存", "纯黑").forEach { hint ->
                                    AssistChip(onClick = { query = hint }, label = { Text(hint) })
                                }
                            }
                        }
                    }
                }
            } else {
                item { PageGroup("常用", listOf(SettingsPage.Appearance, SettingsPage.Browsing, SettingsPage.Reading, SettingsPage.Downloads), onOpenPage) }
                item { PageGroup("系统", listOf(SettingsPage.Network, SettingsPage.Privacy, SettingsPage.Data), onOpenPage) }
                item { PageGroup(null, listOf(SettingsPage.About), onOpenPage, Modifier.padding(top = 20.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageGroup(
    title: String?,
    pages: List<SettingsPage>,
    onOpenPage: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
    )
    SettingsGroup(title = title, modifier = modifier) {
        pages.forEach { page ->
            row { shapes ->
                val style = page.style()
                val (container, content) = palette[page.ordinal % palette.size]
                SettingsNavRow(
                    shapes = shapes,
                    title = page.title,
                    summary = page.summary,
                    onClick = { onOpenPage(page) },
                    leading = { ShapeBadge(style.icon, style.shape, container, content) },
                )
            }
        }
    }
}

/**
 * One settings sub page. [onOpenDownloads] opens the downloads tab from the storage card;
 * [onOpenPage] lets the about entry etc. jump between pages.
 */
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDownloads: () -> Unit = {},
) {
    when (page) {
        SettingsPage.Appearance -> AppearanceSettingsPage(onBack, modifier)
        SettingsPage.Browsing -> BrowsingSettingsPage(onBack, modifier)
        SettingsPage.Reading -> ReadingSettingsPage(onBack, modifier)
        SettingsPage.Downloads -> app.picacgmd.feature.downloads.DownloadSettingsScreen(onBack, modifier)
        SettingsPage.Network -> NetworkSettingsPage(onBack, modifier)
        SettingsPage.Privacy -> PrivacySettingsPage(onBack, modifier)
        SettingsPage.Data -> DataSettingsPage(onBack, onOpenDownloads, modifier)
        SettingsPage.About -> AboutSettingsPage(onBack, modifier)
    }
}

/**
 * Self-contained settings flow for a single `SettingsRoute` destination: the home page and its sub
 * pages are switched in place, with system back returning to the home page first. Use this when no
 * separate sub page route exists; [initialPage] opens a page directly (e.g. 关于 from 「我的」).
 */
@Composable
fun SettingsHost(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: SettingsPage? = null,
    onOpenDownloads: () -> Unit = {},
) {
    val stack = rememberSaveable(
        saver = listSaver(save = { list -> list.map { it.name } }, restore = { names -> mutableStateListOf(*names.map(SettingsPage::valueOf).toTypedArray()) }),
    ) { mutableStateListOf<SettingsPage>().apply { if (initialPage != null) add(initialPage) } }
    val startedOnPage = initialPage != null
    val current = stack.lastOrNull()
    val popOrExit: () -> Unit = {
        if (stack.isEmpty() || (startedOnPage && stack.size == 1)) onBack() else stack.removeAt(stack.lastIndex)
    }
    BackHandler(enabled = current != null && !(startedOnPage && stack.size == 1)) { stack.removeAt(stack.lastIndex) }
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            if (targetState != null && initialState == null) {
                (slideInHorizontally { it / 8 } + fadeIn()) togetherWith fadeOut()
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        modifier = modifier,
        label = "settingsHost",
    ) { page ->
        if (page == null) {
            SettingsScreen(onBack = onBack, onOpenPage = { stack.add(it) })
        } else {
            SettingsPageScreen(page = page, onBack = popOrExit, onOpenDownloads = onOpenDownloads)
        }
    }
}
