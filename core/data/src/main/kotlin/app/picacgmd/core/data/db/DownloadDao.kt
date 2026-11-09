package app.picacgmd.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val PENDING_PICTURES_SQL = """
    SELECT p.* FROM download_pictures p
    JOIN download_eps e ON e.epId = p.epId AND e.comicId = p.comicId
    JOIN download_comics c ON c.comicId = p.comicId
    WHERE p.comicId = :comicId AND p.status = 'queued' AND e.picturesFetched = 1
        AND e.status IN ('queued', 'running') AND c.status IN ('queued', 'running')
    ORDER BY e.epOrder, p.pictureIndex, p.pictureId LIMIT :limit
"""

private const val DOWNLOAD_COUNTS_SQL = """
    SELECT c.comicId,
        (SELECT COUNT(*) FROM download_eps e WHERE e.comicId = c.comicId) AS totalEps,
        (SELECT COUNT(*) FROM download_eps e WHERE e.comicId = c.comicId AND e.status = 'done') AS completedEps,
        COUNT(p.pictureId) AS totalPictures,
        COALESCE(SUM(CASE WHEN p.status = 'queued' THEN 1 ELSE 0 END), 0) AS queuedPictures,
        COALESCE(SUM(CASE WHEN p.status = 'running' THEN 1 ELSE 0 END), 0) AS runningPictures,
        COALESCE(SUM(CASE WHEN p.status = 'paused' THEN 1 ELSE 0 END), 0) AS pausedPictures,
        COALESCE(SUM(CASE WHEN p.status = 'failed' THEN 1 ELSE 0 END), 0) AS failedPictures,
        COALESCE(SUM(CASE WHEN p.status = 'done' THEN 1 ELSE 0 END), 0) AS completedPictures,
        COALESCE(SUM(CASE WHEN p.status = 'deleting' THEN 1 ELSE 0 END), 0) AS deletingPictures,
        COALESCE(SUM(p.downloadedBytes), 0) AS downloadedBytes
    FROM download_comics c LEFT JOIN download_pictures p ON p.comicId = c.comicId
    WHERE c.comicId = :comicId GROUP BY c.comicId
"""

@Dao
abstract class DownloadDao {
    @Query("""
        SELECT * FROM download_comics WHERE status != 'deleting'
            AND (:folder IS NULL OR folder = :folder) AND instr(lower(title), lower(:search)) > 0
        ORDER BY createdAt, comicId
    """)
    abstract fun observeComics(folder: String? = null, search: String = ""): Flow<List<DownloadComic>>

    @Query("SELECT DISTINCT folder FROM download_comics WHERE status != 'deleting' AND folder != '' ORDER BY folder")
    abstract fun observeFolders(): Flow<List<String>>

    @Query("SELECT * FROM download_comics WHERE comicId = :comicId")
    abstract fun observeComic(comicId: String): Flow<DownloadComic?>

    @Query("SELECT * FROM download_comics WHERE comicId = :comicId")
    abstract suspend fun getComic(comicId: String): DownloadComic?

    @Query("SELECT * FROM download_comics WHERE status IN ('queued', 'running') ORDER BY createdAt, comicId LIMIT 1")
    abstract suspend fun getNextComic(): DownloadComic?

    @Query("SELECT * FROM download_comics WHERE status = 'deleting' ORDER BY updatedAt, comicId")
    abstract fun observeDeletingComics(): Flow<List<DownloadComic>>

    @Query("SELECT * FROM download_comics WHERE status = 'deleting' ORDER BY updatedAt, comicId")
    abstract suspend fun getDeletingComics(): List<DownloadComic>

    @Query("SELECT * FROM download_eps WHERE comicId = :comicId ORDER BY epOrder")
    abstract fun observeEps(comicId: String): Flow<List<DownloadEp>>

    @Query("SELECT * FROM download_eps WHERE epId = :epId")
    abstract suspend fun getEp(epId: String): DownloadEp?

    @Query("""
        SELECT e.* FROM download_eps e JOIN download_comics c ON c.comicId = e.comicId
        WHERE e.comicId = :comicId AND e.picturesFetched = 0
            AND e.status IN ('queued', 'running') AND c.status IN ('queued', 'running')
        ORDER BY e.epOrder LIMIT 1
    """)
    abstract suspend fun getNextUnfetchedEp(comicId: String): DownloadEp?

    @Query("SELECT * FROM download_pictures WHERE epId = :epId ORDER BY pictureIndex")
    abstract fun observePictures(epId: String): Flow<List<DownloadPicture>>

    @Query("SELECT * FROM download_pictures WHERE pictureId = :pictureId")
    abstract suspend fun getPicture(pictureId: String): DownloadPicture?

    /** Ready pictures only: paused/deleting parents and chapters with an incomplete manifest are excluded. */
    @Query(PENDING_PICTURES_SQL)
    abstract fun observeNextPendingPictures(comicId: String, limit: Int = 100): Flow<List<DownloadPicture>>

    @Query(PENDING_PICTURES_SQL)
    abstract suspend fun nextPendingPictures(comicId: String, limit: Int = 100): List<DownloadPicture>

    @Query(DOWNLOAD_COUNTS_SQL)
    abstract fun observeCounts(comicId: String): Flow<DownloadCounts?>

    @Query(DOWNLOAD_COUNTS_SQL)
    abstract suspend fun getCounts(comicId: String): DownloadCounts?

    // Upsert, not INSERT OR REPLACE: replacing a parent would cascade-delete its existing children.
    @Upsert
    abstract suspend fun upsertComic(comic: DownloadComic)

    @Upsert
    abstract suspend fun upsertEps(eps: List<DownloadEp>)

    @Upsert
    abstract suspend fun upsertPictures(pictures: List<DownloadPicture>)

    /** Stores a comic and its selected chapter snapshot. Empty chapters support background resolution (B6). */
    @Transaction
    open suspend fun enqueue(comic: DownloadComic, eps: List<DownloadEp>) {
        require(eps.all { it.comicId == comic.comicId }) { "All chapters must belong to the queued comic" }
        upsertComic(comic)
        upsertEps(eps)
        refreshProgress(comic.comicId, comic.updatedAt)
    }

    /** Compare-and-set reservation: exactly one of up to 16 workers can claim a queued picture. */
    @Query("""
        UPDATE download_pictures SET status = 'running', error = NULL,
            attemptCount = attemptCount + 1, updatedAt = :updatedAt
        WHERE pictureId = :pictureId AND status = 'queued' AND EXISTS (
            SELECT 1 FROM download_eps e JOIN download_comics c ON c.comicId = e.comicId
            WHERE e.epId = download_pictures.epId AND e.comicId = download_pictures.comicId
                AND e.picturesFetched = 1 AND e.status IN ('queued', 'running')
                AND c.status IN ('queued', 'running')
        )
    """)
    abstract suspend fun claimPicture(pictureId: String, updatedAt: Long): Int

    @Update
    protected abstract suspend fun updatePictures(pictures: List<DownloadPicture>): Int

    /**
     * Batch final results to reduce WAL write contention. Carry the attemptCount returned by getPicture
     * after a successful claim. Late results after pause/deletion or from an older claim are ignored.
     * Returns the number accepted; the caller owns temporary-file cleanup for unaccepted results.
     */
    @Transaction
    open suspend fun savePictureResults(pictures: List<DownloadPicture>, updatedAt: Long): Int {
        require(pictures.map { it.pictureId }.distinct().size == pictures.size) { "Duplicate picture results" }
        val accepted = pictures.mapNotNull { result ->
            require(result.status == DownloadStatus.DONE || result.status == DownloadStatus.FAILED) {
                "Picture result must be done or failed"
            }
            val current = getPicture(result.pictureId) ?: return@mapNotNull null
            require(current.comicId == result.comicId && current.epId == result.epId) {
                "Picture result cannot change its parent"
            }
            if (!current.acceptsResult(result)) return@mapNotNull null
            val comic = getComic(current.comicId) ?: return@mapNotNull null
            val ep = getEp(current.epId) ?: return@mapNotNull null
            if (comic.status !in activeStates || ep.status !in activeStates) return@mapNotNull null
            result.copy(
                updatedAt = updatedAt,
                completedAt = if (result.status == DownloadStatus.DONE) updatedAt else null,
                error = if (result.status == DownloadStatus.DONE) null else result.error,
                attemptCount = current.attemptCount,
            )
        }
        if (accepted.isEmpty()) return 0
        val count = updatePictures(accepted)
        accepted.map { it.comicId }.distinct().forEach { refreshProgress(it, updatedAt) }
        return count
    }

    @Query("""
        UPDATE download_eps SET
            selectedPictureCount = CASE WHEN picturesFetched = 1 THEN
                (SELECT COUNT(*) FROM download_pictures p WHERE p.epId = download_eps.epId)
                ELSE selectedPictureCount END,
            completedPictureCount = (SELECT COUNT(*) FROM download_pictures p
                WHERE p.epId = download_eps.epId AND p.status = 'done'),
            downloadedBytes = COALESCE((SELECT SUM(p.downloadedBytes) FROM download_pictures p
                WHERE p.epId = download_eps.epId), 0), updatedAt = :updatedAt
        WHERE comicId = :comicId
    """)
    protected abstract suspend fun refreshEpProgress(comicId: String, updatedAt: Long)

    @Query("""
        UPDATE download_comics SET
            selectedEpCount = (SELECT COUNT(*) FROM download_eps e WHERE e.comicId = :comicId),
            completedEpCount = (SELECT COUNT(*) FROM download_eps e WHERE e.comicId = :comicId AND e.status = 'done'),
            selectedPictureCount = COALESCE((SELECT SUM(e.selectedPictureCount) FROM download_eps e
                WHERE e.comicId = :comicId), 0),
            completedPictureCount = (SELECT COUNT(*) FROM download_pictures p
                WHERE p.comicId = :comicId AND p.status = 'done'),
            downloadedBytes = COALESCE((SELECT SUM(p.downloadedBytes) FROM download_pictures p
                WHERE p.comicId = :comicId), 0), updatedAt = :updatedAt
        WHERE comicId = :comicId
    """)
    protected abstract suspend fun refreshComicProgress(comicId: String, updatedAt: Long)

    /** Refreshes counters only. The queue engine decides when a resolved chapter/comic is done or failed. */
    @Transaction
    open suspend fun refreshProgress(comicId: String, updatedAt: Long) {
        refreshEpProgress(comicId, updatedAt)
        refreshComicProgress(comicId, updatedAt)
    }

    @Query("""
        UPDATE download_comics SET status = :next, error = :error,
            updatedAt = :updatedAt, completedAt = :completedAt
        WHERE comicId = :comicId AND status = :expected
    """)
    protected abstract suspend fun changeComicStatus(
        comicId: String, expected: DownloadStatus, next: DownloadStatus,
        error: String?, updatedAt: Long, completedAt: Long?,
    ): Int

    /** Pause/resume/delete also updates unfinished descendants in this transaction. Done files survive resume. */
    @Transaction
    open suspend fun transitionComic(
        comicId: String,
        next: DownloadStatus,
        error: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val comic = getComic(comicId) ?: return false
        comic.status.transitionTo(next)
        if (comic.status == next) return true
        val changed = changeComicStatus(
            comicId, comic.status, next, error, updatedAt,
            if (next == DownloadStatus.DONE) comic.completedAt ?: updatedAt else null,
        ) > 0
        if (!changed) return false
        when (next) {
            DownloadStatus.PAUSED -> {
                pauseEps(comicId, updatedAt)
                pausePictures(comicId, updatedAt)
            }
            DownloadStatus.QUEUED -> {
                requeueEps(comicId, updatedAt)
                requeuePictures(comicId, updatedAt)
            }
            DownloadStatus.DELETING -> {
                markEpsDeleting(comicId, updatedAt)
                markPicturesDeleting(comicId, updatedAt)
            }
            else -> Unit
        }
        return true
    }

    @Transaction
    open suspend fun transitionEp(
        epId: String,
        next: DownloadStatus,
        error: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val ep = getEp(epId) ?: return false
        ep.status.transitionTo(next)
        if (ep.status == next) return true
        upsertEps(listOf(ep.copy(
            status = next, error = error, updatedAt = updatedAt,
            completedAt = if (next == DownloadStatus.DONE) ep.completedAt ?: updatedAt else null,
        )))
        if (next == DownloadStatus.DELETING) markEpPicturesDeleting(epId, updatedAt)
        refreshProgress(ep.comicId, updatedAt)
        return true
    }

    @Query("UPDATE download_pictures SET status = 'deleting', updatedAt = :updatedAt WHERE epId = :epId")
    protected abstract suspend fun markEpPicturesDeleting(epId: String, updatedAt: Long)

    @Query("UPDATE download_pictures SET status = 'queued', error = NULL, updatedAt = :updatedAt WHERE epId = :epId AND status = 'failed'")
    protected abstract suspend fun requeueFailedEpPictures(epId: String, updatedAt: Long): Int

    /** Retry just this chapter; other failed/paused chapters and finished files remain untouched. */
    @Transaction
    open suspend fun retryEp(epId: String, updatedAt: Long): Int {
        val ep = getEp(epId) ?: return 0
        val comic = getComic(ep.comicId) ?: return 0
        if (ep.status != DownloadStatus.FAILED || comic.status in setOf(DownloadStatus.PAUSED, DownloadStatus.DELETING)) return 0
        val count = requeueFailedEpPictures(epId, updatedAt)
        transitionEp(epId, DownloadStatus.QUEUED, updatedAt = updatedAt)
        if (comic.status == DownloadStatus.FAILED || comic.status == DownloadStatus.DONE) {
            changeComicStatus(comic.comicId, comic.status, DownloadStatus.QUEUED, null, updatedAt, null)
        }
        return count
    }

    /** Caller removes the chapter directory first; pictures are cascade-deleted. */
    @Query("DELETE FROM download_eps WHERE epId = :epId AND status = 'deleting'")
    abstract suspend fun deleteEp(epId: String): Int

    @Query("UPDATE download_eps SET status = 'paused', updatedAt = :updatedAt WHERE comicId = :comicId AND status IN ('queued', 'running', 'failed')")
    protected abstract suspend fun pauseEps(comicId: String, updatedAt: Long)

    @Query("UPDATE download_pictures SET status = 'paused', updatedAt = :updatedAt WHERE comicId = :comicId AND status IN ('queued', 'running', 'failed')")
    protected abstract suspend fun pausePictures(comicId: String, updatedAt: Long)

    @Query("UPDATE download_eps SET status = 'queued', error = NULL, completedAt = NULL, updatedAt = :updatedAt WHERE comicId = :comicId AND status IN ('paused', 'running', 'failed')")
    protected abstract suspend fun requeueEps(comicId: String, updatedAt: Long)

    @Query("UPDATE download_pictures SET status = 'queued', error = NULL, completedAt = NULL, updatedAt = :updatedAt WHERE comicId = :comicId AND status IN ('paused', 'running', 'failed')")
    protected abstract suspend fun requeuePictures(comicId: String, updatedAt: Long)

    @Query("UPDATE download_eps SET status = 'deleting', updatedAt = :updatedAt WHERE comicId = :comicId")
    protected abstract suspend fun markEpsDeleting(comicId: String, updatedAt: Long)

    @Query("UPDATE download_pictures SET status = 'deleting', updatedAt = :updatedAt WHERE comicId = :comicId")
    protected abstract suspend fun markPicturesDeleting(comicId: String, updatedAt: Long)

    @Query("""
        UPDATE download_pictures SET status = 'queued', error = NULL, updatedAt = :updatedAt
        WHERE status = 'failed' AND comicId IN (
            SELECT comicId FROM download_comics WHERE status IN ('queued', 'running', 'failed')
        )
    """)
    protected abstract suspend fun requeueFailedPictures(updatedAt: Long): Int

    @Query("""
        UPDATE download_eps SET status = 'queued', error = NULL, updatedAt = :updatedAt
        WHERE status = 'failed' AND comicId IN (
            SELECT comicId FROM download_comics WHERE status IN ('queued', 'running', 'failed')
        )
    """)
    protected abstract suspend fun requeueFailedEps(updatedAt: Long)

    @Query("UPDATE download_comics SET status = 'queued', error = NULL, updatedAt = :updatedAt WHERE status = 'failed'")
    protected abstract suspend fun requeueFailedComics(updatedAt: Long)

    /** Returns retried picture count; explicitly paused/deleting comics remain untouched. */
    @Transaction
    open suspend fun retryFailed(updatedAt: Long): Int {
        val count = requeueFailedPictures(updatedAt)
        requeueFailedEps(updatedAt)
        requeueFailedComics(updatedAt)
        return count
    }

    @Query("""
        UPDATE download_pictures SET status = 'queued', updatedAt = :updatedAt
        WHERE status = 'running' AND epId IN (
            SELECT e.epId FROM download_eps e JOIN download_comics c ON c.comicId = e.comicId
            WHERE e.status IN ('queued', 'running') AND c.status IN ('queued', 'running')
        )
    """)
    protected abstract suspend fun recoverRunningPictures(updatedAt: Long): Int

    @Query("""
        UPDATE download_eps SET status = 'queued', updatedAt = :updatedAt
        WHERE status = 'running' AND comicId IN (
            SELECT comicId FROM download_comics WHERE status IN ('queued', 'running')
        )
    """)
    protected abstract suspend fun recoverRunningEps(updatedAt: Long)

    @Query("UPDATE download_comics SET status = 'queued', updatedAt = :updatedAt WHERE status = 'running'")
    protected abstract suspend fun recoverRunningComics(updatedAt: Long)

    /** Call once when restarting the engine after process death, not while workers are still active. */
    @Transaction
    open suspend fun recoverInterrupted(updatedAt: Long): Int {
        val count = recoverRunningPictures(updatedAt)
        recoverRunningEps(updatedAt)
        recoverRunningComics(updatedAt)
        return count
    }

    @Query("UPDATE download_comics SET folder = :folder, updatedAt = :updatedAt WHERE comicId IN (:comicIds) AND status != 'deleting'")
    abstract suspend fun moveComics(comicIds: List<String>, folder: String, updatedAt: Long): Int

    /** Caller removes the app-private directory first; children are then cascade-deleted. */
    @Query("DELETE FROM download_comics WHERE comicId = :comicId AND status = 'deleting'")
    abstract suspend fun deleteComic(comicId: String): Int

    private val activeStates: Set<DownloadStatus> = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)
}
