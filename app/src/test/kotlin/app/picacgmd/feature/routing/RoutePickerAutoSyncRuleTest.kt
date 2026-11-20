package app.picacgmd.feature.routing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutePickerAutoSyncRuleTest {
    private val now = 50 * Hour

    @Test
    fun neverSyncedRoutesNeedAutomaticSync() {
        assertTrue(shouldAutoSyncApiRoutes(enabled = true, lastSyncedAtMillis = null, nowMillis = now))
    }

    @Test
    fun aSyncFrom23Hours59MinutesAgoIsStillFresh() {
        assertFalse(shouldAutoSyncApiRoutes(enabled = true, lastSyncedAtMillis = now - 23 * Hour - 59 * Minute, nowMillis = now))
    }

    @Test
    fun aSyncFromExactly24HoursAgoNeedsAutomaticSync() {
        assertTrue(shouldAutoSyncApiRoutes(enabled = true, lastSyncedAtMillis = now - 24 * Hour, nowMillis = now))
    }

    @Test
    fun aFutureTimestampNeedsAutomaticSync() {
        assertTrue(shouldAutoSyncApiRoutes(enabled = true, lastSyncedAtMillis = now + Minute, nowMillis = now))
    }

    @Test
    fun anOffOptionNeverNeedsAutomaticSync() {
        for (lastSyncedAt in listOf(null, now - 25 * Hour, now + Hour)) {
            assertFalse(shouldAutoSyncApiRoutes(enabled = false, lastSyncedAtMillis = lastSyncedAt, nowMillis = now))
        }
    }

    private companion object {
        const val Minute = 60_000L
        const val Hour = 60 * Minute
    }
}
