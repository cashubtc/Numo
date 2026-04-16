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
        const val CURRENCY_CUP = "CUP"
        const val CURRENCY_MLC = "MLC"

        // Default currency is USD
        private const val DEFAULT_CURRENCY = CURRENCY_USD

        private const val COINBASE_BASE_URL = "https://api.coinbase.com/v2/prices/BTC-"
        private const val YADIO_BASE_URL = "https://api.yadio.io/rate/BTC/"

        private val COINBASE_PARSER: (String) -> Double = { response ->
            JSONObject(response).getJSONObject("data").getDouble("amount")
        }

        private val YADIO_PARSER: (String) -> Double = { response ->
            1.0 / JSONObject(response).getDouble("rate")
        }

        /** Currencies that need special API sources (not Yadio or Coinbase). */
        private val SPECIAL_APIS = mapOf(
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
            )
        )

        /** Currencies that use Yadio.io API (Latin American currencies) */
        private val YADIO_LATAM_CURRENCIES = setOf(
            "CUP", "MLC", "ARS", "BOB", "BRL", "CLP", "COP", "CRC", "DOP", "GTQ",
            "HNL", "MXN", "NIO", "PAB", "PEN", "PYG", "UYU", "VES"
        )

        /** Currencies that display their code instead of symbol */
        private val USE_CODE_INSTEAD_OF_SYMBOL = setOf(
            "DKK", "SEK", "NOK", "COP", "CLP", "ARS", "VES", "IDR", "VND", "KRW",
            "CUP", "MLC", "JPY", "ISK"
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
        val symbol = Amount.Currency.fromCode(currentCurrency).symbol
        return if (USE_CODE_INSTEAD_OF_SYMBOL.contains(currentCurrency)) {
            currentCurrency
        } else {
            symbol
        }
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
        val upperCode = currencyCode.uppercase()
        if (YADIO_LATAM_CURRENCIES.contains(upperCode)) return true
        return runCatching {
            java.util.Currency.getInstance(upperCode)
            true
        }.getOrDefault(false)
    }

    /** Get the API URL for the current currency. Uses Yadio.io for Latin American currencies. */
    fun getPriceApiUrl(): String {
        return SPECIAL_APIS[currentCurrency]?.url
            ?: if (YADIO_LATAM_CURRENCIES.contains(currentCurrency)) {
                "${YADIO_BASE_URL}$currentCurrency"
            } else {
                "${COINBASE_BASE_URL}$currentCurrency/spot"
            }
    }

    /** Get fallback API URL (Yadio.io) for when primary API fails. */
    fun getFallbackApiUrl(): String {
        return "${YADIO_BASE_URL}$currentCurrency"
    }

    /** Parse a price API response for the current currency. */
    fun parsePriceResponse(response: String, forceYadio: Boolean = false): Double {
        val specialConfig = SPECIAL_APIS[currentCurrency]
        if (specialConfig != null) {
            return specialConfig.parsePrice(response)
        }
        
        // Use Yadio parser for fallback or for Latin American currencies
        val useYadio = forceYadio || YADIO_LATAM_CURRENCIES.contains(currentCurrency)
        
        return if (useYadio) {
            YADIO_PARSER(response)
        } else {
            COINBASE_PARSER(response)
        }
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
