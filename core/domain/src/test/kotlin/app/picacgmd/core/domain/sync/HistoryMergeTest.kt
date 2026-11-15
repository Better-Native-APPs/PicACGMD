package app.picacgmd.core.domain.sync

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import app.picacgmd.core.data.db.HistoryMutationTime
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavException

class HistoryMergeTest {
    @Test fun newerRecordsWinAsAWholeInBothDirections() {
        val older = record("a", 10).copy(lastEpOrder = 9, lastPictureIndex = 90, pinned = true, author = "Older")
        val newer = record("a", 11).copy(lastEpOrder = 1, lastPictureIndex = 2, pinned = false, author = "Newer")
        assertEquals(document(newer), HistoryMerge.merge(document(older), document(newer)))
        assertEquals(document(newer), HistoryMerge.merge(document(newer), document(older)))
    }

    @Test fun equalTimestampsAreDeterministicAndDoNotMixFields() {
        val first = record("a", 10).copy(title = "A", pinned = true, lastEpOrder = 9)
        val second = record("a", 10).copy(title = "B", pinned = false, lastEpOrder = 1)
        val merged = HistoryMerge.merge(document(first), document(second))
        assertEquals(document(second), merged)
        assertEquals(merged, HistoryMerge.merge(document(second), document(first)))
        assertEquals(merged, HistoryMerge.merge(merged, merged))
    }

    @Test fun aDeletionBeatsOlderOrEqualRecordsFromEitherSide() {
        val deleted = document(tombstones = listOf(HistorySyncTombstone("a", 10)))
        listOf(9L, 10L).forEach {
            assertEquals(deleted, HistoryMerge.merge(document(record("a", it)), deleted))
            assertEquals(deleted, HistoryMerge.merge(deleted, document(record("a", it))))
        }
    }

    @Test fun aRecordNewerThanTheDeletionWinsIncludingItsPinnedFlag() {
        val restored = record("a", 11).copy(pinned = true)
        val deletion = document(tombstones = listOf(HistorySyncTombstone("a", 10)))
        assertEquals(document(restored), HistoryMerge.merge(document(restored), deletion))
        assertEquals(document(restored), HistoryMerge.merge(deletion, document(restored)))
    }

    @Test fun theNewestTombstoneWinsAndRecordOrderingIsStable() {
        val local = document(record("z", 2), record("b", 2), tombstones = listOf(HistorySyncTombstone("a", 3)))
        val remote = document(tombstones = listOf(HistorySyncTombstone("a", 5)))
        assertEquals(listOf("b", "z"), HistoryMerge.merge(local, remote).records.map { it.comicId })
        assertEquals(listOf(HistorySyncTombstone("a", 5)), HistoryMerge.merge(local, remote).tombstones)
    }

    @Test fun pruningUsesThe180DayBoundaryAfterResolvingDeletedRecords() {
        val now = HistoryMerge.TOMBSTONE_RETENTION_MILLIS + 1_000
        val input = document(record("expired", 1), tombstones = listOf(
            HistorySyncTombstone("expired", 999), HistorySyncTombstone("boundary", 1_000),
            HistorySyncTombstone("recent", 1_001)))
        val resolved = HistoryMerge.merge(input, HistorySyncDocument.EMPTY)
        assertTrue(resolved.records.isEmpty())
        assertEquals(3, resolved.tombstones.size)
        assertEquals(listOf("boundary", "recent"), HistoryMerge.pruneTombstones(resolved, now).tombstones.map { it.comicId })
    }

    @Test fun emptySidesAreValidAndDoNotLosePinnedProgress() {
        val input = document(record("a", 10).copy(pinned = true, lastEpOrder = 4, lastPictureIndex = 13, lastChapterPages = 20))
        assertEquals(input, HistoryMerge.merge(input, HistorySyncDocument.EMPTY))
        assertEquals(input, HistoryMerge.merge(HistorySyncDocument.EMPTY, input))
        assertEquals(HistorySyncDocument.EMPTY, HistoryMerge.merge(HistorySyncDocument.EMPTY, HistorySyncDocument.EMPTY))
    }

    @Test fun fiveThousandRecordsMergeWithinAPerformanceSanityBound() {
        val local = HistorySyncDocument(1, List(5_000) { record("comic-$it", 1) }, emptyList())
        val remote = local.copy(records = local.records.map { it.copy(modifiedAt = 2, pinned = true) })
        lateinit var merged: HistorySyncDocument
        val elapsed = measureTimeMillis { merged = HistoryMerge.merge(local, remote) }
        assertEquals(5_000, merged.records.size)
        assertTrue(merged.records.all { it.modifiedAt == 2L && it.updatedAt == 1L && it.pinned })
        assertTrue(elapsed < 5_000, "Merge took $elapsed ms")
    }

    @Test fun explicitOverwriteSurvivesTheNextMergeWithNewerLosingEvents() {
        val chosen = record("a", 10).copy(lastEpOrder = 1, pinned = false)
        val other = document(record("a", 90).copy(lastEpOrder = 9, pinned = true), record("removed", 100))
        val forced = HistoryMerge.overwrite(document(chosen), other, 50)
        assertEquals(chosen.copy(modifiedAt = 91), forced.records.single())
        assertEquals(listOf(HistorySyncTombstone("removed", 101)), forced.tombstones)
        assertEquals(forced, HistoryMerge.merge(forced, other))
    }

    @Test fun explicitOverwriteCanRestoreAnAuthoritativeRecordPastAnOpposingDeletion() {
        val forced = HistoryMerge.overwrite(document(record("a", 10)),
            document(tombstones = listOf(HistorySyncTombstone("a", 90))), 50)
        assertEquals(91L, forced.records.single().modifiedAt)
        assertEquals(10L, forced.records.single().updatedAt)
        assertTrue(forced.tombstones.isEmpty())
    }

    @Test fun countsDescribeChangedRecordsAndUniqueRemovals() {
        val local = document(record("a", 1), record("b", 1))
        val remote = document(record("a", 2), record("c", 2), tombstones = listOf(HistorySyncTombstone("b", 3)))
        assertEquals(HistorySyncCounts(uploaded = 0, downloaded = 2, removed = 1),
            HistoryMerge.counts(local, remote, HistoryMerge.merge(local, remote)))
    }

    @Test fun mutationTimeDecidesRecordsAndDeletionsWhileViewingRecencyIsCarriedUnchanged() {
        val recentlyViewed = record("a", 90)
        val pinnedOlderView = record("a", 10).copy(modifiedAt = 100, pinned = true)
        assertEquals(document(pinnedOlderView), HistoryMerge.merge(document(recentlyViewed), document(pinnedOlderView)))
        assertEquals(document(pinnedOlderView), HistoryMerge.merge(document(pinnedOlderView), document(recentlyViewed)))
        val deleted = document(tombstones = listOf(HistorySyncTombstone("a", 100)))
        assertEquals(deleted, HistoryMerge.merge(document(pinnedOlderView), deleted))
        val restored = pinnedOlderView.copy(modifiedAt = 101)
        assertEquals(document(restored), HistoryMerge.merge(document(restored), deleted))
        assertEquals(10L, restored.updatedAt)
    }

    @Test fun overwriteAdvancesTheSelectedVersionEvenWhenItsClockAndVersionAlreadyLeadThePeer() {
        val chosen = record("a", 10).copy(modifiedAt = 200, pinned = true)
        val other = document(record("a", 90))
        val forced = HistoryMerge.overwrite(document(chosen), other, 50)
        assertEquals(chosen.copy(modifiedAt = 201), forced.records.single())
        assertEquals(forced, HistoryMerge.merge(forced, other))
    }

    @Test fun unsupportedDeletionsCannotBeOverwrittenIntoEqualTimeRestorations() {
        val peer = document(tombstones = listOf(HistorySyncTombstone("a", Long.MAX_VALUE)))
        assertEquals(WebDavError.DAMAGED_FILE, assertFailsWith<WebDavException> {
            HistoryMerge.overwrite(document(record("a", 10)), peer, 20)
        }.error)
        val atCeiling = document(tombstones = listOf(HistorySyncTombstone("a", HistoryMutationTime.MAX_EPOCH_MILLIS)))
        assertEquals(WebDavError.DAMAGED_FILE, assertFailsWith<WebDavException> {
            HistoryMerge.overwrite(document(record("a", 10)), atCeiling, 20)
        }.error)
    }

    @Test fun aDeviceThatMissesTheRetentionWindowCanBringAPrunedDeletionBack() {
        val stale = document(record("a", 1))
        val deleted = document(tombstones = listOf(HistorySyncTombstone("a", 2)))
        assertEquals(deleted, HistoryMerge.merge(stale, deleted))
        val pruned = HistoryMerge.pruneTombstones(deleted, HistoryMerge.TOMBSTONE_RETENTION_MILLIS + 3)
        assertEquals(stale, HistoryMerge.merge(stale, pruned))
    }

    private fun record(id: String, time: Long) = HistorySyncRecord(id, "Title", createdAt = 1, updatedAt = time)
    private fun document(vararg records: HistorySyncRecord, tombstones: List<HistorySyncTombstone> = emptyList()) =
        HistorySyncDocument(1, records.toList(), tombstones)
}
