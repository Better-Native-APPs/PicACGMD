package app.picacgmd.feature.reader.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import app.picacgmd.feature.reader.logic.ZOOM_EPSILON
import app.picacgmd.feature.reader.logic.ZoomTransform
import app.picacgmd.feature.reader.logic.atPanEdge
import app.picacgmd.feature.reader.logic.distributeVerticalShift
import app.picacgmd.feature.reader.logic.doubleTapTarget
import app.picacgmd.feature.reader.logic.panLimit
import app.picacgmd.feature.reader.logic.stripListHeight
import app.picacgmd.feature.reader.logic.zoomAround
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import app.picacgmd.feature.reader.logic.isZoomed as isZoomedScale

private val ZoomSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * Zoom of the page (paged modes) or of the whole strip. All values are screen pixels; the
 * transform is applied with [applyTo] inside `graphicsLayer`, pivot in the centre.
 *
 * A strip (see [listScroller]) keeps its translation small: vertical moves scroll the list, and the
 * translation only takes what the list cannot, at its two ends.
 */
@Stable
internal class ZoomState(private val scope: CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    var minScale: Float = 1f
    var maxScale: Float = 3f

    /** Multiplies one-finger drags while zoomed (手势速度倍率). */
    var gestureSpeed: Float = 1f

    /**
     * Continuous strips only: scrolls the list by the given list pixels (positive scrolls forward)
     * and returns how many it consumed. Without it, vertical moves translate the page.
     */
    var listScroller: ((Float) -> Float)? = null

    /**
     * The strip is laid out [stripListHeight] tall, so below 1× it grows with the zoom. Its centre
     * has to stay put while it grows, which takes a scroll of half the growth.
     */
    var listGrowsWhenZoomedOut: Boolean = false

    /** How far past the content edges a drag may go, as a fraction of the container (锁定拖动边界 off). */
    var panSlack: Float = 0f

    var containerWidth = 0f
        private set
    var containerHeight = 0f
        private set
    private var contentWidth = 0f
    private var contentHeight = 0f
    private var animation: Job? = null

    val isZoomed: Boolean get() = isZoomedScale(scale)

    /** Above 1×: part of the page is off screen. */
    val isZoomedIn: Boolean get() = scale > 1f + ZOOM_EPSILON
    val maxOffsetX: Float get() = panLimit(containerWidth, contentWidth, scale, panSlack)
    val maxOffsetY: Float get() = panLimit(containerHeight, contentHeight, scale, panSlack)
    private val transform: ZoomTransform get() = ZoomTransform(scale, offsetX, offsetY)

    /** Container and (unzoomed) content size; the content defaults to filling the container. */
    fun setGeometry(
        containerWidth: Float,
        containerHeight: Float,
        contentWidth: Float = containerWidth,
        contentHeight: Float = containerHeight,
    ) {
        if (containerWidth == this.containerWidth && containerHeight == this.containerHeight &&
            contentWidth == this.contentWidth && contentHeight == this.contentHeight
        ) {
            return
        }
        this.containerWidth = containerWidth
        this.containerHeight = containerHeight
        this.contentWidth = contentWidth
        this.contentHeight = contentHeight
        clampToBounds()
    }

    /** Pulls the translation back inside the bounds, for example after 锁定拖动边界 was turned on. */
    fun clampToBounds() {
        val mx = maxOffsetX
        val my = maxOffsetY
        offsetX = offsetX.coerceIn(-mx, mx)
        offsetY = offsetY.coerceIn(-my, my)
    }

    fun gestureStarted() {
        animation?.cancel()
        animation = null
    }

    /** Runs [block] (a zoom animation or a list fling) until the next finger lands. */
    fun launchUntilNextGesture(block: suspend CoroutineScope.() -> Unit) {
        animation?.cancel()
        animation = scope.launch(block = block)
    }

    /** One pinch step; focus is relative to the container centre. */
    fun pinch(focusX: Float, focusY: Float, panX: Float, panY: Float, zoomChange: Float) {
        apply(zoomAround(transform, focusX, focusY, zoomChange, panX, panY, minScale, maxScale))
    }

    /** One-finger drag while zoomed. */
    fun drag(dx: Float, dy: Float) {
        panBy(dx * gestureSpeed, dy * gestureSpeed)
    }

    /** Moves the content by ([dx], [dy]) screen pixels, as far as the bounds allow. */
    fun panBy(dx: Float, dy: Float) {
        apply(ZoomTransform(scale, offsetX + dx, offsetY + dy))
    }

    /** Back to 1× without animation (page change, chapter change). */
    fun reset() {
        animation?.cancel()
        animation = null
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /** Double tap: zoom to [doubleTapScale] around the tap, or back to 1× when already zoomed. */
    fun toggle(focusX: Float, focusY: Float, doubleTapScale: Float, animated: Boolean) {
        val target = doubleTapTarget(
            current = transform,
            focusX = focusX,
            focusY = focusY,
            doubleTapScale = doubleTapScale.coerceIn(minScale, maxScale),
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        ).scale
        animation?.cancel()
        if (!animated) {
            pinch(focusX, focusY, 0f, 0f, target / scale)
            return
        }
        launchUntilNextGesture {
            var last = scale
            animate(initialValue = scale, targetValue = target, animationSpec = ZoomSpring) { value, _ ->
                pinch(focusX, focusY, 0f, 0f, value / last)
                last = value
            }
        }
    }

    fun applyTo(layer: GraphicsLayerScope) {
        layer.scaleX = scale
        layer.scaleY = scale
        layer.translationX = offsetX
        layer.translationY = offsetY
    }

    private fun apply(next: ZoomTransform) {
        val s = if (abs(next.scale - 1f) < 0.001f) 1f else next.scale
        val previous = scale
        scale = s
        val scroller = listScroller
        if (scroller != null && listGrowsWhenZoomedOut && s != previous) {
            val growth = stripListHeight(containerHeight, s) - stripListHeight(containerHeight, previous)
            if (growth != 0f) scroller(-growth / 2f)
        }
        val mx = panLimit(containerWidth, contentWidth, s, panSlack)
        val my = panLimit(containerHeight, contentHeight, s, panSlack)
        offsetX = next.offsetX.coerceIn(-mx, mx)
        offsetY = if (scroller == null) {
            next.offsetY.coerceIn(-my, my)
        } else {
            // A finger moving down by dy screen pixels scrolls the list back by dy / scale.
            distributeVerticalShift(next.offsetY - offsetY, offsetY, my) { dy -> -scroller(-dy / s) * s }
        }
    }
}

private fun PointerEvent.pinchInto(zoom: ZoomState, width: Int, height: Int) {
    val centroid = calculateCentroid(useCurrent = true)
    if (centroid.isSpecified) {
        val pan = calculatePan()
        zoom.pinch(centroid.x - width / 2f, centroid.y - height / 2f, pan.x, pan.y, calculateZoom())
    }
    changes.forEach { if (it.positionChanged()) it.consume() }
}

/**
 * Continuous strip: two fingers zoom anywhere; while zoomed, one finger moves the strip sideways
 * and scrolls it (through [ZoomState.listScroller]); [onFling] gets the vertical release
 * velocity. Runs in the initial pass so it can take the gesture away from the list; at 1× single
 * fingers are left to the list.
 */
internal suspend fun PointerInputScope.detectStripZoomGestures(zoom: ZoomState, onFling: (Float) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        zoom.gestureStarted()
        val tracker = VelocityTracker()
        var dragging = false
        var pinched = false
        var travel = Offset.Zero
        var activeId = down.id
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                pinched = true
                dragging = false
                event.pinchInto(zoom, size.width, size.height)
                continue
            }
            if (!zoom.isZoomed) continue
            val change = pressed.first()
            if (change.id != activeId) {
                activeId = change.id
                tracker.resetTracking()
            }
            val delta = change.positionChange()
            if (!dragging) {
                travel += delta
                if (pinched || travel.getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    tracker.resetTracking()
                }
            }
            if (dragging) {
                tracker.addPosition(change.uptimeMillis, change.position)
                zoom.drag(delta.x, delta.y)
                change.consume()
            }
        }
        if (dragging && zoom.isZoomed) onFling(tracker.calculateVelocity().y * zoom.gestureSpeed)
    }
}

/**
 * One page of a pager: two fingers zoom; while zoomed, one finger pans the page. A drag that starts
 * against the edge of the zoomed page along the paging axis is left to the pager, so the reader
 * can still turn pages without zooming out first. [isActive] limits this to the settled page.
 */
internal suspend fun PointerInputScope.detectPageZoomGestures(
    zoom: ZoomState,
    horizontalPaging: Boolean,
    isActive: () -> Boolean,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        if (!isActive()) return@awaitEachGesture
        zoom.gestureStarted()
        var claimed = false
        var released = false
        var pinched = false
        var travel = Offset.Zero
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (released) continue
            if (pressed.size >= 2) {
                pinched = true
                event.pinchInto(zoom, size.width, size.height)
                continue
            }
            if (!zoom.isZoomed) continue
            val change = pressed.first()
            val delta = change.positionChange()
            if (!claimed) {
                if (pinched) {
                    claimed = true
                } else {
                    travel += delta
                    if (travel.getDistance() > viewConfiguration.touchSlop) {
                        val along = if (horizontalPaging) travel.x else travel.y
                        val across = if (horizontalPaging) travel.y else travel.x
                        val atEdge = if (horizontalPaging) {
                            atPanEdge(zoom.offsetX, zoom.maxOffsetX, along)
                        } else {
                            atPanEdge(zoom.offsetY, zoom.maxOffsetY, along)
                        }
                        if (abs(along) > abs(across) && atEdge) released = true else claimed = true
                    }
                }
            }
            if (claimed) {
                zoom.drag(delta.x, delta.y)
                change.consume()
            }
        }
    }
}
