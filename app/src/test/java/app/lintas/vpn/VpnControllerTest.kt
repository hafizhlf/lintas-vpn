package app.lintas.vpn

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnControllerTest {
    @After fun resetState() {
        VpnController.idle()
    }

    @Test fun errorCarriesMasqueSuggestion() {
        VpnController.error("WireGuard tidak mendapat balasan", suggestMasque = true)

        val state = VpnController.state.value
        assertTrue(state.status == TunnelStatus.ERROR)
        assertTrue(state.suggestMasque)
    }

    @Test fun reconnectClearsMasqueSuggestion() {
        VpnController.error("WireGuard tidak mendapat balasan", suggestMasque = true)
        VpnController.connecting()

        assertFalse(VpnController.state.value.suggestMasque)
    }

    @Test fun connectedClearsMasqueSuggestion() {
        VpnController.error("WireGuard tidak mendapat balasan", suggestMasque = true)
        VpnController.connected(TunnelTransport.WIREGUARD)

        assertFalse(VpnController.state.value.suggestMasque)
    }

    @Test fun normalErrorDoesNotSuggestMasque() {
        VpnController.error("Daftarkan WARP terlebih dahulu")

        assertFalse(VpnController.state.value.suggestMasque)
    }
}
