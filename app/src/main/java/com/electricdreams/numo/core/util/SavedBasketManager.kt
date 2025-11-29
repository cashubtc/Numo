package com.electricdreams.numo.core.util

import android.content.Context
import android.content.SharedPreferences
import com.electricdreams.numo.core.model.BasketItem
import com.electricdreams.numo.core.model.BasketStatus
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.model.PriceType
import com.electricdreams.numo.core.model.SavedBasket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manager class for persisting saved baskets and archived (paid) baskets.
 * Uses SharedPreferences with JSON serialization.
 */
class SavedBasketManager private constructor(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val savedBaskets: MutableList<SavedBasket> = mutableListOf()
    private val archivedBaskets: MutableList<SavedBasket> = mutableListOf()
    
    // Currently editing basket ID (null if creating new)
    var currentEditingBasketId: String? = null
        private set
    
    companion object {
        private const val PREFS_NAME = "saved_baskets"
        private const val KEY_BASKETS = "baskets"
        private const val KEY_ARCHIVED = "archived_baskets"
        
        @Volatile
        private var instance: SavedBasketManager? = null
        
        @JvmStatic
        fun getInstance(context: Context): SavedBasketManager {
            return instance ?: synchronized(this) {
                instance ?: SavedBasketManager(context.applicationContext).also {
                    instance = it
                    it.loadBaskets()
                }
            }
        }
    }
    
    /**
     * Load saved baskets from storage.
     */
    private fun loadBaskets() {
        savedBaskets.clear()
        archivedBaskets.clear()
        
        // Load active baskets
        val json = prefs.getString(KEY_BASKETS, null)
        if (json != null) {
            try {
                val jsonArray = JSONArray(json)
                for (i in 0 until jsonArray.length()) {
                    val basketJson = jsonArray.getJSONObject(i)
                    savedBaskets.add(deserializeBasket(basketJson))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Load archived baskets
        val archivedJson = prefs.getString(KEY_ARCHIVED, null)
        if (archivedJson != null) {
            try {
                val jsonArray = JSONArray(archivedJson)
                for (i in 0 until jsonArray.length()) {
                    val basketJson = jsonArray.getJSONObject(i)
                    archivedBaskets.add(deserializeBasket(basketJson))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Save baskets to storage.
     */
    private fun saveBaskets() {
        try {
            val jsonArray = JSONArray()
            savedBaskets.forEach { basket ->
                jsonArray.put(serializeBasket(basket))
            }
            prefs.edit().putString(KEY_BASKETS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Save archived baskets to storage.
     */
    private fun saveArchivedBaskets() {
        try {
            val jsonArray = JSONArray()
            archivedBaskets.forEach { basket ->
                jsonArray.put(serializeBasket(basket))
            }
            prefs.edit().putString(KEY_ARCHIVED, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Get all saved (active) baskets.
     */
    fun getSavedBaskets(): List<SavedBasket> = savedBaskets.toList()
    
    /**
     * Get all archived (paid) baskets.
     */
    fun getArchivedBaskets(): List<SavedBasket> = archivedBaskets.sortedByDescending { it.paidAt ?: it.updatedAt }
    
    /**
     * Get a saved basket by ID (searches both active and archived).
     */
    fun getBasket(id: String): SavedBasket? = 
        savedBaskets.find { it.id == id } ?: archivedBaskets.find { it.id == id }
    
    /**
     * Get a basket by its associated payment ID.
     */
    fun getBasketByPaymentId(paymentId: String): SavedBasket? =
        archivedBaskets.find { it.paymentId == paymentId } ?: savedBaskets.find { it.paymentId == paymentId }
    
    /**
     * Get the index of a basket for display name fallback.
     */
    fun getBasketIndex(id: String): Int = savedBaskets.indexOfFirst { it.id == id }
    
    /**
     * Get the index of an archived basket for display name fallback.
     */
    fun getArchivedBasketIndex(id: String): Int = archivedBaskets.indexOfFirst { it.id == id }
    
    /**
     * Save current basket from BasketManager.
     * @param name Optional name for the basket
     * @param basketManager The basket manager containing current items
     * @return The saved basket
     */
    fun saveCurrentBasket(name: String?, basketManager: BasketManager): SavedBasket {
        val items = basketManager.getBasketItems().map { it.copy() }
        
        val existingBasket = currentEditingBasketId?.let { getBasket(it) }
        
        return if (existingBasket != null && existingBasket.isActive()) {
            // Update existing basket
            existingBasket.name = name
            existingBasket.items = items
            existingBasket.updatedAt = System.currentTimeMillis()
            saveBaskets()
            existingBasket
        } else {
            // Create new basket
            val basket = SavedBasket(
                name = name,
                items = items,
                status = BasketStatus.ACTIVE
            )
            savedBaskets.add(basket)
            saveBaskets()
            basket
        }
    }
    
    /**
     * Load a saved basket into BasketManager for editing.
     * @param basketId ID of the basket to load
     * @param basketManager The basket manager to load into
     * @return true if loaded successfully
     */
    fun loadBasketForEditing(basketId: String, basketManager: BasketManager): Boolean {
        val basket = getBasket(basketId) ?: return false
        
        // Can't edit paid baskets
        if (basket.isPaid()) return false
        
        basketManager.clearBasket()
        basket.items.forEach { item ->
            basketManager.addItem(item.item, item.quantity)
        }
        
        currentEditingBasketId = basketId
        return true
    }
    
    /**
     * Clear the current editing state (when starting fresh or after checkout).
     */
    fun clearEditingState() {
        currentEditingBasketId = null
    }
    
    /**
     * Delete a saved basket.
     */
    fun deleteBasket(basketId: String): Boolean {
        val removed = savedBaskets.removeAll { it.id == basketId }
        if (removed) {
            saveBaskets()
            if (currentEditingBasketId == basketId) {
                currentEditingBasketId = null
            }
        }
        return removed
    }
    
    /**
     * Delete an archived basket.
     */
    fun deleteArchivedBasket(basketId: String): Boolean {
        val removed = archivedBaskets.removeAll { it.id == basketId }
        if (removed) {
            saveArchivedBaskets()
        }
        return removed
    }
    
    /**
     * Mark a basket as paid and move it to archive.
     * @param basketId ID of the basket to mark as paid
     * @param paymentId ID of the associated payment
     * @return The updated basket, or null if not found
     */
    fun markBasketAsPaid(basketId: String, paymentId: String): SavedBasket? {
        val basket = savedBaskets.find { it.id == basketId } ?: return null
        
        // Update basket status
        basket.status = BasketStatus.PAID
        basket.paymentId = paymentId
        basket.paidAt = System.currentTimeMillis()
        basket.updatedAt = System.currentTimeMillis()
        
        // Move from active to archived
        savedBaskets.remove(basket)
        archivedBaskets.add(0, basket)
        
        // Clear editing state if this was being edited
        if (currentEditingBasketId == basketId) {
            currentEditingBasketId = null
        }
        
        // Persist changes
        saveBaskets()
        saveArchivedBaskets()
        
        return basket
    }
    
    /**
     * Update the name of a saved basket.
     */
    fun updateBasketName(basketId: String, newName: String?): Boolean {
        val basket = getBasket(basketId) ?: return false
        basket.name = newName
        basket.updatedAt = System.currentTimeMillis()
        
        if (basket.isPaid()) {
            saveArchivedBaskets()
        } else {
            saveBaskets()
        }
        return true
    }
    
    /**
     * Get total count of saved baskets.
     */
    fun getBasketCount(): Int = savedBaskets.size
    
    /**
     * Get total count of archived baskets.
     */
    fun getArchivedBasketCount(): Int = archivedBaskets.size
    
    /**
     * Check if we're currently editing an existing basket.
     */
    fun isEditingExistingBasket(): Boolean = currentEditingBasketId != null
    
    /**
     * Get the basket currently being edited.
     */
    fun getCurrentEditingBasket(): SavedBasket? = currentEditingBasketId?.let { getBasket(it) }
    
    // ----- JSON Serialization -----
    
    private fun serializeBasket(basket: SavedBasket): JSONObject {
        return JSONObject().apply {
            put("id", basket.id)
            put("name", basket.name ?: JSONObject.NULL)
            put("createdAt", basket.createdAt)
            put("updatedAt", basket.updatedAt)
            put("status", basket.status.name)
            put("paymentId", basket.paymentId ?: JSONObject.NULL)
            put("paidAt", basket.paidAt ?: JSONObject.NULL)
            put("items", JSONArray().apply {
                basket.items.forEach { item ->
                    put(serializeBasketItem(item))
                }
            })
        }
    }
    
    private fun deserializeBasket(json: JSONObject): SavedBasket {
        val itemsArray = json.getJSONArray("items")
        val items = mutableListOf<BasketItem>()
        for (i in 0 until itemsArray.length()) {
            items.add(deserializeBasketItem(itemsArray.getJSONObject(i)))
        }
        
        return SavedBasket(
            id = json.getString("id"),
            name = if (json.isNull("name")) null else json.getString("name"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            status = try { 
                BasketStatus.valueOf(json.optString("status", "ACTIVE")) 
            } catch (e: Exception) { 
                BasketStatus.ACTIVE 
            },
            paymentId = if (json.isNull("paymentId")) null else json.optString("paymentId"),
            paidAt = if (json.isNull("paidAt")) null else json.optLong("paidAt"),
            items = items
        )
    }
    
    private fun serializeBasketItem(basketItem: BasketItem): JSONObject {
        return JSONObject().apply {
            put("quantity", basketItem.quantity)
            put("item", serializeItem(basketItem.item))
        }
    }
    
    private fun deserializeBasketItem(json: JSONObject): BasketItem {
        return BasketItem(
            quantity = json.getInt("quantity"),
            item = deserializeItem(json.getJSONObject("item"))
        )
    }
    
    private fun serializeItem(item: Item): JSONObject {
        return JSONObject().apply {
            put("id", item.id ?: JSONObject.NULL)
            put("uuid", item.uuid)
            put("name", item.name ?: JSONObject.NULL)
            put("variationName", item.variationName ?: JSONObject.NULL)
            put("sku", item.sku ?: JSONObject.NULL)
            put("description", item.description ?: JSONObject.NULL)
            put("category", item.category ?: JSONObject.NULL)
            put("gtin", item.gtin ?: JSONObject.NULL)
            put("price", item.price)
            put("priceSats", item.priceSats)
            put("priceType", item.priceType.name)
            put("priceCurrency", item.priceCurrency)
            put("vatEnabled", item.vatEnabled)
            put("vatRate", item.vatRate)
            put("imagePath", item.imagePath ?: JSONObject.NULL)
        }
    }
    
    private fun deserializeItem(json: JSONObject): Item {
        return Item(
            id = if (json.isNull("id")) null else json.getString("id"),
            uuid = json.optString("uuid", java.util.UUID.randomUUID().toString()),
            name = if (json.isNull("name")) null else json.getString("name"),
            variationName = if (json.isNull("variationName")) null else json.optString("variationName"),
            sku = if (json.isNull("sku")) null else json.optString("sku"),
            description = if (json.isNull("description")) null else json.optString("description"),
            category = if (json.isNull("category")) null else json.optString("category"),
            gtin = if (json.isNull("gtin")) null else json.optString("gtin"),
            price = json.optDouble("price", 0.0),
            priceSats = json.optLong("priceSats", 0L),
            priceType = try { PriceType.valueOf(json.optString("priceType", "FIAT")) } catch (e: Exception) { PriceType.FIAT },
            priceCurrency = json.optString("priceCurrency", "USD"),
            vatEnabled = json.optBoolean("vatEnabled", false),
            vatRate = json.optInt("vatRate", 0),
            imagePath = if (json.isNull("imagePath")) null else json.optString("imagePath")
        )
    }
}
