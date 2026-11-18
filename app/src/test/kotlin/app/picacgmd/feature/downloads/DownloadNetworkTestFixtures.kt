package app.picacgmd.feature.downloads

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import kotlin.test.assertNotNull
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo

@Suppress("DEPRECATION")
internal fun setDownloadTestNetwork(transport: Int, unmetered: Boolean) {
    val manager = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
    val type = when (transport) {
        NetworkCapabilities.TRANSPORT_WIFI -> ConnectivityManager.TYPE_WIFI
        NetworkCapabilities.TRANSPORT_ETHERNET -> ConnectivityManager.TYPE_ETHERNET
        else -> ConnectivityManager.TYPE_MOBILE
    }
    val shadow = shadowOf(manager)
    shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED, type, 0, true, true,
    ))
    val capabilities = NetworkCapabilities()
    val capabilityShadow = shadowOf(capabilities)
    capabilityShadow.addTransportType(transport)
    capabilityShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    capabilityShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    if (unmetered) capabilityShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    else capabilityShadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    shadow.setNetworkCapabilities(assertNotNull(manager.activeNetwork), capabilities)
}
