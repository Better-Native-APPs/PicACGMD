package app.picacgmd.core.data.db

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Existing viewing times become the initial sync versions without changing reading recency. */
class HistorySyncMigrationSpec : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL("UPDATE `view_history` SET `modifiedAt` = `updatedAt`")
    }
}
