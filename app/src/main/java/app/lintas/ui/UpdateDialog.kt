package app.lintas.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lintas.update.UpdateState
import app.lintas.update.UpdateStatus
import app.lintas.update.formatUpdateSize

internal fun updateSubtitle(state: UpdateState): String {
    if (state.justUpdatedTo != null) {
        return "Diperbarui ke ${state.justUpdatedTo}"
    }
    return when (state.status) {
        UpdateStatus.IDLE -> "Belum diperiksa"
        UpdateStatus.CHECKING -> "Memeriksa..."
        UpdateStatus.UP_TO_DATE -> "Versi ${state.currentVersion} adalah yang terbaru"
        UpdateStatus.AVAILABLE -> "Versi ${state.release?.versionName ?: "baru"} tersedia"
        UpdateStatus.DOWNLOADING -> "Mengunduh ${state.progress}%"
        UpdateStatus.READY -> "Siap dipasang · ${state.release?.versionName.orEmpty()}".trimEnd(' ', '·')
        UpdateStatus.ERROR -> "Gagal: ${state.error ?: "tidak diketahui"}"
    }
}

internal fun updateAction(state: UpdateState): String = when (state.status) {
    UpdateStatus.IDLE, UpdateStatus.UP_TO_DATE, UpdateStatus.ERROR -> "Cek"
    UpdateStatus.CHECKING, UpdateStatus.DOWNLOADING -> "Lihat"
    UpdateStatus.AVAILABLE -> "Unduh"
    UpdateStatus.READY -> "Pasang"
}

@Composable
fun UpdateDialog(
    state: UpdateState,
    tunnelActive: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onDiscard: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showPermissionExplanation by remember { mutableStateOf(false) }
    var showInstallWarning by remember { mutableStateOf(false) }
    var intentError by remember { mutableStateOf<String?>(null) }

    val continueInstall: () -> Unit = {
        showPermissionExplanation = false
        if (tunnelActive) {
            showInstallWarning = true
        } else {
            onInstall()
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            continueInstall()
        }
    }

    val openSettings: () -> Unit = {
        intentError = null
        val specific = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        )
        try {
            settingsLauncher.launch(specific)
        } catch (error: Exception) {
            try {
                settingsLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (fallback: Exception) {
                intentError = "Tidak bisa membuka pengaturan izin. Buka Setelan Android secara manual."
            }
        }
    }

    val startInstall: () -> Unit = {
        intentError = null
        if (!context.packageManager.canRequestPackageInstalls()) {
            showPermissionExplanation = true
        } else if (tunnelActive) {
            showInstallWarning = true
        } else {
            onInstall()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pembaruan Lintas") },
        text = {
            Column {
                when (state.status) {
                    UpdateStatus.CHECKING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Memeriksa pembaruan...")
                        }
                    }

                    UpdateStatus.IDLE -> {
                        Text("Belum ada pemeriksaan.")
                    }

                    UpdateStatus.UP_TO_DATE -> {
                        Text("Lintas sudah versi terbaru (${state.currentVersion}).")
                    }

                    UpdateStatus.AVAILABLE -> {
                        val release = state.release
                        if (release != null) {
                            Text(
                                "Versi ${release.versionName} · ${formatUpdateSize(release.apkSize)}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                            if (release.notes.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    release.notes.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (release.pageUrl.isNotBlank()) {
                                TextButton(onClick = { onOpenRelease(release.pageUrl) }) {
                                    Text("Lihat di GitHub")
                                }
                            }
                        }
                    }

                    UpdateStatus.DOWNLOADING -> {
                        val release = state.release
                        Text(
                            if (release != null) {
                                "Mengunduh ${state.progress}% dari ${formatUpdateSize(release.apkSize)}"
                            } else {
                                "Mengunduh ${state.progress}%"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tetap buka Lintas selama mengunduh.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    UpdateStatus.READY -> {
                        Text("Unduhan selesai dan sudah diverifikasi.")
                        val release = state.release
                        if (release != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Versi ${release.versionName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onDiscard) {
                            Text("Hapus unduhan")
                        }
                    }

                    UpdateStatus.ERROR -> {
                        Text(
                            state.error ?: "Pembaruan gagal.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (intentError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        intentError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (state.status) {
                UpdateStatus.CHECKING, UpdateStatus.UP_TO_DATE -> {
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }

                UpdateStatus.IDLE -> {
                    TextButton(onClick = onCheck) { Text("Cek") }
                }

                UpdateStatus.AVAILABLE -> {
                    TextButton(onClick = onDownload) { Text("Unduh") }
                }

                UpdateStatus.DOWNLOADING -> {
                    TextButton(onClick = onCancelDownload) { Text("Batal") }
                }

                UpdateStatus.READY -> {
                    TextButton(onClick = startInstall) { Text("Pasang") }
                }

                UpdateStatus.ERROR -> {
                    TextButton(onClick = onCheck) { Text("Coba lagi") }
                }
            }
        },
        dismissButton = {
            when (state.status) {
                UpdateStatus.CHECKING, UpdateStatus.UP_TO_DATE -> Unit
                UpdateStatus.IDLE, UpdateStatus.ERROR -> {
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }

                UpdateStatus.AVAILABLE -> {
                    TextButton(onClick = onDismiss) { Text("Nanti") }
                }

                UpdateStatus.DOWNLOADING -> {
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }

                UpdateStatus.READY -> {
                    TextButton(onClick = onDismiss) { Text("Nanti") }
                }
            }
        },
    )

    if (showPermissionExplanation) {
        AlertDialog(
            onDismissRequest = { showPermissionExplanation = false },
            title = { Text("Izin pemasangan") },
            text = { Text("Izinkan Lintas memasang aplikasi di pengaturan, lalu kembali.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionExplanation = false
                        openSettings()
                    },
                ) {
                    Text("Buka pengaturan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionExplanation = false }) {
                    Text("Nanti")
                }
            },
        )
    }

    if (showInstallWarning) {
        val version = state.release?.versionName
        AlertDialog(
            onDismissRequest = { showInstallWarning = false },
            title = {
                Text(if (version != null) "Pasang pembaruan $version?" else "Pasang pembaruan?")
            },
            text = {
                Text(
                    "Koneksi VPN akan terputus saat aplikasi diperbarui. " +
                        "Sambungkan ulang setelah selesai.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInstallWarning = false
                        onInstall()
                    },
                ) {
                    Text("Pasang")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallWarning = false }) {
                    Text("Batal")
                }
            },
        )
    }
}
