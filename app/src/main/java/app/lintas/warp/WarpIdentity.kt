package app.lintas.warp

import org.json.JSONObject

data class WarpIdentity(
    val deviceId: String,
    val accessToken: String,
    val wireGuardPublicKey: String,
    val wireGuardPrivateKey: String,
    val wireGuardPeerPublicKey: String,
    val clientId: String,
    val ipv4: String,
    val ipv6: String,
    val endpointHost: String,
    val endpointPort: Int,
    val accountType: String,
    val organization: String,
    val registeredAt: Long,
    val warpPlus: Boolean = false,
    val licenseKey: String = "",
    val activeTunnelType: String = "",
    val masqueCertificatePem: String? = null,
    val masquePrivateKeyPem: String? = null,
    val masqueEnrolledAt: Long? = null,
    val masqueEndpointHost: String = "",
    val masqueEndpointPort: Int = 0,
    val masqueEndpointPublicKeyPem: String? = null,
) {
    val hasMasqueCredentials: Boolean
        get() = !masqueCertificatePem.isNullOrBlank() && !masquePrivateKeyPem.isNullOrBlank()

    val endpoint: String
        get() = if (endpointPort > 0) "$endpointHost:$endpointPort" else "$endpointHost:2408"

    val effectiveEndpointPort: Int
        get() = if (endpointPort > 0) endpointPort else 2408

    val effectiveMasquePort: Int
        get() = if (masqueEndpointPort > 0) masqueEndpointPort else 443

    val hasMasqueTransport: Boolean
        get() = hasMasqueCredentials &&
            masqueEndpointHost.isNotBlank()

    fun toJson(): String = JSONObject().apply {
        put("deviceId", deviceId)
        put("accessToken", accessToken)
        put("wireGuardPublicKey", wireGuardPublicKey)
        put("wireGuardPrivateKey", wireGuardPrivateKey)
        put("wireGuardPeerPublicKey", wireGuardPeerPublicKey)
        put("clientId", clientId)
        put("ipv4", ipv4)
        put("ipv6", ipv6)
        put("endpointHost", endpointHost)
        put("endpointPort", endpointPort)
        put("accountType", accountType)
        put("organization", organization)
        put("registeredAt", registeredAt)
        put("warpPlus", warpPlus)
        put("licenseKey", licenseKey)
        put("activeTunnelType", activeTunnelType)
        put("masqueCertificatePem", masqueCertificatePem ?: JSONObject.NULL)
        put("masquePrivateKeyPem", masquePrivateKeyPem ?: JSONObject.NULL)
        put("masqueEnrolledAt", masqueEnrolledAt ?: JSONObject.NULL)
        put("masqueEndpointHost", masqueEndpointHost)
        put("masqueEndpointPort", masqueEndpointPort)
        put("masqueEndpointPublicKeyPem", masqueEndpointPublicKeyPem ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJson(text: String): WarpIdentity? = runCatching {
            val json = JSONObject(text)
            WarpIdentity(
                deviceId = json.getString("deviceId"),
                accessToken = json.getString("accessToken"),
                wireGuardPublicKey = json.optString("wireGuardPublicKey"),
                wireGuardPrivateKey = json.getString("wireGuardPrivateKey"),
                wireGuardPeerPublicKey = json.optString("wireGuardPeerPublicKey"),
                clientId = json.optString("clientId"),
                ipv4 = json.optString("ipv4"),
                ipv6 = json.optString("ipv6"),
                endpointHost = json.optString("endpointHost"),
                endpointPort = json.optInt("endpointPort", 0),
                accountType = json.optString("accountType"),
                organization = json.optString("organization"),
                registeredAt = json.optLong("registeredAt", 0L),
                warpPlus = json.optBoolean("warpPlus", false),
                licenseKey = json.optString("licenseKey"),
                activeTunnelType = json.optString("activeTunnelType"),
                masqueCertificatePem = if (json.isNull("masqueCertificatePem")) null else json.optString("masqueCertificatePem").takeIf { it.isNotBlank() },
                masquePrivateKeyPem = if (json.isNull("masquePrivateKeyPem")) null else json.optString("masquePrivateKeyPem").takeIf { it.isNotBlank() },
                masqueEnrolledAt = json.optLong("masqueEnrolledAt", 0L).takeIf { it > 0L },
                masqueEndpointHost = json.optString("masqueEndpointHost"),
                masqueEndpointPort = json.optInt("masqueEndpointPort", 0),
                masqueEndpointPublicKeyPem = if (json.isNull("masqueEndpointPublicKeyPem")) null else json.optString("masqueEndpointPublicKeyPem").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}
