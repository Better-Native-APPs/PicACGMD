package app.picacgmd.feature.reader.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomMathTest {

    private val eps = 0.001f

    /** Screen position (relative to the centre) of a content point [q] (relative to the centre at 1×). */
    private fun screenOf(t: ZoomTransform, qx: Float, qy: Float) = (t.offsetX + t.scale * qx) to (t.offsetY + t.scale * qy)

    @Test
    fun maxOffsetIsZeroUntilContentOutgrowsContainer() {
        assertEquals(0f, maxOffset(1000f, 1000f, 1f), eps)
        assertEquals(500f, maxOffset(1000f, 1000f, 2f), eps)
        assertEquals(0f, maxOffset(1000f, 400f, 2f), eps)
        assertEquals(100f, maxOffset(1000f, 400f, 3f), eps)
    }

    @Test
    fun clampTransformKeepsContentCoveringContainer() {
        val t = clampTransform(ZoomTransform(2f, 900f, -900f), 1000f, 2000f, 1000f, 2000f)
        assertEquals(500f, t.offsetX, eps)
        assertEquals(-900f, t.offsetY, eps)
        val narrow = clampTransform(ZoomTransform(2f, 300f, 0f), 1000f, 2000f, 400f, 2000f)
        assertEquals(0f, narrow.offsetX, eps)
    }

    @Test
    fun zoomAroundKeepsTheFocusedContentUnderTheFingers() {
        val start = ZoomTransform(1.5f, 40f, -60f)
        val focusX = 120f
        val focusY = -200f
        // Content point under the focus before zooming.
        val qx = (focusX - start.offsetX) / start.scale
        val qy = (focusY - start.offsetY) / start.scale
        val zoomed = zoomAround(start, focusX, focusY, 1.4f, 0f, 0f, 1f, 3f)
        assertEquals(2.1f, zoomed.scale, eps)
        val (sx, sy) = screenOf(zoomed, qx, qy)
        assertEquals(focusX, sx, eps)
        assertEquals(focusY, sy, eps)
    }

    @Test
    fun zoomAroundCarriesContentAlongWithAMovingCentroid() {
        val start = ZoomTransform(2f, 0f, 0f)
        val previousX = 100f
        val previousY = 100f
        val qx = (previousX - start.offsetX) / start.scale
        val qy = (previousY - start.offsetY) / start.scale
        val moved = zoomAround(start, focusX = 130f, focusY = 80f, zoomChange = 1f, panX = 30f, panY = -20f, minScale = 1f, maxScale = 3f)
        val (sx, sy) = screenOf(moved, qx, qy)
        assertEquals(130f, sx, eps)
        assertEquals(80f, sy, eps)
    }

    @Test
    fun zoomAroundRespectsLimits() {
        assertEquals(3f, zoomAround(ZoomTransform(2.5f, 0f, 0f), 0f, 0f, 2f, 0f, 0f, 1f, 3f).scale, eps)
        assertEquals(1f, zoomAround(ZoomTransform(1.2f, 0f, 0f), 0f, 0f, 0.5f, 0f, 0f, 1f, 3f).scale, eps)
        assertEquals(0.5f, zoomAround(ZoomTransform(1f, 0f, 0f), 0f, 0f, 0.2f, 0f, 0f, 0.5f, 3f).scale, eps)
    }

    @Test
    fun doubleTapZoomsInAroundTheTapAndBackOut() {
        val zoomed = doubleTapTarget(ZoomTransform.Identity, 200f, -300f, 2f, 1000f, 2000f, 1000f, 2000f)
        assertEquals(2f, zoomed.scale, eps)
        assertEquals(-200f, zoomed.offsetX, eps)
        assertEquals(300f, zoomed.offsetY, eps)
        assertEquals(ZoomTransform.Identity, doubleTapTarget(zoomed, 0f, 0f, 2f, 1000f, 2000f, 1000f, 2000f))
        // Zoomed out below 1x also returns to 1x.
        assertEquals(ZoomTransform.Identity, doubleTapTarget(ZoomTransform(0.6f, 0f, 0f), 0f, 0f, 2f, 1000f, 2000f, 1000f, 2000f))
    }

    @Test
    fun doubleTapNearTheEdgeIsClamped() {
        val zoomed = doubleTapTarget(ZoomTransform.Identity, 500f, 1000f, 3f, 1000f, 2000f, 1000f, 2000f)
        assertEquals(3f, zoomed.scale, eps)
        assertEquals(-1000f, zoomed.offsetX, eps)
        assertEquals(-2000f, zoomed.offsetY, eps)
    }

    @Test
    fun fitSizeLetterboxesOrPillarboxes() {
        assertEquals(1000f to 1500f, fitSize(800f, 1200f, 1000f, 2000f))
        assertEquals(1000f to 500f, fitSize(2000f, 1000f, 1000f, 2000f))
        assertEquals(1000f to 2000f, fitSize(0f, 0f, 1000f, 2000f))
        assertEquals(1000f to 2000f, fitSize(Float.POSITIVE_INFINITY, 10f, 1000f, 2000f))
    }

    @Test
    fun atPanEdgeDetectsPushingPastTheBound() {
        assertTrue(atPanEdge(offset = 500f, maxOffset = 500f, delta = 10f))
        assertFalse(atPanEdge(offset = 200f, maxOffset = 500f, delta = 10f))
        assertTrue(atPanEdge(offset = -500f, maxOffset = 500f, delta = -10f))
        assertFalse(atPanEdge(offset = 500f, maxOffset = 500f, delta = -10f))
        assertTrue(atPanEdge(offset = 0f, maxOffset = 0f, delta = 3f))
        assertFalse(atPanEdge(offset = 0f, maxOffset = 0f, delta = 0f))
    }

    @Test
    fun verticalShiftScrollsTheListBeforeUsingTranslation() {
        var scrolled = 0f
        val offset = distributeVerticalShift(-120f, 0f, 400f) { scrolled += it; it }
        assertEquals(-120f, scrolled, eps)
        assertEquals(0f, offset, eps)
    }

    @Test
    fun verticalShiftSpillsIntoTranslationAtTheListEnds() {
        // The list can only absorb 50 px more before hitting its top.
        var room = 50f
        val offset = distributeVerticalShift(120f, 0f, 400f) { wanted ->
            val taken = minOf(wanted, room)
            room -= taken
            taken
        }
        assertEquals(70f, offset, eps)
        assertEquals(400f, distributeVerticalShift(900f, 0f, 400f) { 0f }, eps)
    }

    @Test
    fun verticalShiftUndoesTranslationFirst() {
        var scrolled = 0f
        // Translated to reveal the top of the first page; dragging up first takes that back.
        val offset = distributeVerticalShift(-150f, 100f, 400f) { scrolled += it; it }
        assertEquals(0f, offset, eps)
        assertEquals(-50f, scrolled, eps)
        val partial = distributeVerticalShift(60f, -100f, 400f) { error("list must not scroll") }
        assertEquals(-40f, partial, eps)
    }

    @Test
    fun verticalShiftClampsAStaleTranslation() {
        assertEquals(0f, distributeVerticalShift(0f, 300f, 0f) { 0f }, eps)
    }

    @Test
    fun unzoomedScreenPositionsAreContentPositions() {
        assertEquals(123f, screenToContent(123f, center = 500f, scale = 1f, offset = 0f), eps)
    }

    @Test
    fun zoomedPositionsMapBackAroundTheCentre() {
        // At 2× around the centre, the screen's top edge shows the content a quarter of the way down.
        assertEquals(250f, screenToContent(0f, center = 500f, scale = 2f, offset = 0f), eps)
        assertEquals(500f, screenToContent(500f, center = 500f, scale = 2f, offset = 0f), eps)
        // Moving the layer up by 100 screen pixels reveals content 50 pixels further down.
        assertEquals(300f, screenToContent(0f, center = 500f, scale = 2f, offset = -100f), eps)
    }
}
