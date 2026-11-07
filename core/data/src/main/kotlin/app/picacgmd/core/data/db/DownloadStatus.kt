package app.picacgmd.core.data.db

import androidx.room.TypeConverter

/** Persisted queue states. Retrying or appending chapters requeues a task; deletion is terminal. */
enum class DownloadStatus(val storedValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    PAUSED("paused"),
    FAILED("failed"),
    DONE("done"),
    DELETING("deleting");

    fun canTransitionTo(next: DownloadStatus): Boolean = next == this || when (this) {
        QUEUED -> next in setOf(RUNNING, PAUSED, DELETING)
        RUNNING -> next in setOf(QUEUED, PAUSED, FAILED, DONE, DELETING)
        PAUSED -> next in setOf(QUEUED, DELETING)
        FAILED -> next in setOf(QUEUED, PAUSED, DELETING)
        DONE -> next in setOf(QUEUED, DELETING)
        DELETING -> false
    }

    fun transitionTo(next: DownloadStatus): DownloadStatus {
        require(canTransitionTo(next)) { "Invalid download transition: $this -> $next" }
        return next
    }

    companion object {
        fun fromStoredValue(value: String): DownloadStatus =
            entries.firstOrNull { it.storedValue == value }
                ?: throw IllegalArgumentException("Unknown download status: $value")
    }
}

class DownloadStatusConverters {
    @TypeConverter
    fun encode(status: DownloadStatus): String = status.storedValue

    @TypeConverter
    fun decode(value: String): DownloadStatus = DownloadStatus.fromStoredValue(value)
}
