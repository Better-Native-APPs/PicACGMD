package app.picacgmd.core.domain.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadProgressFlushPolicyTest {
    @Test
    fun partialBatchesUseTheLastFlushDeadlineInsteadOfAnIdleReadTimeout() {
        var now = 0L
        val policy = DownloadProgressFlushPolicy { now }
        now = 200
        assertFalse(policy.shouldFlush(1))
        assertEquals(800L, policy.timeUntilFlushMillis())
        now = 900
        assertFalse(policy.shouldFlush(4))
        assertEquals(100L, policy.timeUntilFlushMillis())
        now = 1_000
        assertTrue(policy.shouldFlush(5))
        assertEquals(0L, policy.timeUntilFlushMillis())
    }

    @Test
    fun aFullBatchFlushesWithoutWaitingForTheDeadline() {
        val policy = DownloadProgressFlushPolicy { 0L }
        assertFalse(policy.shouldFlush(15))
        assertTrue(policy.shouldFlush(16))
    }

    @Test
    fun everyActualFlushStartsANewOneSecondWindow() {
        var now = 0L
        val policy = DownloadProgressFlushPolicy { now }
        now = 300
        policy.onFlushed()
        now = 1_299
        assertFalse(policy.shouldFlush(1))
        now = 1_300
        assertTrue(policy.shouldFlush(1))
    }

    @Test
    fun emptyBatchesDoNotFlushAndTheFirstResultAfterALongIdlePeriodDoes() {
        var now = 0L
        val policy = DownloadProgressFlushPolicy { now }
        now = 10_000
        assertFalse(policy.shouldFlush(0))
        assertTrue(policy.shouldFlush(1))
    }
}
