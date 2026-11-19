package app.picacgmd.feature.downloads

import android.content.Context
import android.net.NetworkCapabilities
import androidx.room.Room
import app.picacgmd.core.data.db.DownloadStatus
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.core.domain.favourite.FavouriteDownloadActions
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.testing.MemoryPreferencesStore
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class FavouriteDownloadActionsTest {
    private lateinit var database: PicaDatabase
    private lateinit var workQueue: Any
    private lateinit var taskExecutor: ExecutorService

    @BeforeTest fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, PicaDatabase::class.java).allowMainThreadQueries().build()
        taskExecutor = Executors.newSingleThreadExecutor()
        initializeWorkQueue(context)
    }

    @AfterTest fun tearDown() {
        val operation = workQueue.javaClass.getMethod("cancelAllWork").invoke(workQueue)
        val result = Class.forName("androidx.work.Operation").getMethod("getResult").invoke(operation) as Future<*>
        result.get(5, TimeUnit.SECONDS)
        val workScope = workQueue.javaClass.getDeclaredMethod("getWorkManagerScope").apply { isAccessible = true }
            .invoke(workQueue) as CoroutineScope
        workScope.cancel()
        workQueue.javaClass.getMethod("closeDatabase").invoke(workQueue)
        taskExecutor.shutdownNow()
        resetWorkQueue()
        database.close()
    }

    @Test fun meteredWifiSkipsAutomaticDownloads() = runTest {
        setDownloadTestNetwork(NetworkCapabilities.TRANSPORT_WIFI, unmetered = false)
        val actions = actions()
        val comic = ComicInfo(id = "metered-wifi", title = "测试漫画")

        assertEquals("未连接不计流量的网络，已跳过自动下载", actions.onAdded(comic))
        assertNull(database.downloadDao().getComic(comic.id))
    }

    @Test fun unmeteredNonWifiAddsAllChaptersToTheQueue() = runTest {
        val actions = actions()
        for (transport in listOf(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET)) {
            setDownloadTestNetwork(transport, unmetered = true)
            val comic = ComicInfo(id = "unmetered-$transport", title = "测试漫画")

            assertEquals("已加入全部章节下载队列", actions.onAdded(comic))
            assertEquals(DownloadStatus.QUEUED, database.downloadDao().getComic(comic.id)?.status)
        }
    }

    @Test fun disablingTheRestrictionAddsAutomaticDownloadsOnMeteredNetworks() = runTest {
        val actions = actions(restricted = false)
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR)) {
            setDownloadTestNetwork(transport, unmetered = false)
            val comic = ComicInfo(id = "metered-$transport", title = "测试漫画")

            assertEquals("已加入全部章节下载队列", actions.onAdded(comic))
            assertEquals(DownloadStatus.QUEUED, database.downloadDao().getComic(comic.id)?.status)
        }
    }

    private fun TestScope.actions(restricted: Boolean = true): FavouriteDownloadActions {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(MemoryPreferencesStore(), AppSettings(
            autoDownloadOnFavourite = true, autoDownloadWifiOnly = restricted,
        ))
        val downloads = DownloadManager(
            apiProvider = { error("Enqueue must not request chapter metadata") },
            imageClientProvider = { error("Enqueue must not download images") },
            dao = database.downloadDao(), settingsRepository = settings, context = context, scope = backgroundScope,
        )
        return FavouriteDownloadActions(context, settings, downloads)
    }

    private fun initializeWorkQueue(context: Context) {
        resetWorkQueue()
        // Keep workers queued: these tests exercise automatic enqueue, not network transfers.
        val builder = Class.forName("androidx.work.Configuration\$Builder").getConstructor().newInstance()
        builder.javaClass.getMethod("setExecutor", Executor::class.java).invoke(builder, Executor { })
        builder.javaClass.getMethod("setTaskExecutor", Executor::class.java).invoke(builder, taskExecutor)
        val configuration = builder.javaClass.getMethod("build").invoke(builder)
        val type = Class.forName("androidx.work.WorkManager")
        type.getMethod("initialize", Context::class.java, configuration.javaClass).invoke(null, context, configuration)
        workQueue = type.getMethod("getInstance", Context::class.java).invoke(null, context)
    }

    private fun resetWorkQueue() {
        val type = Class.forName("androidx.work.impl.WorkManagerImpl")
        type.getMethod("setDelegate", type).invoke(null, null)
        type.getDeclaredField("sDefaultInstance").apply { isAccessible = true }.set(null, null)
    }
}
