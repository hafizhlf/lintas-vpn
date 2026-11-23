package app.lintas.vpn

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {
    private const val PREFS = "lintas_settings"
    private const val KEY_ENABLED = "diagnostic_log_enabled"
    private const val FILE_NAME = "lintas-diagnostics.log"
    private const val MAX_BYTES = 2L * 1024L * 1024L

    private val lock = Any()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var enabled = false

    private var file: File? = null

    val isActive: Boolean
        get() = enabled

    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            enabled = prefs(app).getBoolean(KEY_ENABLED, false)
            file = if (enabled) logFile(app) else null
        }
    }

    fun isEnabled(context: Context): Boolean {
        return prefs(context.applicationContext).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, value).apply()
        synchronized(lock) {
            if (!value) {
                writeLocked("=== diagnostic logging disabled ===")
            }
            enabled = value
            file = if (value) logFile(app) else null
            if (value) {
                writeLocked("=== diagnostic logging enabled ===")
                writeLocked("${timestamp.format(Date())} [APP] ${appHeader(app)}")
            }
        }
    }

    fun log(category: String, message: String) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return
            writeLocked("${timestamp.format(Date())} [$category] $message")
        }
    }

    fun path(context: Context): String {
        return logFile(context.applicationContext).absolutePath
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            runCatching { logFile(app).delete() }
            runCatching { File(logFile(app).path + ".1").delete() }
        }
    }

    private fun writeLocked(line: String) {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            if (target.length() + line.length + 1 > MAX_BYTES) {
                val backup = File(target.path + ".1")
                backup.delete()
                target.renameTo(backup)
            }
            target.appendText(line + "\n")
        }.onFailure { error ->
            Log.w("LintasDiag", "write failed: ${error.message}")
        }
    }

    private fun logFile(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, FILE_NAME)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun appHeader(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return "start version=$version device=${Build.MANUFACTURER}/${Build.MODEL} " +
            "android=${Build.VERSION.RELEASE}"
    }
}
