package com.electricdreams.numo.core.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.electricdreams.numo.core.model.Amount
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages currency settings and preferences for the app.
 */
class CurrencyManager private constructor(context: Context) {

    interface CurrencyChangeListener {
        fun onCurrencyChanged(newCurrency: String)
    }

    companion object {
        private const val TAG = "CurrencyManager"
        private const val PREFS_NAME = "CurrencyPreferences"
        private const val KEY_CURRENCY = "preferredCurrency"

        // Legacy compatibility - keeping these as constants for places that still reference them
        const val CURRENCY_USD = "USD"
        const val CURRENCY_EUR = "EUR"
        const val CURRENCY_GBP = "GBP"
        const val CURRENCY_JPY = "JPY"
        const val CURRENCY_DKK = "DKK"
        const val CURRENCY_SEK = "SEK"
        const val CURRENCY_NOK = "NOK"
        const val CURRENCY_KRW = "KRW"

        // Default currency is USD
        private const val DEFAULT_CURRENCY = CURRENCY_USD

        private const val COINBASE_BASE_URL = "https://api.coinbase.com/v2/prices/BTC-"

        private val COINBASE_PARSER: (String) -> Double = { response ->
            JSONObject(response).getJSONObject("data").getDouble("amount")
        }

        /** Add entries here for currencies not on Coinbase (or where we prefer a different source). */
        private val CUSTOM_APIS = mapOf(
            CURRENCY_JPY to PriceApiConfig(
                url = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=jpy",
                parsePrice = { response ->
                    JSONObject(response).getJSONObject("bitcoin").getDouble("jpy")
                }
            ),
            CURRENCY_KRW to PriceApiConfig(
                url = "https://api.upbit.com/v1/ticker?markets=KRW-BTC",
                parsePrice = { response ->
                    JSONArray(response).getJSONObject(0).getDouble("trade_price")
                }
            ),
        )

        @Volatile
        private var instance: CurrencyManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): CurrencyManager {
            if (instance == null) {
                instance = CurrencyManager(context.applicationContext)
            }
            return instance as CurrencyManager
        }
    }

    private val context: Context = context.applicationContext
    private val preferences: SharedPreferences =
        this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var currentCurrency: String =
        preferences.getString(KEY_CURRENCY, DEFAULT_CURRENCY) ?: DEFAULT_CURRENCY

    private var listener: CurrencyChangeListener? = null

    init {
        Log.d(TAG, "Initialized with currency: $currentCurrency")
    }

    /** Set a listener to be notified when the currency changes. */
    fun setCurrencyChangeListener(listener: CurrencyChangeListener?) {
        this.listener = listener
    }

    /** Get the currently selected currency code (USD, EUR, etc.). */
    fun getCurrentCurrency(): String = currentCurrency

    /** Get the currency symbol for the current currency. */
    fun getCurrentSymbol(): String {
        return Amount.Currency.fromCode(currentCurrency).symbol
    }

    /** Set the preferred currency and save to preferences. */
    fun setPreferredCurrency(currencyCode: String): Boolean {
        if (!isValidCurrency(currencyCode)) {
            Log.e(TAG, "Invalid currency code: $currencyCode")
            return false
        }

        val changed = currencyCode != currentCurrency
        if (changed) {
            currentCurrency = currencyCode
            preferences.edit().putString(KEY_CURRENCY, currencyCode).apply()
            Log.d(TAG, "Currency changed to: $currencyCode")

            listener?.onCurrencyChanged(currencyCode)
        }

        return true
    }

    /** Check if a currency code is valid and supported. */
    fun isValidCurrency(currencyCode: String?): Boolean {
        if (currencyCode.isNullOrEmpty()) return false
        return runCatching {
            java.util.Currency.getInstance(currencyCode.uppercase())
            true
        }.getOrDefault(false)
    }

    /** Get the API URL for the current currency. Falls back to Coinbase. */
    fun getPriceApiUrl(): String {
        return CUSTOM_APIS[currentCurrency]?.url
            ?: "${COINBASE_BASE_URL}$currentCurrency/spot"
    }

    /** Parse a price API response for the current currency. */
    fun parsePriceResponse(response: String): Double {
        val parser = CUSTOM_APIS[currentCurrency]?.parsePrice ?: COINBASE_PARSER
        return parser(response)
    }

    /**
     * Format a currency amount with the appropriate symbol using Amount class.
     */
    fun formatCurrencyAmount(amount: Double): String {
        // Convert to minor units (cents)
        val minorUnits = kotlin.math.round(amount * 100).toLong()
        val currency = Amount.Currency.fromCode(currentCurrency)
        return Amount(minorUnits, currency).toString()
    }
}

/** Configuration for a non-Coinbase price API. */
data class PriceApiConfig(
    val url: String,
    val parsePrice: (String) -> Double,
)
