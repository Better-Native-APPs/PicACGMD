package app.picacgmd.feature.detail

import app.picacgmd.core.designsystem.window.secureModalBottomSheetProperties
import app.picacgmd.core.designsystem.window.securePopupProperties
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.DoNotDisturbOff
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.nativeClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.renderCopyName
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppTextButton
import app.picacgmd.core.designsystem.component.ComicCover
import app.picacgmd.core.designsystem.component.ComicCoverCard
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.designsystem.component.MetaIcons
import app.picacgmd.core.designsystem.component.MetaInfo
import app.picacgmd.core.designsystem.component.SectionHeader
import app.picacgmd.core.designsystem.component.ShapedAvatar
import app.picacgmd.core.designsystem.component.SoftBackdrop
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.withTabularFigures
import app.picacgmd.core.domain.download.DownloadManagerOwner
import app.picacgmd.core.domain.repository.ReadingProgress
import app.picacgmd.core.domain.repository.containsKeyword
import app.picacgmd.core.domain.repository.uploader
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.Ep
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.comics.formatCount
import app.picacgmd.feature.comics.readMarkText
import app.picacgmd.feature.comics.rememberImageUrl
import app.picacgmd.feature.comics.rememberReadMarks
import app.picacgmd.feature.comments.CommentRow
import kotlinx.coroutines.flow.collectLatest

/** Chapter rows shown before 「展开全部」 (board 10). */
private const val COLLAPSED_CHAPTER_COUNT = 8

/** Lifts a message clear of the read button: the button's height and its margin, less the message's own margin. */
private val SnackbarAboveReadButton = 64.dp

/**
 * Boards 10–11: comic detail. Wire as `composable<ComicDetailRoute> { ComicDetailScreen(comicId = it.comicId, …) }`.
 *
 * @param onRead open the reader at chapter [epOrder] (1-based) and picture index [page] (0-based).
 * @param onDownloadChapters enqueue the chosen chapters (all chapters are pre-selected in the picker).
 * @param onTeamClick 汉化 row; the list API filters by `chineseTeam`.
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
    val downloads = (LocalContext.current.applicationContext as DownloadManagerOwner).downloadManager
    val viewModel = viewModel(key = "detail:$comicId") { ComicDetailViewModel(container, comicId, downloads) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences by container.settingsRepository.settings.collectAsStateWithLifecycle(null)
    val library by viewModel.library.collectAsStateWithLifecycle()
    val hiddenKeywords by viewModel.hiddenKeywords.collectAsStateWithLifecycle()
    val showTagFavourites by viewModel.showTagFavourites.collectAsStateWithLifecycle()
    val favouriteTags by viewModel.favouriteTags.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failedTemplate = stringResource(R.string.detail_action_failed, "%s")
    val blockedTemplate = stringResource(R.string.detail_keyword_blocked_message, "%s")
    val unblockedTemplate = stringResource(R.string.detail_keyword_unblocked_message, "%s")
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(failedTemplate.replace("%s", it)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { snackbar.showSnackbar(it) }
    }
    val undoLabel = stringResource(R.string.detail_keyword_undo)
    LaunchedEffect(viewModel) {
        // The newest change replaces the one on screen, so a run of changes doesn't queue up a run of snackbars.
        viewModel.keywordChanges.collectLatest { change ->
            val result = snackbar.showSnackbar(
                message = (if (change.blocked) blockedTemplate else unblockedTemplate).replace("%s", change.keyword),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoKeywordChange(change)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.tagFavouriteChanges.collectLatest { change ->
            snackbar.showSnackbar(if (change.added) "已收藏标签「${change.tag}」" else "已移出标签收藏「${change.tag}」")
        }
    }
    var downloadPicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        // Above the read button and the system bar, so 「撤销」 can be reached and reading stays one tap away.
        snackbarHost = {
            SnackbarHost(
                snackbar,
                Modifier.navigationBarsPadding().padding(bottom = if (state.comic != null) SnackbarAboveReadButton else 0.dp),
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        val comic = state.comic
        when {
            comic == null && state.error != null -> Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                DetailTopBar(onBack = onBack, solid = true)
                ErrorState(onRetry = viewModel::load, description = state.error)
            }
            comic == null -> Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                DetailTopBar(onBack = onBack, solid = true)
                LoadingState()
            }
            else -> DetailBody(
                comic = comic,
                preferences = preferences ?: AppSettings.DEFAULT,
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
                showTagFavourites = showTagFavourites,
                favouriteTags = favouriteTags,
                onToggleTagFavourite = viewModel::toggleTagFavourite,
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
private fun DetailTopBar(onBack: () -> Unit, solid: Boolean, title: @Composable () -> Unit = {}) {
    TopAppBar(
        title = title,
        navigationIcon = {
            app.picacgmd.core.designsystem.component.BackButton(onBack)
        },
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (solid) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        ),
    )
}

/**
 * The page below the loading state, top to bottom: cover and title, the four actions, the description,
 * the people behind the comic, categories and tags, chapters, recommendations, comments.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DetailBody(
    comic: ComicInfo,
    preferences: AppSettings,
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
    showTagFavourites: Boolean,
    favouriteTags: Set<String>,
    onToggleTagFavourite: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val topBarContainerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (collapsed) 1f else 0f),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "detailTopBarContainer",
    )
    val clipboard = LocalClipboard.current
    val copyText = remember(preferences.copyNameTemplate, comic.author, comic.title, comic.id) {
        renderCopyName(preferences.copyNameTemplate, comic.author, comic.title, comic.id)
    }
    val copyName = { clipboard.nativeClipboardManager.setPrimaryClip(ClipData.newPlainText("plain text", copyText)) }
    var showAllChapters by rememberSaveable { mutableStateOf(false) }
    var chaptersReversed by rememberSaveable { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    // Outside the LazyColumn: the marks keep one holder however often the row scrolls away.
    val readMarks = rememberReadMarks(remember(state.recommendations) { state.recommendations.map(ComicSimple::id) })
    val cover = rememberImageUrl(comic.thumb)
    var coverOpen by rememberSaveable { mutableStateOf(false) }
    // The page stays where it is under the opened cover, but only the cover can be reached and read out.
    val underCover = if (coverOpen) Modifier.clearAndSetSemantics { } else Modifier
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().then(underCover), contentPadding = PaddingValues(bottom = 112.dp)) {
            item(key = "hero") { Hero(comic, cover, copyText, preferences.confirmCopy, copyName) { coverOpen = true } }
            // Always in the list, so blocking or unblocking a word makes room for the notice instead of jumping.
            item(key = "hidden") {
                AnimatedVisibility(
                    visible = state.hiddenByRule,
                    enter = fadeIn(motion.fastEffectsSpec()) + expandVertically(motion.fastSpatialSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
                ) { HiddenNotice() }
            }
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
            if (comic.description.isNotBlank()) item(key = "desc") { Description(comic.description) }
            if (comic.author.isNotBlank() || comic.chineseTeam.isNotBlank() || comic.uploader() != null) {
                item(key = "people") {
                    People(
                        comic = comic,
                        showAuthorSearch = preferences.showAuthorSearch,
                        onAuthorClick = onAuthorClick,
                        onTeamClick = onTeamClick,
                        onCreatorClick = onCreatorClick,
                    )
                }
            }
            if (comic.categories.isNotEmpty() || comic.tags.isNotEmpty()) {
                item(key = "keywords") {
                    Keywords(
                        comic = comic,
                        hiddenKeywords = hiddenKeywords,
                        showTagFavourites = showTagFavourites,
                        favouriteTags = favouriteTags,
                        onCategoryClick = onCategoryClick,
                        onTagClick = onTagClick,
                        onToggleKeyword = onToggleKeyword,
                        onToggleTagFavourite = onToggleTagFavourite,
                    )
                }
            }
            item(key = "eps-header") {
                ChaptersHeader(
                    count = state.eps?.size ?: comic.epsCount,
                    reversed = chaptersReversed,
                    sortable = (state.eps?.size ?: 0) > 1,
                    onToggleOrder = { chaptersReversed = !chaptersReversed },
                )
            }
            chapters(
                state = state,
                library = library,
                showAll = showAllChapters,
                reversed = chaptersReversed,
                onRead = onRead,
                onRetry = onRetryEps,
                onToggleShowAll = { showAllChapters = !showAllChapters },
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
        Column(Modifier.fillMaxWidth().then(underCover)) {
            Surface(color = topBarContainerColor) {
                Column {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    DetailTopBar(onBack = onBack, solid = false) {
                        if (collapsed) {
                            CopyableTitle(
                                title = comic.title,
                                copyText = copyText,
                                confirmCopy = preferences.confirmCopy,
                                onCopy = copyName,
                                style = LocalTextStyle.current,
                                maxLines = 1,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        ReadButton(
            progress = library.progress,
            firstEp = state.eps?.firstOrNull()?.order ?: 1,
            eps = state.eps.orEmpty(),
            currentEpPages = library.currentEpPages,
            downloadedEpIds = library.downloadedEpIds,
            onRead = onRead,
            modifier = Modifier.align(Alignment.BottomCenter).then(underCover),
        )

        CoverViewer(visible = coverOpen, coverUrl = cover, title = comic.title, onClose = { coverOpen = false })
    }
}

/** Blurred cover wash behind a crisp cover and the title block: the page's one dramatic moment. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(
    comic: ComicInfo,
    cover: String?,
    copyText: String,
    confirmCopy: Boolean,
    onCopy: () -> Unit,
    onCoverClick: () -> Unit,
) {
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
                val coverShape = MaterialTheme.shapes.large
                val viewCover = stringResource(R.string.detail_cover_view)
                ComicCover(
                    coverUrl = cover,
                    // As a button the cover says what it does; the comic's name is the title beside it.
                    contentDescription = if (cover == null) comic.title else null,
                    modifier = Modifier
                        .width(124.dp)
                        .then(
                            if (cover == null) Modifier else Modifier
                                .clip(coverShape)
                                .clickable(role = Role.Button, onClick = onCoverClick)
                                .semantics { contentDescription = viewCover },
                        ),
                    shape = coverShape,
                    badge = if (comic.finished) stringResource(R.string.comics_finished) else null,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CopyableTitle(
                        title = comic.title,
                        copyText = copyText,
                        confirmCopy = confirmCopy,
                        onCopy = onCopy,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 4,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

/**
 * The comic's name. A long press copies it in the form set by the copy template: with 复制前确认 on it
 * opens a menu whose one item shows what will be copied, and tapping that copies; with it off the long
 * press copies at once.
 */
@Composable
private fun CopyableTitle(
    title: String,
    copyText: String,
    confirmCopy: Boolean,
    onCopy: () -> Unit,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val label = stringResource(R.string.detail_copy_name)
    Box(modifier) {
        Text(
            title,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .outset(horizontal = 8.dp, vertical = 4.dp)
                .longPressable(label = label, held = menu) { if (confirmCopy) menu = true else onCopy() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            properties = securePopupProperties(focusable = true),
        ) {
            DropdownMenuItem(
                text = {
                    Column(Modifier.widthIn(max = 196.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label)
                        Text(
                            copyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                onClick = { menu = false; onCopy() },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            )
        }
    }
}

/** Lets the content draw and take touches [horizontal] and [vertical] beyond the space it is given. */
private fun Modifier.outset(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val dx = horizontal.roundToPx()
    val dy = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = dx * 2, vertical = dy * 2))
    layout(placeable.width - dx * 2, placeable.height - dy * 2) { placeable.place(-dx, -dy) }
}

/**
 * A long press and nothing else: a tap does nothing, so no click is offered. The press shows while the
 * finger is down and stays shown while [held]. Screen readers get the same action under [label].
 */
@Composable
private fun Modifier.longPressable(label: String, held: Boolean, onLongPress: () -> Unit): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val action by rememberUpdatedState(onLongPress)
    return this
        .clip(MaterialTheme.shapes.small)
        .background(if (held) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else Color.Transparent)
        .indication(interactions, LocalIndication.current)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interactions.emit(press)
                    interactions.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    action()
                },
            )
        }
        .semantics {
            onLongClick(label) { action(); true }
            customActions = listOf(CustomAccessibilityAction(label) { action(); true })
        }
}

/** Why this comic is missing from the lists, and where the blocked words are kept. */
@Composable
private fun HiddenNotice() {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(colors.errorContainer, MaterialTheme.shapes.medium)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.VisibilityOff, null, tint = colors.onErrorContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.detail_hidden_by_rule), color = colors.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.detail_hidden_by_rule_where), color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        val downloadLabel = stringResource(R.string.detail_download)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
            FilledTonalIconButton(
                onClick = onDownload,
                enabled = comic.allowDownload,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Outlined.Download, downloadLabel) }
            Text(
                downloadLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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

/**
 * The people behind the comic in one block, a row each and all rows alike: who they are for this comic,
 * their name, and an arrow when a tap leads to their comics. A missing person has no row.
 */
@Composable
private fun People(
    comic: ComicInfo,
    showAuthorSearch: Boolean,
    onAuthorClick: (String) -> Unit,
    onTeamClick: (String) -> Unit,
    onCreatorClick: (String, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val uploader = comic.uploader()
    val rows = buildList<@Composable (Shape) -> Unit> {
        if (comic.author.isNotBlank()) add { shape ->
            PersonRow(
                shape = shape,
                role = stringResource(R.string.detail_author),
                name = comic.author,
                onClick = if (showAuthorSearch) ({ onAuthorClick(comic.author) }) else null,
            ) { Icon(Icons.Outlined.Person, contentDescription = null, tint = colors.onSurfaceVariant) }
        }
        if (comic.chineseTeam.isNotBlank()) add { shape ->
            PersonRow(
                shape = shape,
                role = stringResource(R.string.detail_team),
                name = comic.chineseTeam,
                onClick = { onTeamClick(comic.chineseTeam) },
            ) { Icon(Icons.Outlined.Translate, contentDescription = null, tint = colors.onSurfaceVariant) }
        }
        if (uploader != null) add { shape ->
            PersonRow(
                shape = shape,
                role = stringResource(R.string.detail_uploader),
                name = uploader.name,
                level = uploader.level,
                title = uploader.title,
                description = stringResource(R.string.detail_uploader_description, uploader.name),
                onClick = { onCreatorClick(uploader.id, uploader.name) },
            ) { ShapedAvatar(imageUrl = rememberImageUrl(uploader.avatar), initial = uploader.name.trim().take(1), size = 28.dp) }
        }
    }
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            val top = if (index == 0) 20.dp else 4.dp
            val bottom = if (index == rows.lastIndex) 20.dp else 4.dp
            row(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
        }
    }
}

/**
 * One row of [People]. Without [onClick] the row only shows the name and has no arrow. TalkBack reads
 * the row as one item, as [description] when there is one.
 */
@Composable
private fun PersonRow(
    shape: Shape,
    role: String,
    name: String,
    onClick: (() -> Unit)?,
    level: Int = 0,
    title: String = "",
    description: String? = null,
    lead: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(
                if (description != null) Modifier.clearAndSetSemantics { contentDescription = description }
                else Modifier.semantics(mergeDescendants = true) {},
            )
            .heightIn(min = 52.dp)
            .padding(start = 14.dp, top = 6.dp, end = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { lead() }
        Text(role, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1)
        PersonName(name = name, level = level, title = title, modifier = Modifier.weight(1f))
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

/**
 * Name, level and title on one line. The level always shows whole; when the line is too short the title
 * goes first, then the name is shortened.
 */
@Composable
private fun PersonName(name: String, level: Int, title: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Layout(
        modifier = modifier,
        content = {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(PersonNamePart.Name),
            )
            if (level > 0) {
                Text(
                    stringResource(R.string.detail_uploader_level, level),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSecondaryContainer,
                    maxLines = 1,
                    modifier = Modifier
                        .layoutId(PersonNamePart.Level)
                        .background(colors.secondaryContainer, CircleShape)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.layoutId(PersonNamePart.Title),
                )
            }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val level = measurables.firstOrNull { it.layoutId == PersonNamePart.Level }?.measure(loose)
        val levelSpace = if (level != null) level.width + gap else 0
        val nameWidth = (constraints.maxWidth - levelSpace).coerceAtLeast(0)
        val nameText = measurables.first { it.layoutId == PersonNamePart.Name }.measure(loose.copy(maxWidth = nameWidth))
        val left = constraints.maxWidth - nameText.width - levelSpace - gap
        // A title cut down to a character or two says nothing, so it is left out instead.
        val titleText = measurables.firstOrNull { it.layoutId == PersonNamePart.Title }
            ?.takeIf { left >= PersonTitleMinWidth.roundToPx() }
            ?.measure(loose.copy(maxWidth = left))
        val height = listOfNotNull(nameText, level, titleText).maxOf { it.height }.coerceAtLeast(constraints.minHeight)
        layout(constraints.maxWidth, height) {
            var x = 0
            nameText.placeRelative(x, (height - nameText.height) / 2)
            x += nameText.width + gap
            if (level != null) {
                level.placeRelative(x, (height - level.height) / 2)
                x += level.width + gap
            }
            titleText?.placeRelative(x, (height - titleText.height) / 2)
        }
    }
}

private enum class PersonNamePart { Name, Level, Title }

private val PersonTitleMinWidth = 40.dp

/**
 * Categories and tags. A tap opens the word; a long press opens a menu to block it or take the block
 * off and, for a tag while tag favourites are on, to keep it or drop it. A blocked word carries ⊘ and a
 * kept tag a bookmark; a tag that is both shows ⊘ only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Keywords(
    comic: ComicInfo,
    hiddenKeywords: List<String>,
    showTagFavourites: Boolean,
    favouriteTags: Set<String>,
    onCategoryClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onToggleKeyword: (String) -> Unit,
    onToggleTagFavourite: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 2.dp, bottom = 8.dp)) {
        if (comic.categories.isNotEmpty()) {
            KeywordGroupLabel(stringResource(R.string.detail_categories))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                comic.categories.forEach { name ->
                    KeywordChip(
                        text = name,
                        blocked = hiddenKeywords.containsKeyword(name),
                        favourite = false,
                        onOpen = { onCategoryClick(name) },
                        onToggleBlocked = { onToggleKeyword(name) },
                        onToggleFavourite = null,
                    )
                }
            }
        }
        if (comic.tags.isNotEmpty()) {
            KeywordGroupLabel(stringResource(R.string.detail_tags), Modifier.padding(top = if (comic.categories.isEmpty()) 0.dp else 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                comic.tags.forEach { name ->
                    KeywordChip(
                        text = name,
                        blocked = hiddenKeywords.containsKeyword(name),
                        favourite = showTagFavourites && name.trim() in favouriteTags,
                        onOpen = { onTagClick(name) },
                        onToggleBlocked = { onToggleKeyword(name) },
                        onToggleFavourite = if (showTagFavourites) ({ onToggleTagFavourite(name) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeywordGroupLabel(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A category or tag. [onToggleFavourite] is null where keeping does not apply: categories, and tags
 * while tag favourites are off. The menu's actions are also offered to screen readers by name, so
 * they do not depend on the long press.
 */
@Composable
private fun KeywordChip(
    text: String,
    blocked: Boolean,
    favourite: Boolean,
    onOpen: () -> Unit,
    onToggleBlocked: () -> Unit,
    onToggleFavourite: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    var menu by remember { mutableStateOf(false) }
    val blockLabel = stringResource(if (blocked) R.string.detail_keyword_unblock else R.string.detail_keyword_block)
    val favouriteLabel = stringResource(if (favourite) R.string.detail_tag_unfavourite else R.string.detail_tag_favourite)
    val menuLabel = stringResource(R.string.detail_keyword_menu)
    val mark = when {
        blocked -> stringResource(R.string.detail_keyword_blocked)
        favourite -> stringResource(R.string.detail_tag_favourited)
        else -> null
    }
    Box {
        Row(
            Modifier
                .clip(shape)
                .background(if (menu) colors.onSurface.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, colors.outlineVariant, shape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = menuLabel,
                    onLongClick = { menu = true },
                    onClick = onOpen,
                )
                .semantics {
                    if (mark != null) stateDescription = mark
                    customActions = listOfNotNull(
                        onToggleFavourite?.let { toggle -> CustomAccessibilityAction(favouriteLabel) { toggle(); true } },
                        CustomAccessibilityAction(blockLabel) { onToggleBlocked(); true },
                    )
                }
                .heightIn(min = 32.dp)
                .padding(start = if (mark != null) 8.dp else 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (blocked) {
                Icon(Icons.Outlined.Block, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.error)
            } else if (favourite) {
                Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.primary)
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (blocked) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            properties = securePopupProperties(focusable = true),
        ) {
            if (onToggleFavourite != null) {
                DropdownMenuItem(
                    text = { Text(favouriteLabel) },
                    onClick = { menu = false; onToggleFavourite() },
                    leadingIcon = {
                        Icon(if (favourite) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkBorder, contentDescription = null)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(blockLabel) },
                onClick = { menu = false; onToggleBlocked() },
                leadingIcon = {
                    Icon(if (blocked) Icons.Outlined.DoNotDisturbOff else Icons.Outlined.Block, contentDescription = null)
                },
            )
        }
    }
}

/**
 * The description, four lines at first. 「展开 / 收起」 is there only when those four lines do not hold
 * the whole text; the text is measured for the width at hand before it is first drawn, so the button
 * never appears a moment late. A tap anywhere on the block does the same as the button.
 */
@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
    val measured = LocalTextStyle.current.merge(style)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val textWidth = with(LocalDensity.current) { (maxWidth - DescriptionSidePadding * 2).roundToPx() }.coerceAtLeast(0)
        val overflows = remember(text, measured, textWidth, measurer) {
            measurer.measure(
                text = text,
                style = measured,
                overflow = TextOverflow.Ellipsis,
                maxLines = DescriptionCollapsedLines,
                constraints = Constraints(maxWidth = textWidth),
            ).hasVisualOverflow
        }
        val toggle = stringResource(if (expanded) R.string.detail_collapse else R.string.detail_expand)
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (overflows) Modifier.clickable(onClickLabel = toggle, role = Role.Button) { expanded = !expanded }
                    else Modifier,
                )
                .padding(horizontal = DescriptionSidePadding)
                .padding(top = 6.dp, bottom = if (overflows) 6.dp else 12.dp)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Text(
                text,
                style = style,
                maxLines = if (overflows && !expanded) DescriptionCollapsedLines else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (overflows) {
                Row(
                    Modifier.padding(top = 2.dp).heightIn(min = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(toggle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private const val DescriptionCollapsedLines = 4

private val DescriptionSidePadding = 20.dp

/**
 * 「章节 · N」 with the order button beside it. The button names the order in use and is there only
 * when [sortable]: with one chapter, or before the chapters have loaded, there is nothing to turn round.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChaptersHeader(count: Int, reversed: Boolean, sortable: Boolean, onToggleOrder: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 20.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.detail_chapters, count),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (sortable) {
            val order = stringResource(if (reversed) R.string.detail_chapters_reverse else R.string.detail_chapters_forward)
            val description = stringResource(R.string.detail_chapters_order, order)
            AppTextButton(onClick = onToggleOrder, modifier = Modifier.semantics { contentDescription = description }) {
                Icon(Icons.Outlined.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(order, style = MaterialTheme.typography.titleSmall)
            }
        }
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
    if (ordered.size > COLLAPSED_CHAPTER_COUNT) {
        item(key = "eps-actions") {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                AppTextButton(onClick = onToggleShowAll) {
                    Text(if (showAll) stringResource(R.string.detail_collapse) else stringResource(R.string.detail_chapters_expand, ordered.size))
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (showAll) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
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
                        Icon(Icons.Outlined.DownloadDone, stringResource(R.string.detail_chapter_downloaded), tint = PicaTheme.extendedColors.success)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReadButton(
    progress: ReadingProgress?,
    firstEp: Int,
    eps: List<Ep>,
    currentEpPages: Int?,
    downloadedEpIds: Set<String>,
    onRead: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chapterPicker by rememberSaveable { mutableStateOf(false) }
    val buttonHeight = SplitButtonDefaults.MediumContainerHeight
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SplitButtonLayout(
            modifier = Modifier.fillMaxWidth().height(buttonHeight),
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = { if (progress != null) onRead(progress.epOrder, progress.pictureIndex - 1) else onRead(firstEp, 0) },
                    modifier = Modifier.fillMaxWidth(),
                    shapes = SplitButtonDefaults.leadingButtonShapesFor(buttonHeight),
                    contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(buttonHeight),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(SplitButtonDefaults.leadingButtonIconSizeFor(buttonHeight)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (progress != null) stringResource(R.string.detail_continue_reading, progress.epTitle.ifBlank { progress.epOrder.toString() })
                        else stringResource(R.string.detail_start_reading),
                        style = ButtonDefaults.textStyleFor(buttonHeight),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    onClick = { chapterPicker = true },
                    enabled = eps.isNotEmpty(),
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(buttonHeight),
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(buttonHeight),
                ) {
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = "选择章节",
                        modifier = Modifier.size(SplitButtonDefaults.trailingButtonIconSizeFor(buttonHeight)),
                    )
                }
            },
        )
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
    if (chapterPicker) {
        ModalBottomSheet(onDismissRequest = { chapterPicker = false }, properties = secureModalBottomSheetProperties()) {
            Text(
                stringResource(R.string.detail_chapters, eps.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(eps, key = { it.id }) { ep ->
                    ChapterRow(
                        ep = ep,
                        progress = progress,
                        currentEpPages = currentEpPages,
                        downloaded = ep.id in downloadedEpIds,
                        showDownloadColumn = downloadedEpIds.isNotEmpty(),
                        onClick = { onRead(ep.order, 0); chapterPicker = false },
                    )
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun DownloadPickerSheet(eps: List<Ep>, onDismiss: () -> Unit, onConfirm: (List<Ep>) -> Unit) {
    var chosen by remember(eps) { mutableStateOf(eps.map(Ep::id).toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = secureModalBottomSheetProperties()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.detail_download_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            AppTextButton(onClick = { chosen = if (chosen.size == eps.size) emptySet() else eps.map(Ep::id).toSet() }) {
                Text(stringResource(R.string.comics_select_all))
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(eps, key = { it.id }) { ep ->
                ListItem(
                    checked = ep.id in chosen,
                    onCheckedChange = { checked -> chosen = if (checked) chosen + ep.id else chosen - ep.id },
                    leadingContent = { Checkbox(checked = ep.id in chosen, onCheckedChange = null) },
                ) { Text(ep.title.ifBlank { ep.order.toString() }) }
            }
        }
        AppButton(
            onClick = { onConfirm(eps.filter { it.id in chosen }) },
            enabled = chosen.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        ) { Text(stringResource(R.string.detail_download_confirm, chosen.size)) }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
