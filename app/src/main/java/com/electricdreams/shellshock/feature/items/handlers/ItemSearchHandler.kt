package com.electricdreams.shellshock.feature.items.handlers

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.shellshock.core.model.Item
import com.electricdreams.shellshock.core.util.ItemManager

/**
 * Handles item search and filtering logic for ItemSelectionActivity.
 * Supports both text search and category filtering.
 */
class ItemSearchHandler(
    private val itemManager: ItemManager,
    private val searchInput: EditText,
    private val itemsRecyclerView: RecyclerView,
    private val emptyView: LinearLayout,
    private val noResultsView: LinearLayout,
    private val onItemsFiltered: (List<Item>) -> Unit,
    private val onFilterStateChanged: ((hasActiveFilters: Boolean) -> Unit)? = null
) {

    private var allItems: List<Item> = emptyList()
    private var filteredItems: List<Item> = emptyList()
    private var selectedCategory: String? = null

    init {
        setupSearchListener()
    }

    private fun setupSearchListener() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters()
            }
        })
    }

    /**
     * Load all items from the item manager and update the UI.
     */
    fun loadItems() {
        allItems = itemManager.getAllItems()
        applyFilters()
    }

    /**
     * Get all available categories from items.
     */
    fun getCategories(): List<String> {
        return itemManager.getAllCategories()
    }

    /**
     * Check if any categories exist.
     */
    fun hasCategories(): Boolean {
        return getCategories().isNotEmpty()
    }

    /**
     * Get the currently selected category.
     */
    fun getSelectedCategory(): String? = selectedCategory

    /**
     * Set the selected category filter.
     * Pass null to clear the category filter.
     */
    fun setSelectedCategory(category: String?) {
        selectedCategory = category
        applyFilters()
    }

    /**
     * Clear all filters (search text and category).
     */
    fun clearAllFilters() {
        selectedCategory = null
        searchInput.text.clear()
        applyFilters()
    }

    /**
     * Check if any filters are currently active.
     */
    fun hasActiveFilters(): Boolean {
        return selectedCategory != null || searchInput.text.isNotBlank()
    }

    /**
     * Apply both text search and category filters.
     */
    private fun applyFilters() {
        val query = searchInput.text?.toString() ?: ""
        
        filteredItems = allItems.filter { item ->
            val matchesCategory = selectedCategory == null || 
                item.category?.equals(selectedCategory, ignoreCase = true) == true
            
            val matchesSearch = query.isBlank() || 
                item.name?.lowercase()?.contains(query.lowercase()) == true ||
                item.sku?.lowercase()?.contains(query.lowercase()) == true ||
                item.variationName?.lowercase()?.contains(query.lowercase()) == true ||
                item.category?.lowercase()?.contains(query.lowercase()) == true
            
            matchesCategory && matchesSearch
        }
        
        onItemsFiltered(filteredItems)
        updateEmptyState()
        onFilterStateChanged?.invoke(hasActiveFilters())
    }

    /**
     * Update the empty/no results state based on current items.
     */
    private fun updateEmptyState() {
        val hasItems = allItems.isNotEmpty()
        val hasResults = filteredItems.isNotEmpty()
        val isFiltering = hasActiveFilters()

        when {
            !hasItems -> {
                emptyView.visibility = View.VISIBLE
                noResultsView.visibility = View.GONE
                itemsRecyclerView.visibility = View.GONE
            }
            !hasResults && isFiltering -> {
                emptyView.visibility = View.GONE
                noResultsView.visibility = View.VISIBLE
                itemsRecyclerView.visibility = View.GONE
            }
            else -> {
                emptyView.visibility = View.GONE
                noResultsView.visibility = View.GONE
                itemsRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Get current filtered items.
     */
    fun getFilteredItems(): List<Item> = filteredItems

    /**
     * Get all loaded items.
     */
    fun getAllItems(): List<Item> = allItems
}
