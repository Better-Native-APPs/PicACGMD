package app.picacgmd.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "api_cache", indices = [Index(value = ["expiresAt"])])
data class ApiCache(
    @PrimaryKey val key: String,
    val json: String,
    val expiresAt: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun isExpired(now: Long): Boolean = expiresAt <= now

    companion object {
        const val CATEGORIES_KEY: String = "categories"
        const val COLLECTIONS_KEY: String = "collections"
        const val CATEGORIES_COLLECTIONS_TTL_MILLIS: Long = 3 * 60 * 60 * 1_000L

        fun categories(json: String, now: Long = System.currentTimeMillis()): ApiCache =
            ApiCache(CATEGORIES_KEY, json, now + CATEGORIES_COLLECTIONS_TTL_MILLIS, now)

        fun collections(json: String, now: Long = System.currentTimeMillis()): ApiCache =
            ApiCache(COLLECTIONS_KEY, json, now + CATEGORIES_COLLECTIONS_TTL_MILLIS, now)
    }
}

/** Image-cache files are separate from downloads, so clearing this table cannot delete downloaded comics. */
@Entity(
    tableName = "remote_images",
    primaryKeys = ["fileServer", "path"],
    indices = [Index(value = ["updatedAt"])],
)
data class RemoteImage(
    val fileServer: String,
    val path: String,
    val filePath: String,
    val fileSizeBytes: Long = 0,
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)
