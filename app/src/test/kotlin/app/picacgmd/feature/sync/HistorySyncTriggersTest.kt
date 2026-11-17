package app.picacgmd.feature.sync

import app.picacgmd.reader.ReaderPersistence
import app.picacgmd.testing.awaitRealTime
import app.picacgmd.core.network.webdav.WebDavError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class HistorySyncTriggersTest {
    @Test fun appStartWaitsForSessionReadinessAndSchedulesOnce() = runTest {
        val actions = FakeSyncActions()
        val ready = CompletableDeferred<Unit>()
        val job = launch { requestHistorySyncAfterSessionReady(actions, { ready.await() }, { error("Unexpected trigger failure") }) }
        testScheduler.runCurrent()
        assertEquals(0, actions.automaticRequests)
        ready.complete(Unit); job.join()
        assertEquals(1, actions.automaticRequests)
    }

    @Test fun aFailedReadinessGateReportsATypedFailureInsteadOfCrashingTheAppStartJob() = runTest {
        val actions = FakeSyncActions()
        val failures = mutableListOf<WebDavError>()
        val job = launch { requestHistorySyncAfterSessionReady(actions,
            { throw IOException("private-readiness-error") }, failures::add) }
        job.join()
        assertEquals(listOf(WebDavError.STORAGE), failures)
        assertEquals(0, actions.automaticRequests)
    }

    @Test fun cancellationWhileWaitingForReadinessDoesNotTriggerOrRecordAFailure() = runTest {
        val actions = FakeSyncActions()
        val ready = CompletableDeferred<Unit>()
        val failures = mutableListOf<WebDavError>()
        val job = launch { requestHistorySyncAfterSessionReady(actions, { ready.await() }, failures::add) }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, actions.automaticRequests)
        assertTrue(failures.isEmpty())
    }

    @Test fun readerCloseTriggersAfterAlreadyQueuedProgressWrites() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val triggered = CompletableDeferred<Unit>()
        ReaderPersistence.bindHistorySync { triggered.complete(Unit) }
        try {
            ReaderPersistence.launch(onError = { throw it }) { entered.complete(Unit); finishWrite.await() }
            awaitRealTime { entered.await() }
            ReaderPersistence.readerClosed()
            assertFalse(triggered.isCompleted)
            finishWrite.complete(Unit)
            awaitRealTime { triggered.await() }
        } finally { finishWrite.complete(Unit); ReaderPersistence.bindHistorySync {} }
    }
}
