package app.picacgmd.core.domain.sync

import app.picacgmd.core.data.db.HistorySnapshot
import app.picacgmd.core.data.db.HistoryTombstone
import app.picacgmd.core.data.db.HistoryMutationTime
import app.picacgmd.core.data.db.ViewHistory
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class HistorySyncFormatTest {
    @Test fun unknownFieldsAreIgnoredAtEveryLevel() {
        val content = """{"version":1,"future":{"x":true},"records":[{"comicId":"a","title":"标题","createdAt":1,"updatedAt":2,"extra":123}],"tombstones":[{"comicId":"b","deletedAt":3,"extra":true}]}"""
        val document = HistorySyncFormat.decode(content.toByteArray())
        assertEquals(HistorySyncRecord("a", "标题", createdAt = 1, updatedAt = 2), document.records.single())
        assertEquals(HistorySyncTombstone("b", 3), document.tombstones.single())
    }

    @Test fun higherVersionsAreRefusedBeforeDecodingTheirUnknownStructure() {
        val failure = assertFailsWith<WebDavException> {
            HistorySyncFormat.decode("""{"version":2,"newHistoryShape":{"private":"value"}}""".toByteArray())
        }
        assertEquals(WebDavError.NEWER_FORMAT, failure.error)
        assertNull(failure.cause)
    }

    @Test fun damagedOrTruncatedFilesAreNotRepairedIntoEmptyHistory() {
        listOf("", "{", "[]", "{}", """{"version":1}""", """{"version":0,"records":[],"tombstones":[]}""",
            """{"version":"1","records":[],"tombstones":[]}""",
            """{"version":1,"records":[{"comicId":"a","title":"private-history","createdAt":1}],"tombstones":[]}""",
            """{"version":1,"records":[{"comicId":"a","title":"Title","createdAt":1,"updatedAt":-1}],"tombstones":[]}""",
            """{"version":1,"records":[],"tombstones":[{"comicId":"a","deletedAt":2},{"comicId":"a","deletedAt":3}]}"""
        ).forEach { content ->
            val failure = assertFailsWith<WebDavException> { HistorySyncFormat.decode(content.toByteArray()) }
            assertEquals(WebDavError.DAMAGED_FILE, failure.error)
            assertNull(failure.cause)
            assertEquals("WebDAV DAMAGED_FILE", failure.message)
        }
    }

    @Test fun invalidUtf8AndInvalidCategoriesAreTypedDamage() {
        assertEquals(WebDavError.DAMAGED_FILE, assertFailsWith<WebDavException> {
            HistorySyncFormat.decode(byteArrayOf(0xc3.toByte(), 0x28))
        }.error)
        assertEquals(WebDavError.DAMAGED_FILE, assertFailsWith<WebDavException> {
            HistorySyncFormat.decode("""{"version":1,"records":[{"comicId":"a","title":"T","createdAt":1,"updatedAt":2,"categoriesJson":"{}"}],"tombstones":[]}""".toByteArray())
        }.error)
    }

    @Test fun everyHistoryFieldRoundTripsWithoutAddingAccountOrDeviceMetadata() {
        val record = ViewHistory("a", "标题", "Author", "https://localhost", "cover", "[\"Category\"]",
            lastEpOrder = 3, lastEpTitle = "第三话", lastPictureIndex = 17, lastChapterPages = 24,
            pinned = true, createdAt = 12, updatedAt = 34, modifiedAt = 45)
        val snapshot = HistorySnapshot(listOf(record), listOf(HistoryTombstone("b", 56)))
        val bytes = HistorySyncFormat.encode(HistorySyncDocument.fromSnapshot(snapshot))
        assertEquals(snapshot, HistorySyncFormat.decode(bytes).toSnapshot())
        assertEquals(setOf("version", "records", "tombstones"), Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.keys)
    }

    @Test fun unsupportedEpochsAreTypedDamageForEveryTimestampWithoutClamping() {
        val record = HistorySyncRecord("a", "Title", createdAt = 1, updatedAt = 2, modifiedAt = 3)
        for (timestamp in listOf(-1L, Long.MIN_VALUE, HistoryMutationTime.MAX_EPOCH_MILLIS + 1, Long.MAX_VALUE)) {
            val documents = listOf(
                HistorySyncDocument(1, listOf(record.copy(createdAt = timestamp)), emptyList()),
                HistorySyncDocument(1, listOf(record.copy(updatedAt = timestamp)), emptyList()),
                HistorySyncDocument(1, listOf(record.copy(modifiedAt = timestamp)), emptyList()),
                HistorySyncDocument(1, emptyList(), listOf(HistorySyncTombstone("a", timestamp))),
            )
            for (document in documents) {
                val failure = assertFailsWith<WebDavException> { HistorySyncFormat.decode(HistorySyncFormat.encode(document)) }
                assertEquals(WebDavError.DAMAGED_FILE, failure.error)
                assertEquals("WebDAV DAMAGED_FILE", failure.message)
                assertNull(failure.cause)
            }
        }
    }

    @Test fun absoluteEpochBoundariesAreSerializableAndLegacyRecordsStartWithViewingTimeAsTheirVersion() {
        val maximum = HistoryMutationTime.MAX_EPOCH_MILLIS
        val document = HistorySyncDocument(1, listOf(
            HistorySyncRecord("a", "Title", createdAt = 0, updatedAt = 0, modifiedAt = maximum)),
            listOf(HistorySyncTombstone("b", maximum)))
        assertEquals(document, HistorySyncFormat.decode(HistorySyncFormat.encode(document)))
        val legacy = HistorySyncFormat.decode(
            """{"version":1,"records":[{"comicId":"a","title":"Title","createdAt":1,"updatedAt":2}],"tombstones":[]}""".toByteArray())
        assertEquals(2L, legacy.records.single().modifiedAt)
    }

    @Test fun incomingAdmissionUsesTheReceivingClockForEveryTimestampWithoutClamping() {
        val now = 1_800_000_000_000L
        val boundary = now + HistorySyncFormat.INCOMING_HORIZON_MILLIS
        val record = HistorySyncRecord("a", "Title", createdAt = boundary, updatedAt = boundary, modifiedAt = boundary)
        val accepted = HistorySyncDocument(1, listOf(record), listOf(HistorySyncTombstone("b", boundary)))
        HistorySyncFormat.validateIncomingEventTimes(accepted, now)
        assertEquals(accepted, HistorySyncFormat.decode(HistorySyncFormat.encode(accepted)))
        val documents = listOf(
            accepted.copy(records = listOf(record.copy(createdAt = boundary + 1))),
            accepted.copy(records = listOf(record.copy(updatedAt = boundary + 1))),
            accepted.copy(records = listOf(record.copy(modifiedAt = boundary + 1))),
            accepted.copy(tombstones = listOf(HistorySyncTombstone("b", boundary + 1))),
        )
        for (document in documents) {
            val decoded = HistorySyncFormat.decode(HistorySyncFormat.encode(document))
            val failure = assertFailsWith<WebDavException> { HistorySyncFormat.validateIncomingEventTimes(decoded, now) }
            assertEquals(WebDavError.INVALID_TIMESTAMP, failure.error)
            assertEquals("WebDAV INVALID_TIMESTAMP", failure.message)
            assertNull(failure.cause)
            HistorySyncFormat.validateIncomingEventTimes(decoded, now + 1)
            assertEquals(document, decoded)
        }
    }

    @Test fun aClockRollbackDoesNotApplyIncomingAdmissionToLocalMergeOrSerialization() {
        val now = 1_800_000_000_000L
        val timestamp = now + HistorySyncFormat.INCOMING_HORIZON_MILLIS
        val record = HistorySyncRecord("a", "Title", createdAt = 1, updatedAt = now, modifiedAt = timestamp)
        val accepted = HistorySyncDocument(1, listOf(record), emptyList())
        HistorySyncFormat.validateIncomingEventTimes(accepted, now)
        val successor = accepted.copy(records = listOf(record.copy(modifiedAt = HistoryMutationTime.next(now - 60_000, timestamp))))
        assertEquals(successor, HistorySyncFormat.decode(HistorySyncFormat.encode(HistoryMerge.merge(successor, accepted))))
        assertEquals(WebDavError.INVALID_TIMESTAMP, assertFailsWith<WebDavException> {
            HistorySyncFormat.validateIncomingEventTimes(successor, now - 60_000)
        }.error)
        HistorySyncFormat.validateIncomingEventTimes(successor, now + 1)
    }
}
