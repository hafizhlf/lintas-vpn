package app.lintas.update

import android.content.Context
import androidx.core.content.edit

data class StoredCheck(
    val versionName: String,
    val checkedAt: Long,
    val release: ReleaseInfo?,
)

object UpdateStore {
    private const val PREFS = "lintas_update"
    private const val KEY_LAST_ATTEMPT = "last_attempt_at"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_LATEST_VERSION = "latest_version"
    private const val KEY_LATEST_TAG = "latest_tag"
    private const val KEY_NOTES = "latest_notes"
    private const val KEY_PAGE_URL = "latest_page_url"
    private const val KEY_APK_URL = "apk_url"
    private const val KEY_APK_SIZE = "apk_size"
    private const val KEY_APK_SHA256 = "apk_sha256"
    private const val KEY_READY_FILE = "ready_file"
    private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"

    fun loadCheck(context: Context): StoredCheck? {
        val prefs = prefs(context)
        val versionName = prefs.getString(KEY_LATEST_VERSION, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val apkUrl = prefs.getString(KEY_APK_URL, null).orEmpty()
        val release = if (apkUrl.isNotBlank()) {
            ReleaseInfo(
                versionName = versionName,
                tag = prefs.getString(KEY_LATEST_TAG, null).orEmpty(),
                notes = prefs.getString(KEY_NOTES, null).orEmpty(),
                pageUrl = prefs.getString(KEY_PAGE_URL, null).orEmpty(),
                apkUrl = apkUrl,
                apkSize = prefs.getLong(KEY_APK_SIZE, 0L),
                apkSha256 = prefs.getString(KEY_APK_SHA256, null)?.takeIf { it.isNotBlank() },
            )
        } else {
            null
        }
        return StoredCheck(
            versionName = versionName,
            checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L),
            release = release,
        )
    }

    fun saveCheck(context: Context, release: ReleaseInfo, checkedAt: Long) {
        val prefs = prefs(context)
        val previousVersion = prefs.getString(KEY_LATEST_VERSION, null)
        prefs.edit {
            putString(KEY_LATEST_VERSION, release.versionName)
            putString(KEY_LATEST_TAG, release.tag)
            putString(KEY_NOTES, release.notes)
            putString(KEY_PAGE_URL, release.pageUrl)
            putString(KEY_APK_URL, release.apkUrl)
            putLong(KEY_APK_SIZE, release.apkSize)
            if (release.apkSha256 != null) {
                putString(KEY_APK_SHA256, release.apkSha256)
            } else {
                remove(KEY_APK_SHA256)
            }
            putLong(KEY_CHECKED_AT, checkedAt)
            if (previousVersion != release.versionName) {
                remove(KEY_READY_FILE)
            }
        }
    }

    fun saveUpToDate(context: Context, versionName: String, checkedAt: Long) {
        prefs(context).edit {
            putString(KEY_LATEST_VERSION, versionName)
            putLong(KEY_CHECKED_AT, checkedAt)
            remove(KEY_LATEST_TAG)
            remove(KEY_NOTES)
            remove(KEY_PAGE_URL)
            remove(KEY_APK_URL)
            remove(KEY_APK_SIZE)
            remove(KEY_APK_SHA256)
            remove(KEY_READY_FILE)
        }
    }

    fun readyFile(context: Context): String? =
        prefs(context).getString(KEY_READY_FILE, null)?.takeIf { it.isNotBlank() }

    fun saveReadyFile(context: Context, path: String?) {
        prefs(context).edit {
            if (path != null) {
                putString(KEY_READY_FILE, path)
            } else {
                remove(KEY_READY_FILE)
            }
        }
    }

    fun lastAttemptAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ATTEMPT, 0L)

    fun setLastAttemptAt(context: Context, value: Long) {
        prefs(context).edit { putLong(KEY_LAST_ATTEMPT, value) }
    }

    fun lastSeenVersionCode(context: Context): Long? {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_LAST_SEEN_VERSION_CODE)) {
            prefs.getLong(KEY_LAST_SEEN_VERSION_CODE, 0L)
        } else {
            null
        }
    }

    fun setLastSeenVersionCode(context: Context, value: Long) {
        prefs(context).edit { putLong(KEY_LAST_SEEN_VERSION_CODE, value) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
