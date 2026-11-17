package app.picacgmd.backup

import android.annotation.TargetApi
import android.os.Build
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

@TargetApi(Build.VERSION_CODES.P)
class BackupRulesTest {
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val snapshots = setOf(
        Entry("file", "system-backup/app_settings.preferences_pb"),
        Entry("file", "system-backup/pikapika.db"),
        Entry("file", "system-backup/secrets"),
    )
    private val preferencePolicies = mapOf(
        "onboarding" to PreferencePolicy(
            PreferenceRetention.CARRY, "Usage notice acceptance is portable, not an installation-bound confirmation.",
        ),
        "reader_tap_overlay" to PreferencePolicy(
            PreferenceRetention.CARRY, "Already-seen reading hints should not repeat after a restore.",
        ),
        "system_permissions" to PreferencePolicy(
            PreferenceRetention.DEVICE_BOUND, "Notification permission flow state is bound to the current device.",
        ),
        "cache_maintenance" to PreferencePolicy(
            PreferenceRetention.CACHE_METADATA, "Cleanup timestamps describe caches that are not backed up.",
        ),
    )
    private val includedLocations = snapshots + preferencePolicies.filterValues {
        it.retention == PreferenceRetention.CARRY
    }.keys.map { Entry("sharedpref", "$it.xml") }
    private val sourceFiles by lazy {
        listOf("app", "core", "feature").flatMap { module ->
            File(root, module).walkTopDown().filter {
                it.isFile && it.extension in setOf("kt", "java") && "/src/main/" in it.invariantSeparatorsPath
            }.toList()
        }
    }

    @Test
    fun bothRuleFormatsOnlyIncludeTheSnapshotsAndCarriedPreferences() {
        val modern = parse("app/src/main/res/xml/data_extraction_rules.xml")
        val cloud = modern.getElementsByTagName("cloud-backup").item(0) as Element
        val transfer = modern.getElementsByTagName("device-transfer").item(0) as Element
        val legacy = parse("app/src/main/res/xml/backup_rules.xml")
        assertEquals("true", cloud.getAttribute("disableIfNoEncryptionCapabilities"))
        assertFalse(transfer.hasAttribute("disableIfNoEncryptionCapabilities"))
        for (section in listOf(cloud, transfer, legacy)) {
            assertEquals(includedLocations, entries(section, "include").map { it.copy(flags = "") }.toSet())
            for (excluded in listOf(
                Entry("file", "datastore/app_settings.preferences_pb"), Entry("file", "downloads/comic/page"),
                Entry("database", "pikapika.db"), Entry("database", "pikapika.db-wal"), Entry("database", "pikapika.db-shm"),
                Entry("database", "androidx.work.workdb"), Entry("sharedpref", "system_permissions.xml"),
                Entry("sharedpref", "cache_maintenance.xml"), Entry("sharedpref", "an_unreviewed_future_store.xml"),
                Entry("root", "cache/coil_images/page"), Entry("root", "code_cache/code"),
                Entry("root", "no_backup/account_credentials"), Entry("root", "no_backup/webdav_credentials"),
                Entry("root", "no_backup/system_restore_pending"),
                Entry("root", "no_backup/system-restore/app_settings.preferences_pb"),
                Entry("root", "no_backup/system-restore/pikapika.db"), Entry("root", "no_backup/system-restore/secrets/secrets.json"),
                Entry("external", "downloads/comic/page"), Entry("file", "an_unreviewed_future_location/file"),
                Entry("file", "system-backup/pikapika.db-wal"), Entry("file", "system-backup/pikapika.db-shm"),
            )) assertFalse(eligible(section, excluded, "clientSideEncryption"), excluded.toString())
        }
        assertEquals(entries(cloud, "exclude"), entries(transfer, "exclude"))
        assertEquals(entries(cloud, "exclude"), entries(legacy, "exclude"))
        // noBackupFilesDir, cacheDir and codeCacheDir are also unconditionally excluded by Android.
        val credentials = File(root, "core/domain/src/main/kotlin/app/picacgmd/core/domain/session/CredentialStore.kt").readText()
        val webDav = File(root, "core/domain/src/main/kotlin/app/picacgmd/core/domain/sync/WebDavPasswordStore.kt").readText()
        assertTrue(credentials.contains("KeystoreEncryptedFile(context, \"account_credentials\""))
        assertTrue(webDav.contains("KeystoreEncryptedFile(context, \"webdav_credentials\""))
        assertTrue(credentials.contains("AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))"))
    }

    @Test
    fun legacyRulesRequireEncryptionOrDirectTransferAndNeverRequireBoth() {
        val legacy = parse("app/src/main/res/xml/backup_rules.xml")
        assertEquals(includedLocations.size * 2, entries(legacy, "include").size)
        for (location in includedLocations) {
            assertEquals(
                setOf("clientSideEncryption", "deviceToDeviceTransfer"),
                entries(legacy, "include").filter { it.copy(flags = "") == location }.map { it.flags }.toSet(),
                "Every included location must have exactly the same protected transport conditions",
            )
            assertFalse(eligible(legacy, location, ""))
            assertTrue(eligible(legacy, location, "clientSideEncryption"))
            assertTrue(eligible(legacy, location, "deviceToDeviceTransfer"))
        }
        assertFalse(PicaBackupAgent.allowsTransport(0))
        assertTrue(PicaBackupAgent.allowsTransport(1))
        assertTrue(PicaBackupAgent.allowsTransport(2))
        assertTrue(PicaBackupAgent.allowsTransport(3))
        assertFalse(PicaBackupAgent.allowsTransport(4))
    }

    @Test
    fun manifestEnablesFullBackupWithTheAgentAndKeepsNewerVersionRestoreDisabled() {
        val manifest = parse("app/src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("true", application.getAttributeNS(android, "allowBackup"))
        assertEquals("true", application.getAttributeNS(android, "fullBackupOnly"))
        assertEquals(".backup.PicaBackupAgent", application.getAttributeNS(android, "backupAgent"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(android, "dataExtractionRules"))
        assertFalse(application.hasAttributeNS(android, "restoreAnyVersion"))
        assertFalse(application.getAttributeNS("http://schemas.android.com/tools", "ignore").contains("DataExtractionRules"))
        assertTrue(File(root, "gradle/libs.versions.toml").readText().contains("minSdk = \"26\""))
        val startup = File(root, "app/src/main/kotlin/app/picacgmd/PicaApplication.kt").readText()
        val restoreHook = startup.indexOf("SystemBackupRestore.onApplicationCreate(this)")
        assertTrue(restoreHook >= 0, "The pre-container restore hook must exist")
        val containerStart = startup.indexOf("appContainer = AppContainer(")
        assertTrue(containerStart >= 0, "Application startup must construct the container")
        assertTrue(restoreHook < containerStart)
    }

    @Test
    fun manifestPinsRestrictedBackupEvenWhenAModernTransportRequestsTheNormalApplication() {
        // This checks the platform contract in the manifest, not a running Android backup process.
        val manifest = parse("app/src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        val properties = application.childNodes
        val restrictedMode = (0 until properties.length).mapNotNull { properties.item(it) as? Element }
            .filter { it.tagName == "property" }
            .singleOrNull {
                it.getAttributeNS(android, "name") == "android.app.backup.PROPERTY_USE_RESTRICTED_BACKUP_MODE"
            }
        assertEquals("true", restrictedMode?.getAttributeNS(android, "value"))
    }

    @Test
    fun manifestPreservesStagedAndLiveDataWhenThePlatformRestoreFails() {
        // The declaration keeps platform failure cleanup from deleting data owned by the staging agent.
        val manifest = parse("app/src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "false", application.getAttributeNS("http://schemas.android.com/apk/res/android", "allowClearUserDataOnFailedRestore"),
            "A failed platform restore must not clear an existing installation or a committed restore",
        )
    }

    @Test
    fun everySharedPreferencesStoreHasAPolicyAndReason() {
        for ((name, policy) in preferencePolicies) {
            assertTrue(policy.reason.isNotBlank(), "$name needs a reason for its backup policy")
        }
        val sharedPreferences = Regex("\\bgetSharedPreferences\\s*\\(\\s*\"([^\"]+)\"")
        assertEquals(
            preferencePolicies.keys,
            sourceFiles.flatMap { sharedPreferences.findAll(it.readText()).map { match -> match.groupValues[1] }.toList() }.toSet(),
            "Add each new SharedPreferences store to BackupRulesTest.preferencePolicies with a policy and reason. " +
                "For CARRY, add its exact sharedpref XML path under the existing transport conditions in both rule " +
                "formats and cover first-start restore/replay; DEVICE_BOUND and CACHE_METADATA stores stay excluded. " +
                "Remove obsolete policies when stores are deleted. Unknown stores are not backed up or restored.",
        )
    }

    @Test
    fun newNamedStorageWritersRequireAnExplicitBackupDecision() {
        val dataStores = Regex("preferencesDataStore\\(name\\s*=\\s*\"([^\"]+)\"")
        assertEquals(
            setOf("app_settings"),
            sourceFiles.flatMap { dataStores.findAll(it.readText()).map { match -> match.groupValues[1] }.toList() }.toSet(),
            "Audit new DataStore files, add only eligible snapshots to both XML rule formats and update this inventory. " +
                "Unknown stores are excluded from backup and are not restored.",
        )
        assertEquals(
            1, sourceFiles.sumOf { Regex("Room\\.databaseBuilder\\(").findAll(it.readText()).count() },
            "Audit a new Room database's library/download/cache tables, add a consistent cleaned snapshot and update " +
                "both XML rule formats and this inventory. Unknown databases are not restored.",
        )
        val encryptedFiles = sourceFiles.flatMap { file ->
            Regex("KeystoreEncryptedFile\\s*\\([^)]*\\)").findAll(file.readText()).map {
                "${file.relativeTo(root).invariantSeparatorsPath}:${it.value.replace(Regex("\\s+"), " ")}"
            }.toList()
        }.toSet()
        assertEquals(
            setOf(
                "core/domain/src/main/kotlin/app/picacgmd/core/domain/session/CredentialStore.kt:" +
                    "KeystoreEncryptedFile(context, \"account_credentials\", \"app.picacgmd.account.credentials\")",
                "core/domain/src/main/kotlin/app/picacgmd/core/domain/session/CredentialStore.kt:" +
                    "KeystoreEncryptedFile(context: Context, fileName: String, private val keyAlias: String)",
                "core/domain/src/main/kotlin/app/picacgmd/core/domain/sync/WebDavPasswordStore.kt:" +
                    "KeystoreEncryptedFile(context, \"webdav_credentials\", \"app.picacgmd.webdav.credentials\")",
            ), encryptedFiles,
            "For each new encrypted file, add one SystemBackupSecretStore(name, read, save, clear) entry to " +
                "systemBackupSecretStores(context), audit its storage location and update this encrypted-file inventory. " +
                "Unknown secret stores are not backed up or restored; do not add their ciphertext to XML rules.",
        )
        val fileRoots = Regex("File\\([^,\\n]*\\b(filesDir|cacheDir|noBackupFilesDir)\\s*,\\s*\"([^\"$]+)\"")
        val locations = sourceFiles.flatMap { file ->
            fileRoots.findAll(file.readText()).map {
                "${file.relativeTo(root).invariantSeparatorsPath}:${it.groupValues[1]}:${it.groupValues[2]}"
            }.toList()
        }.toSet()
        assertEquals(
            setOf(
                "core/domain/src/main/kotlin/app/picacgmd/core/domain/download/DownloadManager.kt:filesDir:downloads",
                "core/domain/src/main/kotlin/app/picacgmd/core/domain/download/DownloadManager.kt:cacheDir:download-staging",
                "app/src/main/kotlin/app/picacgmd/backup/SystemBackupStorage.kt:noBackupFilesDir:system_restore_pending",
                "app/src/main/kotlin/app/picacgmd/backup/SystemBackupStorage.kt:noBackupFilesDir:system_restore_lock_notice",
            ), locations, "Audit each new file location; register encrypted secrets in systemBackupSecretStores(context) " +
                "or add an eligible sanitized snapshot to both XML rule formats, then update this inventory. " +
                "Unknown locations are excluded from backup and are not restored.",
        )
    }

    private fun parse(path: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(root, path)).documentElement

    private fun entries(section: Element, tag: String): Set<Entry> {
        val nodes = section.getElementsByTagName(tag)
        return (0 until nodes.length).map { index ->
            val node = nodes.item(index) as Element
            Entry(node.getAttribute("domain"), node.getAttribute("path"), node.getAttribute("requireFlags"))
        }.toSet()
    }

    private fun eligible(section: Element, location: Entry, transport: String): Boolean =
        entries(section, "include").any {
            it.domain == location.domain && under(location.path, it.path) && (it.flags.isEmpty() || it.flags == transport)
        } && entries(section, "exclude").none { it.domain == location.domain && under(location.path, it.path) }

    private fun under(path: String, parent: String): Boolean = parent == "." || path == parent || path.startsWith("$parent/")
    private data class Entry(val domain: String, val path: String, val flags: String = "")
    private data class PreferencePolicy(val retention: PreferenceRetention, val reason: String)
    private enum class PreferenceRetention { CARRY, DEVICE_BOUND, CACHE_METADATA }
}
