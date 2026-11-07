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
import app.picacgmd.core.designsystem.icon.PikapikaIcons
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

object HomeChannelDefaults {
    /** Slot width; five slots plus 12dp side padding fit a 412dp phone. */
    val ItemWidth: Dp = 72.dp

    /** Diameter of the shaped badge. */
    val BadgeSize: Dp = 64.dp

    /** Icon size inside the badge. */
    val IconSize: Dp = 28.dp
}

/** The three fixed home channels of design/SPEC.md §0, with their board-01 shape and color. */
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
 * One of the fixed channels: 排行榜 = Cookie6Sided on primary container, 随机 = Clover4Leaf on
 * tertiary container, 游戏专区 = Flower (board 01 reserves it for the game entry) on the highest
 * surface container. Icons come from [PikapikaIcons].
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
            label, PikapikaIcons.Rankings, MaterialShapes.Cookie6Sided, onClick, modifier,
            colors.primaryContainer, colors.onPrimaryContainer,
        )
        HomeChannel.Random -> HomeChannelButton(
            label, PikapikaIcons.Random, MaterialShapes.Clover4Leaf, onClick, modifier,
            colors.tertiaryContainer, colors.onTertiaryContainer,
        )
        HomeChannel.Games -> HomeChannelButton(
            label, PikapikaIcons.Gamepad, MaterialShapes.Flower, onClick, modifier,
            colors.surfaceContainerHighest, colors.onSurface,
        )
    }
}

/**
 * A category the user pinned to the home row (「固定到首页」). Shows the category thumbnail with
 * plain 20dp corners — pictures never get decorative shapes — or a tonal tile with the first
 * character while there is no picture.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryChannelButton(
    label: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tonal = tonalPairFor(label)
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    ChannelSlot(
        label = label,
        badgeShape = MaterialTheme.shapes.largeIncreased,
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
                    modifier = Modifier.fillMaxSize(),
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

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun HomeChannelRowPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HomeChannelButton(HomeChannel.Rankings, "排行榜", onClick = {})
            HomeChannelButton(HomeChannel.Random, "随机", onClick = {})
            HomeChannelButton(HomeChannel.Games, "游戏专区", onClick = {})
            CategoryChannelButton("大家都在看", imageUrl = null, onClick = {})
            CategoryChannelButton("大湿推荐", imageUrl = null, onClick = {})
        }
    }
}
