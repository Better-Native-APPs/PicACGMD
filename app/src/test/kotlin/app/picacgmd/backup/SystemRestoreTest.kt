package app.picacgmd.backup

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.stringPreferencesKey
import app.picacgmd.core.data.db.HistoryTombstone
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.settings.AppTheme
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.session.KeystoreCredentialStore
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.core.domain.sync.KeystoreWebDavPasswordStore
import app.picacgmd.core.network.PicaApi
import app.picacgmd.testing.MemoryCredentialStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Storage-phase fixtures directly seed committed inbound snapshots, not a platform restore lifecycle. */
@RunWith(RobolectricTestRunner::class)
class SystemRestoreTest {
    private lateinit var context: Application
    private lateinit var storage: SystemBackupStorage

    @Before
    fun setUp() {
        BackupTestKeyStore.install()
        context = RuntimeEnvironment.getApplication()
        storage = SystemBackupStorage(context)
        resetFiles()
    }

    @After
    fun tearDown() {
        BackupTestKeyStore.remove()
    }

    @Test
    fun backupProducesStandaloneSanitizedSnapshotsWithoutChangingLiveData() {
        BackupDatabaseFixture.create(storage.databaseFile).use { database ->
            assertTrue(database.enableWriteAheadLogging())
            database.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            BackupDatabaseFixture.seed(database)
            assertTrue(File(storage.databaseFile.path + "-wal").length() > 32)
            val library = BackupDatabaseFixture.libraryRows(storage.databaseFile)
            populateBackupSettings(storage.settingsFile)
            storage.prepareBackup()
            assertEquals(library, BackupDatabaseFixture.libraryRows(storage.databaseSnapshot))
            assertEquals(library, BackupDatabaseFixture.libraryRows(storage.databaseFile))
            for (table in BackupDatabaseFixture.deviceTables) {
                assertEquals(0, BackupDatabaseFixture.count(storage.databaseSnapshot, table), table)
                assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, table), table)
            }
            assertFalse(storage.databaseSnapshot.readBytes().toString(Charsets.ISO_8859_1).contains(BackupDatabaseFixture.CACHE_MARKER))
            assertFalse(File(storage.databaseSnapshot.path + "-wal").exists())
            assertFalse(File(storage.databaseSnapshot.path + "-shm").exists())
            val backup = withBackupPreferences(storage.settingsSnapshot) {
                assertNull(it.data.first()[stringPreferencesKey("startup_image_uri")])
                SettingsRepository(it).settings.first()
            }
            val live = withBackupPreferences(storage.settingsFile) {
                assertEquals("content://images/startup", it.data.first()[stringPreferencesKey("startup_image_uri")])
                SettingsRepository(it).settings.first()
            }
            assertNull(backup.authToken)
            assertNull(backup.authTokenUpdatedAt)
            assertEquals("", backup.accountEmail)
            assertEquals("private-token", live.authToken)
            assertEquals(16, backup.downloadThreads)
            assertTrue(backup.appLockEnabled)
        }
    }

    @Test
    fun versionFiveRestoreKeepsTheLibraryCleansDeviceRowsAndResetsOnlyDeviceSettings() {
        val library = stageRestore(version = 5)
        storage.consumeRestore()
        assertCleanRestore(library)
        SQLiteDatabase.openDatabase(storage.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(5, it.version, "Cleanup must not migrate or rewrite the restored schema")
        }
        val database = PicaDatabase.create(context)
        try {
            val history = runBlocking(Dispatchers.IO) { database.viewHistoryDao().get(BackupDatabaseFixture.COMIC) }
            assertEquals(3, history?.lastEpOrder)
            assertEquals(4, history?.lastPictureIndex)
            assertEquals(12, history?.lastChapterPages)
            assertEquals(9_000L, history?.modifiedAt, "The v5 migration initializes sync versions from viewing times")
            assertEquals(emptyList(), runBlocking(Dispatchers.IO) { database.viewHistoryDao().getTombstones() })
        } finally {
            database.close()
        }
        SQLiteDatabase.openDatabase(storage.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(6, it.version) }
    }

    @Test
    fun versionSixRestoreKeepsHistorySyncVersionsAndTombstonesWhileCleaningDownloadsAndCaches() {
        val library = stageRestore()
        assertEquals(BackupDatabaseFixture.libraryTables.toSet(), library.keys)
        storage.consumeRestore()
        assertCleanRestore(library)
        SQLiteDatabase.openDatabase(storage.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(6, it.version) }
        val database = PicaDatabase.create(context)
        try {
            runBlocking(Dispatchers.IO) {
                val history = database.viewHistoryDao().get(BackupDatabaseFixture.COMIC)
                assertEquals(9_000L, history?.updatedAt)
                assertEquals(BackupDatabaseFixture.HISTORY_MODIFIED_AT, history?.modifiedAt)
                assertEquals(
                    listOf(HistoryTombstone(BackupDatabaseFixture.DELETED_COMIC, BackupDatabaseFixture.TOMBSTONE_DELETED_AT)),
                    database.viewHistoryDao().getTombstones(),
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun firstApplicationRestoreHookMakesPortableHintsVisibleBeforeTheContainerStarts() {
        val library = stageRestore()
        SystemBackupRestore.onApplicationCreate(context)
        // These are the first destination-side reads, just as in the later login and reader composables.
        assertCleanRestore(library)
    }

    @Test
    fun everyInterruptedRestorePhaseCanRunAgainWithoutLosingLibraryOrTheLockWarning() {
        for (phase in RestorePhase.entries) {
            resetFiles()
            val library = stageRestore()
            assertFailsWith<InterruptedRestore> {
                storage.consumeRestore { if (it == phase) throw InterruptedRestore() }
            }
            assertTrue(storage.restorePending, phase.name)
            storage = SystemBackupStorage(context)
            storage.consumeRestore()
            assertCleanRestore(library)
        }
    }

    @Test
    fun completedRestoreIsIdempotentAndOrdinaryStartupDoesNotClearNewData() {
        val library = stageRestore()
        storage.consumeRestore()
        storage.consumeRestore()
        assertCleanRestore(library)
        SQLiteDatabase.openDatabase(storage.databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO api_cache (`key`, json, expiresAt, updatedAt) VALUES ('new', '{}', 9999, 1)")
        }
        storage.consumeRestore()
        assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, "api_cache"))
    }

    @Test
    fun oldSchemaIsCleanedWithoutRoomAndThenMigratesNormallyWhenRoomOpens() {
        val library = stageRestore(version = 4)
        storage.consumeRestore()
        assertEquals(library, BackupDatabaseFixture.libraryRows(storage.databaseFile))
        SQLiteDatabase.openDatabase(storage.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(4, it.version) }
        val database = PicaDatabase.create(context)
        try {
            val history = runBlocking(Dispatchers.IO) { database.viewHistoryDao().get(BackupDatabaseFixture.COMIC) }
            assertEquals("恢复的历史", history?.title)
            assertEquals(3, history?.lastEpOrder)
            assertEquals(4, history?.lastPictureIndex)
        } finally {
            database.close()
        }
        SQLiteDatabase.openDatabase(storage.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { assertTrue(it.version > 4) }
    }

    @Test
    fun missingDeviceTablesAreToleratedAndMissingDatabaseIsNotCreated() {
        SystemBackupStorage.clearRestoredDatabase(storage.databaseFile)
        assertFalse(storage.databaseFile.exists())
        storage.databaseFile.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(storage.databaseFile, null).use {
            it.execSQL("CREATE TABLE view_history (comicId TEXT PRIMARY KEY, lastPictureIndex INTEGER)")
            it.execSQL("INSERT INTO view_history VALUES ('kept', 7)")
            it.version = 1
        }
        SystemBackupStorage.clearRestoredDatabase(storage.databaseFile)
        SystemBackupStorage.clearRestoredDatabase(storage.databaseFile)
        SQLiteDatabase.openDatabase(storage.databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertEquals(1, database.version)
            database.rawQuery("SELECT lastPictureIndex FROM view_history", null).use { assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0)) }
        }
    }

    @Test
    fun restoredStartupIsSignedOutWithoutApiContactAndDifferentAccountLoginKeepsLibrary() {
        val library = stageRestore()
        storage.consumeRestore()
        withBackupSettings(storage.settingsFile) { repository ->
            runBlocking(Dispatchers.IO) {
                val signedOut = SessionManager(repository, KeystoreCredentialStore(context), {
                    error("Restored signed-out startup must not contact the API")
                }, this)
                signedOut.awaitReady()
                signedOut.onAppOpen()
                assertEquals(SessionState.SignedOut, signedOut.state.value)
                assertNull(signedOut.token)
                assertNull(KeystoreCredentialStore(context).read())
                MockWebServer().use { server ->
                    server.start()
                    server.enqueue(MockResponse.Builder().body("""{"code":200,"message":"success","data":{"token":"new-token"}}""").build())
                    val client = OkHttpClient()
                    lateinit var session: SessionManager
                    val api = PicaApi(baseUrl = server.url("/").toString(), client = client, tokenProvider = { session.token })
                    session = SessionManager(repository, MemoryCredentialStore(), { api }, this)
                    session.awaitReady()
                    session.onAppOpen()
                    assertEquals(0, server.requestCount)
                    session.login("different@example.com", "test-password", rememberCredentials = false)
                    assertEquals(SessionState.SignedIn("different@example.com"), session.state.value)
                    assertEquals(1, server.requestCount)
                    assertEquals(library, BackupDatabaseFixture.libraryRows(storage.databaseFile))
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test
    fun agentRestoreFinishedWithoutDeliveredFilesDoesNotCommitAMarkerOrOpenStorage() {
        val agent = PicaBackupAgent()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }
            .invoke(agent, context)
        agent.onRestoreFinished()
        assertFalse(SystemBackupStorage(context).restorePending)
        assertFalse(storage.databaseFile.exists())
        assertFalse(storage.settingsFile.exists())
    }

    private fun stageRestore(version: Int = 6): Map<String, List<String?>> {
        BackupPreferenceFiles.stageRestore(context)
        BackupDatabaseFixture.create(storage.restoredDatabaseSnapshot, version).use { BackupDatabaseFixture.seed(it, version) }
        val library = BackupDatabaseFixture.libraryRows(storage.restoredDatabaseSnapshot)
        populateBackupSettings(storage.restoredSettingsSnapshot)
        File(context.noBackupFilesDir, "account_credentials").apply { parentFile!!.mkdirs(); writeText("old-device-ciphertext") }
        storage.markRestorePending()
        return library
    }

    private fun assertCleanRestore(library: Map<String, List<String?>>) {
        assertEquals(library, BackupDatabaseFixture.libraryRows(storage.databaseFile))
        for (table in BackupDatabaseFixture.deviceTables) assertEquals(0, BackupDatabaseFixture.count(storage.databaseFile, table), table)
        val settings = withBackupPreferences(storage.settingsFile) {
            assertNull(it.data.first()[stringPreferencesKey("startup_image_uri")])
            SettingsRepository(it).settings.first()
        }
        assertNull(settings.authToken)
        assertNull(settings.authTokenUpdatedAt)
        assertEquals("", settings.accountEmail)
        assertEquals("203.0.113.1", settings.apiRouteAddress)
        assertEquals(listOf("203.0.113.1"), settings.syncedApiAddresses)
        assertEquals(456L, settings.apiRoutesSyncedAt)
        assertNull(settings.displayModeId)
        assertNull(settings.downloadTreeUri)
        assertEquals(8, settings.timeOffsetHours)
        assertFalse(settings.appLockEnabled)
        assertTrue(settings.biometricUnlockEnabled)
        assertEquals(0, settings.appLockTimeoutSeconds)
        assertTrue(settings.eInkReduceAnimations)
        assertEquals(AppTheme.BOOK, settings.theme)
        assertEquals(listOf("分类二", "分类一"), settings.pinnedHomeCategories)
        assertEquals(16, settings.downloadThreads)
        assertTrue(settings.preventScreenshots)
        assertEquals("https://backup.example.com/dav/", settings.webDav.address)
        assertEquals("sync-reader", settings.webDav.username)
        assertEquals("Library", settings.webDav.folder)
        assertTrue(settings.webDav.autoSync)
        assertFalse(settings.webDav.wifiOnly)
        assertNull(settings.webDav.lastSyncedAt)
        assertNull(runBlocking { KeystoreWebDavPasswordStore(context).read() })
        assertTrue(storage.lockNoticePending)
        assertFalse(File(context.noBackupFilesDir, "account_credentials").exists())
        assertFalse(storage.restorePending)
        assertFalse(storage.snapshotDirectory.exists())
        assertFalse(storage.restoreDirectory.exists())
        assertRestoredPreferences()
    }

    private fun assertRestoredPreferences() {
        assertTrue(context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).getBoolean("notice_accepted", false))
        val overlay = context.getSharedPreferences("reader_tap_overlay", Context.MODE_PRIVATE)
        assertTrue(overlay.getBoolean("ThreeZones", false))
        assertFalse(overlay.getBoolean("Controller", true))
        assertFalse(context.getSharedPreferences("system_permissions", Context.MODE_PRIVATE)
            .getBoolean("notifications_requested", false))
        assertFalse(context.getSharedPreferences("cache_maintenance", Context.MODE_PRIVATE)
            .contains("last_successful_cleanup_millis"))
    }

    private fun resetFiles() {
        context.deleteDatabase(PicaDatabase.DATABASE_NAME)
        storage.settingsFile.parentFile?.deleteRecursively()
        storage.clearSnapshots()
        context.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
        BackupPreferenceFiles.clear(context)
    }

    private class InterruptedRestore : RuntimeException("Simulated restore interruption")
}
