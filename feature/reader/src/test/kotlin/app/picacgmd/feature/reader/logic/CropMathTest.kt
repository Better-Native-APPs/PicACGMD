package app.picacgmd.feature.reader.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropMathTest {
    private val frame = CropRect(0.2f, 0.2f, 0.8f, 0.8f)

    @Test
    fun theBodyMovesWithoutChangingSize() {
        val moved = frame.dragged(CropHandle.Body, 0.1f, -0.1f)
        assertEquals(0.3f, moved.left, 0.0001f)
        assertEquals(0.1f, moved.top, 0.0001f)
        assertEquals(frame.width, moved.width, 0.0001f)
        assertEquals(frame.height, moved.height, 0.0001f)
    }

    @Test
    fun theBodyStopsAtTheImageEdge() {
        val moved = frame.dragged(CropHandle.Body, 5f, -5f)
        assertEquals(1f, moved.right, 0.0001f)
        assertEquals(0f, moved.top, 0.0001f)
        assertEquals(frame.width, moved.width, 0.0001f)
    }

    @Test
    fun aCornerMovesItsTwoEdgesOnly() {
        val moved = frame.dragged(CropHandle.TopLeft, -0.1f, 0.1f)
        assertEquals(CropRect(0.1f, 0.3f, 0.8f, 0.8f), moved.rounded())
    }

    @Test
    fun anEdgeMovesOneEdgeOnly() {
        val moved = frame.dragged(CropHandle.Right, 0.1f, 0.5f)
        assertEquals(CropRect(0.2f, 0.2f, 0.9f, 0.8f), moved.rounded())
    }

    @Test
    fun resizingStopsAtTheMinimumAndAtTheImage() {
        val tiny = frame.dragged(CropHandle.BottomRight, -5f, -5f)
        assertEquals(MIN_CROP_SIZE, tiny.width, 0.0001f)
        assertEquals(MIN_CROP_SIZE, tiny.height, 0.0001f)
        val full = frame.dragged(CropHandle.TopLeft, -5f, -5f)
        assertEquals(0f, full.left)
        assertEquals(0f, full.top)
    }

    @Test
    fun handlesAreFoundAroundTheFrame() {
        fun at(x: Float, y: Float) = cropHandleAt(x, y, frame, 1000f, 1000f, 40f)
        assertEquals(CropHandle.TopLeft, at(210f, 190f))
        assertEquals(CropHandle.TopRight, at(790f, 210f))
        assertEquals(CropHandle.BottomLeft, at(190f, 790f))
        assertEquals(CropHandle.BottomRight, at(810f, 810f))
        assertEquals(CropHandle.Left, at(195f, 500f))
        assertEquals(CropHandle.Right, at(805f, 500f))
        assertEquals(CropHandle.Top, at(500f, 195f))
        assertEquals(CropHandle.Bottom, at(500f, 805f))
        assertEquals(CropHandle.Body, at(500f, 500f))
        assertNull(at(50f, 50f))
        assertNull(at(500f, 950f))
    }

    @Test
    fun onASmallFrameTheNearestCornerWins() {
        val small = CropRect(0.5f, 0.5f, 0.55f, 0.55f)
        assertEquals(CropHandle.TopLeft, cropHandleAt(495f, 495f, small, 1000f, 1000f, 40f))
        assertEquals(CropHandle.BottomRight, cropHandleAt(555f, 555f, small, 1000f, 1000f, 40f))
    }

    @Test
    fun pixelsCoverTheFrame() {
        assertEquals(PixelRect(256, 364, 1024, 1456), frame.toPixelRect(1280, 1820))
    }

    @Test
    fun pixelsAreNeverEmptyOrOutside() {
        val edge = CropRect(0.999f, 0.999f, 1f, 1f).toPixelRect(10, 10)
        assertEquals(9, edge.left)
        assertEquals(10, edge.right)
        assertTrue(edge.width >= 1 && edge.height >= 1)
        val degenerate = frame.toPixelRect(0, 0)
        assertEquals(PixelRect(0, 0, 1, 1), degenerate)
    }

    private fun CropRect.rounded(): CropRect {
        fun Float.r() = kotlin.math.round(this * 1000f) / 1000f
        return CropRect(left.r(), top.r(), right.r(), bottom.r())
    }
}
