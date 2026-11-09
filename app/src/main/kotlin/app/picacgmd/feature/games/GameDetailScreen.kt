package app.picacgmd.feature.games

import android.text.Html
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.picacgmd.core.designsystem.window.secureDialogProperties
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.SoftBackdrop
import app.picacgmd.core.network.GameInfo
import app.picacgmd.core.network.PicaImage
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.comics.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GameDetailScreen(gameId: String, onBack: () -> Unit, onComments: (String) -> Unit) {
    val repository = LocalAppContainer.current.gameRepository
    var game by remember(gameId) { mutableStateOf<GameInfo?>(null) }
    var error by remember(gameId) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var links by remember { mutableStateOf(false) }
    var screenshot by remember { mutableStateOf<PicaImage?>(null) }
    var liking by remember(gameId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val likeFailedTemplate = stringResource(R.string.detail_action_failed, "%s")
    // Optimistic like, as on the comic detail page: flip now, settle on the server's answer, undo on failure.
    val toggleLike: () -> Unit = toggle@{
        val before = game ?: return@toggle
        if (liking) return@toggle
        liking = true
        game = before.withLiked(!before.isLiked)
        scope.launch {
            try {
                game = before.withLiked(repository.switchLike(before.id))
                liking = false
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                game = before
                liking = false
                snackbar.showSnackbar(likeFailedTemplate.replace("%s", exception.readable()))
            }
        }
    }
    LaunchedEffect(gameId, attempt) {
        loading = true
        error = null
        try {
            game = repository.game(gameId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.localizedMessage ?: "加载游戏详情失败"
        } finally {
            loading = false
        }
    }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(game?.title ?: "游戏详情") },
                navigationIcon = { app.picacgmd.core.designsystem.component.BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val info = game
        if (info == null) GameLoadState(loading, error, "游戏不存在", Modifier.padding(padding)) { attempt++ }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Box {
                    SoftBackdrop(rememberGameImageUrl(info.icon), Modifier.fillMaxWidth().height(180.dp))
                    Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f), MaterialTheme.colorScheme.surface))))
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        GameImage(info.icon, info.title, Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(info.publisher, style = MaterialTheme.typography.titleMedium)
                            Text("v${info.version} · ${info.androidSize} MB", style = MaterialTheme.typography.bodySmall)
                            info.updatedAt?.let { Text("${it.take(10)} 更新", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GameLikeStatistic(info.isLiked, info.likesCount, toggleLike)
                    GameStatistic("下载", info.downloadsCount)
                    GameStatistic("评论", info.commentsCount)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button({ links = true }, Modifier.fillMaxWidth(), enabled = info.androidLinks.isNotEmpty()) {
                        Text(if (info.androidLinks.isEmpty()) "暂无安卓版" else "获取下载链接")
                    }
                    if (info.androidLinks.isNotEmpty()) Text(
                        "第三方下载链接",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (info.screenshots.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)) {
                    items(info.screenshots) { image ->
                        GameImage(image, "游戏截图，点击放大", Modifier.size(244.dp, 148.dp).clip(RoundedCornerShape(20.dp)).clickable { screenshot = image })
                    }
                }
            }
            stickyHeader {
                PrimaryTabRow(selectedTabIndex = 0) {
                    Tab(selected = true, onClick = {}, text = { Text("详情") })
                    Tab(selected = false, onClick = { onComments(info.title) }, text = { Text("评论 ${info.commentsCount}") })
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(remember(info.description) { Html.fromHtml(info.description, Html.FROM_HTML_MODE_COMPACT).toString().trim() })
                    if (info.updateContent.isNotBlank()) {
                        Text("更新内容", style = MaterialTheme.typography.titleMedium)
                        Text(remember(info.updateContent) { Html.fromHtml(info.updateContent, Html.FROM_HTML_MODE_COMPACT).toString().trim() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Box(Modifier.height(16.dp)) }
        }
    }
    game?.takeIf { links }?.let { GameLinksSheet(it.androidLinks, onDismiss = { links = false }) }
    screenshot?.let { image ->
        Dialog(onDismissRequest = { screenshot = null }, properties = secureDialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                GameImage(image, "游戏截图", Modifier.fillMaxSize(), ContentScale.Fit)
                IconButton({ screenshot = null }, Modifier.align(Alignment.TopEnd).padding(24.dp)) { Icon(Icons.Outlined.Close, "关闭", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun GameStatistic(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** This game with the like set to [liked]; the count only moves when the state actually changes. */
private fun GameInfo.withLiked(liked: Boolean): GameInfo =
    if (liked == isLiked) this else copy(isLiked = liked, likesCount = (likesCount + if (liked) 1 else -1).coerceAtLeast(0))

/** The 喜欢 statistic doubles as the like toggle: a heart joins the count and fills once liked. */
@Composable
private fun GameLikeStatistic(liked: Boolean, count: Int, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = if (liked) colors.primary else colors.onSurfaceVariant
    val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val heart = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        // Only a real change jumps: not the first frame, and not a server answer that agrees.
        if (liked == shown) return@LaunchedEffect
        shown = liked
        heart.snapTo(0.6f)
        heart.animateTo(1f, spring)
    }
    Column(
        Modifier
            .clip(MaterialTheme.shapes.large)
            .toggleable(value = liked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                null,
                Modifier.size(18.dp).graphicsLayer {
                    scaleX = heart.value
                    scaleY = heart.value
                },
                tint = accent,
            )
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, color = if (liked) colors.primary else Color.Unspecified)
        }
        Text("喜欢", style = MaterialTheme.typography.bodySmall, color = accent)
    }
}
