package app.picacgmd.feature.reader.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryBudgetTest {
    @Test
    fun androidLowRamFlagWinsEvenWithMorePhysicalMemory() {
        assertTrue(readerMemoryBudget(isLowRamDevice = true, totalMemoryBytes = 8L * 1024 * 1024 * 1024).isLowRam)
    }

    @Test
    fun physicalMemoryThresholdIsStrictlyBelowThreeGiB() {
        assertEquals(3_221_225_472L, LOW_RAM_TOTAL_MEMORY_BYTES)
        assertTrue(readerMemoryBudget(false, 2L * 1024 * 1024 * 1024).isLowRam)
        assertTrue(readerMemoryBudget(false, LOW_RAM_TOTAL_MEMORY_BYTES - 1).isLowRam)
        assertFalse(readerMemoryBudget(false, LOW_RAM_TOTAL_MEMORY_BYTES).isLowRam)
        assertFalse(readerMemoryBudget(false, LOW_RAM_TOTAL_MEMORY_BYTES + 1).isLowRam)
    }

    @Test
    fun normalDevicesKeepEveryConfiguredPreloadWindow() {
        val budget = readerMemoryBudget(false, LOW_RAM_TOTAL_MEMORY_BYTES)
        for (pagesPerSlot in 1..2) for (configuredAhead in 1..5) {
            val ahead = configuredAhead * pagesPerSlot + (pagesPerSlot - 1)
            val behind = pagesPerSlot
            assertEquals(ReaderPreloadWindow(ahead, behind), budget.preloadWindow(ahead, behind, pagesPerSlot))
            assertEquals(
                ReaderPreloadWindow(configuredAhead * pagesPerSlot, 0),
                budget.preloadWindow(configuredAhead * pagesPerSlot, 0, pagesPerSlot),
            )
        }
        assertTrue(budget.preloadNextChapterImages)
        assertEquals(2, budget.previewDecodeFactor)
        assertEquals(12_000_000, budget.maxStripPagePixels)
    }

    @Test
    fun lowRamDevicesKeepOneSlotAheadAndOnePageBehind() {
        val budget = readerMemoryBudget(false, LOW_RAM_TOTAL_MEMORY_BYTES - 1)
        for (pagesPerSlot in 1..2) for (configuredAhead in 1..5) {
            val window = budget.preloadWindow(
                ahead = configuredAhead * pagesPerSlot + (pagesPerSlot - 1),
                behind = pagesPerSlot,
                pagesPerSlot = pagesPerSlot,
            )
            assertEquals(ReaderPreloadWindow(2 * pagesPerSlot - 1, 1), window)
            // The request layer can safely reapply the budget to the reader's bounded window.
            assertEquals(window, budget.preloadWindow(window.ahead, window.behind, pagesPerSlot))
        }
    }

    @Test
    fun lowRamSpreadWindowIncludesTheCurrentPairAndJustTheNextPair() {
        val window = ReaderMemoryBudget(isLowRam = true).preloadWindow(ahead = 11, behind = 2, pagesPerSlot = 2)
        assertEquals(
            listOf(8, 9, 10, 11, 7),
            preloadOrder(current = 8, pageCount = 20, ahead = window.ahead, behind = window.behind),
        )
    }

    @Test
    fun lowRamBudgetDoesNotWidenSmallerRequestedWindows() {
        val budget = ReaderMemoryBudget(isLowRam = true)
        assertEquals(ReaderPreloadWindow(0, 0), budget.preloadWindow(0, 0))
        assertEquals(ReaderPreloadWindow(1, 0), budget.preloadWindow(1, 0, pagesPerSlot = 2))
    }

    @Test
    fun lowRamDevicesSkipNextChapterImagesAndReduceDecodeSizes() {
        val budget = ReaderMemoryBudget(isLowRam = true)
        assertFalse(budget.preloadNextChapterImages)
        assertEquals(1, budget.previewDecodeFactor)
        assertEquals(6_000_000, budget.maxStripPagePixels)
        assertEquals(1080, decodeEdge(1080 * budget.previewDecodeFactor))
        assertEquals(2160, decodeEdge(1080 * ReaderMemoryBudget(isLowRam = false).previewDecodeFactor))
    }
}
