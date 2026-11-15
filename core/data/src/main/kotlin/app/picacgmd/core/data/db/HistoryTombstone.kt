package app.picacgmd.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "history_tombstones", indices = [Index(value = ["deletedAt"])])
data class HistoryTombstone(@PrimaryKey val comicId: String, val deletedAt: Long)

data class HistorySnapshot(val records: List<ViewHistory>, val tombstones: List<HistoryTombstone>)
