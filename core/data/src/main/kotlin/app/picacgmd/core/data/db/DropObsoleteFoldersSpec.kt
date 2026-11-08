package app.picacgmd.core.data.db

import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec

/** Removes only the retired tables; history, reading progress and downloads are retained. */
@DeleteTable(tableName = "local_favourites")
@DeleteTable(tableName = "local_favourite_folders")
class DropObsoleteFoldersSpec : AutoMigrationSpec
