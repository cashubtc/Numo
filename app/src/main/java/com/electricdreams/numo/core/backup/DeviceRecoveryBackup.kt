package com.electricdreams.numo.core.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintManager
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stores the only Numo file eligible for Android Auto Backup.
 *
 * The recovery password is used to encrypt the file. A device-only Android
 * Keystore wrapping key lets this device refresh the file when its mint list
 * changes; that wrapping key is never part of the cloud backup.
 */
object DeviceRecoveryBackup {
    private const val TAG = "DeviceRecoveryBackup"
    private const val FILE_NAME = "numo_recovery_backup.json"
    private const val VERSION = 1
    private const val KDF_ITERATIONS = 210_000
    private const val KEY_ALIAS = "numo_device_backup_password"
    private const val PREF_ENABLED = "device_recovery_backup_enabled"
    private const val PREF_WRAPPED_PASSWORD = "device_recovery_backup_wrapped_password"
    private const val PREF_WRAPPED_PASSWORD_IV = "device_recovery_backup_wrapped_password_iv"

    data class RecoveryData(
        val mnemonic: String,
        val mints: List<String>,
        val preferredLightningMint: String?,
        val preferredUnit: String?,
    )

    private data class RecoveryPayload(
        val mnemonic: String,
        val mints: List<String>,
        val preferredLightningMint: String?,
        val preferredUnit: String?,
    )

    private data class BackupEnvelope(
        val version: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val kdfIterations: Int,
        val salt: String,
        val iv: String,
        val ciphertext: String,
    )

    private val gson = Gson()
    private val secureRandom = SecureRandom()

    fun isEnabled(context: Context): Boolean =
        PreferenceStore.app(context).getBoolean(PREF_ENABLED)

    fun hasRestoredBackup(context: Context): Boolean = backupFile(context).isFile

    /** Enables automatic encrypted backup and creates the first envelope. */
    fun enable(context: Context, recoveryPassword: CharArray) {
        require(recoveryPassword.size >= 12) { "Recovery password must be at least 12 characters" }
        try {
            writeEnvelope(context, recoveryPassword)
            storePasswordForUpdates(context, recoveryPassword)
            PreferenceStore.app(context).putBoolean(PREF_ENABLED, true)
        } finally {
            recoveryPassword.fill('\u0000')
        }
    }

    fun disable(context: Context) {
        backupFile(context).delete()
        PreferenceStore.app(context).remove(PREF_ENABLED)
        PreferenceStore.app(context).remove(PREF_WRAPPED_PASSWORD)
        PreferenceStore.app(context).remove(PREF_WRAPPED_PASSWORD_IV)
    }

    /** Called after local wallet state changes. Failures retain the prior valid backup. */
    fun updateIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        val password = loadPasswordForUpdates(context) ?: run {
            Log.w(TAG, "Unable to refresh device recovery backup: password unavailable")
            return
        }
        try {
            writeEnvelope(context, password)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to refresh device recovery backup", exception)
        } finally {
            password.fill('\u0000')
        }
    }

    fun decrypt(context: Context, recoveryPassword: CharArray): RecoveryData {
        try {
            val envelope = gson.fromJson(backupFile(context).readText(), BackupEnvelope::class.java)
                ?: throw IllegalArgumentException("Backup file is invalid")
            require(envelope.version == VERSION) { "Backup format is not supported" }
            val key = deriveKey(recoveryPassword, decode(envelope.salt), envelope.kdfIterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(envelope.iv)))
            val payload = gson.fromJson(
                cipher.doFinal(decode(envelope.ciphertext)).toString(Charsets.UTF_8),
                RecoveryPayload::class.java,
            ) ?: throw IllegalArgumentException("Backup file is invalid")
            return RecoveryData(
                mnemonic = payload.mnemonic,
                mints = payload.mints,
                preferredLightningMint = payload.preferredLightningMint,
                preferredUnit = payload.preferredUnit,
            )
        } finally {
            recoveryPassword.fill('\u0000')
        }
    }

    private fun writeEnvelope(context: Context, password: CharArray) {
        val mnemonic = CashuWalletManager.getMnemonic()
            ?: throw IllegalStateException("Wallet seed phrase is unavailable")
        val mintManager = MintManager.getInstance(context)
        val payload = RecoveryPayload(
            mnemonic = mnemonic,
            mints = mintManager.getAllowedMints(),
            preferredLightningMint = mintManager.getPreferredLightningMint(),
            preferredUnit = mintManager.getPreferredUnit(),
        )
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val key = deriveKey(password, salt, KDF_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val now = System.currentTimeMillis()
        val existingCreatedAt = backupFile(context).takeIf(File::isFile)?.let {
            runCatching { gson.fromJson(it.readText(), BackupEnvelope::class.java).createdAt }.getOrNull()
        }
        val envelope = BackupEnvelope(
            version = VERSION,
            createdAt = existingCreatedAt ?: now,
            updatedAt = now,
            kdfIterations = KDF_ITERATIONS,
            salt = encode(salt),
            iv = encode(iv),
            ciphertext = encode(cipher.doFinal(gson.toJson(payload).toByteArray(Charsets.UTF_8))),
        )
        val temporaryFile = File(context.filesDir, "$FILE_NAME.tmp")
        temporaryFile.writeText(gson.toJson(envelope))
        if (!temporaryFile.renameTo(backupFile(context))) {
            temporaryFile.delete()
            throw IllegalStateException("Could not save recovery backup")
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun storePasswordForUpdates(context: Context, password: CharArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val encrypted = cipher.doFinal(password.concatToString().toByteArray(Charsets.UTF_8))
        PreferenceStore.app(context).putString(PREF_WRAPPED_PASSWORD, encode(encrypted))
        PreferenceStore.app(context).putString(PREF_WRAPPED_PASSWORD_IV, encode(cipher.iv))
    }

    private fun loadPasswordForUpdates(context: Context): CharArray? {
        val encrypted = PreferenceStore.app(context).getString(PREF_WRAPPED_PASSWORD) ?: return null
        val iv = PreferenceStore.app(context).getString(PREF_WRAPPED_PASSWORD_IV) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(128, decode(iv)))
            cipher.doFinal(decode(encrypted)).toString(Charsets.UTF_8).toCharArray()
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to unwrap recovery password", exception)
            null
        }
    }

    private fun getOrCreateWrappingKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun backupFile(context: Context) = File(context.filesDir, FILE_NAME)
    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
