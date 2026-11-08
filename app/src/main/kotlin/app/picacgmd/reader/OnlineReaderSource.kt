package app.picacgmd.reader

import app.picacgmd.core.domain.repository.ComicRepository
import app.picacgmd.core.domain.repository.HistoryRepository
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.Ep
import app.picacgmd.feature.reader.ImageQuality
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.ReaderChapter
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.ReaderProgress
import app.picacgmd.feature.reader.ReaderSource
import app.picacgmd.core.network.ImageServer
import app.picacgmd.core.network.PicaImage
import app.picacgmd.core.network.PicaImageUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.picacgmd.core.network.ImageQuality as NetworkImageQuality

/** Online episodes and complete picture manifests, resolved through the configured image server. */
class OnlineReaderSource(
    private val comic: ComicInfo,
    private val comicRepository: ComicRepository,
    private val historyRepository: HistoryRepository,
    private val networkConfigProvider: NetworkConfigProvider,
    private val onProgressError: (Exception) -> Unit,
    private val qualityProvider: () -> ImageQuality,
) : ReaderSource {
    override val comicTitle: String = comic.title
    override val comicCover: PageImage? = comic.thumb?.takeIf { it.path.isNotBlank() }
        ?.let { PageImage.Remote(networkConfigProvider.imageUrl(it)) }
    override val commentCount: Int = comic.commentsCount

    override fun imageLoadProgress(url: String): StateFlow<Float?>? = networkConfigProvider.imageLoadProgress.observe(url)

    /**
     * Page images keyed by their server-independent cache key (the address on the original server),
     * so pages can be re-addressed when the image route changes while the reader is open.
     */
    private val pageMedia = ConcurrentHashMap<String, PicaImage>()

    override val pageAddressChanges: Flow<Unit> = networkConfigProvider.clients
        .map { it.configuration.imageServer }
        .distinctUntilChanged()
        .map { }

    override fun refreshPage(page: ReaderPage): ReaderPage {
        val image = page.image as? PageImage.Remote ?: return page
        val media = image.cacheKey?.let(pageMedia::get) ?: return page
        val url = networkConfigProvider.imageUrl(media)
        return if (url == image.url) page else page.copy(image = image.copy(url = url))
    }

    private fun remotePage(media: PicaImage): ReaderPage {
        val key = PicaImageUrls.build(media, ImageServer.Original)
        pageMedia[key] = media
        return ReaderPage(PageImage.Remote(networkConfigProvider.imageUrl(media), cacheKey = key))
    }

    private val chapterMutex = Mutex()
    private var episodes: List<Ep> = emptyList()
    private var chapters: List<ReaderChapter>? = null

    override suspend fun loadChapters(): List<ReaderChapter> = withContext(Dispatchers.IO) {
        chapterMutex.withLock {
            chapters ?: run {
                val eps = comicRepository.allEps(comic.id)
                val progress = historyRepository.getProgress(comic.id)
                val result = eps.map { ep ->
                    ReaderChapter(id = ep.id, title = ep.title, isRead = ep.order <= (progress?.epOrder ?: 0))
                }
                episodes = eps
                chapters = result
                result
            }
        }
    }

    /** Episode orders are one-based but need not be contiguous; -1 means it is no longer available. */
    suspend fun indexOfEpisode(episodeOrder: Int): Int {
        loadChapters()
        return episodes.indexOfFirst { it.order == episodeOrder }
    }

    override suspend fun loadPages(chapter: ReaderChapter): List<ReaderPage> {
        // One quality snapshot per manifest, including all of its API pages.
        val quality = when (qualityProvider()) {
            ImageQuality.Original -> NetworkImageQuality.ORIGINAL
            ImageQuality.High -> NetworkImageQuality.HIGH
            ImageQuality.Medium -> NetworkImageQuality.MEDIUM
            ImageQuality.Low -> NetworkImageQuality.LOW
        }
        return withContext(Dispatchers.IO) {
            loadChapters()
            val ep = checkNotNull(episodes.firstOrNull { it.id == chapter.id }) { "找不到该章节" }
            val pages = mutableListOf<ReaderPage>()
            var page = 1
            do {
                val result = comicRepository.pictures(comic.id, ep.order, page, quality = quality).pages
                pages += result.docs.map { picture -> remotePage(picture.media) }
                page++
            } while (page <= result.pages)
            pages
        }
    }

    override fun saveProgress(progress: ReaderProgress) {
        ReaderPersistence.launch(onProgressError) {
            val ep = checkNotNull(episodes.firstOrNull { it.id == progress.chapter.id }) { "找不到该章节" }
            historyRepository.saveProgress(comic, ep.order, ep.title, progress.pageIndex + 1)
        }
    }
}
