package app.picacgmd.feature.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal fun isSyncWifiAvailable(context: Context): Boolean {
    val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
}
