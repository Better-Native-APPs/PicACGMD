package app.picacgmd.feature.sync

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import app.picacgmd.core.data.settings.WebDavSettings
import app.picacgmd.core.domain.sync.HistorySyncDirection
import app.picacgmd.core.domain.sync.HistorySyncDocument
import app.picacgmd.core.domain.sync.HistorySyncOutcome
import app.picacgmd.core.domain.sync.HistorySyncService
import app.picacgmd.core.domain.sync.HistorySyncStore
import app.picacgmd.core.domain.sync.WebDavPassword
import app.picacgmd.core.domain.sync.WebDavPasswordStore
import app.picacgmd.core.domain.sync.WebDavPreferences
import app.picacgmd.core.network.webdav.WebDavAccount
import app.picacgmd.core.network.webdav.WebDavConnection
import app.picacgmd.core.network.webdav.WebDavRemoteFile
import app.picacgmd.core.network.webdav.WebDavResult
import app.picacgmd.core.network.webdav.WebDavRevision
import app.picacgmd.core.network.webdav.WebDavTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION")
@OptIn(ExperimentalCoroutinesApi::class)
class HistorySyncConnectivityTest {
    @Test fun meteredWifiBlocksAutomaticSync() = runTest {
        setActiveNetwork(NetworkCapabilities.TRANSPORT_WIFI, unmetered = false)
        val fixture = syncFixture()

        fixture.service.requestAutomaticSync()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(HistorySyncService.AUTOMATIC_DEBOUNCE_MILLIS)
        testScheduler.runCurrent()

        assertEquals(0, fixture.transport.connections)
        assertEquals(0, fixture.transport.uploads)
        assertNull(fixture.service.state.value.lastResult)
        assertEquals(HistorySyncOutcome.Skipped, fixture.service.synchronize(automatic = true))
    }

    @Test fun unmeteredNonWifiAllowsAutomaticSync() = runTest {
        for (transport in listOf(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET)) {
            setActiveNetwork(transport, unmetered = true)
            val fixture = syncFixture()

            fixture.service.requestAutomaticSync()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(HistorySyncService.AUTOMATIC_DEBOUNCE_MILLIS)
            testScheduler.runCurrent()

            assertEquals(1, fixture.transport.connections)
            assertEquals(1, fixture.transport.uploads)
            assertEquals(HistorySyncOutcome.Success(app.picacgmd.core.domain.sync.HistorySyncCounts()), fixture.service.state.value.lastResult)
        }
    }

    @Test fun disablingTheRestrictionAllowsAutomaticSyncOnMeteredNetworks() = runTest {
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR)) {
            setActiveNetwork(transport, unmetered = false)
            val fixture = syncFixture()
            fixture.service.setWifiOnly(false).getOrThrow()

            fixture.service.requestAutomaticSync()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(HistorySyncService.AUTOMATIC_DEBOUNCE_MILLIS)
            testScheduler.runCurrent()

            assertEquals(1, fixture.transport.connections)
            assertEquals(1, fixture.transport.uploads)
        }
    }

    @Test fun aMissingActiveNetworkIsNotUnmetered() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        assertFalse(isSyncWifiAvailable(context))
    }

    private fun setActiveNetwork(transport: Int, unmetered: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        val type = when (transport) {
            NetworkCapabilities.TRANSPORT_WIFI -> ConnectivityManager.TYPE_WIFI
            NetworkCapabilities.TRANSPORT_ETHERNET -> ConnectivityManager.TYPE_ETHERNET
            else -> ConnectivityManager.TYPE_MOBILE
        }
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, type, 0, true, true,
        ))
        val active = assertNotNull(manager.activeNetwork)
        val capabilities = NetworkCapabilities()
        val capabilityShadow = shadowOf(capabilities)
        capabilityShadow.addTransportType(transport)
        capabilityShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (unmetered) capabilityShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        else capabilityShadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadow.setNetworkCapabilities(active, capabilities)
    }

    private suspend fun TestScope.syncFixture(): SyncFixture {
        val preferences = object : WebDavPreferences {
            override val settings = MutableStateFlow(WebDavSettings(autoSync = true))
            override suspend fun saveAccount(address: String, username: String, folder: String) {
                settings.value = settings.value.copy(address = address, username = username, folder = folder)
            }
            override suspend fun setAutoSync(enabled: Boolean) { settings.value = settings.value.copy(autoSync = enabled) }
            override suspend fun setWifiOnly(enabled: Boolean) { settings.value = settings.value.copy(wifiOnly = enabled) }
            override suspend fun setLastSyncedAt(timestamp: Long) { settings.value = settings.value.copy(lastSyncedAt = timestamp) }
        }
        val passwords = object : WebDavPasswordStore {
            private var value: WebDavPassword? = null
            override suspend fun read(): WebDavPassword? = value
            override suspend fun save(password: WebDavPassword) { value = password }
            override suspend fun clear() { value = null }
        }
        val store = object : HistorySyncStore {
            override suspend fun snapshot(): HistorySyncDocument = HistorySyncDocument.EMPTY
            override suspend fun apply(remote: HistorySyncDocument, direction: HistorySyncDirection, now: Long): HistorySyncDocument = remote
            override suspend fun pruneTombstones(before: Long) = Unit
        }
        val transport = RecordingTransport()
        val service = HistorySyncService(preferences, passwords, store, transport, backgroundScope,
            wifiAvailable = { isSyncWifiAvailable(RuntimeEnvironment.getApplication()) },
            now = { testScheduler.currentTime }, workDispatcher = StandardTestDispatcher(testScheduler))
        service.saveAccount("https://localhost/dav/", "test-reader", "PicACGMD", "test-password").getOrThrow()
        return SyncFixture(service, transport)
    }

    private class SyncFixture(val service: HistorySyncService, val transport: RecordingTransport)

    private class RecordingTransport : WebDavTransport {
        var connections = 0
        var uploads = 0
        override suspend fun testConnection(account: WebDavAccount): WebDavResult<WebDavConnection> {
            connections++
            return WebDavResult.Success(WebDavConnection(false))
        }
        override suspend fun getHistory(account: WebDavAccount): WebDavResult<WebDavRemoteFile> =
            WebDavResult.Success(WebDavRemoteFile(null, WebDavRevision.Missing))
        override suspend fun putHistory(account: WebDavAccount, content: ByteArray, revision: WebDavRevision): WebDavResult<Unit> {
            uploads++
            return WebDavResult.Success(Unit)
        }
    }
}
