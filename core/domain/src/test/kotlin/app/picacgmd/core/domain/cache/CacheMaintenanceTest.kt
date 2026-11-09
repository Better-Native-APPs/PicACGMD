package app.picacgmd.core.domain.cache

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheMaintenanceTest {
    @Test fun neverDoesNotCleanEvenWithoutTimestamp() {
        assertFalse(isCacheCleanupDue(null, 100_000, 0))
    }

    @Test fun firstEnabledRunAndExactDeadlineAreDue() {
        assertTrue(isCacheCleanupDue(null, 100_000, 86_400))
        assertFalse(isCacheCleanupDue(100, 86_400_099, 86_400))
        assertTrue(isCacheCleanupDue(100, 86_400_100, 86_400))
    }

    @Test fun clockRollbackDoesNotPostponeForever() {
        assertTrue(isCacheCleanupDue(1_000, 999, 604_800))
    }
}
