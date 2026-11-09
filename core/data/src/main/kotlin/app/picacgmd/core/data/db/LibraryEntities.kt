package app.picacgmd.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** All timestamps are epoch milliseconds. Episode/page positions are one-based; zero means unread. */
@Entity(tableName = "view_history", indices = [Index(value = ["pinned", "updatedAt"])])
data class ViewHistory(
    @PrimaryKey val comicId: String,
    val title: String,
    val author: String = "",
    val thumbFileServer: String = "",
    val thumbPath: String = "",
    val categoriesJson: String = "[]",
    val lastEpOrder: Int = 0,
    val lastEpTitle: String = "",
    val lastPictureIndex: Int = 0,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

@Entity(tableName = "search_history", indices = [Index(value = ["updatedAt"])])
data class SearchHistory(
    @PrimaryKey val query: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tag_favourites", indices = [Index(value = ["sortOrder"])])
data class TagFavourite(
    @PrimaryKey val tag: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** New table names keep the retired version-one tables separate from the on-device library. */
@Entity(tableName = "local_folders", indices = [Index(value = ["sortOrder"])])
data class LocalFavouriteFolder(
    @PrimaryKey val folderId: String,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSelectedAt: Long = 0,
) {
    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "默认收藏夹"
    }
}

/** A complete list snapshot, so browsing local favourites never needs a network request. */
@Entity(
    tableName = "local_folder_entries",
    primaryKeys = ["folderId", "comicId"],
    foreignKeys = [ForeignKey(
        entity = LocalFavouriteFolder::class,
        parentColumns = ["folderId"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["comicId"]), Index(value = ["addedAt"])],
)
data class LocalFavouriteEntry(
    val folderId: String,
    val comicId: String,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val thumbFileServer: String = "",
    val thumbPath: String = "",
    val categoriesJson: String = "[]",
    val pagesCount: Int = 0,
    val epsCount: Int = 0,
    val finished: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "subscriptions", indices = [Index(value = ["unseen", "updatedAt"])])
data class Subscription(
    @PrimaryKey val comicId: String,
    val title: String,
    val author: String = "",
    val thumbFileServer: String = "",
    val thumbPath: String = "",
    val lastKnownEpsCount: Int = 0,
    val lastKnownUpdatedAt: Long? = null,
    val unseen: Boolean = false,
    val newEpCount: Int = 0,
    val lastCheckedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    /** Checking again does not consume an unread update; new chapter counts accumulate until seen. */
    fun withLatest(epsCount: Int, comicUpdatedAt: Long?, checkedAt: Long): Subscription {
        require(epsCount >= 0) { "Episode count must not be negative" }
        val addedEps = (epsCount - lastKnownEpsCount).coerceAtLeast(0)
        val changed = lastKnownUpdatedAt != null && comicUpdatedAt != null &&
            comicUpdatedAt > lastKnownUpdatedAt
        return copy(
            lastKnownEpsCount = epsCount,
            lastKnownUpdatedAt = comicUpdatedAt ?: lastKnownUpdatedAt,
            unseen = unseen || addedEps > 0 || changed,
            newEpCount = newEpCount + addedEps,
            lastCheckedAt = checkedAt,
            updatedAt = if (addedEps > 0 || changed) checkedAt else updatedAt,
        )
    }
}
