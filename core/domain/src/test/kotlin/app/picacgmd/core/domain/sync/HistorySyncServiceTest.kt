package app.picacgmd.core.domain.sync

import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavResult
import app.picacgmd.core.network.webdav.WebDavRevision
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class HistorySyncServiceTest {
    @Test fun twoWaySyncWritesTheSameMergedHistoryToBothSides() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("local", 2)), syncDocument(syncRecord("remote", 3)))
        val result = assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        assertEquals(listOf("local", "remote"), fixture.store.document.records.map { it.comicId })
        assertEquals(fixture.store.document, fixture.transport.document)
        assertEquals(HistorySyncCounts(1, 1, 0), result.counts)
        assertEquals(listOf<WebDavRevision>(WebDavRevision.Existing("\"one\"")), fixture.transport.revisions)
        assertEquals(100L, fixture.service.state.value.lastSuccessAt)
        assertEquals(100L, fixture.preferences.settings.value.lastSyncedAt)
        assertEquals(HistorySyncActivity.Idle, fixture.service.state.value.activity)
    }

    @Test fun localOverwriteRebasesTheChosenRecordAndDeletesRemoteOnlyRecords() = runTest {
        val chosen = syncRecord("a", 2).copy(lastEpOrder = 1, pinned = false)
        val fixture = SyncFixture(backgroundScope, syncDocument(chosen),
            syncDocument(syncRecord("a", 90).copy(lastEpOrder = 9, pinned = true), syncRecord("remote-only", 95)))
        val result = assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize(HistorySyncDirection.LOCAL_TO_REMOTE))
        assertEquals(chosen.copy(modifiedAt = 100), fixture.transport.document.records.single())
        assertEquals(listOf(HistorySyncTombstone("remote-only", 100)), fixture.transport.document.tombstones)
        assertEquals(fixture.store.document, fixture.transport.document)
        assertEquals(HistorySyncCounts(uploaded = 1, downloaded = 0, removed = 1), result.counts)
    }

    @Test fun remoteOverwriteChangesOnlyLocalStorageAndRemembersDiscardedLocalRecords() = runTest {
        val remote = syncDocument(syncRecord("a", 2).copy(lastEpOrder = 1, pinned = false))
        val fixture = SyncFixture(backgroundScope,
            syncDocument(syncRecord("a", 90).copy(lastEpOrder = 9, pinned = true), syncRecord("local-only", 95)), remote)
        val result = assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize(HistorySyncDirection.REMOTE_TO_LOCAL))
        assertEquals(remote.records.single().copy(modifiedAt = 100), fixture.store.document.records.single())
        assertEquals(listOf(HistorySyncTombstone("local-only", 100)), fixture.store.document.tombstones)
        assertEquals(remote, fixture.transport.document)
        assertEquals(0, fixture.transport.puts)
        assertEquals(HistorySyncCounts(uploaded = 0, downloaded = 1, removed = 1), result.counts)
    }

    @Test fun lastSuccessfulTimePersistsForEveryDirectionAndLoadsInANewServiceWithoutItsResult() = runTest {
        for (direction in HistorySyncDirection.entries) {
            val fixture = SyncFixture(backgroundScope)
            assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize(direction))
            assertEquals(100L, fixture.preferences.settings.value.lastSyncedAt)
            fixture.time = 200
            fixture.service.testConnection().getOrThrow()
            fixture.transport.connectionFailure = WebDavResult.Failure(WebDavError.TIMEOUT)
            assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize())
            assertEquals(100L, fixture.preferences.settings.value.lastSyncedAt)
            val restarted = HistorySyncService(fixture.preferences, fixture.passwords, fixture.store, fixture.transport,
                backgroundScope, wifiAvailable = { true }, now = { 300 }, workDispatcher = StandardTestDispatcher(testScheduler))
            testScheduler.runCurrent()
            assertEquals(100L, restarted.state.value.lastSuccessAt)
            assertEquals(100L, restarted.state.value.config.lastSyncedAt)
            assertNull(restarted.state.value.lastResult)
            assertNull(restarted.state.value.lastAttemptAt)
            assertNull(restarted.state.value.configurationError)
            assertEquals(HistorySyncActivity.Idle, restarted.state.value.activity)
        }
    }

    @Test fun aLastSyncTimeWriteFailureIsTypedAndKeepsThePreviousSuccessfulTime() = runTest {
        val fixture = SyncFixture(backgroundScope)
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        fixture.time = 200
        fixture.preferences.beforeLastSyncSave = { throw IOException("private-storage-diagnostic") }
        val failure = assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize())
        assertEquals(WebDavError.STORAGE, failure.error)
        assertTrue(failure.remoteWritten)
        assertEquals(100L, fixture.preferences.settings.value.lastSyncedAt)
        assertEquals(100L, fixture.service.state.value.lastSuccessAt)
        assertEquals(200L, fixture.service.state.value.lastAttemptAt)
        assertEquals(failure, fixture.service.state.value.lastResult)
    }

    @Test fun missingRemoteCanBeCreatedOrExplicitlyUsedAsAnEmptyAuthoritativeHistory() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("a", 2)))
        fixture.transport.missing = true
        fixture.service.synchronize().let { assertIs<HistorySyncOutcome.Success>(it) }
        assertEquals(listOf<WebDavRevision>(WebDavRevision.Missing), fixture.transport.revisions)
        fixture.transport.missing = true
        fixture.service.synchronize(HistorySyncDirection.REMOTE_TO_LOCAL).let { assertIs<HistorySyncOutcome.Success>(it) }
        assertTrue(fixture.store.document.records.isEmpty())
        assertEquals(listOf(HistorySyncTombstone("a", 100)), fixture.store.document.tombstones)
        assertEquals(1, fixture.transport.puts)
    }

    @Test fun a412RereadsAndRemergesExactlyOnceUsingTheNewEtag() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("local", 2)), syncDocument(syncRecord("remote", 3)))
        fixture.transport.putFailures += WebDavResult.Failure(WebDavError.CHANGED_ELSEWHERE, 412)
        fixture.transport.beforePut = { attempt -> if (attempt == 1) {
            fixture.transport.document = syncDocument(syncRecord("new", 4), tombstones = listOf(HistorySyncTombstone("remote", 5)))
            fixture.transport.etag = "\"two\""
        } }
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        assertEquals(2, fixture.transport.gets); assertEquals(2, fixture.transport.puts)
        assertEquals(listOf<WebDavRevision>(WebDavRevision.Existing("\"one\""), WebDavRevision.Existing("\"two\"")), fixture.transport.revisions)
        assertEquals(listOf("local", "new"), fixture.store.document.records.map { it.comicId })
        assertEquals(fixture.store.document, fixture.transport.document)
    }

    @Test fun aSecond412FailsWithoutFurtherRetries() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("local", 2)), syncDocument(syncRecord("remote", 3)))
        repeat(2) { fixture.transport.putFailures += WebDavResult.Failure(WebDavError.CHANGED_ELSEWHERE, 412) }
        val failure = assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize())
        assertEquals(WebDavError.CHANGED_ELSEWHERE, failure.error)
        assertEquals(2, fixture.transport.gets); assertEquals(2, fixture.transport.puts)
        assertTrue(failure.localChanged)
        assertEquals(0, failure.counts.uploaded)
        assertEquals(listOf("local", "remote"), fixture.store.document.records.map { it.comicId })
    }

    @Test fun aFailedUploadKeepsTheCompleteLocalMergeAndReportsNoSuccessfulUploads() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("local", 2)), syncDocument(syncRecord("remote", 3)))
        fixture.transport.putFailures += WebDavResult.Failure(WebDavError.CONNECTION)
        val failure = assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize())
        assertEquals(WebDavError.CONNECTION, failure.error)
        assertTrue(failure.localChanged)
        assertEquals(HistorySyncCounts(0, 1, 0), failure.counts)
        assertEquals(listOf("local", "remote"), fixture.store.document.records.map { it.comicId })
        assertEquals(listOf("remote"), fixture.transport.document.records.map { it.comicId })
        assertEquals(100L, fixture.service.state.value.lastAttemptAt)
        assertNull(fixture.service.state.value.lastSuccessAt)
    }

    @Test fun failedUploadCannotPruneADeletionAndResurrectTheOldRemoteRecordOnRetry() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(tombstones = listOf(HistorySyncTombstone("a", 1_000))),
            syncDocument(syncRecord("a", 1)))
        fixture.time = HistoryMerge.TOMBSTONE_RETENTION_MILLIS + 1_001
        fixture.transport.putFailures += WebDavResult.Failure(WebDavError.CONNECTION)
        assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize())
        assertTrue(fixture.store.document.records.isEmpty())
        assertEquals(1, fixture.store.document.tombstones.size)
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        assertEquals(HistorySyncDocument.EMPTY, fixture.store.document)
        assertEquals(HistorySyncDocument.EMPTY, fixture.transport.document)
    }

    @Test fun aPruningFailureAfterSuccessfulPutDoesNotReportTheRemoteAsUnwritten() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("local", 1)), syncDocument(syncRecord("remote", 2)))
        fixture.store.beforePrune = { throw IOException("cleanup failed") }
        val failure = assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize())
        assertEquals(WebDavError.STORAGE, failure.error)
        assertTrue(failure.remoteWritten)
        assertEquals(1, failure.counts.uploaded)
        assertEquals(fixture.store.document, fixture.transport.document)
    }

    @Test fun corruptAndHigherVersionRemoteFilesLeaveBothSidesUntouchedInEveryDirection() = runTest {
        for (direction in HistorySyncDirection.entries) for ((content, error) in listOf(
            "{" to WebDavError.DAMAGED_FILE, """{"version":2,"newShape":true}""" to WebDavError.NEWER_FORMAT)) {
            val original = syncDocument(syncRecord("local", 2))
            val fixture = SyncFixture(backgroundScope, original)
            fixture.transport.contentOverride = content.toByteArray()
            assertEquals(error, assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize(direction)).error)
            assertEquals(original, fixture.store.document)
            assertEquals(0, fixture.store.commits); assertEquals(0, fixture.transport.puts)
        }
    }

    @Test fun unsupportedRecordAndTombstoneTimesLeaveBothSidesUntouchedInEveryDirection() = runTest {
        val extreme = syncRecord("a", 1)
        val peers = listOf(
            syncDocument(extreme.copy(createdAt = Long.MAX_VALUE)),
            syncDocument(extreme.copy(updatedAt = Long.MAX_VALUE)),
            syncDocument(extreme.copy(modifiedAt = Long.MAX_VALUE)),
            syncDocument(tombstones = listOf(HistorySyncTombstone("a", Long.MAX_VALUE))),
        )
        for (direction in HistorySyncDirection.entries) for (peer in peers) {
            val original = syncDocument(syncRecord("a", 10))
            val fixture = SyncFixture(backgroundScope, original, peer)
            val failure = assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize(direction))
            assertEquals(WebDavError.DAMAGED_FILE, failure.error)
            assertEquals(false, failure.localChanged)
            assertEquals(false, failure.remoteWritten)
            assertEquals(original, fixture.store.document)
            assertEquals(peer, fixture.transport.document)
            assertEquals(0, fixture.store.commits)
            assertEquals(0, fixture.transport.puts)
        }
    }

    @Test fun aPinWithOlderViewingRecencyWinsOnTheOtherDeviceByItsMutationTime() = runTest {
        val pinned = syncRecord("a", 10).copy(modifiedAt = 100, pinned = true)
        val fixture = SyncFixture(backgroundScope, syncDocument(pinned), syncDocument(syncRecord("a", 90)))
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        assertEquals(pinned, fixture.transport.document.records.single())
        assertEquals(pinned, fixture.store.document.records.single())
    }

    @Test fun futureRecordAndTombstoneTimesRejectTheWholeFileBeforeALocalSnapshotInEveryDirection() = runTest {
        val future = 100 + HistorySyncFormat.INCOMING_HORIZON_MILLIS + 1
        val record = syncRecord("remote", 1)
        val peers = listOf(
            syncDocument(record.copy(createdAt = future)),
            syncDocument(record.copy(updatedAt = future)),
            syncDocument(record.copy(modifiedAt = future)),
            syncDocument(tombstones = listOf(HistorySyncTombstone("remote", future))),
        )
        for (direction in HistorySyncDirection.entries) for (peer in peers) {
            val original = syncDocument(syncRecord("local", 10))
            val fixture = SyncFixture(backgroundScope, original, peer)
            val failure = assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize(direction))
            assertEquals(WebDavError.INVALID_TIMESTAMP, failure.error)
            assertEquals(false, failure.localChanged)
            assertEquals(false, failure.remoteWritten)
            assertEquals(original, fixture.store.document)
            assertEquals(peer, fixture.transport.document)
            assertEquals(0, fixture.store.snapshots)
            assertEquals(0, fixture.store.commits)
            assertEquals(0, fixture.transport.puts)
        }
    }

    @Test fun manualSyncDecodesMergesAndEncodesOnTheInjectedWorkerInsteadOfTheCaller() = runTest {
        val caller = Thread.currentThread()
        var decodes = 0
        var merges = 0
        var encodes = 0
        val codec = object : HistorySyncCodec {
            override fun decode(content: ByteArray): HistorySyncDocument {
                assertNotSame(caller, Thread.currentThread())
                assertEquals("history-sync-worker", Thread.currentThread().name)
                decodes++
                return HistorySyncFormat.decode(content)
            }
            override fun encode(document: HistorySyncDocument): ByteArray {
                assertNotSame(caller, Thread.currentThread())
                assertEquals("history-sync-worker", Thread.currentThread().name)
                encodes++
                return HistorySyncFormat.encode(document)
            }
        }
        Executors.newSingleThreadExecutor { task -> Thread(task, "history-sync-worker") }.asCoroutineDispatcher().use { worker ->
            val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("local", 1)),
                syncDocument(syncRecord("remote", 2)), workDispatcher = worker, codec = codec)
            fixture.store.beforeApply = {
                assertNotSame(caller, Thread.currentThread())
                assertSame(worker, currentCoroutineContext()[ContinuationInterceptor])
                merges++
            }
            assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
            assertEquals(fixture.store.document, fixture.transport.document)
            assertEquals(1, decodes)
            assertEquals(1, merges)
            assertEquals(1, encodes)
        }
    }

    @Test fun aLocalWriteDuringDownloadIsIncludedRatherThanOverwrittenFromAnOldSnapshot() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("a", 1)), syncDocument(syncRecord("a", 2)))
        fixture.store.beforeApply = { fixture.store.document = syncDocument(syncRecord("a", 3).copy(pinned = true)) }
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        assertEquals(3L, fixture.store.document.records.single().updatedAt)
        assertTrue(fixture.store.document.records.single().pinned)
    }

    @Test fun cancellationBeforeCommitLeavesNoPartialLocalStateOrLastResult() = runTest {
        val original = syncDocument(syncRecord("local", 1))
        val fixture = SyncFixture(backgroundScope, original, syncDocument(syncRecord("remote", 2)))
        val gate = CompletableDeferred<Unit>()
        fixture.store.beforeCommit = { gate.await() }
        val job = launch { fixture.service.synchronize() }
        testScheduler.runCurrent()
        assertEquals(HistorySyncPhase.WRITING_LOCAL, assertIs<HistorySyncActivity.Syncing>(fixture.service.state.value.activity).phase)
        job.cancelAndJoin()
        assertEquals(original, fixture.store.document)
        assertEquals(0, fixture.store.commits); assertEquals(0, fixture.transport.puts)
        assertNull(fixture.service.state.value.lastResult)
        assertEquals(HistorySyncActivity.Idle, fixture.service.state.value.activity)
    }

    @Test fun cancellationDuringUploadLeavesACompleteLocalMergeAndDoesNotPruneOrPublishAResult() = runTest {
        val fixture = SyncFixture(backgroundScope, syncDocument(syncRecord("local", 1)), syncDocument(syncRecord("remote", 2)))
        val gate = CompletableDeferred<Unit>()
        fixture.transport.beforePut = { gate.await() }
        val job = launch { fixture.service.synchronize() }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("local", "remote"), fixture.store.document.records.map { it.comicId })
        assertNull(fixture.service.state.value.lastResult)
        assertEquals(HistorySyncActivity.Idle, fixture.service.state.value.activity)
    }
}
