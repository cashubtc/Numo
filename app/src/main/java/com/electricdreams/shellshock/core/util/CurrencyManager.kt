package com.electricdreams.shellshock.core.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.electricdreams.shellshock.core.model.Amount

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

        // Supported currencies
        const val CURRENCY_USD = "USD"
        const val CURRENCY_EUR = "EUR"
        const val CURRENCY_GBP = "GBP"
        const val CURRENCY_JPY = "JPY"

        // Default currency is USD
        private const val DEFAULT_CURRENCY = CURRENCY_USD

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
    fun getCurrentSymbol(): String = when (currentCurrency) {
        CURRENCY_EUR -> "€"
        CURRENCY_GBP -> "£"
        CURRENCY_JPY -> "¥"
        CURRENCY_USD -> "$"
        else -> "$"
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
        return when (currencyCode) {
            CURRENCY_USD, CURRENCY_EUR, CURRENCY_GBP, CURRENCY_JPY -> true
            else -> false
        }
    }

    /** Get the Coinbase API URL for the current currency. */
    fun getCoinbaseApiUrl(): String {
        return "https://api.coinbase.com/v2/prices/BTC-$currentCurrency/spot"
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
