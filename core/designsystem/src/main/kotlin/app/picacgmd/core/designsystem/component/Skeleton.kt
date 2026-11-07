package app.picacgmd.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import kotlinx.coroutines.launch

/**
 * Skeleton shimmer: a soft diagonal [highlight] band sweeping across the content, drawn on top of
 * whatever is underneath (apply after `clip` + `background`). Stays still when the system has
 * animations turned off.
 */
fun Modifier.shimmer(highlight: Color): Modifier = this then ShimmerElement(highlight)

/** Placeholder block for loading skeletons: `surfaceContainerHigh` with [shimmer]. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .shimmer(colors.surfaceBright.copy(alpha = 0.7f)),
    )
}

private data class ShimmerElement(val highlight: Color) : ModifierNodeElement<ShimmerNode>() {
    override fun create() = ShimmerNode(highlight)

    override fun update(node: ShimmerNode) {
        node.highlight = highlight
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shimmer"
        properties["highlight"] = highlight
    }
}

private class ShimmerNode(var highlight: Color) : Modifier.Node(), DrawModifierNode {
    private val progress = Animatable(0f)

    override fun onAttach() {
        val durationScale = coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (durationScale == 0f) return
        coroutineScope.launch {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1300, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val band = size.width.coerceAtLeast(size.height) * 0.6f
        val travel = size.width + band * 2
        val x = -band + travel * progress.value
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(x - band, 0f),
                end = Offset(x + band, size.height),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun SkeletonPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth().height(88.dp), MaterialTheme.shapes.extraLarge)
            SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(12.dp))
        }
    }
}
