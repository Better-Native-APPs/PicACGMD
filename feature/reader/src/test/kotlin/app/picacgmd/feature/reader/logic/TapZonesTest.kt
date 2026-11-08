package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.TapMode
import kotlin.test.Test
import kotlin.test.assertEquals

class TapZonesTest {

    private fun horizontal(x: Float, rtl: Boolean = false, keepRight: Boolean = false) =
        tapZoneAt(x, 500f, 1000f, 2000f, PagingAxis.Horizontal, rtl, keepRight)

    private fun vertical(y: Float, rtl: Boolean = false) =
        tapZoneAt(500f, y, 1000f, 2000f, PagingAxis.Vertical, rtl, alwaysRightIsNext = false)

    @Test
    fun leftToRightSplitsThirtyFortyThirty() {
        assertEquals(TapZone.Previous, horizontal(0f))
        assertEquals(TapZone.Previous, horizontal(299f))
        assertEquals(TapZone.Menu, horizontal(301f))
        assertEquals(TapZone.Menu, horizontal(500f))
        assertEquals(TapZone.Menu, horizontal(699f))
        assertEquals(TapZone.Next, horizontal(701f))
        assertEquals(TapZone.Next, horizontal(1000f))
    }

    @Test
    fun rightToLeftMirrorsTheOuterZones() {
        assertEquals(TapZone.Next, horizontal(100f, rtl = true))
        assertEquals(TapZone.Menu, horizontal(500f, rtl = true))
        assertEquals(TapZone.Previous, horizontal(900f, rtl = true))
    }

    @Test
    fun alwaysRightIsNextKeepsLeftToRightLayoutWhenReadingRightToLeft() {
        assertEquals(TapZone.Previous, horizontal(100f, rtl = true, keepRight = true))
        assertEquals(TapZone.Next, horizontal(900f, rtl = true, keepRight = true))
    }

    @Test
    fun verticalUsesTopMiddleBottomAndIgnoresDirection() {
        assertEquals(TapZone.Previous, vertical(100f))
        assertEquals(TapZone.Menu, vertical(1000f))
        assertEquals(TapZone.Next, vertical(1900f))
        assertEquals(TapZone.Previous, vertical(100f, rtl = true))
        assertEquals(TapZone.Next, vertical(1900f, rtl = true))
    }

    @Test
    fun emptyAreaIsMenu() {
        assertEquals(TapZone.Menu, tapZoneAt(0f, 0f, 0f, 0f, PagingAxis.Horizontal, false, false))
    }

    @Test
    fun singleTapActionsFollowTapMode() {
        for (zone in TapZone.entries) {
            assertEquals(TapAction.ToggleControls, singleTapAction(TapMode.TapToToggle, zone))
            assertEquals(TapAction.None, singleTapAction(TapMode.Controller, zone))
            assertEquals(TapAction.None, singleTapAction(TapMode.DoubleTapToToggle, zone))
            assertEquals(TapAction.NextPage, singleTapAction(TapMode.DoubleTapToggleTapNext, zone))
        }
        assertEquals(TapAction.PreviousPage, singleTapAction(TapMode.ThreeZones, TapZone.Previous))
        assertEquals(TapAction.ToggleControls, singleTapAction(TapMode.ThreeZones, TapZone.Menu))
        assertEquals(TapAction.NextPage, singleTapAction(TapMode.ThreeZones, TapZone.Next))
    }

    @Test
    fun doubleTapZoomsUnlessItIsTakenByTheControls() {
        assertEquals(TapAction.ToggleZoom, doubleTapAction(TapMode.TapToToggle))
        assertEquals(TapAction.ToggleZoom, doubleTapAction(TapMode.Controller))
        assertEquals(TapAction.ToggleZoom, doubleTapAction(TapMode.ThreeZones))
        assertEquals(TapAction.ToggleControls, doubleTapAction(TapMode.DoubleTapToToggle))
        assertEquals(TapAction.ToggleControls, doubleTapAction(TapMode.DoubleTapToggleTapNext))
    }

    @Test
    fun zoneAreasSplitTheScreenThirtyFortyThirty() {
        val areas = threeZoneAreas(PagingAxis.Horizontal, rightToLeft = false, alwaysRightIsNext = false)
        assertEquals(listOf(TapZone.Previous, TapZone.Menu, TapZone.Next), areas.map { it.zone })
        assertEquals(0.3f, areas[0].right, 0.0001f)
        assertEquals(0.7f, areas[1].right, 0.0001f)
        assertEquals(1f, areas[2].right, 0.0001f)
        areas.forEach { assertEquals(1f, it.bottom) }

        val strip = threeZoneAreas(PagingAxis.Vertical, rightToLeft = false, alwaysRightIsNext = false)
        assertEquals(listOf(TapZone.Previous, TapZone.Menu, TapZone.Next), strip.map { it.zone })
        assertEquals(0.3f, strip[0].bottom, 0.0001f)
        strip.forEach { assertEquals(1f, it.right) }
    }

    @Test
    fun rightToLeftMirrorsTheAreasUnlessTheRightStaysNext() {
        val mirrored = threeZoneAreas(PagingAxis.Horizontal, rightToLeft = true, alwaysRightIsNext = false)
        assertEquals(listOf(TapZone.Next, TapZone.Menu, TapZone.Previous), mirrored.map { it.zone })
        val kept = threeZoneAreas(PagingAxis.Horizontal, rightToLeft = true, alwaysRightIsNext = true)
        assertEquals(listOf(TapZone.Previous, TapZone.Menu, TapZone.Next), kept.map { it.zone })
    }

    @Test
    fun whatTheOverlayShowsIsWhatATapDoes() {
        for (axis in PagingAxis.entries) for (rtl in listOf(false, true)) for (keep in listOf(false, true)) {
            for (area in threeZoneAreas(axis, rtl, keep)) {
                val x = (area.left + area.right) / 2f * 1000f
                val y = (area.top + area.bottom) / 2f * 2000f
                assertEquals(area.zone, tapZoneAt(x, y, 1000f, 2000f, axis, rtl, keep))
            }
        }
    }
}
