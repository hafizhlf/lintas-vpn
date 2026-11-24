package app.lintas.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateApiTest {
    private fun releaseJson(
        tag: String = "v1.0.8",
        prerelease: Boolean = false,
        draft: Boolean = false,
    ): String = JSONObject().apply {
        put("tag_name", tag)
        put("body", "Catatan rilis")
        put("prerelease", prerelease)
        put("draft", draft)
    }.toString()

    @Test fun returnsNewerReleaseWithoutApkAsset() {
        val result = UpdateApi.parseRelease(releaseJson(), "1.0.7")
        assertTrue(result is ReleaseParseResult.Newer)
        val release = (result as ReleaseParseResult.Newer).release.info
        assertEquals("1.0.8", release.versionName)
        assertEquals("Catatan rilis", release.notes)
        assertEquals(
            "https://github.com/hafizhlf/lintas-vpn/releases/tag/v1.0.8",
            release.pageUrl,
        )
    }

    @Test fun returnsUpToDateForSameVersion() {
        val result = UpdateApi.parseRelease(releaseJson(tag = "v1.0.7"), "1.0.7")
        assertEquals(ReleaseParseResult.UpToDate("1.0.7"), result)
    }

    @Test fun rejectsPrerelease() {
        val result = UpdateApi.parseRelease(releaseJson(prerelease = true), "1.0.7")
        assertTrue(result is ReleaseParseResult.Invalid)
    }

    @Test fun rejectsDraft() {
        val result = UpdateApi.parseRelease(releaseJson(draft = true), "1.0.7")
        assertTrue(result is ReleaseParseResult.Invalid)
    }

    @Test fun rejectsMalformedTag() {
        val result = UpdateApi.parseRelease(releaseJson(tag = "latest"), "1.0.7")
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
