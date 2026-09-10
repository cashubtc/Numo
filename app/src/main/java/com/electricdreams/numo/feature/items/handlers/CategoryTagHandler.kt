package com.electricdreams.numo.feature.items.handlers

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.ItemManager
import com.google.android.flexbox.FlexboxLayout

/**
 * Handles category tag creation, selection, and management.
 * Manages the FlexboxLayout of selectable category tags and the "Add New" functionality.
 */
class CategoryTagHandler(
    private val context: Context,
    private val categoryTagsContainer: FlexboxLayout,
    private val newCategoryContainer: LinearLayout,
    private val newCategoryInput: EditText,
    private val btnConfirmCategory: ImageButton,
    private val btnCancelCategory: ImageButton,
    private val categoryInput: EditText,
    private val itemManager: ItemManager
) {
    private var selectedCategory: String? = null
    private var existingCategories: MutableList<String> = mutableListOf()

    /**
     * Initializes the category tag system by loading existing categories and setting up listeners.
     */
    fun initialize() {
        loadExistingCategories()
        setupListeners()
        refreshCategoryTags()
    }

    /**
     * Gets the currently selected category.
     */
    fun getSelectedCategory(): String? = selectedCategory

    /**
     * Sets the selected category (used when loading existing item data).
     */
    fun setSelectedCategory(category: String?) {
        selectedCategory = category
        categoryInput.setText(category ?: "")
        refreshCategoryTags()
    }

    private fun loadExistingCategories() {
        existingCategories = itemManager.getAllCategories().toMutableList()
    }

    private fun setupListeners() {
        btnConfirmCategory.setOnClickListener {
            val newCategory = newCategoryInput.text.toString().trim()
            if (newCategory.isNotEmpty()) {
                // Add to existing categories if not already present
                if (!existingCategories.contains(newCategory)) {
                    existingCategories.add(newCategory)
                    existingCategories.sort()
                }
                selectCategory(newCategory)
                newCategoryContainer.visibility = View.GONE
                newCategoryInput.text.clear()
                refreshCategoryTags()
            }
        }

        btnCancelCategory.setOnClickListener {
            newCategoryContainer.visibility = View.GONE
            newCategoryInput.text.clear()
        }
    }

    private fun refreshCategoryTags() {
        categoryTagsContainer.removeAllViews()

        // Add existing category tags
        for (category in existingCategories) {
            val tagView = createCategoryTag(category, category == selectedCategory)
            categoryTagsContainer.addView(tagView)
        }

        // Add "Add New" button
        val addNewButton = createAddNewCategoryButton()
        categoryTagsContainer.addView(addNewButton)
    }

    private fun createCategoryTag(category: String, isSelected: Boolean): View {
        return TextView(context).apply {
            text = category
            textSize = 14f
            setTextColor(
                if (isSelected) ContextCompat.getColor(context, R.color.color_bg_white)
                else ContextCompat.getColor(context, R.color.color_text_primary)
            )
            background = ContextCompat.getDrawable(
                context,
                if (isSelected) R.drawable.bg_category_tag_selected
                else R.drawable.bg_category_tag
            )

            val params = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 12, 12)
            layoutParams = params

            setOnClickListener {
                if (isSelected) {
                    // Deselect
                    selectCategory(null)
                } else {
                    // Select this category
                    selectCategory(category)
                }
                refreshCategoryTags()
            }
        }
    }

    private fun createAddNewCategoryButton(): View {
        return LayoutInflater.from(context)
            .inflate(R.layout.item_add_category_button, categoryTagsContainer, false).apply {
                setOnClickListener {
                    newCategoryContainer.visibility = View.VISIBLE
                    newCategoryInput.requestFocus()
                }
            }
    }

    private fun selectCategory(category: String?) {
        selectedCategory = category
        categoryInput.setText(category ?: "")
    }
}
