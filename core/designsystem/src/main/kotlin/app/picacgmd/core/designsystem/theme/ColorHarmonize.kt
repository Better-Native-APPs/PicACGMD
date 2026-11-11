package app.picacgmd.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.cbrt

/*
 * Runtime harmonisation of the extended roles under dynamic colour (design/SPEC.md 104).
 *
 * The design pipeline (design/src/gen-tokens.mjs) harmonises the success / warning seed colours
 * toward each palette's seed and then rebuilds their tones; tokens.json therefore ships an already
 * harmonised set for every fixed palette. Under [ThemeChoice.Dynamic] the source colour is the
 * user's wallpaper, which is only known at runtime, so the same rule is applied here to the brand
 * set: rotate the custom hue toward the scheme's primary.
 *
 * Material's harmonise changes the hue alone and by a very small amount (the angular difference is
 * cube-rooted and then halved, so even a 180° gap moves the hue under 3°). Chroma and tone are
 * kept, which is what makes the roles still read as "the same green" on a different wallpaper.
 * The hue is compared in HSL here rather than cam16 — with a shift this small the two hue scales
 * differ by a fraction of a degree, and HSL keeps the design system free of the colour-science
 * dependency the build-time generator uses.
 */

/** Below this saturation a colour is treated as neutral: it has no hue worth moving. */
private const val NeutralSaturation = 0.01f

/** The M3 harmonise shift: cube-root the angular gap (signed) and take half of it. */
private fun harmonisedHueDelta(degrees: Float): Float {
    val signed = if (degrees < 0f) -cbrt(-degrees.toDouble()).toFloat() else cbrt(degrees.toDouble()).toFloat()
    return 0.5f * signed
}

/** Shortest signed angle from [from] to [to], in (-180, 180]. */
private fun shortestHueDelta(from: Float, to: Float): Float {
    var delta = to - from
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

/** Hue in degrees plus saturation / lightness in 0..1; alpha is carried separately. */
private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float, val alpha: Float)

private fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val span = max - min
    if (span < 1e-6f) return Hsl(0f, 0f, lightness, alpha)
    val saturation = if (lightness > 0.5f) span / (2f - max - min) else span / (max + min)
    val hue = when (max) {
        r -> 60f * (((g - b) / span) % 6f)
        g -> 60f * (((b - r) / span) + 2f)
        else -> 60f * (((r - g) / span) + 4f)
    }
    return Hsl((hue + 360f) % 360f, saturation, lightness, alpha)
}

private fun fromHsl(hsl: Hsl): Color {
    val c = (1f - abs(2f * hsl.lightness - 1f)) * hsl.saturation
    val x = c * (1f - abs((hsl.hue / 60f) % 2f - 1f))
    val m = hsl.lightness - c / 2f
    val (r, g, b) = when {
        hsl.hue < 60f -> Triple(c, x, 0f)
        hsl.hue < 120f -> Triple(x, c, 0f)
        hsl.hue < 180f -> Triple(0f, c, x)
        hsl.hue < 240f -> Triple(0f, x, c)
        hsl.hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m, alpha = hsl.alpha)
}

/**
 * Moves this colour's hue toward [source] by the Material harmonise rule, keeping saturation,
 * lightness and alpha. A neutral colour on either side is returned unchanged.
 */
internal fun Color.harmonisedToward(source: Color): Color {
    val hsl = toHsl()
    if (hsl.saturation < NeutralSaturation) return this
    val sourceHsl = source.toHsl()
    if (sourceHsl.saturation < NeutralSaturation) return this
    val shift = harmonisedHueDelta(shortestHueDelta(hsl.hue, sourceHsl.hue))
    return fromHsl(hsl.copy(hue = (hsl.hue + shift + 360f) % 360f))
}

/**
 * Harmonises every extended role toward [source] (the live scheme's primary under dynamic colour),
 * so green and amber drift with the wallpaper like the rest of the scheme.
 */
internal fun PikapikaExtendedColors.harmonisedToward(source: Color): PikapikaExtendedColors = PikapikaExtendedColors(
    success = success.harmonisedToward(source),
    onSuccess = onSuccess.harmonisedToward(source),
    successContainer = successContainer.harmonisedToward(source),
    onSuccessContainer = onSuccessContainer.harmonisedToward(source),
    warning = warning.harmonisedToward(source),
    onWarning = onWarning.harmonisedToward(source),
    warningContainer = warningContainer.harmonisedToward(source),
    onWarningContainer = onWarningContainer.harmonisedToward(source),
)
