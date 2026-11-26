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

/**
 * Candidates for a reconnect, preferring the endpoint/port that last completed
 * a handshake so a network handover does not start again from a dead port. The
 * preferred endpoint is tried once more at the end because the first attempt
 * can race a network that is still settling.
 */
fun warpWireGuardCandidates(
    identity: WarpIdentity,
    preferred: WarpEndpoint?,
    limit: Int = 7,
): List<WarpEndpoint> {
    val base = warpEndpointCandidates(identity, limit = Int.MAX_VALUE)
    if (preferred == null) return base.take(limit)

    val knownHost = preferred.host == identity.endpointHost.trim() ||
        preferred.host == WARP_FALLBACK_HOST
    if (!knownHost) return base.take(limit)
    if (limit < 3) return (listOf(preferred) + base.filter { it != preferred }).take(limit)

    val rest = base.filter { it != preferred }
    return listOf(preferred) + rest.take(limit - 2) + preferred
}
