package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.logic.loadPercent
import app.picacgmd.core.designsystem.component.AppButton
import app.picacgmd.core.designsystem.component.AppFilledTonalButton
import app.picacgmd.core.designsystem.component.ImageFailureReloadAction
import app.picacgmd.core.designsystem.component.LocalImageFailureReloadAction
import app.picacgmd.core.designsystem.component.reloadFailedImage
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Shared by every page of the open chapter: bumping [epoch] restarts every page that failed, which
 * is what 「换分流」 does after the image server changed.
 */
@Stable
internal class PageRetryState(
    /** Offer 「换分流」 on failed network pages. */
    val canSwitchServer: Boolean,
    private val onSwitchServer: () -> Unit,
) {
    var epoch by mutableIntStateOf(0)
        private set

    fun retryAllFailed() {
        epoch++
    }

    fun switchServer() = onSwitchServer()
}

/**
 * 重新加载这张图 / 重载整页: a page asked to reload goes around Coil's caches once, and what it loads
 * replaces what they held. Later visits use the caches again.
 */
@Stable
internal class PageReloadState {
    private var all by mutableIntStateOf(0)
    private val single = mutableStateMapOf<Int, Int>()
    private val loaded = HashMap<Int, Int>()

    /** Changes whenever [page] has to load again; use it as a `key` around the page. */
    fun nonce(page: Int): Int = all + (single[page] ?: 0)

    /** Whether the next load of [page] must go around the caches. */
    fun needsFresh(page: Int): Boolean = nonce(page) != (loaded[page] ?: 0)

    fun markLoaded(page: Int) {
        loaded[page] = nonce(page)
    }

    fun reload(page: Int) {
        single[page] = (single[page] ?: 0) + 1
    }

    fun reloadAll() {
        all++
    }
}

/** Why a page failed, as far as the reader can tell. */
internal enum class PageFailure { Timeout, Network, LocalFile, Unknown }

internal fun pageFailureOf(throwable: Throwable, image: PageImage): PageFailure {
    if (image is PageImage.Local || image is PageImage.Document) return PageFailure.LocalFile
    val chain = generateSequence(throwable) { it.cause }.take(8).toList()
    return when {
        chain.any { it is InterruptedIOException } -> PageFailure.Timeout
        chain.any { it is IOException } -> PageFailure.Network
        else -> PageFailure.Unknown
    }
}

/** Minimum height of the loading / failure block so the failure pane always fits. */
internal val PageBlockMinHeight = 320.dp

/**
 * How far a page has downloaded: null when the source cannot tell for this page at all, otherwise a
 * reader of the current fraction, which is null while the total size is unknown.
 */
@Composable
internal fun rememberLoadProgress(image: PageImage, host: ReaderPageHost): (() -> Float?)? {
    val flow = remember(image, host) { (image as? PageImage.Remote)?.let { host.loadProgress(it.url) } }
        ?: return null
    val fraction = flow.collectAsState()
    return remember(fraction) { { fraction.value } }
}

private val LoadingRingSize = 48.dp

/**
 * Loading block: keeps the page's height and shows 「第 N 页」 under a single indicator.
 *
 * - Size known: a ring fills to the downloaded share and the label becomes 「第 N 页 · 62%」. It
 *   steps in whole percents with no easing, so it only redraws when the number changes.
 * - Size unknown (still connecting, or the source cannot tell): the expressive loading indicator
 *   morphs in the same spot, never a plain spinning ring (2026-11-10); with [reduceMotion] it holds
 *   its first shape.
 *
 * Both are the same size in the same place, so nothing jumps when the size arrives.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PageLoadingBlock(
    pageNumber: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    progress: (() -> Float?)? = null,
) {
    // Only a change of the whole percent recomposes the block, not every progress tick.
    val percent by remember(progress) { derivedStateOf { progress?.invoke()?.let(::loadPercent) } }
    Box(modifier.background(PageBlockColors.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shown = percent
            val ringModifier = Modifier.size(LoadingRingSize)
            when {
                shown != null -> CircularWavyProgressIndicator(
                    progress = { shown / 100f },
                    modifier = ringModifier,
                    color = ReaderDarkScheme.primary,
                    trackColor = ReaderDarkScheme.secondaryContainer,
                    waveSpeed = 0.dp,
                )
                reduceMotion -> LoadingIndicator(
                    progress = { 0f },
                    modifier = ringModifier,
                    color = ReaderDarkScheme.primary,
                )
                else -> LoadingIndicator(modifier = ringModifier, color = ReaderDarkScheme.primary)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (shown != null) {
                    stringResource(R.string.reader_page_loading_progress, pageNumber, shown)
                } else {
                    stringResource(R.string.reader_page_number, pageNumber)
                },
                style = MaterialTheme.typography.labelMedium.withTabularFigures(),
                color = PageBlockColors.Label,
            )
        }
    }
}

/** Failure block: shown in place of the page, with 「重试」 and, for network pages, 「换分流」. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PageFailureBlock(
    pageNumber: Int,
    failure: PageFailure,
    onRetry: () -> Unit,
    onSwitchServer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tapToRetry = LocalImageFailureReloadAction.current == ImageFailureReloadAction.Tap
    MaterialTheme(colorScheme = ReaderDarkScheme) {
        Box(modifier.background(PageBlockColors.Background).reloadFailedImage(onRetry), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(MaterialShapes.Cookie6Sided.toShape())
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.reader_page_failed, pageNumber),
                    style = MaterialTheme.typography.titleSmall,
                    color = PageBlockColors.Title,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        when (failure) {
                            PageFailure.Timeout -> R.string.reader_page_failed_timeout
                            PageFailure.Network -> R.string.reader_page_failed_network
                            PageFailure.LocalFile -> R.string.reader_page_failed_local
                            PageFailure.Unknown -> R.string.reader_page_failed_unknown
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = PageBlockColors.Body,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onSwitchServer != null) {
                        AppFilledTonalButton(
                            onClick = onSwitchServer,
                            modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
                            contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                        ) {
                            Text(
                                stringResource(R.string.reader_switch_server),
                                style = ButtonDefaults.textStyleFor(ButtonDefaults.ExtraSmallContainerHeight),
                            )
                        }
                    }
                    if (tapToRetry) AppButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
                        contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                    ) {
                        Text(
                            stringResource(R.string.reader_retry),
                            style = ButtonDefaults.textStyleFor(ButtonDefaults.ExtraSmallContainerHeight),
                        )
                    }
                    else Text("下拉重试", color = PageBlockColors.Body)
                }
            }
        }
    }
}

/** Page numbers and counters never jitter: tabular figures. */
internal fun androidx.compose.ui.text.TextStyle.withTabularFigures(): androidx.compose.ui.text.TextStyle =
    copy(fontFeatureSettings = "tnum")
