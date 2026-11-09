package app.picacgmd.core.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks generated migration SQL without an Android runtime or a new SQLite test dependency. */
class DatabaseMigrationTest {
    @Test
    fun versionOneUpgradeOnlyDropsTheTwoRetiredTables() {
        val migration = PikapikaDatabase_AutoMigration_1_2_Impl()
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)
        val statements = recordStatements { migration.migrate(it) }

        val droppedTables = statements.filter { it.startsWith("DROP TABLE") }.map {
            it.replace("`", "").removePrefix("DROP TABLE IF EXISTS ").removePrefix("DROP TABLE ").trimEnd(';')
        }
        assertEquals(setOf("local_favourites", "local_favourite_folders"), droppedTables.toSet())
        assertEquals(2, droppedTables.size)
        val dataWrite = Regex("^(CREATE|ALTER|INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE)
        assertTrue(statements.none { dataWrite.containsMatchIn(it.trim()) }, "Remaining tables must not be rewritten")
    }

    @Test
    fun versionTwoUpgradeOnlyCreatesTheNewLocalLibrary() {
        val migration = PikapikaDatabase_AutoMigration_2_3_Impl()
        assertEquals(2, migration.startVersion)
        assertEquals(3, migration.endVersion)
        val statements = recordStatements { migration.migrate(it) }
        val createdTables = statements.filter { it.startsWith("CREATE TABLE") }
        assertEquals(2, createdTables.size)
        assertTrue(createdTables.any { "`local_folders`" in it })
        val entriesTable = createdTables.single { "`local_folder_entries`" in it }
        assertTrue("ON DELETE CASCADE" in entriesTable)
        assertTrue("PRIMARY KEY(`comicId`)" in entriesTable, "A comic must belong to only one local folder")
        assertTrue(statements.any { "CREATE INDEX" in it && "ON `local_folder_entries` (`folderId`)" in it })
        assertTrue(statements.none { Regex("^(DROP|ALTER|DELETE|UPDATE|REPLACE)\\b").containsMatchIn(it) })
        assertTrue(createdTables.none { "`local_favourites`" in it || "`local_favourite_folders`" in it })
    }

    private fun recordStatements(migrate: (SQLiteConnection) -> Unit): List<String> {
        val statements = mutableListOf<String>()
        val connection = Proxy.newProxyInstance(
            SQLiteConnection::class.java.classLoader,
            arrayOf(SQLiteConnection::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "prepare" -> {
                    statements += arguments[0] as String
                    Proxy.newProxyInstance(
                        SQLiteStatement::class.java.classLoader,
                        arrayOf(SQLiteStatement::class.java),
                    ) { _, statementMethod, _ ->
                        when (statementMethod.name) {
                            "step" -> false
                            "close" -> null
                            else -> error("Unexpected migration statement operation: ${statementMethod.name}")
                        }
                    }
                }
                "close" -> null
                else -> error("Unexpected migration connection operation: ${method.name}")
            }
        } as SQLiteConnection

        migrate(connection)
        return statements
    }
}
