package app.lintas.warp

data class WarpEndpoint(
    val host: String,
    val port: Int,
)

const val WARP_FALLBACK_HOST = "engage.cloudflareclient.com"

val WARP_FALLBACK_PORTS = listOf(2408, 500, 1701, 4500)

/**
 * WireGuard WARP candidates, ordered from the endpoint returned by registration
 * to Cloudflare's fallback host and ports.
 */
fun warpEndpointCandidates(identity: WarpIdentity, limit: Int = 6): List<WarpEndpoint> {
    val hosts = listOf(identity.endpointHost, WARP_FALLBACK_HOST)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    val ports = (listOf(identity.effectiveEndpointPort) + WARP_FALLBACK_PORTS).distinct()

    return hosts
        .flatMap { host -> ports.map { port -> WarpEndpoint(host, port) } }
        .take(limit)
}
