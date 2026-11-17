package app.picacgmd.backup

import android.app.backup.BackupAgent
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.picacgmd.core.data.settings.AppTheme
import app.picacgmd.core.data.settings.RoutingMode
import app.picacgmd.core.data.settings.SettingsRepository
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Uses framework file restore in Robolectric, not a transport or its Binder restore lifecycle. */
internal fun backupRestoreAgent(context: Context): PicaBackupAgent {
    val schemeCache = Class.forName("android.app.backup.FullBackup").getDeclaredField("kPackageBackupSchemeMap")
        .apply { isAccessible = true }.get(null) as MutableMap<*, *>
    schemeCache.clear()
    val backupContext = object : ContextWrapper(context) {
        override fun getDataDir() = File(context.applicationInfo.dataDir)
        // The framework calls this hidden method, which is absent from the public SDK.
        fun createCredentialProtectedStorageContext(): Context = this
    }
    return PicaBackupAgent().also { agent ->
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(agent, backupContext)
    }
}

internal fun deliverBackupSnapshot(context: Context, agent: PicaBackupAgent, name: String, bytes: ByteArray) {
    val transportFile = File.createTempFile("snapshot-restore-", ".bin", context.cacheDir)
    try {
        if (name.startsWith("${SystemBackupStorage.SECRETS_DIRECTORY}/")) {
            // Full backup carries the included directory entry before its files; eligibility needs that directory.
            transportFile.writeBytes(byteArrayOf())
            ParcelFileDescriptor.open(transportFile, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                val directory = File(context.filesDir, "${SystemBackupStorage.SNAPSHOT_DIRECTORY}/${SystemBackupStorage.SECRETS_DIRECTORY}")
                agent.onRestoreFile(input, 0L, directory, BackupAgent.TYPE_DIRECTORY, -1L, 0L)
            }
        }
        transportFile.writeBytes(bytes)
        ParcelFileDescriptor.open(transportFile, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            val destination = File(context.filesDir, "${SystemBackupStorage.SNAPSHOT_DIRECTORY}/$name")
            agent.onRestoreFile(input, bytes.size.toLong(), destination, BackupAgent.TYPE_FILE, -1L, 0L)
        }
    } finally {
        transportFile.delete()
    }
}

internal fun <T> withBackupSettings(file: File, block: suspend (SettingsRepository) -> T): T =
    withBackupPreferences(file) { block(SettingsRepository(it)) }

internal fun <T> withBackupPreferences(file: File, block: suspend (DataStore<Preferences>) -> T): T = runBlocking(Dispatchers.IO) {
    val job = SupervisorJob()
    val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
    try {
        block(store)
    } finally {
        job.cancelAndJoin()
    }
}

internal fun populateBackupSettings(file: File): Unit = withBackupPreferences(file) { store ->
    val repository = SettingsRepository(store)
    repository.setAccountEmail("reader@example.com")
    repository.setAuthToken("private-token", 123)
    repository.setApiRouteAddress("203.0.113.1")
    repository.setSyncedApiAddresses(listOf("203.0.113.1"), 456)
    repository.setImageRouting(RoutingMode.ROUTE_2)
    repository.setTimeOffsetHours(8)
    repository.setDisplayModeId(42)
    repository.setDownloadTreeUri("content://documents/tree/comics")
    repository.setAppLockEnabled(true)
    repository.setBiometricUnlockEnabled(false)
    repository.setAppLockTimeoutSeconds(60)
    repository.setEInkReduceAnimations(true)
    repository.setTheme(AppTheme.BOOK)
    repository.setPinnedHomeCategories(listOf("分类二", "分类一"))
    repository.setDownloadThreads(16)
    repository.setPreventScreenshots(true)
    repository.setAutoPunchIn(false)
    repository.setWebDavAccount("https://backup.example.com/dav/", "sync-reader", "Library")
    repository.setWebDavAutoSync(true)
    repository.setWebDavWifiOnly(false)
    repository.setWebDavLastSyncedAt(789L)
    // Seed a real old key without reintroducing a setter for the removed feature.
    store.edit { it[stringPreferencesKey("startup_image_uri")] = "content://images/startup" }
}

/** Real preference XML and default file restore, with a simulated transport and aligned test storage paths. */
internal object BackupPreferenceFiles {
    private val carriedStores = listOf("onboarding", "reader_tap_overlay")
    private val excludedStores = listOf("system_permissions", "cache_maintenance")

    fun stageRestore(context: Context) {
        assertTrue(context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit()
            .putBoolean("notice_accepted", true).commit())
        assertTrue(context.getSharedPreferences("reader_tap_overlay", Context.MODE_PRIVATE).edit()
            .putBoolean("ThreeZones", true).putBoolean("Controller", false).commit())
        assertTrue(context.getSharedPreferences("system_permissions", Context.MODE_PRIVATE).edit()
            .putBoolean("notifications_requested", true).commit())
        assertTrue(context.getSharedPreferences("cache_maintenance", Context.MODE_PRIVATE).edit()
            .putLong("last_successful_cleanup_millis", 123L).commit())
        val payloads = carriedStores.associateWith { preferenceFile(context, it).readBytes() }
        // Clear both files and Context's cached preferences to model the destination's first read.
        clear(context)
        // A fresh destination process has no scheme cached for a previous test Application's directories.
        val schemeCache = Class.forName("android.app.backup.FullBackup").getDeclaredField("kPackageBackupSchemeMap")
            .apply { isAccessible = true }.get(null) as MutableMap<*, *>
        schemeCache.clear()
        val agent = PicaBackupAgent()
        // Robolectric's dataDir differs from the directory actually backing SharedPreferences.
        val backupContext = object : ContextWrapper(context) {
            override fun getDataDir() = File(context.applicationInfo.dataDir)
            // The runtime calls this hidden platform method; it is absent from the public SDK.
            fun createCredentialProtectedStorageContext(): Context = this
        }
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(agent, backupContext)
        val transportFile = File.createTempFile("preferences-restore-", ".xml", context.cacheDir)
        try {
            for ((name, bytes) in payloads) {
                transportFile.writeBytes(bytes)
                ParcelFileDescriptor.open(transportFile, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                    agent.onRestoreFile(input, bytes.size.toLong(), preferenceFile(context, name), BackupAgent.TYPE_FILE, -1L, 0L)
                }
                val restored = preferenceFile(context, name)
                assertTrue(restored.isFile, "$name must be installed by the default file restore")
                assertContentEquals(bytes, restored.readBytes(), "$name must retain its actual preference XML")
            }
        } finally {
            transportFile.delete()
        }
    }

    fun clear(context: Context) {
        for (name in carriedStores + excludedStores) context.deleteSharedPreferences(name)
    }

    private fun preferenceFile(context: Context, name: String) =
        File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
}

/** Builds actual exported schemas, including Room's identity hash, without opening current Room. */
internal object BackupDatabaseFixture {
    const val COMIC = "restored-comic"
    const val DELETED_COMIC = "restored-deleted-comic"
    const val HISTORY_MODIFIED_AT = 12_000L
    const val TOMBSTONE_DELETED_AT = 13_000L
    const val CACHE_MARKER = "private-api-cache-marker"
    val libraryTables = listOf("view_history", "search_history", "tag_favourites", "subscriptions", "history_tombstones")
    val deviceTables = listOf("download_comics", "download_eps", "download_pictures", "api_cache", "remote_images")

    fun create(file: File, version: Int = 6): SQLiteDatabase {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val schema = schema(version)
        return SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val name = entity.getString("tableName")
                execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = entity.getJSONArray("indices")
                for (entry in 0 until indices.length()) {
                    execSQL(indices.getJSONObject(entry).getString("createSql").replace("\${TABLE_NAME}", name))
                }
            }
            val queries = schema.getJSONArray("setupQueries")
            for (index in 0 until queries.length()) execSQL(queries.getString(index))
            this.version = version
        }
    }

    fun seed(database: SQLiteDatabase, version: Int = database.version) {
        val entities = schema(version).getJSONArray("entities")
        database.beginTransaction()
        try {
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                if (table !in libraryTables && table !in deviceTables) continue
                val fields = entity.getJSONArray("fields")
                val values = ContentValues()
                for (fieldIndex in 0 until fields.length()) {
                    val field = fields.getJSONObject(fieldIndex)
                    if (!field.optBoolean("notNull", false)) continue
                    val column = field.getString("columnName")
                    if (field.getString("affinity") == "TEXT") values.put(column, "") else values.put(column, 0)
                }
                val overrides = when (table) {
                    "view_history" -> mapOf(
                        "comicId" to COMIC, "title" to "恢复的历史", "author" to "作者", "lastEpOrder" to 3,
                        "lastEpTitle" to "第三章", "lastPictureIndex" to 4, "pinned" to 1, "updatedAt" to 9_000,
                    )
                    "history_tombstones" -> mapOf("comicId" to DELETED_COMIC, "deletedAt" to TOMBSTONE_DELETED_AT)
                    "search_history" -> mapOf("query" to "恢复的搜索", "updatedAt" to 8_000)
                    "tag_favourites" -> mapOf("tag" to "恢复的标签", "sortOrder" to 2)
                    "subscriptions" -> mapOf(
                        "comicId" to COMIC, "title" to "恢复的追更", "lastKnownEpsCount" to 6, "unseen" to 1, "newEpCount" to 2,
                    )
                    "download_comics" -> mapOf("comicId" to COMIC, "title" to "缺失的下载", "status" to "done")
                    "download_eps" -> mapOf("epId" to "ep-1", "comicId" to COMIC, "epOrder" to 3, "status" to "done")
                    "download_pictures" -> mapOf(
                        "pictureId" to "picture-1", "comicId" to COMIC, "epId" to "ep-1", "epOrder" to 3,
                        "pictureIndex" to 4, "status" to "done", "filePath" to "content://missing/page",
                    )
                    "api_cache" -> mapOf("key" to "categories", "json" to CACHE_MARKER, "expiresAt" to 9_999_999)
                    "remote_images" -> mapOf("fileServer" to "image-host", "path" to "image", "filePath" to "missing-cache-file")
                    else -> error("Unknown fixture table")
                }
                for ((column, value) in overrides) {
                    when (value) {
                        is String -> values.put(column, value)
                        is Int -> values.put(column, value)
                        is Long -> values.put(column, value)
                        else -> error("Unknown fixture value type")
                    }
                }
                if (table == "view_history" && (0 until fields.length()).any {
                        fields.getJSONObject(it).getString("columnName") == "lastChapterPages"
                    }) values.put("lastChapterPages", 12)
                if (table == "view_history" && (0 until fields.length()).any {
                        fields.getJSONObject(it).getString("columnName") == "modifiedAt"
                    }) values.put("modifiedAt", HISTORY_MODIFIED_AT)
                database.insertOrThrow(table, null, values)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun libraryRows(file: File): Map<String, List<String?>> = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        libraryTables.filter { table ->
            database.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }
        }.associateWith { table ->
            database.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
                check(cursor.moveToFirst())
                (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
            }
        }
    }

    fun count(file: File, table: String): Int = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY,
    ).use { database -> database.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { it.moveToFirst(); it.getInt(0) } }

    private fun schema(version: Int): JSONObject {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return JSONObject(File(root, "core/data/schemas/app.picacgmd.core.data.db.PicaDatabase/$version.json").readText())
            .getJSONObject("database")
    }
}
