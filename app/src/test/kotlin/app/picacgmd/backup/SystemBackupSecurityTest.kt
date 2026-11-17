package app.picacgmd.backup

import android.app.Application
import android.app.backup.BackupAgent
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.settings.AppTheme
import app.picacgmd.core.domain.session.CredentialStore
import app.picacgmd.core.domain.session.Credentials
import app.picacgmd.core.domain.session.KeystoreCredentialStore
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.core.domain.sync.KeystoreWebDavPasswordStore
import app.picacgmd.core.domain.sync.WebDavPassword
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Backup hand-off and transport bytes are simulated. Restore uses framework onRestoreFile in Robolectric,
 * not a real transport/Binder session. Interrupted processes are represented by on-disk files and fresh instances.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29])
class SystemBackupSecurityTest {
    private lateinit var context: Application
    private lateinit var storage: SystemBackupStorage
    private lateinit var credentials: KeystoreCredentialStore
    private lateinit var webDavPasswords: KeystoreWebDavPasswordStore
    private val accountA = Credentials("account-a@example.com", "account-a-password")
    private val webDavA = WebDavPassword("https://backup.example.com/dav/\naccount-a", "webdav-a-password")

    @Before
    fun setUp() {
        BackupTestKeyStore.install()
        context = RuntimeEnvironment.getApplication()
        storage = SystemBackupStorage(context)
        credentials = KeystoreCredentialStore(context)
        webDavPasswords = KeystoreWebDavPasswordStore(context)
        clearLiveFiles()
        storage.clearSnapshots()
    }

    @After
    fun tearDown() {
        clearLiveFiles()
        storage.clearSnapshots()
        BackupTestKeyStore.remove()
    }

    @Test
    @Config(sdk = [26, 27, 28, 29, 36, 37])
    fun unprotectedOrUnknownFlagsNeverPrepareSnapshotsOrCallThePlatformOnAnyApi() {
        seedLiveData()
        val originalSettings = storage.settingsFile.readBytes()
        val originalLibrary = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        val agent = backupRestoreAgent(context)
        for (flags in listOf(0, 4, null)) {
            var platformCalled = false
            agent.backupToTransport(flags, storage) { platformCalled = true }
            assertFalse(platformCalled, "flags=$flags must not enter the platform default whole-domain pass")
            assertFalse(storage.snapshotDirectory.exists(), "flags=$flags must not prepare snapshots")
            assertContentEquals(originalSettings, storage.settingsFile.readBytes())
            assertEquals(originalLibrary, BackupDatabaseFixture.libraryRows(storage.databaseFile))
            assertEquals(accountA, runBlocking { credentials.read() })
            assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        }
    }

    @Test
    fun restoreFinishedWithoutDeliveredSnapshotsLeavesSignedInLockedInstallationUntouched() {
        seedLiveData()
        val originalSettings = storage.settingsFile.readBytes()
        val originalLibrary = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        backupRestoreAgent(context).onRestoreFinished()
        SystemBackupStorage(context).consumeRestore()
        assertContentEquals(originalSettings, storage.settingsFile.readBytes())
        assertEquals(accountA, runBlocking { credentials.read() })
        assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        assertEquals(originalLibrary, BackupDatabaseFixture.libraryRows(storage.databaseFile))
        for (table in BackupDatabaseFixture.deviceTables) assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, table))
        assertFalse(storage.restorePending)
        assertFalse(storage.lockNoticePending)
    }

    @Test
    fun invalidDeliveredSnapshotsLeaveTheExistingInstallationUntouched() {
        seedLiveData()
        val originalSettings = storage.settingsFile.readBytes()
        val agent = backupRestoreAgent(context)
        deliverBackupSnapshot(context, agent, SystemBackupStorage.SETTINGS_FILE, byteArrayOf(0x0a, 0x7f))
        deliverBackupSnapshot(context, agent, PicaDatabase.DATABASE_NAME, "not-a-database".toByteArray())
        deliverBackupSnapshot(context, agent, "secrets/secrets.json", "not-json".toByteArray())
        agent.onRestoreFinished()
        SystemBackupStorage(context).consumeRestore()
        assertContentEquals(originalSettings, storage.settingsFile.readBytes())
        assertEquals(accountA, runBlocking { credentials.read() })
        assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        for (table in BackupDatabaseFixture.deviceTables) assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, table))
        assertFalse(storage.restorePending)
        assertFalse(storage.lockNoticePending)
    }

    @Test
    fun wrongTypeDeliveredSettingsAreRejectedWithoutChangingTheExistingInstallation() {
        seedLiveData()
        val originalSettings = storage.settingsFile.readBytes()
        val originalLibrary = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        val agent = backupRestoreAgent(context)
        val failure = runCatching {
            deliverBackupSnapshot(context, agent, SystemBackupStorage.SETTINGS_FILE, wrongTypeSettingsBytes())
        }.exceptionOrNull()
        assertNull(failure, "Unreadable delivered settings must be rejected without throwing")
        agent.onRestoreFinished()
        SystemBackupStorage(context).consumeRestore()
        assertContentEquals(originalSettings, storage.settingsFile.readBytes())
        assertEquals(originalLibrary, BackupDatabaseFixture.libraryRows(storage.databaseFile))
        assertEquals(accountA, runBlocking { credentials.read() })
        assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        for (table in BackupDatabaseFixture.deviceTables) assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, table))
        assertFalse(storage.restorePending)
        assertFalse(storage.lockNoticePending)
        assertFalse(storage.restoreDirectory.exists())
        assertFalse(storage.snapshotDirectory.exists())
    }

    @Test
    fun preferenceOnlySessionFinishesAnOlderRestoreCommittedAtMarkerOne() {
        val pending = stageCommittedRestore(markerState = 1)
        val agent = backupRestoreAgent(context)
        deliverCarriedPreference(agent)
        agent.onRestoreFinished()
        assertFinishedRestore(pending.payload, pending.previousLibrary, includeSecrets = true)
    }

    @Test
    fun preferenceOnlySessionFinishesAnOlderRestoreKilledAtMarkerTwoWithoutSigningItOut() {
        val pending = stageCommittedRestore(markerState = 2)
        val agent = backupRestoreAgent(context)
        deliverCarriedPreference(agent)
        agent.onRestoreFinished()
        assertFinishedRestore(pending.payload, pending.previousLibrary, includeSecrets = true)
    }

    @Test
    fun preferenceOnlySessionFinishesAnOlderRestoreKilledAtMarkerThree() {
        val pending = stageCommittedRestore(markerState = 3)
        val agent = backupRestoreAgent(context)
        deliverCarriedPreference(agent)
        agent.onRestoreFinished()
        assertFinishedRestore(pending.payload, pending.previousLibrary, includeSecrets = false)
    }

    @Test
    fun rejectedSnapshotSessionStillFinishesAnOlderCommittedRestore() {
        val pending = stageCommittedRestore(markerState = 2)
        val agent = backupRestoreAgent(context)
        deliverBackupSnapshot(context, agent, SystemBackupStorage.SETTINGS_FILE, byteArrayOf(0x0a, 0x7f))
        agent.onRestoreFinished()
        assertFinishedRestore(pending.payload, pending.previousLibrary, includeSecrets = true)
    }

    @Test
    fun failedOlderRestoreRefusesAndDrainsEveryFileOfTheNewSessionUntilFinished() {
        val backupB = createRestorePayload("restore-b", AppTheme.GREY_BLUE, largeLibrary = true)
        val pending = stageCommittedRestore(markerState = 2)
        val agent = backupRestoreAgent(context)
        assertRefusedSession(agent, pending, backupB)
        agent.onRestoreFinished()
        assertContentEquals(byteArrayOf(2), restoreMarkerFile().readBytes())
        assertFinishedRestore(pending.payload, pending.previousLibrary, includeSecrets = true)
    }

    @Test
    fun validSecretsFreeSessionReplacesAnOlderCommittedRestoreAndEndsSignedOut() {
        val backupB = createRestorePayload("restore-b", AppTheme.GREY_BLUE)
        val pending = stageCommittedRestore(markerState = 2)
        val agent = backupRestoreAgent(context)
        for ((name, bytes) in backupB.files.filterKeys { !it.startsWith("secrets/") }) {
            deliverBackupSnapshot(context, agent, name, bytes)
        }
        agent.onRestoreFinished()
        assertContentEquals(byteArrayOf(1), restoreMarkerFile().readBytes())
        assertContentEquals(backupB.files.getValue(PicaDatabase.DATABASE_NAME), storage.restoredDatabaseSnapshot.readBytes())
        assertContentEquals(backupB.files.getValue(SystemBackupStorage.SETTINGS_FILE), storage.restoredSettingsSnapshot.readBytes())
        assertFalse(storage.restoredSecretsSnapshot.exists())
        assertFinishedRestore(backupB, pending.previousLibrary, includeSecrets = false)
    }

    @Test
    fun restoreFinishedLetsTheSameAgentAcceptAValidSessionAfterARefusedOne() {
        val backupB = createRestorePayload("restore-b", AppTheme.GREY_BLUE, largeLibrary = true)
        val pending = stageCommittedRestore(markerState = 2)
        val agent = backupRestoreAgent(context)
        assertRefusedSession(agent, pending, backupB)
        agent.onRestoreFinished()
        for ((name, bytes) in backupB.files) deliverBackupSnapshot(context, agent, name, bytes)
        agent.onRestoreFinished()
        assertContentEquals(byteArrayOf(1), restoreMarkerFile().readBytes())
        assertContentEquals(backupB.files.getValue(PicaDatabase.DATABASE_NAME), storage.restoredDatabaseSnapshot.readBytes())
        assertFinishedRestore(backupB, pending.previousLibrary, includeSecrets = true)
    }

    @Test
    fun restoreFinishedFailureStillResetsTheSameAgentForAnotherSession() {
        val payload = createRestorePayload("restore-b", AppTheme.GREY_BLUE)
        val agent = backupRestoreAgent(context)
        deliverBackupSnapshot(context, agent, SystemBackupStorage.SETTINGS_FILE, payload.files.getValue(SystemBackupStorage.SETTINGS_FILE))
        val blockedMarkerWrite = File(restoreMarkerFile().path + ".new")
        assertTrue(blockedMarkerWrite.mkdir())
        File(blockedMarkerWrite, "blocker").writeText("temporary write obstruction")
        assertFailsWith<IOException> { agent.onRestoreFinished() }
        assertFalse(restoreMarkerFile().exists())
        assertTrue(blockedMarkerWrite.deleteRecursively())
        for ((name, bytes) in payload.files) deliverBackupSnapshot(context, agent, name, bytes)
        agent.onRestoreFinished()
        assertContentEquals(byteArrayOf(1), restoreMarkerFile().readBytes())
        assertContentEquals(payload.files.getValue(PicaDatabase.DATABASE_NAME), storage.restoredDatabaseSnapshot.readBytes())
        assertFinishedRestore(payload, includeSecrets = true)
    }

    @Test
    fun rejectedSettingsWithValidDatabaseAndSecretsKeepPortableSettingsButResetDeviceKeysAndInstallAuthentication() {
        val payload = createRestorePayload("restore-b", AppTheme.GREY_BLUE)
        seedLiveData()
        val previousSettings = withBackupSettings(storage.settingsFile) { it.settings.first() }
        val agent = backupRestoreAgent(context)
        deliverBackupSnapshot(context, agent, SystemBackupStorage.SETTINGS_FILE, byteArrayOf(0x0a, 0x7f))
        deliverBackupSnapshot(context, agent, PicaDatabase.DATABASE_NAME, payload.files.getValue(PicaDatabase.DATABASE_NAME))
        deliverBackupSnapshot(context, agent, "secrets/secrets.json", payload.files.getValue("secrets/secrets.json"))
        agent.onRestoreFinished()
        assertFalse(storage.restoredSettingsSnapshot.exists())
        SystemBackupStorage(context).consumeRestore()
        val restoredSettings = withBackupSettings(storage.settingsFile) { it.settings.first() }
        assertEquals(
            previousSettings.copy(
                accountEmail = payload.account.email,
                authToken = payload.token,
                authTokenUpdatedAt = 123L,
                displayModeId = null,
                startupImageUri = null,
                downloadTreeUri = null,
                appLockEnabled = false,
                biometricUnlockEnabled = true,
                appLockTimeoutSeconds = 0,
                webDav = previousSettings.webDav.copy(lastSyncedAt = null),
            ),
            restoredSettings,
        )
        assertEquals(payload.library, BackupDatabaseFixture.libraryRows(storage.databaseFile))
        assertEquals(payload.account, runBlocking { KeystoreCredentialStore(context).read() })
        assertEquals(payload.webDavPassword, runBlocking { KeystoreWebDavPasswordStore(context).read() })
        assertTrue(storage.lockNoticePending)
        assertFalse(storage.restorePending)
        assertFalse(storage.restoreDirectory.exists())
    }

    @Test
    fun staleSecretsFromAKilledBackupCannotBecomeAuthenticationForASecretsFreeRestore() {
        seedLiveData()
        storage.prepareBackup(includeSecrets = true)
        assertTrue(storage.secretsSnapshot.isFile)
        // A dies after writing secrets. B's simulated transport delivers portable files, never A's secrets.
        val backupB = snapshotBytes().filterKeys { !it.startsWith("secrets/") }
        val agent = backupRestoreAgent(context)
        for ((name, bytes) in backupB) deliverBackupSnapshot(context, agent, name, bytes)
        agent.onRestoreFinished()
        SystemBackupStorage(context).consumeRestore()
        assertNull(runBlocking { credentials.read() })
        assertNull(runBlocking { webDavPasswords.read() })
        assertNull(withBackupSettings(storage.settingsFile) { it.settings.first().authToken })
        assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, "view_history"))
        assertFalse(storage.snapshotDirectory.exists())
        assertFalse(storage.restorePending)
    }

    @Test
    fun aNewRestoreSessionDiscardsSecretsDeliveredByAnUnfinishedPreviousSession() {
        seedLiveData()
        storage.prepareBackup(includeSecrets = true)
        val backupA = snapshotBytes()
        storage.clearSnapshots()
        clearLiveFiles()
        val killedAgent = backupRestoreAgent(context)
        deliverBackupSnapshot(context, killedAgent, "secrets/secrets.json", backupA.getValue("secrets/secrets.json"))
        // No onRestoreFinished: discard the agent as if the process died, then start B's restore session.
        val nextAgent = backupRestoreAgent(context)
        for ((name, bytes) in backupA.filterKeys { !it.startsWith("secrets/") }) {
            deliverBackupSnapshot(context, nextAgent, name, bytes)
        }
        nextAgent.onRestoreFinished()
        SystemBackupStorage(context).consumeRestore()
        assertNull(runBlocking { credentials.read() })
        assertNull(runBlocking { webDavPasswords.read() })
        assertNull(withBackupSettings(storage.settingsFile) { it.settings.first().authToken })
        assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, "view_history"))
    }

    @Test
    fun secretsReceivedFirstLeaveNoOutboundDirectoryToBlockLaterPortableFiles() {
        seedLiveData()
        val library = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        storage.prepareBackup(includeSecrets = true)
        val backup = snapshotBytes()
        storage.clearSnapshots()
        clearLiveFiles()
        val agent = backupRestoreAgent(context)
        // Exercise tar order explicitly, not the host directory listing or the platform allowlist's set order.
        deliverBackupSnapshot(context, agent, "secrets/secrets.json", backup.getValue("secrets/secrets.json"))
        assertTrue(storage.restoredSecretsSnapshot.isFile)
        assertFalse(File(storage.snapshotDirectory, SystemBackupStorage.SECRETS_DIRECTORY).exists())
        for (name in listOf(SystemBackupStorage.SETTINGS_FILE, PicaDatabase.DATABASE_NAME)) {
            deliverBackupSnapshot(context, agent, name, backup.getValue(name))
        }
        agent.onRestoreFinished()
        assertContentEquals(backup.getValue(SystemBackupStorage.SETTINGS_FILE), storage.restoredSettingsSnapshot.readBytes())
        val restarted = SystemBackupStorage(context)
        restarted.consumeRestore()
        assertEquals(library, BackupDatabaseFixture.libraryRows(restarted.databaseFile))
        assertEquals("https://backup.example.com/dav/", withBackupSettings(restarted.settingsFile) { it.settings.first().webDav.address })
        assertEquals(accountA, runBlocking { credentials.read() })
        assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        assertTrue(restarted.lockNoticePending)
        assertFalse(restarted.restorePending)
    }

    @Test
    fun pendingRestoreReencryptionFailureDuringBackupKeepsLibraryForTheNextStart() {
        seedLiveData()
        val library = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        stageProtectedRestoreOnANewInstallation()
        val failingCredentials = object : CredentialStore by credentials {
            override suspend fun save(credentials: Credentials) { throw IOException("Temporary credential encryption failure") }
        }
        val failingStorage = SystemBackupStorage(context, listOf(SystemBackupSecretStore.credentials(failingCredentials)))
        var platformCalled = false
        assertFailsWith<IOException> {
            backupRestoreAgent(context).backupToTransport(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED, failingStorage) {
                platformCalled = true
            }
        }
        assertFalse(platformCalled, "A backup must be skipped when the pending restore cannot finish")
        assertTrue(storage.restorePending)
        val restarted = SystemBackupStorage(context)
        restarted.consumeRestore()
        assertTrue(restarted.databaseFile.isFile, "The next start must still have the restored library input")
        assertEquals(library, BackupDatabaseFixture.libraryRows(restarted.databaseFile))
        for (table in BackupDatabaseFixture.deviceTables) assertEquals(0, BackupDatabaseFixture.count(restarted.databaseFile, table))
        assertFalse(restarted.restorePending)
    }

    @Test
    fun bakOnlyPendingMarkerReplaysInsteadOfDiscardingTheRestore() {
        seedLiveData()
        val library = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        stageProtectedRestoreOnANewInstallation()
        val marker = File(context.noBackupFilesDir, "system_restore_pending")
        // API 28/29's old writer can die after base -> .bak and before recreating the base file.
        assertTrue(marker.renameTo(File(marker.path + ".bak")))
        assertFalse(marker.exists())
        val restarted = SystemBackupStorage(context)
        restarted.consumeRestore()
        assertTrue(restarted.databaseFile.isFile, "A .bak-only committed marker must not discard the inbound snapshots")
        assertEquals(library, BackupDatabaseFixture.libraryRows(restarted.databaseFile))
        assertEquals(accountA, runBlocking { credentials.read() })
        assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        assertFalse(File(marker.path + ".bak").exists())
        assertFalse(restarted.restorePending)
    }

    @Test
    fun halfWrittenLegacySettingsAreRecoveredBeforeTheLockNoticeReadsDataStore() {
        seedLiveData()
        storage.prepareBackup(includeSecrets = true)
        val backup = snapshotBytes()
        storage.clearSnapshots()
        val committedSettings = storage.settingsFile.readBytes()
        val agent = backupRestoreAgent(context)
        for ((name, bytes) in backup) deliverBackupSnapshot(context, agent, name, bytes)
        agent.onRestoreFinished()
        // Death inside the old file-copy writer: .bak is good, the live protobuf base is truncated.
        File(storage.settingsFile.path + ".bak").writeBytes(committedSettings)
        storage.settingsFile.writeBytes(byteArrayOf(0x0a, 0x7f))
        val restarted = SystemBackupStorage(context)
        restarted.consumeRestore()
        assertEquals("private-token", withBackupSettings(restarted.settingsFile) { it.settings.first().authToken })
        assertFalse(withBackupSettings(restarted.settingsFile) { it.settings.first().appLockEnabled })
        assertTrue(restarted.lockNoticePending, "The old installation's lock must be remembered before overwriting it")
        assertEquals(accountA, runBlocking { credentials.read() })
        assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        assertFalse(File(restarted.settingsFile.path + ".bak").exists())
        assertFalse(restarted.restorePending)
    }

    @Test
    fun halfWrittenFirstSettingsInstallWithoutABackupIsReplacedByTheValidatedSnapshot() {
        seedLiveData()
        val library = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        stageProtectedRestoreOnANewInstallation()
        // With no pre-existing DataStore, the legacy writer has no .bak when killed inside its first base copy.
        assertFalse(storage.settingsFile.exists())
        storage.settingsFile.parentFile!!.mkdirs()
        storage.settingsFile.writeBytes(byteArrayOf(0x0a, 0x7f))
        assertFalse(File(storage.settingsFile.path + ".bak").exists())
        val restarted = SystemBackupStorage(context)
        restarted.consumeRestore()
        assertEquals("private-token", withBackupSettings(restarted.settingsFile) { it.settings.first().authToken })
        assertEquals(library, BackupDatabaseFixture.libraryRows(restarted.databaseFile))
        assertEquals(accountA, runBlocking { credentials.read() })
        assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        assertTrue(restarted.lockNoticePending)
        assertFalse(restarted.restorePending)
    }

    @Test
    fun interruptedMarkerWriteKeepsItsCommittedStateUntilTheTemporaryFileIsRenamed() {
        seedLiveData()
        stageProtectedRestoreOnANewInstallation()
        val marker = File(context.noBackupFilesDir, "system_restore_pending")
        val committedState = marker.readBytes()
        // Drive the actual AtomicFile selected by production. Close without finish/fail to leave kill-point files.
        val atomic = storage.javaClass.getDeclaredField("restoreMarker").apply { isAccessible = true }.get(storage)
        val output = atomic.javaClass.getMethod("startWrite").invoke(atomic) as FileOutputStream
        output.use { it.write(2); it.fd.sync() }
        assertContentEquals(committedState, marker.readBytes(), "A not-yet-renamed update must not replace the committed base")
        val restarted = SystemBackupStorage(context)
        restarted.consumeRestore()
        assertEquals(accountA, runBlocking { credentials.read() })
        assertEquals(webDavA, runBlocking { webDavPasswords.read() })
        assertEquals("private-token", withBackupSettings(restarted.settingsFile) { it.settings.first().authToken })
        assertFalse(File(marker.path + ".new").exists())
        assertFalse(restarted.restorePending)
    }

    private data class RestorePayload(
        val files: Map<String, ByteArray>,
        val library: Map<String, List<String?>>,
        val label: String,
        val theme: AppTheme,
        val account: Credentials,
        val token: String,
        val webDavPassword: WebDavPassword,
    )

    private data class PendingRestore(
        val payload: RestorePayload,
        val previousLibrary: Map<String, List<String?>>,
    )

    private fun createRestorePayload(label: String, theme: AppTheme, largeLibrary: Boolean = false): RestorePayload {
        clearLiveFiles()
        storage.clearSnapshots()
        seedLiveData()
        val account = Credentials("$label@example.com", "$label-password")
        val webDavPassword = WebDavPassword("https://backup.example.com/dav/\n$label", "$label-webdav-password")
        val token = "$label-token"
        withBackupSettings(storage.settingsFile) {
            it.setTheme(theme)
            it.setPinnedHomeCategories(listOf(label))
            it.setAccountEmail(account.email)
            it.setAuthToken(token, 123L)
        }
        runBlocking {
            credentials.save(account)
            webDavPasswords.save(webDavPassword)
        }
        setLibraryTitle(label + if (largeLibrary) "x".repeat(80 * 1024) else "")
        val library = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        storage.prepareBackup(includeSecrets = true)
        val result = RestorePayload(snapshotBytes(), library, label, theme, account, token, webDavPassword)
        storage.clearSnapshots()
        clearLiveFiles()
        return result
    }

    private fun stageCommittedRestore(markerState: Int): PendingRestore {
        val payload = createRestorePayload("restore-a", AppTheme.BOOK)
        seedLiveData()
        withBackupSettings(storage.settingsFile) {
            it.setTheme(AppTheme.DYNAMIC)
            it.setPinnedHomeCategories(listOf("previous-installation"))
            it.setAuthToken("previous-token", 456L)
        }
        setLibraryTitle("previous-installation")
        val previousLibrary = BackupDatabaseFixture.libraryRows(storage.databaseFile)
        val previousSettings = storage.settingsFile.readBytes()
        assertTrue(payload.library != previousLibrary, "A and the previous installation must have different library rows")
        val agent = backupRestoreAgent(context)
        for ((name, bytes) in payload.files.filterKeys { markerState != 3 || !it.startsWith("secrets/") }) {
            deliverBackupSnapshot(context, agent, name, bytes)
        }
        agent.onRestoreFinished()
        assertContentEquals(byteArrayOf(1), restoreMarkerFile().readBytes())
        if (markerState != 1) {
            val installing = SystemBackupStorage(context)
            assertFailsWith<IOException> {
                installing.consumeRestore { phase ->
                    if (phase == RestorePhase.AUTHENTICATION_INSTALLED) throw IOException("Stop at the completed authentication phase")
                }
            }
            // Secrets are already removed at this phase; the finally leaves exactly the same files as a kill here.
            assertFalse(storage.restoredSecretsSnapshot.parentFile!!.exists())
            val installedSettings = withBackupSettings(storage.settingsFile) { it.settings.first() }
            assertEquals(payload.theme, installedSettings.theme)
            assertEquals(listOf(payload.label), installedSettings.pinnedHomeCategories)
            assertEquals(if (markerState == 2) payload.token else null, installedSettings.authToken)
            assertEquals(if (markerState == 2) payload.account else null, runBlocking { KeystoreCredentialStore(context).read() })
            assertEquals(if (markerState == 2) payload.webDavPassword else null, runBlocking { KeystoreWebDavPasswordStore(context).read() })
        } else {
            assertContentEquals(previousSettings, storage.settingsFile.readBytes())
            assertEquals(accountA, runBlocking { KeystoreCredentialStore(context).read() })
            assertTrue(storage.restoredSecretsSnapshot.isFile)
        }
        assertContentEquals(byteArrayOf(markerState.toByte()), restoreMarkerFile().readBytes())
        assertTrue(storage.restoredSettingsSnapshot.isFile)
        assertContentEquals(payload.files.getValue(PicaDatabase.DATABASE_NAME), storage.restoredDatabaseSnapshot.readBytes())
        assertEquals(previousLibrary, BackupDatabaseFixture.libraryRows(storage.databaseFile), "A's database is not installed at the kill point")
        assertFalse(storage.snapshotDirectory.exists())
        return PendingRestore(payload, previousLibrary)
    }

    private fun assertFinishedRestore(
        payload: RestorePayload,
        previousLibrary: Map<String, List<String?>>? = null,
        includeSecrets: Boolean,
    ) {
        val restarted = SystemBackupStorage(context)
        restarted.consumeRestore()
        if (previousLibrary != null) assertTrue(payload.library != previousLibrary)
        assertEquals(
            payload.library, BackupDatabaseFixture.libraryRows(restarted.databaseFile),
            "Committed ${payload.label} must install its own library instead of the previous installation",
        )
        withBackupSettings(restarted.settingsFile) { repository ->
            val settings = repository.settings.first()
            assertEquals(payload.theme, settings.theme)
            assertEquals(listOf(payload.label), settings.pinnedHomeCategories)
            assertEquals(if (includeSecrets) payload.token else null, settings.authToken)
            assertFalse(settings.appLockEnabled)
            assertNull(settings.downloadTreeUri)
            runBlocking(Dispatchers.IO) {
                val session = SessionManager(
                    repository, KeystoreCredentialStore(context), { error("A restored session must not contact the API") }, this,
                )
                session.awaitReady()
                session.onAppOpen()
                assertEquals(if (includeSecrets) SessionState.SignedIn(payload.account.email) else SessionState.SignedOut, session.state.value)
            }
        }
        assertEquals(if (includeSecrets) payload.account else null, runBlocking { KeystoreCredentialStore(context).read() })
        assertEquals(if (includeSecrets) payload.webDavPassword else null, runBlocking { KeystoreWebDavPasswordStore(context).read() })
        assertFalse(restarted.restorePending)
        assertFalse(restarted.restoreDirectory.exists())
        assertFalse(restarted.snapshotDirectory.exists())
    }

    private fun assertRefusedSession(agent: PicaBackupAgent, pending: PendingRestore, backupB: RestorePayload) {
        val markerBytes = restoreMarkerFile().readBytes()
        val inboundSettings = storage.restoredSettingsSnapshot.readBytes()
        val inboundDatabase = storage.restoredDatabaseSnapshot.readBytes()
        val liveSettings = storage.settingsFile.readBytes()
        val obstruction = File(storage.databaseFile.path + "-journal")
        assertTrue(obstruction.mkdir())
        File(obstruction, "blocker").writeText("temporary journal obstruction")
        assertRestoreFileConsumed(
            agent, storage.settingsSnapshot, backupB.files.getValue(SystemBackupStorage.SETTINGS_FILE),
        )
        // The installation step fails only for the first callback; the rest must not retry the session.
        assertTrue(obstruction.deleteRecursively())
        assertTrue(backupB.files.getValue(PicaDatabase.DATABASE_NAME).size > 64 * 1024, "The refused database must exceed an old platform pipe's capacity")
        assertRestoreFileConsumed(agent, storage.databaseSnapshot, backupB.files.getValue(PicaDatabase.DATABASE_NAME))
        assertRestoreFileConsumed(agent, storage.secretsSnapshot.parentFile!!, byteArrayOf(), BackupAgent.TYPE_DIRECTORY)
        assertRestoreFileConsumed(agent, storage.secretsSnapshot, backupB.files.getValue("secrets/secrets.json"))
        assertTrue(restoreMarkerFile().isFile, "A's committed marker must survive every refused callback")
        assertContentEquals(markerBytes, restoreMarkerFile().readBytes())
        assertContentEquals(inboundSettings, storage.restoredSettingsSnapshot.readBytes(), "B must not replace A's settings input")
        assertContentEquals(inboundDatabase, storage.restoredDatabaseSnapshot.readBytes(), "B must not replace A's database input")
        assertEquals(
            setOf(SystemBackupStorage.SETTINGS_FILE, PicaDatabase.DATABASE_NAME),
            storage.restoreDirectory.walkTopDown().filter(File::isFile).map { it.relativeTo(storage.restoreDirectory).invariantSeparatorsPath }.toSet(),
        )
        assertFalse(storage.snapshotDirectory.exists(), "A refused session must never write outbound snapshots")
        assertContentEquals(liveSettings, storage.settingsFile.readBytes(), "B must not change the live settings")
        assertEquals(pending.previousLibrary, BackupDatabaseFixture.libraryRows(storage.databaseFile), "B must not change the live library")
        assertEquals(pending.payload.account, runBlocking { KeystoreCredentialStore(context).read() })
        assertEquals(pending.payload.webDavPassword, runBlocking { KeystoreWebDavPasswordStore(context).read() })
    }

    private fun assertRestoreFileConsumed(agent: PicaBackupAgent, destination: File, bytes: ByteArray, type: Int = BackupAgent.TYPE_FILE) {
        val transportFile = File.createTempFile("refused-restore-", ".bin", context.cacheDir)
        val followingBytes = byteArrayOf(0x17, 0x2a, 0x5b)
        try {
            transportFile.writeBytes(bytes + followingBytes)
            ParcelFileDescriptor.open(transportFile, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                val failure = runCatching {
                    agent.onRestoreFile(input, bytes.size.toLong(), destination, type, -1L, 0L)
                }.exceptionOrNull()
                assertNull(failure, "Every refused restore callback must return without throwing")
                val reader = FileInputStream(input.fileDescriptor)
                assertEquals(bytes.size.toLong(), reader.channel.position(), "Every restore callback must consume exactly its declared size")
                assertContentEquals(followingBytes, reader.readBytes(), "A callback must leave following file bytes unread")
                assertTrue(input.fileDescriptor.valid(), "The restore descriptor belongs to the caller")
            }
        } finally {
            transportFile.delete()
        }
    }

    private fun deliverCarriedPreference(agent: PicaBackupAgent) {
        val destination = File(context.applicationInfo.dataDir, "shared_prefs/onboarding.xml")
        val bytes = "<?xml version=\"1.0\" encoding=\"utf-8\"?><map><boolean name=\"notice_accepted\" value=\"true\" /></map>".toByteArray()
        assertRestoreFileConsumed(agent, destination, bytes)
        assertContentEquals(bytes, destination.readBytes(), "B must actually deliver the allowed preference file")
    }

    private fun wrongTypeSettingsBytes(): ByteArray {
        val file = File.createTempFile("wrong-type-settings-", ".preferences_pb", context.cacheDir)
        try {
            runBlocking(Dispatchers.IO) {
                val job = SupervisorJob()
                val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
                try {
                    store.edit { it[stringPreferencesKey("app_lock_enabled")] = "not-a-boolean" }
                } finally {
                    job.cancelAndJoin()
                }
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun setLibraryTitle(title: String) {
        SQLiteDatabase.openDatabase(storage.databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE view_history SET title = ?", arrayOf(title))
        }
    }

    private fun restoreMarkerFile() = File(context.noBackupFilesDir, "system_restore_pending")

    private fun seedLiveData() {
        populateBackupSettings(storage.settingsFile)
        runBlocking {
            credentials.save(accountA)
            webDavPasswords.save(webDavA)
        }
        BackupDatabaseFixture.create(storage.databaseFile).use { BackupDatabaseFixture.seed(it) }
    }

    private fun snapshotBytes(): Map<String, ByteArray> = storage.snapshotDirectory.walkTopDown().filter(File::isFile).associate {
        it.relativeTo(storage.snapshotDirectory).invariantSeparatorsPath to it.readBytes()
    }

    private fun stageProtectedRestoreOnANewInstallation() {
        storage.prepareBackup(includeSecrets = true)
        val backup = snapshotBytes()
        storage.clearSnapshots()
        clearLiveFiles()
        val agent = backupRestoreAgent(context)
        for ((name, bytes) in backup) deliverBackupSnapshot(context, agent, name, bytes)
        agent.onRestoreFinished()
        assertTrue(storage.restorePending)
        assertContentEquals(backup.getValue(SystemBackupStorage.SETTINGS_FILE), storage.restoredSettingsSnapshot.readBytes())
        assertContentEquals(backup.getValue(PicaDatabase.DATABASE_NAME), storage.restoredDatabaseSnapshot.readBytes())
        assertContentEquals(backup.getValue("secrets/secrets.json"), storage.restoredSecretsSnapshot.readBytes())
    }

    private fun clearLiveFiles() {
        context.deleteDatabase(PicaDatabase.DATABASE_NAME)
        storage.settingsFile.parentFile?.deleteRecursively()
        context.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
        BackupTestKeyStore.keys.clear()
    }
}
