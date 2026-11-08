package app.picacgmd.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.transform.Transformation
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A soft, out-of-focus wash of [model] (a cover or banner URL) for atmospheric header backdrops.
 *
 * The blur is baked once into a thumbnail a few dozen pixels wide, off the main thread, and kept in
 * Coil's memory cache; on screen it is just that bitmap stretched with bilinear filtering. So it looks
 * the same on every API level (Modifier.blur is a no-op before API 31) and costs nothing per frame:
 * no RenderEffect, no offscreen layer, nothing re-rendered while the page scrolls.
 *
 * Draws nothing until the image arrives; lay a scrim on top for text contrast.
 */
@Composable
fun SoftBackdrop(model: String?, modifier: Modifier = Modifier) {
    if (model.isNullOrEmpty()) return
    val context = LocalPlatformContext.current
    val request = remember(model, context) {
        ImageRequest.Builder(context)
            .data(model)
            // Let the decoder subsample a large cover straight down; the transformation finishes the job.
            .size(BackdropDecodePx)
            .precision(Precision.INEXACT)
            .transformations(SoftBlurTransformation)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Low, // bilinear: smooth upscaling of the tiny pre-blurred bitmap
        modifier = modifier,
    )
}

/** Decode bound for the source image: enough for the sampler to land near the thumbnail size. */
private const val BackdropDecodePx = 96

/** Long edge of the blurred thumbnail. The backdrop is this many pixels across, stretched. */
private const val ThumbLongEdgePx = 40

/** Box-blur radius in thumbnail pixels; three passes approximate a Gaussian. */
private const val BlurRadiusPx = 2
private const val BlurPasses = 3

/**
 * Shrinks to a [ThumbLongEdgePx] thumbnail and box-blurs it. Working on ~40 × 56 pixels, this is a few
 * thousand additions once per image. Being a transformation also gives the result its own memory-cache
 * entry, so it never displaces the sharp cover that lists and the detail header show.
 */
private object SoftBlurTransformation : Transformation() {
    override val cacheKey: String = "pikapika.softBackdrop.$ThumbLongEdgePx.$BlurRadiusPx.$BlurPasses"

    override suspend fun transform(input: Bitmap, size: coil3.size.Size): Bitmap {
        val scale = ThumbLongEdgePx.toFloat() / max(input.width, input.height)
        val width = (input.width * scale).roundToInt().coerceAtLeast(1)
        val height = (input.height * scale).roundToInt().coerceAtLeast(1)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(input, width, height, true) else input
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== input) small.recycle()
        boxBlur(pixels, w, h, BlurRadiusPx, BlurPasses)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}

/** In-place separable box blur of ARGB [pixels]; edges clamp to the border pixel. */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int) {
    if (radius <= 0 || width <= 0 || height <= 0) return
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        blurLine(pixels, scratch, width, height, radius, horizontal = true)
        blurLine(scratch, pixels, width, height, radius, horizontal = false)
    }
}

private fun blurLine(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val window = radius * 2 + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, length - 1)
            return if (horizontal) src[line * width + c] else src[c * width + line]
        }
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = at(i)
            a += p ushr 24
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until length) {
            val out = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            if (horizontal) dst[line * width + i] = out else dst[i * width + line] = out
            val leaving = at(i - radius)
            val entering = at(i + radius + 1)
            a += (entering ushr 24) - (leaving ushr 24)
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
