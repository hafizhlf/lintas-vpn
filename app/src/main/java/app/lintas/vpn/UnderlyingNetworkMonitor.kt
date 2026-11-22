package app.lintas.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

class UnderlyingNetworkMonitor(
    context: Context,
    private val onChanged: () -> Unit,
) {
    data class Snapshot(val path: Set<String>) {
        val hasNetwork: Boolean
            get() = path.isNotEmpty()
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val networks = mutableMapOf<String, Network>()
    private var selectedKey: String? = null
    private var registered = false

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onChanged()
        }

        override fun onLost(network: Network) {
            onChanged()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            onChanged()
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "available $network")
            consider(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            consider(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            remove(network)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return
        connectivity.registerNetworkCallback(request, callback)
        connectivity.registerDefaultNetworkCallback(defaultCallback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
        synchronized(lock) {
            networks.clear()
            selectedKey = null
        }
    }

    fun snapshot(): Snapshot {
        val selected = synchronized(lock) {
            refreshSelectionLocked()
            selectedKey
        }
        return Snapshot(if (selected != null) setOf(selected) else emptySet())
    }

    fun selectedNetwork(): Network? {
        return synchronized(lock) {
            refreshSelectionLocked()
            selectedKey?.let { networks[it] }
        }
    }

    @SuppressLint("MissingPermission")
    private fun consider(
        network: Network,
        capabilities: NetworkCapabilities? = connectivity.getNetworkCapabilities(network),
    ) {
        val key = networkKey(network)
        val usable = capabilities != null && isUsable(capabilities)
        val changed = synchronized(lock) {
            val before = selectedKey
            if (usable) networks[key] = network else networks.remove(key)
            refreshSelectionLocked()
            before != selectedKey
        }
        if (changed) {
            Log.i(TAG, "selected ${selectedKey ?: "none"}")
            onChanged()
        }
    }

    private fun remove(network: Network) {
        val key = networkKey(network)
        val changed = synchronized(lock) {
            val before = selectedKey
            networks.remove(key)
            refreshSelectionLocked()
            before != selectedKey
        }
        Log.i(TAG, "lost $key changed=$changed")
        if (changed) onChanged()
    }

    @SuppressLint("MissingPermission")
    private fun refreshSelectionLocked() {
        if (networks.isEmpty()) {
            selectedKey = null
            return
        }
        val current = selectedKey?.let { key ->
            networks[key]?.let { network -> network to connectivity.getNetworkCapabilities(network) }
        }
        val currentCaps = current?.second?.takeIf { isUsable(it) }
        if (currentCaps == null) {
            selectedKey = chooseBestLocked()?.first
            return
        }
        val best = chooseBestLocked() ?: return
        val bestCaps = best.second
        if (priority(bestCaps) > priority(currentCaps)) {
            selectedKey = best.first
        }
    }

    @SuppressLint("MissingPermission")
    private fun chooseBestLocked(): Pair<String, NetworkCapabilities>? {
        return networks.mapNotNull { (key, network) ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!isUsable(capabilities)) return@mapNotNull null
            Triple(key, capabilities, capabilities.linkDownstreamBandwidthKbps)
        }.maxWithOrNull(
            compareBy<Triple<String, NetworkCapabilities, Int>> { priority(it.second) }
                .thenBy { it.third },
        )?.let { it.first to it.second }
    }

    private fun priority(capabilities: NetworkCapabilities): Int {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 5
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 4
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 3
            else -> 1
        }
    }

    private fun isUsable(capabilities: NetworkCapabilities): Boolean {
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun networkKey(network: Network): String = network.toString()

    private companion object {
        const val TAG = "LintasNet"
        val request: NetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    }
}
