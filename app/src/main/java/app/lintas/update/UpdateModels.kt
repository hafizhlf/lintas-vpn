package app.lintas.update

import java.util.Locale

enum class UpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    ERROR,
}

data class ReleaseInfo(
    val versionName: String,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String?,
)

data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val currentVersion: String,
    val release: ReleaseInfo? = null,
    val progress: Int = 0,
    val error: String? = null,
    val justUpdatedTo: String? = null,
)

data class ParsedVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ParsedVersion> {
    override fun compareTo(other: ParsedVersion): Int {
        val majorDiff = major.compareTo(other.major)
        if (majorDiff != 0) return majorDiff
        val minorDiff = minor.compareTo(other.minor)
        if (minorDiff != 0) return minorDiff
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

private val VERSION_TAG = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

fun parseVersionTag(tag: String): ParsedVersion? {
    val match = VERSION_TAG.matchEntire(tag.trim()) ?: return null
    return ParsedVersion(
        major = match.groupValues[1].toIntOrNull() ?: return null,
        minor = match.groupValues[2].toIntOrNull() ?: return null,
        patch = match.groupValues[3].toIntOrNull() ?: return null,
    )
}

fun isNewerVersion(remote: String, current: String): Boolean {
    val remoteVersion = parseVersionTag(remote) ?: return false
    val currentVersion = parseVersionTag(current) ?: return false
    return remoteVersion > currentVersion
}

data class ReleaseAsset(
    val name: String,
    val state: String,
    val size: Long,
    val downloadUrl: String,
    val sha256: String?,
)

const val MAX_APK_BYTES: Long = 150L * 1024L * 1024L

fun pickApkAsset(assets: List<ReleaseAsset>, version: String): ReleaseAsset? {
    val apks = assets.filter { asset ->
        asset.state == "uploaded" &&
            asset.name.endsWith(".apk", ignoreCase = true) &&
            !asset.name.endsWith(".apk.idsig", ignoreCase = true) &&
            asset.size in 1L..MAX_APK_BYTES
    }
    return apks.firstOrNull { it.name == "lintas-$version.apk" } ?: apks.firstOrNull()
}

fun parseSha256Digest(digest: String?): String? {
    val value = digest?.trim()?.lowercase(Locale.US) ?: return null
    if (!value.startsWith("sha256:")) return null
    val hex = value.removePrefix("sha256:")
    return hex.takeIf { candidate ->
        candidate.length == 64 && candidate.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

fun formatUpdateSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
