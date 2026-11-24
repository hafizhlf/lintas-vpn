package app.lintas.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lintas.ui.theme.PaperInk
import app.lintas.ui.theme.PaperLight
import app.lintas.update.UpdateState
import app.lintas.update.UpdateStatus

internal fun updateSubtitle(state: UpdateState): String {
    val updated = state.justUpdatedTo
    if (
        updated != null &&
        (state.status == UpdateStatus.IDLE || state.status == UpdateStatus.UP_TO_DATE)
    ) {
        return "Diperbarui ke $updated"
    }
    return when (state.status) {
        UpdateStatus.IDLE -> "Belum diperiksa"
        UpdateStatus.CHECKING -> "Memeriksa..."
        UpdateStatus.UP_TO_DATE -> "Versi ${state.currentVersion} adalah yang terbaru"
        UpdateStatus.AVAILABLE -> "Versi ${state.release?.versionName ?: "baru"} tersedia"
        UpdateStatus.ERROR -> "Gagal: ${state.error ?: "tidak diketahui"}"
    }
}

internal fun updateAction(state: UpdateState): String = when (state.status) {
    UpdateStatus.IDLE -> "Cek"
    UpdateStatus.CHECKING, UpdateStatus.UP_TO_DATE -> "Tutup"
    UpdateStatus.AVAILABLE -> "Buka halaman rilis"
    UpdateStatus.ERROR -> "Coba lagi"
}

@Composable
fun UpdateDialog(
    state: UpdateState,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, PaperInk, RoundedCornerShape(2.dp)),
        shape = RoundedCornerShape(2.dp),
        containerColor = PaperLight,
        titleContentColor = PaperInk,
        textContentColor = PaperInk,
        tonalElevation = 0.dp,
        title = {
            Text(
                "Pembaruan Lintas",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
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
                                "Versi ${release.versionName}",
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
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Unduh dan pasang manual dari halaman rilis.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("Versi baru tersedia.")
                        }
                    }

                    UpdateStatus.ERROR -> {
                        Text(
                            state.error ?: "Pembaruan gagal.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state.status) {
                UpdateStatus.IDLE -> {
                    TextButton(onClick = onCheck) { Text("Cek") }
                }

                UpdateStatus.CHECKING, UpdateStatus.UP_TO_DATE -> {
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }

                UpdateStatus.AVAILABLE -> {
                    val url = state.release?.pageUrl
                    if (!url.isNullOrBlank()) {
                        TextButton(onClick = { onOpenRelease(url) }) {
                            Text("Buka halaman rilis")
                        }
                    } else {
                        TextButton(onClick = onCheck) { Text("Cek ulang") }
                    }
                }

                UpdateStatus.ERROR -> {
                    TextButton(onClick = onCheck) { Text("Coba lagi") }
                }
            }
        },
        dismissButton = {
            when (state.status) {
                UpdateStatus.IDLE, UpdateStatus.ERROR -> {
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }

                UpdateStatus.AVAILABLE -> {
                    TextButton(onClick = onDismiss) { Text("Nanti") }
                }

                UpdateStatus.CHECKING, UpdateStatus.UP_TO_DATE -> Unit
            }
        },
    )
}
