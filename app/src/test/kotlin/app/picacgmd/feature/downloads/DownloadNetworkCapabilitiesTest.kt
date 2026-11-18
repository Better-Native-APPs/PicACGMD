package app.picacgmd.feature.downloads

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.domain.download.DownloadManager
import app.picacgmd.testing.MemoryPreferencesStore
import app.picacgmd.testing.ReadOnlyDownloadDao
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadNetworkCapabilitiesTest {
    @Test fun meteredWifiMakesRestrictedDownloadsWait() = runTest {
        setDownloadTestNetwork(NetworkCapabilities.TRANSPORT_WIFI, unmetered = false)

        assertTrue(downloads(restricted = true).waitingForUnmetered.first())
        assertFalse(downloads(restricted = false).waitingForUnmetered.first())
    }

    @Test fun unmeteredNonWifiAllowsRestrictedDownloads() = runTest {
        for (transport in listOf(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET)) {
            setDownloadTestNetwork(transport, unmetered = true)

            assertFalse(downloads(restricted = true).waitingForUnmetered.first())
        }
    }

    @Test fun disablingTheRestrictionAllowsMeteredNetworks() = runTest {
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR)) {
            setDownloadTestNetwork(transport, unmetered = false)

            assertFalse(downloads(restricted = false).waitingForUnmetered.first())
        }
    }

    @Test fun aMissingActiveNetworkMakesRestrictedDownloadsWait() = runTest {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)

        assertTrue(downloads(restricted = true).waitingForUnmetered.first())
    }

    private fun TestScope.downloads(restricted: Boolean): DownloadManager = DownloadManager(
        apiProvider = { error("Checking a network must not request chapter metadata") },
        imageClientProvider = { error("Checking a network must not download images") },
        dao = ReadOnlyDownloadDao(),
        settingsRepository = SettingsRepository(MemoryPreferencesStore(), AppSettings(downloadWifiOnly = restricted)),
        context = RuntimeEnvironment.getApplication(), scope = backgroundScope,
    )
}
