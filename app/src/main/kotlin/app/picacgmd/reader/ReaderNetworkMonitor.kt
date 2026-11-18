package app.picacgmd.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class ReaderNetworkState(val dataSaverEnabled: Boolean, val metered: Boolean)

internal class ReaderNetworkMonitor(context: Context) {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)

    fun currentState(): ReaderNetworkState = readState(
        connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities),
    )

    private fun readState(capabilities: NetworkCapabilities?): ReaderNetworkState = ReaderNetworkState(
        dataSaverEnabled = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
    )

    val states: Flow<ReaderNetworkState> = callbackFlow {
        fun publish() { trySend(currentState()) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { publish() }
            override fun onLost(network: Network) { publish() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(readState(capabilities))
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { publish() }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        connectivity.registerDefaultNetworkCallback(callback)
        publish()
        awaitClose {
            connectivity.unregisterNetworkCallback(callback)
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()
}

@Composable
internal fun rememberReaderNetworkMonitor(): ReaderNetworkMonitor {
    val context = LocalContext.current.applicationContext
    return remember(context) { ReaderNetworkMonitor(context) }
}

@Composable
internal fun rememberReaderNetworkState(monitor: ReaderNetworkMonitor): ReaderNetworkState {
    val state by monitor.states.collectAsStateWithLifecycle(initialValue = monitor.currentState())
    return state
}
