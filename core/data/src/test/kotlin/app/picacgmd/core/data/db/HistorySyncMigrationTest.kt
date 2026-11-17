package app.picacgmd.core.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistorySyncMigrationTest {
    @Test fun versionFiveUpgradeAddsTombstonesAndBackfillsMutationTimeWithoutChangingRecency() {
        val migration = PicaDatabase_AutoMigration_5_6_Impl()
        assertEquals(5, migration.startVersion)
        assertEquals(6, migration.endVersion)
        val statements = mutableListOf<String>()
        val connection = Proxy.newProxyInstance(SQLiteConnection::class.java.classLoader, arrayOf(SQLiteConnection::class.java)) { _, method, args ->
            when (method.name) {
                "prepare" -> {
                    statements += args[0] as String
                    Proxy.newProxyInstance(SQLiteStatement::class.java.classLoader, arrayOf(SQLiteStatement::class.java)) { _, statementMethod, _ ->
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
        migration.migrate(connection)
        assertEquals(4, statements.size)
        val table = statements.single { it.startsWith("CREATE TABLE") }
        assertTrue("`history_tombstones`" in table)
        assertTrue("`comicId` TEXT NOT NULL" in table)
        assertTrue("`deletedAt` INTEGER NOT NULL" in table)
        assertTrue("PRIMARY KEY(`comicId`)" in table)
        assertTrue(statements.any { "CREATE INDEX" in it && "ON `history_tombstones` (`deletedAt`)" in it })
        assertTrue(statements.any { it == "ALTER TABLE `view_history` ADD COLUMN `modifiedAt` INTEGER NOT NULL DEFAULT 0" })
        assertEquals("UPDATE `view_history` SET `modifiedAt` = `updatedAt`", statements.last())
        assertTrue(statements.none { Regex("^(DROP|DELETE|REPLACE|INSERT)\\b").containsMatchIn(it) })
    }
}
