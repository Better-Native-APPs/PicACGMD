package app.picacgmd.backup

import android.app.Application
import android.app.backup.BackupAgent
import android.content.Context
import android.content.ContextWrapper
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.domain.session.CredentialStore
import app.picacgmd.core.domain.session.Credentials
import app.picacgmd.core.domain.session.KeystoreCredentialStore
import app.picacgmd.core.domain.session.SessionManager
import app.picacgmd.core.domain.session.SessionState
import app.picacgmd.core.domain.sync.KeystoreWebDavPasswordStore
import app.picacgmd.core.domain.sync.WebDavPassword
import app.picacgmd.core.network.PicaApi
import app.picacgmd.core.network.UnauthorizedException
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.feature.sync.WebDavAccountDraft
import app.picacgmd.feature.sync.WebDavField
import app.picacgmd.feature.sync.WebDavSyncUiState
import app.picacgmd.feature.sync.message
import app.picacgmd.feature.sync.statusSummary
import app.picacgmd.feature.sync.validateWebDavAccount
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserException

/** Backup copies simulated transport bytes; restore calls the framework in Robolectric, not a real transport lifecycle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemBackupSecretsTest {
    private lateinit var context: Application
    private lateinit var storage: SystemBackupStorage
    private lateinit var credentials: KeystoreCredentialStore
    private lateinit var webDavPasswords: KeystoreWebDavPasswordStore
    private val saved = Credentials("reader@example.com", "backup-test-password")
    private val savedWebDav = WebDavPassword("https://backup.example.com/dav/\nsync-reader", "webdav-backup-test-password")

    @Before
    fun setUp() {
        BackupTestKeyStore.install()
        context = RuntimeEnvironment.getApplication()
        storage = SystemBackupStorage(context)
        credentials = KeystoreCredentialStore(context)
        webDavPasswords = KeystoreWebDavPasswordStore(context)
        resetFiles()
    }

    @After
    fun tearDown() {
        storage.clearSnapshots()
        storage.clearRestoredSnapshots()
        BackupTestKeyStore.remove()
    }

    @Test
    fun encryptedCloudCarriesCredentialsAndTokenAndReencryptsThemOnTheNewDevice() {
        assertProtectedRoundTrip(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED)
    }

    @Test
    fun directDeviceTransferCarriesCredentialsAndTokenWithoutTheEncryptionFlag() {
        assertProtectedRoundTrip(BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER)
    }

    @Test
    fun neitherFlagOrAnUnrelatedFlagProducesNoPayloadAndLeavesTheInstallationUntouched() {
        for (flags in listOf(0, 4)) {
            resetFiles()
            seedLiveData()
            val backup = backup(flags)
            assertTrue(backup.isEmpty())
            assertEquals(saved, runBlocking { credentials.read() }, "Backup must not alter local credentials")
            assertEquals(savedWebDav, runBlocking { webDavPasswords.read() }, "Backup must not alter the local WebDAV password")
            backupRestoreAgent(context).onRestoreFinished()
            storage.consumeRestore()
            assertSignedIn()
            assertEquals(savedWebDav, runBlocking { webDavPasswords.read() })
            assertTrue(withBackupSettings(storage.settingsFile) { it.settings.first().appLockEnabled })
            for (table in BackupDatabaseFixture.deviceTables) assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, table))
            assertFalse(storage.snapshotDirectory.exists())
        }
    }

    @Test
    @Config(sdk = [26, 27])
    fun androidEightProducesAnEmptyBackupEvenWithAnEncryptionFlagAndToleratesAnEmptyRestore() {
        seedLiveData()
        val backup = backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED)
        assertTrue(backup.isEmpty(), "Android 8 must not submit any application payload")
        assertFalse(storage.snapshotDirectory.exists(), "Android 8 must not prepare snapshots")
        backupRestoreAgent(context).onRestoreFinished()
        storage.consumeRestore()
        assertSignedIn()
        assertEquals(savedWebDav, runBlocking { webDavPasswords.read() })
        assertTrue(withBackupSettings(storage.settingsFile) { it.settings.first().appLockEnabled })
    }

    @Test
    @Config(sdk = [26, 27])
    fun androidEightBacksUpNothingBeforeAndAfterTheLegacyParserCachesAnEmptyAllowlist() {
        seedLiveData()
        val preferenceNames = listOf("onboarding", "reader_tap_overlay")
        for (name in preferenceNames) {
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putBoolean("portable-marker", true).commit())
        }
        val fullBackup = Class.forName("android.app.backup.FullBackup")
        val schemeCache = fullBackup.getDeclaredField("kPackageBackupSchemeMap").apply { isAccessible = true }
            .get(null) as MutableMap<*, *>
        schemeCache.clear()
        val guardedAgent = agent()
        assertEmptyAndroidEightBackup(guardedAgent)
        val getScheme = fullBackup.getDeclaredMethod("getBackupScheme", Context::class.java)
            .apply { isAccessible = true }
        val scheme = assertNotNull(getScheme.invoke(null, context))
        val getIncludes = scheme.javaClass.getDeclaredMethod("maybeParseAndGetCanonicalIncludePaths")
            .apply { isAccessible = true }
        val failure = assertFailsWith<InvocationTargetException> {
            getIncludes.invoke(scheme)
        }
        val cause = assertIs<XmlPullParserException>(failure.targetException)
        assertEquals("At most 2 tag attributes allowed for \"include\" tag (\"domain\" & \"path\".", cause.message)
        assertTrue(scheme === getScheme.invoke(null, context), "The scheme is reused for the next backup call")
        val cached = assertIs<Map<*, *>>(getIncludes.invoke(scheme))
        assertTrue(cached.isEmpty(), "The platform treats this cached empty allowlist as backing up the entire domain")
        assertEmptyAndroidEightBackup(guardedAgent)
        for (name in preferenceNames) assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).getBoolean("portable-marker", false))
        assertEquals(saved, runBlocking { credentials.read() })
        assertEquals(savedWebDav, runBlocking { webDavPasswords.read() })
    }

    @Test
    fun unavailableKeystoreKeySkipsAllAuthenticationSilentlyButKeepsTheLibrary() {
        seedLiveData()
        val ciphertext = accountFile().readBytes()
        BackupTestKeyStore.keys.clear()
        val backup = backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED)
        assertFalse(backup.keys.any { it.startsWith("secrets/") })
        assertTrue(backup.containsKey(PicaDatabase.DATABASE_NAME))
        assertFalse(backup.getValue(SystemBackupStorage.SETTINGS_FILE).toString(Charsets.ISO_8859_1).contains("private-token"))
        assertContentEquals(ciphertext, accountFile().readBytes())
        assertEquals("private-token", withBackupSettings(storage.settingsFile) { it.settings.first().authToken })
        stageRestore(backup)
        storage.consumeRestore()
        assertSignedOut()
        assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, "view_history"))
    }

    @Test
    fun unavailableWebDavKeySkipsSecretsSilentlyWithoutChangingEitherLiveStore() {
        seedLiveData()
        val accountCiphertext = accountFile().readBytes()
        val webDavCiphertext = webDavFile().readBytes()
        BackupTestKeyStore.keys.remove("app.picacgmd.webdav.credentials")
        val backup = backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED)
        assertFalse(backup.keys.any { it.startsWith("secrets/") })
        assertTrue(backup.containsKey(PicaDatabase.DATABASE_NAME))
        assertEquals(saved, runBlocking { credentials.read() })
        assertContentEquals(accountCiphertext, accountFile().readBytes())
        assertContentEquals(webDavCiphertext, webDavFile().readBytes())
        assertFalse(storage.snapshotDirectory.exists())
        stageRestore(backup)
        storage.consumeRestore()
        assertSignedOut()
        assertWebDavNeedsPassword()
    }

    @Test
    fun aProtectedTokenIsCarriedWhenTheUserDidNotRememberThePassword() {
        seedLiveData()
        runBlocking { credentials.clear() }
        stageRestore(backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED))
        storage.consumeRestore()
        assertNull(runBlocking { credentials.read() })
        assertSignedIn()
    }

    @Test
    fun aProtectedBackupWithoutTheWebDavStoreKeepsItsSettingsButAsksForAPassword() {
        seedLiveData()
        val backup = backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED).toMutableMap()
        val path = "secrets/secrets.json"
        val payload = Json.decodeFromString<SystemBackupSecrets>(backup.getValue(path).toString(Charsets.UTF_8))
        backup[path] = Json.encodeToString(payload.copy(stores = payload.stores - "webdav_credentials")).toByteArray(Charsets.UTF_8)
        stageRestore(backup)
        storage.consumeRestore()
        assertEquals(saved, runBlocking { credentials.read() })
        assertSignedIn()
        assertWebDavNeedsPassword()
        assertFalse(storage.snapshotDirectory.exists())
    }

    @Test
    fun anotherSecretStoreIsCarriedByTheSameListAndRemovedStoresAreIgnored() {
        seedLiveData()
        var secondSecret: ByteArray? = "second-test-secret".toByteArray()
        val second = SystemBackupSecretStore("second_secret", { secondSecret }, { secondSecret = it }, { secondSecret = null })
        val source = SystemBackupStorage(context, listOf(SystemBackupSecretStore.credentials(credentials), second))
        val backup = backup(BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER, source).toMutableMap()
        val path = "secrets/secrets.json"
        val payload = Json.decodeFromString<SystemBackupSecrets>(backup.getValue(path).toString(Charsets.UTF_8))
        backup[path] = Json.encodeToString(payload.copy(stores = payload.stores + ("removed_store" to byteArrayOf(1))))
            .toByteArray(Charsets.UTF_8)
        stageRestore(backup)
        secondSecret = null
        SystemBackupStorage(context, listOf(SystemBackupSecretStore.credentials(credentials), second)).consumeRestore()
        assertContentEquals("second-test-secret".toByteArray(), assertNotNull(secondSecret))
        assertEquals(saved, runBlocking { credentials.read() })
        assertFalse(File(context.noBackupFilesDir, "removed_store").exists())
        assertFalse(storage.snapshotDirectory.exists())
    }

    @Test
    fun everyInterruptedRestorePhaseDeletesPlaintextAndReplaysWithoutLosingAuthentication() {
        for (phase in RestorePhase.entries) {
            resetFiles()
            seedLiveData()
            stageRestore(backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED))
            assertFailsWith<IOException> {
                storage.consumeRestore { if (it == phase) throw IOException("Fake restore interruption") }
            }
            assertFalse(storage.restoredSecretsSnapshot.parentFile!!.exists(), phase.name)
            assertTrue(storage.restorePending, phase.name)
            storage = SystemBackupStorage(context)
            storage.consumeRestore()
            assertEquals(saved, runBlocking { credentials.read() }, phase.name)
            assertEquals(savedWebDav, runBlocking { webDavPasswords.read() }, phase.name)
            assertSignedIn()
            assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, "view_history"))
            assertTrue(storage.lockNoticePending, phase.name)
            assertFalse(storage.snapshotDirectory.exists())
            assertFalse(storage.restoreDirectory.exists())
        }
    }

    @Test
    fun restorePhasesReportOnlyDistinctInstallAndCleanupStepsInOrder() {
        val expected = listOf(
            "AUTHENTICATION_INSTALLED", "DATABASE_INSTALLED", "DATABASE_CLEANED", "SETTINGS_CLEANED", "SNAPSHOTS_REMOVED",
        )
        assertEquals(expected, RestorePhase.entries.map { it.name }, "Restore phases must not count the database install twice")
        seedLiveData()
        stageRestore(backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED))
        val phases = mutableListOf<String>()
        storage.consumeRestore { phases += it.name }
        assertEquals(expected, phases, "Every restore phase must follow a distinct completed step")
    }

    @Test
    fun failureWhileTheTransportReadsTheSnapshotLeavesNoPlaintextOrChangedLiveData() {
        seedLiveData()
        assertFailsWith<IOException> {
            agent().backupToTransport(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED, storage) {
                assertEquals(saved, credentialsInSnapshot(storage.secretsSnapshot.readBytes()))
                assertEquals(savedWebDav, webDavPasswordInSnapshot(storage.secretsSnapshot.readBytes()))
                throw IOException("Fake transport stopped")
            }
        }
        assertFalse(storage.snapshotDirectory.exists())
        assertEquals(saved, runBlocking { credentials.read() })
        assertEquals(savedWebDav, runBlocking { webDavPasswords.read() })
        assertEquals("private-token", withBackupSettings(storage.settingsFile) { it.settings.first().authToken })
    }

    @Test
    fun failureWhileWritingTheSecretSnapshotLeavesNoPlaintext() {
        seedLiveData()
        val failingStore = SystemBackupSecretStore("write_failure", {
            storage.secretsSnapshot.parentFile!!.apply { parentFile!!.mkdirs(); writeText("not-a-directory") }
            byteArrayOf(1)
        }, {}, {})
        storage = SystemBackupStorage(context, listOf(SystemBackupSecretStore.credentials(credentials), failingStore))
        assertFailsWith<IOException> { backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED) }
        assertFalse(storage.snapshotDirectory.exists())
        assertEquals(saved, runBlocking { credentials.read() })
    }

    @Test
    fun failureWhileReencryptingDeletesPlaintextAndTheNextStartupRestoresTheLibrarySignedOut() {
        seedLiveData()
        stageRestore(backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED))
        val failingCredentials = object : CredentialStore by credentials {
            override suspend fun save(credentials: Credentials) { throw IOException("Fake credential import stopped") }
        }
        val failing = SystemBackupStorage(context, listOf(SystemBackupSecretStore.credentials(failingCredentials)))
        assertFailsWith<IOException> { failing.consumeRestore() }
        assertFalse(storage.restoredSecretsSnapshot.parentFile!!.exists())
        assertTrue(storage.restorePending)
        storage.consumeRestore()
        assertSignedOut()
        assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, "view_history"))
        assertFalse(storage.snapshotDirectory.exists())
    }

    @Test
    fun ordinaryStartupDeletesSnapshotsLeftByAKilledBackupBeforeStorageIsOpened() {
        seedLiveData()
        storage.prepareBackup(includeSecrets = true)
        assertTrue(storage.secretsSnapshot.exists())
        SystemBackupRestore.onApplicationCreate(context)
        assertFalse(storage.snapshotDirectory.exists())
        assertEquals(saved, runBlocking { credentials.read() })
        assertEquals(savedWebDav, runBlocking { webDavPasswords.read() })
        assertEquals("private-token", withBackupSettings(storage.settingsFile) { it.settings.first().authToken })
    }

    @Test
    fun restoredExpiredTokenUsesRealRestoredCredentialsForTheExistingSingleRelogin() {
        seedLiveData()
        stageRestore(backup(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED))
        storage.consumeRestore()
        withRestoredApi { session, server ->
            server.enqueue(MockResponse.Builder().code(401).body("expired").build())
            server.enqueue(success("""{"token":"fresh-token"}"""))
            server.enqueue(success("""{"categories":[]}"""))
            assertTrue(session.withApi { it.categories() }.isEmpty())
            assertEquals("private-token", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).headers["authorization"])
            val login = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/auth/sign-in", login.url.encodedPath)
            assertTrue(assertNotNull(login.body).utf8().contains(saved.password))
            assertEquals("fresh-token", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).headers["authorization"])
            assertEquals(SessionState.SignedIn(saved.email), session.state.value)
            assertEquals("fresh-token", session.token)
        }
    }

    @Test
    fun rejectedAutomaticReloginUsesTheExistingExpiredLoginState() {
        seedLiveData()
        stageRestore(backup(BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER))
        storage.consumeRestore()
        withRestoredApi { session, server ->
            repeat(2) { server.enqueue(MockResponse.Builder().body("""{"code":401,"message":"expired"}""").build()) }
            assertFailsWith<UnauthorizedException> { session.withApi { it.categories() } }
            assertIs<SessionState.Expired>(session.state.value)
            assertNull(session.token)
            assertEquals(2, server.requestCount)
        }
    }

    private fun assertProtectedRoundTrip(flags: Int) {
        seedLiveData()
        val oldCiphertexts = listOf(accountFile(), webDavFile()).associate { it.name to it.readBytes() }
        val oldKeys = BackupTestKeyStore.keys.toMap()
        assertEquals(2, oldKeys.size)
        val backup = backup(flags)
        assertEquals(saved, credentialsInSnapshot(backup.getValue("secrets/secrets.json")))
        assertEquals(savedWebDav, webDavPasswordInSnapshot(backup.getValue("secrets/secrets.json")))
        val payload = Json.decodeFromString<SystemBackupSecrets>(backup.getValue("secrets/secrets.json").toString(Charsets.UTF_8))
        assertEquals(setOf("account_credentials", "webdav_credentials"), payload.stores.keys, "Every audited secret store must be registered")
        assertFalse(backup.getValue(SystemBackupStorage.SETTINGS_FILE).toString(Charsets.ISO_8859_1).contains("private-token"))
        assertFalse(storage.snapshotDirectory.exists())
        stageRestore(backup)
        assertTrue(BackupTestKeyStore.keys.isEmpty(), "The source device's key must not travel")
        storage.consumeRestore()
        assertEquals(saved, runBlocking { credentials.read() })
        assertEquals(savedWebDav, runBlocking { webDavPasswords.read() })
        assertFalse(accountFile().readBytes().toString(Charsets.ISO_8859_1).contains(saved.password))
        assertFalse(webDavFile().readBytes().toString(Charsets.ISO_8859_1).contains(savedWebDav.password))
        for ((name, ciphertext) in oldCiphertexts) {
            assertFalse(ciphertext.contentEquals(File(context.noBackupFilesDir, name).readBytes()), name)
        }
        assertEquals(oldKeys.keys, BackupTestKeyStore.keys.keys)
        for ((alias, key) in oldKeys) assertFalse(key == BackupTestKeyStore.keys[alias], alias)
        assertSignedIn()
        assertWebDavSettings()
        assertEquals(1, BackupDatabaseFixture.count(storage.databaseFile, "view_history"))
        for (table in BackupDatabaseFixture.deviceTables) assertEquals(0, BackupDatabaseFixture.count(storage.databaseFile, table))
        assertFalse(storage.snapshotDirectory.exists())
        assertFalse(storage.restorePending)
        assertFalse(storage.restoreDirectory.exists())
    }

    private fun seedLiveData() {
        populateBackupSettings(storage.settingsFile)
        runBlocking {
            credentials.save(saved)
            webDavPasswords.save(savedWebDav)
        }
        BackupDatabaseFixture.create(storage.databaseFile).use { BackupDatabaseFixture.seed(it) }
    }

    private fun backup(flags: Int?, source: SystemBackupStorage = storage): Map<String, ByteArray> {
        var result = emptyMap<String, ByteArray>()
        agent().backupToTransport(flags, source) {
            result = source.snapshotDirectory.walkTopDown().filter(File::isFile).associate {
                it.relativeTo(source.snapshotDirectory).invariantSeparatorsPath to it.readBytes()
            }
        }
        return result
    }

    private fun stageRestore(backup: Map<String, ByteArray>) {
        resetFiles()
        val restoreAgent = backupRestoreAgent(context)
        for ((name, bytes) in backup) deliverBackupSnapshot(context, restoreAgent, name, bytes)
        restoreAgent.onRestoreFinished()
        for ((name, bytes) in backup) {
            val target = when (name) {
                SystemBackupStorage.SETTINGS_FILE -> storage.restoredSettingsSnapshot
                PicaDatabase.DATABASE_NAME -> storage.restoredDatabaseSnapshot
                "secrets/secrets.json" -> storage.restoredSecretsSnapshot
                else -> continue
            }
            assertTrue(target.isFile, "$name must be delivered into this restore's input directory")
            assertContentEquals(bytes, target.readBytes(), "$name must retain the delivered bytes")
        }
    }

    private fun assertSignedIn() = withBackupSettings(storage.settingsFile) { settings ->
        runBlocking(Dispatchers.IO) {
            val session = SessionManager(settings, credentials, { error("A saved token needs no startup API contact") }, this)
            session.awaitReady()
            session.onAppOpen()
            assertEquals(SessionState.SignedIn(saved.email), session.state.value)
            assertEquals("private-token", session.token)
            assertEquals(123L, settings.settings.first().authTokenUpdatedAt)
        }
    }

    private fun assertSignedOut() = withBackupSettings(storage.settingsFile) { settings ->
        runBlocking(Dispatchers.IO) {
            val session = SessionManager(settings, credentials, { error("Unauthenticated restore must not contact the API") }, this)
            session.awaitReady()
            session.onAppOpen()
            assertEquals(SessionState.SignedOut, session.state.value)
            assertNull(session.token)
            assertEquals("", settings.settings.first().accountEmail)
            assertNull(credentials.read())
            assertNull(webDavPasswords.read())
        }
    }

    private fun withRestoredApi(block: suspend (SessionManager, MockWebServer) -> Unit) =
        withBackupSettings(storage.settingsFile) { settings ->
            MockWebServer().use { server ->
                server.start()
                val client = OkHttpClient()
                try {
                    runBlocking(Dispatchers.IO) {
                        lateinit var session: SessionManager
                        val api = PicaApi(baseUrl = server.url("/").toString(), client = client, tokenProvider = { session.token })
                        session = SessionManager(settings, credentials, { api }, this)
                        session.awaitReady()
                        block(session, server)
                    }
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }

    private fun agent(): PicaBackupAgent = PicaBackupAgent().also {
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }
            .invoke(it, context)
    }

    private fun resetFiles() {
        context.deleteDatabase(PicaDatabase.DATABASE_NAME)
        storage.settingsFile.parentFile?.deleteRecursively()
        storage.clearSnapshots()
        context.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
        BackupTestKeyStore.keys.clear()
    }

    private fun accountFile() = File(context.noBackupFilesDir, "account_credentials")
    private fun webDavFile() = File(context.noBackupFilesDir, "webdav_credentials")

    private fun assertEmptyAndroidEightBackup(guardedAgent: PicaBackupAgent) {
        var platformPassCalled = false
        guardedAgent.backupToTransport(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED, storage) { platformPassCalled = true }
        assertFalse(platformPassCalled, "Android 8 must not hand off any snapshots or XML-included preferences")
        assertFalse(storage.snapshotDirectory.exists(), "Android 8 must not prepare snapshots on any call")
    }

    private fun assertWebDavSettings() = withBackupSettings(storage.settingsFile) { repository ->
        val config = repository.settings.first().webDav
        assertEquals("https://backup.example.com/dav/", config.address)
        assertEquals("sync-reader", config.username)
        assertEquals("Library", config.folder)
        assertTrue(config.autoSync)
        assertFalse(config.wifiOnly)
        assertNull(config.lastSyncedAt)
        config
    }

    private fun assertWebDavNeedsPassword() {
        assertNull(runBlocking { webDavPasswords.read() })
        val config = assertWebDavSettings()
        val draft = WebDavAccountDraft(config.address, config.username, config.folder)
        val state = WebDavSyncUiState(account = draft, savedAccount = draft, ready = true)
        assertEquals("尚未设置", state.statusSummary(0).headline)
        assertEquals(mapOf(WebDavField.Password to WebDavError.PASSWORD_REQUIRED), validateWebDavAccount(draft, "", passwordRequired = true))
        assertEquals("请输入密码", WebDavError.PASSWORD_REQUIRED.message())
    }

    private fun credentialsInSnapshot(bytes: ByteArray): Credentials {
        val payload = Json.decodeFromString<SystemBackupSecrets>(bytes.toString(Charsets.UTF_8))
        return Json.decodeFromString(payload.stores.getValue("account_credentials").toString(Charsets.UTF_8))
    }

    private fun webDavPasswordInSnapshot(bytes: ByteArray): WebDavPassword {
        val payload = Json.decodeFromString<SystemBackupSecrets>(bytes.toString(Charsets.UTF_8))
        return Json.decodeFromString(payload.stores.getValue("webdav_credentials").toString(Charsets.UTF_8))
    }
    private fun success(data: String) = MockResponse.Builder().body("""{"code":200,"data":$data}""").build()
}
