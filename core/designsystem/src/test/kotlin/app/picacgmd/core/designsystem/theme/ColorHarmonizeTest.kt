package app.picacgmd.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The success / warning roles under dynamic colour. The shipped sets of the fixed palettes were made by the
 * colour-token generator with Material's own colour library, so they are the reference: built here toward
 * the same seeds, the roles have to come out as those sets.
 */
@RunWith(RobolectricTestRunner::class)
class ColorHarmonizeTest {
    @Test
    fun theHueTurnsByHalfTheGapTowardTheSource() {
        assertEquals(110f, harmonisedHue(design = 100f, source = 120f), 1e-4f)
        assertEquals(90f, harmonisedHue(design = 100f, source = 80f), 1e-4f)
    }

    @Test
    fun theHueTurnsByFifteenDegreesAtMost() {
        assertEquals(115f, harmonisedHue(design = 100f, source = 160f), 1e-4f)
        assertEquals(85f, harmonisedHue(design = 100f, source = 20f), 1e-4f)
        assertEquals(115f, harmonisedHue(design = 100f, source = 280f), 1e-4f)
    }

    @Test
    fun theHueTurnsTheShortWayAcrossZero() {
        assertEquals(355f, harmonisedHue(design = 350f, source = 0f), 1e-4f)
        assertEquals(0f, harmonisedHue(design = 350f, source = 10f), 1e-4f)
        assertEquals(5f, harmonisedHue(design = 350f, source = 40f), 1e-4f)
        assertEquals(355f, harmonisedHue(design = 10f, source = 320f), 1e-4f)
    }

    @Test
    fun aSourceOfTheSameHueLeavesTheHueAlone() {
        assertEquals(100f, harmonisedHue(design = 100f, source = 100f), 1e-4f)
    }

    @Test
    fun theRolesBuiltTowardTheSeedOfAFixedPaletteAreItsShippedRoles() {
        val palettes = listOf(
            Triple("brand", Color(0xFFE8467C), BrandLightExtendedColors to BrandDarkExtendedColors),
            Triple("book", Color(0xFFA5824F), BookLightExtendedColors to BookDarkExtendedColors),
            Triple("dusty", Color(0xFF5F7F9E), DustyLightExtendedColors to DustyDarkExtendedColors),
        )
        val differences = buildList {
            for ((name, seed, shipped) in palettes) {
                addAll(differences("$name light", harmonisedExtendedColors(seed, dark = false), shipped.first))
                addAll(differences("$name dark", harmonisedExtendedColors(seed, dark = true), shipped.second))
            }
        }
        assertEquals(48, differences.size)
        val worst = differences.maxBy { it.second }
        assertTrue(worst.second <= ChannelTolerance, "largest difference of a colour channel: $worst; all: ${differences.filter { it.second > 0 }}")
    }

    @Test
    fun aSourceOfAnotherHueMovesTheRolesAndKeepsTheirTones() {
        val pink = harmonisedExtendedColors(Color(0xFFE8467C), dark = false)
        val teal = harmonisedExtendedColors(Color(0xFF006A60), dark = false)
        assertTrue(pink.success != teal.success)
        assertTrue(pink.warning != teal.warning)
        assertEquals(Color.White, teal.onSuccess)
        assertEquals(Color.White, teal.onWarning)
        // Tone is what the roles are told apart by, so it does not follow the wallpaper.
        assertEquals(40f, tone(pink.success), 1f)
        assertEquals(40f, tone(teal.success), 1f)
        assertEquals(90f, tone(pink.warningContainer), 1f)
        assertEquals(90f, tone(teal.warningContainer), 1f)
    }

    private fun differences(palette: String, built: PicaExtendedColors, shipped: PicaExtendedColors): List<Pair<String, Int>> =
        listOf(
            "success" to (built.success to shipped.success),
            "onSuccess" to (built.onSuccess to shipped.onSuccess),
            "successContainer" to (built.successContainer to shipped.successContainer),
            "onSuccessContainer" to (built.onSuccessContainer to shipped.onSuccessContainer),
            "warning" to (built.warning to shipped.warning),
            "onWarning" to (built.onWarning to shipped.onWarning),
            "warningContainer" to (built.warningContainer to shipped.warningContainer),
            "onWarningContainer" to (built.onWarningContainer to shipped.onWarningContainer),
        ).map { (role, pair) -> "$palette $role" to channelDifference(pair.first, pair.second) }

    private fun channelDifference(a: Color, b: Color): Int {
        val x = a.toArgb()
        val y = b.toArgb()
        return listOf(24, 16, 8, 0).maxOf { shift -> abs(((x ushr shift) and 0xFF) - ((y ushr shift) and 0xFF)) }
    }

    private fun tone(c: Color): Float = FloatArray(3).also { ColorUtils.colorToM3HCT(c.toArgb(), it) }[2]

    private companion object {
        /**
         * The generator's colour library and AndroidX Core solve HCT to sRGB differently where a colour is asked
         * for that sRGB does not have (a dark tone with much chroma): there one channel differs by up to 6 of 255.
         * A wrong rule is off by far more, a hue turned 12 degrees less by dozens.
         */
        const val ChannelTolerance = 6
    }
}
