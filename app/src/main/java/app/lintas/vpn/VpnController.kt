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

enum class ProbeStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILURE,
}

data class ProbeState(
    val status: ProbeStatus = ProbeStatus.IDLE,
    val latencyMs: Long? = null,
    val message: String? = null,
)

data class TunnelState(
    val status: TunnelStatus = TunnelStatus.IDLE,
    val transport: TunnelTransport? = null,
    val packets: Long = 0L,
    val bytes: Long = 0L,
    val rxBytes: Long = 0L,
    val netRxPackets: Long = 0L,
    val startedAtMillis: Long? = null,
    val error: String? = null,
    val suggestMasque: Boolean = false,
)

object VpnController {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()
    private val _probeState = MutableStateFlow(ProbeState())
    val probeState: StateFlow<ProbeState> = _probeState.asStateFlow()

    fun start(context: Context, protocol: VpnProtocol = VpnProtocol.WIREGUARD) {
        _state.value = TunnelState(status = TunnelStatus.CONNECTING)
        _probeState.value = ProbeState()
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
        if (_state.value.status != TunnelStatus.CONNECTED || _probeState.value.status == ProbeStatus.RUNNING) {
            return
        }
        _probeState.value = ProbeState(status = ProbeStatus.RUNNING)
        val intent = Intent(context, LintasVpnService::class.java)
            .setAction(LintasVpnService.ACTION_PROBE)
        runCatching { context.startService(intent) }
            .onFailure { error ->
                _probeState.value = ProbeState(
                    status = ProbeStatus.FAILURE,
                    message = error.message ?: "Tidak bisa memulai uji koneksi",
                )
            }
    }

    internal fun probeSuccess(latencyMs: Long) {
        _probeState.value = ProbeState(status = ProbeStatus.SUCCESS, latencyMs = latencyMs)
    }

    internal fun probeFailure(message: String) {
        _probeState.value = ProbeState(status = ProbeStatus.FAILURE, message = message)
    }

    internal fun connecting() {
        _state.update {
            it.copy(status = TunnelStatus.CONNECTING, error = null, suggestMasque = false)
        }
        if (_probeState.value.status != ProbeStatus.IDLE) {
            _probeState.value = ProbeState()
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
                suggestMasque = false,
            )
        }
    }

    internal fun updateStats(packets: Long, bytes: Long, rxBytes: Long, netRxPackets: Long) {
        _state.update {
            it.copy(
                packets = packets,
                bytes = bytes,
                rxBytes = rxBytes,
                netRxPackets = netRxPackets,
            )
        }
    }

    internal fun idle() {
        _state.value = TunnelState()
        _probeState.value = ProbeState()
    }

    internal fun error(message: String, suggestMasque: Boolean = false) {
        _state.value = TunnelState(
            status = TunnelStatus.ERROR,
            error = message,
            suggestMasque = suggestMasque,
        )
        _probeState.value = ProbeState()
    }
}
