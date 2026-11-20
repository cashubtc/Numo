package com.electricdreams.numo.core.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts short secrets before they are written to SharedPreferences. */
interface SecretCipher {
    /** Returns an opaque string, or null when encryption is unavailable. */
    fun encrypt(plaintext: String): String?

    /** Returns the plaintext, or null when the value cannot be decrypted (e.g. after a restore). */
    fun decrypt(ciphertext: String): String?
}

/**
 * AES-GCM with a non-exportable Android Keystore key. Values restored from a backup to
 * another device cannot be decrypted, so callers must treat null as "not configured".
 */
class KeystoreSecretCipher(private val keyAlias: String) : SecretCipher {

    override fun encrypt(plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        encode(cipher.iv) + SEPARATOR + encode(encrypted)
    } catch (e: Exception) {
        Log.e(TAG, "Unable to encrypt secret", e)
        null
    }

    override fun decrypt(ciphertext: String): String? = try {
        val parts = ciphertext.split(SEPARATOR)
        if (parts.size != 2) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, decode(parts[0])),
            )
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Unable to decrypt secret", e)
        null
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val TAG = "KeystoreSecretCipher"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SEPARATOR = ":"
    }
}
