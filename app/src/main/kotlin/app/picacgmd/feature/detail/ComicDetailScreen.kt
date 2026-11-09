package app.picacgmd.feature.detail

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.ComicCover
import app.picacgmd.core.designsystem.component.ComicCoverCard
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.designsystem.component.MetaIcons
import app.picacgmd.core.designsystem.component.MetaInfo
import app.picacgmd.core.designsystem.component.SectionHeader
import app.picacgmd.core.designsystem.component.ShapedAvatar
import app.picacgmd.core.designsystem.component.SoftBackdrop
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.withTabularFigures
import app.picacgmd.core.domain.repository.ReadingProgress
import app.picacgmd.core.domain.repository.containsKeyword
import app.picacgmd.core.domain.repository.uploader
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Ep
import app.picacgmd.core.network.UserProfile
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.comics.formatCount
import app.picacgmd.feature.comics.readMarkText
import app.picacgmd.feature.comics.rememberImageUrl
import app.picacgmd.feature.comics.rememberReadMarks
import app.picacgmd.feature.comments.CommentRow
import kotlinx.coroutines.flow.collectLatest

/** Chapter rows shown before 「展开全部」 (board 10). */
private const val COLLAPSED_CHAPTER_COUNT = 8

/**
 * Boards 10–11: comic detail. Wire as `composable<ComicDetailRoute> { ComicDetailScreen(comicId = it.comicId, …) }`.
 *
 * @param onRead open the reader at chapter [epOrder] (1-based) and picture index [page] (0-based).
 * @param onDownloadChapters enqueue the chosen chapters (all chapters are pre-selected in the picker).
 * @param onTeamClick 汉化组 chip; the list API filters by `chineseTeam`.
 * @param onCreatorClick uploader row: the uploader's comics, listed by their [id] and titled with their [name].
 */
@Composable
fun ComicDetailScreen(
    comicId: String,
    onBack: () -> Unit,
    onRead: (comicId: String, epOrder: Int, page: Int) -> Unit,
    onDownloadChapters: (ComicInfo, List<Ep>) -> Unit,
    onCategoryClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onTeamClick: (String) -> Unit,
    onCreatorClick: (id: String, name: String) -> Unit,
    onComicClick: (String) -> Unit,
    onOpenComments: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "detail:$comicId") { ComicDetailViewModel(container, comicId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val hiddenKeywords by viewModel.hiddenKeywords.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failedTemplate = stringResource(R.string.detail_action_failed, "%s")
    val blockedTemplate = stringResource(R.string.detail_keyword_blocked_message, "%s")
    val unblockedTemplate = stringResource(R.string.detail_keyword_unblocked_message, "%s")
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(failedTemplate.replace("%s", it)) }
    }
    LaunchedEffect(viewModel) {
        // The newest change replaces the one on screen, so a run of taps doesn't queue up a run of snackbars.
        viewModel.keywordChanges.collectLatest { change ->
            snackbar.showSnackbar((if (change.blocked) blockedTemplate else unblockedTemplate).replace("%s", change.keyword))
        }
    }
    var downloadPicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        val comic = state.comic
        when {
            comic == null && state.error != null -> Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                DetailTopBar(title = null, onBack = onBack, solid = true)
                ErrorState(onRetry = viewModel::load, description = state.error)
            }
            comic == null -> Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                DetailTopBar(title = null, onBack = onBack, solid = true)
                LoadingState()
            }
            else -> DetailBody(
                comic = comic,
                state = state,
                library = library,
                onBack = onBack,
                onRead = { ep, page -> onRead(comic.id, ep, page) },
                onLike = viewModel::toggleLike,
                onFavourite = viewModel::toggleFavourite,
                onSubscribe = viewModel::toggleSubscribe,
                onDownload = { downloadPicker = true },
                onCategoryClick = onCategoryClick,
                onTagClick = onTagClick,
                onAuthorClick = onAuthorClick,
                onTeamClick = onTeamClick,
                onCreatorClick = onCreatorClick,
                onComicClick = onComicClick,
                onOpenComments = { onOpenComments(comic.id) },
                onRetryEps = viewModel::retryEps,
                hiddenKeywords = hiddenKeywords,
                onToggleKeyword = viewModel::toggleHiddenKeyword,
            )
        }
    }

    val comic = state.comic
    if (downloadPicker && comic != null) {
        DownloadPickerSheet(
            eps = state.eps.orEmpty(),
            onDismiss = { downloadPicker = false },
            onConfirm = { downloadPicker = false; onDownloadChapters(comic, it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(title: String?, onBack: () -> Unit, solid: Boolean, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { if (title != null) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            app.picacgmd.core.designsystem.component.BackButton(onBack)
        },
        actions = { actions() },
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (solid) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailBody(
    comic: ComicInfo,
    state: DetailState,
    library: LibraryState,
    onBack: () -> Unit,
    onRead: (Int, Int) -> Unit,
    onLike: () -> Unit,
    onFavourite: () -> Unit,
    onSubscribe: () -> Unit,
    onDownload: () -> Unit,
    onCategoryClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onTeamClick: (String) -> Unit,
    onCreatorClick: (String, String) -> Unit,
    onComicClick: (String) -> Unit,
    onOpenComments: () -> Unit,
    onRetryEps: () -> Unit,
    hiddenKeywords: List<String>,
    onToggleKeyword: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val clipboard = LocalClipboardManager.current
    var showAllChapters by rememberSaveable { mutableStateOf(false) }
    var chaptersReversed by rememberSaveable { mutableStateOf(false) }
    var blockMode by rememberSaveable { mutableStateOf(false) }
    // While chips are being toggled the notice above them stays as it was, so the next chip doesn't move
    // away from under the finger; it catches up once 屏蔽模式 is off again.
    var noticeDuringBlockMode by rememberSaveable { mutableStateOf(false) }
    val showHiddenNotice = if (blockMode) noticeDuringBlockMode else state.hiddenByRule
    // Outside the LazyColumn: the marks keep one holder however often the row scrolls away.
    val readMarks = rememberReadMarks(remember(state.recommendations) { state.recommendations.map(ComicSimple::id) })
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
            item(key = "hero") { Hero(comic, onAuthorClick) }
            if (showHiddenNotice) item(key = "hidden") { HiddenNotice() }
            item(key = "actions") {
                ActionRow(
                    comic = comic,
                    library = library,
                    onLike = onLike,
                    onFavourite = onFavourite,
                    onSubscribe = onSubscribe,
                    onDownload = onDownload,
                )
            }
            item(key = "meta") {
                MetaChips(
                    comic = comic,
                    hiddenKeywords = hiddenKeywords,
                    blockMode = blockMode,
                    onBlockModeChange = { on ->
                        if (on) noticeDuringBlockMode = state.hiddenByRule
                        blockMode = on
                    },
                    onToggleKeyword = onToggleKeyword,
                    onCategoryClick = onCategoryClick,
                    onTagClick = onTagClick,
                    onAuthorClick = onAuthorClick,
                    onTeamClick = onTeamClick,
                    onCreatorClick = onCreatorClick,
                )
            }
            if (comic.description.isNotBlank()) item(key = "desc") { Description(comic.description) }
            item(key = "eps-header") {
                SectionHeader(title = stringResource(R.string.detail_chapters, state.eps?.size ?: comic.epsCount))
            }
            chapters(
                state = state,
                library = library,
                showAll = showAllChapters,
                reversed = chaptersReversed,
                onRead = onRead,
                onRetry = onRetryEps,
                onToggleShowAll = { showAllChapters = !showAllChapters },
                onToggleReversed = { chaptersReversed = !chaptersReversed },
            )
            if (state.recommendations.isNotEmpty()) {
                item(key = "rec-header") { SectionHeader(title = stringResource(R.string.detail_recommend)) }
                item(key = "rec") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.recommendations, key = { it.id }) { rec ->
                            val mark = readMarkText(readMarks.chapterOf(rec.id), rec.epsCount)
                            ComicCoverCard(
                                title = rec.title,
                                coverUrl = rememberImageUrl(rec.thumb),
                                onClick = { onComicClick(rec.id) },
                                modifier = Modifier.width(112.dp),
                                progressLabel = mark?.label,
                                progressDetail = mark?.detail,
                                progressDetailShort = mark?.detailShort,
                            )
                        }
                    }
                }
            }
            item(key = "comments-header") {
                SectionHeader(
                    title = stringResource(R.string.detail_comments, comic.commentsCount),
                    actionLabel = stringResource(R.string.detail_comments_all),
                    onActionClick = onOpenComments,
                )
            }
            item(key = "comments") {
                Column(Modifier.clickable(onClick = onOpenComments)) {
                    if (state.commentsLoaded && state.comments.isEmpty()) {
                        Text(
                            stringResource(R.string.detail_comments_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    state.comments.forEach { comment -> CommentRow(comment = comment, compact = true) }
                }
            }
        }

        // Top bar fades from transparent-over-hero to solid once the hero scrolls away.
        Column(Modifier.fillMaxWidth()) {
            Surface(color = if (collapsed) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent) {
                Column {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    DetailTopBar(
                        title = if (collapsed) comic.title else null,
                        onBack = onBack,
                        solid = collapsed,
                        actions = {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(comic.title)) }) {
                                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.detail_copy_title))
                            }
                        },
                    )
                }
            }
        }

        ReadButton(
            progress = library.progress,
            firstEp = state.eps?.firstOrNull()?.order ?: 1,
            onRead = onRead,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Blurred cover wash behind a crisp cover and the title block: the page's one dramatic moment. */
@Composable
private fun Hero(comic: ComicInfo, onAuthorClick: (String) -> Unit) {
    val cover = rememberImageUrl(comic.thumb)
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth()) {
        SoftBackdrop(cover, Modifier.matchParentSize())
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to surface.copy(alpha = 0.55f), 0.65f to surface.copy(alpha = 0.88f), 1f to surface)),
        )
        Column {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(64.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ComicCover(
                    coverUrl = cover,
                    contentDescription = comic.title,
                    modifier = Modifier.width(124.dp),
                    shape = MaterialTheme.shapes.large,
                    badge = if (comic.finished) stringResource(R.string.comics_finished) else null,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        comic.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (comic.author.isNotBlank()) {
                        Text(
                            comic.author,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onAuthorClick(comic.author) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetaInfo(MetaIcons.Likes, formatCount(comic.likesCount.toLong()))
                        MetaInfo(MetaIcons.Views, formatCount(comic.viewsCount))
                        MetaInfo(MetaIcons.Pages, stringResource(R.string.comics_pages_eps, comic.pagesCount, comic.epsCount))
                    }
                    comic.updatedAt?.take(10)?.let {
                        Text(
                            stringResource(R.string.detail_updated, it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenNotice() {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.VisibilityOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(stringResource(R.string.detail_hidden_by_rule), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionRow(
    comic: ComicInfo,
    library: LibraryState,
    onLike: () -> Unit,
    onFavourite: () -> Unit,
    onSubscribe: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionToggle(
            on = comic.isLiked,
            iconOn = Icons.Filled.Favorite, iconOff = Icons.Outlined.FavoriteBorder,
            label = if (comic.isLiked) stringResource(R.string.detail_liked) else stringResource(R.string.detail_like),
            onClick = onLike,
        )
        ActionToggle(
            on = comic.isFavourite,
            iconOn = Icons.Filled.Bookmark, iconOff = Icons.Outlined.BookmarkBorder,
            label = if (comic.isFavourite) stringResource(R.string.detail_favourited) else stringResource(R.string.detail_favourite),
            onClick = onFavourite,
        )
        ActionToggle(
            on = library.subscribed,
            iconOn = Icons.Filled.NotificationsActive, iconOff = Icons.Outlined.NotificationsNone,
            label = if (library.subscribed) stringResource(R.string.detail_subscribed) else stringResource(R.string.detail_subscribe),
            onClick = onSubscribe,
        )
        ActionToggle(
            on = false,
            iconOn = Icons.Outlined.Download, iconOff = Icons.Outlined.Download,
            label = stringResource(R.string.detail_download),
            onClick = onDownload,
            enabled = comic.allowDownload,
        )
    }
}

@Composable
private fun ActionToggle(
    on: Boolean,
    iconOn: ImageVector,
    iconOff: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
        androidx.compose.material3.FilledIconToggleButton(
            checked = on,
            onCheckedChange = { onClick() },
            enabled = enabled,
            shapes = IconButtonDefaults.toggleableShapes(),
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = colors.surfaceContainerHigh,
                contentColor = colors.onSurfaceVariant,
                checkedContainerColor = colors.primaryContainer,
                checkedContentColor = colors.onPrimaryContainer,
            ),
            modifier = Modifier.size(52.dp),
        ) { Icon(if (on) iconOn else iconOff, label) }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** One labelled row of keyword chips: the categories or the tags. */
private class ChipGroup(@StringRes val label: Int, val names: List<String>, val onOpen: (String) -> Unit)

/**
 * Author and team chips, the uploader, then the categories and tags. Those two groups share 屏蔽模式,
 * switched on the first label: while it is on, a tap puts the word into the hidden keywords or takes it out again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(
    comic: ComicInfo,
    hiddenKeywords: List<String>,
    blockMode: Boolean,
    onBlockModeChange: (Boolean) -> Unit,
    onToggleKeyword: (String) -> Unit,
    onCategoryClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onTeamClick: (String) -> Unit,
    onCreatorClick: (String, String) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val groups = listOfNotNull(
        ChipGroup(R.string.detail_categories, comic.categories, onCategoryClick).takeIf { comic.categories.isNotEmpty() },
        ChipGroup(R.string.detail_tags, comic.tags, onTagClick).takeIf { comic.tags.isNotEmpty() },
    )
    val uploader = comic.uploader()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (comic.author.isNotBlank()) {
                AssistChip(onClick = { onAuthorClick(comic.author) }, label = { Text(comic.author) }, leadingIcon = { Icon(Icons.Outlined.Person, stringResource(R.string.detail_author)) })
            }
            if (comic.chineseTeam.isNotBlank()) {
                AssistChip(onClick = { onTeamClick(comic.chineseTeam) }, label = { Text(comic.chineseTeam) }, leadingIcon = { Icon(Icons.Outlined.Translate, stringResource(R.string.detail_team)) })
            }
        }
        if (uploader != null) {
            UploaderRow(
                uploader = uploader,
                onClick = { onCreatorClick(uploader.id, uploader.name) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        groups.forEachIndexed { index, group ->
            val first = index == 0
            ChipGroupLabel(
                stringResource(group.label),
                trailing = if (first) {
                    { BlockModeToggle(blockMode, onBlockModeChange) }
                } else {
                    null
                },
            )
            if (first) {
                AnimatedVisibility(
                    visible = blockMode,
                    enter = fadeIn(motion.fastEffectsSpec()) + expandVertically(motion.fastSpatialSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
                ) {
                    Text(
                        stringResource(R.string.detail_block_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.names.forEach { name ->
                    KeywordChip(
                        text = name,
                        blocked = hiddenKeywords.containsKeyword(name),
                        blockMode = blockMode,
                        onClick = { if (blockMode) onToggleKeyword(name) else group.onOpen(name) },
                    )
                }
            }
        }
    }
}

/**
 * The uploader: avatar, name and level over 「上传者」 and their title. A tap lists their comics, the
 * same list the 骑士榜 opens. TalkBack reads the row as one button.
 */
@Composable
private fun UploaderRow(uploader: UserProfile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.detail_uploader_description, uploader.name)
    val role = listOfNotNull(stringResource(R.string.detail_uploader), uploader.title.ifBlank { null }).joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(start = 12.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShapedAvatar(imageUrl = rememberImageUrl(uploader.avatar), initial = uploader.name.trim().take(1), size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    uploader.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (uploader.level > 0) {
                    Text(
                        stringResource(R.string.detail_uploader_level, uploader.level),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSecondaryContainer,
                        maxLines = 1,
                        modifier = Modifier
                            .background(colors.secondaryContainer, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                role,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun ChipGroupLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun BlockModeToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(stringResource(R.string.detail_block_mode)) },
        leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

/**
 * A category or tag. A blocked one carries ⊘ wherever it shows. In 屏蔽模式 every chip gets an icon,
 * ＋ to block or ⊘ to unblock, so toggling one never changes its width and the chips around it stay put.
 */
@Composable
private fun KeywordChip(text: String, blocked: Boolean, blockMode: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (blockMode) {
        val state = stringResource(if (blocked) R.string.detail_keyword_blocked else R.string.detail_keyword_not_blocked)
        FilterChip(
            selected = blocked,
            onClick = onClick,
            label = { Text(text) },
            leadingIcon = {
                Icon(if (blocked) Icons.Outlined.Block else Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colors.errorContainer,
                selectedLabelColor = colors.onErrorContainer,
                selectedLeadingIconColor = colors.onErrorContainer,
            ),
            modifier = Modifier.semantics { stateDescription = state },
        )
    } else {
        SuggestionChip(
            onClick = onClick,
            label = { Text(text) },
            icon = if (blocked) {
                {
                    Icon(
                        Icons.Outlined.Block,
                        contentDescription = stringResource(R.string.detail_keyword_blocked),
                        modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                        tint = colors.error,
                    )
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
            .clickable { expanded = !expanded }
            .padding(14.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(if (expanded) R.string.detail_collapse else R.string.detail_expand),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Chapter rows as lazy items, so a comic with hundreds of chapters stays light once expanded. */
private fun LazyListScope.chapters(
    state: DetailState,
    library: LibraryState,
    showAll: Boolean,
    reversed: Boolean,
    onRead: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    onToggleShowAll: () -> Unit,
    onToggleReversed: () -> Unit,
) {
    val eps = state.eps
    if (eps == null) {
        item(key = "eps-status") {
            if (state.epsError != null) ErrorState(onRetry = onRetry, description = state.epsError) else LoadingState()
        }
        return
    }
    val progress = library.progress
    val ordered = if (reversed) eps.asReversed() else eps
    val visible = if (showAll) ordered else collapsedChapters(ordered, progress?.epOrder)
    // With a download column in every row, the ✓ marks and the download marks each line up.
    val hasDownloads = library.downloadedEpIds.isNotEmpty()
    items(visible, key = { "ep-${it.id}" }) { ep ->
        val resumeAt = progress?.takeIf { it.epOrder == ep.order }
        ChapterRow(
            ep = ep,
            progress = progress,
            currentEpPages = library.currentEpPages,
            downloaded = ep.id in library.downloadedEpIds,
            showDownloadColumn = hasDownloads,
            onClick = { onRead(ep.order, (resumeAt?.pictureIndex ?: 1) - 1) },
        )
    }
    item(key = "eps-actions") {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onToggleReversed) { Text(stringResource(R.string.detail_chapters_reverse)) }
            if (ordered.size > COLLAPSED_CHAPTER_COUNT) {
                TextButton(onClick = onToggleShowAll) {
                    Text(if (showAll) stringResource(R.string.detail_collapse) else stringResource(R.string.detail_chapters_expand, ordered.size))
                }
            }
        }
    }
}

/** The rows shown before 「展开全部」: from the top, or around the chapter being read so it is always in view. */
private fun collapsedChapters(ordered: List<Ep>, lastRead: Int?): List<Ep> {
    if (ordered.size <= COLLAPSED_CHAPTER_COUNT) return ordered
    val current = ordered.indexOfFirst { it.order == lastRead }
    val start = (current - 2).coerceIn(0, ordered.size - COLLAPSED_CHAPTER_COUNT)
    return ordered.subList(start, start + COLLAPSED_CHAPTER_COUNT)
}

/** Board 10 chapter row: 已读 is a ✓ and a quieter title, 当前 a tinted band with 看到 14/22, 已下载 a green mark. */
@Composable
private fun ChapterRow(
    ep: Ep,
    progress: ReadingProgress?,
    currentEpPages: Int?,
    downloaded: Boolean,
    showDownloadColumn: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // History keeps only the last position, so every chapter before it counts as read.
    val read = progress != null && ep.order < progress.epOrder
    val position = progress?.takeIf { it.epOrder == ep.order }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (position != null) colors.secondaryContainer else Color.Transparent,
        contentColor = if (position != null) colors.onSecondaryContainer else colors.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ep.title.ifBlank { ep.order.toString() },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (read) FontWeight.Normal else null,
                color = if (read) colors.onSurfaceVariant else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (position != null) {
                Text(
                    if (currentEpPages != null) {
                        stringResource(R.string.detail_chapter_progress, position.pictureIndex.coerceAtMost(currentEpPages), currentEpPages)
                    } else {
                        stringResource(R.string.detail_chapter_progress_page, position.pictureIndex)
                    },
                    style = MaterialTheme.typography.labelMedium.withTabularFigures(),
                )
            }
            if (read) {
                Icon(Icons.Outlined.Check, stringResource(R.string.viewed_mark), Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
            if (showDownloadColumn) {
                Box(Modifier.size(20.dp)) {
                    if (downloaded) {
                        Icon(Icons.Outlined.DownloadDone, stringResource(R.string.detail_chapter_downloaded), tint = PikapikaTheme.extendedColors.success)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadButton(
    progress: app.picacgmd.core.domain.repository.ReadingProgress?,
    firstEp: Int,
    onRead: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Button(
            onClick = { if (progress != null) onRead(progress.epOrder, progress.pictureIndex - 1) else onRead(firstEp, 0) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null)
            Spacer(Modifier.width(10.dp))
            Text(
                if (progress != null) stringResource(R.string.detail_continue_reading, progress.epTitle.ifBlank { progress.epOrder.toString() })
                else stringResource(R.string.detail_start_reading),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DownloadPickerSheet(eps: List<Ep>, onDismiss: () -> Unit, onConfirm: (List<Ep>) -> Unit) {
    var chosen by remember(eps) { mutableStateOf(eps.map(Ep::id).toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.detail_download_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { chosen = if (chosen.size == eps.size) emptySet() else eps.map(Ep::id).toSet() }) {
                Text(stringResource(R.string.comics_select_all))
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(eps, key = { it.id }) { ep ->
                ListItem(
                    headlineContent = { Text(ep.title.ifBlank { ep.order.toString() }) },
                    leadingContent = { Checkbox(checked = ep.id in chosen, onCheckedChange = null) },
                    modifier = Modifier.clickable { chosen = if (ep.id in chosen) chosen - ep.id else chosen + ep.id },
                )
            }
        }
        FilledTonalButton(
            onClick = { onConfirm(eps.filter { it.id in chosen }) },
            enabled = chosen.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        ) { Text(stringResource(R.string.detail_download_confirm, chosen.size)) }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
