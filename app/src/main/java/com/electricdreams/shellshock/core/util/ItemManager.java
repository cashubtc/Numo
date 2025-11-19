package com.electricdreams.shellshock.core.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.electricdreams.shellshock.core.model.Item;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manager class for handling the merchant's catalog items
 */
public class ItemManager {
    private static final String TAG = "ItemManager";
    private static final String PREFS_NAME = "ItemManagerPrefs";
    private static final String KEY_ITEM_LIST = "items_list";
    
    private static ItemManager instance;
    private final Context context;
    private final List<Item> items = new ArrayList<>();
    private final SharedPreferences prefs;
    
    private ItemManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadItems();
    }
    
    public static synchronized ItemManager getInstance(Context context) {
        if (instance == null) {
            instance = new ItemManager(context);
        }
        return instance;
    }
    
    /**
     * Load items from SharedPreferences
     */
    private void loadItems() {
        items.clear();
        String itemsJson = prefs.getString(KEY_ITEM_LIST, "");
        
        if (!TextUtils.isEmpty(itemsJson)) {
            try {
                JSONArray array = new JSONArray(itemsJson);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    Item item = new Item();
                    item.setId(obj.getString("id"));
                    item.setName(obj.getString("name"));
                    item.setPrice(obj.getDouble("price"));
                    
                    if (!obj.isNull("variationName")) {
                        item.setVariationName(obj.getString("variationName"));
                    }
                    if (!obj.isNull("sku")) {
                        item.setSku(obj.getString("sku"));
                    }
                    if (!obj.isNull("description")) {
                        item.setDescription(obj.getString("description"));
                    }
                    if (!obj.isNull("category")) {
                        item.setCategory(obj.getString("category"));
                    }
                    if (!obj.isNull("gtin")) {
                        item.setGtin(obj.getString("gtin"));
                    }
                    if (!obj.isNull("quantity")) {
                        item.setQuantity(obj.getInt("quantity"));
                    }
                    if (!obj.isNull("alertEnabled")) {
                        item.setAlertEnabled(obj.getBoolean("alertEnabled"));
                    }
                    if (!obj.isNull("alertThreshold")) {
                        item.setAlertThreshold(obj.getInt("alertThreshold"));
                    }
                    
                    items.add(item);
                }
                
                Log.d(TAG, "Loaded " + items.size() + " items from storage");
            } catch (JSONException e) {
                Log.e(TAG, "Error loading items: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Save items to SharedPreferences
     */
    private void saveItems() {
        try {
            JSONArray array = new JSONArray();
            for (Item item : items) {
                JSONObject obj = new JSONObject();
                obj.put("id", item.getId());
                obj.put("name", item.getName());
                obj.put("price", item.getPrice());
                
                if (item.getVariationName() != null) {
                    obj.put("variationName", item.getVariationName());
                }
                if (item.getSku() != null) {
                    obj.put("sku", item.getSku());
                }
                if (item.getDescription() != null) {
                    obj.put("description", item.getDescription());
                }
                if (item.getCategory() != null) {
                    obj.put("category", item.getCategory());
                }
                if (item.getGtin() != null) {
                    obj.put("gtin", item.getGtin());
                }
                obj.put("quantity", item.getQuantity());
                obj.put("alertEnabled", item.isAlertEnabled());
                obj.put("alertThreshold", item.getAlertThreshold());
                
                array.put(obj);
            }
            
            prefs.edit().putString(KEY_ITEM_LIST, array.toString()).apply();
            Log.d(TAG, "Saved " + items.size() + " items to storage");
        } catch (JSONException e) {
            Log.e(TAG, "Error saving items: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get all items in the catalog
     * @return List of items
     */
    public List<Item> getAllItems() {
        return new ArrayList<>(items);
    }
    
    /**
     * Add an item to the catalog
     * @param item Item to add
     * @return true if added successfully, false if already exists
     */
    public boolean addItem(Item item) {
        if (item.getId() == null || item.getId().isEmpty()) {
            item.setId(UUID.randomUUID().toString());
        }
        
        // Check if item with the same ID already exists
        for (Item existingItem : items) {
            if (existingItem.getId().equals(item.getId())) {
                return false;
            }
        }
        
        items.add(item);
        saveItems();
        return true;
    }
    
    /**
     * Update an existing item
     * @param item Item to update
     * @return true if updated successfully, false if not found
     */
    public boolean updateItem(Item item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(item.getId())) {
                items.set(i, item);
                saveItems();
                return true;
            }
        }
        return false;
    }
    
    /**
     * Remove an item from the catalog
     * @param itemId ID of the item to remove
     * @return true if removed successfully, false if not found
     */
    public boolean removeItem(String itemId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(itemId)) {
                items.remove(i);
                saveItems();
                return true;
            }
        }
        return false;
    }
    
    /**
     * Clear all items
     */
    public void clearItems() {
        items.clear();
        saveItems();
    }
    
    /**
     * Import items from a CSV file
     * @param csvFilePath Path to the CSV file
     * @param clearExisting Whether to clear existing items before importing
     * @return Number of items imported
     */
    public int importItemsFromCsv(String csvFilePath, boolean clearExisting) {
        if (clearExisting) {
            items.clear();
        }
        
        int importedCount = 0;
        BufferedReader reader = null;
        
        try {
            File file = new File(csvFilePath);
            if (!file.exists()) {
                Log.e(TAG, "CSV file not found: " + csvFilePath);
                return 0;
            }
            
            reader = new BufferedReader(new FileReader(file));
            
            // Skip header lines (first 5 lines based on Square template)
            for (int i = 0; i < 5; i++) {
                reader.readLine();
            }
            
            String line;
            while ((line = reader.readLine()) != null) {
                // Parse CSV line
                String[] values = parseCsvLine(line);
                if (values.length < 14) { // Need at least 14 columns for basic item data
                    continue;
                }
                
                // Item data starts at column 1 (index 0 is token)
                String name = values[1];
                String variationName = values[2];
                String sku = values[3];
                String description = values[4];
                String category = values[5];
                String gtin = values[7];
                
                // Parse price (index 12)
                double price = 0.0;
                try {
                    price = Double.parseDouble(values[12]);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid price format for item: " + name);
                }
                
                // Parse quantity (index 20)
                int quantity = 0;
                if (values.length > 20) {
                    try {
                        quantity = Integer.parseInt(values[20]);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Invalid quantity format for item: " + name);
                    }
                }
                
                // Parse stock alert (index 22-23)
                boolean alertEnabled = false;
                int alertThreshold = 0;
                
                if (values.length > 22) {
                    alertEnabled = "Y".equalsIgnoreCase(values[22]);
                }
                
                if (values.length > 23) {
                    try {
                        alertThreshold = Integer.parseInt(values[23]);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Invalid alert threshold format for item: " + name);
                    }
                }
                
                // Create new item
                Item item = new Item();
                item.setId(UUID.randomUUID().toString());
                item.setName(name);
                item.setVariationName(variationName);
                item.setSku(sku);
                item.setDescription(description);
                item.setCategory(category);
                item.setGtin(gtin);
                item.setPrice(price);
                item.setQuantity(quantity);
                item.setAlertEnabled(alertEnabled);
                item.setAlertThreshold(alertThreshold);
                
                items.add(item);
                importedCount++;
            }
            
            // Save imported items
            if (importedCount > 0) {
                saveItems();
            }
            
            Log.d(TAG, "Imported " + importedCount + " items from CSV");
        } catch (IOException e) {
            Log.e(TAG, "Error importing items from CSV: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing CSV reader: " + e.getMessage(), e);
                }
            }
        }
        
        return importedCount;
    }
    
    /**
     * Parse a CSV line, handling quoted fields that may contain commas
     */
    private String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                // If we're already in quotes and the next char is also a quote, it's an escaped quote
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++; // Skip the next quote
                } else {
                    // Toggle quote state
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                // End of field
                result.add(field.toString());
                field = new StringBuilder();
            } else {
                field.append(c);
            }
        }
        
        // Add the last field
        result.add(field.toString());
        
        return result.toArray(new String[0]);
    }
}
