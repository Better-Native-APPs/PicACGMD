package app.picacgmd.core.domain.sync

import app.picacgmd.core.data.db.HistoryMutationTime
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavException

enum class HistorySyncDirection { MERGE, LOCAL_TO_REMOTE, REMOTE_TO_LOCAL }

data class HistorySyncCounts(val uploaded: Int = 0, val downloaded: Int = 0, val removed: Int = 0)

object HistoryMerge {
    const val TOMBSTONE_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1_000

    /** Equal mutation times use the canonical full-record JSON; a deletion wins an event-time tie. */
    fun merge(local: HistorySyncDocument, remote: HistorySyncDocument): HistorySyncDocument {
        HistorySyncFormat.validateEventTimes(local)
        HistorySyncFormat.validateEventTimes(remote)
        val records = mutableMapOf<String, HistorySyncRecord>()
        (local.records + remote.records).forEach { record ->
            val previous = records[record.comicId]
            // Device wall clocks decide event versions; a fast clock wins until the other device passes it.
            if (previous == null || record.modifiedAt > previous.modifiedAt ||
                record.modifiedAt == previous.modifiedAt && HistorySyncFormat.tieKey(record) > HistorySyncFormat.tieKey(previous)
            ) records[record.comicId] = record
        }
        val tombstones = mutableMapOf<String, HistorySyncTombstone>()
        (local.tombstones + remote.tombstones).forEach {
            if (it.deletedAt >= (tombstones[it.comicId]?.deletedAt ?: -1)) tombstones[it.comicId] = it
        }
        tombstones.toMap().forEach { (id, deletion) ->
            val record = records[id]
            if (record != null && record.modifiedAt > deletion.deletedAt) tombstones.remove(id) else records.remove(id)
        }
        return HistorySyncDocument(HistorySyncDocument.VERSION, records.values.sortedBy { it.comicId },
            tombstones.values.sortedBy { it.comicId })
    }

    /** An explicit overwrite is a new event, so a later merge cannot undo the chosen direction. */
    fun overwrite(authoritative: HistorySyncDocument, other: HistorySyncDocument, now: Long): HistorySyncDocument {
        val chosen = merge(authoritative, HistorySyncDocument.EMPTY)
        val others = merge(other, HistorySyncDocument.EMPTY)
        val otherRecords = others.records.associateBy { it.comicId }
        val otherDeletions = others.tombstones.associateBy { it.comicId }
        val records = chosen.records.map {
            val previous = otherRecords[it.comicId]
            val deletion = otherDeletions[it.comicId]
            if (previous == it && deletion == null || previous == null && deletion == null) it else it.copy(
                modifiedAt = nextTimestamp(maxOf(now, it.modifiedAt), it.modifiedAt, previous?.modifiedAt, deletion?.deletedAt),
            )
        }
        val chosenIds = records.map { it.comicId }.toSet()
        val deletions = (chosen.tombstones + others.tombstones).filterNot { it.comicId in chosenIds }
            .groupBy { it.comicId }.mapValues { (_, events) -> events.maxBy { it.deletedAt } }.toMutableMap()
        others.records.filterNot { it.comicId in chosenIds }.forEach {
            deletions[it.comicId] = HistorySyncTombstone(it.comicId,
                nextTimestamp(now, it.modifiedAt, deletions[it.comicId]?.deletedAt))
        }
        return HistorySyncDocument(HistorySyncDocument.VERSION, records.sortedBy { it.comicId },
            deletions.values.sortedBy { it.comicId })
    }

    /** Call only after resolving deletions. A failed upload must keep unpruned local tombstones. */
    fun pruneTombstones(document: HistorySyncDocument, now: Long): HistorySyncDocument =
        // After 180-day pruning, a device that has not synced for longer can bring deleted records back.
        document.copy(tombstones = document.tombstones.filter { it.deletedAt >= pruningThreshold(now) })

    fun pruningThreshold(now: Long): Long = (now - TOMBSTONE_RETENTION_MILLIS).coerceAtLeast(0)

    fun counts(local: HistorySyncDocument, remote: HistorySyncDocument, final: HistorySyncDocument): HistorySyncCounts {
        val localRecords = local.records.associateBy { it.comicId }
        val remoteRecords = remote.records.associateBy { it.comicId }
        val finalRecords = final.records.associateBy { it.comicId }
        return HistorySyncCounts(
            uploaded = final.records.count { remoteRecords[it.comicId] != it },
            downloaded = final.records.count { localRecords[it.comicId] != it },
            removed = (localRecords.keys + remoteRecords.keys).count { it !in finalRecords },
        )
    }

    private fun nextTimestamp(requested: Long, vararg observed: Long?): Long = try {
        HistoryMutationTime.next(requested, *observed)
    } catch (_: IllegalArgumentException) {
        throw WebDavException(WebDavError.DAMAGED_FILE)
    }
}
