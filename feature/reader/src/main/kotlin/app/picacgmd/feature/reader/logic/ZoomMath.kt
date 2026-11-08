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

/**
 * Where a screen position lands in the unzoomed content along one axis, for a layer scaled by
 * [scale] around [center] and then moved by [offset].
 */
internal fun screenToContent(screen: Float, center: Float, scale: Float, offset: Float): Float =
    center + (screen - center - offset) / scale.coerceAtLeast(0.01f)

/** Smallest scale the strip layout accounts for; guards the divisions below. */
private const val MIN_LAYOUT_SCALE: Float = 0.1f

/**
 * Height a zoomable strip is laid out at: the viewport at 1× and above, and proportionally more of
 * the strip below 1× (自由缩放), so the zoomed-out strip still fills the screen from top to bottom.
 */
internal fun stripListHeight(viewportHeight: Float, scale: Float): Float =
    viewportHeight / scale.coerceIn(MIN_LAYOUT_SCALE, 1f)

/**
 * Largest translation along one axis. The content edges stop at the container edges, and with
 * 锁定拖动边界 off they may go [slack] × the container further.
 */
internal fun panLimit(container: Float, content: Float, scale: Float, slack: Float): Float =
    maxOffset(container, content, scale) + container * slack.coerceAtLeast(0f)

/**
 * Where a screen position lands in a list laid out [listSize] tall (or wide), centred on a
 * [screenSize] screen, scaled by [scale] around the centre and then moved by [offset].
 */
internal fun screenToList(screen: Float, screenSize: Float, listSize: Float, scale: Float, offset: Float): Float =
    listSize / 2f + (screen - screenSize / 2f - offset) / scale.coerceAtLeast(MIN_LAYOUT_SCALE)

/** A rectangle in fractions (0..1) of a page. */
internal data class PageFraction(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The part of a strip page that is on screen, in fractions of the page, for the minimap; null when
 * none of it is. Strip pages span the full width at 1×; [pageTop] and [pageHeight] are the page's
 * position in the list as laid out ([listHeight] tall, see [stripListHeight]).
 */
internal fun visiblePageFraction(
    screenWidth: Float,
    screenHeight: Float,
    listHeight: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    pageTop: Float,
    pageHeight: Float,
): PageFraction? {
    if (screenWidth <= 0f || screenHeight <= 0f || pageHeight <= 0f) return null
    val left = screenToList(0f, screenWidth, screenWidth, scale, offsetX) / screenWidth
    val right = screenToList(screenWidth, screenWidth, screenWidth, scale, offsetX) / screenWidth
    val top = (screenToList(0f, screenHeight, listHeight, scale, offsetY) - pageTop) / pageHeight
    val bottom = (screenToList(screenHeight, screenHeight, listHeight, scale, offsetY) - pageTop) / pageHeight
    val clipped = PageFraction(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f),
    )
    return if (clipped.left < clipped.right && clipped.top < clipped.bottom) clipped else null
}

/**
 * The pan, in screen pixels, that brings the point ([u], [v]) of a strip page (fractions) to the
 * centre of the screen. Same geometry as [visiblePageFraction].
 */
internal fun panToPagePoint(
    u: Float,
    v: Float,
    screenWidth: Float,
    screenHeight: Float,
    listHeight: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    pageTop: Float,
    pageHeight: Float,
): Pair<Float, Float> {
    val centerX = screenToList(screenWidth / 2f, screenWidth, screenWidth, scale, offsetX)
    val centerY = screenToList(screenHeight / 2f, screenHeight, listHeight, scale, offsetY)
    val targetX = u.coerceIn(0f, 1f) * screenWidth
    val targetY = pageTop + v.coerceIn(0f, 1f) * pageHeight
    return -(targetX - centerX) * scale to -(targetY - centerY) * scale
}

/**
 * Size of the minimap for a page of [aspect] (height / width): [maxWidth] wide, but narrower for
 * tall pages so that it is never taller than [maxHeight]; never narrower than [minWidth].
 */
internal fun minimapSize(aspect: Float, maxWidth: Float, maxHeight: Float, minWidth: Float): Pair<Float, Float> {
    val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
    val height = maxWidth * a
    if (height <= maxHeight) return maxWidth to height
    return (maxHeight / a).coerceAtLeast(minWidth) to maxHeight
}
