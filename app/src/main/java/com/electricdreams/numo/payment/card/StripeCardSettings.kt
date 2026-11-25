package com.electricdreams.numo.payment.card

import android.content.Context
import java.util.UUID
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
                trimmed.all { it in '!'..'~' }
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
        set(value) {
            prefs.edit {
                putBoolean(KEY_ENABLE_ON_PASS, value)
                putBoolean(KEY_ENABLED, value)
            }
        }

    private val enableOnPass: Boolean
        get() {
            // Older forms stored the switch alone. Guided setups also recorded validation.
            val previousIntent = when {
                prefs.contains(KEY_CHECK_PASSED) -> isEnabled || !hasPassedCheck
                hasStoredKey() -> prefs.getBoolean(KEY_ENABLED, true)
                else -> true
            }
            return prefs.getBoolean(KEY_ENABLE_ON_PASS, previousIntent)
        }

    internal val configurationRevision: String?
        get() = prefs.getString(KEY_REVISION)

    val readerCheckFailed: Boolean get() = prefs.getBoolean(KEY_READER_FAILED, false)

    /** A late reader callback may only update the configuration that was actually checked. */
    fun recordFullCheck(
        revision: String?,
        report: CardSetupChecker.Report,
        requestedEnable: Boolean,
    ): Boolean = prefs.edit {
        if (revision != configurationRevision) return@edit false
        putString(KEY_LAST_CHECKED_AT, System.currentTimeMillis().toString())
        if (report.passed) {
            val enable = requestedEnable || enableOnPass
            putBoolean(KEY_CHECK_PASSED, true)
            putBoolean(KEY_READER_FAILED, false)
            putBoolean(KEY_ENABLE_ON_PASS, enable)
            putBoolean(KEY_ENABLED, enable)
        } else if (report.outcomes[CardSetupChecker.Check.READER_STARTS] is CardSetupChecker.Outcome.Failed) {
            putBoolean(KEY_READER_FAILED, true)
        }
        true
    }

    /**
     * True once a full setup check has passed for the stored key and location. Setups enabled
     * before the check existed count as passed.
     */
    var hasPassedCheck: Boolean
        get() = prefs.getBoolean(KEY_CHECK_PASSED, isEnabled)
        set(value) = prefs.putBoolean(KEY_CHECK_PASSED, value)

    fun loadConfig(): StripeCardConfig? = configFor(storedKey(), storedLocationId())

    private fun configFor(key: String?, locationId: String): StripeCardConfig? =
        if (key != null && StripeCardConfig.isValidLocationId(locationId)) StripeCardConfig(key, locationId)
        else null

    data class Snapshot(
        val config: StripeCardConfig?,
        val hasStoredKey: Boolean,
        val maskedKey: String?,
        val isTestMode: Boolean,
        val locationId: String,
        val locationName: String?,
        val locationPlace: String?,
        val enabled: Boolean,
        val passed: Boolean,
        val readerFailed: Boolean,
        val lastCheckedAt: Long?,
    )

    fun snapshot(): Snapshot {
        val key = storedKey()
        val id = storedLocationId()
        return Snapshot(
            config = configFor(key, id),
            hasStoredKey = hasStoredKey(),
            maskedKey = key?.let(::maskKey),
            isTestMode = key?.startsWith(StripeCardConfig.TEST_KEY_PREFIX) == true,
            locationId = id,
            locationName = storedLocationName(),
            locationPlace = storedLocationPlace(),
            enabled = isEnabled,
            passed = hasPassedCheck,
            readerFailed = readerCheckFailed,
            lastCheckedAt = lastCheckedAt,
        )
    }

    /** Location ID shown in settings; it is not secret. */
    fun storedLocationId(): String = prefs.getString(KEY_LOCATION_ID) ?: ""

    /** Name the merchant gave the location at Stripe, when it was picked or created in Numo. */
    fun storedLocationName(): String? = prefs.getString(KEY_LOCATION_NAME)?.takeIf { it.isNotBlank() }

    fun hasStoredKey(): Boolean = prefs.getString(KEY_RESTRICTED_KEY) != null

    /** The stored key, or null when there is none or it cannot be decrypted. */
    fun storedKey(): String? {
        val encryptedKey = prefs.getString(KEY_RESTRICTED_KEY) ?: return null
        return cipher.decrypt(encryptedKey)?.takeIf(StripeCardConfig::isValidRestrictedKey)
    }

    /** True for a sandbox key, false for a live key, null when no key is stored. */
    fun isTestMode(): Boolean? = storedKey()?.startsWith(StripeCardConfig.TEST_KEY_PREFIX)

    /** The stored key with its secret part hidden, e.g. "rk_test_…dtS3". */
    fun maskedKey(): String? = storedKey()?.let(::maskKey)

    /**
     * Stores the key by itself, before a location is chosen during setup.
     *
     * @return false when the key is not a restricted key or could not be encrypted.
     */
    fun saveKey(restrictedKey: String): Boolean {
        val key = restrictedKey.trim()
        if (!StripeCardConfig.isValidRestrictedKey(key)) return false
        val encrypted = cipher.encrypt(key) ?: return false
        return saveConfiguration(mapOf(KEY_RESTRICTED_KEY to encrypted), key != storedKey())
    }

    /**
     * Stores the chosen location; [name] is null when only the ID is known.
     *
     * @param place where the shop is, e.g. "Washington, US", shown under its name.
     */
    fun saveLocation(locationId: String, name: String?, place: String? = null): Boolean {
        return saveConfiguration(
            mapOf(
                KEY_LOCATION_ID to locationId.trim(),
                KEY_LOCATION_NAME to name?.trim()?.takeIf { it.isNotEmpty() },
                KEY_LOCATION_PLACE to place?.trim()?.takeIf { it.isNotEmpty() },
            ),
            locationId.trim() != storedLocationId(),
        )
    }

    /** Remembers what Stripe has for the stored location, once it has been looked up. */
    fun saveLocationName(name: String, place: String? = null) {
        name.trim().takeIf { it.isNotEmpty() }?.let { prefs.putString(KEY_LOCATION_NAME, it) }
        place?.trim()?.takeIf { it.isNotEmpty() }?.let { prefs.putString(KEY_LOCATION_PLACE, it) }
    }

    /** City and country of the stored location, when Stripe has them. */
    fun storedLocationPlace(): String? =
        prefs.getString(KEY_LOCATION_PLACE)?.takeIf { it.isNotBlank() }

    /** When the full setup check last finished, pass or fail, in epoch milliseconds. */
    var lastCheckedAt: Long?
        get() = prefs.getString(KEY_LAST_CHECKED_AT)?.toLongOrNull()
        set(value) = prefs.putString(KEY_LAST_CHECKED_AT, value?.toString())

    /** @return false when the key could not be encrypted, in which case nothing is stored. */
    fun saveConfig(config: StripeCardConfig): Boolean {
        val encrypted = cipher.encrypt(config.restrictedKey.trim()) ?: return false
        return saveConfiguration(
            mapOf(KEY_RESTRICTED_KEY to encrypted, KEY_LOCATION_ID to config.locationId.trim()),
            loadConfig() != config,
        )
    }

    private fun saveConfiguration(values: Map<String, String?>, changed: Boolean): Boolean =
        if (changed) {
            prefs.commitValues(
                values + mapOf(KEY_LAST_CHECKED_AT to null, KEY_REVISION to UUID.randomUUID().toString()),
                mapOf(
                    KEY_ENABLE_ON_PASS to enableOnPass,
                    KEY_ENABLED to false,
                    KEY_CHECK_PASSED to false,
                    KEY_READER_FAILED to false,
                ),
            )
        } else prefs.commitStrings(values)

    fun clear() {
        prefs.edit {
            putString(KEY_REVISION, UUID.randomUUID().toString())
            remove(KEY_RESTRICTED_KEY)
            remove(KEY_LOCATION_ID)
            remove(KEY_LOCATION_NAME)
            remove(KEY_LOCATION_PLACE)
            remove(KEY_LAST_CHECKED_AT)
            putBoolean(KEY_ENABLED, false)
            remove(KEY_CHECK_PASSED)
            remove(KEY_ENABLE_ON_PASS)
            remove(KEY_READER_FAILED)
        }
    }

    /** Card payments are offered only when enabled and fully configured. */
    fun isReady(): Boolean = isEnabled && !readerCheckFailed && loadConfig() != null

    companion object {
        private const val KEY_REVISION = "stripe_card_configuration_revision"
        private const val KEY_ENABLE_ON_PASS = "stripe_card_enable_on_pass"
        private const val KEY_READER_FAILED = "stripe_card_reader_failed"
        private const val KEY_ENABLED = "stripe_card_enabled"
        private const val KEY_RESTRICTED_KEY = "stripe_card_restricted_key"
        private const val KEY_LOCATION_ID = "stripe_card_location_id"
        private const val KEY_LOCATION_NAME = "stripe_card_location_name"
        private const val KEY_LOCATION_PLACE = "stripe_card_location_place"
        private const val KEY_LAST_CHECKED_AT = "stripe_card_last_checked_at"
        private const val KEY_CHECK_PASSED = "stripe_card_check_passed"
        private const val MASK_VISIBLE_SUFFIX = 4

        /** Keeps the mode prefix and the last few characters, which is enough to recognise a key. */
        fun maskKey(key: String): String {
            val trimmed = key.trim()
            val prefix = listOf(StripeCardConfig.LIVE_KEY_PREFIX, StripeCardConfig.TEST_KEY_PREFIX)
                .firstOrNull(trimmed::startsWith)
                .orEmpty()
            val secret = trimmed.removePrefix(prefix)
            val suffix = if (secret.length > MASK_VISIBLE_SUFFIX * 2) secret.takeLast(MASK_VISIBLE_SUFFIX) else ""
            return "$prefix…$suffix"
        }
        private const val KEYSTORE_ALIAS = "numo_stripe_card_key"

        fun getInstance(context: Context): StripeCardSettings = StripeCardSettings(
            PreferenceStore.app(context),
            KeystoreSecretCipher(KEYSTORE_ALIAS),
        )
    }
}
