package app.lintas.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class UnderlyingNetworkMonitor(
    context: Context,
    private val onChanged: () -> Unit,
) {
    data class Snapshot(val validated: Set<Network>) {
        val hasValidatedNetwork: Boolean
            get() = validated.isNotEmpty()
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val validated = mutableSetOf<Network>()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            consider(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            consider(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            val changed = synchronized(lock) {
                validated.remove(network)
            }
            if (changed) onChanged()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return
        connectivity.registerNetworkCallback(request, callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        synchronized(lock) { validated.clear() }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(validated.toSet())
    }

    @SuppressLint("MissingPermission")
    private fun consider(
        network: Network,
        capabilities: NetworkCapabilities? = connectivity.getNetworkCapabilities(network),
    ) {
        val usable = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val changed = synchronized(lock) {
            if (usable) validated.add(network) else validated.remove(network)
        }
        if (changed) onChanged()
    }

    private companion object {
        val request: NetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    }
}
