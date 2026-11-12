package app.picacgmd.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.picacgmd.core.designsystem.R

/**
 * Icons that are not part of Material Symbols. The three home channel icons are the fixed channel
 * artwork chosen in the design spec; they are single-color and take the tint of their container.
 */
object PikapikaIcons {
    /** 排行榜: three podium bars with stars. */
    val Rankings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_channel_rankings)

    /** 随机: crossing shuffle arrows. */
    val Random: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_channel_random)

    /** 游戏专区: gamepad. */
    val Gamepad: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_channel_gamepad)
}
