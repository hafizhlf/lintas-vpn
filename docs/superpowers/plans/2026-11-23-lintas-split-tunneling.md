# Lintas Split Tunneling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users exclude selected launcher apps from the WARP tunnel, with the choice persisted and applied the next time the TUN interface is created.

**Architecture:** Store excluded package names in the existing `lintas_settings` SharedPreferences. Discover launcher apps through `PackageManager` with manifest `<queries>` visibility. Apply the list with `VpnService.Builder.addDisallowedApplication()` inside `establishTun()`. Add a full-screen Compose picker opened from Settings.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android `VpnService`, SharedPreferences, JUnit 4 for pure unit tests.

**Spec:** `docs/2026-11-23-lintas-split-tunneling-design.md`

## Global Constraints

- `minSdk 29`, `targetSdk 36`, `compileSdk 36`.
- Do **not** add `QUERY_ALL_PACKAGES`; use a `<queries>` element for launcher activities only.
- Never mix `addAllowedApplication` and `addDisallowedApplication` on the same `VpnService.Builder`.
- Changes apply only after a full disconnect/reconnect; do not reconnect automatically.
- Never display or add Lintas's own package to the exclusion list.
- Do not log WARP keys, licenses, or other secrets.
- Preserve the existing Settings and diagnostic logging style.
- Release target: `versionCode 16`, `versionName 1.0.6`.

## Review Focus

- A selected app is uninstalled before the next connect: the tunnel must still come up, and the missing package must be skipped and logged.
- A selected app is updated or reinstalled (its UID changes): the service must resolve the package by name on every `establishTun()`, never cache a UID.
- The exclusion set is empty: the app must behave exactly like a full tunnel, with no disallowed-app call.
- The stored set contains Lintas itself or a stale package: neither may be passed to the builder; stale entries are pruned from the picker.
- A system app is selected while "show system apps" is off: it must remain visible in the picker so the user can unselect it.
- Search input with different case or surrounding spaces must still match labels and package names.

---

### Task 1: Split Tunnel Selection Model and Preferences

**Files:**
- Create: `app/src/main/java/app/lintas/vpn/SplitTunnel.kt`
- Modify: `app/src/main/java/app/lintas/vpn/VpnProtocol.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/app/lintas/vpn/SplitTunnelTest.kt`

**Interfaces:**
- Produces:
  - `data class SplitTunnelApp(val packageName: String, val label: String, val isSystem: Boolean)`
  - `data class SplitTunnelAppCatalog(val apps: List<SplitTunnelApp>, val installedPackages: Set<String>)`
  - `internal fun visibleSplitTunnelApps(apps: List<SplitTunnelApp>, excluded: Set<String>, query: String, includeSystem: Boolean): List<SplitTunnelApp>`
  - `internal fun pruneExcludedPackages(excluded: Set<String>, installedPackages: Set<String>, ownPackage: String): Set<String>`
  - `internal fun applySplitTunnelPackages(packages: Set<String>, ownPackage: String, add: (String) -> Unit, onSkipped: (String, Throwable) -> Unit = { _, _ -> }): Int`
  - `VpnPreferences.loadExcludedApps(context: Context): Set<String>`
  - `VpnPreferences.saveExcludedApps(context: Context, packages: Set<String>)`

- [ ] **Step 1: Add the JUnit test dependency**

In `app/build.gradle.kts`, add to `dependencies`:

```kotlin
testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/app/lintas/vpn/SplitTunnelTest.kt`:

```kotlin
package app.lintas.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTunnelTest {
    private val apps = listOf(
        SplitTunnelApp("com.example.browser", "Browser", isSystem = false),
        SplitTunnelApp("com.example.chat", "Chat", isSystem = false),
        SplitTunnelApp("com.android.settings", "Settings", isSystem = true),
    )

    @Test
    fun `filters by label and package name ignoring case`() {
        assertEquals(
            listOf("com.example.browser"),
            visibleSplitTunnelApps(apps, emptySet(), "BROW", includeSystem = false)
                .map { it.packageName },
        )
        assertEquals(
            listOf("com.example.chat"),
            visibleSplitTunnelApps(apps, emptySet(), "example.chat", includeSystem = false)
                .map { it.packageName },
        )
    }

    @Test
    fun `system apps stay hidden unless included or already excluded`() {
        assertEquals(
            listOf("com.example.browser", "com.example.chat"),
            visibleSplitTunnelApps(apps, emptySet(), "", includeSystem = false)
                .map { it.packageName },
        )
        assertEquals(
            listOf("com.android.settings"),
            visibleSplitTunnelApps(apps, setOf("com.android.settings"), "", includeSystem = false)
                .map { it.packageName },
        )
        assertEquals(
            listOf("com.example.browser", "com.example.chat", "com.android.settings"),
            visibleSplitTunnelApps(apps, emptySet(), "", includeSystem = true)
                .map { it.packageName },
        )
    }

    @Test
    fun `prunes missing packages and the app itself`() {
        assertEquals(
            setOf("com.example.browser"),
            pruneExcludedPackages(
                excluded = setOf("com.example.browser", "com.example.gone", "app.lintas"),
                installedPackages = setOf("com.example.browser", "app.lintas"),
                ownPackage = "app.lintas",
            ),
        )
    }

    @Test
    fun `applies valid packages and skips missing ones and itself`() {
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val applied = applySplitTunnelPackages(
            packages = setOf("com.example.browser", "com.example.gone", "app.lintas"),
            ownPackage = "app.lintas",
            add = { pkg ->
                if (pkg == "com.example.gone") error("missing")
                added += pkg
            },
            onSkipped = { pkg, _ -> skipped += pkg },
        )

        assertEquals(1, applied)
        assertEquals(listOf("com.example.browser"), added)
        assertEquals(listOf("com.example.gone"), skipped)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
/tmp/kilo/gradle-8.11.1/bin/gradle :app:testDebugUnitTest --no-daemon
```

Expected: compilation fails because `SplitTunnelApp`, `visibleSplitTunnelApps`, `pruneExcludedPackages`, and `applySplitTunnelPackages` do not exist.

- [ ] **Step 4: Implement the pure model and helpers**

Create `app/src/main/java/app/lintas/vpn/SplitTunnel.kt` with the two data classes and three functions from the Interfaces block. Behavior:

- `visibleSplitTunnelApps` trims the query, matches label or package name case-insensitively, keeps list order, and hides a system app unless `includeSystem` is true or its package is already in `excluded`.
- `pruneExcludedPackages` returns the intersection of `excluded` and `installedPackages`, minus `ownPackage`.
- `applySplitTunnelPackages` iterates the set in order, skips `ownPackage`, calls `add`, counts successes, and passes failures to `onSkipped` without aborting.

- [ ] **Step 5: Add preference accessors**

In `VpnProtocol.kt`, add key `split_tunnel_excluded_apps`. Implement:

```kotlin
fun loadExcludedApps(context: Context): Set<String> =
    prefs(context).getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet().orEmpty()

fun saveExcludedApps(context: Context, packages: Set<String>) {
    prefs(context).edit().putStringSet(KEY_EXCLUDED_APPS, packages).apply()
}
```

Use a private `prefs(context)` helper so both protocol and split-tunnel keys share `lintas_settings`.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
/tmp/kilo/gradle-8.11.1/bin/gradle :app:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`, four tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/app/lintas/vpn/SplitTunnel.kt \
  app/src/main/java/app/lintas/vpn/VpnProtocol.kt \
  app/src/test/java/app/lintas/vpn/SplitTunnelTest.kt
git commit -m "feat: add split tunnel selection rules"
```

---

### Task 2: Discover Launcher Apps

**Files:**
- Create: `app/src/main/java/app/lintas/vpn/SplitTunnelApps.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `SplitTunnelApp`, `SplitTunnelAppCatalog` from Task 1.
- Produces: `object SplitTunnelApps { fun load(context: Context, excludedPackages: Set<String>): SplitTunnelAppCatalog }`

- [ ] **Step 1: Implement the loader**

Create `SplitTunnelApps.kt`:

- Query `Intent.ACTION_MAIN` with `CATEGORY_LAUNCHER`, plus `CATEGORY_LEANBACK_LAUNCHER`.
- Deduplicate package names with a `LinkedHashSet`.
- Skip `context.packageName`.
- Resolve `ApplicationInfo` for each package; skip failures.
- For each stored excluded package that is installed but not launchable, add it to the result as well so it can be removed from the picker.
- Collect every resolved package into `installedPackages`.
- Label from `PackageManager.getApplicationLabel`, falling back to the package name.
- `isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0`.
- Sort with `compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }`.
- Use API-level branches for `getApplicationInfo` and `queryIntentActivities` so API 33+ uses the flags overloads and older versions use the deprecated int overloads behind `@Suppress("DEPRECATION")`.

- [ ] **Step 2: Declare package visibility**

Add to `AndroidManifest.xml`, as a direct child of `<manifest>` before `<application>`:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
    </intent>
</queries>
```

Do not add `QUERY_ALL_PACKAGES`.

- [ ] **Step 3: Verify compilation**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
/tmp/kilo/gradle-8.11.1/bin/gradle :app:compileDebugKotlin --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/lintas/vpn/SplitTunnelApps.kt app/src/main/AndroidManifest.xml
git commit -m "feat: discover launcher apps for split tunneling"
```

---

### Task 3: Apply Exclusions When Establishing the TUN

**Files:**
- Modify: `app/src/main/java/app/lintas/vpn/LintasVpnService.kt`

**Interfaces:**
- Consumes: `VpnPreferences.loadExcludedApps`, `applySplitTunnelPackages` from Task 1.
- Produces: `private fun applySplitTunnel(builder: VpnService.Builder)`.

- [ ] **Step 1: Add the builder integration**

In `establishTun()`, call `applySplitTunnel(builder)` after all addresses, routes, and DNS servers are added and before `builder.establish()`.

Implement:

```kotlin
private fun applySplitTunnel(builder: Builder) {
    val excluded = VpnPreferences.loadExcludedApps(this)
    if (excluded.isEmpty()) return

    val applied = applySplitTunnelPackages(
        packages = excluded,
        ownPackage = packageName,
        add = { pkg -> builder.addDisallowedApplication(pkg) },
        onSkipped = { pkg, error ->
            DiagnosticLog.log(
                "SPLIT",
                "skip package=$pkg error=${error.javaClass.simpleName}",
            )
        },
    )
    DiagnosticLog.log("SPLIT", "excluded=$applied requested=${excluded.size}")
}
```

Import `app.lintas.warp.VpnPreferences` is already in the same package (`app.lintas.vpn`), so no import is needed.

- [ ] **Step 2: Verify compilation and existing tests**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
/tmp/kilo/gradle-8.11.1/bin/gradle :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/app/lintas/vpn/LintasVpnService.kt
git commit -m "feat: apply split tunnel exclusions to the VPN builder"
```

---

### Task 4: Split Tunnel Picker and Settings Entry

**Files:**
- Create: `app/src/main/java/app/lintas/ui/SplitTunnelScreen.kt`
- Modify: `app/src/main/java/app/lintas/ui/LintasApp.kt`

**Interfaces:**
- Consumes: `SplitTunnelApps`, `SplitTunnelApp`, `SplitTunnelAppCatalog`, `visibleSplitTunnelApps`, `pruneExcludedPackages`, `VpnPreferences.loadExcludedApps`, `VpnPreferences.saveExcludedApps`.
- Produces: `@Composable fun SplitTunnelScreen(excluded: Set<String>, tunnelActive: Boolean, onExcludedChange: (Set<String>) -> Unit, onBack: () -> Unit)`.

- [ ] **Step 1: Share the existing header and switch row**

In `LintasApp.kt`:

- Change `private fun ScreenHeader` to `internal fun ScreenHeader`.
- Add `onBack: (() -> Unit)? = null` before the existing `action` parameter.
- When `onBack != null`, render an `IconButton` with `Icons.AutoMirrored.Filled.ArrowBack`, content description `"Kembali"`, before the title column.
- Change `private fun SwitchSettingRow` to `internal fun SwitchSettingRow`.

Keep existing callers working by using named arguments.

- [ ] **Step 2: Add the state and full-screen branch in `LintasApp`**

After the `LaunchedEffect(Unit)` block and before `Scaffold`:

```kotlin
var showSplitTunnel by rememberSaveable { mutableStateOf(false) }
var excludedApps by remember {
    mutableStateOf(VpnPreferences.loadExcludedApps(context))
}

if (showSplitTunnel) {
    SplitTunnelScreen(
        excluded = excludedApps,
        tunnelActive = tunnel.status == TunnelStatus.CONNECTED ||
            tunnel.status == TunnelStatus.CONNECTING,
        onExcludedChange = { next ->
            excludedApps = next
            VpnPreferences.saveExcludedApps(context, next)
        },
        onBack = { showSplitTunnel = false },
    )
    return
}
```

Add the required `remember` import.

- [ ] **Step 3: Add the Settings row and pass callbacks**

Add parameters to `SettingsScreen`: `splitTunnelCount: Int`, `splitTunnelActive: Boolean`, `onOpenSplitTunnel: () -> Unit`.

Add a `SettingsGroup(title = "Lalu lintas")` after the Protocol group containing a clickable row:

- Title: `"Split tunnel"`.
- Subtitle:
  - `"Semua aplikasi melewati tunnel"` when count is 0.
  - `"$count aplikasi dikecualikan"` otherwise.
  - Append `" · sambung ulang untuk menerapkan"` when `splitTunnelActive` is true.
- Trailing text `"Kelola"` and `Icons.Outlined.ChevronRight`.
- Whole row calls `onOpenSplitTunnel`.

Pass `splitTunnelCount = excludedApps.size`, `splitTunnelActive = tunnel.status == TunnelStatus.CONNECTED || tunnel.status == TunnelStatus.CONNECTING`, and `onOpenSplitTunnel = { showSplitTunnel = true }` from the Settings branch.

- [ ] **Step 4: Create the picker screen**

Create `SplitTunnelScreen.kt` with the signature above. Structure:

- `BackHandler(onBack = onBack)`.
- Header via shared `ScreenHeader` with back, title `"Split tunnel"`, subtitle showing the excluded count, and a `Bersihkan` text action when the set is not empty.
- `OutlinedTextField` for search with placeholder `"Cari aplikasi"` and a search leading icon.
- Shared `SwitchSettingRow` for `"Tampilkan aplikasi sistem"`.
- Inline note when `tunnelActive`: `"Perubahan berlaku setelah putus dan sambung ulang."`
- Load `SplitTunnelApps.load(context, excluded)` in `LaunchedEffect` on `Dispatchers.IO`.
- On successful load, call `pruneExcludedPackages(excluded, catalog.installedPackages, context.packageName)` and pass any change to `onExcludedChange`.
- Loading state: centered `CircularProgressIndicator`.
- Load failure: message plus `Coba lagi` action that reruns the load.
- Empty filtered list: `"Tidak ada aplikasi yang cocok."`.
- `LazyColumn` with `key = { it.packageName }`, rows showing icon, label, package name, and a `Switch`; tapping the row toggles selection.
- Use `visibleSplitTunnelApps(catalog.apps, excluded, query, includeSystem)` for the displayed list.
- Icons load lazily off the main thread through a small `LruCache`; fall back to `Icons.Outlined.Apps` in a rounded placeholder.

- [ ] **Step 5: Verify build and lint**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
/tmp/kilo/gradle-8.11.1/bin/gradle :app:testDebugUnitTest :app:lintDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL` with no new lint errors.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/lintas/ui/SplitTunnelScreen.kt \
  app/src/main/java/app/lintas/ui/LintasApp.kt
git commit -m "feat: add split tunnel app picker"
```

---

### Task 5: Version 1.0.6, Documentation, and Release Verification

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `docs/2026-11-23-lintas-split-tunneling-design.md`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: signed `dist/lintas-1.0.6.apk` with a recorded SHA-256.

- [ ] **Step 1: Bump the version**

In `app/build.gradle.kts`:

```kotlin
versionCode = 16
versionName = "1.0.6"
```

- [ ] **Step 2: Update documentation**

- Add a `## 1.0.6 - 2026-11-23` section to `CHANGELOG.md` covering per-app split tunneling, next-connection behavior, and stale-selection pruning.
- Add a split tunneling feature bullet to `README.md`.
- Change the README status sentence so it no longer says split tunneling is missing; keep kill switch as the remaining limitation.
- Set the design doc status to `diimplementasikan`.

- [ ] **Step 3: Run the full verification**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
/tmp/kilo/gradle-8.11.1/bin/gradle \
  :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon
```

Expected: `BUILD SUCCESSFUL`; unsigned APK at `app/build/outputs/apk/release/app-release-unsigned.apk`.

- [ ] **Step 4: Sign and verify the APK**

Use the existing release keystore without printing passwords:

```bash
/opt/android-sdk/build-tools/36.0.0/apksigner sign \
  --ks "$HOME/.config/lintas/lintas-release.jks" \
  --ks-key-alias lintas \
  --ks-pass file:"$HOME/.config/lintas/keystore-password" \
  --key-pass file:"$HOME/.config/lintas/keystore-password" \
  --out dist/lintas-1.0.6.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

/opt/android-sdk/build-tools/36.0.0/apksigner verify --print-certs dist/lintas-1.0.6.apk
sha256sum dist/lintas-1.0.6.apk
```

Expected: verification succeeds, certificate matches the previous release key, and the hash is recorded in `dist/SHA256SUMS`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts CHANGELOG.md README.md \
  docs/2026-11-23-lintas-split-tunneling-design.md
git commit -m "Lintas v1.0.6: per-app split tunneling"
```

- [ ] **Step 6: Device verification (when the Poco is connected)**

Install the signed APK, connect, select one app, disconnect and reconnect, then compare `https://www.cloudflare.com/cdn-cgi/trace`:

- Excluded app: `warp=off`.
- Non-excluded app: `warp=on`.

Also uninstall a selected app, reconnect, and confirm the tunnel still establishes and `lintas-diagnostics.log` contains `[SPLIT] skip package=...`.

If the device is not connected, report this step as pending instead of claiming it passed.
