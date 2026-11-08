package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.TwoPageArrangement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpreadMathTest {

    @Test
    fun singleSlotsAreOnePageEach() {
        val slots = PageSlots(pageCount = 5, spreads = false)
        assertEquals(5, slots.count)
        assertEquals(1, slots.perSlot)
        assertEquals(3, slots.slotOf(3))
        assertEquals(4, slots.slotOf(9))
        assertEquals(2..2, slots.pagesOf(2))
        assertTrue(slots.pagesOf(5).isEmpty())
    }

    @Test
    fun spreadSlotsHandleAnOddLastPage() {
        val slots = PageSlots(pageCount = 5, spreads = true)
        assertEquals(3, slots.count)
        assertEquals(0..1, slots.pagesOf(0))
        assertEquals(2..3, slots.pagesOf(1))
        assertEquals(4..4, slots.pagesOf(2))
        assertEquals(2, slots.slotOf(4))
        assertTrue(slots.pagesOf(3).isEmpty())
    }

    @Test
    fun coverAloneGivesTheFirstPageItsOwnSlot() {
        val slots = PageSlots(pageCount = 22, spreads = true, coverAlone = true)
        assertEquals(12, slots.count)
        assertEquals(0..0, slots.pagesOf(0))
        // 「14-15 / 22」
        assertEquals(13..14, slots.pagesOf(slots.slotOf(13)))
        assertEquals(21..21, slots.pagesOf(slots.count - 1))
    }

    @Test
    fun pagesAroundCoverTheComposedSlots() {
        val slots = PageSlots(pageCount = 9, spreads = true)
        assertEquals(0..3, slots.pagesAround(0, 1))
        assertEquals(2..7, slots.pagesAround(2, 1))
        // The chapter-end page sits past the last slot; its neighbour is the lone last page.
        assertEquals(8..8, slots.pagesAround(slots.count, 1))
        assertEquals(6..8, slots.pagesAround(slots.count - 1, 1))
        assertTrue(PageSlots(0, spreads = true).pagesAround(0, 1).isEmpty())
    }

    @Test
    fun rightToLeftPutsTheFirstPageOnTheRight() {
        assertEquals(SpreadSides(left = 4, right = 5), spreadSides(4, 5, rightToLeft = false))
        assertEquals(SpreadSides(left = 5, right = 4), spreadSides(4, 5, rightToLeft = true))
    }

    @Test
    fun arrangementsAnchorEachHalf() {
        assertEquals(HalfAnchor.Right, spreadAnchor(TwoPageArrangement.CloseTo, leftHalf = true))
        assertEquals(HalfAnchor.Left, spreadAnchor(TwoPageArrangement.CloseTo, leftHalf = false))
        assertEquals(HalfAnchor.Left, spreadAnchor(TwoPageArrangement.PullAway, leftHalf = true))
        assertEquals(HalfAnchor.Right, spreadAnchor(TwoPageArrangement.PullAway, leftHalf = false))
        assertEquals(HalfAnchor.Center, spreadAnchor(TwoPageArrangement.EachCentered, leftHalf = true))
        assertEquals(HalfAnchor.Center, spreadAnchor(TwoPageArrangement.EachCentered, leftHalf = false))
    }

    @Test
    fun spreadPageAtPicksTheHalf() {
        val sides = SpreadSides(left = 7, right = 6)
        assertEquals(7, spreadPageAt(100f, 1000f, sides))
        assertEquals(6, spreadPageAt(500f, 1000f, sides))
        assertEquals(6, spreadPageAt(900f, 1000f, sides))
    }
}
