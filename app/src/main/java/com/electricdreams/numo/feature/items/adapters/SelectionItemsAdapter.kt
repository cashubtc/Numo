package com.electricdreams.numo.feature.items.adapters

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Item
import com.electricdreams.numo.core.util.BasketManager
import java.io.File
import java.util.UUID

/**
 * Adapter for displaying selectable items in the item selection screen.
 * Supports quantity changes, custom variations, and basket integration.
 */
class SelectionItemsAdapter(
    private val context: Context,
    private val basketManager: BasketManager,
    private val mainScrollView: NestedScrollView,
    private val onQuantityChanged: () -> Unit,
    private val onQuantityAnimation: (TextView) -> Unit
) : RecyclerView.Adapter<SelectionItemsAdapter.ItemViewHolder>() {

    private var items: MutableList<Item> = mutableListOf()
    private val basketQuantities: MutableMap<String, Int> = mutableMapOf()
    
    // Track which position has variation input expanded
    private var expandedPosition: Int = -1
    
    // Store custom variation items (items created during this session)
    private val customVariationItems: MutableList<Item> = mutableListOf()

    // ----- Public Methods -----

    fun updateItems(newItems: List<Item>) {
        // Combine original items with custom variation items
        items = (newItems + customVariationItems).toMutableList()
        refreshBasketQuantities()
        notifyDataSetChanged()
    }

    fun clearAllQuantities() {
        basketQuantities.clear()
        customVariationItems.clear()
        notifyDataSetChanged()
    }
    
    fun syncQuantitiesFromBasket() {
        refreshBasketQuantities()
        notifyDataSetChanged()
    }

    fun resetItemQuantity(itemId: String) {
        basketQuantities.remove(itemId)
        
        // If it's a custom variation item with 0 quantity, remove it from the list
        if (itemId.startsWith("custom_")) {
            val customItem = customVariationItems.find { it.id == itemId }
            if (customItem != null) {
                customVariationItems.remove(customItem)
                val index = items.indexOfFirst { it.id == itemId }
                if (index >= 0) {
                    items.removeAt(index)
                    notifyItemRemoved(index)
                    return
                }
            }
        }
        notifyDataSetChanged()
    }

    /**
     * Add a custom variation item to the list and basket.
     */
    fun addCustomVariation(baseItem: Item, variationName: String) {
        // Create a new item with custom variation and unique ID
        val customItem = baseItem.copy(
            id = "custom_${baseItem.id}_${UUID.randomUUID().toString().take(8)}",
            uuid = UUID.randomUUID().toString(),
            variationName = variationName
        )
        
        // Add to custom items list
        customVariationItems.add(customItem)
        
        // Find position to insert (right after the base item)
        val baseIndex = items.indexOfFirst { it.id == baseItem.id }
        if (baseIndex >= 0) {
            items.add(baseIndex + 1, customItem)
            notifyItemInserted(baseIndex + 1)
        } else {
            items.add(customItem)
            notifyItemInserted(items.size - 1)
        }
        
        // Add to basket with quantity 1
        basketManager.addItem(customItem, 1)
        basketQuantities[customItem.id!!] = 1
        
        // Refresh basket UI
        onQuantityChanged()
        
        // Collapse the variation input
        collapseVariationInput()
    }

    /**
     * Collapse any open variation input.
     */
    fun collapseVariationInput() {
        // Clear focus before collapsing to prevent NestedScrollView crash
        mainScrollView.clearFocus()
        
        val previousExpanded = expandedPosition
        expandedPosition = -1
        if (previousExpanded >= 0 && previousExpanded < items.size) {
            notifyItemChanged(previousExpanded)
        }
    }

    /**
     * Toggle variation input for an item.
     */
    fun toggleVariationInput(position: Int) {
        // CRITICAL: Clear focus before any view changes to prevent NestedScrollView crash
        mainScrollView.clearFocus()
        
        val previousExpanded = expandedPosition
        
        if (expandedPosition == position) {
            expandedPosition = -1
        } else {
            expandedPosition = position
        }
        
        if (previousExpanded >= 0 && previousExpanded < items.size) {
            notifyItemChanged(previousExpanded)
        }
        if (expandedPosition >= 0) {
            notifyItemChanged(expandedPosition)
        }
    }

    // ----- Private Methods -----

    private fun refreshBasketQuantities() {
        basketQuantities.clear()
        for (basketItem in basketManager.getBasketItems()) {
            basketQuantities[basketItem.item.id!!] = basketItem.quantity
        }
    }

    // ----- RecyclerView.Adapter Implementation -----

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_selection, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]
        val quantity = basketQuantities[item.id] ?: 0
        val isExpanded = position == expandedPosition
        val isCustomVariation = item.id?.startsWith("custom_") == true
        holder.bind(item, quantity, position == items.lastIndex, isExpanded, isCustomVariation, position)
    }

    override fun getItemCount(): Int = items.size

    // ----- ViewHolder -----

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val mainContent: View = itemView.findViewById(R.id.main_content)
        private val nameView: TextView = itemView.findViewById(R.id.item_name)
        private val variationView: TextView = itemView.findViewById(R.id.item_variation)
        private val priceView: TextView = itemView.findViewById(R.id.item_price)
        private val stockView: TextView = itemView.findViewById(R.id.item_quantity)
        private val quantityView: TextView = itemView.findViewById(R.id.basket_quantity)
        private val decreaseButton: ImageButton = itemView.findViewById(R.id.decrease_quantity_button)
        private val increaseButton: ImageButton = itemView.findViewById(R.id.increase_quantity_button)
        private val itemImageView: ImageView = itemView.findViewById(R.id.item_image)
        private val imagePlaceholder: ImageView? = itemView.findViewById(R.id.item_image_placeholder)
        private val divider: View? = itemView.findViewById(R.id.divider)
        
        // Variation input views
        private val variationInputContainer: LinearLayout = itemView.findViewById(R.id.variation_input_container)
        private val variationInput: EditText = itemView.findViewById(R.id.variation_input)
        private val addVariationButton: TextView = itemView.findViewById(R.id.add_variation_button)

        fun bind(
            item: Item, 
            basketQuantity: Int, 
            isLast: Boolean, 
            isExpanded: Boolean, 
            isCustomVariation: Boolean, 
            position: Int
        ) {
            nameView.text = item.name ?: ""

            setupVariationDisplay(item, isCustomVariation)
            priceView.text = item.getFormattedPrice()
            setupStockDisplay(item, isCustomVariation)
            quantityView.text = basketQuantity.toString()
            loadItemImage(item)
            setupButtonStates(item, basketQuantity, isCustomVariation)
            
            // Hide divider on last item or when variation input is expanded
            divider?.visibility = if (isLast || isExpanded) View.GONE else View.VISIBLE

            // Handle variation input container visibility
            setupVariationInput(item, isExpanded, isCustomVariation, position)

            setupClickListeners(item, basketQuantity, isCustomVariation, position)
        }

        private fun setupVariationDisplay(item: Item, isCustomVariation: Boolean) {
            if (!item.variationName.isNullOrEmpty()) {
                variationView.text = item.variationName
                variationView.visibility = View.VISIBLE
                if (isCustomVariation) {
                    variationView.setTextColor(itemView.context.getColor(R.color.color_accent_blue))
                } else {
                    variationView.setTextColor(itemView.context.getColor(R.color.color_text_tertiary))
                }
            } else {
                variationView.visibility = View.GONE
            }
        }

        private fun setupStockDisplay(item: Item, isCustomVariation: Boolean) {
            if (item.trackInventory && !isCustomVariation) {
                stockView.visibility = View.VISIBLE
                stockView.text = "${item.quantity} in stock"
            } else {
                stockView.visibility = View.GONE
            }
        }

        private fun setupButtonStates(item: Item, basketQuantity: Int, isCustomVariation: Boolean) {
            decreaseButton.isEnabled = basketQuantity > 0
            decreaseButton.alpha = if (basketQuantity > 0) 1f else 0.4f

            val hasStock = if (item.trackInventory && !isCustomVariation) item.quantity > basketQuantity else true
            increaseButton.isEnabled = hasStock
            increaseButton.alpha = if (hasStock) 1f else 0.4f
        }

        private fun setupClickListeners(
            item: Item, 
            basketQuantity: Int, 
            isCustomVariation: Boolean, 
            position: Int
        ) {
            val hasStock = if (item.trackInventory && !isCustomVariation) item.quantity > basketQuantity else true

            decreaseButton.setOnClickListener {
                if (basketQuantity > 0) {
                    updateBasketItem(item, basketQuantity - 1, isCustomVariation)
                }
            }

            increaseButton.setOnClickListener {
                if (hasStock) {
                    updateBasketItem(item, basketQuantity + 1, isCustomVariation)
                } else {
                    Toast.makeText(itemView.context, "No more stock available", Toast.LENGTH_SHORT).show()
                }
            }
            
            // Long press to show variation input (only for base items, not custom variations)
            if (!isCustomVariation) {
                mainContent.setOnLongClickListener {
                    itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    toggleVariationInput(position)
                    true
                }
            } else {
                mainContent.setOnLongClickListener(null)
            }
        }

        private fun setupVariationInput(
            item: Item, 
            isExpanded: Boolean, 
            isCustomVariation: Boolean, 
            position: Int
        ) {
            // Don't show variation input for custom variation items
            if (isCustomVariation) {
                variationInputContainer.visibility = View.GONE
                return
            }
            
            if (isExpanded) {
                showVariationInput(item)
            } else {
                hideVariationInput()
            }
        }

        private fun showVariationInput(item: Item) {
            if (variationInputContainer.visibility != View.VISIBLE) {
                // Disable scroll view's descendant focus handling during animation
                mainScrollView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                
                variationInputContainer.visibility = View.VISIBLE
                variationInputContainer.alpha = 0f
                variationInputContainer.translationY = -20f
                
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(variationInputContainer, View.ALPHA, 0f, 1f),
                        ObjectAnimator.ofFloat(variationInputContainer, View.TRANSLATION_Y, -20f, 0f)
                    )
                    duration = 250
                    interpolator = DecelerateInterpolator()
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            mainScrollView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                            variationInput.text.clear()
                            variationInput.requestFocus()
                            Handler(Looper.getMainLooper()).postDelayed({
                                val imm = itemView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(variationInput, InputMethodManager.SHOW_IMPLICIT)
                            }, 50)
                        }
                    })
                    start()
                }
            }
            
            // Setup add button
            addVariationButton.setOnClickListener {
                val variationText = variationInput.text.toString().trim()
                if (variationText.isNotEmpty()) {
                    addCustomVariation(item, variationText)
                    hideKeyboard()
                } else {
                    Toast.makeText(itemView.context, "Enter a variation name", Toast.LENGTH_SHORT).show()
                }
            }
            
            // Handle keyboard "Done" action
            variationInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val variationText = variationInput.text.toString().trim()
                    if (variationText.isNotEmpty()) {
                        addCustomVariation(item, variationText)
                        hideKeyboard()
                    }
                    true
                } else {
                    false
                }
            }
        }

        private fun hideVariationInput() {
            if (variationInputContainer.visibility == View.VISIBLE) {
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(variationInputContainer, View.ALPHA, 1f, 0f),
                        ObjectAnimator.ofFloat(variationInputContainer, View.TRANSLATION_Y, 0f, -10f)
                    )
                    duration = 150
                    interpolator = DecelerateInterpolator()
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            variationInputContainer.visibility = View.GONE
                            variationInputContainer.translationY = 0f
                        }
                    })
                    start()
                }
            } else {
                variationInputContainer.visibility = View.GONE
            }
        }

        private fun hideKeyboard() {
            val imm = itemView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(variationInput.windowToken, 0)
        }

        private fun loadItemImage(item: Item) {
            if (!item.imagePath.isNullOrEmpty()) {
                val imageFile = File(item.imagePath!!)
                if (imageFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    if (bitmap != null) {
                        itemImageView.setImageBitmap(bitmap)
                        imagePlaceholder?.visibility = View.GONE
                        return
                    }
                }
            }
            itemImageView.setImageBitmap(null)
            imagePlaceholder?.visibility = View.VISIBLE
        }

        private fun updateBasketItem(item: Item, newQuantity: Int, isCustomVariation: Boolean) {
            if (newQuantity <= 0) {
                basketManager.removeItem(item.id!!)
                basketQuantities.remove(item.id!!)
                
                // Remove custom variation items from the list when quantity reaches 0
                if (isCustomVariation) {
                    customVariationItems.removeAll { it.id == item.id }
                    val index = items.indexOfFirst { it.id == item.id }
                    if (index >= 0) {
                        items.removeAt(index)
                        // Adjust expanded position if needed
                        if (expandedPosition > index) {
                            expandedPosition--
                        } else if (expandedPosition == index) {
                            expandedPosition = -1
                        }
                        notifyItemRemoved(index)
                        onQuantityChanged()
                        return
                    }
                }
            } else {
                val updated = basketManager.updateItemQuantity(item.id!!, newQuantity)
                if (!updated) {
                    basketManager.addItem(item, newQuantity)
                }
                basketQuantities[item.id!!] = newQuantity
            }

            // Animate quantity change
            quantityView.text = newQuantity.toString()
            onQuantityAnimation(quantityView)

            notifyItemChanged(adapterPosition)
            onQuantityChanged()
        }
    }
}
