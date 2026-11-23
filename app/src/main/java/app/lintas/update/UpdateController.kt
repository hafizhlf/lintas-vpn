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
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadCall: Call? = null

    @Volatile
    private var downloadCancelled = false

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
        val current = _state.value
        if (
            current.status == UpdateStatus.CHECKING ||
            current.status == UpdateStatus.DOWNLOADING ||
            current.status == UpdateStatus.READY
        ) {
            return
        }
        checkJob?.cancel()
        val previous = current.status
        _state.update { it.copy(status = UpdateStatus.CHECKING, error = null, progress = 0) }
        checkJob = scope.launch {
            UpdateStore.setLastAttemptAt(app, System.currentTimeMillis())
            try {
                when (val result = UpdateApi.fetchLatest(BuildConfig.VERSION_NAME)) {
                    is ReleaseParseResult.Newer -> {
                        val now = System.currentTimeMillis()
                        UpdateStore.saveCheck(app, result.release.info, now)
                        _state.update {
                            it.copy(
                                status = UpdateStatus.AVAILABLE,
                                release = result.release.info,
                                error = null,
                                progress = 0,
                            )
                        }
                        DiagnosticLog.log(
                            "UPDATE",
                            "check available version=${result.release.info.versionName}",
                        )
                    }

                    is ReleaseParseResult.UpToDate -> {
                        val now = System.currentTimeMillis()
                        UpdateStore.saveUpToDate(app, result.versionName, now)
                        _state.update {
                            it.copy(
                                status = UpdateStatus.UP_TO_DATE,
                                release = null,
                                error = null,
                                progress = 0,
                            )
                        }
                        DiagnosticLog.log("UPDATE", "check up_to_date version=${result.versionName}")
                    }

                    is ReleaseParseResult.Invalid -> {
                        handleCheckFailure(app, previous, result.message, manual)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                handleCheckFailure(app, previous, updateErrorMessage(error), manual)
            }
        }
    }

    fun clearJustUpdated() {
        _state.update { it.copy(justUpdatedTo = null) }
    }

    fun download(context: Context) {
        val app = context.applicationContext
        val current = _state.value
        if (current.status != UpdateStatus.AVAILABLE) return
        val release = current.release ?: return
        if (downloadJob?.isActive == true) return

        downloadCancelled = false
        _state.update {
            it.copy(status = UpdateStatus.DOWNLOADING, progress = 0, error = null)
        }
        downloadJob = scope.launch {
            val dir = File(app.cacheDir, UPDATES_DIR)
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            if (dir.usableSpace < release.apkSize + 10L * 1024L * 1024L) {
                _state.update {
                    it.copy(
                        status = UpdateStatus.ERROR,
                        error = "Ruang penyimpanan tidak cukup. Butuh sekitar " +
                            "${formatUpdateSize(release.apkSize)}.",
                    )
                }
                return@launch
            }

            val part = File(dir, "lintas-${release.versionName}.apk.part")
            val finalFile = File(dir, "lintas-${release.versionName}.apk")
            try {
                val request = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "Lintas/${BuildConfig.VERSION_NAME} (Android)")
                    .build()
                val call = UpdateApi.httpClient.newCall(request)
                downloadCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw UpdateException.Http(response.code)
                    }
                    val body = response.body ?: throw UpdateException.Network("Respons kosong.")
                    val total = body.contentLength().takeIf { it > 0L } ?: release.apkSize
                    var read = 0L
                    var lastPercent = -1
                    body.byteStream().use { input ->
                        part.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (downloadCancelled) throw IOException("Download dibatalkan.")
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                read += count
                                val percent = ((read * 100L) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _state.update { it.copy(progress = percent) }
                                }
                            }
                        }
                    }
                    if (read != release.apkSize) {
                        throw UpdateException.Verification("Ukuran APK tidak cocok.")
                    }
                }
                if (downloadCancelled) {
                    throw IOException("Download dibatalkan.")
                }
                UpdateVerifier.verify(
                    context = app,
                    apk = part,
                    expectedVersionName = release.versionName,
                    expectedSha256 = release.apkSha256,
                    expectedSize = release.apkSize,
                )
                if (!part.renameTo(finalFile)) {
                    throw UpdateException.Verification("Gagal menyimpan APK.")
                }
                UpdateStore.saveReadyFile(app, finalFile.absolutePath)
                _state.update {
                    it.copy(status = UpdateStatus.READY, progress = 100, error = null)
                }
                DiagnosticLog.log(
                    "UPDATE",
                    "download verified version=${release.versionName} size=${release.apkSize}",
                )
            } catch (cancel: CancellationException) {
                part.delete()
                throw cancel
            } catch (error: Exception) {
                part.delete()
                finalFile.delete()
                UpdateStore.saveReadyFile(app, null)
                if (downloadCancelled) {
                    _state.update {
                        it.copy(status = UpdateStatus.AVAILABLE, progress = 0, error = null)
                    }
                } else {
                    _state.update {
                        it.copy(status = UpdateStatus.ERROR, error = downloadErrorMessage(error))
                    }
                    DiagnosticLog.log("UPDATE", "download failed: ${error.message}")
                }
            } finally {
                downloadCall = null
                downloadCancelled = false
            }
        }
    }

    fun cancelDownload(context: Context) {
        if (_state.value.status != UpdateStatus.DOWNLOADING) return
        val app = context.applicationContext
        downloadCancelled = true
        downloadCall?.cancel()
        downloadJob?.cancel()
        File(app.cacheDir, UPDATES_DIR).listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.forEach { it.delete() }
        _state.update {
            it.copy(status = UpdateStatus.AVAILABLE, progress = 0, error = null)
        }
        DiagnosticLog.log("UPDATE", "download cancelled")
    }

    fun launchInstaller(context: Context) {
        val app = context.applicationContext
        if (_state.value.status != UpdateStatus.READY) return
        val file = UpdateStore.readyFile(app)?.let(::File)
        if (file == null || !file.isFile) {
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
            _state.update {
                it.copy(
                    status = UpdateStatus.ERROR,
                    error = "Tidak ada aplikasi installer yang bisa membuka APK ini.",
                )
            }
        } catch (error: Exception) {
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
        context: Context,
        previous: UpdateStatus,
        message: String,
        manual: Boolean,
    ) {
        DiagnosticLog.log("UPDATE", "check failed manual=$manual error=$message")
        if (manual) {
            _state.update { it.copy(status = UpdateStatus.ERROR, error = message) }
        } else {
            _state.update { it.copy(status = previous, error = null) }
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
                    _state.update {
                        it.copy(
                            status = UpdateStatus.READY,
                            release = release,
                            progress = 100,
                            error = null,
                        )
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
        _state.update {
            it.copy(
                status = status,
                release = if (status == UpdateStatus.AVAILABLE) latest?.release else null,
                error = null,
                progress = 0,
            )
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
