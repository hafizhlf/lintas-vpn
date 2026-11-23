package app.lintas.vpn

data class SplitTunnelApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

data class SplitTunnelAppCatalog(
    val apps: List<SplitTunnelApp>,
    val installedPackages: Set<String>,
)

internal fun visibleSplitTunnelApps(
    apps: List<SplitTunnelApp>,
    excluded: Set<String>,
    query: String,
    includeSystem: Boolean,
): List<SplitTunnelApp> {
    val term = query.trim()
    return apps.filter { app ->
        val visible = includeSystem || !app.isSystem || app.packageName in excluded
        val matches = term.isEmpty() ||
            app.label.contains(term, ignoreCase = true) ||
            app.packageName.contains(term, ignoreCase = true)
        visible && matches
    }
}

internal fun pruneExcludedPackages(
    excluded: Set<String>,
    installedPackages: Set<String>,
    ownPackage: String,
): Set<String> {
    return excluded.intersect(installedPackages) - ownPackage
}

internal fun applySplitTunnelPackages(
    packages: Set<String>,
    ownPackage: String,
    add: (String) -> Unit,
    onSkipped: (String, Throwable) -> Unit = { _, _ -> },
): Int {
    var applied = 0
    for (pkg in packages) {
        if (pkg == ownPackage) continue
        runCatching { add(pkg) }
            .onSuccess { applied += 1 }
            .onFailure { error -> onSkipped(pkg, error) }
    }
    return applied
}
