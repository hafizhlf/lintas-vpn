package app.lintas.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class LatestRelease(
    val version: ParsedVersion,
    val info: ReleaseInfo,
)

sealed interface ReleaseParseResult {
    data class Newer(val release: LatestRelease) : ReleaseParseResult
    data class UpToDate(val versionName: String) : ReleaseParseResult
    data class Invalid(val message: String) : ReleaseParseResult
}

sealed class UpdateException(message: String) : Exception(message) {
    data object NotFound : UpdateException("Belum ada rilis publik.")
    data class RateLimited(val resetAtEpochSeconds: Long?) :
        UpdateException("Batas pemeriksaan GitHub tercapai.")

    data class Http(val code: Int) : UpdateException("GitHub mengembalikan HTTP $code.")
    data class Network(override val message: String) : UpdateException(message)
    data class Verification(override val message: String) : UpdateException(message)
}

object UpdateApi {
    const val RELEASE_URL = "https://api.github.com/repos/hafizhlf/lintas-vpn/releases/latest"

    internal val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    internal fun parseRelease(json: String, currentVersion: String): ReleaseParseResult {
        val root = runCatching { JSONObject(json) }.getOrElse {
            return ReleaseParseResult.Invalid("Respons GitHub tidak valid.")
        }
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            return ReleaseParseResult.Invalid("Rilis terbaru bukan rilis stabil.")
        }
        val tag = root.optString("tag_name")
        val version = parseVersionTag(tag)
            ?: return ReleaseParseResult.Invalid("Format versi rilis tidak dikenal.")
        if (!isNewerVersion(tag, currentVersion)) {
            return ReleaseParseResult.UpToDate(version.toString())
        }
        val assetArray = root.optJSONArray("assets")
        val assets = buildList {
            if (assetArray != null) {
                for (index in 0 until assetArray.length()) {
                    val asset = assetArray.optJSONObject(index) ?: continue
                    add(
                        ReleaseAsset(
                            name = asset.optString("name"),
                            state = asset.optString("state"),
                            size = asset.optLong("size"),
                            downloadUrl = asset.optString("browser_download_url"),
                            sha256 = parseSha256Digest(asset.optString("digest").takeIf { it.isNotBlank() }),
                        ),
                    )
                }
            }
        }
        val apk = pickApkAsset(assets, version.toString())
            ?: return ReleaseParseResult.Invalid("Rilis terbaru tidak menyertakan APK.")
        return ReleaseParseResult.Newer(
            LatestRelease(
                version = version,
                info = ReleaseInfo(
                    versionName = version.toString(),
                    tag = tag,
                    notes = root.optString("body"),
                    pageUrl = root.optString("html_url"),
                    apkUrl = apk.downloadUrl,
                    apkSize = apk.size,
                    apkSha256 = apk.sha256,
                ),
            ),
        )
    }

    suspend fun fetchLatest(currentVersion: String): ReleaseParseResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lintas/$currentVersion (Android)")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw UpdateException.NotFound
                    response.code == 403 && response.header("X-RateLimit-Remaining") == "0" ->
                        throw UpdateException.RateLimited(response.header("X-RateLimit-Reset")?.toLongOrNull())
                    !response.isSuccessful -> throw UpdateException.Http(response.code)
                    else -> parseRelease(
                        response.body?.string() ?: throw UpdateException.Network("Respons GitHub kosong."),
                        currentVersion,
                    )
                }
            }
        } catch (error: IOException) {
            throw UpdateException.Network("Tidak bisa menghubungi GitHub. Periksa koneksi internet.")
        }
    }
}
