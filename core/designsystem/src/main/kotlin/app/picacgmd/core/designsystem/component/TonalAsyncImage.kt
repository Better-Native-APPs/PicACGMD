package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * A plain remote picture (a game cover, an announcement banner) that is never a blank hole while it
 * loads: the area is `surfaceContainerHigh` with the [loadingSheen] passing over it until the picture
 * arrives. A picture that fails, or has no address, leaves the still tonal area.
 *
 * Size and clip come from [modifier]. Comic covers use [ComicCover], which adds marks and retry.
 */
@Composable
fun TonalAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var settled by remember(model) { mutableStateOf(false) }
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .loadingSheen(active = !model.isNullOrEmpty() && !settled, highlight = MaterialTheme.colorScheme.surfaceBright),
    ) {
        if (!model.isNullOrEmpty()) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onSuccess = { settled = true },
                onError = { settled = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
