package app.lintas.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

object SplitTunnelApps {
    fun load(context: Context, excludedPackages: Set<String>): SplitTunnelAppCatalog {
        val packageManager = context.packageManager
        val launchable = LinkedHashSet<String>()
        for (intent in launcherIntents()) {
            val resolved = runCatching {
                queryIntentActivities(packageManager, intent)
            }.getOrDefault(emptyList())
            for (info in resolved) {
                info.activityInfo?.packageName?.let { launchable.add(it) }
            }
        }

        val candidates = LinkedHashSet(launchable)
        candidates.addAll(excludedPackages)

        val apps = LinkedHashMap<String, SplitTunnelApp>()
        val installed = LinkedHashSet<String>()
        for (packageName in candidates) {
            if (packageName == context.packageName) continue
            val info = runCatching {
                applicationInfo(packageManager, packageName)
            }.getOrNull() ?: continue
            installed.add(packageName)
            val label = runCatching {
                packageManager.getApplicationLabel(info).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
            apps[packageName] = SplitTunnelApp(
                packageName = packageName,
                label = label,
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }

        return SplitTunnelAppCatalog(
            apps = apps.values.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            ),
            installedPackages = installed,
        )
    }

    private fun launcherIntents(): List<Intent> = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
    )

    private fun queryIntentActivities(
        packageManager: PackageManager,
        intent: Intent,
    ): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun applicationInfo(
        packageManager: PackageManager,
        packageName: String,
    ): ApplicationInfo {
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
    }
}
