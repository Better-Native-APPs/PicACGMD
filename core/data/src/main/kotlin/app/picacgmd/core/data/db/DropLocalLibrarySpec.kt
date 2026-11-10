package app.picacgmd.core.data.db

import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec

/** Removes device-only favourites while preserving history, downloads, subscriptions and tags. */
@DeleteTable(tableName = "local_folder_entries")
@DeleteTable(tableName = "local_folders")
class DropLocalLibrarySpec : AutoMigrationSpec
