package app.picacgmd.feature.sync

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION")
class HistorySyncConnectivityTest {
    @Test fun wifiTransportIsAcceptedWhetherMeteredOrNotAndCellularIsNotWifi() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        ))
        val active = assertNotNull(manager.activeNetwork)
        val capabilities = NetworkCapabilities()
        val capabilityShadow = shadowOf(capabilities)
        capabilityShadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        capabilityShadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadow.setNetworkCapabilities(active, capabilities)
        assertFalse(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertTrue(isSyncWifiAvailable(context))
        capabilityShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        assertTrue(isSyncWifiAvailable(context))
        capabilityShadow.removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        capabilityShadow.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(isSyncWifiAvailable(context))
        shadow.setActiveNetworkInfo(null)
        assertFalse(isSyncWifiAvailable(context))
    }
}
