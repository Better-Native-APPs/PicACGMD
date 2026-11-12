package app.picacgmd.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The runtime harmonisation behind [PikapikaExtendedColors] under dynamic colour (audit §B7). It is
 * pure colour arithmetic, so it is pinned here: the hue follows the wallpaper, nothing else moves.
 */
class ColorHarmonizeTest {

    /** Hue in degrees (0..360). */
    private fun hue(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val span = max - min
        if (span < 1e-6f) return 0f
        val raw = when (max) {
            c.red -> 60f * (((c.green - c.blue) / span) % 6f)
            c.green -> 60f * (((c.blue - c.red) / span) + 2f)
            else -> 60f * (((c.red - c.green) / span) + 4f)
        }
        return (raw + 360f) % 360f
    }

    private val success = Color(0xFF2C6C18)
    private val neutral = Color(0xFF777777)

    @Test
    fun neutralSourceLeavesTheColourAlone() {
        // A desaturated primary has no hue to follow; the fixed set then keeps its own.
        assertEquals(neutral, neutral.harmonisedToward(Color(0xFF005A6E)))
        assertEquals(success, success.harmonisedToward(neutral))
    }

    @Test
    fun alreadyAlignedHueBarelyMoves() {
        // When the source sits on the same hue the shift is 0, and chroma / tone are untouched.
        assertEquals(success, success.harmonisedToward(success))
    }

    @Test
    fun hueMovesTowardTheSourceByASmallAmount() {
        val source = Color(0xFF006A60) // teal wallpaper primary
        val moved = success.harmonisedToward(source)
        val before = hue(success)
        val after = hue(moved)
        val sourceHue = hue(source)
        // It moves, it moves toward the source, and it stays well under 3 degrees.
        assertTrue(after != before, "hue did not move")
        assertTrue(abs(shortest(after - sourceHue)) < abs(shortest(before - sourceHue)), "moved away from source")
        assertTrue(abs(shortest(after - before)) < 3f, "shifted too far: ${after - before}")
    }

    @Test
    fun alphaAndToneSurvive() {
        val translucent = success.copy(alpha = 0.5f)
        val moved = translucent.harmonisedToward(Color(0xFF3F5F90))
        assertEquals(0.5f, moved.alpha, 0.01f)
        // Tone is preserved: the colour stays the same lightness, so it still reads at the same weight.
        assertEquals(success.brightness(), moved.brightness(), 0.02f)
    }

    @Test
    fun everyExtendedRoleIsHarmonised() {
        val source = Color(0xFF006A60)
        val set = BrandLightExtendedColors.harmonisedToward(source)
        assertEquals(BrandLightExtendedColors.success.harmonisedToward(source), set.success)
        assertEquals(BrandLightExtendedColors.warningContainer.harmonisedToward(source), set.warningContainer)
        assertEquals(BrandLightExtendedColors.onSuccessContainer.harmonisedToward(source), set.onSuccessContainer)
    }

    private fun shortest(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}

private fun Color.brightness(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
