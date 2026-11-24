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
    private const val KEY_NOTES = "latest_notes"
    private const val KEY_PAGE_URL = "latest_page_url"
    private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    private val LEGACY_KEYS = listOf(
        "latest_tag",
        "apk_url",
        "apk_size",
        "apk_sha256",
        "ready_file",
    )

    fun loadCheck(context: Context): StoredCheck? {
        val prefs = prefs(context)
        val versionName = prefs.getString(KEY_LATEST_VERSION, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val pageUrl = prefs.getString(KEY_PAGE_URL, null)?.takeIf { it.isNotBlank() }
        val release = pageUrl?.let {
            ReleaseInfo(
                versionName = versionName,
                notes = prefs.getString(KEY_NOTES, null).orEmpty(),
                pageUrl = it,
            )
        }
        return StoredCheck(
            versionName = versionName,
            checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L),
            release = release,
        )
    }

    fun saveCheck(context: Context, release: ReleaseInfo, checkedAt: Long) {
        prefs(context).edit {
            putString(KEY_LATEST_VERSION, release.versionName)
            putString(KEY_NOTES, release.notes)
            putString(KEY_PAGE_URL, release.pageUrl)
            putLong(KEY_CHECKED_AT, checkedAt)
            LEGACY_KEYS.forEach { remove(it) }
        }
    }

    fun saveUpToDate(context: Context, versionName: String, checkedAt: Long) {
        prefs(context).edit {
            putString(KEY_LATEST_VERSION, versionName)
            putLong(KEY_CHECKED_AT, checkedAt)
            remove(KEY_NOTES)
            remove(KEY_PAGE_URL)
            LEGACY_KEYS.forEach { remove(it) }
        }
    }

    fun clearLegacy(context: Context) {
        prefs(context).edit {
            LEGACY_KEYS.forEach { remove(it) }
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
