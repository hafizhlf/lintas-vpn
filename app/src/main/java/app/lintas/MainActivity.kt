package app.lintas

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.lintas.ui.LintasApp
import app.lintas.ui.theme.LintasTheme
import app.lintas.vpn.VpnController
import app.lintas.vpn.VpnProtocol

class MainActivity : ComponentActivity() {
    private var pendingProtocol = VpnProtocol.WIREGUARD

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            VpnController.start(this, pendingProtocol)
        } else {
            VpnController.error("Izin VPN ditolak")
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        requestVpnConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            ),
        )
        setContent {
            LintasTheme {
                LintasApp(
                    onConnect = { protocol ->
                        pendingProtocol = protocol
                        requestNotificationThenVpn()
                    },
                    onDisconnect = { VpnController.stop(this) },
                    onProbe = { VpnController.probe(this) },
                )
            }
        }
    }

    private fun requestNotificationThenVpn() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnConsent()
        }
    }

    private fun requestVpnConsent() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermission.launch(intent)
        } else {
            VpnController.start(this, pendingProtocol)
        }
    }
}
