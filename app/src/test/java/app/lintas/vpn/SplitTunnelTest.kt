package app.lintas.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTunnelTest {
    private val apps = listOf(
        SplitTunnelApp("com.example.browser", "Browser", isSystem = false),
        SplitTunnelApp("com.example.chat", "Chat", isSystem = false),
        SplitTunnelApp("com.android.settings", "Settings", isSystem = true),
        SplitTunnelApp("com.android.camera", "Camera", isSystem = true),
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
            listOf("com.example.browser", "com.example.chat", "com.android.settings"),
            visibleSplitTunnelApps(apps, setOf("com.android.settings"), "", includeSystem = false)
                .map { it.packageName },
        )
        assertEquals(
            listOf(
                "com.example.browser",
                "com.example.chat",
                "com.android.settings",
                "com.android.camera",
            ),
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
