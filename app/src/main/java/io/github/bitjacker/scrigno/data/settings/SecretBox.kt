package io.github.bitjacker.scrigno.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the server password with a key that lives in the Android Keystore (hardware backed on
 * most phones) and can never leave this device.
 */
class SecretBox(private val alias: String = "scrigno.server.password") {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX_KEYSTORE + b64(cipher.iv) + ":" + b64(encrypted)
        } catch (e: Exception) {
            // A few devices have a broken keystore: the app's private storage still protects it.
            PREFIX_PLAIN + b64(plain.toByteArray(Charsets.UTF_8))
        }
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return ""
        return try {
            when {
                stored.startsWith(PREFIX_KEYSTORE) -> {
                    val (iv, data) = stored.removePrefix(PREFIX_KEYSTORE).split(':', limit = 2)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
                    String(cipher.doFinal(unb64(data)), Charsets.UTF_8)
                }
                stored.startsWith(PREFIX_PLAIN) -> String(unb64(stored.removePrefix(PREFIX_PLAIN)), Charsets.UTF_8)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun forget() {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX_KEYSTORE = "ks1:"
        const val PREFIX_PLAIN = "pl1:"
    }
}
