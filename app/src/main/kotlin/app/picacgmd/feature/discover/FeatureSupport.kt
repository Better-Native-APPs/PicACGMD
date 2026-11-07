package app.picacgmd.feature.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.ComicListItem
import app.picacgmd.core.designsystem.component.ErrorState
import app.picacgmd.core.designsystem.component.OfflineState
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.PicaImage
import app.picacgmd.core.network.PicaTransportException
import app.picacgmd.di.LocalAppContainer
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** Result of a one-shot screen load. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
    /** [offline] = the request never reached the server (no network, DNS, timeout). */
    data class Failed(val offline: Boolean, val message: String?) : LoadState<Nothing>
}

internal fun Throwable.toFailure(): LoadState.Failed = LoadState.Failed(
    offline = generateSequence(this) { it.cause }.any { it is PicaTransportException || it is IOException },
    message = message,
)

/** Runs [block], turning ordinary failures into [LoadState.Failed] while letting cancellation through. */
internal suspend fun <T> loadCatching(block: suspend () -> T): LoadState<T> = try {
    LoadState.Ready(block())
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Exception) {
    exception.toFailure()
}

/** ViewModel whose factory pulls dependencies from the root [AppContainer]. */
@Composable
internal inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    noinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    val factory = remember(container) { viewModelFactory { initializer { create(container) } } }
    return viewModel(key = key, factory = factory)
}

internal fun AppContainer.imageUrl(image: PicaImage?): String? =
    image?.takeIf { it.path.isNotBlank() }?.let(networkConfigProvider::imageUrl)

@Composable
internal fun rememberImageUrl(image: PicaImage?): String? {
    val container = LocalAppContainer.current
    return remember(container, image) { container.imageUrl(image) }
}

/** 12345 → 「1.2万」, the way the community counts likes. */
internal fun formatCount(value: Int): String = when {
    value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 100_000_000f)
    value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 10_000f)
    else -> value.toString()
}

/** Offline or error pane for a failed first load. */
@Composable
internal fun LoadFailure(failure: LoadState.Failed, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (failure.offline) {
        OfflineState(onRetry = onRetry, modifier = modifier)
    } else if (failure.message.isNullOrBlank()) {
        ErrorState(onRetry = onRetry, modifier = modifier)
    } else {
        ErrorState(onRetry = onRetry, modifier = modifier, description = failure.message)
    }
}

/** A comic row shared by the rankings, random and other channel pages. */
@Composable
internal fun ComicSimpleRow(
    comic: ComicSimple,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    ComicListItem(
        title = comic.title,
        coverUrl = rememberImageUrl(comic.thumb),
        onClick = onClick,
        modifier = modifier,
        subtitle = comic.author.ifBlank { null },
        tags = comic.categories.take(3),
        finished = comic.finished,
        likes = comic.likesCount.takeIf { it > 0 }?.let(::formatCount),
        pages = stringResource(R.string.discover_comic_pages, comic.pagesCount, comic.epsCount),
        leadingContent = leadingContent,
    )
}

/** Board 07 rank badge: Sunny for the podium, a plain circle for everyone else. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val podium = rank <= 3
    val (container, content) = when (rank) {
        1 -> colors.primary to colors.onPrimary
        2 -> colors.tertiary to colors.onTertiary
        3 -> colors.secondary to colors.onSecondary
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Surface(
        modifier = modifier.size(if (podium) 36.dp else 28.dp),
        shape = if (podium) MaterialShapes.Sunny.toShape() else CircleShape,
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = rank.toString(),
                style = if (podium) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            )
        }
    }
}
