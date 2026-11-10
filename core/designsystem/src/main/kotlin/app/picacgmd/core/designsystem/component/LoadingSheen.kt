package app.picacgmd.core.designsystem.component

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The loading look of a picture that has not arrived yet (2026-11-10): a soft band of [highlight]
 * crossing the tonal placeholder from the start edge, then a short rest, for as long as [active].
 * Apply it after `clip` + `background`; the band is drawn under the content.
 *
 * - It only appears once the wait has lasted [SheenDelayMillis], so a picture that comes straight
 *   from the cache never flickers.
 * - Every placeholder on screen sweeps in step (the band's place comes from the frame time), which
 *   reads as one quiet wave across a grid instead of many separate blinks.
 * - It runs in the draw phase alone: no recomposition, no layout, no allocation per frame, and no
 *   frames at all while the band rests.
 * - With system animations off the placeholder stays still.
 */
fun Modifier.loadingSheen(active: Boolean, highlight: Color): Modifier =
    if (active) this then LoadingSheenElement(highlight) else this

internal const val SheenDelayMillis = 220f
internal const val SheenFadeInMillis = 320f
internal const val SheenSweepMillis = 1_100L
internal const val SheenRestMillis = 500L

/**
 * Where the band is at [frameMillis]: 0 = about to enter, 1 = just left, eased at both ends; null
 * while it rests between two sweeps.
 */
internal fun sheenSweep(frameMillis: Long): Float? {
    val inCycle = Math.floorMod(frameMillis, SheenSweepMillis + SheenRestMillis)
    if (inCycle >= SheenSweepMillis) return null
    val linear = inCycle.toFloat() / SheenSweepMillis
    return linear * linear * (3f - 2f * linear)
}

/** 0 → 1 once a placeholder has waited [SheenDelayMillis], over [SheenFadeInMillis]. */
internal fun sheenAppear(waitedMillis: Float): Float =
    ((waitedMillis - SheenDelayMillis) / SheenFadeInMillis).coerceIn(0f, 1f)

/**
 * How long nothing has to be drawn from [frameMillis] on, for a placeholder that has waited
 * [waitedMillis]: until it may first appear, or until the next sweep starts. 0 while the band is
 * on screen.
 */
internal fun sheenIdleMillis(frameMillis: Long, waitedMillis: Float): Long {
    if (waitedMillis <= SheenDelayMillis) return (SheenDelayMillis - waitedMillis).toLong() + 1
    if (sheenSweep(frameMillis) != null) return 0
    val cycle = SheenSweepMillis + SheenRestMillis
    return cycle - Math.floorMod(frameMillis, cycle)
}

private data class LoadingSheenElement(val highlight: Color) : ModifierNodeElement<LoadingSheenNode>() {
    override fun create() = LoadingSheenNode(highlight)

    override fun update(node: LoadingSheenNode) {
        node.highlight = highlight
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "loadingSheen"
        properties["highlight"] = highlight
    }
}

private class LoadingSheenNode(var highlight: Color) : Modifier.Node(), DrawModifierNode {
    private var attachedAtNanos = Long.MIN_VALUE
    private var frameNanos = 0L
    private var band: Brush? = null
    private var bandWidth = 0f
    private var bandColor = Color.Unspecified

    override fun onAttach() {
        attachedAtNanos = Long.MIN_VALUE
        val durationScale = coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (durationScale == 0f) return
        coroutineScope.launch {
            var drawn = false
            while (true) {
                withFrameNanos { now ->
                    if (attachedAtNanos == Long.MIN_VALUE) attachedAtNanos = now
                    frameNanos = now
                }
                val waited = (frameNanos - attachedAtNanos) / 1e6f
                val idle = sheenIdleMillis(frameNanos / 1_000_000, waited)
                val visible = idle == 0L
                // One more frame after the band left wipes it.
                if (visible || drawn) invalidateDraw()
                drawn = visible
                // Asleep until there is something to draw again: no frame is asked for meanwhile.
                if (!visible) delay(idle)
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val appear = if (attachedAtNanos == Long.MIN_VALUE) 0f else sheenAppear((frameNanos - attachedAtNanos) / 1e6f)
        val sweep = if (appear > 0f) sheenSweep(frameNanos / 1_000_000) else null
        if (sweep != null && size.width > 0f) {
            val width = size.width * BandWidthFraction
            val slant = width * BandSlant
            val brush = band?.takeIf { bandWidth == width && bandColor == highlight }
                ?: Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to highlight,
                    1f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(width, slant),
                ).also {
                    band = it
                    bandWidth = width
                    bandColor = highlight
                }
            // The band leans, so it has to start further out to be clear of the bottom edge too.
            val lean = size.height * BandSlant
            val from = -width - slant
            val to = size.width + lean
            val x = from + (to - from) * sweep
            translate(left = x) {
                drawRect(brush = brush, topLeft = Offset(-x, 0f), size = size, alpha = appear)
            }
        }
        drawContent()
    }
}

private const val BandWidthFraction = 0.7f
private const val BandSlant = 0.3f
