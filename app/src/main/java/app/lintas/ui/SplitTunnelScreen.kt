package app.lintas.ui

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lintas.vpn.SplitTunnelApp
import app.lintas.vpn.SplitTunnelAppCatalog
import app.lintas.vpn.SplitTunnelApps
import app.lintas.vpn.pruneExcludedPackages
import app.lintas.vpn.visibleSplitTunnelApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SplitTunnelScreen(
    excluded: Set<String>,
    tunnelActive: Boolean,
    onExcludedChange: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val icons = remember { AppIconCache() }
    var query by rememberSaveable { mutableStateOf("") }
    var includeSystem by rememberSaveable { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<SplitTunnelAppCatalog?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        catalog = null
        loadFailed = false
        val loaded = withContext(Dispatchers.IO) {
            runCatching { SplitTunnelApps.load(context, excluded) }.getOrNull()
        }
        if (loaded == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        catalog = loaded
        val pruned = pruneExcludedPackages(
            excluded = excluded,
            installedPackages = loaded.installedPackages,
            ownPackage = context.packageName,
        )
        if (pruned != excluded) onExcludedChange(pruned)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(
            title = "Split tunnel",
            subtitle = if (excluded.isEmpty()) {
                "Semua aplikasi melewati tunnel"
            } else {
                "${excluded.size} aplikasi tidak melewati tunnel"
            },
            onBack = onBack,
            action = {
                if (excluded.isNotEmpty()) {
                    TextButton(onClick = { onExcludedChange(emptySet()) }) {
                        Text("Bersihkan")
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("Cari aplikasi") },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null)
            },
        )
        SwitchSettingRow(
            title = "Tampilkan aplikasi sistem",
            subtitle = if (includeSystem) {
                "Aplikasi sistem ikut ditampilkan."
            } else {
                "Aplikasi yang sudah dikecualikan tetap terlihat."
            },
            checked = includeSystem,
            onCheckedChange = { includeSystem = it },
        )
        if (tunnelActive) {
            Text(
                "Perubahan berlaku setelah putus dan sambung ulang.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))

        val currentCatalog = catalog
        when {
            loadFailed -> {
                CenteredMessage(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    message = "Daftar aplikasi tidak bisa dimuat.",
                    actionLabel = "Coba lagi",
                    onAction = { reload += 1 },
                )
            }
            currentCatalog == null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                val visible = visibleSplitTunnelApps(
                    apps = currentCatalog.apps,
                    excluded = excluded,
                    query = query,
                    includeSystem = includeSystem,
                )
                if (visible.isEmpty()) {
                    CenteredMessage(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        message = if (query.isBlank()) {
                            "Tidak ada aplikasi."
                        } else {
                            "Tidak ada aplikasi yang cocok."
                        },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(visible, key = { it.packageName }) { app ->
                            SplitTunnelAppRow(
                                app = app,
                                selected = app.packageName in excluded,
                                icons = icons,
                                onToggle = {
                                    onExcludedChange(
                                        if (app.packageName in excluded) {
                                            excluded - app.packageName
                                        } else {
                                            excluded + app.packageName
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitTunnelAppRow(
    app: SplitTunnelApp,
    selected: Boolean,
    icons: AppIconCache,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, icons)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                app.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = selected, onCheckedChange = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}

@Composable
private fun AppIcon(packageName: String, icons: AppIconCache) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(
        initialValue = icons.peek(packageName),
        key1 = packageName,
    ) {
        if (value == null) {
            value = icons.load(context.packageManager, packageName)
        }
    }
    val bitmap = image

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Outlined.Apps,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CenteredMessage(
    modifier: Modifier,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

private class AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(ICON_CACHE_ENTRIES)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(packageManager: PackageManager, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(packageName) ?: runCatching {
                packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX)
                    .asImageBitmap()
                    .also { cache.put(packageName, it) }
            }.getOrNull()
        }
    }

    private companion object {
        const val ICON_CACHE_ENTRIES = 64
        const val ICON_SIZE_PX = 96
    }
}
