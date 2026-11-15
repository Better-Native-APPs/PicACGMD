package app.picacgmd.feature.sync

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.picacgmd.core.data.db.PicaDatabase
import app.picacgmd.core.data.db.ViewHistory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HistorySyncDatabaseMigrationTest {
    @Test fun aRealPopulatedVersionFiveDatabaseReopensAtSixWithoutChangingAnyHistoryField() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "history-sync-v5.db"
        val schema = Json.parseToJsonElement(
            File("../core/data/schemas/app.picacgmd.core.data.db.PicaDatabase/5.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val rows = listOf(
            ViewHistory("b", "标题 'B'", "Author", "https://localhost", "cover/b", "[\"Category\"]",
                lastEpOrder = 8, lastEpTitle = "第八话", lastPictureIndex = 12, lastChapterPages = 30,
                pinned = true, createdAt = 123, updatedAt = 456),
            ViewHistory("a", "Unread", createdAt = 0, updatedAt = 0),
            ViewHistory("c", "Chapter without a known page count", lastEpOrder = 1,
                lastEpTitle = "One", lastPictureIndex = 3, createdAt = 111, updatedAt = 456),
        )
        context.deleteDatabase(name)
        var migrated: PicaDatabase? = null
        try {
            val old = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (entity in schema.getValue("entities").jsonArray) {
                                val table = entity.jsonObject
                                val tableName = table.getValue("tableName").jsonPrimitive.content
                                db.execSQL(table.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                                for (index in table.getValue("indices").jsonArray) {
                                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                                }
                            }
                            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            error("The fixture must be created at version 5")
                        }
                    }).build(),
            )
            try {
                val db = old.writableDatabase
                assertEquals(5, db.version)
                db.query("SELECT * FROM view_history").use { cursor ->
                    assertEquals(13, cursor.columnCount)
                    assertFalse("modifiedAt" in cursor.columnNames)
                }
                for (row in rows) {
                    db.execSQL("""
                        INSERT INTO view_history (comicId, title, author, thumbFileServer, thumbPath,
                            categoriesJson, lastEpOrder, lastEpTitle, lastPictureIndex, lastChapterPages,
                            pinned, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(), arrayOf<Any>(row.comicId, row.title, row.author, row.thumbFileServer, row.thumbPath,
                        row.categoriesJson, row.lastEpOrder, row.lastEpTitle, row.lastPictureIndex, row.lastChapterPages,
                        if (row.pinned) 1 else 0, row.createdAt, row.updatedAt))
                }
                db.query("SELECT COUNT(*) FROM view_history").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(rows.size, cursor.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'history_tombstones'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                old.close()
            }

            val database = Room.databaseBuilder(context, PicaDatabase::class.java, name).allowMainThreadQueries().build()
            migrated = database
            val history = database.viewHistoryDao().getAll()
            assertEquals(rows.sortedBy { it.comicId }, history)
            assertTrue(history.all { it.modifiedAt == it.updatedAt })
            assertTrue(database.viewHistoryDao().getTombstones().isEmpty())
            assertEquals(6, database.openHelper.readableDatabase.version)
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
        }
    }
}
