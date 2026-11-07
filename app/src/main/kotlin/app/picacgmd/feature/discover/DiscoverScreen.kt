package app.picacgmd.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.CategoryChannelButton
import app.picacgmd.core.designsystem.component.CategoryTile
import app.picacgmd.core.designsystem.component.HomeChannel
import app.picacgmd.core.designsystem.component.HomeChannelButton
import app.picacgmd.core.designsystem.component.HomeChannelDefaults
import app.picacgmd.core.designsystem.component.OfflineBanner
import app.picacgmd.core.designsystem.component.SkeletonBlock
import app.picacgmd.core.designsystem.component.SearchEntryBar
import app.picacgmd.core.designsystem.component.SectionHeader
import app.picacgmd.core.designsystem.component.ShapedAvatar
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

/** A cell of the category grid: the built-in 「推荐」 entry or a server category. */
private sealed interface CategoryCell {
    data object Recommend : CategoryCell
    data class Server(val category: HomeCategory) : CategoryCell
}

/**
 * 发现 (board 04 with the §0 changes): search bar with the 「我的」 avatar, the three fixed channels
 * 排行榜 / 随机 / 游戏专区 followed by pinned categories, the recommendation carousel and the
 * category grid, whose first tile is 「推荐」.
 *
 * @param showProfileEntry false on large screens, where 「我的」 is a rail item instead (board 31).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    content: DiscoverContent?,
    showProfileEntry: Boolean,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onChannelClick: (HomeChannel) -> Unit,
    onCategoryClick: (String) -> Unit,
    onCollectionsClick: () -> Unit,
    onAllCategoriesClick: () -> Unit,
    onComicClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    failure: LoadState.Failed? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        SearchEntryBar(
            placeholder = stringResource(R.string.discover_search_placeholder),
            onClick = onSearchClick,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            trailingContent = if (showProfileEntry) {
                {
                    ShapedAvatar(
                        contentDescription = stringResource(R.string.discover_profile),
                        onClick = onProfileClick,
                    )
                }
            } else {
                null
            },
        )
        OfflineBanner(
            visible = content != null && failure != null,
            message = if (failure?.offline == false) {
                stringResource(R.string.discover_state_refresh_failed)
            } else {
                stringResource(R.string.discover_state_offline_banner)
            },
            actionLabel = stringResource(R.string.discover_state_retry),
            onAction = onRetry,
        )
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = when {
                    maxWidth < 600.dp -> 3
                    maxWidth < 840.dp -> 4
                    else -> 6
                }
                val cells = remember(content?.categories) {
                    listOf<CategoryCell>(CategoryCell.Recommend) +
                        content?.categories.orEmpty().map { CategoryCell.Server(it) }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    // Fixed channels need no data, so they stay usable while loading or offline.
                    item(key = "channels", contentType = "channels") {
                        ChannelRow(
                            pinned = content?.pinnedCategories.orEmpty(),
                            onChannelClick = onChannelClick,
                            onPinnedClick = onCategoryClick,
                        )
                    }
                    when {
                        content != null -> {
                            if (content.carousel.isNotEmpty()) {
                                item(key = "recommend-header", contentType = "header") {
                                    SectionHeader(
                                        title = stringResource(R.string.discover_section_recommend),
                                        actionLabel = stringResource(R.string.discover_action_all),
                                        onActionClick = onCollectionsClick,
                                    )
                                }
                                item(key = "carousel", contentType = "carousel") {
                                    RecommendCarousel(items = content.carousel, onComicClick = onComicClick)
                                }
                            }
                            item(key = "category-header", contentType = "header") {
                                SectionHeader(
                                    title = stringResource(R.string.discover_section_categories),
                                    actionLabel = stringResource(R.string.discover_action_all_categories),
                                    onActionClick = onAllCategoriesClick,
                                )
                            }
                            itemsIndexed(
                                items = cells.chunked(columns),
                                key = { index, _ -> "category-row-$columns-$index" },
                                contentType = { _, _ -> "category-row" },
                            ) { _, row ->
                                CategoryRow(
                                    row = row,
                                    columns = columns,
                                    onRecommendClick = onCollectionsClick,
                                    onCategoryClick = onCategoryClick,
                                )
                            }
                        }
                        failure != null -> item(key = "failure", contentType = "status") {
                            LoadFailure(failure = failure, onRetry = onRetry)
                        }
                        else -> item(key = "skeleton", contentType = "status") {
                            DiscoverSkeleton(columns = columns)
                        }
                    }
                    item(key = "bottom-inset", contentType = "spacer") {
                        Spacer(Modifier.padding(bottom = 8.dp).windowInsetsBottomHeight(WindowInsets.safeDrawing))
                    }
                }
            }
        }
    }
}

/** Carousel and grid placeholders shown on the very first load. */
@Composable
private fun DiscoverSkeleton(columns: Int) {
    Column(Modifier.fillMaxWidth()) {
        SkeletonBlock(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth(0.3f).height(20.dp),
        )
        SkeletonBlock(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(192.dp),
            MaterialTheme.shapes.extraLarge,
        )
        SkeletonBlock(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth(0.25f).height(20.dp),
        )
        repeat(3) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(columns) {
                    SkeletonBlock(Modifier.weight(1f).height(88.dp), MaterialTheme.shapes.large)
                }
            }
        }
    }
}

/**
 * Fixed channels, then pinned categories. Spread across the width on phones when everything fits,
 * otherwise a scrollable row.
 */
@Composable
private fun ChannelRow(
    pinned: List<HomeCategory>,
    onChannelClick: (HomeChannel) -> Unit,
    onPinnedClick: (String) -> Unit,
) {
    val channels = listOf(
        HomeChannel.Rankings to R.string.discover_channel_rankings,
        HomeChannel.Random to R.string.discover_channel_random,
        HomeChannel.Games to R.string.discover_channel_games,
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val count = channels.size + pinned.size
        val sidePadding = 12.dp
        val minGap = 4.dp
        val needed = HomeChannelDefaults.ItemWidth * count + minGap * (count - 1) + sidePadding * 2
        val compact = maxWidth < 600.dp
        val arrangement = when {
            compact && needed <= maxWidth -> Arrangement.SpaceBetween
            compact -> Arrangement.spacedBy(minGap)
            else -> Arrangement.spacedBy(16.dp)
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = maxWidth)
                .padding(start = sidePadding, end = sidePadding, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = arrangement,
        ) {
            channels.forEach { (channel, label) ->
                HomeChannelButton(
                    channel = channel,
                    label = stringResource(label),
                    onClick = { onChannelClick(channel) },
                )
            }
            pinned.forEach { category ->
                CategoryChannelButton(
                    label = category.name,
                    imageUrl = category.thumbUrl,
                    onClick = { onPinnedClick(category.name) },
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    row: List<CategoryCell>,
    columns: Int,
    onRecommendClick: () -> Unit,
    onCategoryClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        row.forEach { cell ->
            when (cell) {
                CategoryCell.Recommend -> CategoryTile(
                    name = stringResource(R.string.discover_category_recommend),
                    imageUrl = null,
                    onClick = onRecommendClick,
                    modifier = Modifier.weight(1f),
                )
                is CategoryCell.Server -> CategoryTile(
                    name = cell.category.name,
                    imageUrl = cell.category.thumbUrl,
                    onClick = { onCategoryClick(cell.category.name) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

private val CaptionScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.72f),
)

/** Recommendation carousel (board 04): 208dp large items, captions fade out as items shrink. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecommendCarousel(
    items: List<CarouselComic>,
    onComicClick: (String) -> Unit,
) {
    val state = rememberCarouselState { items.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 208.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .height(192.dp),
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { index ->
        val comic = items[index]
        Box(
            Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.extraLarge)
                .clickable(role = Role.Button) { onComicClick(comic.id) },
        ) {
            CarouselArtwork(comic = comic, index = index, modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val info = carouselItemDrawInfo
                        val range = info.maxSize - info.minSize
                        alpha = if (range <= 0f) 1f else ((info.size - info.minSize) / range).coerceIn(0f, 1f)
                    }
                    .background(CaptionScrim)
                    .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 12.dp),
            ) {
                Text(
                    text = comic.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (comic.author.isNotEmpty()) {
                    Text(
                        text = comic.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.86f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Cover art, or a theme-tinted gradient while there is no picture (sample data, failed loads). */
@Composable
private fun CarouselArtwork(comic: CarouselComic, index: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (from, to) = when (index % 3) {
        0 -> colors.primary to colors.tertiaryContainer
        1 -> colors.tertiary to colors.primaryContainer
        else -> colors.secondary to colors.secondaryContainer
    }
    Box(modifier.background(Brush.linearGradient(listOf(from, to)))) {
        if (!comic.coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model = comic.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun DiscoverScreenPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        DiscoverScreen(
            content = rememberSampleDiscoverContent(),
            showProfileEntry = true,
            onSearchClick = {},
            onProfileClick = {},
            onChannelClick = {},
            onCategoryClick = {},
            onCollectionsClick = {},
            onAllCategoriesClick = {},
            onComicClick = {},
        )
    }
}
