package app.lintas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lintas.core.NativeCore
import app.lintas.ui.theme.LintasTheme
import app.lintas.vpn.TunnelState
import app.lintas.vpn.VpnPreferences
import app.lintas.vpn.VpnProtocol
import app.lintas.vpn.TunnelTransport
import app.lintas.vpn.TunnelStatus
import app.lintas.vpn.VpnController
import app.lintas.warp.WarpController
import app.lintas.warp.WarpIdentity
import app.lintas.warp.WarpPlusState
import app.lintas.warp.WarpState
import app.lintas.warp.WarpStatus
import java.util.Locale


@Composable
fun LintasApp(
    onConnect: (VpnProtocol) -> Unit,
    onDisconnect: () -> Unit,
    onProbe: () -> Unit,
) {
    val context = LocalContext.current
    val tunnel by VpnController.state.collectAsStateWithLifecycle()
    val warp by WarpController.state.collectAsStateWithLifecycle()
    val warpPlusState by WarpController.warpPlus.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var protocolName by rememberSaveable {
        mutableStateOf(VpnPreferences.loadProtocol(context).name)
    }
    val protocol = runCatching { VpnProtocol.valueOf(protocolName) }
        .getOrDefault(VpnProtocol.WIREGUARD)

    LaunchedEffect(Unit) {
        WarpController.initialize(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            LintasNavBar(selected = tab, onTab = { tab = it })
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                0 -> HomeScreen(
                    tunnel = tunnel,
                    protocol = protocol,
                    warpPlus = warp.identity?.warpPlus == true,
                    onConnect = { onConnect(protocol) },
                    onDisconnect = onDisconnect,
                    onProbe = onProbe,
                    onOpenRoutes = { tab = 1 },
                    onOpenSettings = { tab = 3 },
                )
                1 -> RoutesScreen(
                    warp = warp,
                    tunnel = tunnel,
                    onProbe = onProbe,
                )
                2 -> ActivityScreen(
                    tunnel = tunnel,
                    warp = warp,
                )
                else -> SettingsScreen(
                    warp = warp,
                    tunnel = tunnel,
                    protocol = protocol,
                    onProtocol = {
                        protocolName = it.name
                        VpnPreferences.saveProtocol(context, it)
                    },
                    onWarpRegister = { WarpController.register(context) },
                    onWarpEnroll = { WarpController.enroll(context) },
                    onWarpReset = { WarpController.reset(context) },
                    warpPlus = warpPlusState,
                    onActivateWarpPlus = { WarpController.activateWarpPlus(context, it) },
                    onClearWarpPlus = { WarpController.clearWarpPlusFeedback() },
                )
            }
        }
    }
}

@Composable
private fun LintasNavBar(selected: Int, onTab: (Int) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        val items = listOf(
            Triple("Beranda", Icons.Outlined.Home, Icons.Filled.Home),
            Triple("Rute", Icons.AutoMirrored.Outlined.AltRoute, Icons.AutoMirrored.Filled.AltRoute),
            Triple("Aktivitas", Icons.Outlined.MonitorHeart, Icons.Filled.MonitorHeart),
            Triple("Setelan", Icons.Outlined.Tune, Icons.Filled.Tune),
        )
        items.forEachIndexed { index, (label, outlined, filled) ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onTab(index) },
                icon = {
                    Icon(
                        imageVector = if (selected == index) filled else outlined,
                        contentDescription = null,
                    )
                },
                label = {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun HomeScreen(
    tunnel: TunnelState,
    protocol: VpnProtocol,
    warpPlus: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onProbe: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val connected = tunnel.status == TunnelStatus.CONNECTED
    val connecting = tunnel.status == TunnelStatus.CONNECTING
    val error = tunnel.status == TunnelStatus.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            AppHeader(warpPlus, onOpenSettings)
            Spacer(Modifier.height(18.dp))
            SessionLine(tunnel.status, tunnel.transport)
            Spacer(Modifier.height(7.dp))
            Text(
                text = when {
                    tunnel.status == TunnelStatus.CONNECTED &&
                        (tunnel.transport == TunnelTransport.WIREGUARD || tunnel.transport == TunnelTransport.MASQUE) -> "Terlindungi"
                    tunnel.status == TunnelStatus.CONNECTED -> "TUN aktif"
                    tunnel.status == TunnelStatus.CONNECTING -> "Menyiapkan rute"
                    else -> "Belum terhubung"
                },
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    tunnel.status == TunnelStatus.CONNECTED && tunnel.transport == TunnelTransport.MASQUE ->
                        "Trafik keluar melalui WARP MASQUE."
                    tunnel.status == TunnelStatus.CONNECTED && tunnel.transport == TunnelTransport.WIREGUARD ->
                        "Trafik keluar melalui WARP WireGuard."
                    tunnel.status == TunnelStatus.CONNECTED ->
                        "Mode uji: TUN dan packet loop aktif; trafik internet belum dialihkan."
                    tunnel.status == TunnelStatus.CONNECTING -> "Menyiapkan ${protocol.label}."
                    tunnel.status == TunnelStatus.ERROR -> tunnel.error ?: "Terjadi kesalahan."
                    else -> "${protocol.label} dipilih. Hubungkan untuk mulai."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            RouteCard(
                connected = connected,
                tunnel = tunnel,
                protocol = protocol,
            )
            Spacer(Modifier.height(14.dp))
            StatsRow(tunnel)
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onOpenRoutes,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.AltRoute, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Detail rute", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onProbe,
                    enabled = connected,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Uji koneksi", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(26.dp))
        }

        ConnectButton(
            status = tunnel.status,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )
    }
}

@Composable
private fun AppHeader(warpPlus: Boolean, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Lintas",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Surface(
            onClick = onOpenSettings,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (warpPlus) "WARP+" else "WARP",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionLine(status: TunnelStatus, transport: TunnelTransport?) {
    val dotColor = when (status) {
        TunnelStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
        TunnelStatus.CONNECTING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val label = when {
        status == TunnelStatus.CONNECTED &&
            (transport == TunnelTransport.WIREGUARD || transport == TunnelTransport.MASQUE) -> "Sesi WARP aktif"
        status == TunnelStatus.CONNECTED -> "Sesi uji"
        status == TunnelStatus.CONNECTING -> "Sedang menghubungkan"
        status == TunnelStatus.ERROR -> "Perlu perhatian"
        else -> "Belum ada sesi"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RouteCard(
    connected: Boolean,
    tunnel: TunnelState,
    protocol: VpnProtocol,
) {
    val transportLabel = when {
        tunnel.transport == TunnelTransport.MASQUE -> "MASQUE"
        tunnel.transport == TunnelTransport.WIREGUARD -> "WIREGUARD"
        tunnel.transport == TunnelTransport.SPIKE -> "TUN"
        connected -> "WARP"
        else -> protocol.label.uppercase()
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Jalur",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(
                                if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = transportLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RouteMap(
                connected = connected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dari", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Anda", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Perangkat ini",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text("Ke", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Cloudflare",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "WARP · Rute otomatis",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteMap(connected: Boolean, modifier: Modifier = Modifier) {
    val landColor = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val leftLand = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.46f, 0f)
            cubicTo(w * 0.43f, h * 0.14f, w * 0.32f, h * 0.20f, w * 0.25f, h * 0.35f)
            cubicTo(w * 0.18f, h * 0.50f, w * 0.13f, h * 0.62f, 0f, h * 0.74f)
            close()
        }
        drawPath(leftLand, color = landColor)
        drawPath(
            leftLand,
            color = lineColor.copy(alpha = 0.75f),
            style = Stroke(width = 1.dp.toPx()),
        )
        val rightLand = Path().apply {
            moveTo(w, h)
            lineTo(w * 0.58f, h)
            cubicTo(w * 0.62f, h * 0.82f, w * 0.74f, h * 0.74f, w * 0.80f, h * 0.58f)
            cubicTo(w * 0.86f, h * 0.42f, w * 0.92f, h * 0.32f, w, h * 0.24f)
            close()
        }
        drawPath(rightLand, color = landColor)
        drawPath(
            rightLand,
            color = lineColor.copy(alpha = 0.75f),
            style = Stroke(width = 1.dp.toPx()),
        )
        val route = Path().apply {
            moveTo(w * 0.12f, h * 0.80f)
            cubicTo(w * 0.28f, h * 0.78f, w * 0.30f, h * 0.56f, w * 0.48f, h * 0.52f)
            cubicTo(w * 0.66f, h * 0.48f, w * 0.76f, h * 0.36f, w * 0.88f, h * 0.24f)
        }
        drawPath(
            route,
            color = lineColor,
            style = Stroke(
                width = 1.6.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f), 0f),
                cap = StrokeCap.Round,
            ),
        )
        if (connected) {
            drawPath(
                route,
                color = primary,
                style = Stroke(
                    width = 2.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f), 0f),
                    cap = StrokeCap.Round,
                ),
            )
        }
        drawCircle(
            color = ink,
            radius = 4.dp.toPx(),
            center = Offset(w * 0.12f, h * 0.80f),
        )
        drawCircle(
            color = if (connected) primary else muted,
            radius = 4.dp.toPx(),
            center = Offset(w * 0.88f, h * 0.24f),
        )
        drawCircle(
            color = if (connected) primary.copy(alpha = 0.45f) else lineColor.copy(alpha = 0.5f),
            radius = 9.dp.toPx(),
            center = Offset(w * 0.88f, h * 0.24f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
private fun StatsRow(tunnel: TunnelState) {
    Row(modifier = Modifier.fillMaxWidth()) {
        StatColumn(
            label = "Durasi",
            value = if (tunnel.status == TunnelStatus.CONNECTED) formatDuration(tunnel.startedAtMillis) else "--:--",
            modifier = Modifier.weight(1f),
        )
        StatDivider()
        StatColumn(
            label = "Paket",
            value = if (tunnel.packets > 0) tunnel.packets.toString() else "0",
            modifier = Modifier.weight(1f),
        )
        StatDivider()
        StatColumn(
            label = "Data",
            value = formatBytes(tunnel.bytes),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    )
}

@Composable
private fun ConnectButton(
    status: TunnelStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = status == TunnelStatus.CONNECTED
    val connecting = status == TunnelStatus.CONNECTING
    val label = when (status) {
        TunnelStatus.CONNECTED -> "Putuskan"
        TunnelStatus.CONNECTING -> "Menyambungkan"
        else -> "Hubungkan"
    }
    Button(
        onClick = { if (connected) onDisconnect() else onConnect() },
        enabled = !connecting,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        border = if (connected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (connected) Color.Transparent else MaterialTheme.colorScheme.primary,
            contentColor = if (connected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun RoutesScreen(
    warp: WarpState,
    tunnel: TunnelState,
    onProbe: () -> Unit,
) {
    val connected = tunnel.status == TunnelStatus.CONNECTED
    val identity = warp.identity
    val transport = when (tunnel.transport) {
        TunnelTransport.MASQUE -> "MASQUE"
        TunnelTransport.WIREGUARD -> "WireGuard"
        TunnelTransport.SPIKE -> "TUN"
        null -> "—"
    }
    val endpoint = when (tunnel.transport) {
        TunnelTransport.MASQUE -> identity?.let {
            "${it.masqueEndpointHost}:${it.effectiveMasquePort}"
        }
        TunnelTransport.WIREGUARD -> identity?.let {
            "${it.endpointHost}:${it.endpointPort}"
        }
        else -> null
    } ?: "Belum tersedia"
    val warpAddress = identity?.ipv4?.substringBefore('/') ?: "Belum tersedia"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(
            title = "Rute",
            subtitle = "Jalur WARP saat ini",
            action = {
                IconButton(onClick = onProbe, enabled = connected) {
                    Icon(Icons.Outlined.Speed, contentDescription = "Uji koneksi")
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.AltRoute,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Rute otomatis", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cloudflare memilih gerbang terdekat berdasarkan lokasi dan kondisi jaringan.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = if (connected) {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        if (connected) "Aktif" else "Siaga",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Detail koneksi",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                RouteInfoRow("Transport", if (connected) transport else "—")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                RouteInfoRow("Endpoint", endpoint)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                RouteInfoRow("Alamat WARP", warpAddress)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                RouteInfoRow("DNS", "1.1.1.1")
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Pemilihan gerbang manual belum tersedia. Rute mengikuti jaringan Cloudflare yang aktif.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = onProbe,
            enabled = connected,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Uji koneksi", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RouteInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.8f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun ActivityScreen(tunnel: TunnelState, warp: WarpState) {
    val connected = tunnel.status == TunnelStatus.CONNECTED
    val identity = warp.identity
    val transport = when (tunnel.transport) {
        TunnelTransport.MASQUE -> "MASQUE"
        TunnelTransport.WIREGUARD -> "WireGuard"
        TunnelTransport.SPIKE -> "TUN"
        null -> "—"
    }
    val tunAddress = identity?.ipv4?.substringBefore('/')

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(title = "Aktivitas", subtitle = "Sesi dan detail koneksi")
        Spacer(Modifier.height(16.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (connected) "Sesi berjalan" else "Sesi terakhir",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = if (connected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        if (connected) "Aktif" else "Berhenti",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatDuration(tunnel.startedAtMillis),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                StatColumn("Paket", tunnel.packets.toString(), Modifier.weight(1f))
                StatDivider()
                StatColumn("Data", formatBytes(tunnel.bytes), Modifier.weight(1f))
                StatDivider()
                StatColumn("Transport", if (connected) transport else "—", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        CheckRow(
            ok = connected,
            title = "TUN interface",
            subtitle = if (connected && tunAddress != null) "$tunAddress · full tunnel" else "Belum dibuat",
            value = if (connected) "Aktif" else "—",
        )
        CheckRow(
            ok = NativeCore.isAvailable(),
            title = "Core Rust (JNI)",
            subtitle = if (NativeCore.isAvailable()) "liblintas_core.so termuat" else "Library belum tersedia",
            value = if (NativeCore.isAvailable()) "Siap" else "Gagal",
        )
        CheckRow(
            ok = tunnel.packets > 0,
            title = "Lalu lintas",
            subtitle = "${tunnel.packets} keluar · ${tunnel.netRxPackets} masuk",
            value = formatBytes(tunnel.bytes),
        )
        CheckRow(
            ok = connected && tunnel.transport == TunnelTransport.MASQUE,
            title = "MASQUE",
            subtitle = if (connected && tunnel.transport == TunnelTransport.MASQUE) "Tunnel IP terenkripsi" else "Siaga",
            value = if (connected && tunnel.transport == TunnelTransport.MASQUE) "Aktif" else "—",
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Data dihitung sejak sesi dimulai.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, subtitle: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (ok) MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
}

@Composable
private fun SettingsScreen(
    warp: WarpState,
    tunnel: TunnelState,
    protocol: VpnProtocol,
    onProtocol: (VpnProtocol) -> Unit,
    onWarpRegister: () -> Unit,
    onWarpEnroll: () -> Unit,
    onWarpReset: () -> Unit,
    warpPlus: WarpPlusState,
    onActivateWarpPlus: (String) -> Unit,
    onClearWarpPlus: () -> Unit,
) {
    var showWarpPlusDialog by rememberSaveable { mutableStateOf(false) }
    var licenseInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(warpPlus.success) {
        if (warpPlus.success) {
            showWarpPlusDialog = false
            licenseInput = ""
            onClearWarpPlus()
        }
    }

    if (showWarpPlusDialog) {
        WarpPlusDialog(
            license = licenseInput,
            onLicenseChange = { licenseInput = it },
            busy = warpPlus.busy,
            error = warpPlus.error,
            onConfirm = { onActivateWarpPlus(licenseInput) },
            onDismiss = {
                if (!warpPlus.busy) {
                    showWarpPlusDialog = false
                    onClearWarpPlus()
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(title = "Setelan", subtitle = "Koneksi dan protokol")
        Spacer(Modifier.height(16.dp))
        Text(
            "WARP",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        WarpPanel(
            state = warp,
            onRegister = onWarpRegister,
            onEnroll = onWarpEnroll,
            onReset = onWarpReset,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "WARP+",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        WarpPlusPanel(
            identity = warp.identity,
            onOpen = {
                onClearWarpPlus()
                showWarpPlusDialog = true
            },
        )
        Spacer(Modifier.height(16.dp))
        SettingsGroup(title = "Protokol") {
            ProtocolRow(
                protocol = VpnProtocol.WIREGUARD,
                subtitle = "Koneksi klasik, kompatibel luas",
                selected = protocol == VpnProtocol.WIREGUARD,
                onClick = { onProtocol(VpnProtocol.WIREGUARD) },
            )
            SettingDivider()
            ProtocolRow(
                protocol = VpnProtocol.MASQUE,
                subtitle = if (warp.identity?.hasMasqueTransport == true) {
                    "Tunnel modern tanpa port WireGuard"
                } else {
                    "Disiapkan otomatis saat dipilih"
                },
                selected = protocol == VpnProtocol.MASQUE,
                onClick = { onProtocol(VpnProtocol.MASQUE) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (tunnel.status == TunnelStatus.CONNECTED) {
                    "Protokol baru berlaku untuk koneksi berikutnya."
                } else {
                    "Tombol utama di Beranda memakai protokol ini."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        SettingsGroup(title = "Aplikasi") {
            ValueSettingRow("Versi", "1.0.0", "Open source · GPL-3.0")
            SettingDivider()
            ValueSettingRow("Mesin", "Rust + Kotlin", "Android 16")
            SettingDivider()
            ValueSettingRow("Tema", "Ikut sistem", null)
            SettingDivider()
            ValueSettingRow("Bahasa", "Indonesia", null)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProtocolRow(
    protocol: VpnProtocol,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                protocol.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WarpPanel(
    state: WarpState,
    onRegister: () -> Unit,
    onEnroll: () -> Unit,
    onReset: () -> Unit,
) {
    val identity = state.identity
    val busy = state.status == WarpStatus.REGISTERING || state.status == WarpStatus.ENROLLING
    val ready = identity?.hasMasqueTransport == true
    val dotColor = when (state.status) {
        WarpStatus.READY, WarpStatus.REGISTERED -> MaterialTheme.colorScheme.secondary
        WarpStatus.REGISTERING, WarpStatus.ENROLLING -> MaterialTheme.colorScheme.primary
        WarpStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val title = when (state.status) {
        WarpStatus.UNREGISTERED -> "Belum terdaftar"
        WarpStatus.REGISTERING -> "Mendaftarkan WARP"
        WarpStatus.REGISTERED -> "WARP terdaftar"
        WarpStatus.ENROLLING -> "Menyiapkan MASQUE"
        WarpStatus.READY -> "WARP siap"
        WarpStatus.ERROR -> "Perlu perhatian"
    }
    val detail = state.error ?: when {
        identity == null -> "Daftarkan identitas WARP consumer untuk memulai."
        identity.wireGuardPeerPublicKey.isBlank() -> "Identitas lama tanpa peer key. Hapus lalu daftar ulang."
        ready -> "Satu identitas untuk WireGuard dan MASQUE."
        else -> "WireGuard siap; siapkan MASQUE untuk protokol kedua."
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("WARP consumer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (ready) {
                Text(
                    "Siap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = if (state.status == WarpStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        when {
            identity == null -> Button(
                onClick = onRegister,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(11.dp),
            ) {
                Text(if (busy) "Mendaftarkan…" else "Daftarkan WARP")
            }
            !identity.hasMasqueTransport -> {
                Button(
                    onClick = onEnroll,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Text(if (busy) "Menyiapkan…" else "Siapkan MASQUE")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onReset,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text("Reset identitas", style = MaterialTheme.typography.labelLarge)
                }
            }
            else -> OutlinedButton(
                onClick = onReset,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(11.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text("Reset identitas", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun WarpPlusPanel(identity: WarpIdentity?, onOpen: () -> Unit) {
    val active = identity?.warpPlus == true
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "WARP+",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (active) "Lisensi aktif" else "Belum aktif",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (active) {
                Text(
                    "Aktif",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            when {
                identity == null -> "Daftarkan WARP terlebih dahulu."
                active -> "Akun ini memakai rute WARP+ untuk koneksi berikutnya."
                else -> "Masukkan license key WARP+ untuk mengaktifkan Argo Smart Routing."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (identity != null && !active) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(11.dp),
            ) {
                Text("Aktifkan WARP+")
            }
        }
    }
}

@Composable
private fun WarpPlusDialog(
    license: String,
    onLicenseChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aktifkan WARP+") },
        text = {
            Column {
                Text(
                    "Masukkan license key WARP+ yang terhubung dengan akun Cloudflare Anda.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = license,
                    onValueChange = onLicenseChange,
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    label = { Text("License key") },
                    placeholder = { Text("XXXX-XXXX-XXXX") },
                    supportingText = {
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Key tidak disimpan dalam bentuk terbuka.")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && license.isNotBlank(),
            ) {
                Text(if (busy) "Mengaktifkan…" else "Aktifkan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Batal")
            }
        },
    )
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Column(content = content)
}

@Composable
private fun ValueSettingRow(title: String, value: String, hint: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.invoke()
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable


private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDuration(startedAtMillis: Long?): String {
    if (startedAtMillis == null) return "--:--"
    val total = ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
