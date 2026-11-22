package app.lintas.warp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object WarpIdentityStore {
    private const val PREFS = "lintas_warp"
    private const val KEY = "identity_v1"
    private const val LEGACY_MASQUE_KEY = "masque_identity_v1"
    private const val ALIAS = "lintas_warp_identity"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun load(context: Context): WarpIdentity? = loadKey(context, KEY)

    private fun loadKey(context: Context, key: String): WarpIdentity? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return runCatching {
            val payload = Base64.decode(value, Base64.NO_WRAP)
            require(payload.size > IV_BYTES)
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(ciphertext)
            WarpIdentity.fromJson(String(plain, Charsets.UTF_8))
        }.getOrNull()
    }

    fun save(context: Context, identity: WarpIdentity) = saveKey(context, KEY, identity)

    private fun saveKey(context: Context, key: String, identity: WarpIdentity) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(identity.toJson().toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(key, Base64.encodeToString(payload, Base64.NO_WRAP))
        }
    }

    fun clear(context: Context) {
        clearKey(context, KEY)
        clearKey(context, LEGACY_MASQUE_KEY)
    }

    fun clearLegacyMasque(context: Context) = clearKey(context, LEGACY_MASQUE_KEY)

    private fun clearKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(key)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
