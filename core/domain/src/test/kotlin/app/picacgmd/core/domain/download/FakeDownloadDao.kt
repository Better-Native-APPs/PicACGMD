package app.picacgmd.core.domain.download

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadCounts
import app.picacgmd.core.data.db.DownloadDao
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadPicture
import app.picacgmd.core.data.db.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Implements SQL primitives in memory; the real DAO's transaction/result/state logic is reused. */
internal class FakeDownloadDao : DownloadDao() {
    private val lock = Any()
    private val comics = linkedMapOf<String, DownloadComic>()
    private val eps = linkedMapOf<String, DownloadEp>()
    private val pictures = linkedMapOf<String, DownloadPicture>()
    private val revision = MutableStateFlow(0L)
    var acceptedResults = 0
        private set
    val resultBatchSizes = mutableListOf<Int>()
    private val active = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)

    private fun <T> query(block: () -> T): T = synchronized(lock) { block() }
    private fun <T> change(block: () -> T): T = synchronized(lock) {
        block().also { revision.value++ }
    }
    private fun <T> observe(block: () -> T): Flow<T> = revision.map { query(block) }

    override fun observeComics(folder: String?, search: String) = observe {
        comics.values.filter { it.status != DownloadStatus.DELETING && (folder == null || it.folder == folder) &&
            it.title.contains(search, ignoreCase = true) }.sortedWith(compareBy({ it.createdAt }, { it.comicId }))
    }
    override fun observeFolders() = observe { comics.values.map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted() }
    override fun observeComic(comicId: String) = observe { comics[comicId] }
    override suspend fun getComic(comicId: String) = query { comics[comicId] }
    override suspend fun getNextComic() = query {
        comics.values.filter { it.status in active }.minWithOrNull(compareBy({ it.createdAt }, { it.comicId }))
    }
    override fun observeDeletingComics() = observe { comics.values.filter { it.status == DownloadStatus.DELETING } }
    override suspend fun getDeletingComics() = query { comics.values.filter { it.status == DownloadStatus.DELETING } }
    override fun observeEps(comicId: String) = observe { eps.values.filter { it.comicId == comicId }.sortedBy { it.epOrder } }
    override suspend fun getEp(epId: String) = query { eps[epId] }
    override suspend fun getNextUnfetchedEp(comicId: String) = query {
        eps.values.filter { it.comicId == comicId && !it.picturesFetched && it.status in active &&
            comics[comicId]?.status in active }.minByOrNull { it.epOrder }
    }
    override fun observePictures(epId: String) = observe { pictures.values.filter { it.epId == epId }.sortedBy { it.pictureIndex } }
    override suspend fun getPicture(pictureId: String) = query { pictures[pictureId] }
    override fun observeNextPendingPictures(comicId: String, limit: Int) = observe { pending(comicId, limit) }
    override suspend fun nextPendingPictures(comicId: String, limit: Int) = query { pending(comicId, limit) }
    private fun pending(comicId: String, limit: Int) = pictures.values.filter {
        it.comicId == comicId && it.status == DownloadStatus.QUEUED && eps[it.epId]?.picturesFetched == true &&
            eps[it.epId]?.status in active && comics[comicId]?.status in active
    }.sortedWith(compareBy({ it.epOrder }, { it.pictureIndex }, { it.pictureId })).take(limit)

    override fun observeCounts(comicId: String) = observe { counts(comicId) }
    override suspend fun getCounts(comicId: String) = query { counts(comicId) }
    private fun counts(comicId: String): DownloadCounts? {
        if (comics[comicId] == null) return null
        val children = pictures.values.filter { it.comicId == comicId }
        val chapters = eps.values.filter { it.comicId == comicId }
        return DownloadCounts(
            comicId, chapters.size, chapters.count { it.status == DownloadStatus.DONE }, children.size,
            children.count { it.status == DownloadStatus.QUEUED }, children.count { it.status == DownloadStatus.RUNNING },
            children.count { it.status == DownloadStatus.PAUSED }, children.count { it.status == DownloadStatus.FAILED },
            children.count { it.status == DownloadStatus.DONE }, children.count { it.status == DownloadStatus.DELETING },
            children.sumOf { it.downloadedBytes },
        )
    }

    override suspend fun upsertComic(comic: DownloadComic) { change { comics[comic.comicId] = comic } }
    override suspend fun upsertEps(eps: List<DownloadEp>) { change { eps.forEach { this.eps[it.epId] = it } } }
    override suspend fun upsertPictures(pictures: List<DownloadPicture>) { change { pictures.forEach { this.pictures[it.pictureId] = it } } }
    override suspend fun claimPicture(pictureId: String, updatedAt: Long): Int = change {
        val picture = pictures[pictureId] ?: return@change 0
        if (picture.status != DownloadStatus.QUEUED || comics[picture.comicId]?.status !in active ||
            eps[picture.epId]?.status !in active || eps[picture.epId]?.picturesFetched != true) return@change 0
        pictures[pictureId] = picture.copy(status = DownloadStatus.RUNNING, attemptCount = picture.attemptCount + 1,
            error = null, updatedAt = updatedAt)
        1
    }
    override suspend fun updatePictures(pictures: List<DownloadPicture>): Int = change {
        resultBatchSizes.add(pictures.size)
        pictures.forEach { this.pictures[it.pictureId] = it }
        acceptedResults += pictures.size
        pictures.size
    }

    override suspend fun refreshEpProgress(comicId: String, updatedAt: Long) {
        change {
            eps.values.filter { it.comicId == comicId }.toList().forEach { ep ->
                val children = pictures.values.filter { it.epId == ep.epId }
                eps[ep.epId] = ep.copy(
                    selectedPictureCount = if (ep.picturesFetched) children.size else ep.selectedPictureCount,
                    completedPictureCount = children.count { it.status == DownloadStatus.DONE },
                    downloadedBytes = children.sumOf { it.downloadedBytes }, updatedAt = updatedAt,
                )
            }
        }
    }
    override suspend fun refreshComicProgress(comicId: String, updatedAt: Long) {
        change {
            val comic = comics[comicId] ?: return@change
            val children = pictures.values.filter { it.comicId == comicId }
            val chapters = eps.values.filter { it.comicId == comicId }
            comics[comicId] = comic.copy(selectedEpCount = chapters.size,
                completedEpCount = chapters.count { it.status == DownloadStatus.DONE },
                selectedPictureCount = chapters.sumOf { it.selectedPictureCount },
                completedPictureCount = children.count { it.status == DownloadStatus.DONE },
                downloadedBytes = children.sumOf { it.downloadedBytes }, updatedAt = updatedAt)
        }
    }
    override suspend fun changeComicStatus(comicId: String, expected: DownloadStatus, next: DownloadStatus,
        error: String?, updatedAt: Long, completedAt: Long?): Int = change {
        val comic = comics[comicId] ?: return@change 0
        if (comic.status != expected) return@change 0
        comics[comicId] = comic.copy(status = next, error = error, updatedAt = updatedAt, completedAt = completedAt)
        1
    }

    private fun updateEps(comicId: String?, from: Set<DownloadStatus>, next: DownloadStatus, updatedAt: Long,
        clearError: Boolean = false) = change {
        eps.values.filter { (comicId == null || it.comicId == comicId) && it.status in from }.toList().forEach {
            eps[it.epId] = it.copy(status = next, updatedAt = updatedAt, error = if (clearError) null else it.error,
                completedAt = if (clearError) null else it.completedAt)
        }
    }
    private fun updatePictures(comicId: String?, from: Set<DownloadStatus>, next: DownloadStatus, updatedAt: Long,
        clearError: Boolean = false): Int = change {
        val changed = pictures.values.filter { (comicId == null || it.comicId == comicId) && it.status in from }.toList()
        changed.forEach {
            pictures[it.pictureId] = it.copy(status = next, updatedAt = updatedAt, error = if (clearError) null else it.error,
                completedAt = if (clearError) null else it.completedAt)
        }
        changed.size
    }
    override suspend fun pauseEps(comicId: String, updatedAt: Long) {
        updateEps(comicId, active + DownloadStatus.FAILED, DownloadStatus.PAUSED, updatedAt)
    }
    override suspend fun pausePictures(comicId: String, updatedAt: Long) {
        updatePictures(comicId, active + DownloadStatus.FAILED, DownloadStatus.PAUSED, updatedAt)
    }
    override suspend fun requeueEps(comicId: String, updatedAt: Long) {
        updateEps(comicId, setOf(DownloadStatus.PAUSED, DownloadStatus.RUNNING, DownloadStatus.FAILED),
            DownloadStatus.QUEUED, updatedAt, true)
    }
    override suspend fun requeuePictures(comicId: String, updatedAt: Long) {
        updatePictures(comicId, setOf(DownloadStatus.PAUSED, DownloadStatus.RUNNING, DownloadStatus.FAILED),
            DownloadStatus.QUEUED, updatedAt, true)
    }
    override suspend fun markEpsDeleting(comicId: String, updatedAt: Long) {
        updateEps(comicId, DownloadStatus.entries.toSet(), DownloadStatus.DELETING, updatedAt)
    }
    override suspend fun markPicturesDeleting(comicId: String, updatedAt: Long) {
        updatePictures(comicId, DownloadStatus.entries.toSet(), DownloadStatus.DELETING, updatedAt)
    }
    override suspend fun requeueFailedPictures(updatedAt: Long): Int = change {
        comics.values.filter { it.status in active + DownloadStatus.FAILED }.sumOf {
            updatePictures(it.comicId, setOf(DownloadStatus.FAILED), DownloadStatus.QUEUED, updatedAt, true)
        }
    }
    override suspend fun requeueFailedEps(updatedAt: Long) {
        query { comics.values.filter { it.status in active + DownloadStatus.FAILED }.map { it.comicId } }.forEach {
            updateEps(it, setOf(DownloadStatus.FAILED), DownloadStatus.QUEUED, updatedAt, true)
        }
    }
    override suspend fun requeueFailedComics(updatedAt: Long) {
        change { comics.values.filter { it.status == DownloadStatus.FAILED }.toList().forEach {
            comics[it.comicId] = it.copy(status = DownloadStatus.QUEUED, error = null, updatedAt = updatedAt)
        } }
    }
    override suspend fun recoverRunningPictures(updatedAt: Long): Int = change {
        val changed = pictures.values.filter { it.status == DownloadStatus.RUNNING &&
            comics[it.comicId]?.status in active && eps[it.epId]?.status in active }.toList()
        changed.forEach { pictures[it.pictureId] = it.copy(status = DownloadStatus.QUEUED, updatedAt = updatedAt) }
        changed.size
    }
    override suspend fun recoverRunningEps(updatedAt: Long) {
        query { comics.values.filter { it.status in active }.map { it.comicId } }.forEach {
            updateEps(it, setOf(DownloadStatus.RUNNING), DownloadStatus.QUEUED, updatedAt)
        }
    }
    override suspend fun recoverRunningComics(updatedAt: Long) {
        change { comics.values.filter { it.status == DownloadStatus.RUNNING }.toList().forEach {
            comics[it.comicId] = it.copy(status = DownloadStatus.QUEUED, updatedAt = updatedAt)
        } }
    }
    override suspend fun moveComics(comicIds: List<String>, folder: String, updatedAt: Long): Int = change {
        val selected = comics.values.filter { it.comicId in comicIds && it.status != DownloadStatus.DELETING }.toList()
        selected.forEach { comics[it.comicId] = it.copy(folder = folder, updatedAt = updatedAt) }
        selected.size
    }
    override suspend fun deleteComic(comicId: String): Int = change {
        if (comics[comicId]?.status != DownloadStatus.DELETING) return@change 0
        comics.remove(comicId)
        eps.entries.removeIf { it.value.comicId == comicId }
        pictures.entries.removeIf { it.value.comicId == comicId }
        1
    }
}
