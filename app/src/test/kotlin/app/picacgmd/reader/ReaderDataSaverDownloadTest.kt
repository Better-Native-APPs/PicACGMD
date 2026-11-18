package app.picacgmd.reader

import android.net.ConnectivityManager
import androidx.room.Room
import app.picacgmd.core.data.db.DownloadComic
import app.picacgmd.core.data.db.DownloadEp
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.settings.ImageQuality
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.download.DownloadNetworkState
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ReaderDataSaverDownloadTest {
    @Test fun downloadsKeepTheirStoredQualityWhileOnlineReadingUsesLow() = runTest {
        val context = RuntimeEnvironment.getApplication()
        setReaderTestNetwork(unmetered = true)
        shadowOf(context.getSystemService(ConnectivityManager::class.java))
            .setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)

        for (stored in listOf(ImageQuality.HIGH, ImageQuality.ORIGINAL)) {
            ReaderDataSaverFixture(stored).use { fixture ->
                val user = fixture.settings.settings.first().toReaderConfig()
                val monitor = ReaderNetworkMonitor(context)
                val online = fixture.source {
                    val state = monitor.currentState()
                    effectiveReaderLoading(state.dataSaverEnabled, state.metered, user.quality, user.preloadAhead).quality
                }
                online.loadPages(online.loadChapters().first())
                assertEquals("low", fixture.requests.last().header("image-quality"))

                val database = Room.inMemoryDatabaseBuilder(context, PicaDatabase::class.java).build()
                try {
                    val dao = database.downloadDao()
                    dao.enqueue(DownloadComic("comic", "测试漫画", createdAt = 1L), listOf(
                        DownloadEp("ep-1", "comic", 1, "第 1 话", createdAt = 1L),
                    ))
                    val downloads = DownloadManager(
                        apiProvider = { fixture.api }, imageClientProvider = { fixture.client }, dao = dao,
                        settingsRepository = fixture.settings, context = context, scope = backgroundScope,
                    )
                    downloads.runEngine()

                    assertEquals(stored.name.lowercase(), fixture.requests.last().header("image-quality"))
                    assertEquals(DownloadStatus.DONE, dao.getComic("comic")?.status)
                    assertEquals(stored, fixture.settings.settings.first().imageQuality)
                } finally {
                    database.close()
                }
            }
        }
    }

    private suspend fun DownloadManager.runEngine(): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        val engine = DownloadManager::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(this)
        // Keep this quality test independent of Android callback scheduling.
        engine.javaClass.getDeclaredField("network").apply {
            isAccessible = true
            set(engine, flowOf(DownloadNetworkState(connected = true, unmetered = true)))
        }
        engine.javaClass.getDeclaredMethod("runUntilIdle", Continuation::class.java).apply { isAccessible = true }
            .invoke(engine, continuation)
    }
}
