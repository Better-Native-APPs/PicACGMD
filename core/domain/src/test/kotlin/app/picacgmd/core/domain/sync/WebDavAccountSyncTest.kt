package app.picacgmd.core.domain.sync

import app.picacgmd.core.data.settings.WebDavSettings
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class WebDavAccountSyncTest {
    @Test fun saveNormalizesTheAccountAndKeepsPasswordOutOfConfigAndDiagnostics() = runTest {
        val fixture = SyncFixture(backgroundScope)
        fixture.service.saveAccount(" HTTPS://localhost/new ", " new-reader ", " 阅读记录 ", "new-private-password").getOrThrow()
        testScheduler.runCurrent()
        val config = fixture.preferences.settings.value
        assertEquals(WebDavSettings("https://localhost/new/", "new-reader", "阅读记录"), config)
        assertEquals(HistorySyncService.accountKey(config), fixture.passwords.value!!.accountKey)
        assertTrue(fixture.service.state.value.hasPassword)
        assertFalse(config.toString().contains("new-private-password"))
        assertFalse(fixture.passwords.value.toString().contains("new-private-password"))
        assertFalse(fixture.service.state.value.toString().contains("new-private-password"))
    }

    @Test fun changingAnAddressOrUsernameRequiresANewPasswordButFolderChangesKeepIt() = runTest {
        val fixture = SyncFixture(backgroundScope)
        val original = fixture.preferences.settings.value
        val password = fixture.passwords.value
        assertEquals(WebDavError.PASSWORD_REQUIRED, assertIs<WebDavResult.Failure>(
            fixture.service.saveAccount("https://localhost/other/", original.username, original.folder)).error)
        assertEquals(WebDavError.PASSWORD_REQUIRED, assertIs<WebDavResult.Failure>(
            fixture.service.saveAccount(original.address, "other-reader", original.folder)).error)
        assertEquals(original, fixture.preferences.settings.value)
        assertEquals(password, fixture.passwords.value)
        fixture.service.saveAccount(original.address, original.username, "other-folder").getOrThrow()
        assertEquals(password, fixture.passwords.value)
    }

    @Test fun invalidInputNeverChangesTheMetadataOrEncryptedPassword() = runTest {
        val fixture = SyncFixture(backgroundScope)
        val original = fixture.preferences.settings.value
        val password = fixture.passwords.value
        val cases = listOf(
            Triple("http://localhost/dav", "reader", "folder") to WebDavError.HTTPS_REQUIRED,
            Triple("localhost", "reader", "folder") to WebDavError.INVALID_ADDRESS,
            Triple(original.address, "reader", "folder/child") to WebDavError.INVALID_FOLDER,
            Triple(original.address, "reader:invalid", "folder") to WebDavError.INVALID_USERNAME,
        )
        cases.forEach { (input, error) ->
            assertEquals(error, assertIs<WebDavResult.Failure>(fixture.service.saveAccount(input.first, input.second, input.third, "private")).error)
        }
        assertEquals(original, fixture.preferences.settings.value)
        assertEquals(password, fixture.passwords.value)
        assertEquals(0, fixture.passwords.saves)
    }

    @Test fun metadataWriteFailureRestoresThePreviousPasswordWithoutLeakingExceptionText() = runTest {
        val fixture = SyncFixture(backgroundScope)
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        val original = fixture.preferences.settings.value
        val password = fixture.passwords.value
        fixture.preferences.beforeSave = { throw IOException("private-password-in-storage-error") }
        val result = fixture.service.saveAccount("https://localhost/new/", "new-reader", "folder", "private-password-in-storage-error")
        assertEquals(WebDavError.STORAGE, assertIs<WebDavResult.Failure>(result).error)
        assertEquals(original, fixture.preferences.settings.value)
        assertEquals(password, fixture.passwords.value)
        assertFalse(result.toString().contains("private-password-in-storage-error"))
        assertEquals(100L, fixture.preferences.settings.value.lastSyncedAt)
    }

    @Test fun onlyCollectionAndUsernameChangesClearTheStoredTimeAndPreviousAccountResult() = runTest {
        val fixture = SyncFixture(backgroundScope)
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        fixture.service.saveAccount("HTTPS://LOCALHOST/dav", " test-reader ", "other-folder", "new-password").getOrThrow()
        fixture.service.setAutoSync(true).getOrThrow()
        fixture.service.setWifiOnly(false).getOrThrow()
        testScheduler.runCurrent()
        assertEquals(100L, fixture.preferences.settings.value.lastSyncedAt)
        assertEquals(100L, fixture.service.state.value.lastSuccessAt)
        assertIs<HistorySyncOutcome.Success>(fixture.service.state.value.lastResult)

        fixture.service.saveAccount("https://localhost/other/", "test-reader", "other-folder", "new-password").getOrThrow()
        assertNull(fixture.preferences.settings.value.lastSyncedAt)
        assertNull(fixture.service.state.value.lastSuccessAt)
        assertNull(fixture.service.state.value.lastAttemptAt)
        assertNull(fixture.service.state.value.lastResult)
        fixture.time = 200
        assertIs<HistorySyncOutcome.Success>(fixture.service.synchronize())
        assertEquals(200L, fixture.preferences.settings.value.lastSyncedAt)
        fixture.service.saveAccount("https://localhost/other/", "other-reader", "other-folder", "new-password").getOrThrow()
        assertNull(fixture.preferences.settings.value.lastSyncedAt)
        assertNull(fixture.service.state.value.lastSuccessAt)
        assertNull(fixture.service.state.value.lastResult)
    }

    @Test fun cancellationFinishesTheAccountPairInsteadOfLeavingAMismatchedPassword() = runTest {
        val fixture = SyncFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        fixture.preferences.beforeSave = { entered.complete(Unit); gate.await() }
        val job = launch { fixture.service.saveAccount("https://localhost/new/", "new-reader", "folder", "new-password") }
        testScheduler.runCurrent()
        assertTrue(entered.isCompleted)
        job.cancel()
        gate.complete(Unit)
        job.join()
        testScheduler.runCurrent()
        val config = fixture.preferences.settings.value
        assertEquals("https://localhost/new/", config.address)
        assertEquals(HistorySyncService.accountKey(config), fixture.passwords.value!!.accountKey)
        assertTrue(fixture.service.state.value.hasPassword)
    }

    @Test fun anUnassociatedSecretIsNeverUsedForAnotherAccount() = runTest {
        val fixture = SyncFixture(backgroundScope)
        fixture.passwords.value = WebDavPassword("different-account", "private")
        testScheduler.runCurrent()
        assertFalse(fixture.service.state.value.hasPassword)
        assertEquals(WebDavError.PASSWORD_REQUIRED, assertIs<HistorySyncOutcome.Failure>(fixture.service.synchronize()).error)
        assertEquals(0, fixture.transport.connections)
    }

    @Test fun emptyInitialMetadataAndNoSecretCannotReportAPasswordAsSet() = runTest {
        val fixture = SyncFixture(backgroundScope)
        fixture.preferences.settings.value = WebDavSettings()
        fixture.passwords.value = null
        testScheduler.runCurrent()
        assertTrue(fixture.service.state.value.ready)
        assertFalse(fixture.service.state.value.hasPassword)
    }

    @Test fun testConnectionIsObservableAndDoesNotBecomeASyncResult() = runTest {
        val fixture = SyncFixture(backgroundScope)
        fixture.service.testConnection().getOrThrow()
        assertEquals(1, fixture.transport.connections)
        assertEquals(0, fixture.transport.gets)
        assertEquals(0, fixture.transport.puts)
        assertEquals(HistorySyncActivity.Idle, fixture.service.state.value.activity)
        assertEquals(null, fixture.service.state.value.lastResult)
    }
}
