package app.lintas.warp

import org.junit.Assert.assertEquals
import org.junit.Test

class WarpEndpointCandidatesTest {
    @Test fun triesConfiguredHostPortsBeforeFallbackHost() {
        val identity = identity(host = "162.159.192.3", port = 2408)

        val candidates = warpEndpointCandidates(identity, limit = 6)

        assertEquals(
            listOf(
                WarpEndpoint("162.159.192.3", 2408),
                WarpEndpoint("162.159.192.3", 500),
                WarpEndpoint("162.159.192.3", 1701),
                WarpEndpoint("162.159.192.3", 4500),
                WarpEndpoint(WARP_FALLBACK_HOST, 2408),
                WarpEndpoint(WARP_FALLBACK_HOST, 500),
            ),
            candidates,
        )
        assertEquals(6, candidates.size)
    }

    @Test fun keepsConfiguredPortFirst() {
        val identity = identity(host = "162.159.192.3", port = 443)

        val candidates = warpEndpointCandidates(identity, limit = 4)

        assertEquals(WarpEndpoint("162.159.192.3", 443), candidates.first())
        assertEquals(
            listOf(443, 2408, 500, 1701),
            candidates.map { it.port },
        )
    }

    @Test fun deduplicatesWhenConfiguredHostIsAlreadyFallbackHost() {
        val identity = identity(host = WARP_FALLBACK_HOST, port = 2408)

        val candidates = warpEndpointCandidates(identity, limit = 6)

        assertEquals(
            listOf(
                WarpEndpoint(WARP_FALLBACK_HOST, 2408),
                WarpEndpoint(WARP_FALLBACK_HOST, 500),
                WarpEndpoint(WARP_FALLBACK_HOST, 1701),
                WarpEndpoint(WARP_FALLBACK_HOST, 4500),
            ),
            candidates,
        )
    }

    @Test fun fallsBackWhenConfiguredHostIsBlank() {
        val identity = identity(host = " ", port = 0)

        val candidates = warpEndpointCandidates(identity, limit = 3)

        assertEquals(
            listOf(
                WarpEndpoint(WARP_FALLBACK_HOST, 2408),
                WarpEndpoint(WARP_FALLBACK_HOST, 500),
                WarpEndpoint(WARP_FALLBACK_HOST, 1701),
            ),
            candidates,
        )
    }

    private fun identity(host: String, port: Int): WarpIdentity {
        return WarpIdentity(
            deviceId = "device",
            accessToken = "token",
            wireGuardPublicKey = "public",
            wireGuardPrivateKey = "private",
            wireGuardPeerPublicKey = "peer",
            clientId = "client",
            ipv4 = "172.16.0.2",
            ipv6 = "",
            endpointHost = host,
            endpointPort = port,
            accountType = "free",
            organization = "",
            registeredAt = 0L,
        )
    }
}
