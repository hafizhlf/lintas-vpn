package app.lintas.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateModelsTest {
    @Test fun parsesVersionTagWithAndWithoutV() {
        assertEquals(ParsedVersion(1, 0, 7), parseVersionTag("v1.0.7"))
        assertEquals(ParsedVersion(1, 0, 7), parseVersionTag("1.0.7"))
    }

    @Test fun rejectsNonStableOrMalformedTags() {
        assertNull(parseVersionTag("v1.0.7-beta"))
        assertNull(parseVersionTag("v1.0"))
        assertNull(parseVersionTag("latest"))
    }

    @Test fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("v1.0.10", "1.0.9"))
        assertTrue(isNewerVersion("1.1.0", "1.0.99"))
        assertFalse(isNewerVersion("1.0.7", "1.0.7"))
        assertFalse(isNewerVersion("1.0.6", "1.0.7"))
    }

    @Test fun prefersExactApkAssetAndIgnoresIdsig() {
        val assets = listOf(
            ReleaseAsset("lintas-1.0.8.apk.idsig", "uploaded", 100, "idsig", null),
            ReleaseAsset("lintas-other.apk", "uploaded", 100, "other", null),
            ReleaseAsset("lintas-1.0.8.apk", "uploaded", 100, "exact", null),
        )
        assertEquals("exact", pickApkAsset(assets, "1.0.8")?.downloadUrl)
    }

    @Test fun fallsBackToFirstUploadedApk() {
        val assets = listOf(
            ReleaseAsset("notes.txt", "uploaded", 10, "notes", null),
            ReleaseAsset("lintas-1.0.8.apk", "uploaded", 100, "apk", null),
        )
        assertEquals("apk", pickApkAsset(assets, "1.0.8")?.downloadUrl)
    }

    @Test fun rejectsMissingOrOversizedApk() {
        assertNull(pickApkAsset(emptyList(), "1.0.8"))
        assertNull(pickApkAsset(listOf(ReleaseAsset("lintas-1.0.8.apk", "uploaded", 0, "x", null)), "1.0.8"))
        assertNull(
            pickApkAsset(
                listOf(ReleaseAsset("lintas-1.0.8.apk", "uploaded", MAX_APK_BYTES + 1, "x", null)),
                "1.0.8",
            ),
        )
    }

    @Test fun parsesSha256Digest() {
        val hex = "a".repeat(64)
        assertEquals(hex, parseSha256Digest("sha256:$hex"))
        assertEquals(hex, parseSha256Digest("SHA256:${hex.uppercase()}"))
        assertNull(parseSha256Digest(null))
        assertNull(parseSha256Digest("sha256:abc"))
        assertNull(parseSha256Digest("md5:$hex"))
    }

    @Test fun formatsSize() {
        assertEquals("0 B", formatUpdateSize(0L))
        assertEquals("1.0 KB", formatUpdateSize(1024L))
        assertEquals("1.0 MB", formatUpdateSize(1024L * 1024L))
    }

    @Test fun generationGateInvalidatesOlderTokens() {
        val gate = GenerationGate()
        val first = gate.next()
        assertTrue(gate.isCurrent(first))
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        val second = gate.next()
        assertTrue(gate.isCurrent(second))
        assertFalse(gate.isCurrent(first))
    }

    @Test fun checkResultRequiresCurrentTokenAndCheckingStatus() {
        val gate = GenerationGate()
        val token = gate.next()
        assertTrue(canApplyCheckResult(token, gate, UpdateStatus.CHECKING))
        assertFalse(canApplyCheckResult(token, gate, UpdateStatus.READY))
        assertFalse(canApplyCheckResult(token, gate, UpdateStatus.DOWNLOADING))
        gate.invalidate()
        assertFalse(canApplyCheckResult(token, gate, UpdateStatus.CHECKING))
    }
}
