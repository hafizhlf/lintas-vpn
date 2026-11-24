package app.lintas.update

enum class UpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    ERROR,
}

data class ReleaseInfo(
    val versionName: String,
    val notes: String,
    val pageUrl: String,
)

data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val currentVersion: String,
    val release: ReleaseInfo? = null,
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

internal class GenerationGate {
    private var value = 0L
    private val lock = Any()

    fun next(): Long = synchronized(lock) {
        value += 1
        value
    }

    fun invalidate() {
        synchronized(lock) {
            value += 1
        }
    }

    fun isCurrent(token: Long): Boolean = synchronized(lock) {
        token == value
    }
}

internal fun canApplyCheckResult(
    token: Long,
    gate: GenerationGate,
    status: UpdateStatus,
): Boolean = gate.isCurrent(token) && status == UpdateStatus.CHECKING
