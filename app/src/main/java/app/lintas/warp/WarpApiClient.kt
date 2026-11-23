package app.lintas.warp

import android.os.Build
import app.lintas.vpn.VpnProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object WarpApiClient {
    private const val BASE_URL = "https://api.cloudflareclient.com"
    private const val API_VERSION = "v0a4471"
    private const val USER_AGENT = "WARP for Android"
    private const val CLIENT_VERSION = "a-6.35-4471"
    private val jsonMediaType = "application/json; charset=UTF-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun register(): WarpIdentity = withContext(Dispatchers.IO) {
        val keys = WarpKeys.generateWireGuardKeys()
        val body = JSONObject()
            .put("key", keys.publicKeyBase64)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", timestamp())
            .put("model", modelName())
            .put("serial_number", randomSerial())
            .put("os_version", Build.VERSION.RELEASE)
            .put("key_type", "curve25519")
            .put("tunnel_type", "wireguard")
            .put("locale", Locale.getDefault().toString().ifBlank { "en_US" })

        val registration = request("POST", "/$API_VERSION/reg", body, null)
        val deviceId = registration.optString("id")
        val accessToken = registration.optString("token")
        if (deviceId.isBlank() || accessToken.isBlank()) {
            throw IOException("WARP registration tidak mengembalikan id/token")
        }

        val enabled = request(
            "PATCH",
            "/$API_VERSION/reg/$deviceId",
            JSONObject().put("warp_enabled", true),
            accessToken,
        )
        val config = enabled.optJSONObject("config")
            ?: registration.optJSONObject("config")
            ?: throw IOException("WARP registration tidak mengembalikan config")

        val parsed = parseConfig(config)
        val account = registration.optJSONObject("account")
        val accountType = account?.optString("account_type").orEmpty()
        WarpIdentity(
            deviceId = deviceId,
            accessToken = accessToken,
            wireGuardPublicKey = keys.publicKeyBase64,
            wireGuardPrivateKey = keys.privateKeyBase64,
            wireGuardPeerPublicKey = parsed.peerPublicKey,
            clientId = parsed.clientId,
            ipv4 = parsed.ipv4,
            ipv6 = parsed.ipv6,
            endpointHost = parsed.endpointHost,
            endpointPort = if (parsed.endpointPort > 0) parsed.endpointPort else 2408,
            accountType = accountType,
            organization = account?.optString("organization").orEmpty(),
            registeredAt = System.currentTimeMillis(),
            warpPlus = isWarpPlusAccountType(accountType),
            plusExpiresAt = account?.optString("plus_expires_at")
                ?.takeIf { it.isNotBlank() && it != "null" },
            activeTunnelType = "wireguard",
        )
    }

    suspend fun enrollMasque(identity: WarpIdentity): WarpIdentity = withContext(Dispatchers.IO) {
        val credentials = WarpKeys.generateMasqueCredentials()
        val body = JSONObject()
            .put("key", credentials.spkiBase64)
            .put("key_type", "secp256r1")
            .put("tunnel_type", "masque")

        val response = request(
            "PATCH",
            "/$API_VERSION/reg/${identity.deviceId}",
            body,
            identity.accessToken,
        )
        val parsed = response.optJSONObject("config")?.let(::parseConfig) ?: ParsedConfig()

        identity.copy(
            clientId = parsed.clientId.ifBlank { identity.clientId },
            ipv4 = parsed.ipv4.ifBlank { identity.ipv4 },
            ipv6 = parsed.ipv6.ifBlank { identity.ipv6 },
            masqueCertificatePem = credentials.certificatePem,
            masquePrivateKeyPem = credentials.privateKeyPem,
            masqueEnrolledAt = System.currentTimeMillis(),
            masqueEndpointHost = parsed.endpointHost.ifBlank { identity.endpointHost },
            masqueEndpointPort = if (parsed.endpointPort > 0) parsed.endpointPort else 443,
            masqueEndpointPublicKeyPem = parsed.peerPublicKey.takeIf { it.isNotBlank() },
            activeTunnelType = "masque",
        )
    }

    suspend fun ensureProtocol(identity: WarpIdentity, protocol: VpnProtocol): WarpIdentity =
        withContext(Dispatchers.IO) {
            when (protocol) {
                VpnProtocol.WIREGUARD -> {
                    if (
                        identity.activeTunnelType == "wireguard" &&
                        identity.wireGuardPeerPublicKey.isNotBlank() &&
                        identity.wireGuardPrivateKey.isNotBlank()
                    ) {
                        identity
                    } else {
                        configureWireGuard(identity)
                    }
                }
                VpnProtocol.MASQUE -> {
                    if (identity.activeTunnelType == "masque" && identity.hasMasqueTransport) {
                        identity
                    } else {
                        enrollMasque(identity)
                    }
                }
            }
        }

    suspend fun applyLicense(identity: WarpIdentity, licenseKey: String): WarpIdentity =
        withContext(Dispatchers.IO) {
            val normalized = licenseKey.trim()
            if (normalized.isBlank()) {
                throw IOException("License key kosong")
            }
            request(
                "PUT",
                "/$API_VERSION/reg/${identity.deviceId}/account",
                JSONObject().put("license", normalized),
                identity.accessToken,
            )
            val updated = fetchAccount(identity)
            if (!updated.warpPlus) {
                throw IOException("License WARP+ tidak diterima")
            }
            updated.copy(licenseKey = normalized)
        }

    suspend fun fetchAccount(identity: WarpIdentity): WarpIdentity =
        withContext(Dispatchers.IO) {
            val response = get(
                "/$API_VERSION/reg/${identity.deviceId}/account",
                identity.accessToken,
            )
            applyAccount(identity, response)
        }

    private fun applyAccount(identity: WarpIdentity, response: JSONObject): WarpIdentity {
        val account = response.optJSONObject("account") ?: response
        val accountType = account.optString("account_type").ifBlank { identity.accountType }
        val warpPlus = isWarpPlusAccountType(accountType)
        val expiresAt = account.optString("plus_expires_at")
            .takeIf { it.isNotBlank() && it != "null" }
        return identity.copy(
            accountType = accountType,
            warpPlus = warpPlus,
            plusExpiresAt = if (warpPlus) expiresAt ?: identity.plusExpiresAt else null,
        )
    }

    private fun isWarpPlusAccountType(accountType: String): Boolean {
        return when (accountType.lowercase(Locale.US)) {
            "unlimited", "warp_plus" -> true
            else -> false
        }
    }

    private suspend fun configureWireGuard(identity: WarpIdentity): WarpIdentity {
        if (identity.wireGuardPublicKey.isBlank() || identity.wireGuardPrivateKey.isBlank()) {
            throw IOException("Key WireGuard tidak tersedia")
        }
        val body = JSONObject()
            .put("key", identity.wireGuardPublicKey)
            .put("key_type", "curve25519")
            .put("tunnel_type", "wireguard")
            .put("warp_enabled", true)
        val response = request(
            "PATCH",
            "/$API_VERSION/reg/${identity.deviceId}",
            body,
            identity.accessToken,
        )
        val parsed = response.optJSONObject("config")?.let(::parseConfig) ?: ParsedConfig()
        return identity.copy(
            clientId = parsed.clientId.ifBlank { identity.clientId },
            ipv4 = parsed.ipv4.ifBlank { identity.ipv4 },
            ipv6 = parsed.ipv6.ifBlank { identity.ipv6 },
            wireGuardPeerPublicKey = parsed.peerPublicKey.ifBlank { identity.wireGuardPeerPublicKey },
            endpointHost = parsed.endpointHost.ifBlank { identity.endpointHost },
            endpointPort = if (parsed.endpointPort > 0) parsed.endpointPort else identity.endpointPort,
            activeTunnelType = "wireguard",
        )
    }

    private fun get(path: String, token: String): JSONObject {
        val request = Request.Builder()
            .url(BASE_URL + path)
            .header("User-Agent", USER_AGENT)
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(text)
                        .optJSONArray("errors")
                        ?.optJSONObject(0)
                        ?.optString("message")
                        .orEmpty()
                }.getOrDefault("")
                throw IOException(
                    "WARP HTTP ${response.code}" + if (message.isNotBlank()) ": $message" else "",
                )
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject,
        token: String?,
    ): JSONObject {
        val builder = Request.Builder()
            .url(BASE_URL + path)
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("User-Agent", USER_AGENT)
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("Accept", "application/json")
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        builder.method(method, body.toString().toRequestBody(jsonMediaType))

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(text)
                        .optJSONArray("errors")
                        ?.optJSONObject(0)
                        ?.optString("message")
                        .orEmpty()
                }.getOrDefault("")
                throw IOException(
                    "WARP HTTP ${response.code}" + if (message.isNotBlank()) ": $message" else "",
                )
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun parseConfig(config: JSONObject): ParsedConfig {
        val addresses = config.optJSONObject("interface")
            ?.optJSONObject("addresses")
        val peerObject = config.optJSONArray("peers")?.optJSONObject(0)
        val peerPublicKey = peerObject?.optString("public_key").orEmpty()
        val endpointObject = peerObject?.optJSONObject("endpoint")
        val endpoint = endpointObject?.optString("v4").orEmpty()
            .ifBlank { endpointObject?.optString("host").orEmpty() }
        val (host, port) = splitEndpoint(endpoint)

        return ParsedConfig(
            clientId = config.optString("client_id"),
            peerPublicKey = peerPublicKey,
            ipv4 = addresses?.optString("v4").orEmpty(),
            ipv6 = addresses?.optString("v6").orEmpty(),
            endpointHost = host,
            endpointPort = port,
        )
    }

    private fun splitEndpoint(endpoint: String): Pair<String, Int> {
        val index = endpoint.lastIndexOf(':')
        if (index <= 0) return endpoint to 0
        val host = endpoint.substring(0, index).removeSurrounding("[", "]")
        val port = endpoint.substring(index + 1).toIntOrNull() ?: 0
        return host to port
    }

    private fun timestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date())
    }

    private fun modelName(): String {
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return name.ifBlank { "Lintas Android" }
    }

    private fun randomSerial(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class ParsedConfig(
        val clientId: String = "",
        val peerPublicKey: String = "",
        val ipv4: String = "",
        val ipv6: String = "",
        val endpointHost: String = "",
        val endpointPort: Int = 0,
    )
}
