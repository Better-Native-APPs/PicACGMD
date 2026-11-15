package app.picacgmd.core.data.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistoryMutationTimeTest {
    @Test fun eventsAdvancePastObservedVersionsEvenIfTheDeviceClockMovesBackwards() {
        assertEquals(0L, HistoryMutationTime.next(0))
        assertEquals(101L, HistoryMutationTime.next(1, 50, 100))
        assertEquals(200L, HistoryMutationTime.next(200, 100))
        val maximum = HistoryMutationTime.MAX_EPOCH_MILLIS
        assertEquals(maximum, HistoryMutationTime.next(1, maximum - 1))
    }

    @Test fun unsupportedOrUnadvanceableTimesFailInsteadOfCreatingATombstoneTie() {
        val maximum = HistoryMutationTime.MAX_EPOCH_MILLIS
        for (timestamp in listOf(-1L, maximum + 1, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { HistoryMutationTime.next(timestamp) }
            assertFailsWith<IllegalArgumentException> { HistoryMutationTime.next(1, timestamp) }
        }
        assertFailsWith<IllegalArgumentException> { HistoryMutationTime.next(1, maximum) }
    }
}
