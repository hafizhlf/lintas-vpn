package app.lintas.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateVerifierTest {
    private val installed = ApkIdentity(
        packageName = "app.lintas",
        versionCode = 17,
        versionName = "1.0.7",
        signerDigests = setOf("aa"),
    )
    private val archive = installed.copy(versionCode = 18, versionName = "1.0.8")

    @Test fun hashesBytesWithSha256() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            UpdateVerifier.sha256("hello".toByteArray()),
        )
    }

    @Test fun acceptsSameKeyNewerVersion() {
        assertNull(apkVerificationError(installed, archive, "1.0.8"))
    }

    @Test fun rejectsWrongPackage() {
        assertEquals(
            "APK bukan untuk aplikasi ini.",
            apkVerificationError(installed, archive.copy(packageName = "other.app"), "1.0.8"),
        )
    }

    @Test fun rejectsDowngrade() {
        assertEquals(
            "APK bukan versi yang lebih baru.",
            apkVerificationError(installed, archive.copy(versionCode = 17), "1.0.8"),
        )
    }

    @Test fun rejectsVersionNameMismatch() {
        assertEquals(
            "Versi APK tidak cocok dengan rilis.",
            apkVerificationError(installed, archive.copy(versionName = "1.0.9"), "1.0.8"),
        )
    }

    @Test fun rejectsDifferentSigner() {
        assertEquals(
            "APK ditandatangani dengan kunci berbeda. Pembaruan ditolak.",
            apkVerificationError(installed, archive.copy(signerDigests = setOf("bb")), "1.0.8"),
        )
    }

    @Test fun rejectsEmptySigners() {
        assertEquals(
            "APK ditandatangani dengan kunci berbeda. Pembaruan ditolak.",
            apkVerificationError(installed, archive.copy(signerDigests = emptySet()), "1.0.8"),
        )
    }

    @Test fun acceptsMatchingFile() {
        assertNull(fileVerificationError(10, 10, "aa", "aa"))
    }

    @Test fun rejectsSizeMismatch() {
        assertEquals(
            "Ukuran APK tidak cocok.",
            fileVerificationError(9, 10, "aa", "aa"),
        )
    }

    @Test fun rejectsDigestMismatch() {
        assertEquals(
            "Checksum APK tidak cocok.",
            fileVerificationError(10, 10, "bb", "aa"),
        )
    }

    @Test fun skipsDigestWhenExpectedIsNull() {
        assertNull(fileVerificationError(10, 10, null, null))
    }
}
