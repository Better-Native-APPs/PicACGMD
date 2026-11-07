package app.picacgmd.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Paths are relative to the app-managed download root, never external export destinations. */
@Entity(tableName = "download_comics", indices = [Index(value = ["status", "createdAt"]), Index(value = ["folder"])])
data class DownloadComic(
    @PrimaryKey val comicId: String,
    val title: String,
    val author: String = "",
    val thumbFileServer: String = "",
    val thumbPath: String = "",
    val categoriesJson: String = "[]",
    val tagsJson: String = "[]",
    val description: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val selectedEpCount: Int = 0,
    val completedEpCount: Int = 0,
    val selectedPictureCount: Int = 0,
    val completedPictureCount: Int = 0,
    val downloadedBytes: Long = 0,
    val folder: String = "",
    val filePath: String = comicId,
    val thumbFilePath: String = "$comicId/logo",
    val thumbFormat: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
)

@Entity(
    tableName = "download_eps",
    foreignKeys = [ForeignKey(
        entity = DownloadComic::class,
        parentColumns = ["comicId"],
        childColumns = ["comicId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["comicId", "epId"], unique = true),
        Index(value = ["comicId", "epOrder"], unique = true),
        Index(value = ["status"]),
    ],
)
data class DownloadEp(
    @PrimaryKey val epId: String,
    val comicId: String,
    val epOrder: Int,
    val title: String,
    val picturesFetched: Boolean = false,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val selectedPictureCount: Int = 0,
    val completedPictureCount: Int = 0,
    val downloadedBytes: Long = 0,
    val filePath: String = "$comicId/$epOrder",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
)

/** epOrder and pictureIndex are one-based. Files have no suffix; format identifies their encoding. */
@Entity(
    tableName = "download_pictures",
    foreignKeys = [ForeignKey(
        entity = DownloadEp::class,
        parentColumns = ["comicId", "epId"],
        childColumns = ["comicId", "epId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["comicId", "epId"]),
        Index(value = ["comicId", "epOrder", "pictureIndex"], unique = true),
        Index(value = ["epId", "pictureIndex"]),
        Index(value = ["status"]),
        Index(value = ["fileServer", "path"]),
    ],
)
data class DownloadPicture(
    @PrimaryKey val pictureId: String,
    val comicId: String,
    val epId: String,
    val epOrder: Int,
    val pictureIndex: Int,
    val fileServer: String,
    val path: String,
    val originalName: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val fileSizeBytes: Long = 0,
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val filePath: String = "$comicId/$epOrder/$pictureIndex",
    val attemptCount: Int = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
)

/** A live projection of the persisted picture states, including an empty (not yet resolved) comic. */
data class DownloadCounts(
    val comicId: String,
    val totalEps: Int,
    val completedEps: Int,
    val totalPictures: Int,
    val queuedPictures: Int,
    val runningPictures: Int,
    val pausedPictures: Int,
    val failedPictures: Int,
    val completedPictures: Int,
    val deletingPictures: Int,
    val downloadedBytes: Long,
)

/** Claim generations prevent a late pre-pause worker from overwriting a resumed worker's result. */
fun DownloadPicture.acceptsResult(result: DownloadPicture): Boolean =
    status == DownloadStatus.RUNNING &&
        result.status in setOf(DownloadStatus.DONE, DownloadStatus.FAILED) &&
        pictureId == result.pictureId && comicId == result.comicId && epId == result.epId &&
        epOrder == result.epOrder && pictureIndex == result.pictureIndex &&
        attemptCount == result.attemptCount
