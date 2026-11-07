package app.picacgmd.feature.reader.logic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Zoom of a page, as applied by `graphicsLayer` with the pivot in the centre: [scale] around the
 * centre, then a translation of ([offsetX], [offsetY]) screen pixels.
 */
internal data class ZoomTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    companion object {
        val Identity = ZoomTransform(1f, 0f, 0f)
    }
}

internal const val ZOOM_EPSILON: Float = 0.01f

/** True when [scale] is meaningfully away from 1×. */
internal fun isZoomed(scale: Float): Boolean = abs(scale - 1f) > ZOOM_EPSILON

/**
 * Largest translation along one axis that keeps content of [content] px (at 1×), scaled by
 * [scale], from uncovering the [container]; 0 when the scaled content is not larger.
 */
internal fun maxOffset(container: Float, content: Float, scale: Float): Float =
    max(0f, (content * scale - container) / 2f)

/** Clamps the translation of [t] so the content never uncovers the container. */
internal fun clampTransform(
    t: ZoomTransform,
    containerWidth: Float,
    containerHeight: Float,
    contentWidth: Float,
    contentHeight: Float,
): ZoomTransform {
    val mx = maxOffset(containerWidth, contentWidth, t.scale)
    val my = maxOffset(containerHeight, contentHeight, t.scale)
    return t.copy(offsetX = t.offsetX.coerceIn(-mx, mx), offsetY = t.offsetY.coerceIn(-my, my))
}

/**
 * Applies one pinch step. ([focusX], [focusY]) is the current centroid relative to the container
 * centre and ([panX], [panY]) how far the centroid moved since the previous step: the content that
 * was under the previous centroid ends up under the current one. The result is not clamped.
 */
internal fun zoomAround(
    t: ZoomTransform,
    focusX: Float,
    focusY: Float,
    zoomChange: Float,
    panX: Float,
    panY: Float,
    minScale: Float,
    maxScale: Float,
): ZoomTransform {
    val newScale = (t.scale * zoomChange).coerceIn(minScale, maxScale)
    val k = newScale / t.scale
    val previousX = focusX - panX
    val previousY = focusY - panY
    return ZoomTransform(
        scale = newScale,
        offsetX = focusX - (previousX - t.offsetX) * k,
        offsetY = focusY - (previousY - t.offsetY) * k,
    )
}

/**
 * Where a double tap at ([focusX], [focusY]) (relative to the centre) leads: back to 1× when the
 * page is zoomed in or out, otherwise to [doubleTapScale] around the tapped point, clamped.
 */
internal fun doubleTapTarget(
    current: ZoomTransform,
    focusX: Float,
    focusY: Float,
    doubleTapScale: Float,
    containerWidth: Float,
    containerHeight: Float,
    contentWidth: Float,
    contentHeight: Float,
): ZoomTransform {
    if (isZoomed(current.scale)) return ZoomTransform.Identity
    val zoomed = zoomAround(
        t = current,
        focusX = focusX,
        focusY = focusY,
        zoomChange = doubleTapScale / current.scale,
        panX = 0f,
        panY = 0f,
        minScale = min(1f, doubleTapScale),
        maxScale = max(1f, doubleTapScale),
    )
    return clampTransform(zoomed, containerWidth, containerHeight, contentWidth, contentHeight)
}

/** Size of an [intrinsicWidth] × [intrinsicHeight] image fitted (ContentScale.Fit) in the container. */
internal fun fitSize(
    intrinsicWidth: Float,
    intrinsicHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
): Pair<Float, Float> {
    if (!(intrinsicWidth > 0f && intrinsicHeight > 0f) || intrinsicWidth.isInfinite() || intrinsicHeight.isInfinite()) {
        return containerWidth to containerHeight
    }
    val s = min(containerWidth / intrinsicWidth, containerHeight / intrinsicHeight)
    return intrinsicWidth * s to intrinsicHeight * s
}

/**
 * Whether dragging by [delta] along one axis pushes against the edge of the zoomed content, i.e.
 * the content cannot move further that way and the gesture should go to the pager instead.
 */
internal fun atPanEdge(offset: Float, maxOffset: Float, delta: Float): Boolean = when {
    delta > 0f -> offset >= maxOffset - 0.5f
    delta < 0f -> offset <= -maxOffset + 0.5f
    else -> false
}

/**
 * Moves a zoomed continuous strip vertically by [dy] screen pixels. The translation is only used at
 * the two ends of the list, where scrolling cannot go further: an existing translation is undone
 * first, then the list scrolls ([scrollBy] takes screen pixels and returns how many it consumed),
 * and whatever is left moves the translation towards [maxOffsetY]. Returns the new translation.
 */
internal fun distributeVerticalShift(
    dy: Float,
    offsetY: Float,
    maxOffsetY: Float,
    scrollBy: (Float) -> Float,
): Float {
    var remaining = dy
    var offset = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
    if (remaining > 0f && offset < 0f) {
        val take = min(remaining, -offset)
        offset += take
        remaining -= take
    } else if (remaining < 0f && offset > 0f) {
        val take = max(remaining, -offset)
        offset += take
        remaining -= take
    }
    if (remaining != 0f) remaining -= scrollBy(remaining)
    if (remaining != 0f) offset = (offset + remaining).coerceIn(-maxOffsetY, maxOffsetY)
    return offset
}
