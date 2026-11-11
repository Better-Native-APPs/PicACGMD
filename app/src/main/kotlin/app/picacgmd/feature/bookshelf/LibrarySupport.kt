package app.picacgmd.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.network.ComicSimple
import app.picacgmd.core.network.PicaImage
import app.picacgmd.core.network.PicaJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Helpers shared by the library lane (bookshelf, downloads, 我的): image URLs that never throw,
 * snapshot → [ComicSimple] conversion and the connected view switcher.
 */

/** Resolves an image URL with the current routing; null while the network config is unavailable. */
internal fun AppContainer.safeImageUrl(fileServer: String, path: String): String? =
    if (path.isBlank()) null else runCatching { networkConfigProvider.imageUrl(fileServer, path) }.getOrNull()

internal fun AppContainer.safeImageUrl(image: PicaImage?): String? =
    image?.let { safeImageUrl(it.fileServer, it.path) }

internal fun decodeCategories(json: String): List<String> =
    runCatching { PicaJson.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())

internal fun snapshotComic(
    id: String,
    title: String,
    author: String,
    thumbFileServer: String,
    thumbPath: String,
    categoriesJson: String,
): ComicSimple = ComicSimple(
    id = id,
    title = title,
    author = author,
    categories = decodeCategories(categoriesJson),
    thumb = if (thumbPath.isBlank()) null else PicaImage(fileServer = thumbFileServer, path = thumbPath),
)

/** Day bucket used by the history groups (置顶 / 今天 / 昨天 / 更早). */
internal fun dayBucket(epochMillis: Long, today: LocalDate = LocalDate.now()): Int {
    val day = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        day == today -> 0
        day == today.minusDays(1) -> 1
        else -> 2
    }
}

/**
 * Connected single-choice ToggleButtons with optional count badges (boards 18 / 21): the one
 * same-page view switcher used across the app.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> ViewSwitcher(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    badge: (T) -> Int = { 0 },
) {
    Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.Tab },
                shapes = when {
                    options.size == 1 -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = badge(option)
                if (count > 0) {
                    Spacer(Modifier.width(6.dp))
                    CountPill(count, highlighted = option != selected)
                }
            }
        }
    }
}

@Composable
internal fun CountPill(count: Int, highlighted: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .background(if (highlighted) colors.error else colors.onPrimary.copy(alpha = 0.24f), MaterialTheme.shapes.extraLarge)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) colors.onError else colors.onPrimary,
        )
    }
}
