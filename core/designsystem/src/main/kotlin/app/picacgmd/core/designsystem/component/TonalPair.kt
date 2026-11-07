package app.picacgmd.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

@Immutable
internal data class TonalPair(val container: Color, val content: Color)

/**
 * A stable primary / secondary / tertiary container pair for [key], used as the stand-in while an
 * image-backed tile (category, pinned category) has no picture. Keeps grids colorful but on-theme.
 */
@Composable
@ReadOnlyComposable
internal fun tonalPairFor(key: String): TonalPair {
    val colors = MaterialTheme.colorScheme
    return when (Math.floorMod(key.hashCode(), 3)) {
        0 -> TonalPair(colors.primaryContainer, colors.onPrimaryContainer)
        1 -> TonalPair(colors.secondaryContainer, colors.onSecondaryContainer)
        else -> TonalPair(colors.tertiaryContainer, colors.onTertiaryContainer)
    }
}
