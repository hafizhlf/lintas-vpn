package app.lintas.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.lintas.MainActivity
import app.lintas.R
import app.lintas.core.NativeCore
import app.lintas.warp.WarpApiClient
import app.lintas.warp.WarpController
import app.lintas.warp.WarpEndpoint
import app.lintas.warp.WarpIdentity
import app.lintas.warp.WarpIdentityStore
import app.lintas.warp.warpEndpointCandidates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.io.File

class LintasVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var udpPfd: ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null
    private var masqueCertFile: File? = null
    private var masqueKeyFile: File? = null
    private val nativeLock = Any()
    private var nativeHandle: Long = 0L
    private var statsJob: Job? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var cleanupJob: Job? = null
    @Volatile private var wireGuardAttempts: List<WarpEndpoint> = emptyList()
    @Volatile private var wireGuardAttemptIndex = 0
    @Volatile private var latestStartId = 0
    @Volatile private var tunnelRequested = false
    @Volatile private var currentProtocol = VpnProtocol.WIREGUARD
    @Volatile private var lastNetworkSnapshot: UnderlyingNetworkMonitor.Snapshot? = null
    @Volatile private var transportTransition = false
    @Volatile private var initialConnect = false
    private var networkMonitor: UnderlyingNetworkMonitor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        DiagnosticLog.log("APP", DiagnosticLog.appHeader(this))
        DiagnosticLog.log("SERVICE", "created")
        createNotificationChannel()
        networkMonitor = UnderlyingNetworkMonitor(this) { onUnderlyingNetworksChanged() }
            .also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        DiagnosticLog.log("CMD", "action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START -> startTunnel(VpnProtocol.WIREGUARD)
            ACTION_START_MASQUE -> startTunnel(VpnProtocol.MASQUE)
            ACTION_STOP -> stopTunnel(startId)
            ACTION_PROBE -> sendProbe()
            null -> if (!tunnelRequested && tun == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(protocol: VpnProtocol) {
        if (tunnelRequested) return
        cleanupJob?.cancel()
        cleanupJob = null
        tunnelRequested = true
        initialConnect = true
        currentProtocol = protocol
        clearWireGuardAttempts()
        lastNetworkSnapshot = networkMonitor?.snapshot()
        DiagnosticLog.log("SESSION", "start protocol=${protocol.wireValue}")
        VpnController.connecting()
        startForegroundNotification()

        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            connect(protocol)
        }
    }

    private suspend fun connect(protocol: VpnProtocol) {
        if (!NativeCore.isAvailable()) {
            failAndStop("Native core belum terbangun")
            return
        }
        if (!tunnelRequested) return

        val identity = runCatching { WarpIdentityStore.load(this@LintasVpnService) }.getOrNull()
        if (identity == null) {
            failAndStop("Daftarkan WARP terlebih dahulu")
            return
        }
        if (!tunnelRequested) return

        val prepared = runCatching {
            WarpApiClient.ensureProtocol(identity, protocol)
        }.getOrElse { error ->
            if (tunnelRequested) {
                failAndStop(error.message ?: "Gagal menyiapkan ${protocol.label}")
            }
            return
        }
        if (!tunnelRequested) return
        if (!prepared.canUseWireGuard()) {
            failAndStop("Identitas WARP tidak lengkap")
            return
        }
        val saved = runCatching { WarpIdentityStore.save(this@LintasVpnService, prepared) }
        if (saved.isFailure) {
            failAndStop("Gagal menyimpan identitas: ${saved.exceptionOrNull()?.message}")
            return
        }
        WarpController.refresh(this@LintasVpnService)
        if (!tunnelRequested) return

        when (protocol) {
            VpnProtocol.WIREGUARD -> startWireGuard(prepared)
            VpnProtocol.MASQUE -> {
                if (prepared.hasMasqueTransport) {
                    startMasqueTunnel(prepared)
                } else {
                    failAndStop("MASQUE belum siap")
                }
            }
        }
    }

    private fun openTunnelSocket(): DatagramSocket? {
        val socket = runCatching { DatagramSocket() }.getOrNull() ?: return null
        if (!protect(socket)) {
            DiagnosticLog.log("SOCKET", "protect failed")
            socket.close()
            return null
        }
        val network = networkMonitor?.selectedNetwork()
        if (network == null) {
            DiagnosticLog.log("SOCKET", "no selected network; using default")
        } else if (runCatching { network.bindSocket(socket) }.isFailure) {
            DiagnosticLog.log("SOCKET", "bind failed network=$network; using default")
            Log.w(TAG, "bindSocket failed; falling back to default network")
        } else {
            DiagnosticLog.log("SOCKET", "bound network=$network local=${socket.localAddress.hostAddress}:${socket.localPort}")
        }
        return socket
    }

    @Synchronized
    private fun startWireGuard(identity: WarpIdentity) {
        if (!tunnelRequested) return
        if (wireGuardAttempts.isEmpty()) {
            wireGuardAttempts = buildWireGuardAttempts(identity)
            wireGuardAttemptIndex = 0
        }
        if (wireGuardAttempts.isEmpty()) {
            failAndStop("Endpoint WARP tidak bisa di-resolve", suggestMasque = true)
            return
        }
        val attempt = wireGuardAttempts.getOrNull(wireGuardAttemptIndex)
        if (attempt == null) {
            failAndStop(transportFailureMessage(VpnProtocol.WIREGUARD), suggestMasque = true)
            return
        }
        val address = runCatching { InetAddress.getByName(attempt.host) }.getOrNull()
        if (address == null) {
            failAndStop("Endpoint WARP tidak bisa di-resolve", suggestMasque = true)
            return
        }
        DiagnosticLog.log(
            "TRANSPORT",
            "wireguard attempt=${wireGuardAttemptIndex + 1}/${wireGuardAttempts.size} " +
                "endpoint=${address.hostAddress}:${attempt.port}",
        )

        val socket = openTunnelSocket()
        if (socket == null) {
            failAndStop("Gagal menyiapkan socket WARP")
            return
        }

        val descriptor = tun ?: establishTun(identity.ipv4, identity.ipv6)
        if (descriptor == null) {
            socket.close()
            failAndStop("Alamat WARP tidak valid")
            return
        }
        tun = descriptor

        val udpDescriptor = runCatching {
            ParcelFileDescriptor.fromDatagramSocket(socket)
        }.getOrNull()
        if (udpDescriptor == null) {
            socket.close()
            failAndStop("Gagal mengambil socket WARP")
            return
        }

        val privateKey = decodeKey(identity.wireGuardPrivateKey)
        val peerKey = decodeKey(identity.wireGuardPeerPublicKey)
        val reserved = runCatching {
            Base64.decode(identity.clientId, Base64.NO_WRAP)
        }.getOrDefault(ByteArray(0)).let { if (it.size == 3) it else ByteArray(3) }

        if (privateKey == null || peerKey == null) {
            udpDescriptor.close()
            socket.close()
            failAndStop("Key WireGuard WARP tidak valid")
            return
        }

        udpPfd = udpDescriptor
        udpSocket = socket
        val handle = NativeCore.startWireGuard(
            descriptor.fd,
            udpDescriptor.fd,
            privateKey,
            peerKey,
            reserved,
            address.hostAddress.orEmpty(),
            attempt.port,
        )

        if (handle == 0L) {
            closeResources()
            failAndStop("Gagal memulai WireGuard")
            return
        }
        if (!tunnelRequested) {
            runCatching { NativeCore.stop(handle) }
            closeResources()
            return
        }

        setNativeHandle(handle)
        markTransportStarted(TunnelTransport.WIREGUARD)
    }

    private fun buildWireGuardAttempts(identity: WarpIdentity): List<WarpEndpoint> {
        val resolved = mutableListOf<WarpEndpoint>()
        for (candidate in warpEndpointCandidates(identity, limit = MAX_WIREGUARD_ATTEMPTS)) {
            val addresses = runCatching {
                InetAddress.getAllByName(candidate.host)
            }.getOrNull()
            if (addresses.isNullOrEmpty()) {
                DiagnosticLog.log("TRANSPORT", "endpoint resolve failed host=${candidate.host}")
                continue
            }
            val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
            resolved += candidate.copy(host = address.hostAddress.orEmpty())
        }
        return resolved.distinct().take(MAX_WIREGUARD_ATTEMPTS)
    }

    private fun clearWireGuardAttempts() {
        wireGuardAttempts = emptyList()
        wireGuardAttemptIndex = 0
    }

    private fun transportFailureMessage(protocol: VpnProtocol): String {
        return when (protocol) {
            VpnProtocol.WIREGUARD ->
                "WireGuard tidak mendapat balasan. Jaringan mungkin memblokir UDP; coba MASQUE."
            VpnProtocol.MASQUE ->
                "MASQUE tidak mendapat balasan. Periksa jaringan."
        }
    }

    @Synchronized
    private fun startMasqueTunnel(identity: WarpIdentity) {
        if (!tunnelRequested) return
        val certificatePem = identity.masqueCertificatePem
        val privateKeyPem = identity.masquePrivateKeyPem
        if (certificatePem.isNullOrBlank() || privateKeyPem.isNullOrBlank()) {
            failAndStop("Sertifikat MASQUE tidak tersedia")
            return
        }

        val addresses = runCatching {
            InetAddress.getAllByName(identity.masqueEndpointHost)
        }.getOrNull()
        if (addresses.isNullOrEmpty()) {
            failAndStop("Endpoint MASQUE tidak bisa di-resolve")
            return
        }
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        DiagnosticLog.log("TRANSPORT", "masque endpoint=${address.hostAddress}:${identity.effectiveMasquePort}")

        val socket = openTunnelSocket()
        if (socket == null) {
            failAndStop("Gagal menyiapkan socket MASQUE")
            return
        }

        val descriptor = tun ?: establishTun(identity.ipv4, identity.ipv6)
        if (descriptor == null) {
            socket.close()
            failAndStop("Alamat WARP tidak valid")
            return
        }
        tun = descriptor

        val udpDescriptor = runCatching {
            ParcelFileDescriptor.fromDatagramSocket(socket)
        }.getOrNull()
        if (udpDescriptor == null) {
            socket.close()
            failAndStop("Gagal mengambil socket MASQUE")
            return
        }

        val certFile = runCatching {
            File(cacheDir, "masque-cert.pem").apply { writeText(certificatePem) }
        }.getOrNull()
        val keyFile = runCatching {
            File(cacheDir, "masque-key.pem").apply { writeText(privateKeyPem) }
        }.getOrNull()
        if (certFile == null || keyFile == null) {
            udpDescriptor.close()
            socket.close()
            failAndStop("Gagal menulis sertifikat MASQUE")
            return
        }

        udpPfd = udpDescriptor
        udpSocket = socket
        masqueCertFile = certFile
        masqueKeyFile = keyFile

        val handle = NativeCore.startMasque(
            descriptor.fd,
            udpDescriptor.fd,
            certFile.absolutePath,
            keyFile.absolutePath,
            address.hostAddress.orEmpty(),
            identity.effectiveMasquePort,
            socket.localAddress.hostAddress.orEmpty(),
            socket.localPort,
            MASQUE_SNI,
            identity.masqueEndpointPublicKeyPem.orEmpty(),
        )

        if (handle == 0L) {
            closeResources()
            failAndStop("Gagal memulai MASQUE")
            return
        }
        if (!tunnelRequested) {
            runCatching { NativeCore.stop(handle) }
            closeResources()
            return
        }

        setNativeHandle(handle)
        markTransportStarted(TunnelTransport.MASQUE)
    }

    private fun markTransportStarted(transport: TunnelTransport) {
        if (!tunnelRequested) {
            closeResources()
            return
        }
        val snapshot = networkMonitor?.snapshot()
        initialConnect = false
        val previous = lastNetworkSnapshot
        val networkChanged = previous?.hasNetwork == true && previous != snapshot
        lastNetworkSnapshot = snapshot
        DiagnosticLog.log("TRANSPORT", "started transport=$transport network=${snapshotLabel(snapshot)}")
        startStatsLoop(watchHandshake = true, transport = transport)
        scope.launch {
            delay(1200L)
            sendWarpProbe()
        }
        if (networkChanged) {
            Log.i(TAG, "network changed during start ${snapshotLabel(previous)} -> ${snapshotLabel(snapshot)}")
            scheduleReconnect("network-during-start", resetWireGuardAttempts = true)
        }
    }

    private fun onUnderlyingNetworksChanged() {
        if (!tunnelRequested || transportTransition) return
        val snapshot = networkMonitor?.snapshot() ?: return
        if (initialConnect) {
            lastNetworkSnapshot = snapshot
            return
        }
        if (snapshot == lastNetworkSnapshot) return
        Log.i(TAG, "network changed ${snapshotLabel(lastNetworkSnapshot)} -> ${snapshotLabel(snapshot)}")
        DiagnosticLog.log("NET", "changed ${snapshotLabel(lastNetworkSnapshot)} -> ${snapshotLabel(snapshot)}")
        scheduleReconnect("network-changed", resetWireGuardAttempts = true)
    }

    private fun networkChangedSinceStart(): Boolean {
        val previous = lastNetworkSnapshot ?: return false
        if (!previous.hasNetwork) return false
        return networkMonitor?.snapshot()?.let { it != previous } ?: false
    }

    private fun scheduleReconnect(
        reason: String,
        immediate: Boolean = false,
        nextWireGuardAttempt: Boolean = false,
        resetWireGuardAttempts: Boolean = false,
    ) {
        if (!tunnelRequested) return
        Log.i(
            TAG,
            "schedule reconnect reason=$reason immediate=$immediate " +
                "nextAttempt=$nextWireGuardAttempt resetAttempts=$resetWireGuardAttempts",
        )
        DiagnosticLog.log(
            "RECONNECT",
            "reason=$reason immediate=$immediate nextAttempt=$nextWireGuardAttempt " +
                "resetAttempts=$resetWireGuardAttempts network=${snapshotLabel(lastNetworkSnapshot)}",
        )
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (!immediate) delay(RECONNECT_DEBOUNCE_MS)
            if (!tunnelRequested) return@launch

            if (nextWireGuardAttempt) {
                val next = wireGuardAttemptIndex + 1
                if (currentProtocol != VpnProtocol.WIREGUARD || next !in wireGuardAttempts.indices) {
                    failAndStop(
                        transportFailureMessage(currentProtocol),
                        suggestMasque = currentProtocol == VpnProtocol.WIREGUARD,
                    )
                    return@launch
                }
                wireGuardAttemptIndex = next
                DiagnosticLog.log(
                    "TRANSPORT",
                    "wireguard retry attempt=${next + 1}/${wireGuardAttempts.size}",
                )
                delay(HANDSHAKE_RETRY_DELAY_MS)
            }
            if (resetWireGuardAttempts) clearWireGuardAttempts()
            if (!tunnelRequested) return@launch

            val monitor = networkMonitor ?: return@launch
            val snapshot = monitor.snapshot()
            transportTransition = true
            try {
                closeTransport()
                VpnController.connecting()
                lastNetworkSnapshot = snapshot
                if (!snapshot.hasNetwork) return@launch

                val identity = runCatching {
                    WarpIdentityStore.load(this@LintasVpnService)
                }.getOrNull()
                if (identity == null) {
                    failAndStop("Daftarkan WARP terlebih dahulu")
                    return@launch
                }
                if (!tunnelRequested) return@launch

                when (currentProtocol) {
                    VpnProtocol.WIREGUARD -> startWireGuard(identity)
                    VpnProtocol.MASQUE -> {
                        if (identity.hasMasqueTransport) {
                            startMasqueTunnel(identity)
                        } else {
                            failAndStop("MASQUE belum siap")
                        }
                    }
                }
            } finally {
                transportTransition = false
            }

            val current = networkMonitor?.snapshot()
            if (tunnelRequested && current != null && current != lastNetworkSnapshot) {
                Log.i(TAG, "network changed after start ${snapshotLabel(lastNetworkSnapshot)} -> ${snapshotLabel(current)}")
                scheduleReconnect("network-after-start", resetWireGuardAttempts = true)
            }
        }
    }

    private fun establishTun(rawIpv4: String, rawIpv6: String): ParcelFileDescriptor? {
        val ipv4 = rawIpv4.substringBefore('/').trim()
        if (ipv4.isBlank()) return null
        val ipv6 = rawIpv6.substringBefore('/').trim()

        val builder = Builder()
            .setSession("Lintas WARP")
            .setMtu(1280)
            .addAddress(ipv4, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setBlocking(true)
            // Android marks VPNs from Q+ targets as metered by default, which
            // makes apps defer large transfers even on unmetered Wi-Fi. Match
            // WireGuard Android and report the tunnel as unmetered.
            .setMetered(false)
        if (ipv6.isNotBlank()) {
            builder.addAddress(ipv6, 128)
            builder.addRoute("::", 0)
            builder.addDnsServer("2606:4700:4700::1111")
        }
        applySplitTunnel(builder)
        return builder.establish()
    }

    private fun applySplitTunnel(builder: Builder) {
        val excluded = VpnPreferences.loadExcludedApps(this)
        if (excluded.isEmpty()) return

        val applied = applySplitTunnelPackages(
            packages = excluded,
            ownPackage = packageName,
            add = { pkg ->
                requireVisiblePackage(pkg)
                builder.addDisallowedApplication(pkg)
            },
            onSkipped = { pkg, error ->
                DiagnosticLog.log(
                    "SPLIT",
                    "skip package=$pkg error=${error.javaClass.simpleName}",
                )
            },
        )
        DiagnosticLog.log("SPLIT", "excluded=$applied requested=${excluded.size}")
    }

    private fun requireVisiblePackage(packageName: String) {
        // Android 16 accepts a missing package in addDisallowedApplication
        // without throwing. Resolve first so stale selections are skipped and
        // logged instead of silently becoming a no-op.
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
    }

    private fun startStatsLoop(watchHandshake: Boolean, transport: TunnelTransport) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var ticks = 0
            var handshakeDone = !watchHandshake
            if (handshakeDone) {
                VpnController.connected(transport)
            }
            while (isActive) {
                val sample = sampleNative() ?: break
                if (!sample.running) {
                    DiagnosticLog.log(
                        "TRANSPORT",
                        "native stopped error=${sample.error.orEmpty().ifBlank { "unknown" }}",
                    )
                    if (networkChangedSinceStart()) {
                        scheduleReconnect(
                            "native-stopped-network",
                            immediate = true,
                            resetWireGuardAttempts = true,
                        )
                    } else if (watchHandshake && !handshakeDone) {
                        scheduleReconnect(
                            "native-stopped-handshake",
                            immediate = true,
                            nextWireGuardAttempt = true,
                        )
                    } else {
                        scheduleReconnect(
                            "native-stopped",
                            immediate = true,
                            resetWireGuardAttempts = true,
                        )
                    }
                    break
                }
                val stats = sample.stats
                if (stats != null && stats.size >= 2) {
                    val netRx = stats.getOrElse(2) { 0L }
                    val rxBytes = stats.getOrElse(4) { 0L }
                    VpnController.updateStats(stats[0], stats[1], rxBytes, netRx)
                    if (watchHandshake && !handshakeDone && netRx > 0L) {
                        handshakeDone = true
                        DiagnosticLog.log("TRANSPORT", "handshake complete transport=$transport")
                        VpnController.connected(transport)
                    }
                    if (watchHandshake && !handshakeDone && ticks >= HANDSHAKE_TIMEOUT_TICKS) {
                        val attempt = if (transport == TunnelTransport.WIREGUARD) {
                            " attempt=${wireGuardAttemptIndex + 1}/${wireGuardAttempts.size}"
                        } else {
                            ""
                        }
                        DiagnosticLog.log(
                            "TRANSPORT",
                            "handshake timeout transport=$transport$attempt",
                        )
                        if (networkChangedSinceStart()) {
                            scheduleReconnect(
                                "handshake-timeout-network",
                                immediate = true,
                                resetWireGuardAttempts = true,
                            )
                        } else {
                            scheduleReconnect(
                                "handshake-timeout",
                                immediate = true,
                                nextWireGuardAttempt = true,
                            )
                        }
                        break
                    }
                    if (DiagnosticLog.isActive && ticks % 30 == 0) {
                        DiagnosticLog.log(
                            "STATS",
                            "transport=$transport packets=${stats[0]} tx=${stats.getOrElse(1) { 0L }} " +
                                "rx=${stats.getOrElse(4) { 0L }} netRx=${stats.getOrElse(2) { 0L }}",
                        )
                    }
                }
                ticks += 1
                delay(1000L)
            }
        }
    }

    private fun sampleNative(): NativeSample? = synchronized(nativeLock) {
        val handle = nativeHandle
        if (handle == 0L) {
            null
        } else {
            val running = runCatching { NativeCore.isRunning(handle) }.getOrDefault(false)
            NativeSample(
                running = running,
                stats = runCatching { NativeCore.stats(handle) }.getOrNull(),
                error = if (running) null else runCatching { NativeCore.lastError(handle) }.getOrNull(),
            )
        }
    }

    private fun setNativeHandle(handle: Long) {
        synchronized(nativeLock) {
            nativeHandle = handle
        }
    }

    private fun sendProbe() {
        scope.launch {
            val transport = VpnController.state.value.transport
            if (tun == null || (transport != TunnelTransport.WIREGUARD && transport != TunnelTransport.MASQUE)) {
                VpnController.probeFailure("Tunnel belum siap")
                return@launch
            }
            val started = System.nanoTime()
            runCatching { probeDns() }
                .onSuccess {
                    val latency = (System.nanoTime() - started) / 1_000_000L
                    DiagnosticLog.log("PROBE", "success latencyMs=$latency")
                    VpnController.probeSuccess(latency)
                }
                .onFailure { error ->
                    Log.w(TAG, "probe failed: ${error.message}")
                    val message = if (error is java.net.SocketTimeoutException) {
                        "Tidak ada balasan dari WARP (timeout)"
                    } else {
                        error.message ?: "Tidak ada balasan dari 1.1.1.1"
                    }
                    DiagnosticLog.log("PROBE", "failure message=$message")
                    VpnController.probeFailure(message)
                }
        }
    }

    private fun probeDns() {
        val query = dnsQuery()
        DatagramSocket().use { socket ->
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.send(
                DatagramPacket(
                    query,
                    query.size,
                    InetAddress.getByName("1.1.1.1"),
                    53,
                ),
            )
            val buffer = ByteArray(MAX_DNS_RESPONSE)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            if (response.length < 12 || buffer[0] != query[0] || buffer[1] != query[1]) {
                error("Balasan DNS tidak valid")
            }
        }
    }

    private fun sendWarpProbe() {
        if (tun == null) return
        val query = dnsQuery()
        scope.launch {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.send(
                        DatagramPacket(
                            query,
                            query.size,
                            InetAddress.getByName("1.1.1.1"),
                            53,
                        ),
                    )
                }
            }
        }
    }

    private fun dnsQuery(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x04, 0x77, 0x61, 0x72,
        0x70, 0x00, 0x00, 0x01, 0x00, 0x01,
    )

    private fun decodeKey(value: String): ByteArray? {
        return runCatching { Base64.decode(value, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
    }

    @Synchronized
    private fun closeTransport() {
        statsJob?.cancel()
        statsJob = null
        val handle = synchronized(nativeLock) {
            val current = nativeHandle
            nativeHandle = 0L
            current
        }
        if (handle != 0L) {
            runCatching { NativeCore.stop(handle) }
        }
        runCatching { udpPfd?.close() }
        udpPfd = null
        runCatching { udpSocket?.close() }
        udpSocket = null
    }

    @Synchronized
    private fun closeResources() {
        closeTransport()
        runCatching { tun?.close() }
        tun = null
        runCatching { masqueCertFile?.delete() }
        masqueCertFile = null
        runCatching { masqueKeyFile?.delete() }
        masqueKeyFile = null
    }

    private fun stopTunnel(startId: Int = latestStartId) {
        DiagnosticLog.log("SESSION", "stop")
        cleanupJob?.cancel()
        cleanupJob = null
        tunnelRequested = false
        initialConnect = false
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        closeResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        VpnController.idle()
        stopSelf(startId)
    }

    private fun failAndStop(message: String, suggestMasque: Boolean = false) {
        DiagnosticLog.log(
            "SESSION",
            "failed message=$message suggestMasque=$suggestMasque",
        )
        tunnelRequested = false
        initialConnect = false
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        closeResources()
        VpnController.error(message, suggestMasque)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        // Keep this service instance around briefly so an immediate retry reuses
        // a clean instance instead of racing the previous stop/destroy.
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            delay(FAILED_IDLE_STOP_MS)
            if (!tunnelRequested) {
                DiagnosticLog.log("SERVICE", "idle stop after failure")
                stopSelf(latestStartId)
            }
        }
    }

    private fun startForegroundNotification() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LintasVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lintas)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.vpn_notification_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.vpn_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        DiagnosticLog.log("SERVICE", "destroy")
        tunnelRequested = false
        initialConnect = false
        connectJob?.cancel()
        reconnectJob?.cancel()
        cleanupJob?.cancel()
        networkMonitor?.stop()
        networkMonitor = null
        closeResources()
        scope.cancel()
        super.onDestroy()
    }

    private fun snapshotLabel(snapshot: UnderlyingNetworkMonitor.Snapshot?): String {
        return snapshot?.path?.joinToString(prefix = "{", postfix = "}") { it.toString() } ?: "null"
    }

    private data class NativeSample(
        val running: Boolean,
        val stats: LongArray?,
        val error: String?,
    )

    companion object {
        const val ACTION_START = "app.lintas.action.START"
        const val ACTION_START_MASQUE = "app.lintas.action.START_MASQUE"
        const val ACTION_STOP = "app.lintas.action.STOP"
        const val ACTION_PROBE = "app.lintas.action.PROBE"
        private const val TAG = "LintasVpn"
        private const val MASQUE_SNI = "consumer-masque.cloudflareclient.com"
        private const val CHANNEL_ID = "lintas_vpn"
        private const val NOTIFICATION_ID = 1
        private const val RECONNECT_DEBOUNCE_MS = 1200L
        private const val HANDSHAKE_TIMEOUT_TICKS = 5
        private const val HANDSHAKE_RETRY_DELAY_MS = 300L
        private const val MAX_WIREGUARD_ATTEMPTS = 6
        private const val FAILED_IDLE_STOP_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 3500
        private const val MAX_DNS_RESPONSE = 1500
    }
}

private fun WarpIdentity.canUseWireGuard(): Boolean {
    return ipv4.isNotBlank() &&
        endpointHost.isNotBlank() &&
        wireGuardPrivateKey.isNotBlank() &&
        wireGuardPeerPublicKey.isNotBlank()
}
