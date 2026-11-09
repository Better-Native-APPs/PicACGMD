package app.picacgmd.feature.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.PaginationMode
import app.picacgmd.core.designsystem.component.StatusPane
import app.picacgmd.core.domain.repository.GameRepository
import app.picacgmd.core.network.GameSimple
import app.picacgmd.core.network.PicaImage
import app.picacgmd.di.LocalAppContainer
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
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("游戏专区"); if (model.total > 0) Text("共 ${model.total} 款", style = MaterialTheme.typography.bodySmall) } },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = { IconButton({ model.load(1, false) }, enabled = !model.loading) { Icon(Icons.Outlined.Refresh, "刷新") } },
        )
    }) { padding ->
        if (model.games.isEmpty()) {
            GameLoadState(model.loading, model.error, "暂无游戏", Modifier.padding(padding)) { model.load(1, false) }
        } else {
            LazyColumn(
                state = list, modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(model.games, key = { _, game -> game.id }) { index, game ->
                    Surface(
                        onClick = { onGameClick(game.id) },
                        shape = RoundedCornerShape(
                            topStart = if (index == 0) 26.dp else 6.dp, topEnd = if (index == 0) 26.dp else 6.dp,
                            bottomStart = if (index == model.games.lastIndex) 26.dp else 6.dp,
                            bottomEnd = if (index == model.games.lastIndex) 26.dp else 6.dp,
                        ),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            GameImage(game.icon, game.title, Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(game.publisher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("v${game.version} · ${if (game.android) "安卓版" else "暂无安卓版"}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("♥ ${game.likesCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (model.loading) LoadingIndicator()
                        model.error?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                            FilledTonalButton({ model.load(if (infinite) model.page + 1 else model.page, infinite) }) { Text("重试") }
                        }
                        if (!infinite) GamePagination(model.page, model.pages, model.loading) { model.load(it, false) }
                        else if (!model.loading && model.error == null && model.page < model.pages) {
                            TextButton({ model.load(model.page + 1, true) }) { Text("加载更多") }
                        } else if (model.page >= model.pages) Text("已经到底了", style = MaterialTheme.typography.bodySmall)
                    }
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
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        AsyncImage(model = url, contentDescription = description, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GameLoadState(loading: Boolean, error: String?, empty: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) LoadingIndicator() else StatusPane(
            title = if (error != null) "加载失败" else empty,
            description = error,
            actions = if (error != null) { { Button(onRetry) { Text("重试") } } } else null,
        )
    }
}

@Composable
internal fun GamePagination(page: Int, pages: Int, loading: Boolean, onPage: (Int) -> Unit) {
    var jump by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    if (pages > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
        TextButton({ onPage(page - 1) }, enabled = !loading && page > 1) { Text("上一页") }
        TextButton({ target = page.toString(); jump = true }, enabled = !loading) { Text("$page / $pages") }
        TextButton({ onPage(page + 1) }, enabled = !loading && page < pages) { Text("下一页") }
    }
    if (jump) AlertDialog(
        onDismissRequest = { jump = false }, title = { Text("跳转页码") },
        text = { OutlinedTextField(target, { target = it.filter(Char::isDigit) }, label = { Text("1–$pages") }, singleLine = true) },
        confirmButton = { TextButton({ target.toIntOrNull()?.takeIf { it in 1..pages }?.let(onPage); jump = false }, enabled = target.toIntOrNull()?.let { it in 1..pages } == true) { Text("跳转") } },
        dismissButton = { TextButton({ jump = false }) { Text("取消") } },
    )
}
