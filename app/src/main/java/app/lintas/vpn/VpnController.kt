package app.lintas.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TunnelStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class TunnelTransport {
    SPIKE,
    WIREGUARD,
    MASQUE,
}

data class TunnelState(
    val status: TunnelStatus = TunnelStatus.IDLE,
    val transport: TunnelTransport? = null,
    val packets: Long = 0L,
    val bytes: Long = 0L,
    val netRxPackets: Long = 0L,
    val startedAtMillis: Long? = null,
    val error: String? = null,
)

object VpnController {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    fun start(context: Context, protocol: VpnProtocol = VpnProtocol.WIREGUARD) {
        _state.value = TunnelState(status = TunnelStatus.CONNECTING)
        val action = if (protocol == VpnProtocol.MASQUE) {
            LintasVpnService.ACTION_START_MASQUE
        } else {
            LintasVpnService.ACTION_START
        }
        val intent = Intent(context, LintasVpnService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, LintasVpnService::class.java)
            .setAction(LintasVpnService.ACTION_STOP)
        runCatching { context.startService(intent) }
    }

    fun probe(context: Context) {
        val intent = Intent(context, LintasVpnService::class.java)
            .setAction(LintasVpnService.ACTION_PROBE)
        runCatching { context.startService(intent) }
    }

    internal fun connecting() {
        _state.update {
            it.copy(status = TunnelStatus.CONNECTING, error = null)
        }
    }

    internal fun connected(transport: TunnelTransport) {
        val startedAt = _state.value.startedAtMillis ?: System.currentTimeMillis()
        _state.update {
            it.copy(
                status = TunnelStatus.CONNECTED,
                transport = transport,
                startedAtMillis = startedAt,
                error = null,
            )
        }
    }

    internal fun updateStats(packets: Long, bytes: Long, netRxPackets: Long) {
        _state.update {
            it.copy(
                packets = packets,
                bytes = bytes,
                netRxPackets = netRxPackets,
            )
        }
    }

    internal fun idle() {
        _state.value = TunnelState()
    }

    internal fun error(message: String) {
        _state.value = TunnelState(status = TunnelStatus.ERROR, error = message)
    }
}
