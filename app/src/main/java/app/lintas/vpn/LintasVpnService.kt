package app.lintas.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.lintas.MainActivity
import app.lintas.R
import app.lintas.core.NativeCore
import app.lintas.warp.WarpApiClient
import app.lintas.warp.WarpController
import app.lintas.warp.WarpIdentity
import app.lintas.warp.WarpIdentityStore
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
    private var nativeHandle: Long = 0L
    private var statsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTunnel(VpnProtocol.WIREGUARD)
            ACTION_START_MASQUE -> startTunnel(VpnProtocol.MASQUE)
            ACTION_STOP -> stopTunnel()
            ACTION_PROBE -> sendProbe()
            null -> if (tun == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(protocol: VpnProtocol) {
        if (tun != null) return
        VpnController.connecting()
        startForegroundNotification()

        scope.launch(Dispatchers.IO) {
            if (!NativeCore.isAvailable()) {
                failAndStop("Native core belum terbangun")
                return@launch
            }

            val identity = runCatching { WarpIdentityStore.load(this@LintasVpnService) }.getOrNull()
            if (identity == null) {
                failAndStop("Daftarkan WARP terlebih dahulu")
                return@launch
            }

            val prepared = runCatching {
                WarpApiClient.ensureProtocol(identity, protocol)
            }.getOrElse { error ->
                failAndStop(error.message ?: "Gagal menyiapkan ${protocol.label}")
                return@launch
            }
            if (!prepared.canUseWireGuard()) {
                failAndStop("Identitas WARP tidak lengkap")
                return@launch
            }
            runCatching { WarpIdentityStore.save(this@LintasVpnService, prepared) }
                .onFailure { error ->
                    failAndStop("Gagal menyimpan identitas: ${error.message}")
                    return@launch
                }
            WarpController.refresh(this@LintasVpnService)

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
    }

    private fun startWireGuard(identity: WarpIdentity) {
        val addresses = runCatching {
            InetAddress.getAllByName(identity.endpointHost)
        }.getOrNull()
        if (addresses.isNullOrEmpty()) {
            failAndStop("Endpoint WARP tidak bisa di-resolve")
            return
        }
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()

        val socket = runCatching { DatagramSocket() }.getOrNull()
        if (socket == null || !protect(socket)) {
            socket?.close()
            failAndStop("Gagal menyiapkan socket WARP")
            return
        }

        val descriptor = establishTun(identity.ipv4, identity.ipv6)
        if (descriptor == null) {
            socket.close()
            failAndStop("Alamat WARP tidak valid")
            return
        }

        val udpDescriptor = runCatching {
            ParcelFileDescriptor.fromDatagramSocket(socket)
        }.getOrNull()
        if (udpDescriptor == null) {
            descriptor.close()
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
            descriptor.close()
            udpDescriptor.close()
            socket.close()
            failAndStop("Key WireGuard WARP tidak valid")
            return
        }

        tun = descriptor
        udpPfd = udpDescriptor
        udpSocket = socket
        nativeHandle = NativeCore.startWireGuard(
            descriptor.fd,
            udpDescriptor.fd,
            privateKey,
            peerKey,
            reserved,
            address.hostAddress.orEmpty(),
            identity.effectiveEndpointPort,
        )

        if (nativeHandle == 0L) {
            closeResources()
            failAndStop("Gagal memulai WireGuard")
            return
        }

        startStatsLoop(watchHandshake = true, transport = TunnelTransport.WIREGUARD)
        scope.launch {
            delay(1200L)
            sendWarpProbe()
        }
    }

    private fun startMasqueTunnel(identity: WarpIdentity) {
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

        val socket = runCatching { DatagramSocket() }.getOrNull()
        if (socket == null || !protect(socket)) {
            socket?.close()
            failAndStop("Gagal menyiapkan socket MASQUE")
            return
        }

        val descriptor = establishTun(identity.ipv4, identity.ipv6)
        if (descriptor == null) {
            socket.close()
            failAndStop("Alamat WARP tidak valid")
            return
        }

        val udpDescriptor = runCatching {
            ParcelFileDescriptor.fromDatagramSocket(socket)
        }.getOrNull()
        if (udpDescriptor == null) {
            descriptor.close()
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
            descriptor.close()
            udpDescriptor.close()
            socket.close()
            failAndStop("Gagal menulis sertifikat MASQUE")
            return
        }

        tun = descriptor
        udpPfd = udpDescriptor
        udpSocket = socket
        masqueCertFile = certFile
        masqueKeyFile = keyFile

        nativeHandle = NativeCore.startMasque(
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

        if (nativeHandle == 0L) {
            closeResources()
            failAndStop("Gagal memulai MASQUE")
            return
        }

        startStatsLoop(watchHandshake = true, transport = TunnelTransport.MASQUE)
        scope.launch {
            delay(1200L)
            sendWarpProbe()
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
        if (ipv6.isNotBlank()) {
            builder.addAddress(ipv6, 128)
            builder.addRoute("::", 0)
            builder.addDnsServer("2606:4700:4700::1111")
        }
        return builder.establish()
    }

    private fun startStatsLoop(watchHandshake: Boolean, transport: TunnelTransport) {
        statsJob = scope.launch {
            var ticks = 0
            var handshakeDone = !watchHandshake
            if (handshakeDone) {
                VpnController.connected(transport)
            }
            while (isActive) {
                val stats = runCatching { NativeCore.stats(nativeHandle) }.getOrNull()
                if (stats != null && stats.size >= 2) {
                    val netRx = stats.getOrElse(2) { 0L }
                    VpnController.updateStats(stats[0], stats[1], netRx)
                    if (watchHandshake && !handshakeDone && netRx > 0L) {
                        handshakeDone = true
                        VpnController.connected(transport)
                    }
                    if (watchHandshake && !handshakeDone && ticks >= 20) {
                        scope.launch {
                            failAndStop("Handshake WARP gagal; periksa jaringan atau daftar ulang.")
                        }
                        break
                    }
                }
                ticks += 1
                delay(1000L)
            }
        }
    }

    private fun sendProbe() {
        when (VpnController.state.value.transport) {
            TunnelTransport.WIREGUARD, TunnelTransport.MASQUE -> sendWarpProbe()
            TunnelTransport.SPIKE -> sendUdpProbe("10.111.0.1", 9, "lintas-phase0".toByteArray())
            null -> Unit
        }
    }

    private fun sendWarpProbe() {
        if (tun == null) return
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x04, 0x77, 0x61, 0x72,
            0x70, 0x00, 0x00, 0x01, 0x00, 0x01,
        )
        sendUdpProbe("1.1.1.1", 53, query)
    }

    private fun sendUdpProbe(host: String, port: Int, payload: ByteArray) {
        scope.launch {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.send(
                        DatagramPacket(
                            payload,
                            payload.size,
                            InetAddress.getByName(host),
                            port,
                        ),
                    )
                }
            }
        }
    }

    private fun decodeKey(value: String): ByteArray? {
        return runCatching { Base64.decode(value, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
    }

    private fun closeResources() {
        if (nativeHandle != 0L) {
            runCatching { NativeCore.stop(nativeHandle) }
            nativeHandle = 0L
        }
        statsJob?.cancel()
        statsJob = null
        runCatching { tun?.close() }
        tun = null
        runCatching { udpPfd?.close() }
        udpPfd = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { masqueCertFile?.delete() }
        masqueCertFile = null
        runCatching { masqueKeyFile?.delete() }
        masqueKeyFile = null
    }

    private fun stopTunnel() {
        closeResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        VpnController.idle()
        stopSelf()
    }

    private fun failAndStop(message: String) {
        closeResources()
        VpnController.error(message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        closeResources()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.lintas.action.START"
        const val ACTION_START_MASQUE = "app.lintas.action.START_MASQUE"
        const val ACTION_STOP = "app.lintas.action.STOP"
        const val ACTION_PROBE = "app.lintas.action.PROBE"
        private const val MASQUE_SNI = "consumer-masque.cloudflareclient.com"
        private const val CHANNEL_ID = "lintas_vpn"
        private const val NOTIFICATION_ID = 1
    }
}

private fun WarpIdentity.canUseWireGuard(): Boolean {
    return ipv4.isNotBlank() &&
        endpointHost.isNotBlank() &&
        wireGuardPrivateKey.isNotBlank() &&
        wireGuardPeerPublicKey.isNotBlank()
}
