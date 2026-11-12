package app.picacgmd.feature.games

import app.picacgmd.core.designsystem.window.secureDialogProperties

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppFilledTonalButton
import app.picacgmd.core.designsystem.component.AppIconButton
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.designsystem.component.TonalAsyncImage
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.designsystem.component.MetaIcons
import app.picacgmd.core.designsystem.component.MetaInfo
import app.picacgmd.core.designsystem.component.StatusPane
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.network.GameSimple
import app.picacgmd.core.network.PicaImage
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.comics.formatCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private class GamesViewModel(private val repository: GameRepository) : ViewModel() {
    var games by mutableStateOf<List<GameSimple>>(emptyList())
        private set
    var page by mutableStateOf(0)
        private set
    var pages by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init { load(1, append = false) }

    fun load(target: Int, append: Boolean) {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = repository.games(target)
                games = if (append) (games + result.docs).distinctBy { it.id } else result.docs
                page = result.page
                pages = result.pages
                total = result.total
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.localizedMessage ?: "加载游戏失败"
            } finally {
                loading = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GamesScreen(onBack: () -> Unit, onGameClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val model: GamesViewModel = viewModel(factory = viewModelFactory { initializer { GamesViewModel(container.gameRepository) } })
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings.DEFAULT)
    val infinite = settings.paginationMode == PaginationMode.INFINITE
    val list = rememberLazyListState()
    LaunchedEffect(list, infinite) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (infinite && model.games.isNotEmpty() && last >= model.games.size - 3 &&
                !model.loading && model.error == null && model.page < model.pages) {
                model.load(model.page + 1, append = true)
            }
        }
    }
    LaunchedEffect(model.page, infinite) { if (!infinite && model.page > 0) list.scrollToItem(0) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Column { Text("游戏专区"); if (model.total > 0) Text("共 ${model.total} 款", style = MaterialTheme.typography.bodySmall) } },
                navigationIcon = { app.picacgmd.core.designsystem.component.BackButton(onBack) },
                actions = { AppIconButton({ model.load(1, false) }, enabled = !model.loading) { Icon(Icons.Outlined.Refresh, "刷新") } },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (model.games.isEmpty()) {
            GameLoadState(model.loading, model.error, "暂无游戏", Modifier.padding(padding)) { model.load(1, false) }
        } else {
            val paging = !infinite && model.pages > 1
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = list, modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 16.dp,
                        // Room for the paging toolbar so the last game can clear it.
                        bottom = if (paging) 104.dp else 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(model.games, key = { _, game -> game.id }) { index, game ->
                        Surface(
                            onClick = { onGameClick(game.id) },
                            shape = ListItemDefaults.segmentedShapes(index = index, count = model.games.size).shape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                GameImage(game.icon, game.title, Modifier.size(72.dp).clip(MaterialTheme.shapes.largeIncreased))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(game.publisher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("v${game.version} · ${if (game.android) "安卓版" else "暂无安卓版"}", style = MaterialTheme.typography.bodySmall)
                                }
                                MetaInfo(MetaIcons.Likes, formatCount(game.likesCount.toLong()))
                            }
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (model.loading) LoadingIndicator()
                            model.error?.let { message ->
                                Text(message, color = MaterialTheme.colorScheme.error)
                                AppFilledTonalButton({ model.load(if (infinite) model.page + 1 else model.page, infinite) }) { Text("重试") }
                            }
                            if (infinite && !model.loading && model.error == null) {
                                if (model.page < model.pages) AppTextButton({ model.load(model.page + 1, true) }) { Text("加载更多") }
                                else Text("没有更多", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (paging) {
                    GamePagination(
                        page = model.page,
                        pages = model.pages,
                        loading = model.loading,
                        onPage = { model.load(it, false) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = padding.calculateBottomPadding() + 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberGameImageUrl(image: PicaImage?): String? {
    val provider = LocalAppContainer.current.networkConfigProvider
    val clients by provider.clients.collectAsStateWithLifecycle()
    return remember(image, clients.configuration) { image?.takeIf { it.path.isNotBlank() }?.let(provider::imageUrl) }
}

@Composable
internal fun GameImage(image: PicaImage?, description: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val url = rememberGameImageUrl(image)
    TonalAsyncImage(model = url, contentDescription = description, modifier = modifier, contentScale = contentScale)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GameLoadState(loading: Boolean, error: String?, empty: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) LoadingState() else StatusPane(
            title = if (error != null) "加载失败" else empty,
            description = error,
            actions = if (error != null) { { AppButton(onRetry) { Text("重试") } } } else null,
        )
    }
}

/**
 * Board 08's button paging, shared with the game list and the game comments: a floating toolbar with
 * 首页 / 上一页 / 「12 / 175」 / 下一页 / 末页. The page label opens a jump dialog. The toolbar is always
 * expanded, so it never animates while it sits idle (low-end rule).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GamePagination(page: Int, pages: Int, loading: Boolean, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    var jump by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    HorizontalFloatingToolbar(modifier = modifier, expanded = true) {
        AppIconButton({ onPage(1) }, enabled = !loading && page > 1) {
            Icon(Icons.Outlined.KeyboardDoubleArrowLeft, "首页")
        }
        AppIconButton({ onPage(page - 1) }, enabled = !loading && page > 1) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一页")
        }
        // The page label is the jump target, sized to the 48dp minimum so it reads as a button too.
        Box(
            Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable(enabled = !loading) { target = page.toString(); jump = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.comics_page_indicator, page, pages),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        AppIconButton({ onPage(page + 1) }, enabled = !loading && page < pages) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一页")
        }
        AppIconButton({ onPage(pages) }, enabled = !loading && page < pages) {
            Icon(Icons.Outlined.KeyboardDoubleArrowRight, "末页")
        }
    }
    if (jump) AlertDialog(
        properties = secureDialogProperties(),
        onDismissRequest = { jump = false }, title = { Text("跳转页码") },
        text = { OutlinedTextField(target, { target = it.filter(Char::isDigit) }, label = { Text("1–$pages") }, singleLine = true) },
        confirmButton = { AppTextButton({ target.toIntOrNull()?.takeIf { it in 1..pages }?.let(onPage); jump = false }, enabled = target.toIntOrNull()?.let { it in 1..pages } == true) { Text("跳转") } },
        dismissButton = { AppTextButton({ jump = false }) { Text("取消") } },
    )
}
