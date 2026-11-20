package app.picacgmd.feature.detail

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the opened cover answers to fingers: a 500 × 700 picture on a 1000 × 2000 screen, so it is fitted to 1000 × 1400. */
class CoverZoomTest {
    private val zoom = CoverZoom().apply {
        viewport = Size(1000f, 2000f)
        picture = Size(500f, 700f)
    }
    private val centre = Offset(500f, 1000f)

    @Test
    fun aFittedCoverCannotBeDraggedAway() {
        zoom.transform(centroid = centre, pan = Offset(300f, -400f), zoom = 1f)

        assertEquals(CoverPlacement(1f, Offset.Zero), zoom.placement)
    }

    @Test
    fun aDoubleTapEnlargesAroundTheTappedPointAndTheNextOneFitsAgain() {
        // Off centre, and still far enough from every edge for the point to stay under the finger.
        val point = Offset(400f, 900f)

        zoom.afterDoubleTap(point).let { zoom.place(it.scale, it.offset) }

        assertEquals(COVER_DOUBLE_TAP_SCALE, zoom.scale)
        // What was under the finger is still under it: its distance from the centre grew with the picture.
        assertEquals((point - centre) - (point - centre) * COVER_DOUBLE_TAP_SCALE, zoom.offset)

        zoom.afterDoubleTap(point).let { zoom.place(it.scale, it.offset) }

        assertEquals(CoverPlacement(1f, Offset.Zero), zoom.placement)
    }

    @Test
    fun anEnlargedCoverFollowsTheFingerUntilItsEdgeMeetsTheScreenEdge() {
        zoom.place(2f, Offset.Zero)

        zoom.transform(centroid = centre, pan = Offset(120f, -80f), zoom = 1f)
        assertEquals(Offset(120f, -80f), zoom.offset)

        zoom.transform(centroid = centre, pan = Offset(5_000f, -5_000f), zoom = 1f)
        // Twice 1000 × 1400 on a 1000 × 2000 screen: half of the extra width and height on each side.
        assertEquals(Offset(500f, -400f), zoom.offset)
    }

    @Test
    fun aCoverStillShorterThanTheScreenMovesSidewaysOnly() {
        // 1200 × 1680: wider than the screen, not taller.
        zoom.place(1.2f, Offset.Zero)

        zoom.transform(centroid = centre, pan = Offset(5_000f, 5_000f), zoom = 1f)

        assertEquals(Offset(100f, 0f), zoom.offset)
    }

    @Test
    fun aPinchStopsAtFittedAndAtFiveTimes() {
        zoom.transform(centroid = centre, pan = Offset.Zero, zoom = 40f)
        assertEquals(COVER_MAX_SCALE, zoom.scale)

        zoom.transform(centroid = centre, pan = Offset.Zero, zoom = 0.01f)
        assertEquals(CoverPlacement(1f, Offset.Zero), zoom.placement)
    }

    @Test
    fun aPinchKeepsThePointBetweenTheFingersInPlace() {
        val fingers = Offset(300f, 800f)

        zoom.transform(centroid = fingers, pan = Offset.Zero, zoom = 2f)

        assertEquals((fingers - centre) - (fingers - centre) * 2f, zoom.offset)
    }
}
