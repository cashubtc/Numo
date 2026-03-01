package com.electricdreams.numo.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import com.electricdreams.numo.core.model.Item
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Manager class for handling the merchant's catalog items.
 *
 * Kotlin version of the original Java ItemManager.
 */
class ItemManager private constructor(context: Context) {

    companion object {
        private const val TAG = "ItemManager"
        private const val PREFS_NAME = "ItemManagerPrefs"
        private const val KEY_ITEM_LIST = "items_list"

        @Volatile
        private var instance: ItemManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): ItemManager {
            if (instance == null) {
                instance = ItemManager(context.applicationContext)
            }
            return instance as ItemManager
        }
    }

    private val context: Context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val items: MutableList<Item> = mutableListOf()

    init {
        loadItems()
    }

    /**
     * Load items from SharedPreferences.
     */
    private fun loadItems() {
        items.clear()
        val itemsJson = prefs.getString(KEY_ITEM_LIST, "")

        if (!itemsJson.isNullOrEmpty()) {
            try {
                val array = JSONArray(itemsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val item = Item().apply {
                        id = obj.getString("id")
                        name = obj.getString("name")
                        price = obj.getDouble("price")

                        // UUID - generate if missing (migration for old items)
                        uuid = if (!obj.isNull("uuid")) {
                            obj.getString("uuid")
                        } else {
                            UUID.randomUUID().toString()
                        }

                        if (!obj.isNull("variationName")) {
                            variationName = obj.getString("variationName")
                        }
                        if (!obj.isNull("sku")) {
                            sku = obj.getString("sku")
                        }
                        if (!obj.isNull("description")) {
                            description = obj.getString("description")
                        }
                        if (!obj.isNull("category")) {
                            category = obj.getString("category")
                        }
                        if (!obj.isNull("gtin")) {
                            gtin = obj.getString("gtin")
                        }
                        if (!obj.isNull("quantity")) {
                            quantity = obj.getInt("quantity")
                        }
                        if (!obj.isNull("alertEnabled")) {
                            alertEnabled = obj.getBoolean("alertEnabled")
                        }
                        if (!obj.isNull("alertThreshold")) {
                            alertThreshold = obj.getInt("alertThreshold")
                        }
                        if (!obj.isNull("imagePath")) {
                            imagePath = obj.getString("imagePath")
                        }
                        // New fields for sats/fiat pricing
                        if (!obj.isNull("priceSats")) {
                            priceSats = obj.getLong("priceSats")
                        }
                        if (!obj.isNull("priceType")) {
                            priceType = try {
                                com.electricdreams.numo.core.model.PriceType.valueOf(obj.getString("priceType"))
                            } catch (e: IllegalArgumentException) {
                                com.electricdreams.numo.core.model.PriceType.FIAT
                            }
                        }
                        if (!obj.isNull("trackInventory")) {
                            trackInventory = obj.getBoolean("trackInventory")
                        }
                        // VAT fields
                        if (!obj.isNull("vatEnabled")) {
                            vatEnabled = obj.getBoolean("vatEnabled")
                        }
                        if (!obj.isNull("vatRate")) {
                            vatRate = obj.getInt("vatRate")
                        }
                    }

                    items.add(item)
                }

                Log.d(TAG, "Loaded ${items.size} items from storage")
            } catch (e: JSONException) {
                Log.e(TAG, "Error loading items: ${e.message}", e)
            }
        }
    }

    /**
     * Save items to SharedPreferences.
     */
    private fun saveItems() {
        try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("uuid", item.uuid)
                    put("name", item.name)
                    put("price", item.price)

                    item.variationName?.let { put("variationName", it) }
                    item.sku?.let { put("sku", it) }
                    item.description?.let { put("description", it) }
                    item.category?.let { put("category", it) }
                    item.gtin?.let { put("gtin", it) }

                    put("quantity", item.quantity)
                    put("alertEnabled", item.alertEnabled)
                    put("alertThreshold", item.alertThreshold)
                    item.imagePath?.let { put("imagePath", it) }
                    
                    // New fields for sats/fiat pricing
                    put("priceSats", item.priceSats)
                    put("priceType", item.priceType.name)
                    put("trackInventory", item.trackInventory)
                    
                    // VAT fields
                    put("vatEnabled", item.vatEnabled)
                    put("vatRate", item.vatRate)
                }
                array.put(obj)
            }

            prefs.edit().putString(KEY_ITEM_LIST, array.toString()).apply()
            Log.d(TAG, "Saved ${items.size} items to storage")
        } catch (e: JSONException) {
            Log.e(TAG, "Error saving items: ${e.message}", e)
        }
    }

    /**
     * Get all items in the catalog.
     * @return List of items.
     */
    fun getAllItems(): List<Item> = ArrayList(items)

    /**
     * Find an item by its Gtin (barcode).
     * @param gtin Gtin to search for.
     * @return Item if found, null otherwise.
     */
    fun findItemByGtin(gtin: String): Item? {
        return items.find { it.gtin?.equals(gtin, ignoreCase = true) == true }
    }

    /**
     * Check if a Gtin already exists in the catalog.
     * @param gtin Gtin to check.
     * @param excludeItemId Optional item ID to exclude from the check (for editing existing items).
     * @return true if Gtin exists (and belongs to a different item), false otherwise.
     */
    fun isGtinDuplicate(gtin: String, excludeItemId: String? = null): Boolean {
        if (gtin.isBlank()) return false
        return items.any { item ->
            item.gtin?.equals(gtin, ignoreCase = true) == true && item.id != excludeItemId
        }
    }

    /**
     * Check if a SKU already exists in the catalog.
     * @param sku SKU to check.
     * @param excludeItemId Optional item ID to exclude from the check (for editing existing items).
     * @return true if SKU exists (and belongs to a different item), false otherwise.
     */
    fun isSkuDuplicate(sku: String, excludeItemId: String? = null): Boolean {
        if (sku.isBlank()) return false
        return items.any { item ->
            item.sku?.equals(sku, ignoreCase = true) == true && item.id != excludeItemId
        }
    }

    /**
     * Get all unique categories from existing items.
     * @return Sorted list of unique category names (non-null, non-empty).
     */
    fun getAllCategories(): List<String> {
        return items
            .mapNotNull { it.category }
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .sorted()
    }

    /**
     * Search items by name, SKU, or variation.
     * @param query Search query.
     * @return List of matching items.
     */
    fun searchItems(query: String): List<Item> {
        if (query.isBlank()) return ArrayList(items)
        
        val lowerQuery = query.lowercase().trim()
        return items.filter { item ->
            item.name?.lowercase()?.contains(lowerQuery) == true ||
            item.sku?.lowercase()?.contains(lowerQuery) == true ||
            item.variationName?.lowercase()?.contains(lowerQuery) == true ||
            item.category?.lowercase()?.contains(lowerQuery) == true
        }
    }

    /**
     * Add an item to the catalog.
     * @param item Item to add.
     * @return true if added successfully, false if already exists.
     */
    fun addItem(item: Item): Boolean {
        if (item.id.isNullOrEmpty()) {
            item.id = UUID.randomUUID().toString()
        }

        // Check if item with the same ID already exists
        if (items.any { it.id == item.id }) {
            return false
        }

        items.add(item)
        saveItems()
        return true
    }

    /**
     * Update an existing item.
     * @param item Item to update.
     * @return true if updated successfully, false if not found.
     */
    fun updateItem(item: Item): Boolean {
        for (i in items.indices) {
            if (items[i].id == item.id) {
                items[i] = item
                saveItems()
                return true
            }
        }
        return false
    }

    /**
     * Remove an item from the catalog.
     * @param itemId ID of the item to remove.
     * @return true if removed successfully, false if not found.
     */
    fun removeItem(itemId: String): Boolean {
        val index = items.indexOfFirst { it.id == itemId }
        return if (index >= 0) {
            items.removeAt(index)
            saveItems()
            true
        } else {
            false
        }
    }

    /**
     * Clear all items.
     */
    fun clearItems() {
        items.clear()
        saveItems()
    }

    /**
     * Reorder items by moving an item from one position to another.
     * @param fromPosition The current position of the item.
     * @param toPosition The target position to move the item to.
     */
    fun reorderItems(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || fromPosition >= items.size ||
            toPosition < 0 || toPosition >= items.size) {
            return
        }
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        saveItems()
    }

    /**
     * Import items from a CSV file.
     * @param csvFilePath Path to the CSV file.
     * @param clearExisting Whether to clear existing items before importing.
     * @return Number of items imported.
     */
    fun importItemsFromCsv(csvFilePath: String, clearExisting: Boolean): Int {
        if (clearExisting) {
            items.clear()
        }

        var importedCount = 0
        var reader: BufferedReader? = null

        try {
            val file = File(csvFilePath)
            if (!file.exists()) {
                Log.e(TAG, "CSV file not found: $csvFilePath")
                return 0
            }

            reader = BufferedReader(FileReader(file))

            // Skip header lines (first 5 lines based on Square template)
            repeat(5) {
                reader.readLine()
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                // Parse CSV line
                val values = parseCsvLine(currentLine)
                if (values.size < 14) { // Need at least 14 columns for basic item data
                    continue
                }

                // Item data starts at column 1 (index 0 is token)
                val name = values[1]
                val variationName = values[2]
                val sku = values[3]
                val description = values[4]
                val category = values[5]
                val gtin = values[7]

                // Parse price (index 12)
                var price = 0.0
                try {
                    price = values[12].toDouble()
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Invalid price format for item: $name")
                }

                // Parse quantity (index 20)
                var quantity = 0
                if (values.size > 20) {
                    try {
                        quantity = values[20].toInt()
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Invalid quantity format for item: $name")
                    }
                }

                // Parse stock alert (index 22-23)
                var alertEnabled = false
                var alertThreshold = 0

                if (values.size > 22) {
                    alertEnabled = values[22].equals("Y", ignoreCase = true)
                }

                if (values.size > 23) {
                    try {
                        alertThreshold = values[23].toInt()
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Invalid alert threshold format for item: $name")
                    }
                }

                // Create new item
                val item = Item().apply {
                    id = UUID.randomUUID().toString()
                    this.name = name
                    this.variationName = variationName
                    this.sku = sku
                    this.description = description
                    this.category = category
                    this.gtin = gtin
                    this.price = price
                    this.quantity = quantity
                    this.alertEnabled = alertEnabled
                    this.alertThreshold = alertThreshold
                }

                items.add(item)
                importedCount++
            }

            // Save imported items
            if (importedCount > 0) {
                saveItems()
            }

            Log.d(TAG, "Imported $importedCount items from CSV")
        } catch (e: IOException) {
            Log.e(TAG, "Error importing items from CSV: ${e.message}", e)
        } finally {
            try {
                reader?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing CSV reader: ${e.message}", e)
            }
        }

        return importedCount
    }

    /**
     * Export items to a CSV file.
     *
     * @param outputStream The stream to write the CSV data to.
     * @return True if successful, false otherwise.
     */
    fun exportItemsToCsv(outputStream: OutputStream): Boolean {
        try {
            val writer = BufferedWriter(OutputStreamWriter(outputStream))

            // Write 5 header lines to match the Square template import expectations
            val header = arrayOfNulls<String>(24)
            header[0] = "Token"
            header[1] = "Item Name"
            header[2] = "Variation Name"
            header[3] = "SKU"
            header[4] = "Description"
            header[5] = "Category"
            header[7] = "GTIN"
            header[12] = "Price"
            header[20] = "Current Quantity"
            header[22] = "Stock Alert Enabled"
            header[23] = "Stock Alert Threshold"

            val headerString = header.joinToString(",") { it ?: "" }
            writer.write(headerString + "\n")
            writer.write(",,,,,,,,,,,,,,,,,,,,,,,\n")
            writer.write(",,,,,,,,,,,,,,,,,,,,,,,\n")
            writer.write(",,,,,,,,,,,,,,,,,,,,,,,\n")
            writer.write(",,,,,,,,,,,,,,,,,,,,,,,\n")

            for (item in items) {
                val csvLine = arrayOfNulls<String>(24)
                csvLine[0] = "" // Token
                csvLine[1] = item.name ?: ""
                csvLine[2] = item.variationName ?: ""
                csvLine[3] = item.sku ?: ""
                csvLine[4] = item.description ?: ""
                csvLine[5] = item.category ?: ""
                csvLine[7] = item.gtin ?: ""

                // Export price
                if (item.priceType == com.electricdreams.numo.core.model.PriceType.FIAT) {
                    csvLine[12] = item.price.toString()
                } else {
                    csvLine[12] = item.priceSats.toString()
                }

                if (item.trackInventory) {
                    csvLine[20] = item.quantity.toString()
                } else {
                    csvLine[20] = ""
                }

                csvLine[22] = if (item.alertEnabled) "Y" else "N"
                csvLine[23] = item.alertThreshold.toString()

                val formattedLine = csvLine.joinToString(",") { formatCsvField(it ?: "") }
                writer.write(formattedLine + "\n")
            }
            writer.flush()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting CSV: ${e.message}", e)
            return false
        }
    }

    private fun formatCsvField(field: String): String {
        var result = field
        if (result.contains("\"")) {
            result = result.replace("\"", "\"\"")
        }
        if (result.contains(",") || result.contains("\"") || result.contains("\n")) {
            result = "\"$result\""
        }
        return result
    }

    /**
     * Parse a CSV line, handling quoted fields that may contain commas.
     */
    private fun parseCsvLine(line: String): Array<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false

        for (c in line) {
            when {
                c == '"' -> {
                    if (inQuotes && field.isNotEmpty() && field.last() == '"') {
                        // Escaped quote within quotes
                        field.append('"')
                    }
                    inQuotes = !inQuotes
                }
                c == ',' && !inQuotes -> {
                    result.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(c)
            }
        }

        // Add the last field
        result.add(field.toString())

        return result.toTypedArray()
    }

    /**
     * Save an image for an item.
     * @param item Item to save image for.
     * @param imageUri Uri of the image to save.
     * @return true if saved successfully, false if failed.
     */
    fun saveItemImage(item: Item, imageUri: Uri): Boolean {
        return try {
            // Create images directory if it doesn't exist
            val imagesDir = File(context.filesDir, "item_images")
            if (!imagesDir.exists() && !imagesDir.mkdirs()) {
                Log.e(TAG, "Failed to create images directory")
                return false
            }

            // Generate a unique filename based on item ID
            val filename = "item_${item.id}.jpg"
            val imageFile = File(imagesDir, filename)

            // If file already exists, delete it
            if (imageFile.exists() && !imageFile.delete()) {
                Log.e(TAG, "Failed to delete existing image file")
            }

            // Copy the image from the Uri to the file
            val inputStream = context.contentResolver.openInputStream(imageUri)
            if (inputStream != null) {
                inputStream.use { stream ->
                    var bitmap = BitmapFactory.decodeStream(stream)

                    // Scale down if the image is too large
                    if (bitmap.width > 1024 || bitmap.height > 1024) {
                        val maxDimension = maxOf(bitmap.width, bitmap.height)
                        val scale = 1024f / maxDimension
                        val newWidth = (bitmap.width * scale).toInt()
                        val newHeight = (bitmap.height * scale).toInt()
                        bitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    }

                    // Save the image as JPEG with 85% quality
                    FileOutputStream(imageFile).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                        outputStream.flush()
                    }

                    // Update the item's image path
                    item.imagePath = imageFile.absolutePath
                    updateItem(item)

                    return true // Success
                }
            } else {
                Log.e(TAG, "Failed to open input stream for image URI")
                return false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error saving item image: ${e.message}", e)
            false
        }
    }

    /**
     * Save a bitmap directly for an item (used when bitmap is already corrected for rotation).
     * @param item Item to save image for.
     * @param bitmap The bitmap to save.
     * @return true if saved successfully, false if failed.
     */
    fun saveItemImageBitmap(item: Item, bitmap: Bitmap): Boolean {
        return try {
            val imagesDir = File(context.filesDir, "item_images")
            if (!imagesDir.exists() && !imagesDir.mkdirs()) {
                Log.e(TAG, "Failed to create images directory")
                return false
            }

            val filename = "item_${item.id}.jpg"
            val imageFile = File(imagesDir, filename)

            if (imageFile.exists() && !imageFile.delete()) {
                Log.e(TAG, "Failed to delete existing image file")
            }

            // Scale down if necessary
            var finalBitmap = bitmap
            if (bitmap.width > 1024 || bitmap.height > 1024) {
                val maxDimension = maxOf(bitmap.width, bitmap.height)
                val scale = 1024f / maxDimension
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                finalBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            }

            FileOutputStream(imageFile).use { outputStream ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                outputStream.flush()
            }

            item.imagePath = imageFile.absolutePath
            updateItem(item)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error saving item image bitmap: ${e.message}", e)
            false
        }
    }

    /**
     * Delete the image associated with an item.
     * @param item Item whose image should be deleted.
     * @return true if deleted successfully or if no image existed, false if failed.
     */
    fun deleteItemImage(item: Item): Boolean {
        val path = item.imagePath ?: return true

        val imageFile = File(path)
        return if (imageFile.exists()) {
            if (imageFile.delete()) {
                item.imagePath = null
                updateItem(item)
                true
            } else {
                Log.e(TAG, "Failed to delete image file: $path")
                false
            }
        } else {
            // File doesn't exist, just update the item
            item.imagePath = null
            updateItem(item)
            true
        }
    }

    /**
     * Load the image bitmap for an item.
     * @param item Item whose image should be loaded.
     * @return Bitmap of the image, or null if no image or error.
     */
    fun loadItemImage(item: Item): Bitmap? {
        val path = item.imagePath ?: return null

        val imageFile = File(path)
        if (!imageFile.exists()) {
            Log.w(TAG, "Image file not found: $path")
            return null
        }

        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading item image: ${e.message}", e)
            null
        }
    }
}
