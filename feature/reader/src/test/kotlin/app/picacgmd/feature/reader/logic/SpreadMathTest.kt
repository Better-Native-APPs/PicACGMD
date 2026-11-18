package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.TwoPageArrangement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun aPreloadWindowInPagesReachesWholeSlots() {
        val single = PageSlots(pageCount = 9, spreads = false)
        val double = PageSlots(pageCount = 9, spreads = true)
        // The paged reader asks for preloadAhead * perSlot + (perSlot - 1) pages from the first page of a slot.
        assertEquals(1, single.slotsAhead(1))
        assertEquals(2, single.slotsAhead(2))
        assertEquals(1, double.slotsAhead(3))
        assertEquals(2, double.slotsAhead(5))
        // The neighbouring slot shows while paging, so it is never held back.
        assertEquals(1, single.slotsAhead(0))
        assertEquals(1, double.slotsAhead(1))
    }

    @Test
    fun aScreenBeyondThePreloadWindowWaitsUntilTheReaderGetsCloser() {
        // One screen ahead: the neighbour loads; the further screen the pager composes while scrolling waits.
        assertTrue(slotMayLoad(slot = 4, currentSlot = 4, aheadSlots = 1, alreadyLoading = false))
        assertTrue(slotMayLoad(slot = 5, currentSlot = 4, aheadSlots = 1, alreadyLoading = false))
        assertFalse(slotMayLoad(slot = 6, currentSlot = 4, aheadSlots = 1, alreadyLoading = false))
        assertFalse(slotMayLoad(slot = 7, currentSlot = 4, aheadSlots = 1, alreadyLoading = false))
        assertTrue(slotMayLoad(slot = 6, currentSlot = 5, aheadSlots = 1, alreadyLoading = false))
        // Two screens ahead.
        assertTrue(slotMayLoad(slot = 6, currentSlot = 4, aheadSlots = 2, alreadyLoading = false))
        assertFalse(slotMayLoad(slot = 7, currentSlot = 4, aheadSlots = 2, alreadyLoading = false))
    }

    @Test
    fun screensBehindAndScreensAlreadyLoadingNeverWait() {
        assertTrue(slotMayLoad(slot = 1, currentSlot = 4, aheadSlots = 1, alreadyLoading = false))
        // The current screen moved back during a drag: the download that had started goes on.
        assertTrue(slotMayLoad(slot = 6, currentSlot = 4, aheadSlots = 1, alreadyLoading = true))
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
