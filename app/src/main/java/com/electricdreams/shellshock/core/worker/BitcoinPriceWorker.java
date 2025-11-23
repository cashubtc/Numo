package com.electricdreams.shellshock.core.worker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.electricdreams.shellshock.core.util.CurrencyManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Worker class to fetch and cache Bitcoin price in various currencies from Coinbase API
 */
public class BitcoinPriceWorker {
    private static final String TAG = "BitcoinPriceWorker";
    private static final String PREFS_NAME = "BitcoinPricePrefs";
    private static final String KEY_PRICE_PREFIX = "btcPrice_";
    private static final String KEY_LAST_UPDATE_TIME = "lastUpdateTime";
    private static final long UPDATE_INTERVAL_MINUTES = 1; // Update every minute
    
    private static BitcoinPriceWorker instance;
    private ScheduledExecutorService scheduler;
    private final Context context;
    private final Handler mainHandler;
    private final CurrencyManager currencyManager;
    private final Map<String, Double> priceByCurrency = new HashMap<>();
    private PriceUpdateListener listener;

    public interface PriceUpdateListener {
        void onPriceUpdated(double price);
    }

    private BitcoinPriceWorker(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.currencyManager = CurrencyManager.getInstance(context);
        
        // Load cached prices on initialization
        loadCachedPrices();
        
        // Set up a listener for currency changes
        currencyManager.setCurrencyChangeListener(newCurrency -> {
            // When currency changes, update the price
            fetchPrice();
        });
        
        // If we don't have a price for the current currency, fetch it now
        if (getCurrentPrice() <= 0) {
            fetchPrice();
        } else {
            // Check how old the cached price is
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long lastUpdateTime = prefs.getLong(KEY_LAST_UPDATE_TIME, 0);
            long currentTime = System.currentTimeMillis();
            long elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(currentTime - lastUpdateTime);
            
            if (elapsedMinutes >= UPDATE_INTERVAL_MINUTES) {
                // Cached price is too old, fetch a new one
                fetchPrice();
            } else {
                // Notify listener with cached price
                notifyListener();
            }
        }
    }

    public static synchronized BitcoinPriceWorker getInstance(Context context) {
        if (instance == null) {
            instance = new BitcoinPriceWorker(context);
        }
        return instance;
    }

    /**
     * Load all cached prices from preferences
     */
    private void loadCachedPrices() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // Load prices for all supported currencies
        for (String currency : new String[] {
                CurrencyManager.CURRENCY_USD,
                CurrencyManager.CURRENCY_EUR,
                CurrencyManager.CURRENCY_GBP,
                CurrencyManager.CURRENCY_JPY
        }) {
            String key = KEY_PRICE_PREFIX + currency;
            float price = prefs.getFloat(key, 0.0f);
            if (price > 0) {
                priceByCurrency.put(currency, (double) price);
                Log.d(TAG, "Loaded cached price for " + currency + ": " + price);
            }
        }
    }

    public void setPriceUpdateListener(PriceUpdateListener listener) {
        this.listener = listener;
        // Immediately notify listener with current price
        if (getCurrentPrice() > 0 && listener != null) {
            notifyListener();
        }
    }

    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::fetchPrice,
                UPDATE_INTERVAL_MINUTES,
                UPDATE_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        
        Log.d(TAG, "Bitcoin price worker started");
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            Log.d(TAG, "Bitcoin price worker stopped");
        }
    }
    
    /**
     * Get the current BTC price in the selected currency
     */
    public double getCurrentPrice() {
        String currency = currencyManager.getCurrentCurrency();
        return priceByCurrency.getOrDefault(currency, 0.0);
    }
    
    /**
     * Get the current BTC price in USD (for backward compatibility)
     */
    public double getBtcUsdPrice() {
        return priceByCurrency.getOrDefault(CurrencyManager.CURRENCY_USD, 0.0);
    }

    /**
     * Convert satoshis to the current currency based on current BTC price
     */
    public double satoshisToFiat(long satoshis) {
        double currentPrice = getCurrentPrice();
        if (currentPrice <= 0) {
            return 0.0;
        }
        
        // Convert satoshis to BTC (1 BTC = 100,000,000 satoshis)
        double btcAmount = satoshis / 100000000.0;
        
        // Convert BTC to current currency
        return btcAmount * currentPrice;
    }

    /**
     * Convert satoshis to USD (for backward compatibility)
     */
    public double satoshisToUsd(long satoshis) {
        double usdPrice = priceByCurrency.getOrDefault(CurrencyManager.CURRENCY_USD, 0.0);
        if (usdPrice <= 0) {
            return 0.0;
        }
        
        // Convert satoshis to BTC (1 BTC = 100,000,000 satoshis)
        double btcAmount = satoshis / 100000000.0;
        
        // Convert BTC to USD
        return btcAmount * usdPrice;
    }

    /**
     * Format a fiat amount in the current currency
     */
    public String formatFiatAmount(double amount) {
        return currencyManager.formatCurrencyAmount(amount);
    }

    /**
     * Format a USD amount (for backward compatibility)
     */
    public String formatUsdAmount(double usdAmount) {
        long cents = (long) Math.round(usdAmount * 100);
        return new com.electricdreams.shellshock.core.model.Amount(cents, com.electricdreams.shellshock.core.model.Amount.Currency.USD).toString();
    }

    /**
     * Fetch the current Bitcoin price from Coinbase API for the current currency
     */
    private void fetchPrice() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            
            try {
                String currency = currencyManager.getCurrentCurrency();
                String apiUrl = currencyManager.getCoinbaseApiUrl();
                Log.d(TAG, "Fetching Bitcoin price in " + currency + " from: " + apiUrl);
                
                // Use URI and URL.toURI() to avoid the deprecated URL constructor
                URI uri = new URI(apiUrl);
                URL url = uri.toURL();
                
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    
                    // Parse JSON response
                    JSONObject jsonObject = new JSONObject(response.toString());
                    JSONObject data = jsonObject.getJSONObject("data");
                    double price = data.getDouble("amount");
                    
                    // Update price and cache
                    priceByCurrency.put(currency, price);
                    cachePrice(currency, price);
                    
                    Log.d(TAG, "Bitcoin price updated: " + price + " " + currency);
                    notifyListener();
                } else {
                    Log.e(TAG, "Failed to fetch Bitcoin price, response code: " + responseCode);
                }
            } catch (IOException | JSONException | URISyntaxException e) {
                Log.e(TAG, "Error fetching Bitcoin price: " + e.getMessage(), e);
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing reader: " + e.getMessage(), e);
                    }
                }
                
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    /**
     * Cache the Bitcoin price for a specific currency in SharedPreferences
     */
    private void cachePrice(String currency, double price) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putFloat(KEY_PRICE_PREFIX + currency, (float) price);
        editor.putLong(KEY_LAST_UPDATE_TIME, System.currentTimeMillis());
        editor.apply();
    }

    /**
     * Notify the listener on the main thread
     */
    private void notifyListener() {
        if (listener != null) {
            mainHandler.post(() -> listener.onPriceUpdated(getCurrentPrice()));
        }
    }
}
