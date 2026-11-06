package app.stratos.host.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-256-GCM vault for secrets that must never leave the
 * device (Cloudflare tunnel token, AI API key). The key is non-exportable;
 * each ciphertext carries a random IV. Kept in its own prefs file which is
 * excluded from backups.
 */
class Vault(context: Context) {

    private val prefs = context.getSharedPreferences("stratos_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: CharArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(String(plain).toByteArray(Charsets.UTF_8))
        return b64(iv) + ":" + b64(ct)
    }

    fun decrypt(token: String): CharArray {
        val (ivB64, ctB64) = token.split(":", limit = 2).let {
            if (it.size != 2) throw IllegalArgumentException("bad vault token")
            it[0] to it[1]
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(ivB64)))
        val plain = cipher.doFinal(unb64(ctB64))
        val chars = String(plain, Charsets.UTF_8).toCharArray()
        plain.fill(0)
        return chars
    }

    fun put(name: String, value: CharArray) {
        prefs.edit().putString(name, encrypt(value)).apply()
    }

    fun get(name: String): CharArray? =
        prefs.getString(name, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    fun has(name: String): Boolean = prefs.contains(name)

    private fun b64(b: ByteArray): String = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "stratos_vault_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
