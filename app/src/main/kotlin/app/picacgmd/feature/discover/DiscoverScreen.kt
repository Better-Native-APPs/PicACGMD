package app.picacgmd.feature.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

/**
 * 发现 (board 04 with the §0 changes): search bar with the 「我的」 avatar, the three fixed channels
 * 排行榜 / 随机 / 游戏专区 followed by pinned categories and the category grid, starting with 全分类.
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
    onAllComicsClick: () -> Unit,
    onManageCategoriesClick: () -> Unit,
    modifier: Modifier = Modifier,
    failure: LoadState.Failed? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    avatarUrl: String? = null,
    avatarInitial: String? = null,
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
                        imageUrl = avatarUrl,
                        initial = avatarInitial,
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
                LazyColumn(Modifier.fillMaxSize()) {
                    // Fixed channels need no data, so they stay usable while loading or offline.
                    item(key = "channels", contentType = "channels") {
                        ChannelRow(
                            pinned = content?.pinnedCategories.orEmpty(),
                            onChannelClick = onChannelClick,
                            onPinnedClick = onCategoryClick,
                            onAllComicsClick = onAllComicsClick,
                        )
                    }
                    item(key = "category-header", contentType = "header") {
                        SectionHeader(
                            title = stringResource(R.string.discover_section_categories),
                            actionLabel = stringResource(R.string.discover_action_manage_categories),
                            onActionClick = onManageCategoriesClick,
                        )
                    }
                    // 全分类 is local, so it remains available before categories load or after a failure.
                    itemsIndexed(
                        items = (content?.categories ?: listOf(AllComicsHomeCategory)).chunked(columns),
                        key = { index, _ -> "category-row-$columns-$index" },
                        contentType = { _, _ -> "category-row" },
                    ) { _, row ->
                        CategoryRow(
                            row = row,
                            columns = columns,
                            onCategoryClick = onCategoryClick,
                            onAllComicsClick = onAllComicsClick,
                        )
                    }
                    when {
                        content != null -> Unit
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

/** Category grid placeholders shown on the very first load. */
@Composable
private fun DiscoverSkeleton(columns: Int) {
    Column(Modifier.fillMaxWidth()) {
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
    onAllComicsClick: () -> Unit,
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
                    onClick = if (category.isAllComics) onAllComicsClick else ({ onPinnedClick(category.name) }),
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    row: List<HomeCategory>,
    columns: Int,
    onCategoryClick: (String) -> Unit,
    onAllComicsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        row.forEach { category ->
            CategoryTile(
                name = category.name,
                imageUrl = category.thumbUrl,
                onClick = if (category.isAllComics) onAllComicsClick else ({ onCategoryClick(category.name) }),
                modifier = Modifier.weight(1f),
            )
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
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
            onAllComicsClick = {},
            onManageCategoriesClick = {},
        )
    }
}
