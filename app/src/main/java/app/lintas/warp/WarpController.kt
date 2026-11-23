package app.lintas.warp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WarpStatus {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    ENROLLING,
    READY,
    ERROR,
}

data class WarpState(
    val status: WarpStatus = WarpStatus.UNREGISTERED,
    val identity: WarpIdentity? = null,
    val error: String? = null,
)

data class WarpPlusState(
    val busy: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

data class WarpCheckState(
    val checking: Boolean = false,
    val error: String? = null,
    val checkedAt: Long? = null,
)

object WarpController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WarpState())
    val state: StateFlow<WarpState> = _state.asStateFlow()
    private val _warpPlus = MutableStateFlow(WarpPlusState())
    val warpPlus: StateFlow<WarpPlusState> = _warpPlus.asStateFlow()
    private val _check = MutableStateFlow(WarpCheckState())
    val check: StateFlow<WarpCheckState> = _check.asStateFlow()

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching { WarpIdentityStore.clearLegacyMasque(appContext) }
            val identity = runCatching { WarpIdentityStore.load(appContext) }.getOrNull()
            _state.value = stateFor(identity)
            if (identity != null) checkStatus(appContext)
        }
    }

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val identity = runCatching { WarpIdentityStore.load(appContext) }.getOrNull()
            _state.value = stateFor(identity)
        }
    }

    private fun stateFor(identity: WarpIdentity?): WarpState = if (identity == null) {
        WarpState()
    } else {
        WarpState(
            status = if (identity.hasMasqueTransport) WarpStatus.READY else WarpStatus.REGISTERED,
            identity = identity,
        )
    }

    fun register(context: Context) {
        if (_state.value.status == WarpStatus.REGISTERING || _state.value.status == WarpStatus.ENROLLING) return
        val appContext = context.applicationContext
        _state.update { it.copy(status = WarpStatus.REGISTERING, error = null) }
        scope.launch {
            runCatching { WarpApiClient.register() }
                .onSuccess { base ->
                    runCatching { WarpIdentityStore.save(appContext, base) }
                        .onFailure { error ->
                            _state.value = WarpState(
                                WarpStatus.ERROR,
                                base,
                                "Gagal menyimpan identitas: ${error.message}",
                            )
                            return@onSuccess
                        }
                    _state.value = WarpState(WarpStatus.ENROLLING, base)
                    runCatching { WarpApiClient.enrollMasque(base) }
                        .onSuccess { combined ->
                            runCatching { WarpIdentityStore.save(appContext, combined) }
                                .onSuccess {
                                    _state.value = WarpState(WarpStatus.READY, combined)
                                }
                                .onFailure { error ->
                                    _state.value = WarpState(
                                        WarpStatus.ERROR,
                                        combined,
                                        "Gagal menyimpan kredensial: ${error.message}",
                                    )
                                }
                        }
                        .onFailure { error ->
                            _state.value = WarpState(
                                WarpStatus.REGISTERED,
                                base,
                                "WireGuard siap. MASQUE belum siap: ${error.message}",
                            )
                        }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            status = WarpStatus.ERROR,
                            error = error.message ?: "Registrasi WARP gagal",
                        )
                    }
                }
        }
    }

    fun enroll(context: Context) {
        val identity = _state.value.identity ?: return
        if (_state.value.status == WarpStatus.REGISTERING || _state.value.status == WarpStatus.ENROLLING) return
        val appContext = context.applicationContext
        _state.update { it.copy(status = WarpStatus.ENROLLING, error = null) }
        scope.launch {
            runCatching { WarpApiClient.enrollMasque(identity) }
                .onSuccess { updated ->
                    runCatching { WarpIdentityStore.save(appContext, updated) }
                        .onSuccess {
                            _state.value = WarpState(WarpStatus.READY, updated)
                        }
                        .onFailure { error ->
                            _state.value = WarpState(
                                WarpStatus.ERROR,
                                updated,
                                "Gagal menyimpan kredensial: ${error.message}",
                            )
                        }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            status = WarpStatus.ERROR,
                            error = error.message ?: "Enrollment MASQUE gagal",
                        )
                    }
                }
        }
    }

    fun activateWarpPlus(context: Context, licenseKey: String) {
        if (_warpPlus.value.busy) return
        val identity = _state.value.identity ?: return
        val appContext = context.applicationContext
        _warpPlus.value = WarpPlusState(busy = true)
        scope.launch {
            runCatching { WarpApiClient.applyLicense(identity, licenseKey) }
                .onSuccess { updated ->
                    runCatching { WarpIdentityStore.save(appContext, updated) }
                        .onSuccess {
                            _state.value = stateFor(updated)
                            _warpPlus.value = WarpPlusState(success = true)
                        }
                        .onFailure { error ->
                            _warpPlus.value = WarpPlusState(
                                error = "Gagal menyimpan lisensi: ${error.message}",
                            )
                        }
                }
                .onFailure { error ->
                    _warpPlus.value = WarpPlusState(
                        error = error.message ?: "Aktivasi WARP+ gagal",
                    )
                }
        }
    }

    fun clearWarpPlusFeedback() {
        _warpPlus.value = WarpPlusState()
    }

    fun checkStatus(context: Context) {
        val identity = _state.value.identity ?: return
        if (_check.value.checking) return
        val appContext = context.applicationContext
        _check.value = WarpCheckState(checking = true)
        scope.launch {
            runCatching { WarpApiClient.fetchAccount(identity) }
                .onSuccess { updated ->
                    if (_state.value.identity?.deviceId != identity.deviceId) return@onSuccess
                    runCatching { WarpIdentityStore.save(appContext, updated) }
                        .onSuccess {
                            _state.value = stateFor(updated)
                            _check.value = WarpCheckState(checkedAt = System.currentTimeMillis())
                        }
                        .onFailure { error ->
                            _check.value = WarpCheckState(
                                error = "Gagal menyimpan status: ${error.message}",
                            )
                        }
                }
                .onFailure { error ->
                    if (_state.value.identity?.deviceId != identity.deviceId) return@onFailure
                    _check.value = WarpCheckState(
                        error = error.message ?: "Gagal memeriksa status WARP+",
                    )
                }
        }
    }

    fun reset(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching { WarpIdentityStore.clear(appContext) }
            _check.value = WarpCheckState()
            _state.value = WarpState()
        }
    }
}
