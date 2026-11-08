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

        val migration = PikapikaDatabase_AutoMigration_1_2_Impl()
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)
        migration.migrate(connection)

        val droppedTables = statements.filter { it.startsWith("DROP TABLE") }.map {
            it.replace("`", "").removePrefix("DROP TABLE IF EXISTS ").removePrefix("DROP TABLE ").trimEnd(';')
        }
        assertEquals(setOf("local_favourites", "local_favourite_folders"), droppedTables.toSet())
        assertEquals(2, droppedTables.size)
        val dataWrite = Regex("^(CREATE|ALTER|INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE)
        assertTrue(statements.none { dataWrite.containsMatchIn(it.trim()) }, "Remaining tables must not be rewritten")
    }
}
