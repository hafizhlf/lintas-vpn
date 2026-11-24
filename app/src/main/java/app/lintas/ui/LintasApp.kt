package app.lintas.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Canvas as AndroidCanvas
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lintas.BuildConfig
import app.lintas.core.NativeCore
import app.lintas.ui.theme.Paper
import app.lintas.ui.theme.PaperFaint
import app.lintas.ui.theme.PaperInk
import app.lintas.ui.theme.PaperInkSoft
import app.lintas.ui.theme.PaperLight
import app.lintas.ui.theme.PaperLine
import app.lintas.ui.theme.PaperLineStrong
import app.lintas.ui.theme.PaperMuted
import app.lintas.ui.theme.StampBlue
import app.lintas.ui.theme.StampGreen
import app.lintas.ui.theme.StampOrange
import app.lintas.update.UpdateController
import app.lintas.update.UpdateState
import app.lintas.update.UpdateStatus
import app.lintas.vpn.DiagnosticLog
import app.lintas.vpn.ProbeState
import app.lintas.vpn.ProbeStatus
import app.lintas.vpn.TunnelState
import app.lintas.vpn.TunnelStatus
import app.lintas.vpn.TunnelTransport
import app.lintas.vpn.VpnController
import app.lintas.vpn.VpnPreferences
import app.lintas.vpn.VpnProtocol
import app.lintas.warp.WarpCheckState
import app.lintas.warp.WarpController
import app.lintas.warp.WarpIdentity
import app.lintas.warp.WarpPlusState
import app.lintas.warp.WarpState
import app.lintas.warp.WarpStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val MONTH_LABELS = listOf(
    "JAN", "FEB", "MAR", "APR", "MEI", "JUN",
    "JUL", "AGU", "SEP", "OKT", "NOV", "DES",
)

@Composable
fun LintasApp(
    onConnect: (VpnProtocol) -> Unit,
    onDisconnect: () -> Unit,
    onProbe: () -> Unit,
) {
    val context = LocalContext.current
    val tunnel by VpnController.state.collectAsStateWithLifecycle()
    val probe by VpnController.probeState.collectAsStateWithLifecycle()
    val warp by WarpController.state.collectAsStateWithLifecycle()
    val warpPlusState by WarpController.warpPlus.collectAsStateWithLifecycle()
    val warpCheck by WarpController.check.collectAsStateWithLifecycle()
    val update by UpdateController.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var protocolName by rememberSaveable {
        mutableStateOf(VpnPreferences.loadProtocol(context).name)
    }
    var diagnosticsEnabled by rememberSaveable {
        mutableStateOf(DiagnosticLog.isEnabled(context))
    }
    val protocol = runCatching { VpnProtocol.valueOf(protocolName) }
        .getOrDefault(VpnProtocol.WIREGUARD)

    var showSplitTunnel by rememberSaveable { mutableStateOf(false) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showDisconnect by rememberSaveable { mutableStateOf(false) }
    var showWarpPlus by rememberSaveable { mutableStateOf(false) }
    var showResetWarp by rememberSaveable { mutableStateOf(false) }
    var licenseInput by remember { mutableStateOf("") }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    var excludedApps by remember {
        mutableStateOf(VpnPreferences.loadExcludedApps(context))
    }
    val connected = tunnel.status == TunnelStatus.CONNECTED
    val nowMillis = rememberNow(connected)
    val downloadSpeed = rememberDownloadSpeed(tunnel)
    val updateAvailable = update.status == UpdateStatus.AVAILABLE ||
        update.status == UpdateStatus.DOWNLOADING ||
        update.status == UpdateStatus.READY

    LaunchedEffect(Unit) {
        DiagnosticLog.initialize(context)
        WarpController.initialize(context)
        UpdateController.initialize(context)
    }

    LaunchedEffect(tunnel.status) {
        if (tunnel.status != TunnelStatus.CONNECTED) showDisconnect = false
    }

    LaunchedEffect(warpPlusState.success) {
        if (warpPlusState.success) {
            showWarpPlus = false
            licenseInput = ""
            WarpController.clearWarpPlusFeedback()
            toastMessage = "VISA WARP+ AKTIF · ARGO SMART ROUTING"
        }
    }

    LaunchedEffect(toastMessage) {
        val shown = toastMessage ?: return@LaunchedEffect
        delay(2600L)
        if (toastMessage == shown) toastMessage = null
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

    Box(modifier = Modifier.fillMaxSize()) {
        PaperBackdrop(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            BookHeader(page = tab + 1, dateLabel = todayLabel())
            DoubleRule()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> HomeScreen(
                        tunnel = tunnel,
                        protocol = protocol,
                        warpPlus = warp.identity?.warpPlus == true,
                        speed = downloadSpeed,
                        nowMillis = nowMillis,
                        onConnect = { onConnect(protocol) },
                        onAskDisconnect = { showDisconnect = true },
                    )
                    1 -> RouteScreen(
                        tunnel = tunnel,
                        protocol = protocol,
                        warp = warp,
                        probe = probe,
                        onProbe = onProbe,
                    )
                    2 -> SessionScreen(
                        tunnel = tunnel,
                        protocol = protocol,
                        speed = downloadSpeed,
                        nowMillis = nowMillis,
                        warpAddress = warp.identity?.ipv4?.substringBefore('/'),
                    )
                    else -> DocumentsScreen(
                        warp = warp,
                        tunnel = tunnel,
                        protocol = protocol,
                        onProtocol = { next ->
                            protocolName = next.name
                            VpnPreferences.saveProtocol(context, next)
                            val sessionProtocol = sessionProtocolOf(tunnel)
                            toastMessage = if (
                                connected &&
                                sessionProtocol != null &&
                                sessionProtocol != next
                            ) {
                                "PROTOKOL BARU BERLAKU UNTUK KONEKSI BERIKUTNYA"
                            } else {
                                "${next.label.uppercase(Locale.US)} DIPILIH"
                            }
                        },
                        onWarpRegister = { WarpController.register(context) },
                        onWarpEnroll = { WarpController.enroll(context) },
                        onOpenWarpPlus = {
                            WarpController.clearWarpPlusFeedback()
                            licenseInput = ""
                            showWarpPlus = true
                        },
                        onShowResetWarp = { showResetWarp = true },
                        update = update,
                        onOpenUpdate = {
                            showUpdateDialog = true
                            UpdateController.clearJustUpdated()
                            if (
                                update.status == UpdateStatus.IDLE ||
                                update.status == UpdateStatus.UP_TO_DATE ||
                                update.status == UpdateStatus.ERROR
                            ) {
                                UpdateController.check(context, manual = true)
                            }
                        },
                        splitTunnelCount = excludedApps.size,
                        splitTunnelActive = tunnel.status == TunnelStatus.CONNECTED ||
                            tunnel.status == TunnelStatus.CONNECTING,
                        onOpenSplitTunnel = { showSplitTunnel = true },
                        diagnosticsEnabled = diagnosticsEnabled,
                        onDiagnosticsToggle = { enabled ->
                            DiagnosticLog.setEnabled(context, enabled)
                            diagnosticsEnabled = enabled
                        },
                    )
                }
            }
            BookNav(
                selected = tab,
                onTab = { tab = it },
                updateAvailable = updateAvailable,
            )
        }

        toastMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 100.dp),
            ) {
                PaperToast(message)
            }
        }
    }

    if (showDisconnect) {
        PaperSheet(onDismiss = { showDisconnect = false }) {
            DisconnectSheet(
                tunnel = tunnel,
                nowMillis = nowMillis,
                protocol = protocol,
                onDismiss = { showDisconnect = false },
                onConfirm = {
                    showDisconnect = false
                    onDisconnect()
                },
            )
        }
    }

    if (showWarpPlus) {
        PaperSheet(
            onDismiss = {
                if (!warpPlusState.busy) {
                    showWarpPlus = false
                    WarpController.clearWarpPlusFeedback()
                }
            },
        ) {
            WarpPlusSheet(
                identity = warp.identity,
                state = warpPlusState,
                check = warpCheck,
                license = licenseInput,
                onLicenseChange = { licenseInput = it },
                onCheck = { WarpController.checkStatus(context) },
                onActivate = {
                    if (licenseInput.isNotBlank()) {
                        WarpController.activateWarpPlus(context, licenseInput)
                    }
                },
                onDismiss = {
                    if (!warpPlusState.busy) {
                        showWarpPlus = false
                        licenseInput = ""
                        WarpController.clearWarpPlusFeedback()
                    }
                },
            )
        }
    }

    if (showUpdateDialog) {
        UpdateDialog(
            state = update,
            tunnelActive = tunnel.status == TunnelStatus.CONNECTED ||
                tunnel.status == TunnelStatus.CONNECTING,
            onCheck = { UpdateController.check(context, manual = true) },
            onDownload = { UpdateController.download(context) },
            onCancelDownload = { UpdateController.cancelDownload(context) },
            onInstall = { UpdateController.launchInstaller(context) },
            onDiscard = { UpdateController.discardReady(context) },
            onOpenRelease = { url ->
                if (url.startsWith("https://github.com/hafizhlf/lintas-vpn/releases/")) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, url.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { error ->
                        DiagnosticLog.log("UPDATE", "release link failed: ${error.message}")
                        Toast.makeText(
                            context,
                            "Tidak bisa membuka tautan rilis.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { showUpdateDialog = false },
        )
    }

    if (showResetWarp) {
        AlertDialog(
            onDismissRequest = { showResetWarp = false },
            title = { Text("Reset identitas WARP?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Identitas dan kunci WARP di perangkat ini akan dihapus. Daftarkan ulang untuk membuat identitas baru.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetWarp = false
                        WarpController.reset(context)
                        toastMessage = "IDENTITAS WARP DIHAPUS"
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetWarp = false }) {
                    Text("Batal")
                }
            },
        )
    }
}

@Composable
private fun rememberNow(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (!active) {
            now = System.currentTimeMillis()
            return@LaunchedEffect
        }
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    return now
}

@Composable
private fun rememberDownloadSpeed(tunnel: TunnelState): Long {
    var speed by remember { mutableLongStateOf(0L) }
    var lastBytes by remember { mutableLongStateOf(tunnel.rxBytes) }
    var lastAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tunnel.status, tunnel.rxBytes) {
        if (tunnel.status != TunnelStatus.CONNECTED) {
            speed = 0L
            lastBytes = tunnel.rxBytes
            lastAt = System.currentTimeMillis()
            return@LaunchedEffect
        }
        val timestamp = System.currentTimeMillis()
        val elapsed = timestamp - lastAt
        val delta = tunnel.rxBytes - lastBytes
        if (elapsed in 250L..5_000L && delta >= 0L) {
            speed = (delta * 1_000L) / elapsed
        }
        lastBytes = tunnel.rxBytes
        lastAt = timestamp
    }
    return speed
}

@Composable
internal fun PaperBackdrop(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val dotBrush = remember(density) {
        val tile = (6f * density.density).toInt().coerceAtLeast(3)
        val bitmap = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(17, 30, 43, 36)
        }
        canvas.drawCircle(
            tile * 0.22f,
            tile * 0.22f,
            density.density * 0.55f,
            paint,
        )
        ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    Box(modifier = modifier.background(Paper).background(dotBrush)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawLine(
                color = PaperLine.copy(alpha = 0.9f),
                start = Offset(12.dp.toPx(), 0f),
                end = Offset(12.dp.toPx(), size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

@Composable
private fun BookHeader(page: Int, dateLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 18.dp, top = 8.dp, bottom = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text(
                "Lintas",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 24.sp,
                    lineHeight = 26.sp,
                    letterSpacing = (-0.6).sp,
                ),
                color = PaperInk,
            )
            Text(
                "PASPOR KONEKSI",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 7.5.sp,
                    letterSpacing = 1.6.sp,
                ),
                color = PaperMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "HAL. 0$page / 04",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                color = PaperInk,
            )
            Text(
                dateLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = PaperMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun DoubleRule() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        HorizontalDivider(color = PaperInk, thickness = 1.dp)
        Spacer(Modifier.height(3.dp))
        HorizontalDivider(color = PaperInk, thickness = 1.dp)
    }
}

@Composable
private fun BookNav(
    selected: Int,
    onTab: (Int) -> Unit,
    updateAvailable: Boolean,
) {
    val pages = listOf("Beranda", "Rute", "Sesi", "Dokumen")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = PaperInk, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .selectableGroup(),
        ) {
            pages.forEachIndexed { index, label ->
                val active = selected == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = active,
                            onClick = { onTab(index) },
                            role = Role.Tab,
                        )
                        .background(if (active) PaperLight.copy(alpha = 0.35f) else Color.Transparent)
                        .drawBehind {
                            if (active) {
                                drawLine(
                                    color = StampOrange,
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 2.dp.toPx(),
                                )
                            }
                        }
                        .padding(start = 13.dp, top = 7.dp, bottom = 5.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "0${index + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                letterSpacing = 0.5.sp,
                            ),
                            color = if (active) StampOrange else PaperFaint,
                        )
                        if (index == 3 && updateAvailable) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(StampOrange, CircleShape),
                            )
                        }
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.4.sp,
                        ),
                        color = if (active) PaperInk else PaperMuted,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    tunnel: TunnelState,
    protocol: VpnProtocol,
    warpPlus: Boolean,
    speed: Long,
    nowMillis: Long,
    onConnect: () -> Unit,
    onAskDisconnect: () -> Unit,
) {
    val connected = tunnel.status == TunnelStatus.CONNECTED
    val connecting = tunnel.status == TunnelStatus.CONNECTING
    val error = tunnel.status == TunnelStatus.ERROR
    val testMode = connected && tunnel.transport == TunnelTransport.SPIKE
    val protected = connected && !testMode
    val activeProtocol = sessionProtocolOf(tunnel) ?: protocol
    val totalBytes = tunnel.bytes + tunnel.rxBytes
    val duration = if (connected) formatDuration(tunnel.startedAtMillis, nowMillis) else "—"
    val data = if (connected) formatBytes(totalBytes) else "—"
    val speedLabel = if (connected) formatSpeed(speed) else "—"
    val date = todayLabel()

    val kicker = when {
        connecting -> "MEMVALIDASI IZIN"
        protected -> "SESI WARP AKTIF"
        testMode -> "MODE UJI TUN"
        error -> "PERLU PERHATIAN"
        else -> "BELUM ADA SESI"
    }
    val kickerColor = when {
        protected -> StampGreen
        connecting || error -> StampOrange
        else -> PaperFaint
    }
    val title = when {
        connecting -> "Menyiapkan izin"
        protected -> "Terlindungi"
        testMode -> "TUN aktif"
        error -> "Rute terhenti"
        else -> "Siap berangkat"
    }
    val lead = when {
        connecting -> "Menyiapkan tunnel dan memvalidasi tiket perjalanan."
        protected -> "Trafik Anda menyeberang lewat Cloudflare."
        testMode -> "Mode uji: TUN dan packet loop aktif; trafik internet belum dialihkan."
        error -> tunnel.error ?: "Terjadi kesalahan. Coba berangkat lagi."
        else -> "Tiket belum divalidasi. Berangkat untuk memulai perjalanan."
    }
    val actionLabel = when {
        connecting -> "Menyiapkan izin…"
        connected -> "Tutup sesi"
        error -> "Coba lagi"
        else -> "Berangkat"
    }
    val actionNote = when {
        connecting -> "MOHON TUNGGU, CAP SEDANG DIPROSES"
        connected -> "TEKAN UNTUK MENGAKHIRI PERJALANAN"
        error -> "TEKAN UNTUK MENCOBA LAGI"
        else -> "TEKAN UNTUK MEMULAI PERJALANAN"
    }

    val journalText = when {
        connecting -> "Sedang memvalidasi izin.\nMohon tunggu sebentar."
        protected -> "Berangkat ${formatClock(tunnel.startedAtMillis)} dari perangkat ini.\nMelintas Cloudflare ${activeProtocol.label.uppercase(Locale.US)}."
        testMode -> "Mode uji TUN aktif.\nTrafik internet belum dialihkan."
        error -> "Perjalanan terhenti.\nCoba berangkat lagi."
        else -> "Tidak ada perjalanan aktif.\nTiket menunggu di meja."
    }
    val journalMeta = when {
        connecting -> "MEMPROSES IZIN"
        connected -> "$duration · $data · $date"
        else -> "TIDAK ADA SESI"
    }
    val sealColor = when {
        protected -> StampGreen
        connecting || testMode -> StampBlue
        error -> StampOrange
        else -> StampGreen
    }
    val sealTop = when {
        connecting -> "MEMPROSES"
        protected -> "TERLINDUNGI"
        testMode -> "MODE UJI"
        error -> "PERHATIAN"
        else -> "SIAP"
    }
    val sealMiddle = when {
        connecting -> "IZIN"
        protected -> "CLOUDFLARE"
        testMode -> "TUN"
        error -> "RUTE"
        else -> "BERANGKAT"
    }
    val configuration = LocalConfiguration.current
    val scrollable = configuration.screenHeightDp < 700 || configuration.fontScale > 1.15f
    val pageModifier = if (scrollable) {
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxSize()
    }

    Column(
        modifier = pageModifier.padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .border(1.dp, kickerColor.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(5.dp).background(kickerColor, CircleShape))
            }
            Spacer(Modifier.width(7.dp))
            Text(
                kicker,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = PaperMuted,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = if (connecting || !connected) 35.sp else 40.sp,
                lineHeight = if (connecting || !connected) 38.sp else 42.sp,
            ),
            color = PaperInk,
        )
        Text(
            lead,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            ),
            color = if (error) StampOrange else PaperInkSoft,
            modifier = Modifier
                .padding(top = 6.dp)
                .widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(12.dp))
        VisaCard(
            connected = connected,
            connecting = connecting,
            error = error,
            testMode = testMode,
            protocol = activeProtocol,
            warpPlus = warpPlus,
            date = date,
        )
        Spacer(Modifier.height(10.dp))
        StatsStrip(
            duration = duration,
            speed = speedLabel,
            data = data,
        )
        Spacer(Modifier.height(10.dp))
        LintasActionButton(
            label = actionLabel,
            solid = !connected && !connecting,
            enabled = !connecting,
            onClick = { if (connected) onAskDisconnect() else onConnect() },
            icon = {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = StampOrange.copy(alpha = 0.42f),
                    )
                } else {
                    Icon(
                        Icons.Outlined.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
            },
        )
        Text(
            actionNote,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 7.5.sp,
                letterSpacing = 0.8.sp,
            ),
            color = PaperFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        )

        if (scrollable) {
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Watermark()
        Journal(
            text = journalText,
            meta = journalMeta,
            sealTop = sealTop,
            sealMiddle = sealMiddle,
            sealBottom = date,
            sealColor = sealColor,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VisaCard(
    connected: Boolean,
    connecting: Boolean,
    error: Boolean,
    testMode: Boolean,
    protocol: VpnProtocol,
    warpPlus: Boolean,
    date: String,
) {
    val muted = !connected
    val stampColor = if (muted) PaperFaint else StampOrange
    val label = when {
        connecting -> "IZIN DIPROSES"
        connected -> "IZIN MELINTAS"
        error -> "IZIN TERHENTI"
        else -> "IZIN BELUM DITERBITKAN"
    }
    val sub = when {
        connecting -> "MOHON TUNGGU"
        connected -> "BERLAKU · $date"
        error -> "PERIKSA KONEKSI"
        else -> "BELUM ADA CAP"
    }
    val stampTop = when {
        connecting -> "VALIDASI"
        testMode -> "UJI"
        connected -> "WARP"
        else -> "BELUM"
    }
    val stampMiddle = when {
        connecting -> "IZIN"
        testMode -> "TUN"
        connected -> protocol.label.uppercase(Locale.US)
        else -> "MELINTAS"
    }
    val stampBottom = when {
        connecting -> "—"
        testMode -> "TUN"
        connected -> if (protocol == VpnProtocol.MASQUE) "443" else "2408"
        else -> "—"
    }
    val protocolLabel = if (connected) protocol.label.uppercase(Locale.US) else "—"

    Box(modifier = Modifier.fillMaxWidth().alpha(if (muted) 0.82f else 1f)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(3.dp, 3.dp)
                .background(PaperInk.copy(alpha = 0.12f)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PaperLight)
                .border(1.dp, PaperInk)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            letterSpacing = 1.4.sp,
                        ),
                        color = PaperMuted,
                    )
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            letterSpacing = 0.7.sp,
                        ),
                        color = PaperFaint,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                TicketStamp(
                    top = if (warpPlus && connected) "WARP+" else stampTop,
                    middle = stampMiddle,
                    bottom = stampBottom,
                    color = stampColor,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "DARI",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            letterSpacing = 1.sp,
                        ),
                        color = PaperMuted,
                    )
                    Text(
                        "Anda",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        color = PaperInk,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                    Text(
                        "Perangkat ini",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                        color = PaperMuted,
                    )
                }
                RouteConnector(
                    active = connected,
                    modifier = Modifier
                        .width(36.dp)
                        .height(30.dp)
                        .padding(horizontal = 4.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        "KE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            letterSpacing = 1.sp,
                        ),
                        color = PaperMuted,
                    )
                    Text(
                        "Cloudflare",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        color = PaperInk,
                        modifier = Modifier.padding(top = 1.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Rute otomatis",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                        color = PaperMuted,
                    )
                }
            }

            Spacer(Modifier.height(9.dp))
            Barcode(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(13.dp)
                    .alpha(if (muted) 0.25f else 0.72f),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LINTAS · WARP",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 7.5.sp,
                        letterSpacing = 0.8.sp,
                    ),
                    color = PaperMuted,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "WARP / $protocolLabel",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 7.5.sp,
                        letterSpacing = 0.8.sp,
                    ),
                    color = PaperMuted,
                )
            }
        }
    }
}

@Composable
private fun TicketStamp(
    top: String,
    middle: String,
    bottom: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .rotate(8f)
            .border(1.5.dp, color, CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, color.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    top,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 6.5.sp,
                        letterSpacing = 0.5.sp,
                    ),
                    color = color,
                    maxLines = 1,
                )
                Text(
                    middle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 6.sp,
                        letterSpacing = 0.3.sp,
                    ),
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    bottom,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 5.5.sp,
                        letterSpacing = 0.3.sp,
                    ),
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RouteConnector(active: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val y = size.height * 0.78f
        val lineColor = if (active) StampOrange.copy(alpha = 0.75f) else PaperFaint
        drawLine(
            color = lineColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
        )
        drawCircle(
            color = if (active) StampOrange else PaperFaint,
            radius = 2.5.dp.toPx(),
            center = Offset(0f, y),
        )
        drawCircle(
            color = if (active) StampGreen else PaperFaint,
            radius = 2.5.dp.toPx(),
            center = Offset(size.width, y),
        )
    }
}

@Composable
private fun Barcode(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val pattern = intArrayOf(1, 1, 2, 1, 3, 1, 1, 2, 1, 1, 2, 3, 1, 2, 1, 1, 3, 1, 2)
        var x = 0f
        var index = 0
        val unit = 1.dp.toPx()
        while (x < size.width) {
            val barWidth = pattern[index % pattern.size] * unit
            if (index % 2 == 0) {
                drawRect(
                    color = PaperInk.copy(alpha = 0.72f),
                    topLeft = Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(barWidth, size.height),
                )
            }
            x += barWidth + unit
            index += 1
        }
    }
}

@Composable
private fun StatsStrip(duration: String, speed: String, data: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = PaperInk, thickness = 1.dp)
        Row(modifier = Modifier.fillMaxWidth().height(46.dp)) {
            StatCell("DURASI", duration, Modifier.weight(1f), first = true)
            StatDivider()
            StatCell("UNDUH", speed, Modifier.weight(1f))
            StatDivider()
            StatCell("DATA", data, Modifier.weight(1f), last = true)
        }
        HorizontalDivider(color = PaperInk, thickness = 1.dp)
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    last: Boolean = false,
) {
    Column(
        modifier = modifier.padding(
            start = if (first) 0.dp else 9.dp,
            end = if (last) 0.dp else 4.dp,
        ),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                letterSpacing = 1.sp,
            ),
            color = PaperMuted,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            ),
            color = PaperInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(30.dp)
            .background(PaperInk.copy(alpha = 0.2f)),
    )
}

@Composable
private fun LintasActionButton(
    label: String,
    solid: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.5.dp, StampOrange),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (solid) StampOrange else Color.Transparent,
            contentColor = if (solid) PaperLight else StampOrange,
            disabledContainerColor = if (solid) StampOrange.copy(alpha = 0.42f) else Color.Transparent,
            disabledContentColor = StampOrange.copy(alpha = 0.42f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.width(7.dp))
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 10.5.sp,
                letterSpacing = 1.2.sp,
            ),
        )
    }
}

@Composable
private fun Watermark() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .rotate(-7f)
                .border(1.dp, PaperInk.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "LINTAS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 7.sp,
                        letterSpacing = 1.2.sp,
                    ),
                    color = PaperInk.copy(alpha = 0.26f),
                )
                Text(
                    "WARP · 0042",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 5.5.sp,
                        letterSpacing = 0.8.sp,
                    ),
                    color = PaperInk.copy(alpha = 0.24f),
                )
            }
        }
        Text(
            "IZIN INI TIDAK DAPAT DIPINDAHTANGANKAN",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 6.5.sp,
                letterSpacing = 1.1.sp,
            ),
            color = PaperInk.copy(alpha = 0.26f),
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun Journal(
    text: String,
    meta: String,
    sealTop: String,
    sealMiddle: String,
    sealBottom: String,
    sealColor: Color,
) {
    HorizontalDivider(
        color = PaperInk.copy(alpha = 0.22f),
        thickness = 1.dp,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "CATATAN PERJALANAN",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = PaperMuted,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                ),
                color = PaperInk,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 7.5.sp,
                    letterSpacing = 0.7.sp,
                ),
                color = PaperMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        RoundSeal(
            top = sealTop,
            middle = sealMiddle,
            bottom = sealBottom,
            color = sealColor,
            size = 78.dp,
        )
    }
}

@Composable
private fun RoundSeal(
    top: String,
    middle: String,
    bottom: String,
    color: Color,
    size: Dp = 78.dp,
    rotation: Float = -7f,
) {
    Box(
        modifier = Modifier
            .size(size)
            .rotate(rotation)
            .border(1.5.dp, color, CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, color.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    top,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 6.5.sp,
                        letterSpacing = 0.8.sp,
                    ),
                    color = color,
                    maxLines = 1,
                )
                Text(
                    middle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                    ),
                    color = color,
                    maxLines = 1,
                )
                Text(
                    bottom,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 6.sp,
                        letterSpacing = 0.4.sp,
                    ),
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RouteScreen(
    tunnel: TunnelState,
    protocol: VpnProtocol,
    warp: WarpState,
    probe: ProbeState,
    onProbe: () -> Unit,
) {
    val connected = tunnel.status == TunnelStatus.CONNECTED
    val connecting = tunnel.status == TunnelStatus.CONNECTING
    val error = tunnel.status == TunnelStatus.ERROR
    val testMode = connected && tunnel.transport == TunnelTransport.SPIKE
    val identity = warp.identity
    val visibleProtocol = sessionProtocolOf(tunnel) ?: protocol
    val transport = transportLabel(if (connected) tunnel.transport else null)
    val endpoint = endpointLabel(identity, tunnel, protocol)
    val warpAddress = identity?.ipv4?.substringBefore('/') ?: "Belum tersedia"
    val statusLabel = when {
        connecting -> "PROSES"
        connected -> if (testMode) "UJI" else "AKTIF"
        error -> "GANGGUAN"
        else -> "SIAGA"
    }
    val statusColor = when {
        connecting || error -> StampOrange
        connected -> StampGreen
        else -> PaperFaint
    }
    val netStatus = when {
        connecting -> "Menyiapkan"
        testMode -> "Mode uji"
        connected -> "Aman"
        else -> "Belum aktif"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        PageHead(
            title = "Rute",
            subtitle = "JALUR WARP SAAT INI",
            page = 2,
            label = "RUTE",
        )
        Spacer(Modifier.height(12.dp))
        PaperCard {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "RUTE OTOMATIS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            letterSpacing = 1.2.sp,
                        ),
                        color = PaperMuted,
                    )
                    Text(
                        "Cloudflare memilih gerbang terdekat.",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp),
                        color = PaperInk,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Text(
                        "Berdasarkan lokasi dan kondisi jaringan Anda.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                        color = PaperMuted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(statusLabel, statusColor)
            }

            Spacer(Modifier.height(8.dp))
            ItineraryStop(
                title = "Perangkat ini",
                subtitle = "Anda",
                meta = "WARP",
                dot = ItineraryDot.Start,
                first = true,
            )
            ItineraryStop(
                title = "Cloudflare",
                subtitle = "Gerbang terdekat",
                meta = visibleProtocol.label.uppercase(Locale.US),
                dot = ItineraryDot.Gateway,
                gatewayActive = connected,
            )
            ItineraryStop(
                title = "Internet",
                subtitle = if (testMode) "Belum dialihkan" else "Keluar lewat WARP",
                meta = netStatus,
                metaColor = if (connected && !testMode) StampGreen else PaperMuted,
                dot = ItineraryDot.End,
                endActive = connected && !testMode,
                last = true,
            )
        }
        Spacer(Modifier.height(13.dp))
        SectionLabel("DETAIL KONEKSI")
        Column(modifier = Modifier.fillMaxWidth().border(1.dp, PaperLine.copy(alpha = 0.8f))) {
            DetailRow("Transport", transport)
            HorizontalDivider(color = PaperLine.copy(alpha = 0.55f))
            DetailRow("Endpoint", endpoint)
            HorizontalDivider(color = PaperLine.copy(alpha = 0.55f))
            DetailRow("Alamat WARP", warpAddress)
            HorizontalDivider(color = PaperLine.copy(alpha = 0.55f))
            DetailRow("DNS", "1.1.1.1")
        }
        Text(
            "Pemilihan gerbang manual belum tersedia. Rute mengikuti jaringan Cloudflare yang aktif.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
            color = PaperMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(13.dp))
        LintasActionButton(
            label = when {
                !connected -> "Hubungkan dulu"
                probe.status == ProbeStatus.RUNNING -> "Menguji izin…"
                probe.status == ProbeStatus.SUCCESS -> "Uji ulang"
                else -> "Uji koneksi"
            },
            solid = false,
            enabled = connected && probe.status != ProbeStatus.RUNNING,
            onClick = onProbe,
            icon = {
                if (probe.status == ProbeStatus.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = StampOrange.copy(alpha = 0.6f),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
            },
        )
        ProbeFeedback(probe)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PageHead(title: String, subtitle: String, page: Int, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 34.sp,
                    lineHeight = 36.sp,
                ),
                color = PaperInk,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = PaperMuted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "HAL. 0$page / 04",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = PaperMuted,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    letterSpacing = 0.8.sp,
                ),
                color = StampOrange,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PaperCard(
    modifier: Modifier = Modifier,
    background: Color = PaperLight,
    borderColor: Color = PaperInk,
    shadow: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (shadow) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(3.dp, 3.dp)
                    .background(PaperInk.copy(alpha = 0.1f)),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .border(1.dp, borderColor)
                .padding(12.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        ),
        color = color,
        modifier = Modifier
            .border(1.dp, color, CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private enum class ItineraryDot { Start, Gateway, End }

@Composable
private fun ItineraryStop(
    title: String,
    subtitle: String,
    meta: String,
    dot: ItineraryDot,
    first: Boolean = false,
    last: Boolean = false,
    gatewayActive: Boolean = true,
    endActive: Boolean = true,
    metaColor: Color = PaperMuted,
) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.width(18.dp).fillMaxHeight()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val lineColor = PaperLineStrong.copy(alpha = 0.85f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            if (!first) {
                drawLine(
                    color = lineColor,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, centerY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }
            if (!last) {
                drawLine(
                    color = lineColor,
                    start = Offset(centerX, centerY),
                    end = Offset(centerX, size.height),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }
            when (dot) {
                ItineraryDot.Start -> drawCircle(
                    color = PaperInk,
                    radius = 3.dp.toPx(),
                    center = Offset(centerX, centerY),
                )
                ItineraryDot.Gateway -> {
                    val gateColor = if (gatewayActive) StampOrange else PaperFaint
                    drawCircle(
                        color = gateColor.copy(alpha = 0.14f),
                        radius = 8.dp.toPx(),
                        center = Offset(centerX, centerY),
                    )
                    drawCircle(
                        color = gateColor,
                        radius = 4.5.dp.toPx(),
                        center = Offset(centerX, centerY),
                    )
                }
                ItineraryDot.End -> {
                    val endColor = if (endActive) StampGreen else PaperFaint
                    drawCircle(
                        color = endColor,
                        radius = 3.5.dp.toPx(),
                        center = Offset(centerX, centerY),
                    )
                    drawCircle(
                        color = endColor.copy(alpha = 0.45f),
                        radius = 6.5.dp.toPx(),
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = PaperInk,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = PaperMuted,
            )
        }
        Text(
            meta,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = metaColor,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = PaperMuted,
            modifier = Modifier.weight(0.8f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = PaperInk,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun ProbeFeedback(probe: ProbeState) {
    if (probe.status == ProbeStatus.IDLE) return
    val running = probe.status == ProbeStatus.RUNNING
    val success = probe.status == ProbeStatus.SUCCESS
    val color = when {
        running -> StampOrange
        success -> StampGreen
        else -> StampOrange
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(color.copy(alpha = 0.05f))
            .border(1.dp, color.copy(alpha = 0.3f))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            text = when {
                running -> "MENGUJI DNS 1.1.1.1 LEWAT TUNNEL…"
                success -> "KONEKSI OK · ${probe.latencyMs ?: 0} ms · DNS 1.1.1.1"
                else -> probe.message ?: "Uji koneksi gagal"
            },
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                lineHeight = 11.sp,
            ),
            color = color,
        )
    }
}

@Composable
private fun SessionScreen(
    tunnel: TunnelState,
    protocol: VpnProtocol,
    speed: Long,
    nowMillis: Long,
    warpAddress: String?,
) {
    val connected = tunnel.status == TunnelStatus.CONNECTED
    val connecting = tunnel.status == TunnelStatus.CONNECTING
    val error = tunnel.status == TunnelStatus.ERROR
    val testMode = connected && tunnel.transport == TunnelTransport.SPIKE
    val visibleProtocol = sessionProtocolOf(tunnel) ?: protocol
    val transport = transportLabel(if (connected) tunnel.transport else null)
    val totalBytes = tunnel.bytes + tunnel.rxBytes
    val duration = if (connected) formatDuration(tunnel.startedAtMillis, nowMillis) else "—"
    val data = if (connected) formatBytes(totalBytes) else "—"
    val speedLabel = if (connected) formatSpeed(speed) else "—"
    val date = todayLabel()
    val statusLabel = when {
        connecting -> "PROSES"
        connected -> if (testMode) "UJI" else "AKTIF"
        error -> "GAGAL"
        else -> "TUTUP"
    }
    val statusColor = when {
        connecting || error -> StampOrange
        connected -> StampGreen
        else -> PaperFaint
    }
    val cardLabel = when {
        connecting -> "MEMVALIDASI IZIN"
        connected -> "SESI BERJALAN"
        error -> "SESI TERPUTUS"
        else -> "TIDAK ADA SESI"
    }
    val note = when {
        connecting -> "Izin sedang divalidasi. Mohon tunggu."
        connected -> "Berangkat ${formatClock(tunnel.startedAtMillis)} · Perangkat ini → Cloudflare."
        error -> tunnel.error ?: "Sesi terputus. Berangkat lagi dari Beranda."
        else -> "Sesi ditutup. Berangkat lagi dari Beranda."
    }
    val configuration = LocalConfiguration.current
    val scrollable = configuration.screenHeightDp < 700 || configuration.fontScale > 1.15f
    val pageModifier = if (scrollable) {
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxSize()
    }

    Column(
        modifier = pageModifier.padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        PageHead(
            title = "Sesi",
            subtitle = "PEMERIKSAAN KONEKSI",
            page = 3,
            label = "SESI",
        )
        Spacer(Modifier.height(12.dp))
        PaperCard(
            background = if (connected) PaperLight else Color.Transparent,
            borderColor = if (connected) PaperInk else PaperLineStrong,
            shadow = connected,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cardLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        letterSpacing = 1.2.sp,
                    ),
                    color = PaperMuted,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    color = statusColor,
                )
            }
            Text(
                duration,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 31.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-1).sp,
                ),
                color = if (connected) PaperInk else PaperFaint,
                modifier = Modifier.padding(top = 5.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SessionMetric(speedLabel, if (connected) "unduh" else null)
                SessionMetric(data, null)
                SessionMetric(
                    if (connected) visibleProtocol.label.uppercase(Locale.US) else "—",
                    null,
                )
            }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
                color = PaperMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(13.dp))
        SectionLabel("PEMERIKSAAN")
        Column(modifier = Modifier.fillMaxWidth()) {
            SessionCheck(
                ok = connected,
                title = "TUN interface",
                subtitle = when {
                    connected && warpAddress != null -> "$warpAddress · full tunnel"
                    connected -> "Tunnel aktif"
                    else -> "Belum dibuat"
                },
                value = if (connected) "Aktif" else "—",
            )
            SessionCheck(
                ok = NativeCore.isAvailable(),
                title = "Core Rust",
                subtitle = if (NativeCore.isAvailable()) "liblintas_core.so termuat" else "Library belum tersedia",
                value = if (NativeCore.isAvailable()) "Siap" else "Gagal",
            )
            SessionCheck(
                ok = connected,
                title = if (transport == "—") visibleProtocol.label else transport,
                subtitle = if (connected) "Transport tunnel" else "Siaga",
                value = if (connected) "Aktif" else "—",
                last = true,
            )
        }
        Text(
            "Data dihitung sejak sesi dimulai. Data sesi tidak memuat kunci WARP atau lisensi.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.5.sp, lineHeight = 14.sp),
            color = PaperMuted,
            modifier = Modifier.padding(top = 9.dp),
        )
        if (scrollable) {
            Spacer(Modifier.height(18.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (connected) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                SpeedSeal(
                    speed = speedLabel,
                    meta = "$duration · $date",
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmptySessionSeal(date = date)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SessionMetric(value: String, suffix: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            ),
            color = PaperInk,
            maxLines = 1,
        )
        if (suffix != null) {
            Text(
                " $suffix",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = PaperMuted,
            )
        }
    }
}

@Composable
private fun SessionCheck(
    ok: Boolean,
    title: String,
    subtitle: String,
    value: String,
    last: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(if (ok) StampGreen else PaperFaint, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = PaperInk,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = PaperMuted,
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = if (ok) StampGreen else PaperMuted,
        )
    }
    if (!last) {
        HorizontalDivider(color = PaperLine.copy(alpha = 0.65f), thickness = 1.dp)
    }
}

@Composable
private fun EmptySessionSeal(date: String) {
    Box(
        modifier = Modifier
            .size(124.dp)
            .rotate(-6f)
            .border(1.dp, PaperLineStrong.copy(alpha = 0.7f), CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, PaperLineStrong.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "BELUM ADA",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 7.sp,
                        letterSpacing = 1.1.sp,
                    ),
                    color = PaperMuted,
                )
                Text(
                    "SESI",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    ),
                    color = PaperMuted,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 6.5.sp,
                        letterSpacing = 0.4.sp,
                    ),
                    color = PaperFaint,
                )
            }
        }
    }
}

@Composable
private fun SpeedSeal(speed: String, meta: String) {
    Box(
        modifier = Modifier
            .size(132.dp)
            .rotate(-6f)
            .border(1.5.dp, StampOrange, CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, StampOrange.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "UNDUH SAAT INI",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 7.sp,
                        letterSpacing = 1.2.sp,
                    ),
                    color = StampOrange,
                )
                Text(
                    speed,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                    ),
                    color = StampOrange,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 6.5.sp,
                        letterSpacing = 0.4.sp,
                    ),
                    color = StampOrange.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun DocumentsScreen(
    warp: WarpState,
    tunnel: TunnelState,
    protocol: VpnProtocol,
    onProtocol: (VpnProtocol) -> Unit,
    onWarpRegister: () -> Unit,
    onWarpEnroll: () -> Unit,
    onOpenWarpPlus: () -> Unit,
    onShowResetWarp: () -> Unit,
    update: UpdateState,
    onOpenUpdate: () -> Unit,
    splitTunnelCount: Int,
    splitTunnelActive: Boolean,
    onOpenSplitTunnel: () -> Unit,
    diagnosticsEnabled: Boolean,
    onDiagnosticsToggle: (Boolean) -> Unit,
) {
    val connected = tunnel.status == TunnelStatus.CONNECTED
    val identity = warp.identity
    val busy = warp.status == WarpStatus.REGISTERING || warp.status == WarpStatus.ENROLLING
    val needsRegister = identity == null
    val needsMasque = identity != null && !identity.hasMasqueTransport
    val sessionProtocol = sessionProtocolOf(tunnel)
    val protocolNote = when {
        connected && sessionProtocol != null && sessionProtocol != protocol ->
            "Berlaku untuk koneksi berikutnya."
        connected -> "Sedang dipakai sesi ini."
        else -> "Protokol untuk koneksi berikutnya."
    }
    val docTitle = when {
        needsRegister -> "WARP belum siap"
        needsMasque -> "WARP siap untuk WireGuard"
        else -> "WARP siap"
    }
    val docBody = when {
        needsRegister -> "Daftarkan identitas untuk memulai perjalanan."
        needsMasque -> "MASQUE belum disiapkan untuk rute modern."
        else -> "Satu identitas untuk WireGuard dan MASQUE."
    }
    val docActionLabel = when {
        needsRegister -> "Daftar"
        needsMasque -> "Siapkan"
        else -> null
    }
    val docAction = when {
        needsRegister -> onWarpRegister
        needsMasque -> onWarpEnroll
        else -> null
    }
    val docStamp = when {
        busy -> "PROSES"
        identity != null -> "SAH"
        else -> "BARU"
    }
    val docStampColor = when {
        busy -> StampBlue
        identity != null -> StampGreen
        else -> StampOrange
    }
    val configuration = LocalConfiguration.current
    val scrollable = configuration.screenHeightDp < 700 || configuration.fontScale > 1.15f
    val pageModifier = if (scrollable) {
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxSize()
    }

    Column(
        modifier = pageModifier.padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        PageHead(
            title = "Dokumen",
            subtitle = "VISA DAN IZIN",
            page = 4,
            label = "DOKUMEN",
        )
        Spacer(Modifier.height(12.dp))
        PaperCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DocStamp(docStamp, docStampColor)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        docTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                        color = PaperInk,
                    )
                    Text(
                        docBody,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
                        color = PaperMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (docActionLabel != null && docAction != null) {
                    MiniButton(
                        label = if (busy) "…" else docActionLabel,
                        enabled = !busy,
                        onClick = docAction,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "TERDAFTAR",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                            ),
                            color = StampGreen,
                        )
                        Text(
                            formatDate(identity?.registeredAt),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp),
                            color = PaperMuted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            if (warp.error != null) {
                Text(
                    warp.error,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = StampOrange,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (identity != null && !connected) {
            Text(
                "Reset identitas",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    textDecoration = TextDecoration.Underline,
                ),
                color = PaperMuted,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp)
                    .clickable(onClick = onShowResetWarp)
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(9.dp))
        WarpPlusCard(
            identity = identity,
            active = identity?.warpPlus == true,
            onOpen = onOpenWarpPlus,
        )

        SectionLabel("PROTOKOL")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProtocolCard(
                code = "WG",
                title = "WireGuard",
                subtitle = "Koneksi klasik",
                selected = protocol == VpnProtocol.WIREGUARD,
                onClick = { onProtocol(VpnProtocol.WIREGUARD) },
                modifier = Modifier.weight(1f),
            )
            ProtocolCard(
                code = "MQ",
                title = "MASQUE",
                subtitle = "Tunnel modern",
                selected = protocol == VpnProtocol.MASQUE,
                onClick = { onProtocol(VpnProtocol.MASQUE) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            protocolNote,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
            color = PaperMuted,
            modifier = Modifier.padding(top = 5.dp),
        )

        SectionLabel("PREFERENSI")
        Column(modifier = Modifier.fillMaxWidth()) {
            PreferenceRow(
                title = "Split tunnel",
                subtitle = "Kelola aplikasi yang tidak melewati WARP.",
                onClick = onOpenSplitTunnel,
                trailing = {
                    Text(
                        when {
                            splitTunnelCount == 0 -> "Tidak ada"
                            splitTunnelCount == 1 -> "1 aplikasi"
                            else -> "$splitTunnelCount aplikasi"
                        } + if (splitTunnelActive && splitTunnelCount > 0) " · sambung ulang" else "",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = PaperMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "›",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        color = PaperFaint,
                    )
                },
            )
            HorizontalDivider(color = PaperLine.copy(alpha = 0.65f), thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = diagnosticsEnabled,
                        role = Role.Checkbox,
                        onValueChange = onDiagnosticsToggle,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Log diagnostik",
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                        color = PaperInk,
                    )
                    Text(
                        if (diagnosticsEnabled) {
                            "Aktif. Event koneksi dan error transport dicatat."
                        } else {
                            "Nonaktif. Aktifkan saat perlu."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
                        color = PaperMuted,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                StampCheck(checked = diagnosticsEnabled)
            }
        }

        SectionLabel("PEMBARUAN")
        UpdateCard(update = update, onClick = onOpenUpdate)

        if (scrollable) {
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = PaperInk.copy(alpha = 0.16f),
                thickness = 1.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "LINTAS ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp),
                color = PaperFaint,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "GPL-3.0 · RUST + KOTLIN",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp),
                color = PaperFaint,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DocStamp(text: String, color: Color) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .rotate(-7f)
            .border(1.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 6.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            ),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun MiniButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        label.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        ),
        color = if (enabled) StampOrange else PaperFaint,
        modifier = Modifier
            .clip(CircleShape)
            .border(
                1.dp,
                if (enabled) StampOrange.copy(alpha = 0.42f) else PaperFaint.copy(alpha = 0.45f),
                CircleShape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        maxLines = 1,
    )
}

@Composable
private fun WarpPlusCard(
    identity: WarpIdentity?,
    active: Boolean,
    onOpen: () -> Unit,
) {
    val enabled = identity != null
    val borderColor = when {
        active -> StampGreen.copy(alpha = 0.4f)
        enabled -> StampOrange.copy(alpha = 0.28f)
        else -> PaperLineStrong
    }
    val background = when {
        active -> StampGreen.copy(alpha = 0.055f)
        enabled -> StampOrange.copy(alpha = 0.045f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .border(1.dp, borderColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .border(
                    1.dp,
                    if (active) StampGreen.copy(alpha = 0.5f) else StampOrange.copy(alpha = 0.4f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "✦",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 13.sp),
                color = if (active) StampGreen else StampOrange,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (active) "WARP+ aktif" else "WARP+ belum aktif",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.5.sp),
                color = PaperInk,
            )
            Text(
                when {
                    !enabled -> "Daftarkan WARP terlebih dahulu."
                    active && identity?.plusExpiresAt != null ->
                        "Argo Smart Routing aktif sampai ${identity.plusExpiresAt}."
                    active -> "Argo Smart Routing aktif untuk rute berikutnya."
                    else -> "Argo Smart Routing untuk rute lebih cepat."
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = PaperMuted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        MiniButton(
            label = if (active) "Aktif" else "Aktifkan",
            enabled = enabled,
            onClick = onOpen,
        )
    }
}

@Composable
private fun ProtocolCard(
    code: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 86.dp)
            .background(if (selected) PaperLight else PaperLight.copy(alpha = 0.5f))
            .border(
                1.dp,
                if (selected) StampOrange else PaperLineStrong.copy(alpha = 0.7f),
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(9.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(
                        1.dp,
                        if (selected) StampOrange else PaperMuted,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    code,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 6.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                    ),
                    color = if (selected) StampOrange else PaperMuted,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.5.sp),
                color = PaperInk,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.5.sp),
                color = PaperMuted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (selected) {
            Text(
                "DIPILIH",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 6.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                ),
                color = StampOrange,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun PreferenceRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                color = PaperInk,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
                color = PaperMuted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            trailing()
        }
    }
}

@Composable
private fun StampCheck(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .rotate(-3f)
            .clip(RoundedCornerShape(2.dp))
            .background(if (checked) StampOrange else Color.Transparent)
            .border(
                1.dp,
                if (checked) StampOrange else PaperLineStrong,
                RoundedCornerShape(2.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                "✓",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = PaperLight,
            )
        }
    }
}

@Composable
private fun UpdateCard(update: UpdateState, onClick: () -> Unit) {
    val stamp = when (update.status) {
        UpdateStatus.AVAILABLE -> update.release?.versionName ?: "BARU"
        UpdateStatus.READY -> "SIAP"
        UpdateStatus.DOWNLOADING -> "${update.progress}%"
        UpdateStatus.CHECKING -> "…"
        UpdateStatus.ERROR -> "ULANG"
        else -> "CEK"
    }
    val subtitle = when (update.status) {
        UpdateStatus.IDLE -> "Versi ${update.currentVersion} terpasang."
        UpdateStatus.CHECKING -> "Memeriksa pembaruan…"
        UpdateStatus.UP_TO_DATE -> "Versi ${update.currentVersion} terbaru."
        UpdateStatus.AVAILABLE -> "Versi ${update.release?.versionName ?: "baru"} tersedia."
        UpdateStatus.DOWNLOADING -> "Mengunduh ${update.progress}%."
        UpdateStatus.READY -> "Pembaruan siap dipasang."
        UpdateStatus.ERROR -> "Gagal memeriksa. Coba lagi."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StampOrange.copy(alpha = 0.05f))
            .border(1.dp, StampOrange.copy(alpha = 0.3f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(27.dp)
                .border(1.dp, StampOrange.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "↓",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = StampOrange,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Pembaruan",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.5.sp),
                color = PaperInk,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = PaperMuted,
                modifier = Modifier.padding(top = 1.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stamp,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            ),
            color = StampOrange,
            modifier = Modifier
                .border(1.dp, StampOrange.copy(alpha = 0.35f), CircleShape)
                .padding(horizontal = 7.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionLabel(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.5.sp,
                letterSpacing = 1.2.sp,
            ),
            color = PaperMuted,
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = PaperInk.copy(alpha = 0.16f),
            thickness = 1.dp,
        )
    }
}

@Composable
private fun PaperToast(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PaperLight)
            .border(1.dp, PaperInk)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(StampOrange, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            message,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                lineHeight = 11.sp,
            ),
            color = PaperInk,
        )
    }
}

@Composable
private fun PaperSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(PaperInk.copy(alpha = 0.38f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(PaperLight)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(3.dp)
                .background(PaperInk.copy(alpha = 0.2f), CircleShape),
        )
    }
}

@Composable
private fun SheetEyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 8.sp,
            letterSpacing = 1.2.sp,
        ),
        color = PaperMuted,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun SheetActions(
    secondaryLabel: String,
    primaryLabel: String,
    primaryEnabled: Boolean = true,
    onSecondary: () -> Unit,
    onPrimary: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = onSecondary,
            modifier = Modifier.weight(1f).height(40.dp),
            shape = RoundedCornerShape(2.dp),
        ) {
            Text(
                secondaryLabel.uppercase(Locale.US),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 9.5.sp),
                color = PaperMuted,
            )
        }
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.weight(1f).height(40.dp),
            shape = RoundedCornerShape(2.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = StampOrange,
                contentColor = PaperLight,
                disabledContainerColor = StampOrange.copy(alpha = 0.42f),
                disabledContentColor = PaperLight.copy(alpha = 0.7f),
            ),
        ) {
            Text(
                primaryLabel.uppercase(Locale.US),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 9.5.sp),
            )
        }
    }
}

@Composable
private fun DisconnectSheet(
    tunnel: TunnelState,
    nowMillis: Long,
    protocol: VpnProtocol,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val activeProtocol = sessionProtocolOf(tunnel) ?: protocol
    val totalBytes = tunnel.bytes + tunnel.rxBytes
    SheetHandle()
    SheetEyebrow("KONFIRMASI · CAP KELUAR")
    Text(
        "Akhiri perjalanan?",
        style = MaterialTheme.typography.headlineMedium,
        color = PaperInk,
        modifier = Modifier.padding(top = 7.dp),
    )
    Text(
        "Sesi ini akan ditutup dan koneksi WARP dihentikan. Tiket perjalanan tidak disimpan.",
        style = MaterialTheme.typography.bodyMedium,
        color = PaperInkSoft,
        modifier = Modifier.padding(top = 6.dp),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        HorizontalDivider(color = PaperInk.copy(alpha = 0.22f), thickness = 1.dp)
        Text(
            "${formatDuration(tunnel.startedAtMillis, nowMillis)} · ${formatBytes(totalBytes)} · ${activeProtocol.label.uppercase(Locale.US)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = PaperMuted,
            modifier = Modifier.padding(vertical = 7.dp),
        )
        HorizontalDivider(color = PaperInk.copy(alpha = 0.22f), thickness = 1.dp)
    }
    SheetActions(
        secondaryLabel = "Batal",
        primaryLabel = "Tutup sesi",
        onSecondary = onDismiss,
        onPrimary = onConfirm,
    )
}

@Composable
private fun WarpPlusSheet(
    identity: WarpIdentity?,
    state: WarpPlusState,
    check: WarpCheckState,
    license: String,
    onLicenseChange: (String) -> Unit,
    onCheck: () -> Unit,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = identity?.warpPlus == true
    val busy = state.busy
    SheetHandle()
    SheetEyebrow("VISA · WARP+")
    Text(
        if (active) "Ganti license key" else "Aktifkan WARP+",
        style = MaterialTheme.typography.headlineMedium,
        color = PaperInk,
        modifier = Modifier.padding(top = 7.dp),
    )
    Text(
        if (active) {
            "License key baru menggantikan key yang tersimpan. Visa ini mempercepat rute tanpa mengubah protokol."
        } else {
            "Masukkan license key WARP+ untuk mengaktifkan Argo Smart Routing. Visa ini mempercepat rute tanpa mengubah protokol."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = PaperInkSoft,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (active && identity?.plusExpiresAt != null) {
        Text(
            "AKTIF SAMPAI ${identity.plusExpiresAt}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = StampGreen,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    OutlinedTextField(
        value = license,
        onValueChange = onLicenseChange,
        singleLine = true,
        enabled = !busy,
        label = { Text("License key") },
        placeholder = { Text("XXXX-XXXX-XXXX") },
        shape = RoundedCornerShape(2.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = StampOrange,
            unfocusedBorderColor = PaperLineStrong,
            focusedLabelColor = StampOrange,
            unfocusedLabelColor = PaperMuted,
            focusedContainerColor = Paper.copy(alpha = 0.35f),
            unfocusedContainerColor = Paper.copy(alpha = 0.35f),
            cursorColor = StampOrange,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
    )
    if (state.error != null) {
        Text(
            state.error,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
            color = StampOrange,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
    if (identity != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCheck,
                enabled = !check.checking,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    if (check.checking) "Memeriksa…" else "Cek status lisensi",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = PaperMuted,
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                check.error != null -> Text(
                    check.error,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = StampOrange,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                check.checkedAt != null -> Text(
                    "Status diperbarui.",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = StampGreen,
                )
            }
        }
    }
    SheetActions(
        secondaryLabel = if (active) "Tutup" else "Nanti",
        primaryLabel = when {
            busy && active -> "Mengganti…"
            busy -> "Mengaktifkan…"
            active -> "Ganti"
            else -> "Aktifkan visa"
        },
        primaryEnabled = !busy && license.isNotBlank(),
        onSecondary = onDismiss,
        onPrimary = onActivate,
    )
}

@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali",
                    tint = PaperInk,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = PaperInk,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = PaperMuted,
            )
        }
        action?.invoke()
    }
}

@Composable
internal fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = PaperInk,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = PaperMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun todayLabel(): String {
    val calendar = Calendar.getInstance()
    return String.format(
        Locale.US,
        "%02d %s %04d",
        calendar.get(Calendar.DAY_OF_MONTH),
        MONTH_LABELS[calendar.get(Calendar.MONTH)],
        calendar.get(Calendar.YEAR),
    )
}

private fun formatDate(millis: Long?): String {
    if (millis == null || millis <= 0L) return "—"
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(
        Locale.US,
        "%02d %s %04d",
        calendar.get(Calendar.DAY_OF_MONTH),
        MONTH_LABELS[calendar.get(Calendar.MONTH)],
        calendar.get(Calendar.YEAR),
    )
}

private fun formatClock(millis: Long?): String {
    if (millis == null || millis <= 0L) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes >= 1024L * 1024L * 1024L ->
        String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L ->
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0L -> "0 KB/s"
    bytesPerSecond >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024L ->
        String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond B/s"
}

private fun formatDuration(startedAtMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
    if (startedAtMillis == null) return "--:--"
    val total = ((nowMillis - startedAtMillis) / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun sessionProtocolOf(tunnel: TunnelState): VpnProtocol? = when (tunnel.transport) {
    TunnelTransport.WIREGUARD -> VpnProtocol.WIREGUARD
    TunnelTransport.MASQUE -> VpnProtocol.MASQUE
    else -> null
}

private fun transportLabel(transport: TunnelTransport?): String = when (transport) {
    TunnelTransport.MASQUE -> "MASQUE"
    TunnelTransport.WIREGUARD -> "WireGuard"
    TunnelTransport.SPIKE -> "TUN"
    null -> "—"
}

private fun endpointLabel(
    identity: WarpIdentity?,
    tunnel: TunnelState,
    selectedProtocol: VpnProtocol,
): String {
    val protocol = sessionProtocolOf(tunnel) ?: selectedProtocol
    return when (protocol) {
        VpnProtocol.MASQUE -> identity?.let {
            if (it.masqueEndpointHost.isNotBlank()) {
                "${it.masqueEndpointHost}:${it.effectiveMasquePort}"
            } else {
                null
            }
        }
        VpnProtocol.WIREGUARD -> identity?.let {
            if (it.endpointHost.isNotBlank()) {
                "${it.endpointHost}:${it.effectiveEndpointPort}"
            } else {
                null
            }
        }
    } ?: "Belum tersedia"
}
