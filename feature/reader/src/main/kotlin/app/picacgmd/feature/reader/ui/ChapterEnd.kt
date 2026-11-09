package app.picacgmd.feature.reader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.ReaderChapter
import app.picacgmd.feature.reader.logic.ForwardSwipe
import coil3.size.Size as CoilSize

/** Everything the chapter-end card shows about what comes next. */
@Immutable
internal data class ChapterEndInfo(
    /** Title of the chapter just finished. */
    val finishedTitle: String,
    val next: ReaderChapter,
    /** Pages of the next chapter, once its page list has been fetched. */
    val nextPageCount: Int?,
    val cover: PageImage?,
    val commentCount: Int?,
)

private val EndCardMaxWidth = 480.dp

/**
 * 章节结束: follows the last page. The card offers the next chapter, the comments and a way out, and
 * the line under it tells the reader that simply swiping on works too.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChapterEndBlock(
    info: ChapterEndInfo,
    pull: ChapterPullState,
    swipe: ForwardSwipe,
    onNext: () -> Unit,
    onComments: (() -> Unit)?,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = LocalReaderPalette.current.onBackground.copy(alpha = 0.67f)
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.reader_end_finished, info.finishedTitle),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(
            modifier = Modifier
                .widthIn(max = EndCardMaxWidth)
                .fillMaxWidth()
                // Taps on the card are not taps on the page; drags still scroll the strip.
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.reader_next_chapter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                NextChapterRow(info)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.MediumIconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
                    Text(
                        text = stringResource(R.string.reader_next_chapter),
                        style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onComments != null) {
                        FilledTonalButton(onClick = onComments, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Rounded.Forum,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(
                                text = info.commentCount?.let { stringResource(R.string.reader_comments_count, it) }
                                    ?: stringResource(R.string.reader_comments),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    OutlinedButton(onClick = onFinish, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.reader_finish), maxLines = 1)
                    }
                }
            }
        }
        PullHint(pull, swipe, muted)
    }
}

/** The same block for paged modes: it fills a page and scrolls if a short landscape screen needs it. */
@Composable
internal fun ChapterEndPage(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NextChapterRow(info: ChapterEndInfo) {
    val palette = LocalReaderPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (info.cover != null) ChapterCover(info.cover)
        Column(Modifier.weight(1f)) {
            Text(
                text = info.next.title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (info.nextPageCount != null) {
                Text(
                    text = stringResource(R.string.reader_page_count, info.nextPageCount),
                    style = MaterialTheme.typography.bodyMedium.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (info.next.isDownloaded) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .heightIn(min = 24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.successContainer)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = null,
                        tint = palette.onSuccessContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.reader_downloaded),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.onSuccessContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterCover(cover: PageImage) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val request = remember(cover, density) {
        val width = with(density) { 72.dp.roundToPx() }
        val height = with(density) { 100.dp.roundToPx() }
        pageRequest(context, cover, CoilSize(width, height))
    }
    Image(
        painter = rememberAsyncImagePainter(model = request),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = 72.dp, height = 100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** 「继续上滑直接进入下一话」; follows the finger, then says when letting go will leave the chapter. */
@Composable
private fun PullHint(pull: ChapterPullState, swipe: ForwardSwipe, muted: Color) {
    val density = LocalDensity.current
    val travel = with(density) { 10.dp.toPx() }
    val armed by remember(pull) { derivedStateOf { pull.armed } }
    val reduceMotion = LocalReaderReduceMotion.current
    // Follows the finger closely and settles back when a pull is let go without leaving.
    val shift by animateFloatAsState(
        targetValue = pull.progress,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessHigh),
        label = "pull hint travel",
    )
    val color by animateColorAsState(
        targetValue = if (armed) MaterialTheme.colorScheme.primary else muted,
        animationSpec = if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "pull hint",
    )
    val (icon: ImageVector, text: Int) = when (swipe) {
        ForwardSwipe.Up -> Icons.Rounded.SwipeUp to R.string.reader_end_swipe_up
        ForwardSwipe.Left -> Icons.Rounded.SwipeLeft to R.string.reader_end_swipe_left
        ForwardSwipe.Right -> Icons.Rounded.SwipeRight to R.string.reader_end_swipe_right
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    val moved = shift * travel
                    when (swipe) {
                        ForwardSwipe.Up -> translationY = -moved
                        ForwardSwipe.Left -> translationX = -moved
                        ForwardSwipe.Right -> translationX = moved
                    }
                },
        )
        Text(
            text = stringResource(if (armed) R.string.reader_end_release else text),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * 全部读完: where the last chapter ends. Unlike a passing message it leaves the reader somewhere to
 * go next. Buttons without a destination are hidden.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AllReadDialog(
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
    onBackToDetails: () -> Unit,
    onComments: (() -> Unit)?,
    onRelated: (() -> Unit)?,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogView = LocalView.current
        SideEffect { (dialogView.parent as? DialogWindowProvider)?.window?.setDimAmount(0.6f) }
        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = EndCardMaxWidth)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SunnyBadge(reduceMotion)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.reader_all_read_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onBackToDetails,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                ) {
                    Text(
                        text = stringResource(R.string.reader_back_to_details),
                        style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                    )
                }
                if (onComments != null || onRelated != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onComments != null) {
                            TextButton(onClick = onComments) { Text(stringResource(R.string.reader_see_comments)) }
                        }
                        if (onRelated != null) {
                            TextButton(onClick = onRelated) { Text(stringResource(R.string.reader_related)) }
                        }
                    }
                }
            }
        }
    }
}

/** The Sunny shape settles in with a little spring: the one moment of celebration in the reader. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SunnyBadge(reduceMotion: Boolean) {
    val scale = remember { Animatable(if (reduceMotion) 1f else 0.55f) }
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        if (!reduceMotion) scale.animateTo(1f, spatial)
    }
    Box(
        modifier = Modifier
            .size(80.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = (1f - scale.value) * 60f
            }
            .clip(MaterialShapes.Sunny.toShape())
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(32.dp),
        )
    }
}
