package app.picacgmd.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import app.picacgmd.core.designsystem.icon.PicaIcons
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

object CategoryTileDefaults {
    /** Tile height in the discover / all-categories grids (board 04). */
    val Height: Dp = 88.dp

    /** Diameter of the channel emblem; larger than the tile's free corner, so the tile crops it. */
    val EmblemSize: Dp = 84.dp

    /** How far the emblem reaches past the tile's top and end edges. */
    val EmblemBleed: Dp = 14.dp

    /** Icon size inside the emblem. */
    val EmblemIconSize: Dp = 32.dp
}

/** The emblem's outline is a tint of the tile's content colour, the icon the colour itself. */
private const val EMBLEM_OUTLINE_ALPHA = 0.12f

private val NameBottomPadding = 8.dp

/** Kept clear between the emblem's icon and the name. */
private val EmblemNameGap = 2.dp

/** Below this share of its size the icon is too small to read, and the tile goes without. */
private const val EMBLEM_MIN_SCALE = 0.5f

/**
 * The share of its full size the emblem gets in a tile [tileHeight] high whose name is [nameHeight]
 * high, or 0 for no emblem. It shrinks as a wrapped or enlarged name takes the tile's height, so the
 * icon always ends above the name.
 */
internal fun emblemScale(tileHeight: Dp, nameHeight: Dp): Float {
    val free = tileHeight - NameBottomPadding - nameHeight - EmblemNameGap
    val iconBottom = (CategoryTileDefaults.EmblemSize + CategoryTileDefaults.EmblemIconSize) / 2 - CategoryTileDefaults.EmblemBleed
    val scale = (free / iconBottom).coerceAtMost(1f)
    return if (scale < EMBLEM_MIN_SCALE) 0f else scale
}

private val CategoryScrim = Brush.verticalGradient(
    0.3f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.66f),
)

/**
 * Category tile of the discover grid (board 04): the category thumbnail behind a bottom scrim with
 * the name in the lower-left corner. Without a thumbnail it falls back to a tonal container color
 * picked from the name, with the name in the matching on-container color; while the thumbnail is on
 * its way the [loadingSheen] passes over that color.
 *
 * A tile that opens a built-in [channel] has no picture of its own. Its upper end corner carries the
 * channel's emblem instead: the outline and icon the channel has in the home row, so the entry looks
 * the same in both places.
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
    channel: HomeChannel? = null,
) {
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    var failed by remember(imageUrl) { mutableStateOf(false) }
    var nameHeight by remember { mutableIntStateOf(0) }
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
            .loadingSheen(
                active = !imageUrl.isNullOrEmpty() && !loaded && !failed,
                highlight = tonal.content.copy(alpha = 0.10f),
            )
            .clickable(onClick = onClick, role = Role.Button),
    ) {
        val emblemScale = emblemScale(CategoryTileDefaults.Height, with(LocalDensity.current) { nameHeight.toDp() })
        if (channel != null && !loaded && emblemScale > 0f) {
            ChannelEmblem(
                channel = channel,
                color = tonal.content,
                scale = emblemScale,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = CategoryTileDefaults.EmblemBleed * emblemScale, y = -CategoryTileDefaults.EmblemBleed * emblemScale),
            )
        }
        if (!imageUrl.isNullOrEmpty()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true; failed = false },
                onError = { loaded = false; failed = true },
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
            onTextLayout = { nameHeight = it.size.height },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, end = 10.dp, bottom = NameBottomPadding),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChannelEmblem(channel: HomeChannel, color: Color, scale: Float, modifier: Modifier = Modifier) {
    val (icon, outline) = when (channel) {
        HomeChannel.Rankings -> PicaIcons.Rankings to MaterialShapes.Cookie6Sided
        HomeChannel.Random -> PicaIcons.Random to MaterialShapes.Clover4Leaf
        HomeChannel.Games -> PicaIcons.Gamepad to MaterialShapes.Flower
    }
    Box(
        modifier = modifier
            .size(CategoryTileDefaults.EmblemSize * scale)
            .background(color.copy(alpha = EMBLEM_OUTLINE_ALPHA), outline.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(CategoryTileDefaults.EmblemIconSize * scale))
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun CategoryTilePreview() {
    PicaTheme(themeChoice = ThemeChoice.Brand) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryTile(name = "全彩", imageUrl = null, onClick = {}, modifier = Modifier.weight(1f))
            CategoryTile(name = "随机", imageUrl = null, onClick = {}, modifier = Modifier.weight(1f), channel = HomeChannel.Random)
            CategoryTile(name = "游戏专区", imageUrl = null, onClick = {}, modifier = Modifier.weight(1f), channel = HomeChannel.Games)
        }
    }
}
