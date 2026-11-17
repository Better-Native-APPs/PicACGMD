package app.picacgmd.backup

import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.domain.cache.CacheMaintenance
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.ComicRepository
import app.picacgmd.core.domain.repository.SubscriptionCheckReport
import app.picacgmd.core.domain.repository.SubscriptionCheckState
import app.picacgmd.core.domain.repository.SubscriptionRepository
import app.picacgmd.core.domain.session.KeystoreCredentialStore
import app.picacgmd.core.domain.session.NetworkConfigProvider
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.core.network.webdav.OkHttpWebDavClient
import coil3.ImageLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sun.misc.Unsafe

/** Exercises the actual app-open method with local storage and an API fake that always fails. */
@RunWith(RobolectricTestRunner::class)
class RestoreAppStartupTest {
    @Before
    fun setUpKeyStore() = BackupTestKeyStore.install()

    @After
    fun tearDownKeyStore() = BackupTestKeyStore.remove()

    @Test
    fun appOpenAfterRestoreStaysSignedOutAndDoesNotRefreshRestoredSubscriptions() {
        val context = RuntimeEnvironment.getApplication()
        val storage = SystemBackupStorage(context)
        context.deleteDatabase(PicaDatabase.DATABASE_NAME)
        storage.settingsFile.parentFile?.deleteRecursively()
        storage.clearSnapshots()
        context.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
        // Seed already-committed inbound files; this app-open fixture does not exercise platform restore delivery.
        BackupDatabaseFixture.create(storage.restoredDatabaseSnapshot).use { BackupDatabaseFixture.seed(it) }
        populateBackupSettings(storage.restoredSettingsSnapshot)
        storage.markRestorePending()
        storage.consumeRestore()

        withBackupSettings(storage.settingsFile) { settings ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val database = PicaDatabase.create(context)
            val session = SessionManager(settings, KeystoreCredentialStore(context), {
                error("Signed-out app startup must not request authenticated data")
            }, scope)
            val network = NetworkConfigProvider(settings.settings, scope) { session.token }
            val imageLoader = ImageLoader.Builder(context).build()
            val comics = ComicRepository(
                session, settings.settings, database.apiCacheDao(), database.searchHistoryDao(), database.viewHistoryDao(),
            )
            val subscriptions = SubscriptionRepository(database.subscriptionDao(), comics, settings.settings)
            // As in the existing container fixtures, bypass only Android/download assembly, not app-open behavior.
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
            val container = unsafe.allocateInstance(AppContainer::class.java) as AppContainer
            mapOf(
                "scope" to scope, "database" to database, "settingsRepository" to settings, "sessionManager" to session,
                "networkConfigProvider" to network, "imageLoader" to imageLoader, "subscriptionRepository" to subscriptions,
                "cacheMaintenance" to CacheMaintenance(context, imageLoader, database.apiCacheDao()),
                "webDavClient" to OkHttpWebDavClient(),
            ).forEach { (name, value) ->
                AppContainer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(container, value)
            }
            try {
                assertEquals(SubscriptionCheckReport(0, 0, emptyMap()), container.onAppOpen())
                assertEquals(SessionState.SignedOut, session.state.value)
                assertNull(session.token)
                assertEquals(SubscriptionCheckState.Idle, subscriptions.checkState.value)
                assertEquals(1, subscriptions.subscriptions.first().size)
                val history = database.viewHistoryDao().get(BackupDatabaseFixture.COMIC)
                assertEquals(3, history?.lastEpOrder)
                assertEquals(4, history?.lastPictureIndex)
                assertEquals(12, history?.lastChapterPages)
            } finally {
                container.close()
            }
        }
    }
}
