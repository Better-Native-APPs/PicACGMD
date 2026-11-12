package app.picacgmd.core.domain.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun wifiRuleBlocks(wifiOnly: Boolean, network: DownloadNetworkState): Boolean = wifiOnly && !network.wifi

internal fun currentDownloadNetwork(context: Context): DownloadNetworkState {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
    return DownloadNetworkState(
        connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
    )
}

internal fun observeDownloadNetwork(context: Context): Flow<DownloadNetworkState> = callbackFlow {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    fun publish() {
        trySend(currentDownloadNetwork(context))
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { publish() }
        override fun onLost(network: Network) { publish() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { publish() }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    publish()
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
