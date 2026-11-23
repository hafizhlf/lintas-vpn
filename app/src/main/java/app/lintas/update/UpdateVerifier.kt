package app.lintas.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class VerifiedApk(
    val versionCode: Long,
    val versionName: String,
)

object UpdateVerifier {
    private const val HEX = "0123456789abcdef"

    fun verify(
        context: Context,
        apk: File,
        expectedVersionName: String,
        expectedSha256: String?,
        expectedSize: Long,
    ): VerifiedApk {
        if (!apk.isFile) {
            throw UpdateException.Verification("File APK tidak ditemukan.")
        }
        if (apk.length() != expectedSize) {
            throw UpdateException.Verification("Ukuran APK tidak cocok.")
        }
        if (expectedSha256 != null && !sha256(apk).equals(expectedSha256, ignoreCase = true)) {
            throw UpdateException.Verification("Checksum APK tidak cocok.")
        }

        val packageManager = context.packageManager
        val archive = getArchiveInfo(packageManager, apk)
            ?: throw UpdateException.Verification("APK tidak bisa dibaca.")
        if (archive.packageName != context.packageName) {
            throw UpdateException.Verification("APK bukan untuk aplikasi ini.")
        }
        val installed = getInstalledInfo(packageManager, context.packageName)
        if (archive.longVersionCode <= installed.longVersionCode) {
            throw UpdateException.Verification("APK bukan versi yang lebih baru.")
        }
        if (archive.versionName != expectedVersionName) {
            throw UpdateException.Verification("Versi APK tidak cocok dengan rilis.")
        }

        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() || installedSigners != archiveSigners) {
            throw UpdateException.Verification(
                "APK ditandatangani dengan kunci berbeda. Pembaruan ditolak.",
            )
        }

        return VerifiedApk(
            versionCode = archive.longVersionCode,
            versionName = archive.versionName ?: expectedVersionName,
        )
    }

    fun sha256(file: File): String = file.inputStream().use { digest(it) }

    fun sha256(bytes: ByteArray): String = bytes.inputStream().use { digest(it) }

    private fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        val bytes = digest.digest()
        val output = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            output.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return output.toString()
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signingInfo = info.signingInfo ?: return emptySet()
        return signingInfo.apkContentsSigners
            .map { signature -> sha256(signature.toByteArray()) }
            .toSet()
    }

    private fun getArchiveInfo(packageManager: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun getInstalledInfo(packageManager: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
}
