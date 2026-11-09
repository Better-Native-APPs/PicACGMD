package app.picacgmd.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import kotlinx.coroutines.launch

/**
 * Static sheen: a soft diagonal [highlight] fading out from the top-start corner, drawn over the
 * content (apply after `clip` + `background`). It never animates, so it costs nothing once drawn.
 */
fun Modifier.shimmer(highlight: Color): Modifier = drawWithCache {
    val brush = Brush.linearGradient(
        colors = listOf(highlight, Color.Transparent),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    onDrawWithContent {
        drawContent()
        drawRect(brush)
    }
}

/**
 * Placeholder block for loading skeletons: a still `surfaceContainerHigh` surface in [shape]. It fades
 * in once when it appears and then stays put, so a long wait on a slow network draws no frames.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    Box(modifier then SkeletonFillElement(MaterialTheme.colorScheme.surfaceContainerHigh, shape))
}

private const val SkeletonFadeInMillis = 220

private data class SkeletonFillElement(val color: Color, val shape: Shape) : ModifierNodeElement<SkeletonFillNode>() {
    override fun create() = SkeletonFillNode(color, shape)

    override fun update(node: SkeletonFillNode) {
        node.update(color, shape)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "skeletonFill"
        properties["color"] = color
        properties["shape"] = shape
    }
}

/** Fills the shape directly (no clip layer) and fades it in once. */
private class SkeletonFillNode(
    private var color: Color,
    private var shape: Shape,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)
    private var outline: Outline? = null
    private var outlineSize = Size.Unspecified
    private var outlineDirection: LayoutDirection? = null

    fun update(color: Color, shape: Shape) {
        if (shape != this.shape) outline = null
        this.color = color
        this.shape = shape
        invalidateDraw()
    }

    override fun onAttach() {
        // CompositionLocals can only be read safely once this composition has been applied.
        sideEffect {
            if (!isAttached) return@sideEffect
            val durationScale = coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            val still = durationScale == 0f || currentValueOf(LocalInspectionMode)
            coroutineScope.launch {
                if (still) {
                    alpha.snapTo(1f)
                } else {
                    alpha.animateTo(1f, tween(SkeletonFadeInMillis, easing = LinearOutSlowInEasing))
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val cached = outline
        val shapeOutline = if (cached != null && outlineSize == size && outlineDirection == layoutDirection) {
            cached
        } else {
            shape.createOutline(size, layoutDirection, this).also {
                outline = it
                outlineSize = size
                outlineDirection = layoutDirection
            }
        }
        drawOutline(shapeOutline, color, alpha = alpha.value)
        drawContent()
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
