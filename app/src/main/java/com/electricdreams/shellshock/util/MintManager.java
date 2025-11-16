package com.electricdreams.shellshock.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages allowed mints for Cashu tokens
 */
public class MintManager {
    private static final String TAG = "MintManager";
    private static final String PREFS_NAME = "MintPreferences";
    private static final String KEY_MINTS = "allowedMints";
    
    // Default mints
    private static final Set<String> DEFAULT_MINTS = new HashSet<String>() {{
        add("https://mint.minibits.cash/Bitcoin");
        add("https://mint.chorus.community");
        add("https://mint.cubabitcoin.org");
        add("https://mint.coinos.io");
    }};
    
    // Singleton instance
    private static MintManager instance;
    
    private final Context context;
    private final SharedPreferences preferences;
    private Set<String> allowedMints;
    
    // Mint change listener
    public interface MintChangeListener {
        void onMintsChanged(List<String> newMints);
    }
    
    private MintChangeListener listener;
    
    private MintManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.allowedMints = preferences.getStringSet(KEY_MINTS, DEFAULT_MINTS);
        
        // Make a copy to ensure it's mutable
        this.allowedMints = new HashSet<>(this.allowedMints);
        
        Log.d(TAG, "Initialized with " + allowedMints.size() + " allowed mints");
    }
    
    /**
     * Get the singleton instance
     */
    public static synchronized MintManager getInstance(Context context) {
        if (instance == null) {
            instance = new MintManager(context);
        }
        return instance;
    }
    
    /**
     * Set a listener to be notified when allowed mints change
     */
    public void setMintChangeListener(MintChangeListener listener) {
        this.listener = listener;
    }
    
    /**
     * Get the list of allowed mints
     */
    public List<String> getAllowedMints() {
        return new ArrayList<>(allowedMints);
    }
    
    /**
     * Add a mint to the allowed list
     * @param mintUrl The mint URL to add
     * @return true if the mint was added, false if it was already in the list
     */
    public boolean addMint(String mintUrl) {
        if (mintUrl == null || mintUrl.trim().isEmpty()) {
            Log.e(TAG, "Cannot add empty mint URL");
            return false;
        }
        
        // Normalize the URL
        mintUrl = normalizeMintUrl(mintUrl);
        
        boolean changed = allowedMints.add(mintUrl);
        
        if (changed) {
            saveChanges();
            Log.d(TAG, "Added mint to allowed list: " + mintUrl);
            
            // Notify listener if set
            if (listener != null) {
                listener.onMintsChanged(getAllowedMints());
            }
        }
        
        return changed;
    }
    
    /**
     * Remove a mint from the allowed list
     * @param mintUrl The mint URL to remove
     * @return true if the mint was removed, false if it wasn't in the list
     */
    public boolean removeMint(String mintUrl) {
        if (mintUrl == null || mintUrl.trim().isEmpty()) {
            Log.e(TAG, "Cannot remove empty mint URL");
            return false;
        }
        
        // Normalize the URL
        mintUrl = normalizeMintUrl(mintUrl);
        
        boolean changed = allowedMints.remove(mintUrl);
        
        if (changed) {
            saveChanges();
            Log.d(TAG, "Removed mint from allowed list: " + mintUrl);
            
            // Notify listener if set
            if (listener != null) {
                listener.onMintsChanged(getAllowedMints());
            }
        }
        
        return changed;
    }
    
    /**
     * Reset allowed mints to the default list
     */
    public void resetToDefaults() {
        allowedMints = new HashSet<>(DEFAULT_MINTS);
        saveChanges();
        Log.d(TAG, "Reset mints to default list");
        
        // Notify listener if set
        if (listener != null) {
            listener.onMintsChanged(getAllowedMints());
        }
    }
    
    /**
     * Check if a mint is allowed
     * @param mintUrl The mint URL to check
     * @return true if the mint is in the allowed list, false otherwise
     */
    public boolean isMintAllowed(String mintUrl) {
        if (mintUrl == null || mintUrl.trim().isEmpty()) {
            return false;
        }
        
        // Normalize the URL
        mintUrl = normalizeMintUrl(mintUrl);
        
        boolean allowed = allowedMints.contains(mintUrl);
        if (!allowed) {
            Log.d(TAG, "Mint not allowed: " + mintUrl);
        }
        
        return allowed;
    }
    
    /**
     * Save current mints to preferences
     */
    private void saveChanges() {
        preferences.edit().putStringSet(KEY_MINTS, allowedMints).apply();
    }
    
    /**
     * Normalize mint URL to ensure consistent format
     * - Trim whitespace
     * - Remove trailing slash if present
     */
    private String normalizeMintUrl(String url) {
        url = url.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
