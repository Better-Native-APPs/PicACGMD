package app.picacgmd.core.domain.sync

import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryAutomaticSyncTest {
    @Test fun severalTriggersWithinThirtySecondsMakeOneTrailingEdgeRun() = runTest {
        val fixture = SyncFixture(backgroundScope, autoSync = true)
        fixture.service.requestAutomaticSync()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000)
        fixture.service.requestAutomaticSync()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000)
        fixture.service.requestAutomaticSync()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(29_999)
        testScheduler.runCurrent()
        assertEquals(0, fixture.transport.connections)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(1, fixture.transport.connections)
        assertEquals(1, fixture.transport.puts)
    }

    @Test fun disabledAutomaticSyncDoesNotRunOrReplaceTheLastResult() = runTest {
        val fixture = SyncFixture(backgroundScope)
        fixture.service.requestAutomaticSync()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()
        assertEquals(0, fixture.transport.connections)
        assertNull(fixture.service.state.value.lastResult)
        assertEquals(HistorySyncOutcome.Skipped, fixture.service.synchronize(automatic = true))
    }

    @Test fun wifiOnlyAppliesToAutomaticRunsAndManualSyncIgnoresIt() = runTest {
        val fixture = SyncFixture(backgroundScope, autoSync = true)
        fixture.wifi = false
        assertEquals(HistorySyncOutcome.Skipped, fixture.service.synchronize(automatic = true))
        assertEquals(1, fixture.wifiChecks)
        assertEquals(0, fixture.transport.connections)
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        assertEquals(1, fixture.transport.connections)
        assertEquals(1, fixture.wifiChecks)
        fixture.service.setWifiOnly(false).getOrThrow()
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize(automatic = true))
        assertEquals(2, fixture.transport.connections)
        assertEquals(1, fixture.wifiChecks)
    }

    @Test fun disablingDuringTheDebounceCancelsThePendingAutomaticRunAtItsGate() = runTest {
        val fixture = SyncFixture(backgroundScope, autoSync = true)
        fixture.service.requestAutomaticSync()
        testScheduler.runCurrent()
        fixture.service.setAutoSync(false).getOrThrow()
        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()
        assertEquals(0, fixture.transport.connections)
    }

    @Test fun automaticFailuresAreRecordedAsNonBlockingLastResultsWithATime() = runTest {
        val fixture = SyncFixture(backgroundScope, autoSync = true)
        fixture.transport.connectionFailure = WebDavResult.Failure(WebDavError.AUTHENTICATION, 401)
        fixture.service.requestAutomaticSync()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()
        assertEquals(WebDavError.AUTHENTICATION, assertIs<HistorySyncOutcome.Failure>(fixture.service.state.value.lastResult).error)
        assertEquals(100L, fixture.service.state.value.lastAttemptAt)
        assertEquals(HistorySyncActivity.Idle, fixture.service.state.value.activity)
    }

    @Test fun aStartupGateFailureIsRetainedWithoutStartingAnOperation() = runTest {
        val fixture = SyncFixture(backgroundScope, autoSync = true)
        fixture.service.recordAutomaticFailure(WebDavError.STORAGE)
        assertEquals(WebDavError.STORAGE, assertIs<HistorySyncOutcome.Failure>(fixture.service.state.value.lastResult).error)
        assertEquals(100L, fixture.service.state.value.lastAttemptAt)
        assertEquals(HistorySyncActivity.Idle, fixture.service.state.value.activity)
        assertEquals(0, fixture.transport.connections)
    }

    @Test fun manualTestingAndAutomaticSyncShareOneOperationMutex() = runTest {
        val fixture = SyncFixture(backgroundScope, autoSync = true)
        val gate = CompletableDeferred<Unit>()
        var activePuts = 0
        var peakPuts = 0
        fixture.transport.beforePut = {
            activePuts++
            peakPuts = maxOf(peakPuts, activePuts)
            try { gate.await() } finally { activePuts-- }
        }
        val manual = async { fixture.service.synchronize() }
        testScheduler.runCurrent()
        assertEquals(1, fixture.transport.connections)
        fixture.service.requestAutomaticSync()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()
        val test = async { fixture.service.testConnection() }
        testScheduler.runCurrent()
        assertEquals(1, fixture.transport.connections)
        assertEquals(1, fixture.transport.puts)
        gate.complete(Unit)
        assertIs<HistorySyncOutcome.Success>(manual.await())
        test.await().getOrThrow()
        testScheduler.runCurrent()
        assertEquals(3, fixture.transport.connections)
        assertEquals(2, fixture.transport.puts)
        assertEquals(1, peakPuts)
    }
}
