package com.electricdreams.shellshock.core.util;

import com.electricdreams.shellshock.core.model.BasketItem;
import com.electricdreams.shellshock.core.model.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Manager class for handling the customer's basket
 */
public class BasketManager {
    private static BasketManager instance;
    private final List<BasketItem> basketItems = new ArrayList<>();
    
    private BasketManager() {
        // Private constructor
    }
    
    public static synchronized BasketManager getInstance() {
        if (instance == null) {
            instance = new BasketManager();
        }
        return instance;
    }
    
    /**
     * Get all items in the basket
     * @return List of basket items
     */
    public List<BasketItem> getBasketItems() {
        return new ArrayList<>(basketItems);
    }
    
    /**
     * Add an item to the basket
     * @param item Item to add
     * @param quantity Quantity to add
     */
    public void addItem(Item item, int quantity) {
        // Check if item is already in basket
        for (BasketItem basketItem : basketItems) {
            if (basketItem.getItem().getId().equals(item.getId())) {
                // Increase quantity
                basketItem.setQuantity(basketItem.getQuantity() + quantity);
                return;
            }
        }
        
        // If not in basket, add new entry
        basketItems.add(new BasketItem(item, quantity));
    }
    
    /**
     * Update quantity for an item in the basket
     * @param itemId ID of the item to update
     * @param quantity New quantity
     * @return true if updated successfully, false if not found or quantity is 0
     */
    public boolean updateItemQuantity(String itemId, int quantity) {
        if (quantity <= 0) {
            return removeItem(itemId);
        }
        
        for (BasketItem basketItem : basketItems) {
            if (basketItem.getItem().getId().equals(itemId)) {
                basketItem.setQuantity(quantity);
                return true;
            }
        }
        
        // If we get here, item is not in basket yet
        return false;
    }
    
    /**
     * Remove an item from the basket
     * @param itemId ID of the item to remove
     * @return true if removed successfully, false if not found
     */
    public boolean removeItem(String itemId) {
        for (int i = 0; i < basketItems.size(); i++) {
            if (basketItems.get(i).getItem().getId().equals(itemId)) {
                basketItems.remove(i);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Clear the basket
     */
    public void clearBasket() {
        basketItems.clear();
    }
    
    /**
     * Get the total number of items in the basket
     */
    public int getTotalItemCount() {
        int count = 0;
        for (BasketItem item : basketItems) {
            count += item.getQuantity();
        }
        return count;
    }
    
    /**
     * Calculate the total price of all items in the basket
     */
    public double getTotalPrice() {
        double total = 0;
        for (BasketItem item : basketItems) {
            total += item.getTotalPrice();
        }
        return total;
    }
    
    /**
     * Calculate the total price in satoshis
     * @param btcPrice Current BTC price in fiat
     */
    public long getTotalSatoshis(double btcPrice) {
        if (btcPrice <= 0) {
            return 0;
        }
        
        double fiatTotal = getTotalPrice();
        double btcAmount = fiatTotal / btcPrice;
        return (long)(btcAmount * 100000000);
    }
}
