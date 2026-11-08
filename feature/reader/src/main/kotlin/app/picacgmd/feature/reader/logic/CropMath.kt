package app.picacgmd.feature.reader.logic

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Smallest crop, as a share of the image's width and height. */
internal const val MIN_CROP_SIZE: Float = 0.05f

/** Which part of the crop frame a drag grabbed. */
internal enum class CropHandle { TopLeft, Top, TopRight, Left, Right, BottomLeft, Bottom, BottomRight, Body }

/** Crop frame as fractions (0..1) of the image, so it does not depend on any decode size. */
internal data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Starting frame: a little inside the image, so that every handle can be seen and grabbed. */
internal val DefaultCropRect = CropRect(0.1f, 0.1f, 0.9f, 0.9f)

/** The frame after dragging [handle] by [dx], [dy] (fractions of the image). Stays inside the image. */
internal fun CropRect.dragged(handle: CropHandle, dx: Float, dy: Float, minSize: Float = MIN_CROP_SIZE): CropRect {
    if (handle == CropHandle.Body) {
        val newLeft = (left + dx).coerceIn(0f, 1f - width)
        val newTop = (top + dy).coerceIn(0f, 1f - height)
        return CropRect(newLeft, newTop, newLeft + width, newTop + height)
    }
    val movesLeft = handle == CropHandle.Left || handle == CropHandle.TopLeft || handle == CropHandle.BottomLeft
    val movesRight = handle == CropHandle.Right || handle == CropHandle.TopRight || handle == CropHandle.BottomRight
    val movesTop = handle == CropHandle.Top || handle == CropHandle.TopLeft || handle == CropHandle.TopRight
    val movesBottom = handle == CropHandle.Bottom || handle == CropHandle.BottomLeft || handle == CropHandle.BottomRight
    return CropRect(
        left = if (movesLeft) (left + dx).coerceIn(0f, right - minSize) else left,
        top = if (movesTop) (top + dy).coerceIn(0f, bottom - minSize) else top,
        right = if (movesRight) (right + dx).coerceIn(left + minSize, 1f) else right,
        bottom = if (movesBottom) (bottom + dy).coerceIn(top + minSize, 1f) else bottom,
    )
}

/**
 * The part of the frame under a finger at ([x], [y]), in pixels relative to the image's top left
 * corner, or null outside it. Corners win over edges and edges over the inside; on a small frame the
 * nearest edge wins, so it can always grow again.
 */
internal fun cropHandleAt(
    x: Float,
    y: Float,
    rect: CropRect,
    imageWidth: Float,
    imageHeight: Float,
    touchRadius: Float,
): CropHandle? {
    val left = rect.left * imageWidth
    val right = rect.right * imageWidth
    val top = rect.top * imageHeight
    val bottom = rect.bottom * imageHeight
    val toLeft = abs(x - left)
    val toRight = abs(x - right)
    val toTop = abs(y - top)
    val toBottom = abs(y - bottom)
    val nearSide = min(toLeft, toRight) <= touchRadius
    val nearEnd = min(toTop, toBottom) <= touchRadius
    val inSpanX = x >= left - touchRadius && x <= right + touchRadius
    val inSpanY = y >= top - touchRadius && y <= bottom + touchRadius
    val leftSide = toLeft <= toRight
    val topEnd = toTop <= toBottom
    return when {
        nearSide && nearEnd -> when {
            leftSide && topEnd -> CropHandle.TopLeft
            topEnd -> CropHandle.TopRight
            leftSide -> CropHandle.BottomLeft
            else -> CropHandle.BottomRight
        }
        nearSide && inSpanY -> if (leftSide) CropHandle.Left else CropHandle.Right
        nearEnd && inSpanX -> if (topEnd) CropHandle.Top else CropHandle.Bottom
        x in left..right && y in top..bottom -> CropHandle.Body
        else -> null
    }
}

/** A crop in image pixels: left and top inclusive, right and bottom exclusive. */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** [this] frame on an image of [imageWidth] × [imageHeight] pixels; never empty, never outside. */
internal fun CropRect.toPixelRect(imageWidth: Int, imageHeight: Int): PixelRect {
    val w = imageWidth.coerceAtLeast(1)
    val h = imageHeight.coerceAtLeast(1)
    val pixelLeft = (left * w).roundToInt().coerceIn(0, w - 1)
    val pixelTop = (top * h).roundToInt().coerceIn(0, h - 1)
    return PixelRect(
        left = pixelLeft,
        top = pixelTop,
        right = (right * w).roundToInt().coerceIn(pixelLeft + 1, w),
        bottom = (bottom * h).roundToInt().coerceIn(pixelTop + 1, h),
    )
}
