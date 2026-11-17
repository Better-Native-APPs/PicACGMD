package app.picacgmd.feature.sync

import app.picacgmd.core.data.settings.WebDavSettings
import app.picacgmd.core.domain.sync.HistorySyncActivity
import app.picacgmd.core.domain.sync.HistorySyncCounts
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncOutcome
import app.picacgmd.core.domain.sync.HistorySyncPhase
import app.picacgmd.core.network.webdav.WebDavConnection
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class WebDavSyncViewModelTest {
    @Test fun initialConfigAndPasswordPresenceLoadWithoutExposingThePassword() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        assertEquals(WebDavAccountDraft("https://localhost/dav/", "test-reader"), viewModel.state.value.account)
        assertTrue(viewModel.state.value.ready); assertTrue(viewModel.state.value.hasPassword)
        assertFalse(viewModel.state.value.autoSync); assertTrue(viewModel.state.value.wifiOnly)
        assertFalse(viewModel.state.value.busy)
    }

    @Test fun storedTimeLoadsIntoANewViewModelWithoutRestoringCountsOrErrorsAndAccountChangesClearIt() = runTest {
        val actions = FakeSyncActions(WebDavSettings("https://localhost/dav/", "test-reader", lastSyncedAt = 456))
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        assertEquals(456L, viewModel.state.value.lastSyncedAt)
        assertNull(viewModel.state.value.lastAttemptAt)
        assertNull(viewModel.state.value.lastResult)
        assertNull(viewModel.state.value.error)
        assertNull(viewModel.state.value.message)
        viewModel.editAddress("https://localhost/other/")
        viewModel.saveAccount("new-password")
        testScheduler.runCurrent()
        assertNull(viewModel.state.value.lastSyncedAt)
        assertNull(actions.state.value.config.lastSyncedAt)
    }

    @Test fun editingAndSavingUsesTransientPasswordInputAndClearsTheDirtyFlag() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.editAddress("https://localhost/new/"); viewModel.editUsername("new-reader"); viewModel.editFolder("阅读记录")
        assertTrue(viewModel.state.value.dirty)
        viewModel.saveAccount("private-panel-password")
        testScheduler.runCurrent()
        assertEquals("private-panel-password", actions.savedPassword)
        assertEquals(WebDavAccountDraft("https://localhost/new/", "new-reader", "阅读记录"), viewModel.state.value.account)
        assertFalse(viewModel.state.value.dirty); assertFalse(viewModel.state.value.saving)
        assertEquals("已保存 WebDAV 账号", viewModel.state.value.message)
        assertFalse(viewModel.state.value.toString().contains("private-panel-password"))
        viewModel.clearMessage()
        assertNull(viewModel.state.value.message)
    }

    @Test fun editsMadeDuringSaveAreNotDiscardedByItsCompletion() = runTest {
        val actions = FakeSyncActions()
        val gate = CompletableDeferred<Unit>()
        actions.beforeSave = { gate.await() }
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.editFolder("first"); viewModel.saveAccount()
        testScheduler.runCurrent()
        assertTrue(viewModel.state.value.busy)
        viewModel.editFolder("second")
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("second", viewModel.state.value.account.folder)
        assertTrue(viewModel.state.value.dirty)
    }

    @Test fun discardingEditsReturnsToTheStoredAccountAndASaveUnderWayKeepsItsDraft() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        val stored = WebDavAccountDraft("https://localhost/dav/", "test-reader")
        viewModel.editAddress("https://localhost/typo/"); viewModel.editUsername("someone-else")
        assertEquals(stored, viewModel.state.value.savedAccount)
        viewModel.discardEdits()
        assertEquals(stored, viewModel.state.value.account)
        assertFalse(viewModel.state.value.dirty)

        val gate = CompletableDeferred<Unit>()
        actions.beforeSave = { gate.await() }
        viewModel.editFolder("kept"); viewModel.saveAccount()
        testScheduler.runCurrent()
        viewModel.discardEdits()
        assertEquals("kept", viewModel.state.value.account.folder)
        gate.complete(Unit); testScheduler.runCurrent()
        assertEquals(WebDavAccountDraft("https://localhost/dav/", "test-reader", "kept"), viewModel.state.value.savedAccount)
        assertEquals(viewModel.state.value.savedAccount, viewModel.state.value.account)
        assertFalse(viewModel.state.value.dirty)
    }

    @Test fun everyCompletedSaveIsCountedAndAFailedOneIsNot() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.saveAccount(); testScheduler.runCurrent()
        assertEquals(1, viewModel.state.value.accountSaves)
        actions.saveResult = WebDavResult.Failure(WebDavError.STORAGE)
        viewModel.saveAccount(); testScheduler.runCurrent()
        assertEquals(1, viewModel.state.value.accountSaves)
        actions.saveResult = WebDavResult.Success(Unit)
        viewModel.saveAccount(); testScheduler.runCurrent()
        assertEquals(2, viewModel.state.value.accountSaves)
    }

    @Test fun automaticSwitchesAreSavedIndependentlyOfTheAccountDraft() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.editAddress("https://localhost/unsaved/")
        viewModel.setAutoSync(true); testScheduler.runCurrent()
        viewModel.setWifiOnly(false); testScheduler.runCurrent()
        assertTrue(viewModel.state.value.autoSync); assertFalse(viewModel.state.value.wifiOnly)
        assertEquals("https://localhost/dav/", actions.state.value.config.address)
        assertEquals("https://localhost/unsaved/", viewModel.state.value.account.address)
    }

    @Test fun testConnectionReportsCreationOrExistingFolderAndShowsTestingProgress() = runTest {
        val actions = FakeSyncActions()
        val gate = CompletableDeferred<Unit>()
        actions.beforeTest = { gate.await() }
        actions.connectionResult = WebDavResult.Success(WebDavConnection(true))
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.testConnection(); testScheduler.runCurrent()
        assertEquals(HistorySyncActivity.Testing, viewModel.state.value.progress)
        assertTrue(viewModel.state.value.busy)
        viewModel.testConnection()
        gate.complete(Unit); testScheduler.runCurrent()
        assertEquals(1, actions.connections)
        assertEquals("连接成功，已创建同步文件夹", viewModel.state.value.message)
        actions.connectionResult = WebDavResult.Success(WebDavConnection(false))
        viewModel.testConnection(); testScheduler.runCurrent()
        assertEquals("连接成功", viewModel.state.value.message)
    }

    @Test fun allThreeManualOperationsExposeTheDirectionProgressCountsAndLastTime() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        val gate = CompletableDeferred<Unit>()
        actions.beforeSync = { gate.await() }
        viewModel.syncNow(); testScheduler.runCurrent()
        assertEquals(HistorySyncActivity.Syncing(HistorySyncDirection.MERGE, HistorySyncPhase.DOWNLOADING, false), viewModel.state.value.progress)
        viewModel.syncNow()
        gate.complete(Unit); testScheduler.runCurrent()
        viewModel.overwriteRemote(); testScheduler.runCurrent()
        viewModel.overwriteLocal(); testScheduler.runCurrent()
        assertEquals(HistorySyncDirection.entries.toList(), actions.directions)
        assertTrue(actions.automatic.none { it })
        assertEquals(HistorySyncCounts(2, 3, 4), viewModel.state.value.lastResult!!.counts)
        assertEquals(123L, viewModel.state.value.lastSyncedAt)
        assertEquals(123L, viewModel.state.value.lastAttemptAt)
        assertEquals("同步完成", viewModel.state.value.message)
        assertFalse(viewModel.state.value.busy)
    }

    @Test fun everyTypedErrorHasChineseCopyAndManualFailuresCanBeDismissed() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        WebDavError.entries.forEach { error ->
            actions.connectionResult = WebDavResult.Failure(error, if (error == WebDavError.HTTP_ERROR) 500 else null)
            viewModel.testConnection(); testScheduler.runCurrent()
            assertEquals(error.message(if (error == WebDavError.HTTP_ERROR) 500 else null), viewModel.state.value.error)
            assertTrue(viewModel.state.value.error!!.any { it in '\u4e00'..'\u9fff' })
            viewModel.clearError(); assertNull(viewModel.state.value.error)
        }
    }

    @Test fun saveAndSwitchErrorsMapWithoutDiscardingDraftOrChangingStoredSwitches() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.editAddress("http://localhost/dav")
        actions.saveResult = WebDavResult.Failure(WebDavError.HTTPS_REQUIRED)
        viewModel.saveAccount("private"); testScheduler.runCurrent()
        assertEquals("仅支持 HTTPS 地址", viewModel.state.value.error)
        assertTrue(viewModel.state.value.dirty)
        actions.switchResult = WebDavResult.Failure(WebDavError.STORAGE)
        viewModel.setAutoSync(true); testScheduler.runCurrent()
        assertFalse(viewModel.state.value.autoSync)
        assertEquals(WebDavError.STORAGE.message(), viewModel.state.value.error)
    }

    @Test fun manualUploadFailureExplainsTheRetainedLocalMerge() = runTest {
        val actions = FakeSyncActions()
        actions.syncResult = HistorySyncOutcome.Failure(WebDavError.CONNECTION, counts = HistorySyncCounts(0, 2, 1), localChanged = true)
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.syncNow(); testScheduler.runCurrent()
        assertEquals("无法连接服务器。本机记录已更新，云端未更新", viewModel.state.value.error)
        assertEquals(HistorySyncCounts(0, 2, 1), viewModel.state.value.lastResult!!.counts)
        assertFalse(viewModel.state.value.lastResult!!.successful)
    }

    @Test fun aFutureTimestampRejectionExplainsClockProblemsWithoutClaimingRecordsWereChanged() = runTest {
        val actions = FakeSyncActions()
        actions.syncResult = HistorySyncOutcome.Failure(WebDavError.INVALID_TIMESTAMP)
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.syncNow(); testScheduler.runCurrent()
        assertEquals("云端记录的时间不正常，请检查各设备的系统时间", viewModel.state.value.error)
        assertEquals(viewModel.state.value.error, viewModel.state.value.lastResult!!.message)
        assertFalse(viewModel.state.value.lastResult!!.localChanged)
        assertFalse(viewModel.state.value.lastResult!!.remoteWritten)
    }

    @Test fun automaticFailureUpdatesLastResultButNeverCreatesABlockingError() = runTest {
        val actions = FakeSyncActions()
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        actions.state.value = actions.state.value.copy(lastAttemptAt = 456, lastResult = HistorySyncOutcome.Failure(WebDavError.TIMEOUT))
        testScheduler.runCurrent()
        assertNull(viewModel.state.value.error)
        assertEquals("连接超时，请重试", viewModel.state.value.lastResult!!.message)
        assertEquals(456L, viewModel.state.value.lastAttemptAt)
    }

    @Test fun postUploadCleanupFailureReportsThatTheRemoteWasAlreadyUpdated() = runTest {
        val actions = FakeSyncActions()
        actions.syncResult = HistorySyncOutcome.Failure(WebDavError.STORAGE, counts = HistorySyncCounts(1, 2, 0),
            localChanged = true, remoteWritten = true)
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.syncNow(); testScheduler.runCurrent()
        assertEquals("读写同步数据失败，请重试。云端记录已更新，本机的后续处理未完成", viewModel.state.value.error)
        assertTrue(viewModel.state.value.lastResult!!.remoteWritten)
    }

    @Test fun rawExceptionTextAndAnInvalidAddressPasswordCannotLeakThroughStateDiagnostics() = runTest {
        val actions = FakeSyncActions()
        actions.beforeTest = { throw IOException("private-diagnostic-value") }
        val viewModel = WebDavSyncViewModel(actions, backgroundScope)
        testScheduler.runCurrent()
        viewModel.editAddress("https://reader:private-diagnostic-value@localhost/dav")
        viewModel.testConnection(); testScheduler.runCurrent()
        assertEquals(WebDavError.STORAGE.message(), viewModel.state.value.error)
        assertFalse(viewModel.state.value.toString().contains("private-diagnostic-value"))
    }
}
