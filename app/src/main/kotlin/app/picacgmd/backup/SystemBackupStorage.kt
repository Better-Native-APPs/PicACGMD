package app.picacgmd.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import androidx.core.util.AtomicFile
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.settings.SettingsRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Works on files and table names, never on Room's current schema or generated migrations. */
internal class SystemBackupStorage(
    private val context: Context,
    private val secretStores: List<SystemBackupSecretStore> = systemBackupSecretStores(context),
) {
    val snapshotDirectory = File(context.filesDir, SNAPSHOT_DIRECTORY)
    val settingsSnapshot = File(snapshotDirectory, SETTINGS_FILE)
    val databaseSnapshot = File(snapshotDirectory, PicaDatabase.DATABASE_NAME)
    val secretsSnapshot = File(snapshotDirectory, "$SECRETS_DIRECTORY/secrets.json")
    val restoreDirectory = File(context.noBackupFilesDir, RESTORE_DIRECTORY)
    val restoredSettingsSnapshot = File(restoreDirectory, SETTINGS_FILE)
    val restoredDatabaseSnapshot = File(restoreDirectory, PicaDatabase.DATABASE_NAME)
    val restoredSecretsSnapshot = File(restoreDirectory, "$SECRETS_DIRECTORY/secrets.json")
    val settingsFile = File(context.filesDir, "datastore/$SETTINGS_FILE")
    val databaseFile: File = context.getDatabasePath(PicaDatabase.DATABASE_NAME)
    private val restoreMarker = AtomicFile(File(context.noBackupFilesDir, "system_restore_pending"))
    private val lockNotice = AtomicFile(File(context.noBackupFilesDir, "system_restore_lock_notice"))
    private val receivedSnapshots = mutableSetOf<File>()

    val restorePending: Boolean get() = readAtomicState(restoreMarker) != null
    val lockNoticePending: Boolean get() = readAtomicState(lockNotice) != null

    fun markRestorePending() = writeAtomically(restoreMarker) { it.write(RESTORE_PENDING) }

    fun acknowledgeLockNotice() = deleteAtomicFile(lockNotice)

    fun beginRestore() {
        // Finish committed inputs before this session can discard abandoned reception or backup leftovers.
        consumeRestore()
        deleteAtomicFile(restoreMarker)
        clearRestoredSnapshots()
        clearSnapshots()
        receivedSnapshots.clear()
    }

    fun receiveRestoredSnapshot(destination: File, size: Long) {
        val source = destination.canonicalFile
        val target = when (source) {
            settingsSnapshot.canonicalFile -> restoredSettingsSnapshot
            databaseSnapshot.canonicalFile -> restoredDatabaseSnapshot
            secretsSnapshot.canonicalFile -> restoredSecretsSnapshot
            else -> return
        }
        if (!source.isFile || source.length() != size || !validRestoredSnapshot(source)) {
            receivedSnapshots.remove(target)
            deleteAtomicFile(AtomicFile(target))
            removeOutboundSnapshot(source)
            return
        }
        // Keep the XML-approved platform write separate from the durable, device-only restore inputs.
        copyAtomically(source, target)
        removeOutboundSnapshot(source)
        receivedSnapshots += target
    }

    private fun removeOutboundSnapshot(file: File) {
        deleteFile(file)
        if (file == secretsSnapshot.canonicalFile) {
            val directory = file.parentFile!!
            // API 28's platform matcher can stop at the first included directory instead of checking later file entries.
            if (directory.listFiles()?.isEmpty() == true) deleteFile(directory)
        }
    }

    fun finishRestore() {
        try {
            if (receivedSnapshots.isEmpty()) clearRestoredSnapshots() else markRestorePending()
        } finally {
            clearSnapshots()
            receivedSnapshots.clear()
        }
    }

    private fun validRestoredSnapshot(file: File): Boolean = try {
        when (file.name) {
            SETTINGS_FILE -> withSettings(file) { it.settings.first() }
            PicaDatabase.DATABASE_NAME -> SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            else -> secretsJson.decodeFromString<SystemBackupSecrets>(file.readText())
        }
        true
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun prepareBackup(includeSecrets: Boolean = false) {
        consumeRestore()
        clearSnapshots()
        try {
            preparePortableSnapshots()
            if (includeSecrets) prepareSecrets()
        } catch (exception: Exception) {
            clearSnapshots()
            throw exception
        }
    }

    private fun preparePortableSnapshots() {
        if (settingsFile.exists()) {
            copyAtomically(settingsFile, settingsSnapshot)
            withSettings(settingsSnapshot) { it.filterForSystemBackup() }
        }
        if (databaseFile.exists()) {
            // The backup process is quiescent. Checkpoint committed WAL frames before copying the main file.
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                checkpoint(database)
                copyAtomically(databaseFile, databaseSnapshot)
            }
            SQLiteDatabase.openDatabase(databaseSnapshot.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.disableWriteAheadLogging()
                database.rawQuery("PRAGMA journal_mode=DELETE", null).use { result ->
                    if (!result.moveToFirst() || result.getString(0) != "delete") {
                        throw IOException("Backup snapshot could not leave WAL mode")
                    }
                }
                database.rawQuery("PRAGMA secure_delete=ON", null).use { result ->
                    if (!result.moveToFirst() || result.getInt(0) != 1) {
                        throw IOException("Backup snapshot could not enable secure deletion")
                    }
                }
                clearDeviceRows(database)
                // Remove deleted cache/download bytes and free pages, not just their visible rows.
                database.execSQL("VACUUM")
            }
        }
    }

    private fun prepareSecrets() {
        val secrets = try {
            val stores = runBlocking(Dispatchers.IO) {
                secretStores.mapNotNull { store -> store.read()?.let { store.name to it } }.toMap()
            }
            val settings = if (settingsFile.exists()) withSettings(settingsFile) { it.settings.first() } else null
            SystemBackupSecrets(settings?.accountEmail.orEmpty(), settings?.authToken, settings?.authTokenUpdatedAt, stores)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // A missing/unusable Keystore key must not prevent the portable data from being backed up.
            return
        }
        writeAtomically(AtomicFile(secretsSnapshot)) { it.write(Json.encodeToString(secrets).toByteArray(Charsets.UTF_8)) }
    }

    /** The marker is removed last. Every phase is safe to replay after an interrupted startup. */
    fun consumeRestore(afterPhase: (RestorePhase) -> Unit = {}) {
        if (!restorePending) {
            // A killed backup/restore process may have left a plaintext transport snapshot behind.
            clearSnapshots()
            clearRestoredSnapshots()
            return
        }
        clearSnapshots()
        try {
            installRestoredSettingsAndSecrets()
            clearSecretSnapshots()
            afterPhase(RestorePhase.AUTHENTICATION_INSTALLED)
            if (restoredDatabaseSnapshot.exists()) {
                listOf("-wal", "-shm", "-journal").forEach { deleteFile(File(databaseFile.path + it)) }
                copyAtomically(restoredDatabaseSnapshot, databaseFile)
            }
            afterPhase(RestorePhase.DATABASE_INSTALLED)
            if (databaseFile.exists()) clearRestoredDatabase(databaseFile)
            afterPhase(RestorePhase.DATABASE_CLEANED)
            if (settingsFile.exists()) withSettings(settingsFile) {
                it.resetAfterSystemRestore(keepAuthentication = restoredWithSecrets)
            }
            afterPhase(RestorePhase.SETTINGS_CLEANED)
            clearRestoredSnapshots()
            afterPhase(RestorePhase.SNAPSHOTS_REMOVED)
            deleteAtomicFile(restoreMarker)
        } finally {
            clearSecretSnapshots()
        }
    }

    private val restoredWithSecrets: Boolean
        get() = readAtomicState(restoreMarker) == SETTINGS_WITH_SECRETS

    private fun installRestoredSettingsAndSecrets() {
        val state = readAtomicState(restoreMarker)
        if (state == SETTINGS_WITH_SECRETS || state == SETTINGS_WITHOUT_SECRETS) return
        val secrets = if (restoredSecretsSnapshot.exists()) {
            secretsJson.decodeFromString<SystemBackupSecrets>(restoredSecretsSnapshot.readText())
        } else null
        try {
            rememberLockNotice(settingsFile)
        } catch (exception: CorruptionException) {
            // A corrupt live settings base can be replaced when a validated inbound settings snapshot is available.
            if (!restoredSettingsSnapshot.isFile) throw exception
        }
        if (restoredSettingsSnapshot.exists()) {
            // Treat an old or unreviewed settings snapshot as unauthenticated until its secret payload is installed.
            withSettings(restoredSettingsSnapshot) { it.filterForSystemBackup() }
            copyAtomically(restoredSettingsSnapshot, settingsFile)
        }
        rememberLockNotice(settingsFile)
        if (settingsFile.exists()) withSettings(settingsFile) { it.resetAfterSystemRestore() }
        runBlocking(Dispatchers.IO) {
            for (store in secretStores) {
                store.clear()
                // Removed or unregistered stores are ignored, not interpreted or recreated.
                secrets?.stores?.get(store.name)?.let { store.save(it) }
            }
        }
        if (secrets != null) withSettings(settingsFile) {
            it.restoreSystemBackupAuthentication(secrets.accountEmail, secrets.authToken, secrets.authTokenUpdatedAt)
        }
        // Persist only the completion state, never plaintext. Replay must not overwrite installed authentication.
        writeAtomically(restoreMarker) { it.write(if (secrets == null) SETTINGS_WITHOUT_SECRETS else SETTINGS_WITH_SECRETS) }
    }

    fun clearSnapshots() {
        if (snapshotDirectory.exists() && !snapshotDirectory.deleteRecursively()) {
            throw IOException("Could not remove system backup snapshots")
        }
    }

    fun clearRestoredSnapshots() {
        if (restoreDirectory.exists() && !restoreDirectory.deleteRecursively()) {
            throw IOException("Could not remove system restore snapshots")
        }
    }

    private fun clearSecretSnapshots() {
        val directory = File(restoreDirectory, SECRETS_DIRECTORY)
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Could not remove system backup secret snapshots")
        }
    }

    private fun rememberLockNotice(file: File) {
        if (recoverAtomicFile(file) && withSettings(file) { it.settings.first().appLockEnabled }) {
            // Persist before resetting the switch, so a crash cannot make the lock reset silent.
            writeAtomically(lockNotice) { it.write(1) }
        }
    }

    private fun <T> withSettings(file: File, block: suspend (SettingsRepository) -> T): T = runBlocking(Dispatchers.IO) {
        // DataStore does not recover AtomicFile's legacy .bak or discard an interrupted .new itself.
        recoverAtomicFile(file)
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            block(SettingsRepository(store))
        } finally {
            job.cancelAndJoin()
        }
    }

    internal companion object {
        const val SNAPSHOT_DIRECTORY = "system-backup"
        const val RESTORE_DIRECTORY = "system-restore"
        const val SETTINGS_FILE = "app_settings.preferences_pb"
        const val SECRETS_DIRECTORY = "secrets"
        private const val RESTORE_PENDING = 1
        private const val SETTINGS_WITH_SECRETS = 2
        private const val SETTINGS_WITHOUT_SECRETS = 3
        private val secretsJson = Json { ignoreUnknownKeys = true }

        fun clearRestoredDatabase(file: File) {
            if (!file.exists()) return
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(::clearDeviceRows)
        }

        private fun clearDeviceRows(database: SQLiteDatabase) {
            database.setForeignKeyConstraintsEnabled(true)
            database.transaction {
                // Children first; old schemas may not have every table, and library tables are never touched.
                for (table in listOf("download_pictures", "download_eps", "download_comics", "remote_images", "api_cache")) {
                    val exists = database.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table),
                    ).use { it.moveToFirst() }
                    if (exists) database.execSQL("DELETE FROM `$table`")
                }
            }
        }

        private fun checkpoint(database: SQLiteDatabase) {
            database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { result ->
                if (!result.moveToFirst() || result.getInt(0) != 0 || result.getLong(1) != result.getLong(2)) {
                    throw IOException("Database checkpoint did not finish")
                }
            }
        }

        private fun copyAtomically(source: File, target: File) = writeAtomically(AtomicFile(target)) { output ->
            source.inputStream().use { it.copyTo(output) }
        }

        private fun writeAtomically(file: AtomicFile, write: (FileOutputStream) -> Unit) {
            val output = file.startWrite()
            try {
                write(output)
                file.finishWrite(output)
            } catch (exception: Exception) {
                file.failWrite(output)
                throw exception
            }
        }

        private fun deleteAtomicFile(file: AtomicFile) {
            file.delete()
            if (listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).any(File::exists)) {
                throw IOException("Could not remove device-only backup state")
            }
        }

        private fun readAtomicState(file: AtomicFile): Int? = try {
            file.openRead().use { it.read().takeIf { state -> state >= 0 } }
        } catch (_: FileNotFoundException) {
            null
        }

        private fun recoverAtomicFile(file: File): Boolean = try {
            AtomicFile(file).openRead().use { }
            true
        } catch (_: FileNotFoundException) {
            false
        }

        private fun deleteFile(file: File) {
            if (file.exists() && !file.delete()) throw IOException("Could not remove stale database journal")
        }
    }
}

internal enum class RestorePhase {
    AUTHENTICATION_INSTALLED, DATABASE_INSTALLED, DATABASE_CLEANED, SETTINGS_CLEANED, SNAPSHOTS_REMOVED,
}
