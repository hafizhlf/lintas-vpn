package app.lintas.vpn

import android.content.Context

enum class VpnProtocol(val wireValue: String, val label: String) {
    WIREGUARD("wireguard", "WireGuard"),
    MASQUE("masque", "MASQUE");

    companion object {
        fun fromWire(value: String?): VpnProtocol =
            entries.firstOrNull { it.wireValue == value } ?: WIREGUARD
    }
}

object VpnPreferences {
    private const val PREFS = "lintas_settings"
    private const val KEY_PROTOCOL = "vpn_protocol"
    private const val KEY_EXCLUDED_APPS = "split_tunnel_excluded_apps"

    fun loadProtocol(context: Context): VpnProtocol {
        val value = prefs(context).getString(KEY_PROTOCOL, null)
        return VpnProtocol.fromWire(value)
    }

    fun saveProtocol(context: Context, protocol: VpnProtocol) {
        prefs(context).edit()
            .putString(KEY_PROTOCOL, protocol.wireValue)
            .apply()
    }

    fun loadExcludedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet().orEmpty()

    fun saveExcludedApps(context: Context, packages: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_EXCLUDED_APPS, packages)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
