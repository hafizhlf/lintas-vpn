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

    fun loadProtocol(context: Context): VpnProtocol {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROTOCOL, null)
        return VpnProtocol.fromWire(value)
    }

    fun saveProtocol(context: Context, protocol: VpnProtocol) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROTOCOL, protocol.wireValue)
            .apply()
    }
}
