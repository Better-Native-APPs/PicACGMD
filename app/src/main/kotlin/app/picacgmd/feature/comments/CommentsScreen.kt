package app.picacgmd.feature.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.EmptyState
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.designsystem.component.LoadingState
import app.picacgmd.core.designsystem.component.ShapedAvatar
import app.picacgmd.core.network.Comment
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.feature.comics.formatCount
import app.picacgmd.feature.comics.rememberImageUrl
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Board 17: comments of a comic — pinned group, latest comments, replies sheet, composer, likes, reports.
 * Wire as `composable<ComicCommentsRoute> { CommentsScreen(comicId = it.comicId, onBack = …) }`.
 *
 * @param title top bar title; null is the usual 「评论」, the message board passes its own name.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CommentsScreen(comicId: String, onBack: () -> Unit, modifier: Modifier = Modifier, title: String? = null) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "comments:$comicId") { CommentsViewModel(container, comicId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val replies by viewModel.replies.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var reportTarget by remember { mutableStateOf<Comment?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LoadMoreEffect(listState, state.items.size, viewModel::loadNext)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title ?: stringResource(R.string.comments_title)) },
                navigationIcon = {
                    app.picacgmd.core.designsystem.component.BackButton(onBack)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Composer(
                hint = stringResource(R.string.comments_input_hint),
                sending = state.sending,
                onSend = viewModel::post,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.items.isEmpty() -> LoadingState()
                state.error != null && state.items.isEmpty() -> ErrorState(onRetry = viewModel::reload, description = state.error)
                state.items.isEmpty() && state.top.isEmpty() -> EmptyState(title = stringResource(R.string.comments_empty))
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (state.top.isNotEmpty()) {
                        item(key = "top-header") { GroupLabel(stringResource(R.string.comments_top), pinned = true) }
                        items(state.top, key = { "top-${it.id}" }) { comment ->
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                CommentRow(
                                    comment,
                                    onLike = { viewModel.toggleLike(comment.id) },
                                    onReplies = { viewModel.openReplies(comment) },
                                    onReport = { reportTarget = comment },
                                )
                            }
                        }
                    }
                    item(key = "all-header") { GroupLabel(stringResource(R.string.comments_all), pinned = false) }
                    items(state.items, key = { it.id }) { comment ->
                        CommentRow(
                            comment,
                            onLike = { viewModel.toggleLike(comment.id) },
                            onReplies = { viewModel.openReplies(comment) },
                            onReport = { reportTarget = comment },
                        )
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.appending -> LoadingIndicator()
                                state.appendError != null -> TextButton(onClick = viewModel::retryAppend) { Text(stringResource(R.string.comics_load_more_failed)) }
                                !state.hasNext -> Text(stringResource(R.string.comics_end_of_list), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }

    replies?.let { thread ->
        RepliesSheet(
            thread = thread,
            snackbar = snackbar,
            onDismiss = viewModel::closeReplies,
            onLike = viewModel::toggleLike,
            onReport = { reportTarget = it },
            onLoadMore = viewModel::loadMoreReplies,
            onRetry = viewModel::retryReplies,
            onSend = viewModel::reply,
        )
    }

    // Composed after the sheet, so its window opens on top of it when reporting a reply.
    reportTarget?.let { target ->
        val confirmation = stringResource(R.string.comments_reported)
        ReportCommentDialog(
            comment = target,
            onDismiss = { reportTarget = null },
            onConfirm = {
                reportTarget = null
                viewModel.report(target.id, confirmation)
            },
        )
    }
}

@Composable
private fun LoadMoreEffect(listState: LazyListState, size: Int, onLoad: () -> Unit) {
    LaunchedEffect(listState, size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }.distinctUntilChanged().collect { if (it) onLoad() }
    }
}

@Composable
private fun GroupLabel(text: String, pinned: Boolean) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pinned) Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp).padding(end = 2.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
    }
}

/** One comment: avatar, name + level, body, time, reply count and like toggle. Public for the detail preview. */
@Composable
fun CommentRow(
    comment: Comment,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onLike: (() -> Unit)? = null,
    onReplies: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
) {
    val user = comment.user
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onReplies != null && !compact) Modifier.clickable(onClick = onReplies) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShapedAvatar(imageUrl = rememberImageUrl(user?.avatar), initial = user?.name?.take(1), size = if (compact) 32.dp else 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        user?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.comments_anonymous),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (user != null) {
                        Text(
                            stringResource(R.string.comments_level, user.level),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                if (onReport != null) CommentMenu(onReport)
            }
            Text(
                if (comment.hide) stringResource(R.string.comments_hidden) else comment.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (comment.hide) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = if (compact) 3 else Int.MAX_VALUE,
            )
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.createdAt?.replace('T', ' ')?.take(16).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    if (onReplies != null) {
                        TextButton(onClick = onReplies) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (comment.commentsCount > 0) stringResource(R.string.comments_replies, comment.commentsCount)
                                else stringResource(R.string.comments_reply),
                            )
                        }
                    }
                    if (onLike != null) {
                        TextButton(onClick = onLike) {
                            Icon(
                                if (comment.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                stringResource(R.string.comments_like),
                                Modifier.size(16.dp),
                                tint = if (comment.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(formatCount(comment.likesCount.toLong()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(hint: String, sending: Boolean, onSend: (String, () -> Unit) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val length = commentLength(text)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it.take(COMMENT_DRAFT_CAP) },
                placeholder = { Text(hint) },
                maxLines = 4,
                isError = length > COMMENT_MAX_LENGTH,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.weight(1f),
            )
            // The count sits on the button it gates, so a disabled send explains itself.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CommentLengthCounter(length)
                FilledIconButton(onClick = { onSend(text) { text = "" } }, enabled = commentSendable(length) && !sending) {
                    Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.comments_send))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RepliesSheet(
    thread: ReplyThread,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
    onLike: (String) -> Unit,
    onReport: (Comment) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSend: (String, () -> Unit) -> Unit,
) {
    val listState = rememberLazyListState()
    // Further pages come in by themselves as the end scrolls into view; only a failed page waits for a tap.
    LoadMoreEffect(listState, thread.replies.size, onLoadMore)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), contentWindowInsets = { WindowInsets(0) }) {
        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.comments_replies_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            CommentRow(thread.parent, onLike = { onLike(thread.parent.id) }, onReport = { onReport(thread.parent) })
            HorizontalDivider()
            Box(Modifier.weight(1f, fill = false)) {
                LazyColumn(state = listState) {
                    items(thread.replies, key = { it.id }) { reply ->
                        CommentRow(reply, modifier = Modifier.padding(start = 24.dp), onLike = { onLike(reply.id) }, onReport = { onReport(reply) })
                    }
                    item(key = "replies-footer") { RepliesFooter(thread, onRetry) }
                }
                // The sheet is its own window and covers the page's snackbar, so messages need a host in here too.
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            Composer(
                hint = stringResource(R.string.comments_reply_hint, thread.parent.user?.name.orEmpty()),
                sending = thread.sending,
                onSend = onSend,
            )
        }
    }
}

/** The end of the replies: a spinner while a page loads, a retry once one has failed, or the empty note. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RepliesFooter(thread: ReplyThread, onRetry: () -> Unit) {
    val error = thread.error
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            thread.loading -> LoadingIndicator()
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // With nothing on screen yet, say what went wrong.
                if (thread.replies.isEmpty()) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.comics_load_more_failed)) }
            }
            thread.replies.isEmpty() -> Text(stringResource(R.string.comments_no_replies), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
