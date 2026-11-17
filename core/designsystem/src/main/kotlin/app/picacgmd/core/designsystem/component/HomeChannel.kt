package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import coil3.compose.AsyncImage
import app.picacgmd.core.designsystem.icon.PicaIcons
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

object HomeChannelDefaults {
    /** Slot width; five slots plus 12dp side padding fit a 412dp phone. */
    val ItemWidth: Dp = 72.dp

    /** Diameter of the shaped badge. */
    val BadgeSize: Dp = 64.dp

    /** Icon size inside the badge. */
    val IconSize: Dp = 28.dp

    /** Width of the tonal rim left around the picture of a pinned category. */
    val PictureRim: Dp = 3.dp
}

/** The built-in home channels, with their board-01 shape and color; only rankings is always pinned. */
enum class HomeChannel { Rankings, Random, Games }

/**
 * Home channel entry: a 64dp MaterialShapes badge with an icon, label below (board 04).
 * The whole slot is clickable; the ripple stays inside the shape.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeChannelButton(
    label: String,
    icon: ImageVector,
    shape: RoundedPolygon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = contentColorFor(containerColor),
) {
    ChannelSlot(label = label, badgeShape = shape.toShape(), onClick = onClick, modifier = modifier, rippleColor = contentColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(HomeChannelDefaults.IconSize))
        }
    }
}

/**
 * One of the built-in channels: 排行榜 = Cookie6Sided on primary container, 随机 = Clover4Leaf on
 * tertiary container, 游戏专区 = Flower (board 01 reserves it for the game entry) on the highest
 * surface container. Icons come from [PicaIcons].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeChannelButton(
    channel: HomeChannel,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    when (channel) {
        HomeChannel.Rankings -> HomeChannelButton(
            label, PicaIcons.Rankings, MaterialShapes.Cookie6Sided, onClick, modifier,
            colors.primaryContainer, colors.onPrimaryContainer,
        )
        HomeChannel.Random -> HomeChannelButton(
            label, PicaIcons.Random, MaterialShapes.Clover4Leaf, onClick, modifier,
            colors.tertiaryContainer, colors.onTertiaryContainer,
        )
        HomeChannel.Games -> HomeChannelButton(
            label, PicaIcons.Gamepad, MaterialShapes.Flower, onClick, modifier,
            colors.surfaceContainerHighest, colors.onSurface,
        )
    }
}

/**
 * A category the user pinned to the home row (「固定到首页」): the same shaped badge as the built-in
 * channels, filled with a tonal colour picked from the name, with the category picture set into it.
 * A rim of the tonal colour, [HomeChannelDefaults.PictureRim] wide, stays around the picture so the
 * outline reads on any picture and any background. The picture is scaled to fill and centred, then
 * cut to [shape]. While there is no picture (none given, still loading, failed) the badge is the
 * tonal shape with the first character of the name.
 *
 * @param shape outline of the badge; the caller picks one per category and keeps it stable.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryChannelButton(
    label: String,
    imageUrl: String?,
    shape: RoundedPolygon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tonal = tonalPairFor(label)
    // Both are remembered per polygon and rebuild their outline only when their size changes. The
    // picture gets its own instance because it is smaller than the badge.
    val badgeShape = shape.toShape()
    val pictureShape = shape.toShape()
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    ChannelSlot(
        label = label,
        badgeShape = badgeShape,
        onClick = onClick,
        modifier = modifier,
        rippleColor = tonal.content,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tonal.container),
            contentAlignment = Alignment.Center,
        ) {
            if (!loaded) {
                Text(
                    text = label.take(1),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = tonal.content,
                )
            }
            if (!imageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { loaded = true },
                    onError = { loaded = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(HomeChannelDefaults.PictureRim)
                        .clip(pictureShape),
                )
            }
        }
    }
}

@Composable
private fun ChannelSlot(
    label: String,
    badgeShape: Shape,
    onClick: () -> Unit,
    modifier: Modifier,
    rippleColor: Color,
    badge: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .width(HomeChannelDefaults.ItemWidth)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(HomeChannelDefaults.BadgeSize)
                .clip(badgeShape)
                .indication(interactionSource, ripple(color = rippleColor)),
        ) {
            badge()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(showBackground = true, widthDp = 412)
@Composable
private fun HomeChannelRowPreview() {
    PicaTheme(themeChoice = ThemeChoice.Brand) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HomeChannelButton(HomeChannel.Rankings, "排行榜", onClick = {})
            HomeChannelButton(HomeChannel.Random, "随机", onClick = {})
            HomeChannelButton(HomeChannel.Games, "游戏专区", onClick = {})
            CategoryChannelButton("大家都在看", imageUrl = null, shape = MaterialShapes.Arch, onClick = {})
            CategoryChannelButton("大湿推荐", imageUrl = null, shape = MaterialShapes.Sunny, onClick = {})
        }
    }
}
