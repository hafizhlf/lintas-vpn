package app.lintas.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.lintas.BuildConfig
import app.lintas.vpn.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UpdateController {
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val UPDATES_DIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UpdateState(currentVersion = BuildConfig.VERSION_NAME))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    private val checkLock = Any()
    private val checkGate = GenerationGate()
    private val downloadLock = Any()
    private val downloadGate = GenerationGate()

    private var downloadJob: Job? = null
    private var downloadAttempt: DownloadAttempt? = null

    private class DownloadAttempt(
        val token: Long,
        val part: File,
        val finalFile: File,
    ) {
        @Volatile
        var call: Call? = null

        @Volatile
        var cancelled = false
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            try {
                cleanupCache(app)
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
            if (
                current.status == UpdateStatus.CHECKING ||
                current.status == UpdateStatus.DOWNLOADING ||
                current.status == UpdateStatus.READY
            ) {
                return
            }
            previous = current.status
            token = checkGate.next()
            _state.update { it.copy(status = UpdateStatus.CHECKING, error = null, progress = 0) }
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
                                    progress = 0,
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
                                    progress = 0,
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

    fun download(context: Context) {
        val app = context.applicationContext
        val release: ReleaseInfo
        val attempt: DownloadAttempt
        synchronized(downloadLock) {
            val current = _state.value
            if (current.status != UpdateStatus.AVAILABLE) return
            release = current.release ?: return
            val dir = File(app.cacheDir, UPDATES_DIR)
            val token = downloadGate.next()
            attempt = DownloadAttempt(
                token = token,
                part = File(dir, "lintas-${release.versionName}-$token.apk.part"),
                finalFile = File(dir, "lintas-${release.versionName}-$token.apk"),
            )
            downloadAttempt = attempt
            _state.update {
                it.copy(status = UpdateStatus.DOWNLOADING, progress = 0, error = null)
            }
        }
        downloadJob = scope.launch {
            val dir = attempt.part.parentFile ?: return@launch
            dir.mkdirs()
            dir.listFiles()?.forEach { file ->
                if (file != attempt.part && file != attempt.finalFile) {
                    file.delete()
                }
            }
            if (!isCurrent(attempt)) return@launch
            if (dir.usableSpace < release.apkSize + 10L * 1024L * 1024L) {
                failAttempt(
                    app,
                    attempt,
                    "Ruang penyimpanan tidak cukup. Butuh sekitar " +
                        "${formatUpdateSize(release.apkSize)}.",
                )
                return@launch
            }
            try {
                val request = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "Lintas/${BuildConfig.VERSION_NAME} (Android)")
                    .build()
                val call = UpdateApi.httpClient.newCall(request)
                attempt.call = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw UpdateException.Http(response.code)
                    }
                    val body = response.body ?: throw UpdateException.Network("Respons kosong.")
                    val total = body.contentLength().takeIf { it > 0L } ?: release.apkSize
                    var read = 0L
                    var lastPercent = -1
                    body.byteStream().use { input ->
                        attempt.part.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (!isCurrent(attempt)) throw IOException("Download dibatalkan.")
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                read += count
                                if (read > release.apkSize) {
                                    throw UpdateException.Verification("Ukuran APK tidak cocok.")
                                }
                                val percent = ((read * 100L) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    updateProgress(attempt, percent)
                                }
                            }
                        }
                    }
                    if (read != release.apkSize) {
                        throw UpdateException.Verification("Ukuran APK tidak cocok.")
                    }
                }
                if (!isCurrent(attempt)) throw IOException("Download dibatalkan.")
                UpdateVerifier.verify(
                    context = app,
                    apk = attempt.part,
                    expectedVersionName = release.versionName,
                    expectedSha256 = release.apkSha256,
                    expectedSize = release.apkSize,
                )
                if (!isCurrent(attempt)) throw IOException("Download dibatalkan.")
                if (!attempt.part.renameTo(attempt.finalFile)) {
                    throw UpdateException.Verification("Gagal menyimpan APK.")
                }
                synchronized(downloadLock) {
                    if (!isCurrentLocked(attempt)) {
                        attempt.finalFile.delete()
                        return@launch
                    }
                    UpdateStore.saveReadyFile(app, attempt.finalFile.absolutePath)
                    _state.update {
                        it.copy(status = UpdateStatus.READY, progress = 100, error = null)
                    }
                }
                DiagnosticLog.log(
                    "UPDATE",
                    "download verified version=${release.versionName} size=${release.apkSize}",
                )
            } catch (cancel: CancellationException) {
                attempt.part.delete()
                attempt.finalFile.delete()
                throw cancel
            } catch (error: Exception) {
                attempt.part.delete()
                attempt.finalFile.delete()
                if (isCurrent(attempt)) {
                    failAttempt(app, attempt, downloadErrorMessage(error))
                    DiagnosticLog.log("UPDATE", "download failed: ${error.message}")
                }
            } finally {
                synchronized(downloadLock) {
                    if (downloadAttempt === attempt) {
                        downloadAttempt = null
                        downloadGate.invalidate()
                    }
                }
            }
        }
    }

    fun cancelDownload(context: Context) {
        val attempt: DownloadAttempt?
        synchronized(downloadLock) {
            if (_state.value.status != UpdateStatus.DOWNLOADING) return
            attempt = downloadAttempt
            downloadAttempt = null
            downloadGate.invalidate()
            attempt?.cancelled = true
            _state.update {
                it.copy(status = UpdateStatus.AVAILABLE, progress = 0, error = null)
            }
        }
        attempt?.call?.cancel()
        downloadJob?.cancel()
        attempt?.part?.delete()
        DiagnosticLog.log("UPDATE", "download cancelled")
    }

    private fun isCurrent(attempt: DownloadAttempt): Boolean =
        synchronized(downloadLock) { isCurrentLocked(attempt) }

    private fun isCurrentLocked(attempt: DownloadAttempt): Boolean =
        downloadAttempt === attempt &&
            !attempt.cancelled &&
            downloadGate.isCurrent(attempt.token)

    private fun updateProgress(attempt: DownloadAttempt, percent: Int) {
        synchronized(downloadLock) {
            if (isCurrentLocked(attempt)) {
                _state.update { it.copy(progress = percent) }
            }
        }
    }

    private fun failAttempt(
        context: Context,
        attempt: DownloadAttempt,
        message: String,
    ) {
        attempt.part.delete()
        attempt.finalFile.delete()
        synchronized(downloadLock) {
            if (downloadAttempt !== attempt) return
            downloadAttempt = null
            downloadGate.invalidate()
            UpdateStore.saveReadyFile(context, null)
            _state.update {
                it.copy(status = UpdateStatus.ERROR, error = message)
            }
        }
    }

    fun launchInstaller(context: Context) {
        val app = context.applicationContext
        if (_state.value.status != UpdateStatus.READY) return
        val file = UpdateStore.readyFile(app)?.let(::File)
        if (file == null || !file.isFile) {
            DiagnosticLog.log("UPDATE", "installer file missing")
            _state.update {
                it.copy(
                    status = UpdateStatus.ERROR,
                    error = "File pembaruan tidak ditemukan. Unduh ulang.",
                )
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            DiagnosticLog.log(
                "UPDATE",
                "installer launched version=${_state.value.release?.versionName}",
            )
        } catch (error: ActivityNotFoundException) {
            DiagnosticLog.log("UPDATE", "installer activity not found")
            _state.update {
                it.copy(
                    status = UpdateStatus.ERROR,
                    error = "Tidak ada aplikasi installer yang bisa membuka APK ini.",
                )
            }
        } catch (error: Exception) {
            DiagnosticLog.log("UPDATE", "installer launch failed: ${error.message}")
            _state.update {
                it.copy(
                    status = UpdateStatus.ERROR,
                    error = "Tidak bisa membuka installer: ${error.message}",
                )
            }
        }
    }

    fun discardReady(context: Context) {
        val app = context.applicationContext
        UpdateStore.readyFile(app)?.let { File(it).delete() }
        UpdateStore.saveReadyFile(app, null)
        val stored = UpdateStore.loadCheck(app)
        val newer = stored != null && isNewerVersion(stored.versionName, BuildConfig.VERSION_NAME)
        val status = when {
            stored == null -> UpdateStatus.IDLE
            newer && stored.release != null -> UpdateStatus.AVAILABLE
            newer -> UpdateStatus.IDLE
            else -> UpdateStatus.UP_TO_DATE
        }
        _state.update {
            it.copy(
                status = status,
                release = if (status == UpdateStatus.AVAILABLE) stored?.release else null,
                progress = 0,
                error = null,
            )
        }
        DiagnosticLog.log("UPDATE", "ready discarded")
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

    private fun cleanupCache(context: Context) {
        val readyFile = UpdateStore.readyFile(context)
        File(context.cacheDir, UPDATES_DIR).listFiles()?.forEach { file ->
            val isPart = file.name.endsWith(".part")
            val isStaleApk = file.extension == "apk" && file.absolutePath != readyFile
            if (isPart || isStaleApk) {
                file.delete()
            }
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
        val readyPath = UpdateStore.readyFile(context)
        if (readyPath != null) {
            val file = File(readyPath)
            val release = stored?.release
            if (file.isFile && release != null) {
                val verified = runCatching {
                    UpdateVerifier.verify(
                        context = context,
                        apk = file,
                        expectedVersionName = release.versionName,
                        expectedSha256 = release.apkSha256,
                        expectedSize = release.apkSize,
                    )
                }.isSuccess
                if (verified) {
                    synchronized(checkLock) {
                        checkGate.invalidate()
                        _state.update {
                            it.copy(
                                status = UpdateStatus.READY,
                                release = release,
                                progress = 100,
                                error = null,
                            )
                        }
                    }
                    return
                }
            }
            file.delete()
            UpdateStore.saveReadyFile(context, null)
        }

        val latest = UpdateStore.loadCheck(context)
        val newer = latest != null && isNewerVersion(latest.versionName, BuildConfig.VERSION_NAME)
        val status = when {
            latest == null -> UpdateStatus.IDLE
            newer && latest.release != null -> UpdateStatus.AVAILABLE
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
                    release = if (status == UpdateStatus.AVAILABLE) latest?.release else null,
                    error = null,
                    progress = 0,
                )
            }
        }
    }

    private fun maybeAutoCheck(context: Context) {
        if (_state.value.status == UpdateStatus.READY) return
        val elapsed = System.currentTimeMillis() - UpdateStore.lastAttemptAt(context)
        if (elapsed < CHECK_INTERVAL_MS) return
        check(context, manual = false)
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    private fun downloadErrorMessage(error: Throwable): String = when (error) {
        is UpdateException.Verification -> error.message ?: "Unduhan tidak lengkap atau rusak."
        is UpdateException.Http -> "GitHub mengembalikan HTTP ${error.code}."
        is UpdateException.Network -> "Unduhan gagal. Coba lagi."
        else -> "Unduhan gagal. Coba lagi."
    }

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
        is UpdateException.Verification -> error.message ?: "Pembaruan ditolak."
        else -> "Tidak bisa memeriksa pembaruan. Coba lagi."
    }
}
