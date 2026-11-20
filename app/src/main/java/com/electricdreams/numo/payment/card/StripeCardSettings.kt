package com.electricdreams.numo.payment.card

import android.content.Context
import com.electricdreams.numo.core.prefs.KeystoreSecretCipher
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.prefs.SecretCipher
import com.electricdreams.numo.core.prefs.ThreadSafePreferences

/** Credentials for the merchant's own Stripe account. */
data class StripeCardConfig(
    /** Restricted API key (rk_live_… or rk_test_…) limited to Terminal connection tokens. */
    val restrictedKey: String,
    /** Terminal Location (tml_…) that Tap to Pay readers register to. */
    val locationId: String,
) {
    val isTestMode: Boolean get() = restrictedKey.startsWith(TEST_KEY_PREFIX)

    override fun toString(): String = "StripeCardConfig(locationId=$locationId, testMode=$isTestMode)"

    companion object {
        const val LIVE_KEY_PREFIX = "rk_live_"
        const val TEST_KEY_PREFIX = "rk_test_"
        const val LOCATION_PREFIX = "tml_"

        /** Only restricted keys are accepted; unrestricted secret keys never belong on a device. */
        fun isValidRestrictedKey(key: String): Boolean {
            val trimmed = key.trim()
            return (trimmed.startsWith(LIVE_KEY_PREFIX) || trimmed.startsWith(TEST_KEY_PREFIX)) &&
                trimmed.length > TEST_KEY_PREFIX.length &&
                trimmed.none { it.isWhitespace() }
        }

        fun isValidLocationId(locationId: String): Boolean {
            val trimmed = locationId.trim()
            return trimmed.startsWith(LOCATION_PREFIX) &&
                trimmed.length > LOCATION_PREFIX.length &&
                trimmed.none { it.isWhitespace() }
        }
    }
}

/**
 * Persists card-payment settings. The restricted key is encrypted with an Android Keystore key.
 */
class StripeCardSettings(
    private val prefs: ThreadSafePreferences,
    private val cipher: SecretCipher,
) {

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.putBoolean(KEY_ENABLED, value)

    /** Returns the stored configuration, or null when it is incomplete or cannot be decrypted. */
    fun loadConfig(): StripeCardConfig? {
        val encryptedKey = prefs.getString(KEY_RESTRICTED_KEY) ?: return null
        val restrictedKey = cipher.decrypt(encryptedKey) ?: return null
        val locationId = prefs.getString(KEY_LOCATION_ID) ?: return null
        if (!StripeCardConfig.isValidRestrictedKey(restrictedKey)) return null
        if (!StripeCardConfig.isValidLocationId(locationId)) return null
        return StripeCardConfig(restrictedKey, locationId)
    }

    /** Location ID shown in settings; it is not secret. */
    fun storedLocationId(): String = prefs.getString(KEY_LOCATION_ID) ?: ""

    fun hasStoredKey(): Boolean = prefs.getString(KEY_RESTRICTED_KEY) != null

    /** @return false when the key could not be encrypted, in which case nothing is stored. */
    fun saveConfig(config: StripeCardConfig): Boolean {
        val encrypted = cipher.encrypt(config.restrictedKey.trim()) ?: return false
        prefs.commitStrings(
            mapOf(
                KEY_RESTRICTED_KEY to encrypted,
                KEY_LOCATION_ID to config.locationId.trim(),
            )
        )
        return true
    }

    fun saveLocationId(locationId: String) {
        prefs.putString(KEY_LOCATION_ID, locationId.trim())
    }

    fun clear() {
        prefs.remove(KEY_RESTRICTED_KEY)
        prefs.remove(KEY_LOCATION_ID)
        prefs.putBoolean(KEY_ENABLED, false)
    }

    /** Card payments are offered only when enabled and fully configured. */
    fun isReady(): Boolean = isEnabled && loadConfig() != null

    companion object {
        private const val KEY_ENABLED = "stripe_card_enabled"
        private const val KEY_RESTRICTED_KEY = "stripe_card_restricted_key"
        private const val KEY_LOCATION_ID = "stripe_card_location_id"
        private const val KEYSTORE_ALIAS = "numo_stripe_card_key"

        fun getInstance(context: Context): StripeCardSettings = StripeCardSettings(
            PreferenceStore.app(context),
            KeystoreSecretCipher(KEYSTORE_ALIAS),
        )
    }
}
