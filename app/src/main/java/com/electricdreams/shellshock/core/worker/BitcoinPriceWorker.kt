package com.electricdreams.shellshock.core.worker

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.electricdreams.shellshock.core.model.Amount
import com.electricdreams.shellshock.core.util.CurrencyManager
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Worker class to fetch and cache Bitcoin price in various currencies from Coinbase API.
 */
class BitcoinPriceWorker private constructor(context: Context) {

    interface PriceUpdateListener {
        fun onPriceUpdated(price: Double)
    }

    companion object {
        private const val TAG = "BitcoinPriceWorker"
        private const val PREFS_NAME = "BitcoinPricePrefs"
        private const val KEY_PRICE_PREFIX = "btcPrice_"
        private const val KEY_LAST_UPDATE_TIME = "lastUpdateTime"
        private const val UPDATE_INTERVAL_MINUTES = 1L // Update every minute

        @Volatile
        private var instance: BitcoinPriceWorker? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): BitcoinPriceWorker {
            if (instance == null) {
                instance = BitcoinPriceWorker(context.applicationContext)
            }
            return instance as BitcoinPriceWorker
        }
    }

    private val context: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val currencyManager: CurrencyManager = CurrencyManager.getInstance(context)
    private val priceByCurrency: MutableMap<String, Double> = mutableMapOf()

    private var scheduler: ScheduledExecutorService? = null
    private var listener: PriceUpdateListener? = null

    init {
        // Load cached prices on initialization
        loadCachedPrices()

        // Set up a listener for currency changes
        currencyManager.setCurrencyChangeListener(object : CurrencyManager.CurrencyChangeListener {
            override fun onCurrencyChanged(newCurrency: String) {
                // When currency changes, update the price
                fetchPrice()
            }
        })

        // If we don't have a price for the current currency, fetch it now
        if (getCurrentPrice() <= 0.0) {
            fetchPrice()
        } else {
            // Check how old the cached price is
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastUpdateTime = prefs.getLong(KEY_LAST_UPDATE_TIME, 0L)
            val currentTime = System.currentTimeMillis()
            val elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(currentTime - lastUpdateTime)

            if (elapsedMinutes >= UPDATE_INTERVAL_MINUTES) {
                // Cached price is too old, fetch a new one
                fetchPrice()
            } else {
                // Notify listener with cached price
                notifyListener()
            }
        }
    }

    /**
     * Load all cached prices from preferences.
     */
    private fun loadCachedPrices() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load prices for all supported currencies
        val supportedCurrencies = arrayOf(
            CurrencyManager.CURRENCY_USD,
            CurrencyManager.CURRENCY_EUR,
            CurrencyManager.CURRENCY_GBP,
            CurrencyManager.CURRENCY_JPY,
        )

        for (currency in supportedCurrencies) {
            val key = KEY_PRICE_PREFIX + currency
            val price = prefs.getFloat(key, 0.0f)
            if (price > 0f) {
                priceByCurrency[currency] = price.toDouble()
                Log.d(TAG, "Loaded cached price for $currency: $price")
            }
        }
    }

    fun setPriceUpdateListener(listener: PriceUpdateListener?) {
        this.listener = listener
        // Immediately notify listener with current price
        if (getCurrentPrice() > 0 && listener != null) {
            notifyListener()
        }
    }

    fun start() {
        if (scheduler != null && !scheduler!!.isShutdown) return

        scheduler = Executors.newSingleThreadScheduledExecutor().also { exec ->
            exec.scheduleAtFixedRate(
                { fetchPrice() },
                UPDATE_INTERVAL_MINUTES,
                UPDATE_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
        }

        Log.d(TAG, "Bitcoin price worker started")
    }

    fun stop() {
        scheduler?.let { exec ->
            if (!exec.isShutdown) {
                exec.shutdown()
                Log.d(TAG, "Bitcoin price worker stopped")
            }
        }
    }

    /** Get the current BTC price in the selected currency. */
    fun getCurrentPrice(): Double {
        val currency = currencyManager.getCurrentCurrency()
        return priceByCurrency[currency] ?: 0.0
    }

    /** Get the current BTC price in USD (for backward compatibility). */
    fun getBtcUsdPrice(): Double {
        return priceByCurrency[CurrencyManager.CURRENCY_USD] ?: 0.0
    }

    /** Convert satoshis to the current currency based on current BTC price. */
    fun satoshisToFiat(satoshis: Long): Double {
        val currentPrice = getCurrentPrice()
        if (currentPrice <= 0) return 0.0

        val btcAmount = satoshis / 100_000_000.0 // 1 BTC = 100,000,000 satoshis
        return btcAmount * currentPrice
    }

    /** Convert satoshis to USD (for backward compatibility). */
    fun satoshisToUsd(satoshis: Long): Double {
        val usdPrice = priceByCurrency[CurrencyManager.CURRENCY_USD] ?: 0.0
        if (usdPrice <= 0) return 0.0

        val btcAmount = satoshis / 100_000_000.0
        return btcAmount * usdPrice
    }

    /** Format a fiat amount in the current currency. */
    fun formatFiatAmount(amount: Double): String {
        return currencyManager.formatCurrencyAmount(amount)
    }

    /** Format a USD amount (for backward compatibility). */
    fun formatUsdAmount(usdAmount: Double): String {
        val cents = kotlin.math.round(usdAmount * 100).toLong()
        return Amount(cents, Amount.Currency.USD).toString()
    }

    /** Fetch the current Bitcoin price from Coinbase API for the current currency. */
    private fun fetchPrice() {
        Thread {
            var connection: HttpURLConnection? = null
            var reader: BufferedReader? = null

            try {
                val currency = currencyManager.getCurrentCurrency()
                val apiUrl = currencyManager.getCoinbaseApiUrl()
                Log.d(TAG, "Fetching Bitcoin price in $currency from: $apiUrl")

                val uri = URI(apiUrl)
                val url: URL = uri.toURL()

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = buildString {
                        var line: String?
                        while (reader!!.readLine().also { line = it } != null) {
                            append(line)
                        }
                    }

                    val jsonObject = JSONObject(response)
                    val data = jsonObject.getJSONObject("data")
                    val price = data.getDouble("amount")

                    priceByCurrency[currency] = price
                    cachePrice(currency, price)

                    Log.d(TAG, "Bitcoin price updated: $price $currency")
                    notifyListener()
                } else {
                    Log.e(TAG, "Failed to fetch Bitcoin price, response code: $responseCode")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error fetching Bitcoin price: ${e.message}", e)
            } catch (e: JSONException) {
                Log.e(TAG, "Error parsing Bitcoin price JSON: ${e.message}", e)
            } catch (e: URISyntaxException) {
                Log.e(TAG, "Invalid Bitcoin price URI: ${e.message}", e)
            } finally {
                try {
                    reader?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing reader: ${e.message}", e)
                }
                connection?.disconnect()
            }
        }.start()
    }

    /** Cache the Bitcoin price for a specific currency in SharedPreferences. */
    private fun cachePrice(currency: String, price: Double) {
        val editor: SharedPreferences.Editor =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putFloat(KEY_PRICE_PREFIX + currency, price.toFloat())
        editor.putLong(KEY_LAST_UPDATE_TIME, System.currentTimeMillis())
        editor.apply()
    }

    /** Notify the listener on the main thread. */
    private fun notifyListener() {
        val price = getCurrentPrice()
        listener?.let { l ->
            mainHandler.post { l.onPriceUpdated(price) }
        }
    }
}
