package app.picacgmd.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The success / warning roles under dynamic colour.
 *
 * For every fixed palette the colour-token generator harmonises the two custom colours toward the
 * palette's seed with Material's rule and then builds the four roles of each from the harmonised hue;
 * the result ships in ColorTokens.kt. Under [ThemeChoice.Dynamic] the colour to harmonise toward is the
 * wallpaper's, known only at runtime, so the same two steps are done here, toward the scheme's primary
 * (Material's own choice where a scheme is all there is).
 *
 * Material's rule: in HCT, turn the hue toward the other colour by half the angle between them, by
 * 15 degrees at most; chroma and tone stay. The roles are then built from the hue and the chroma the
 * harmonised colour really has, which can be less than was asked for where sRGB ends.
 */

/** The custom colours before harmonisation, as the generator has them. */
private const val SuccessSeed = 0xFF2E9D5B.toInt()
private const val WarningSeed = 0xFFC98A00.toInt()

/** Material's limit for the turn of the hue. */
private const val MaxHueTurn = 15f

/** The generator's floor for the chroma of a role and of its "on" colour. */
private const val MinRoleChroma = 36f

private class Hct(argb: Int) {
    val hue: Float
    val chroma: Float
    val tone: Float

    init {
        val out = FloatArray(3)
        ColorUtils.colorToM3HCT(argb, out)
        hue = out[0]
        chroma = out[1]
        tone = out[2]
    }
}

private fun hct(hue: Float, chroma: Float, tone: Float): Color = Color(ColorUtils.M3HCTToColor(hue, chroma, tone))

/** The hue of [design] turned toward the hue of [source] by Material's rule, in degrees from 0 up to 360. */
internal fun harmonisedHue(design: Float, source: Float): Float {
    val gap = 180f - abs(abs(design - source) - 180f)
    val turn = min(gap * 0.5f, MaxHueTurn)
    val direction = if ((source - design + 360f) % 360f <= 180f) 1f else -1f
    return (design + turn * direction + 360f) % 360f
}

/** The four roles of one custom colour, with the tones and chromas the generator gives them. */
private class CustomRoles(val color: Color, val on: Color, val container: Color, val onContainer: Color)

private fun customRoles(seed: Int, source: Hct, dark: Boolean): CustomRoles {
    val design = Hct(seed)
    val harmonised = Hct(ColorUtils.M3HCTToColor(harmonisedHue(design.hue, source.hue), design.chroma, design.tone))
    val hue = harmonised.hue
    val chroma = max(harmonised.chroma, MinRoleChroma)
    return if (dark) {
        CustomRoles(hct(hue, chroma, 80f), hct(hue, chroma, 20f), hct(hue, 28f, 30f), hct(hue, 20f, 90f))
    } else {
        CustomRoles(hct(hue, chroma, 40f), hct(hue, chroma, 100f), hct(hue, 22f, 90f), hct(hue, chroma, 10f))
    }
}

/**
 * The success / warning roles harmonised toward [source], built the way the generator builds the set of a
 * fixed palette from that palette's seed.
 */
internal fun harmonisedExtendedColors(source: Color, dark: Boolean): PicaExtendedColors {
    val toward = Hct(source.toArgb())
    val success = customRoles(SuccessSeed, toward, dark)
    val warning = customRoles(WarningSeed, toward, dark)
    return PicaExtendedColors(
        success = success.color,
        onSuccess = success.on,
        successContainer = success.container,
        onSuccessContainer = success.onContainer,
        warning = warning.color,
        onWarning = warning.on,
        warningContainer = warning.container,
        onWarningContainer = warning.onContainer,
    )
}
