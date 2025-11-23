package com.electricdreams.shellshock.core.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Manages currency settings and preferences for the app
 */
public class CurrencyManager {
    private static final String TAG = "CurrencyManager";
    private static final String PREFS_NAME = "CurrencyPreferences";
    private static final String KEY_CURRENCY = "preferredCurrency";
    
    // Default currency is USD
    private static final String DEFAULT_CURRENCY = "USD";
    
    // Supported currencies
    public static final String CURRENCY_USD = "USD";
    public static final String CURRENCY_EUR = "EUR";
    public static final String CURRENCY_GBP = "GBP";
    public static final String CURRENCY_JPY = "JPY";
    
    // Singleton instance
    private static CurrencyManager instance;
    
    private final Context context;
    private final SharedPreferences preferences;
    private String currentCurrency;
    
    // Currency change listener
    public interface CurrencyChangeListener {
        void onCurrencyChanged(String newCurrency);
    }
    
    private CurrencyChangeListener listener;
    
    private CurrencyManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.currentCurrency = preferences.getString(KEY_CURRENCY, DEFAULT_CURRENCY);
        Log.d(TAG, "Initialized with currency: " + currentCurrency);
    }
    
    /**
     * Get the singleton instance
     */
    public static synchronized CurrencyManager getInstance(Context context) {
        if (instance == null) {
            instance = new CurrencyManager(context);
        }
        return instance;
    }
    
    /**
     * Set a listener to be notified when the currency changes
     */
    public void setCurrencyChangeListener(CurrencyChangeListener listener) {
        this.listener = listener;
    }
    
    /**
     * Get the currently selected currency code (USD, EUR, etc.)
     */
    public String getCurrentCurrency() {
        return currentCurrency;
    }
    
    /**
     * Get the currency symbol for the current currency
     */
    public String getCurrentSymbol() {
        switch (currentCurrency) {
            case CURRENCY_EUR:
                return "€";
            case CURRENCY_GBP:
                return "£";
            case CURRENCY_JPY:
                return "¥";
            case CURRENCY_USD:
            default:
                return "$";
        }
    }
    
    /**
     * Set the preferred currency and save to preferences
     */
    public boolean setPreferredCurrency(String currencyCode) {
        if (!isValidCurrency(currencyCode)) {
            Log.e(TAG, "Invalid currency code: " + currencyCode);
            return false;
        }
        
        boolean changed = !currencyCode.equals(currentCurrency);
        
        if (changed) {
            currentCurrency = currencyCode;
            preferences.edit().putString(KEY_CURRENCY, currencyCode).apply();
            Log.d(TAG, "Currency changed to: " + currencyCode);
            
            // Notify listener if set
            if (listener != null) {
                listener.onCurrencyChanged(currencyCode);
            }
        }
        
        return true;
    }
    
    /**
     * Check if a currency code is valid and supported
     */
    public boolean isValidCurrency(String currencyCode) {
        return CURRENCY_USD.equals(currencyCode) ||
               CURRENCY_EUR.equals(currencyCode) ||
               CURRENCY_GBP.equals(currencyCode) ||
               CURRENCY_JPY.equals(currencyCode);
    }
    
    /**
     * Get the Coinbase API URL for the current currency
     */
    public String getCoinbaseApiUrl() {
        return "https://api.coinbase.com/v2/prices/BTC-" + currentCurrency + "/spot";
    }
    
    /**
     * Format a currency amount with the appropriate symbol
     */
    /**
     * Format a currency amount with the appropriate symbol using Amount class
     */
    public String formatCurrencyAmount(double amount) {
        // Convert to minor units (cents)
        long minorUnits = (long) Math.round(amount * 100);
        com.electricdreams.shellshock.core.model.Amount.Currency currency = 
            com.electricdreams.shellshock.core.model.Amount.Currency.fromCode(currentCurrency);
            
        return new com.electricdreams.shellshock.core.model.Amount(minorUnits, currency).toString();
    }
}
