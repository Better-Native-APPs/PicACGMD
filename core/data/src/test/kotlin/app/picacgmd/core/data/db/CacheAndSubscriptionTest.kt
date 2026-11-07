package app.picacgmd.core.data.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheAndSubscriptionTest {
    @Test
    fun categoriesAndCollectionsExpireAtExactlyThreeHours() {
        val now = 1_000L
        val categories = ApiCache.categories("{}", now)
        val collections = ApiCache.collections("[]", now)
        assertEquals(10_800_000L, ApiCache.CATEGORIES_COLLECTIONS_TTL_MILLIS)
        assertEquals(now + 10_800_000L, categories.expiresAt)
        assertEquals(categories.expiresAt, collections.expiresAt)
        assertFalse(categories.isExpired(categories.expiresAt - 1))
        assertTrue(categories.isExpired(categories.expiresAt))
    }

    @Test
    fun subscriptionChecksAccumulateUpdatesWithoutConsumingThem() {
        val initial = Subscription("comic", "标题", lastKnownEpsCount = 3, createdAt = 0)
        val first = initial.withLatest(5, 100, 200)
        val repeated = first.withLatest(5, 100, 300)
        val second = repeated.withLatest(6, 150, 400)
        assertTrue(repeated.unseen)
        assertEquals(2, repeated.newEpCount)
        assertEquals(3, second.newEpCount)
        assertEquals(6, second.lastKnownEpsCount)
        assertEquals(400L, second.updatedAt)
        assertEquals(400L, second.lastCheckedAt)
    }

    @Test
    fun changedTimestampCanSignalAnUpdateWithoutANewChapter() {
        val initial = Subscription("comic", "标题", lastKnownEpsCount = 3, lastKnownUpdatedAt = 100, createdAt = 0)
        val updated = initial.withLatest(3, 200, 300)
        assertTrue(updated.unseen)
        assertEquals(0, updated.newEpCount)
        assertEquals(200L, updated.withLatest(3, null, 400).lastKnownUpdatedAt)
    }

    @Test
    fun unchangedSubscriptionDoesNotBecomeUnseen() {
        val initial = Subscription("comic", "标题", lastKnownEpsCount = 3, createdAt = 0)
        val checked = initial.withLatest(3, null, 100)
        assertFalse(checked.unseen)
        assertEquals(0, checked.newEpCount)
        assertEquals(0L, checked.updatedAt)
    }
}
