package app.lintas.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateApiTest {
    private val digest = "a".repeat(64)

    private fun releaseJson(
        tag: String = "v1.0.8",
        prerelease: Boolean = false,
        draft: Boolean = false,
        assetName: String = "lintas-1.0.8.apk",
        assetState: String = "uploaded",
        assetSize: Long = 56_000_000,
    ): String = JSONObject().apply {
        put("tag_name", tag)
        put("body", "Catatan rilis")
        put("html_url", "https://github.com/hafizhlf/lintas-vpn/releases/tag/$tag")
        put("prerelease", prerelease)
        put("draft", draft)
        put("assets", JSONArray().apply {
            put(JSONObject().apply {
                put("name", assetName)
                put("state", assetState)
                put("size", assetSize)
                put("browser_download_url", "https://example.com/$assetName")
                put("digest", "sha256:$digest")
            })
        })
    }.toString()

    @Test fun returnsNewerReleaseWithApk() {
        val result = UpdateApi.parseRelease(releaseJson(), "1.0.7")
        assertTrue(result is ReleaseParseResult.Newer)
        val release = (result as ReleaseParseResult.Newer).release.info
        assertEquals("1.0.8", release.versionName)
        assertEquals("v1.0.8", release.tag)
        assertEquals("https://example.com/lintas-1.0.8.apk", release.apkUrl)
        assertEquals(digest, release.apkSha256)
    }

    @Test fun returnsUpToDateForSameVersion() {
        val result = UpdateApi.parseRelease(releaseJson(tag = "v1.0.7"), "1.0.7")
        assertEquals(ReleaseParseResult.UpToDate("1.0.7"), result)
    }

    @Test fun rejectsPrerelease() {
        val result = UpdateApi.parseRelease(releaseJson(prerelease = true), "1.0.7")
        assertTrue(result is ReleaseParseResult.Invalid)
    }

    @Test fun rejectsMissingApk() {
        val result = UpdateApi.parseRelease(releaseJson(assetName = "lintas-1.0.8.apk.idsig"), "1.0.7")
        assertTrue(result is ReleaseParseResult.Invalid)
    }

    @Test fun rejectsMalformedTag() {
        val result = UpdateApi.parseRelease(releaseJson(tag = "latest"), "1.0.7")
        assertTrue(result is ReleaseParseResult.Invalid)
    }

    @Test fun ignoresNonUploadedAssets() {
        val result = UpdateApi.parseRelease(releaseJson(assetState = "new"), "1.0.7")
        assertTrue(result is ReleaseParseResult.Invalid)
    }

    @Test fun mapsPrimaryRateLimit() {
        assertEquals(
            RateLimitInfo(1234L),
            rateLimitInfo(403, "0", null, "1234", 100),
        )
    }

    @Test fun mapsSecondaryRateLimitWithRetryAfter() {
        assertEquals(
            RateLimitInfo(130L),
            rateLimitInfo(429, null, "30", null, 100),
        )
    }

    @Test fun maps429WithoutHeaders() {
        assertEquals(RateLimitInfo(null), rateLimitInfo(429, null, null, null, 100))
    }

    @Test fun ignoresNonRateLimitResponses() {
        assertNull(rateLimitInfo(200, null, null, null, 100))
        assertNull(rateLimitInfo(404, null, null, null, 100))
        assertNull(rateLimitInfo(403, "12", null, "1234", 100))
    }
}
