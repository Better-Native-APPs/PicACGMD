package app.picacgmd.core.domain.sync

import app.picacgmd.core.data.db.HistorySnapshot
import app.picacgmd.core.data.db.HistoryTombstone
import app.picacgmd.core.data.db.HistoryMutationTime
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.network.webdav.OkHttpWebDavClient
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class HistorySyncRecord(
    val comicId: String,
    val title: String,
    val author: String = "",
    val thumbFileServer: String = "",
    val thumbPath: String = "",
    val categoriesJson: String = "[]",
    val lastEpOrder: Int = 0,
    val lastEpTitle: String = "",
    val lastPictureIndex: Int = 0,
    val lastChapterPages: Int = 0,
    val pinned: Boolean = false,
    val createdAt: Long,
    /** Viewing recency is carried unchanged; [modifiedAt] alone decides event precedence. */
    val updatedAt: Long,
    val modifiedAt: Long = updatedAt,
) {
    fun toEntity(): ViewHistory = ViewHistory(comicId, title, author, thumbFileServer, thumbPath,
        categoriesJson, lastEpOrder, lastEpTitle, lastPictureIndex, lastChapterPages, pinned, createdAt, updatedAt, modifiedAt)
}

@Serializable
data class HistorySyncTombstone(val comicId: String, val deletedAt: Long)

@Serializable
data class HistorySyncDocument(
    val version: Int,
    val records: List<HistorySyncRecord>,
    val tombstones: List<HistorySyncTombstone>,
) {
    fun toSnapshot(): HistorySnapshot = HistorySnapshot(records.map(HistorySyncRecord::toEntity),
        tombstones.map { HistoryTombstone(it.comicId, it.deletedAt) })

    companion object {
        const val VERSION = 1
        val EMPTY = HistorySyncDocument(VERSION, emptyList(), emptyList())

        fun fromSnapshot(snapshot: HistorySnapshot): HistorySyncDocument = HistorySyncDocument(VERSION,
            snapshot.records.map { HistorySyncRecord(it.comicId, it.title, it.author, it.thumbFileServer, it.thumbPath,
                it.categoriesJson, it.lastEpOrder, it.lastEpTitle, it.lastPictureIndex, it.lastChapterPages,
                it.pinned, it.createdAt, it.updatedAt, it.modifiedAt) },
            snapshot.tombstones.map { HistorySyncTombstone(it.comicId, it.deletedAt) })
    }
}

interface HistorySyncCodec {
    fun decode(content: ByteArray): HistorySyncDocument
    fun encode(document: HistorySyncDocument): ByteArray
}

/** Strict known fields, forward-compatible extra fields. Invalid input is never repaired into empty history. */
object HistorySyncFormat : HistorySyncCodec {
    // Incoming-only: ten fixed 365-day years ahead of the receiving clock; local successors keep the absolute guard.
    // After rollback, stored events stay editable/exportable. A correctly timed peer rejects over-horizon files until
    // clock + horizon catches up, even after clocks are corrected: residual skew + 1 ms/action bounds that wait
    // while the clock advances 1,000 ms/s. Correcting clocks does not retimestamp history or create a local terminal event.
    const val INCOMING_HORIZON_MILLIS = 3_650L * 24 * 60 * 60 * 1_000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun decode(content: ByteArray): HistorySyncDocument {
        if (content.size > OkHttpWebDavClient.MAX_HISTORY_BYTES) throw WebDavException(WebDavError.FILE_TOO_LARGE)
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString()
            val root = json.parseToJsonElement(text)
            val versionField = root.jsonObject["version"]?.jsonPrimitive
            val version = versionField?.takeUnless { it.isString }?.intOrNull
                ?: throw WebDavException(WebDavError.DAMAGED_FILE)
            if (version > HistorySyncDocument.VERSION) throw WebDavException(WebDavError.NEWER_FORMAT)
            if (version != HistorySyncDocument.VERSION) throw WebDavException(WebDavError.DAMAGED_FILE)
            val document = json.decodeFromJsonElement(HistorySyncDocument.serializer(), root)
            if (document.records.map { it.comicId }.toSet().size != document.records.size ||
                document.tombstones.map { it.comicId }.toSet().size != document.tombstones.size ||
                document.records.any {
                    it.comicId.isBlank() ||
                        it.lastEpOrder < 0 || it.lastPictureIndex < 0 || it.lastChapterPages < 0
                } || document.tombstones.any { it.comicId.isBlank() }
            ) throw WebDavException(WebDavError.DAMAGED_FILE)
            validateEventTimes(document)
            document.records.forEach { json.decodeFromString<List<String>>(it.categoriesJson) }
            return document
        } catch (failure: WebDavException) {
            throw failure
        } catch (_: Exception) {
            // Decoder exceptions may include fragments of the private history file.
            throw WebDavException(WebDavError.DAMAGED_FILE)
        }
    }

    override fun encode(document: HistorySyncDocument): ByteArray {
        val bytes = json.encodeToString(document.copy(
            records = document.records.sortedBy { it.comicId }, tombstones = document.tombstones.sortedBy { it.comicId },
        )).toByteArray(Charsets.UTF_8)
        if (bytes.size > OkHttpWebDavClient.MAX_HISTORY_BYTES) throw WebDavException(WebDavError.FILE_TOO_LARGE)
        return bytes
    }

    internal fun tieKey(record: HistorySyncRecord): String = json.encodeToString(record)

    /** Receiving boundaries call this before mutation; local merge/serialization use only [validateEventTimes]. */
    internal fun validateIncomingEventTimes(document: HistorySyncDocument, now: Long) {
        validateEventTimes(document)
        fun tooFarAhead(timestamp: Long): Boolean = timestamp - INCOMING_HORIZON_MILLIS > now
        if (document.records.any {
                tooFarAhead(it.createdAt) || tooFarAhead(it.updatedAt) || tooFarAhead(it.modifiedAt)
            } || document.tombstones.any { tooFarAhead(it.deletedAt) }
        ) throw WebDavException(WebDavError.INVALID_TIMESTAMP)
    }

    internal fun validateEventTimes(document: HistorySyncDocument) {
        if (document.records.any {
                !HistoryMutationTime.isSupported(it.createdAt) || !HistoryMutationTime.isSupported(it.updatedAt) ||
                    !HistoryMutationTime.isSupported(it.modifiedAt)
            } || document.tombstones.any { !HistoryMutationTime.isSupported(it.deletedAt) }
        ) throw WebDavException(WebDavError.DAMAGED_FILE)
    }
}
