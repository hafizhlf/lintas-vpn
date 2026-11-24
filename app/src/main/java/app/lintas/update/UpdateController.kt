package app.lintas.update

import android.content.Context
import app.lintas.BuildConfig
import app.lintas.vpn.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UpdateController {
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val LEGACY_UPDATES_DIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UpdateState(currentVersion = BuildConfig.VERSION_NAME))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    private val checkLock = Any()
    private val checkGate = GenerationGate()

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            try {
                cleanupLegacyDownloads(app)
                UpdateStore.clearLegacy(app)
                detectJustUpdated(app)
                restore(app)
            } catch (error: Exception) {
                DiagnosticLog.log("UPDATE", "initialize failed: ${error.message}")
            }
            maybeAutoCheck(app)
        }
    }

    fun check(context: Context, manual: Boolean) {
        val app = context.applicationContext
        val token: Long
        val previous: UpdateStatus
        synchronized(checkLock) {
            val current = _state.value
            if (current.status == UpdateStatus.CHECKING) {
                return
            }
            previous = current.status
            token = checkGate.next()
            _state.update { it.copy(status = UpdateStatus.CHECKING, error = null) }
        }
        scope.launch {
            UpdateStore.setLastAttemptAt(app, System.currentTimeMillis())
            try {
                when (val result = UpdateApi.fetchLatest(BuildConfig.VERSION_NAME)) {
                    is ReleaseParseResult.Newer -> {
                        val now = System.currentTimeMillis()
                        val applied = applyCheckResult(token) {
                            UpdateStore.saveCheck(app, result.release.info, now)
                            _state.update {
                                it.copy(
                                    status = UpdateStatus.AVAILABLE,
                                    release = result.release.info,
                                    error = null,
                                    justUpdatedTo = null,
                                )
                            }
                        }
                        if (applied) {
                            DiagnosticLog.log(
                                "UPDATE",
                                "check available version=${result.release.info.versionName}",
                            )
                        } else {
                            DiagnosticLog.log(
                                "UPDATE",
                                "check result discarded version=${result.release.info.versionName}",
                            )
                        }
                    }

                    is ReleaseParseResult.UpToDate -> {
                        val now = System.currentTimeMillis()
                        val applied = applyCheckResult(token) {
                            UpdateStore.saveUpToDate(app, result.versionName, now)
                            _state.update {
                                it.copy(
                                    status = UpdateStatus.UP_TO_DATE,
                                    release = null,
                                    error = null,
                                )
                            }
                        }
                        if (applied) {
                            DiagnosticLog.log(
                                "UPDATE",
                                "check up_to_date version=${result.versionName}",
                            )
                        } else {
                            DiagnosticLog.log("UPDATE", "check result discarded (up_to_date)")
                        }
                    }

                    is ReleaseParseResult.Invalid -> {
                        handleCheckFailure(token, previous, result.message, manual)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                handleCheckFailure(token, previous, updateErrorMessage(error), manual)
            }
        }
    }

    private fun applyCheckResult(token: Long, block: () -> Unit): Boolean =
        synchronized(checkLock) {
            if (!canApplyCheckResult(token, checkGate, _state.value.status)) {
                false
            } else {
                block()
                true
            }
        }

    fun clearJustUpdated() {
        _state.update { it.copy(justUpdatedTo = null) }
    }

    private fun handleCheckFailure(
        token: Long,
        previous: UpdateStatus,
        message: String,
        manual: Boolean,
    ) {
        DiagnosticLog.log("UPDATE", "check failed manual=$manual error=$message")
        synchronized(checkLock) {
            if (!canApplyCheckResult(token, checkGate, _state.value.status)) {
                DiagnosticLog.log("UPDATE", "check failure discarded")
                return
            }
            if (manual) {
                _state.update { it.copy(status = UpdateStatus.ERROR, error = message) }
            } else {
                _state.update { it.copy(status = previous, error = null) }
            }
        }
    }

    private fun cleanupLegacyDownloads(context: Context) {
        val dir = File(context.cacheDir, LEGACY_UPDATES_DIR)
        if (dir.exists() && !dir.deleteRecursively()) {
            DiagnosticLog.log("UPDATE", "legacy download cleanup failed")
        }
    }

    private fun detectJustUpdated(context: Context) {
        val currentCode = installedVersionCode(context)
        val lastSeen = UpdateStore.lastSeenVersionCode(context)
        UpdateStore.setLastSeenVersionCode(context, currentCode)
        if (lastSeen != null && currentCode > lastSeen) {
            _state.update { it.copy(justUpdatedTo = BuildConfig.VERSION_NAME) }
        }
    }

    private fun restore(context: Context) {
        val stored = UpdateStore.loadCheck(context)
        val newer = stored != null && isNewerVersion(stored.versionName, BuildConfig.VERSION_NAME)
        val status = when {
            stored == null -> UpdateStatus.IDLE
            newer && stored.release != null -> UpdateStatus.AVAILABLE
            newer -> UpdateStatus.IDLE
            else -> UpdateStatus.UP_TO_DATE
        }
        synchronized(checkLock) {
            if (_state.value.status == UpdateStatus.CHECKING) {
                return
            }
            _state.update {
                it.copy(
                    status = status,
                    release = if (status == UpdateStatus.AVAILABLE) stored?.release else null,
                    error = null,
                )
            }
        }
    }

    private fun maybeAutoCheck(context: Context) {
        val elapsed = System.currentTimeMillis() - UpdateStore.lastAttemptAt(context)
        if (elapsed < CHECK_INTERVAL_MS) return
        check(context, manual = false)
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    private fun updateErrorMessage(error: Throwable): String = when (error) {
        is UpdateException.RateLimited -> {
            val reset = error.resetAtEpochSeconds
            if (reset != null) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reset * 1000L))
                "Batas pemeriksaan GitHub tercapai. Coba lagi setelah $time."
            } else {
                "Batas pemeriksaan GitHub tercapai. Coba lagi nanti."
            }
        }

        is UpdateException.NotFound -> "Belum ada rilis publik."
        is UpdateException.Http -> "GitHub mengembalikan HTTP ${error.code}."
        is UpdateException.Network -> "Tidak bisa menghubungi GitHub. Periksa koneksi internet."
        else -> "Tidak bisa memeriksa pembaruan. Coba lagi."
    }
}
