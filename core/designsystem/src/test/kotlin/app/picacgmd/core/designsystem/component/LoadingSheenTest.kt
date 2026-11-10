package app.picacgmd.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadingSheenTest {
    private val cycle = SheenSweepMillis + SheenRestMillis

    @Test
    fun sweepRunsFromEntryToExitThenRests() {
        assertEquals(0f, sheenSweep(0))
        assertEquals(0.5f, sheenSweep(SheenSweepMillis / 2))
        assertTrue(assertNotNull(sheenSweep(SheenSweepMillis - 1)) > 0.99f)
        assertNull(sheenSweep(SheenSweepMillis))
        assertNull(sheenSweep(cycle - 1))
    }

    @Test
    fun sweepOnlyMovesForwardWithinACycle() {
        var previous = -1f
        for (millis in 0 until SheenSweepMillis step 16L) {
            val now = assertNotNull(sheenSweep(millis))
            assertTrue(now >= previous, "went back at $millis ms")
            assertTrue(now in 0f..1f)
            previous = now
        }
    }

    @Test
    fun everyPlaceholderSharesOnePhase() {
        // The band's place depends on the frame time alone, so tiles that started at different
        // moments still move together; the cycle repeats, also for times before zero.
        assertEquals(sheenSweep(300), sheenSweep(300 + 7 * cycle))
        assertEquals(sheenSweep(300), sheenSweep(300 - cycle))
        assertNull(sheenSweep(-1))
    }

    @Test
    fun aPictureFromTheCacheNeverShowsTheSheen() {
        assertEquals(0f, sheenAppear(0f))
        assertEquals(0f, sheenAppear(SheenDelayMillis))
        assertEquals(0.5f, sheenAppear(SheenDelayMillis + SheenFadeInMillis / 2))
        assertEquals(1f, sheenAppear(SheenDelayMillis + SheenFadeInMillis))
        assertEquals(1f, sheenAppear(60_000f))
    }
}
