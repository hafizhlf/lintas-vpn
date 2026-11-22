package app.lintas.warp

import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date

object WarpKeys {
    data class WireGuardKeys(
        val publicKeyBase64: String,
        val privateKeyBase64: String,
    )

    data class MasqueCredentials(
        val spkiBase64: String,
        val certificatePem: String,
        val privateKeyPem: String,
    )

    fun generateWireGuardKeys(): WireGuardKeys {
        val privateKey = X25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey()
        return WireGuardKeys(
            publicKeyBase64 = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP),
            privateKeyBase64 = Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP),
        )
    }

    fun generateMasqueCredentials(): MasqueCredentials {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = generator.generateKeyPair()

        val now = Date()
        val subject = X500Name("CN=Lintas")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            now,
            Date(now.time + 365L * 24L * 60L * 60L * 1000L),
            subject,
            pair.public,
        )
        certificateBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyAgreement),
        )
        certificateBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth),
        )

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer))

        val keyBase64 = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)
        val keyPem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            keyBase64.chunked(64).forEach { appendLine(it) }
            appendLine("-----END PRIVATE KEY-----")
        }
        val certificatePem = StringWriter().also { writer ->
            JcaPEMWriter(writer).use { pem -> pem.writeObject(certificate) }
        }.toString()

        return MasqueCredentials(
            spkiBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
            certificatePem = certificatePem,
            privateKeyPem = keyPem,
        )
    }
}
