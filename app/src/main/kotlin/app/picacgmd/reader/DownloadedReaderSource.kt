package app.picacgmd.reader

import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadDao
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.domain.download.DownloadFileStore
import app.picacgmd.core.domain.repository.HistoryRepository
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.PicaImage
import app.picacgmd.core.network.PicaJson
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.ReaderChapter
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.ReaderProgress
import app.picacgmd.feature.reader.ReaderSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Completed chapters only; opening a download never needs an online catalogue or picture request. */
class DownloadedReaderSource(
    private val comic: DownloadComic,
    private val downloadDao: DownloadDao,
    private val files: DownloadFileStore,
    private val historyRepository: HistoryRepository,
    private val onProgressError: (Exception) -> Unit,
) : ReaderSource {
    override val comicTitle: String = comic.title
    override val comicCover: PageImage? = files.coverFile(comic.comicId).takeIf { it.isFile }?.let { PageImage.Local(it) }

    private val historyComic = ComicInfo(
        id = comic.comicId,
        title = comic.title,
        author = comic.author,
        categories = PicaJson.decodeFromString<List<String>>(comic.categoriesJson),
        thumb = comic.thumbPath.takeIf(String::isNotBlank)?.let { PicaImage(comic.thumbFileServer, it) },
    )
    private val chapterMutex = Mutex()
    private var episodes: List<DownloadEp> = emptyList()
    private var chapters: List<ReaderChapter>? = null

    override suspend fun loadChapters(): List<ReaderChapter> = withContext(Dispatchers.IO) {
        chapterMutex.withLock {
            chapters ?: run {
                val eps = downloadDao.observeEps(comic.comicId).first().filter { it.status == DownloadStatus.DONE }
                val progress = historyRepository.getProgress(comic.comicId)
                val result = eps.map { ep ->
                    ReaderChapter(
                        id = ep.epId,
                        title = ep.title,
                        isRead = ep.epOrder <= (progress?.epOrder ?: 0),
                        isDownloaded = true,
                    )
                }
                episodes = eps
                chapters = result
                result
            }
        }
    }

    suspend fun indexOfEpisode(episodeOrder: Int): Int {
        loadChapters()
        return episodes.indexOfFirst { it.epOrder == episodeOrder }
    }

    override suspend fun loadPages(chapter: ReaderChapter): List<ReaderPage> = withContext(Dispatchers.IO) {
        val root = files.rootDirectory()
        downloadDao.observePictures(chapter.id).first()
            .filter { it.status == DownloadStatus.DONE }
            .map { picture ->
                ReaderPage(
                    image = PageImage.Local(File(root, picture.filePath)),
                    width = picture.width ?: 0,
                    height = picture.height ?: 0,
                )
            }
    }

    override fun saveProgress(progress: ReaderProgress) {
        ReaderPersistence.launch(onProgressError) {
            val ep = checkNotNull(episodes.firstOrNull { it.epId == progress.chapter.id }) { "找不到该章节" }
            historyRepository.saveProgress(historyComic, ep.epOrder, ep.title, progress.pageIndex + 1)
        }
    }
}
