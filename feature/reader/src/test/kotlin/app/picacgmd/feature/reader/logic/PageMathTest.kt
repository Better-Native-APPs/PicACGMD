package app.picacgmd.feature.reader.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageMathTest {

    @Test
    fun clampPageKeepsIndexInsideChapter() {
        assertEquals(0, clampPage(-3, 10))
        assertEquals(9, clampPage(42, 10))
        assertEquals(4, clampPage(4, 10))
        assertEquals(0, clampPage(5, 0))
    }

    @Test
    fun preloadOrderPutsCurrentFirstThenAheadThenBehind() {
        assertEquals(listOf(5, 6, 7, 4), preloadOrder(current = 5, pageCount = 20, ahead = 2, behind = 1))
    }

    @Test
    fun preloadOrderStopsAtChapterEdges() {
        assertEquals(listOf(9, 8), preloadOrder(current = 9, pageCount = 10, ahead = 2, behind = 1))
        assertEquals(listOf(0, 1, 2, 3), preloadOrder(current = 0, pageCount = 10, ahead = 3, behind = 1))
        assertEquals(emptyList(), preloadOrder(current = 0, pageCount = 0, ahead = 2, behind = 1))
    }

    @Test
    fun preloadOrderClampsAnOutOfRangeCurrentPage() {
        assertEquals(listOf(2, 1), preloadOrder(current = 7, pageCount = 3, ahead = 2, behind = 1))
    }

    @Test
    fun stripCurrentPageIsTheItemUnderTheProbeLine() {
        val items = listOf(StripItem(3, -400, 600), StripItem(4, 200, 900), StripItem(5, 1100, 900))
        assertEquals(4, stripCurrentPage(items, viewportSize = 1600, atStart = false, atEnd = false))
    }

    @Test
    fun stripCurrentPageReportsShortFirstAndLastPages() {
        val start = listOf(StripItem(0, 0, 300), StripItem(1, 300, 1500))
        assertEquals(0, stripCurrentPage(start, viewportSize = 1600, atStart = true, atEnd = false))
        val end = listOf(StripItem(8, -700, 1500), StripItem(9, 800, 800))
        assertEquals(9, stripCurrentPage(end, viewportSize = 1600, atStart = false, atEnd = true))
    }

    @Test
    fun stripCurrentPageUsesProbeWhenWholeChapterFits() {
        val items = listOf(StripItem(0, 0, 500), StripItem(1, 500, 500), StripItem(2, 1000, 500))
        assertEquals(1, stripCurrentPage(items, viewportSize = 1600, atStart = true, atEnd = true))
    }

    @Test
    fun stripCurrentPageFallsBackToClosestItem() {
        val items = listOf(StripItem(2, 0, 100), StripItem(3, 1200, 400))
        assertEquals(3, stripCurrentPage(items, viewportSize = 1600, atStart = false, atEnd = false))
        assertEquals(0, stripCurrentPage(emptyList(), viewportSize = 1600, atStart = false, atEnd = false))
    }

    @Test
    fun stripStepDistanceFollowsFractionAndZoom() {
        assertEquals(1600f, stripStepDistance(2000, 0.8f), 0.001f)
        assertEquals(800f, stripStepDistance(2000, 0.8f, scale = 2f), 0.001f)
        assertEquals(200f, stripStepDistance(2000, 0.01f), 0.001f)
        assertEquals(2000f, stripStepDistance(2000, 3f), 0.001f)
    }

    @Test
    fun stripDecodeHeightKeepsOrdinaryPagesAtFullWidthAndBoundsTallOnes() {
        // Phone: pages up to 8x as tall as wide still decode at the full 1080 px width.
        assertEquals(8640, stripDecodeHeight(1080))
        // Tablet: the pixel budget wins, so one page never exceeds the budget.
        assertEquals(4687, stripDecodeHeight(2560))
        for (width in listOf(1, 320, 720, 1080, 1440, 1600, 2560, 4096)) {
            assertTrue(width.toLong() * stripDecodeHeight(width) <= MAX_STRIP_PAGE_PIXELS)
            // A typical manga page (1 : 1.42) always fits by width.
            assertTrue(stripDecodeHeight(width) >= (width * 1.42f).toInt() || width.toLong() * width * 1.42f > MAX_STRIP_PAGE_PIXELS)
        }
        assertEquals(stripDecodeHeight(1), stripDecodeHeight(0))
    }

    @Test
    fun decodeEdgeIsAlwaysPositiveAndFinite() {
        assertEquals(1, decodeEdge(0))
        assertEquals(1080, decodeEdge(1080))
        assertEquals(MAX_DECODE_EDGE, decodeEdge(Int.MAX_VALUE))
    }

    @Test
    fun stepTargetReturnsNullOutsideChapter() {
        assertEquals(4, stepTarget(3, 1, 10))
        assertEquals(null, stepTarget(9, 1, 10))
        assertEquals(null, stepTarget(0, -1, 10))
    }

    @Test
    fun sliderMappingRoundTrips() {
        for (page in 0 until 22) assertEquals(page, sliderToPage(pageToSlider(page, 22), 22))
        assertEquals(0f, pageToSlider(0, 1))
        assertEquals(0, sliderToPage(0.7f, 1))
        assertEquals(21, sliderToPage(2f, 22))
        assertEquals(0, sliderToPage(-1f, 22))
    }

    @Test
    fun spreadsPairFromTheFirstPage() {
        assertEquals(11, spreadCount(22, coverAlone = false))
        assertEquals(12, spreadCount(23, coverAlone = false))
        assertEquals(6, spreadOfPage(13, 22, coverAlone = false))
        assertEquals(12..13, pagesOfSpread(6, 22, coverAlone = false))
        assertEquals(22..22, pagesOfSpread(11, 23, coverAlone = false))
    }

    @Test
    fun spreadsWithCoverAloneShiftPairsByOne() {
        assertEquals(12, spreadCount(22, coverAlone = true))
        assertEquals(1, spreadCount(1, coverAlone = true))
        assertEquals(0..0, pagesOfSpread(0, 22, coverAlone = true))
        // 「14-15 / 22」: pages 14 and 15 (1-based) share a spread.
        assertEquals(7, spreadOfPage(13, 22, coverAlone = true))
        assertEquals(7, spreadOfPage(14, 22, coverAlone = true))
        assertEquals(13..14, pagesOfSpread(7, 22, coverAlone = true))
        assertEquals(21..21, pagesOfSpread(11, 22, coverAlone = true))
    }

    @Test
    fun everyPageBelongsToExactlyOneSpread() {
        for (cover in listOf(false, true)) for (count in 1..9) {
            val seen = (0 until spreadCount(count, cover)).flatMap { pagesOfSpread(it, count, cover).toList() }
            assertEquals((0 until count).toList(), seen, "count=$count cover=$cover")
            for (page in 0 until count) assertTrue(page in pagesOfSpread(spreadOfPage(page, count, cover), count, cover))
        }
        assertTrue(pagesOfSpread(30, 10, coverAlone = false).isEmpty())
    }

    @Test
    fun itemUnderAPositionIsFound() {
        val visible = listOf(StripItem(4, -100, 300), StripItem(5, 200, 500), StripItem(6, 720, 300))
        assertEquals(4, stripItemAt(visible, 0f))
        assertEquals(5, stripItemAt(visible, 200f))
        assertEquals(5, stripItemAt(visible, 699f))
        assertEquals(null, stripItemAt(visible, 710f))
        assertEquals(6, stripItemAt(visible, 720f))
        assertEquals(null, stripItemAt(visible, 1020f))
        assertEquals(null, stripItemAt(emptyList(), 0f))
    }

    @Test
    fun loadPercentRoundsAndClamps() {
        assertEquals(62, loadPercent(0.62f))
        assertEquals(0, loadPercent(-0.2f))
        assertEquals(100, loadPercent(1.4f))
        assertEquals(1, loadPercent(0.012f))
        assertEquals(null, loadPercent(Float.NaN))
    }
}
