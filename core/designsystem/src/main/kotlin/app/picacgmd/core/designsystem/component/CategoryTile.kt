package app.picacgmd.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

object CategoryTileDefaults {
    /** Tile height in the discover / all-categories grids (board 04). */
    val Height: Dp = 88.dp
}

private val CategoryScrim = Brush.verticalGradient(
    0.3f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.66f),
)

/**
 * Category tile of the discover grid (board 04): the category thumbnail behind a bottom scrim with
 * the name in the lower-left corner. Without a thumbnail it falls back to a tonal container color
 * picked from the name, with the name in the matching on-container color.
 *
 * Width comes from the grid cell; height defaults to [CategoryTileDefaults.Height] unless
 * [modifier] sets one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryTile(
    name: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    val tonal = tonalPairFor(name)
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scrimAlpha by animateFloatAsState(if (loaded) 1f else 0f, effects, label = "categoryScrim")
    val labelColor by animateColorAsState(
        targetValue = if (loaded) Color.White else tonal.content,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "categoryLabel",
    )
    Box(
        modifier = modifier
            .height(CategoryTileDefaults.Height)
            .clip(MaterialTheme.shapes.largeIncreased)
            .background(tonal.container)
            .clickable(onClick = onClick, role = Role.Button),
    ) {
        if (!imageUrl.isNullOrEmpty()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(CategoryScrim),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = labelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun CategoryTilePreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全彩", "长篇", "同人").forEach {
                CategoryTile(name = it, imageUrl = null, onClick = {}, modifier = Modifier.weight(1f))
            }
        }
    }
}
