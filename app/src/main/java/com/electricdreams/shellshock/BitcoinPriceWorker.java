package com.electricdreams.shellshock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Worker class to fetch and cache Bitcoin price in USD from Coinbase API
 */
public class BitcoinPriceWorker {
    private static final String TAG = "BitcoinPriceWorker";
    private static final String COINBASE_API_URL = "https://api.coinbase.com/v2/prices/BTC-USD/spot";
    private static final String PREFS_NAME = "BitcoinPricePrefs";
    private static final String KEY_BTC_USD_PRICE = "btcUsdPrice";
    private static final String KEY_LAST_UPDATE_TIME = "lastUpdateTime";
    private static final long UPDATE_INTERVAL_MINUTES = 1; // Update every minute
    
    private static BitcoinPriceWorker instance;
    private ScheduledExecutorService scheduler;
    private final Context context;
    private final Handler mainHandler;
    private double btcUsdPrice = 0.0;
    private PriceUpdateListener listener;

    public interface PriceUpdateListener {
        void onPriceUpdated(double price);
    }

    private BitcoinPriceWorker(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Load cached price on initialization
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        btcUsdPrice = prefs.getFloat(KEY_BTC_USD_PRICE, 0.0f);
        
        if (btcUsdPrice <= 0) {
            // No cached price, fetch immediately
            fetchPrice();
        } else {
            // Check how old the cached price is
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

    public void setPriceUpdateListener(PriceUpdateListener listener) {
        this.listener = listener;
        // Immediately notify listener with current price
        if (btcUsdPrice > 0 && listener != null) {
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
     * Get the current BTC price in USD
     */
    public double getBtcUsdPrice() {
        return btcUsdPrice;
    }

    /**
     * Convert satoshis to USD based on current BTC price
     */
    public double satoshisToUsd(long satoshis) {
        if (btcUsdPrice <= 0) {
            return 0.0;
        }
        
        // Convert satoshis to BTC (1 BTC = 100,000,000 satoshis)
        double btcAmount = satoshis / 100000000.0;
        
        // Convert BTC to USD
        return btcAmount * btcUsdPrice;
    }

    /**
     * Format a USD amount as a string with $ sign and 2 decimal places
     */
    public String formatUsdAmount(double usdAmount) {
        return String.format("$%.2f USD", usdAmount);
    }

    /**
     * Fetch the current Bitcoin price from Coinbase API
     */
    private void fetchPrice() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            BufferedReader reader = null;
            
            try {
                URL url = new URL(COINBASE_API_URL);
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
                    btcUsdPrice = price;
                    cachePrice(price);
                    
                    Log.d(TAG, "Bitcoin price updated: " + price + " USD");
                    notifyListener();
                } else {
                    Log.e(TAG, "Failed to fetch Bitcoin price, response code: " + responseCode);
                }
            } catch (IOException | JSONException e) {
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
     * Cache the Bitcoin price in SharedPreferences
     */
    private void cachePrice(double price) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putFloat(KEY_BTC_USD_PRICE, (float) price);
        editor.putLong(KEY_LAST_UPDATE_TIME, System.currentTimeMillis());
        editor.apply();
    }

    /**
     * Notify the listener on the main thread
     */
    private void notifyListener() {
        if (listener != null) {
            mainHandler.post(() -> listener.onPriceUpdated(btcUsdPrice));
        }
    }
}
