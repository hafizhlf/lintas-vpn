# Lintas Self-Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-app updater that checks GitHub Releases, downloads the APK, verifies its authenticity and version, and opens the Android installer.

**Architecture:** A singleton `UpdateController` exposes `StateFlow<UpdateState>`. It reads `GET /repos/hafizhlf/lintas-vpn/releases/latest` with OkHttp, persists the last result in `lintas_update` SharedPreferences, downloads to `cacheDir/updates/`, verifies SHA-256/package/version/signing certificate, then shares the APK through a private `FileProvider` and launches the system installer from the visible Activity. No background service, no token, no `QUERY_ALL_PACKAGES`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, OkHttp 4, `org.json`, Android `PackageManager`, `FileProvider`, `ACTION_VIEW` installer intent, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-23-lintas-self-update-design.md`

**Execution status (2026-11-23):** Tasks 1–10 complete; `v1.0.7` released.

## Global Constraints

- `minSdk 29`, `targetSdk 36`, `compileSdk 36`, JDK 21, build tools 36.0.0.
- Release target: `versionCode 17`, `versionName 1.0.7`.
- Feed: `https://api.github.com/repos/hafizhlf/lintas-vpn/releases/latest`, public repo, **no GitHub token in the APK**.
- Stable releases only; ignore `prerelease` and `draft`.
- Never install without all of: SHA-256 match when present, package `app.lintas`, `versionCode` greater than installed, `versionName` equal to the tag version, and signing-certificate set equal to the installed app.
- Do **not** add `QUERY_ALL_PACKAGES`; the only new permission is `REQUEST_INSTALL_PACKAGES`.
- No silent install. The system installer confirmation is always shown.
- All UI copy is Indonesian, matching the existing app.
- `READY` must survive process death; `DOWNLOADING` must not. A canceled installer keeps the verified APK for retry.
- Downloads run only while the app process is alive; no new foreground service or download notification.
- The repo must be public before the 1.0.7 release. Do not expose a release keystore or token in git.
- Release certificate SHA-256: `0fa1eb301523054e861ea58e2a6dd681539a904163f28d61ceff0f110bc411d5`.

## Review Focus

- An APK with the same package and a higher version but a different signing key must be rejected before the installer opens.
- A replayed older signed APK must be rejected by `versionCode`, even if its tag says otherwise.
- A release with no APK, only `.idsig`, or a non-`uploaded` asset must produce a clear error, not a crash.
- Force-stopping the app after a verified download must restore `READY` without downloading again.
- Canceling the system installer must leave `READY` and allow retry without a re-download.

---

### Task 1: Foundation and Pure Update Rules

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/app/lintas/update/UpdateModels.kt`
- Test: `app/src/test/java/app/lintas/update/UpdateModelsTest.kt`

**Interfaces:**
- Produces:
  - `enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }`
  - `data class UpdateState(val status: UpdateStatus = UpdateStatus.IDLE, val currentVersion: String, val release: ReleaseInfo? = null, val progress: Int = 0, val error: String? = null, val justUpdatedTo: String? = null)`
  - `data class ParsedVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ParsedVersion>`
  - `fun parseVersionTag(tag: String): ParsedVersion?`
  - `fun isNewerVersion(remote: String, current: String): Boolean`
  - `data class ReleaseAsset(val name: String, val state: String, val size: Long, val downloadUrl: String, val sha256: String?)`
  - `const val MAX_APK_BYTES: Long = 150L * 1024L * 1024L`
  - `fun pickApkAsset(assets: List<ReleaseAsset>, version: String): ReleaseAsset?`
  - `fun parseSha256Digest(digest: String?): String?`
  - `fun formatUpdateSize(bytes: Long): String`
  - `data class ReleaseInfo` is declared in Task 2; this task references it in `UpdateState`. Create a temporary placeholder only if compilation requires it, and replace it in Task 2. Preferred: do Task 1 and Task 2 in the same working session before committing, or move `ReleaseInfo` into `UpdateModels.kt` now. **Decision:** declare `ReleaseInfo` in `UpdateModels.kt` now with the fields from the spec; Task 2 adds `LatestRelease` and parsing.

- [ ] **Step 1: Update build configuration**

In `app/build.gradle.kts`:

```kotlin
versionCode = (project.findProperty("lintasVersionCode") as String?)?.toInt() ?: 17
versionName = project.findProperty("lintasVersionName") as String? ?: "1.0.7"
```

Change the existing `buildFeatures` block to:

```kotlin
buildFeatures {
    compose = true
    buildConfig = true
}
```

Add to `dependencies`:

```kotlin
testImplementation("org.json:json:20240303")
```

The property overrides exist only for repeatable test builds; release builds use the defaults.

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/app/lintas/update/UpdateModelsTest.kt`:

```kotlin
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
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests 'app.lintas.update.UpdateModelsTest'`

Expected: compilation fails with unresolved references to `ParsedVersion`, `parseVersionTag`, `ReleaseAsset`, `pickApkAsset`, `parseSha256Digest`, `formatUpdateSize`.

- [ ] **Step 4: Implement `UpdateModels.kt`**

Implement the signatures above. Details that are not free choices:

- Tag regex: `^v?(\d+)\.(\d+)\.(\d+)$`; reject anything else, including suffixes.
- `ParsedVersion.compareTo` compares major, then minor, then patch.
- `pickApkAsset` filters `state == "uploaded"`, name ends with `.apk` (case-insensitive), **not** `.apk.idsig`, and `size in 1L..MAX_APK_BYTES`; then prefers `lintas-<version>.apk`, otherwise the first remaining asset.
- `parseSha256Digest` lowercases, accepts exactly `sha256:` followed by 64 hex characters, and returns only the 64-character hex string.
- `formatUpdateSize` uses 1024-based units with one decimal, matching the existing `formatBytes` style.

Declare `ReleaseInfo` here with the exact fields:

```kotlin
data class ReleaseInfo(
    val versionName: String,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String?,
)
```

- [ ] **Step 5: Run tests to verify they pass**

Run the same command. Expected: `BUILD SUCCESSFUL`, all 8 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/app/lintas/update/UpdateModels.kt app/src/test/java/app/lintas/update/UpdateModelsTest.kt
git commit -m "feat: add update version and asset rules"
```

---

### Task 2: GitHub Release Parsing and Client

**Files:**
- Create: `app/src/main/java/app/lintas/update/UpdateApi.kt`
- Test: `app/src/test/java/app/lintas/update/UpdateApiTest.kt`

**Interfaces:**
- Consumes: `ParsedVersion`, `parseVersionTag`, `isNewerVersion`, `ReleaseAsset`, `pickApkAsset`, `parseSha256Digest`, `ReleaseInfo`.
- Produces:
  - `data class LatestRelease(val version: ParsedVersion, val info: ReleaseInfo)`
  - `sealed interface ReleaseParseResult { data class Newer(val release: LatestRelease) : ReleaseParseResult; data class UpToDate(val versionName: String) : ReleaseParseResult; data class Invalid(val message: String) : ReleaseParseResult }`
  - `object UpdateApi { internal fun parseRelease(json: String, currentVersion: String): ReleaseParseResult; suspend fun fetchLatest(currentVersion: String): ReleaseParseResult; internal val httpClient: OkHttpClient }`
  - `sealed class UpdateException(message: String) : Exception(message)` with `NotFound`, `RateLimited(val resetAtEpochSeconds: Long?)`, `Http(val code: Int)`, `Network(message)`, `Verification(message)`.
  - `const val RELEASE_URL = "https://api.github.com/repos/hafizhlf/lintas-vpn/releases/latest"`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/app/lintas/update/UpdateApiTest.kt`:

```kotlin
package app.lintas.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests 'app.lintas.update.UpdateApiTest'`

Expected: compilation fails because `UpdateApi`, `ReleaseParseResult`, and `LatestRelease` do not exist.

- [ ] **Step 3: Implement `parseRelease`**

Implement `UpdateApi.parseRelease(json: String, currentVersion: String): ReleaseParseResult`:

- Parse with `JSONObject`. Return `Invalid("Respons GitHub tidak valid.")` on malformed JSON.
- If `draft` or `prerelease` is true, return `Invalid("Rilis terbaru bukan rilis stabil.")`.
- Read `tag_name`; if `parseVersionTag` returns null, return `Invalid("Format versi rilis tidak dikenal.")`.
- If the tag is not newer than `currentVersion`, return `UpToDate(version.toString())`.
- Read `assets`; build `ReleaseAsset` entries. A blank or non-`sha256:` digest becomes `null`.
- Pick the asset with `pickApkAsset(assets, version.toString())`; if null, return `Invalid("Rilis terbaru tidak menyertakan APK.")`.
- Return `Newer(LatestRelease(version, ReleaseInfo(...)))` using `body`, `html_url`, the asset URL, size, and SHA-256.

- [ ] **Step 4: Run tests to verify they pass**

Run the same command. Expected: all 6 tests pass.

- [ ] **Step 5: Implement `fetchLatest` and the OkHttp client**

Add:

```kotlin
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
```

`httpClient` is a single shared `OkHttpClient` with connect 15 seconds, read 60 seconds, no call timeout, redirects enabled. It must be `internal` so the controller can reuse it for the download.

- [ ] **Step 6: Compile and commit**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

```bash
git add app/src/main/java/app/lintas/update/UpdateApi.kt app/src/test/java/app/lintas/update/UpdateApiTest.kt
git commit -m "feat: parse GitHub release feed"
```

---

### Task 3: Update Persistence

**Files:**
- Create: `app/src/main/java/app/lintas/update/UpdateStore.kt`

**Interfaces:**
- Consumes: `ReleaseInfo`.
- Produces:
  - `data class StoredCheck(val versionName: String, val checkedAt: Long, val release: ReleaseInfo?)`
  - `object UpdateStore`:
    - `fun loadCheck(context: Context): StoredCheck?`
    - `fun saveCheck(context: Context, release: ReleaseInfo, checkedAt: Long)`
    - `fun saveUpToDate(context: Context, versionName: String, checkedAt: Long)`
    - `fun readyFile(context: Context): String?`
    - `fun saveReadyFile(context: Context, path: String?)`
    - `fun lastAttemptAt(context: Context): Long`
    - `fun setLastAttemptAt(context: Context, value: Long)`
    - `fun lastSeenVersionCode(context: Context): Long?`
    - `fun setLastSeenVersionCode(context: Context, value: Long)`

- [ ] **Step 1: Implement `UpdateStore.kt`**

Use `context.applicationContext.getSharedPreferences("lintas_update", Context.MODE_PRIVATE)` and `androidx.core.content.edit`.

Keys: `last_attempt_at`, `checked_at`, `latest_version`, `latest_tag`, `latest_notes`, `latest_page_url`, `apk_url`, `apk_size`, `apk_sha256`, `ready_file`, `last_seen_version_code`.

Behavior:

- `saveCheck` writes all release fields and `checked_at`. If `latest_version` changes, remove `ready_file` because the old file belongs to a different release.
- `saveUpToDate` writes only `latest_version` and `checked_at`, removes the release fields, and removes `ready_file`.
- `loadCheck` returns null if `latest_version` is blank. Otherwise returns `StoredCheck`; `release` is non-null only when `apk_url` is non-blank.
- `lastSeenVersionCode` returns null when the key is absent; `0` is a valid stored value.
- `saveReadyFile(null)` removes the key.

- [ ] **Step 2: Compile and commit**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

```bash
git add app/src/main/java/app/lintas/update/UpdateStore.kt
git commit -m "feat: persist update check and ready APK"
```

---

### Task 4: APK Verification

**Files:**
- Create: `app/src/main/java/app/lintas/update/UpdateVerifier.kt`
- Test: `app/src/test/java/app/lintas/update/UpdateVerifierTest.kt`

**Interfaces:**
- Consumes: `UpdateException.Verification`.
- Produces:
  - `data class VerifiedApk(val versionCode: Long, val versionName: String)`
  - `object UpdateVerifier`:
    - `fun verify(context: Context, apk: File, expectedVersionName: String, expectedSha256: String?, expectedSize: Long): VerifiedApk`
    - `fun sha256(file: File): String`
    - `fun sha256(bytes: ByteArray): String`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/app/lintas/update/UpdateVerifierTest.kt`:

```kotlin
package app.lintas.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateVerifierTest {
    @Test fun hashesBytesWithSha256() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            UpdateVerifier.sha256("hello".toByteArray()),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests 'app.lintas.update.UpdateVerifierTest'`

Expected: unresolved reference to `UpdateVerifier`.

- [ ] **Step 3: Implement the pure hash functions**

Implement `sha256(bytes: ByteArray)` and `sha256(file: File)` with `MessageDigest.getInstance("SHA-256")`, lowercase hex output. `sha256(file)` reads the file in a buffered stream.

- [ ] **Step 4: Run test to verify it passes**

Run the same command. Expected: 1 test passes.

- [ ] **Step 5: Implement `verify`**

Use `PackageManager`:

- Reject if the file is missing, empty, or `apk.length() != expectedSize`.
- If `expectedSha256 != null`, compare lowercase hex; mismatch → `UpdateException.Verification("Checksum APK tidak cocok.")`.
- Read the archive with `getPackageArchiveInfo` and `GET_SIGNING_CERTIFICATES` (typed `PackageInfoFlags` on API 33+). Null → `"APK tidak bisa dibaca."`.
- Reject `packageName != context.packageName` → `"APK bukan untuk aplikasi ini."`.
- Read the installed package with `GET_SIGNING_CERTIFICATES`.
- Reject `archive.longVersionCode <= installed.longVersionCode` → `"APK bukan versi yang lebih baru."`.
- Reject `archive.versionName != expectedVersionName` → `"Versi APK tidak cocok dengan rilis."`.
- Compare `signingInfo.apkContentsSigners` sets by SHA-256 of each `Signature.toByteArray()`. Empty or unequal sets → `"APK ditandatangani dengan kunci berbeda. Pembaruan ditolak."`.
- Return `VerifiedApk(archive.longVersionCode, archive.versionName ?: expectedVersionName)`.

Because `minSdk` is 29, `longVersionCode` and `GET_SIGNING_CERTIFICATES` are always available; only the typed flag overloads need API 33 guards.

- [ ] **Step 6: Compile and commit**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests 'app.lintas.update.UpdateVerifierTest'`

Expected: `BUILD SUCCESSFUL`, 1 test passes.

```bash
git add app/src/main/java/app/lintas/update/UpdateVerifier.kt app/src/test/java/app/lintas/update/UpdateVerifierTest.kt
git commit -m "feat: verify downloaded APK signature and version"
```

---

### Task 5: Update Controller — Check and Restore

**Files:**
- Create: `app/src/main/java/app/lintas/update/UpdateController.kt`

**Interfaces:**
- Consumes: `UpdateApi`, `UpdateStore`, `UpdateVerifier`, `UpdateState`, `UpdateStatus`, `ReleaseInfo`, `isNewerVersion`, `DiagnosticLog`.
- Produces:
  - `object UpdateController`:
    - `val state: StateFlow<UpdateState>`
    - `fun initialize(context: Context)`
    - `fun check(context: Context, manual: Boolean)`
    - `fun clearJustUpdated()`

- [ ] **Step 1: Implement the controller skeleton**

Use `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and `MutableStateFlow(UpdateState(currentVersion = BuildConfig.VERSION_NAME))`.

`initialize(context)` must run only once per process (`@Volatile private var initialized`). On its IO coroutine:

1. Delete `cacheDir/updates/*.part` and any `.apk` file that is not the path stored in `ready_file`.
2. Read the current `versionCode`; compare with `UpdateStore.lastSeenVersionCode`. If the stored value is non-null and the current value is greater, set `justUpdatedTo = BuildConfig.VERSION_NAME`. Save the current value.
3. Restore `READY`: if `UpdateStore.readyFile` is non-null and the file exists, re-run `UpdateVerifier.verify` with the stored release metadata. On success set `READY` with the stored `ReleaseInfo`. On any failure delete the file, clear `ready_file`, and continue.
4. Otherwise load `StoredCheck`: newer version with a non-null release → `AVAILABLE`; newer version with a null release (inconsistent state) → `IDLE` so a fresh check runs; older/equal → `UP_TO_DATE`; no stored check → `IDLE`.
5. If the status is not `READY` and `now - lastAttemptAt >= 24h`, call `check(manual = false)`.

`check(context, manual)`:

- Return immediately for `CHECKING`, `DOWNLOADING`, or `READY`.
- Capture the previous status, set `CHECKING`, clear `error`, and save `lastAttemptAt = now`.
- Call `UpdateApi.fetchLatest(BuildConfig.VERSION_NAME)`.
- `Newer` → `UpdateStore.saveCheck`, `AVAILABLE`, log `[UPDATE] check available version=...`.
- `UpToDate` → `UpdateStore.saveUpToDate`, `UP_TO_DATE`, and clear `release` because any previously stored release is no longer newer.
- `Invalid` → on manual, `ERROR` with its message; on automatic, restore the previous status silently and log.
- Exceptions → map with `updateErrorMessage` (below); manual shows `ERROR`, automatic restores the previous status.
- Never swallow `CancellationException`; rethrow it.

`updateErrorMessage(error: Throwable)`: map `RateLimited` (include reset time as `HH:mm` when present), `NotFound`, `Http`, `Network`, `Verification`, and unknown errors to the Indonesian messages in the spec.

`clearJustUpdated()` sets `justUpdatedTo = null`.

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/app/lintas/update/UpdateController.kt
git commit -m "feat: check GitHub releases and restore update state"
```

---

### Task 6: Controller — Download, Install, Discard

**Files:**
- Modify: `app/src/main/java/app/lintas/update/UpdateController.kt`
- Create: `app/src/main/res/xml/update_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces:
  - `fun download(context: Context)`
  - `fun cancelDownload(context: Context)`
  - `fun launchInstaller(context: Context)`
  - `fun discardReady(context: Context)`
- FileProvider authority: `${applicationId}.updates`; shared path: `cacheDir/updates/`.

- [ ] **Step 1: Add the manifest permission, provider, and paths**

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

Inside `<application>`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.updates"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/update_paths" />
</provider>
```

Create `app/src/main/res/xml/update_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="updates" path="updates/" />
</paths>
```

- [ ] **Step 2: Implement `download`**

- Guard `status == AVAILABLE` and a non-null `release`.
- Set `DOWNLOADING`, progress `0`, clear `error`.
- Create `cacheDir/updates/`, delete existing files there, and check `usableSpace >= apkSize + 10 MB`; otherwise `ERROR` with the space message.
- Download `release.apkUrl` with `UpdateApi.httpClient` and a `User-Agent` header, writing `lintas-<version>.apk.part` with a 64 KB buffer.
- Total: `response.body.contentLength()` if positive, otherwise `release.apkSize`. Update `progress` at most once per percent.
- After the stream, require the byte count to equal `release.apkSize`.
- Call `UpdateVerifier.verify(app, part, release.versionName, release.apkSha256, release.apkSize)`.
- Rename to `lintas-<version>.apk`, save its path with `UpdateStore.saveReadyFile`, set `READY`, progress 100.
- On `CancellationException`, delete the part and rethrow. On a canceled `Call`, delete the part and return to `AVAILABLE` without an error. On any other failure, delete part/final file, clear `ready_file`, set `ERROR` with the mapped message.
- Log `[UPDATE] download verified version=...` or `[UPDATE] download failed: ...`.

- [ ] **Step 3: Implement `cancelDownload`, `launchInstaller`, and `discardReady`**

`cancelDownload(context)`: set `DOWNLOADING` → `AVAILABLE`, progress 0, cancel the active `Call` and `Job`, and delete `*.part` files. The download coroutine must not overwrite this with `ERROR`.

`launchInstaller(context)`:

- Guard `READY`; require the stored file to exist.
- `FileProvider.getUriForFile(app, "${app.packageName}.updates", file)`.
- `Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)`.
- `context.startActivity(intent)`.
- `ActivityNotFoundException` → `ERROR("Tidak ada aplikasi installer yang bisa membuka APK ini.")`.
- Do **not** change status to an installing state; the user may cancel. Keep `READY`.

`discardReady(context)`:

- Delete the stored file, clear `ready_file`, load `StoredCheck`, and set `AVAILABLE` if the stored version is newer than current, otherwise `UP_TO_DATE` (or `IDLE` if no stored check). Clear progress and error.

- [ ] **Step 4: Build and commit**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`.

```bash
git add app/src/main/java/app/lintas/update/UpdateController.kt app/src/main/res/xml/update_paths.xml app/src/main/AndroidManifest.xml
git commit -m "feat: download and install verified updates"
```

---

### Task 7: Settings UI, Badge, and Update Dialog

**Files:**
- Create: `app/src/main/java/app/lintas/ui/UpdateDialog.kt`
- Modify: `app/src/main/java/app/lintas/ui/LintasApp.kt`

**Interfaces:**
- Consumes: `UpdateController`, `UpdateState`, `UpdateStatus`, `formatUpdateSize`.
- Produces:
  - `@Composable fun UpdateDialog(state: UpdateState, tunnelActive: Boolean, onCheck: () -> Unit, onDownload: () -> Unit, onCancelDownload: () -> Unit, onInstall: () -> Unit, onDiscard: () -> Unit, onOpenRelease: (String) -> Unit, onDismiss: () -> Unit)`
  - `internal fun updateSubtitle(state: UpdateState): String`
  - `internal fun updateAction(state: UpdateState): String`
  - `LintasNavBar(selected: Int, onTab: (Int) -> Unit, updateAvailable: Boolean)`
  - `SettingsScreen(..., update: UpdateState, onOpenUpdate: () -> Unit)`

- [ ] **Step 1: Add the subtitle and action helpers**

Implement `updateSubtitle` and `updateAction` in `UpdateDialog.kt`:

| Status | Subtitle | Action |
|---|---|---|
| `justUpdatedTo != null` | `"Diperbarui ke <version>"` | `"Cek"` |
| `IDLE` | `"Belum diperiksa"` | `"Cek"` |
| `CHECKING` | `"Memeriksa..."` | `"Lihat"` |
| `UP_TO_DATE` | `"Versi <current> adalah yang terbaru"` | `"Cek"` |
| `AVAILABLE` | `"Versi <release.versionName> tersedia"` | `"Unduh"` |
| `DOWNLOADING` | `"Mengunduh <progress>%"` | `"Lihat"` |
| `READY` | `"Siap dipasang · <release.versionName>"` | `"Pasang"` |
| `ERROR` | `"Gagal: <error>"` | `"Coba lagi"` |

- [ ] **Step 2: Add the Settings row**

In `SettingsScreen`, replace the hardcoded `ValueSettingRow("Versi", "1.0.6", ...)` with `BuildConfig.VERSION_NAME`. Add `SettingDivider()` and an `UpdateSettingRow`, a clickable row showing "Pembaruan", `updateSubtitle(state)`, and `updateAction(state)` in the primary color. Tapping the whole row calls `onOpenUpdate`.

- [ ] **Step 3: Implement the dialog**

`UpdateDialog` is an `AlertDialog` with content per status:

- `CHECKING`: small spinner + "Memeriksa pembaruan..."
- `UP_TO_DATE`: "Lintas sudah versi terbaru (<current>)."
- `AVAILABLE`: `"Versi <release.versionName> · <formatUpdateSize(release.apkSize)>"`, notes in `Text(maxLines = 8, overflow = Ellipsis)`, optional "Lihat di GitHub" `TextButton` when `pageUrl` is non-blank.
- `DOWNLOADING`: `LinearProgressIndicator(progress = { state.progress / 100f })`, percent and "Tetap buka Lintas selama mengunduh."
- `READY`: "Unduhan selesai dan sudah diverifikasi." Buttons **Nanti** and **Pasang**, plus a **Hapus unduhan** `TextButton` inside the content (Material 3 `AlertDialog` only has confirm/dismiss slots).
- `ERROR`: `state.error` + **Coba lagi** (`onCheck`; a failed download is re-checked first, then downloaded again, which avoids accidentally downloading a stale release after a check error).
- `IDLE`: "Belum ada pemeriksaan." + **Cek**.

Buttons:

- `AVAILABLE`: confirm **Unduh** (`onDownload`), dismiss **Nanti**.
- `DOWNLOADING`: confirm **Batal** (`onCancelDownload`), dismiss **Tutup** (`onDismiss`; download continues).
- `READY`: confirm **Pasang**, dismiss **Nanti**; **Hapus unduhan** calls `onDiscard`.
- `CHECKING` / `UP_TO_DATE` / `IDLE` / `ERROR`: confirm/dismiss as above; closing never deletes a `READY` file.

Install flow inside the dialog:

1. Use `LocalContext` and `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())` for the unknown-sources settings screen.
2. On **Pasang**, if `packageManager.canRequestPackageInstalls()` is false, show a small explanation dialog: "Izinkan Lintas memasang aplikasi di pengaturan, lalu kembali." with **Buka pengaturan** and **Nanti**. **Buka pengaturan** launches `Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))`; on return, if permission is now granted, continue the install flow.
3. If the app is `CONNECTED` or `CONNECTING`, show a confirmation dialog first: "Pasang pembaruan <version>? Koneksi VPN akan terputus saat aplikasi diperbarui." with **Batal** / **Pasang**.
4. Call `onInstall()`, which runs `UpdateController.launchInstaller(context)`.
5. If the settings or installer intent cannot be launched, show the error text in the dialog; do not crash.

- [ ] **Step 4: Wire `LintasApp`**

- Collect `val update by UpdateController.state.collectAsStateWithLifecycle()`.
- Call `UpdateController.initialize(context)` in the existing `LaunchedEffect(Unit)` after `DiagnosticLog.initialize` and `WarpController.initialize`.
- Add `var showUpdateDialog by rememberSaveable { mutableStateOf(false) }`.
- `updateAvailable = status in { AVAILABLE, DOWNLOADING, READY }`; pass it to `LintasNavBar` and wrap the Setelan icon in `BadgedBox(badge = { Badge() })` only when true and `index == 3`.
- `onOpenUpdate`: set `showUpdateDialog = true`; if status is `IDLE` or `UP_TO_DATE`, call `UpdateController.check(context, manual = true)`; if status is `ERROR` and `update.release == null`, also check. If `ERROR` and a release is present, do **not** re-check; let the dialog's **Coba lagi** retry the download. Clear `justUpdatedTo` when opening.
- Render `UpdateDialog` when `showUpdateDialog`. `onOpenRelease` opens the URL only when it starts with `https://github.com/hafizhlf/lintas-vpn/releases/`, using `Intent.ACTION_VIEW`; otherwise ignore.

- [ ] **Step 5: Build and lint**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug :app:lintDebug`

Expected: `BUILD SUCCESSFUL`; no new lint errors.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/lintas/ui/UpdateDialog.kt app/src/main/java/app/lintas/ui/LintasApp.kt
git commit -m "feat: add update row and installer dialog"
```

---

### Task 8: Documentation

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-11-23-lintas-self-update-design.md`
- Modify: this plan's execution status

- [ ] **Step 1: Update `CHANGELOG.md`**

Add a `## 1.0.7 - 2026-11-23` section:

- Add in-app updates from GitHub Releases.
- Check automatically at most once per 24 hours and manually from Settings.
- Download in-app, verify SHA-256/package/version/signing certificate, then open the Android installer.
- Keep a verified download across process death and installer cancellation; discard it from the dialog.
- Limit: downloads run only while Lintas is open; updating disconnects the VPN.

- [ ] **Step 2: Update `README.md`**

Add "In-app updates from GitHub Releases" to Features. Update Status to mention that the app checks for updates and that the APK is distributed on GitHub. Add a limitation: downloads require the app to stay open, and the first update requires allowing "Install unknown apps" for Lintas.

- [ ] **Step 3: Mark the design status**

Change the design status to "diimplementasikan; verifikasi perangkat tertunda" until Task 9 passes, then update it to the verified result.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md README.md docs/superpowers/specs/2026-11-23-lintas-self-update-design.md docs/superpowers/plans/2026-11-23-lintas-self-update.md
git commit -m "docs: describe in-app updates"
```

---

### Task 9: Device Verification (PASSED — 2026-11-23)

**Result:** passed on the Poco 2602BPC18G (Android 16). Debug 1.0.7 updated to
1.0.8 through the in-app flow; `READY` survived force-stop and installer
cancellation; `Hapus unduhan` returned to `AVAILABLE`; the downgrade and
different-signature releases were rejected; the offline error was clear.
Temporary releases `v1.0.8`, `v1.0.9`, and `v1.0.10` were deleted. The device
is left on debug 1.0.8 with update preferences cleared and the WARP identity
intact. See the design's Verification section for the detailed results.

**Files:**
- Modify: `docs/superpowers/specs/2026-11-23-lintas-self-update-design.md`
- Modify: this plan's execution status

**Prerequisite:** the Poco device is connected (`adb devices` shows `4TDMD6VC65WOMBTS`). The existing installed debug build is 1.0.6.

- [ ] **Step 1: Build and install debug 1.0.7 over the existing debug build**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package app.lintas | grep -E "versionName|versionCode"
```

Expected: `versionCode=17`, `versionName=1.0.7`; WARP identity prefs still exist.

- [ ] **Step 2: Make the repo public and verify the public feed**

Ask the user for the go-ahead immediately before this irreversible visibility change. Then:

```bash
gh repo edit hafizhlf/lintas-vpn --visibility public --accept-visibility-change-consequences
curl -s https://api.github.com/repos/hafizhlf/lintas-vpn/releases/latest | head -40
```

Expected: HTTP 200 with the existing `v1.0.5` release.

- [ ] **Step 3: Publish a temporary debug 1.0.8 release**

Build a debug APK with the test version:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug \
  -PlintasVersionCode=18 -PlintasVersionName=1.0.8
cp app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/lintas-1.0.8.apk
sha256sum /tmp/opencode/lintas-1.0.8.apk > /tmp/opencode/SHA256SUMS-test
gh release create v1.0.8 /tmp/opencode/lintas-1.0.8.apk /tmp/opencode/SHA256SUMS-test \
  --title "Lintas v1.0.8 (test)" --notes "Temporary update test. Deleted after verification." --latest
```

Expected: `gh release view v1.0.8` shows the APK asset and a `digest`.

- [ ] **Step 4: Run the update flow on the device**

1. Open Lintas → Setelan → Pembaruan. Manual check must show "Versi 1.0.8 tersedia".
2. Start the download, press Home for a few seconds, then return. Confirm the download continues while the process lives, or shows a clear retry error if Android killed it.
3. After `READY`, force-stop and reopen; the row must show "Siap dipasang" and must not re-download.
4. Tap **Pasang**; cancel the Android installer; return to Lintas. Status must remain `READY`.
5. Tap **Pasang** again and confirm. The app must update to 1.0.8 with data intact.
6. Reopen; the row must show "Diperbarui ke 1.0.8" once.

- [ ] **Step 5: Test versionCode downgrade rejection**

Build a same-signature debug APK with a newer tag but an older `versionCode`:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug \
  -PlintasVersionCode=16 -PlintasVersionName=1.0.9
cp app/build/outputs/apk/debug/app-debug.apk /tmp/opencode/lintas-1.0.9.apk
gh release create v1.0.9 /tmp/opencode/lintas-1.0.9.apk \
  --title "Lintas v1.0.9 (downgrade test)" --notes "Temporary downgrade test." --latest
```

From the device: check, download, and confirm the app rejects it after download with "APK bukan versi yang lebih baru." and never opens the installer. Delete the release after the test.

- [ ] **Step 6: Test signature rejection**

Create a minimal `app.lintas` APK, `versionCode 20`, `versionName 1.0.10`, signed with a different key:

```bash
keytool -genkeypair -keystore /tmp/opencode/other-debug.jks -alias other \
  -storepass android -keypass android -dname "CN=Other" -keyalg RSA -keysize 2048 -validity 10000
# Build a minimal APK with package app.lintas, versionCode 20, versionName 1.0.10,
# using aapt/d8/zipalign/apksigner; sign with other-debug.jks.
```

Publish it as a temporary `v1.0.10` release. From the device: check, download, and confirm the app rejects it before opening the installer with the "kunci berbeda" message.

- [ ] **Step 7: Test offline and rate-limit errors**

Enable airplane mode, open the update dialog, and confirm the network error message. Disable airplane mode and retry successfully. Do not attempt to exhaust the GitHub rate limit deliberately.

- [ ] **Step 8: Clean up temporary releases and files**

```bash
gh release delete v1.0.8 --cleanup-tag --yes
gh release delete v1.0.9 --cleanup-tag --yes
gh release delete v1.0.10 --cleanup-tag --yes
rm -f /tmp/opencode/lintas-1.0.8.apk /tmp/opencode/lintas-1.0.9.apk \
  /tmp/opencode/lintas-1.0.10.apk /tmp/opencode/SHA256SUMS-test /tmp/opencode/other-debug.jks
```

Verify only `v1.0.5` (and later the real `v1.0.7`) remains.

- [ ] **Step 9: Record the results and commit**

Update the design's Verification section with the device result and the temporary-release cleanup. Leave the device on debug 1.0.8; ask the user before uninstalling it to install the release 1.0.7, because that deletes the local WARP identity.

```bash
git add docs/superpowers/specs/2026-11-23-lintas-self-update-design.md docs/superpowers/plans/2026-11-23-lintas-self-update.md
git commit -m "docs: record self-update device verification"
```

---

### Task 10: Release 1.0.7

**Files:**
- Modify: `dist/SHA256SUMS` (not tracked)
- Modify: `CHANGELOG.md` / docs if needed

**Prerequisite:** Task 9 passed and the user explicitly approves publishing.

- [ ] **Step 1: Run the full verification suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Expected: all unit tests pass, lint has 0 errors, release build succeeds.

- [ ] **Step 2: Sign, align, and verify the release APK**

Use the release keystore at `$HOME/.config/lintas/lintas-release.jks` and the passwords from `$HOME/.config/lintas/release.properties`:

```bash
store_pass=$(sed -n 's/^storePassword=//p' "$HOME/.config/lintas/release.properties")
key_pass=$(sed -n 's/^keyPassword=//p' "$HOME/.config/lintas/release.properties")
LINTAS_STORE_PASS="$store_pass" LINTAS_KEY_PASS="$key_pass" \
/opt/android-sdk/build-tools/36.0.0/apksigner sign \
  --ks "$HOME/.config/lintas/lintas-release.jks" \
  --ks-key-alias lintas --ks-pass env:LINTAS_STORE_PASS --key-pass env:LINTAS_KEY_PASS \
  --out dist/lintas-1.0.7.apk app/build/outputs/apk/release/app-release-unsigned.apk
/opt/android-sdk/build-tools/36.0.0/zipalign -c -p 4 dist/lintas-1.0.7.apk
/opt/android-sdk/build-tools/36.0.0/apksigner verify --print-certs dist/lintas-1.0.7.apk
sha256sum dist/lintas-1.0.7.apk
```

Expected: zipalign OK; certificate SHA-256 is `0fa1eb301523054e861ea58e2a6dd681539a904163f28d61ceff0f110bc411d5`.

- [ ] **Step 3: Update `dist/SHA256SUMS`**

Remove any old 1.0.7 line, append the new hash, and keep the previous entries.

- [ ] **Step 4: Commit and push `main`**

Ask the user for the final go-ahead. Then:

```bash
git status --short
git add CHANGELOG.md README.md docs/superpowers/specs/2026-11-23-lintas-self-update-design.md docs/superpowers/plans/2026-11-23-lintas-self-update.md
git commit -m "Lintas v1.0.7: in-app updates"   # skip if the tree is already clean
git push origin main
```

- [ ] **Step 5: Create the GitHub release**

Write `/tmp/opencode/lintas-1.0.7-notes.md` first. It must include the SHA-256 of `lintas-1.0.7.apk`, install instructions, and the known limitations (download requires the app open; enabling "Install unknown apps"; updating disconnects the VPN). Then:

```bash
gh release create v1.0.7 dist/lintas-1.0.7.apk dist/SHA256SUMS \
  --title "Lintas v1.0.7" --notes-file /tmp/opencode/lintas-1.0.7-notes.md --latest
```

- [ ] **Step 6: Verify the public release**

```bash
curl -s https://api.github.com/repos/hafizhlf/lintas-vpn/releases/latest \
  | grep -E '"tag_name"|"name"|"digest"|"browser_download_url"'
```

Expected: `v1.0.7`, asset `lintas-1.0.7.apk`, and a `sha256:` digest matching the local hash.

- [ ] **Step 7: Report**

Report the release URL, APK SHA-256, test results, and the device state. Mention that the first real self-update will be the next release after 1.0.7.
