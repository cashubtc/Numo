package com.electricdreams.numo.feature.items

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.BasketManager
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.ItemManager
import com.electricdreams.numo.core.util.SavedBasketManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.baskets.SavedBasketsActivity
import com.electricdreams.numo.feature.baskets.BasketNamesManager
import com.electricdreams.numo.feature.items.adapters.SelectionBasketAdapter
import com.electricdreams.numo.feature.items.adapters.SelectionItemsAdapter
import com.electricdreams.numo.feature.items.handlers.BasketUIHandler
import com.electricdreams.numo.feature.items.handlers.CheckoutHandler
import com.electricdreams.numo.feature.items.handlers.ItemSearchHandler
import com.electricdreams.numo.feature.items.handlers.SelectionAnimationHandler

/**
 * Activity for selecting items and adding them to a basket for checkout.
 * Supports search, quantity adjustments, custom variations, saved baskets, and checkout flow.
 */
class ItemSelectionActivity : AppCompatActivity() {

    // ----- Managers -----
    private lateinit var itemManager: ItemManager
    private lateinit var basketManager: BasketManager
    private lateinit var savedBasketManager: SavedBasketManager
    private lateinit var bitcoinPriceWorker: BitcoinPriceWorker
    private lateinit var currencyManager: CurrencyManager

    // ----- Views -----
    private lateinit var mainScrollView: NestedScrollView
    private lateinit var toolbarTitle: TextView
    private lateinit var savedBasketsButton: TextView
    private lateinit var searchInput: EditText
    private lateinit var scanButton: ImageButton
    private lateinit var clearFiltersButton: ImageButton
    private lateinit var categoryBadge: TextView
    private lateinit var categoryChipsContainer: FlexboxLayout
    private lateinit var basketSection: CardView
    private lateinit var basketRecyclerView: RecyclerView
    private lateinit var basketTotalView: TextView
    private lateinit var basketItemCountView: TextView
    private lateinit var clearBasketButton: TextView
    private lateinit var itemsRecyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var noResultsView: LinearLayout
    private lateinit var checkoutContainer: LinearLayout
    private lateinit var saveButtonContainer: CardView
    private lateinit var saveButton: Button
    private lateinit var checkoutButton: Button
    private lateinit var bottomSpacer: View
    private lateinit var bottomBasketCheckoutContainer: LinearLayout
    private var originalBottomSpacerHeight: Int = 0
    
    // ----- Basket Header & Expandable Views -----
    private lateinit var basketHeader: ConstraintLayout
    private lateinit var basketExpandableContent: LinearLayout
    private lateinit var basketChevron: ImageView

    // ----- Category State -----
    private var categoryChipViews: MutableMap<String, TextView> = mutableMapOf()

    // ----- Adapters -----
    private lateinit var itemsAdapter: SelectionItemsAdapter
    private lateinit var basketAdapter: SelectionBasketAdapter

    // ----- Handlers -----
    private lateinit var animationHandler: SelectionAnimationHandler
    private lateinit var basketUIHandler: BasketUIHandler
    private lateinit var searchHandler: ItemSearchHandler
    private lateinit var checkoutHandler: CheckoutHandler

    // ----- Activity Result Launchers -----
    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == CheckoutScannerActivity.RESULT_BASKET_UPDATED) {
            refreshBasket()
            itemsAdapter.notifyDataSetChanged()
        }
    }
    
    private val savedBasketsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == SavedBasketsActivity.RESULT_BASKET_LOADED) {
            val basketId = result.data?.getStringExtra(SavedBasketsActivity.EXTRA_BASKET_ID)
            if (basketId != null) {
                loadSavedBasket(basketId)
            }
        }
    }

    companion object {
        const val EXTRA_BASKET_ID = "basket_id"
    }

    // ----- Lifecycle -----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_selection)

        initializeManagers()
        initializeViews()
        initializeHandlers()
        initializeAdapters()
        setupRecyclerViews()
        setupClickListeners()

        // Check if we're loading a saved basket from intent
        val basketIdFromIntent = intent.getStringExtra(EXTRA_BASKET_ID)
        if (basketIdFromIntent != null) {
            loadSavedBasket(basketIdFromIntent)
        }

        // Load initial data
        searchHandler.loadItems()
        refreshBasket()
        updateEditingState()

        bitcoinPriceWorker.start()
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case items were modified
        searchHandler.loadItems()
        refreshBasket()
        updateEditingState()
    }

    // ----- Initialization -----

    private fun initializeManagers() {
        itemManager = ItemManager.getInstance(this)
        basketManager = BasketManager.getInstance()
        savedBasketManager = SavedBasketManager.getInstance(this)
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this)
        currencyManager = CurrencyManager.getInstance(this)
    }

    private fun initializeViews() {
        findViewById<View>(R.id.back_button).setOnClickListener { 
            handleBackPress()
        }

        mainScrollView = findViewById(R.id.main_scroll_view)
        toolbarTitle = findViewById(R.id.toolbar_title)
        savedBasketsButton = findViewById(R.id.saved_baskets_button)
        searchInput = findViewById(R.id.search_input)
        scanButton = findViewById(R.id.scan_button)
        clearFiltersButton = findViewById(R.id.clear_filters_button)
        categoryBadge = findViewById(R.id.category_badge)
        categoryChipsContainer = findViewById(R.id.category_chips_container)
        basketSection = findViewById(R.id.basket_section)
        basketRecyclerView = findViewById(R.id.basket_recycler_view)
        basketTotalView = findViewById(R.id.basket_total)
        basketItemCountView = findViewById(R.id.basket_item_count)
        clearBasketButton = findViewById(R.id.clear_basket_button)
        itemsRecyclerView = findViewById(R.id.items_recycler_view)
        emptyView = findViewById(R.id.empty_view)
        noResultsView = findViewById(R.id.no_results_view)
        checkoutContainer = findViewById(R.id.checkout_container)
        saveButtonContainer = findViewById(R.id.save_button_container)
        saveButton = findViewById(R.id.save_button)
        checkoutButton = findViewById(R.id.checkout_button)
        bottomSpacer = findViewById(R.id.bottom_spacer)
        bottomBasketCheckoutContainer = findViewById(R.id.bottom_basket_checkout_container)
        originalBottomSpacerHeight = bottomSpacer.layoutParams.height
        
        // Basket header and expandable content
        basketHeader = findViewById(R.id.basket_header)
        basketExpandableContent = findViewById(R.id.basket_expandable_content)
        basketChevron = findViewById(R.id.basket_chevron)
    }

    private fun initializeHandlers() {
        animationHandler = SelectionAnimationHandler(
            basketSection = basketSection,
            checkoutContainer = checkoutContainer
        )
        
        // Set up basket views for expand/collapse animation
        animationHandler.setBasketViews(
            header = basketHeader,
            expandableContent = basketExpandableContent,
            chevron = basketChevron
        )

        basketUIHandler = BasketUIHandler(
            basketManager = basketManager,
            currencyManager = currencyManager,
            basketTotalView = basketTotalView,
            checkoutButton = checkoutButton,
            animationHandler = animationHandler,
            onBasketUpdated = { 
                basketAdapter.updateItems(basketManager.getBasketItems())
                updateEditingState()
            }
        )
        
        // Set up item count view for the UI handler
        basketUIHandler.setItemCountView(basketItemCountView)

        searchHandler = ItemSearchHandler(
            itemManager = itemManager,
            searchInput = searchInput,
            itemsRecyclerView = itemsRecyclerView,
            emptyView = emptyView,
            noResultsView = noResultsView,
            onItemsFiltered = { items -> itemsAdapter.updateItems(items) },
            onFilterStateChanged = { hasActiveFilters -> updateFilterButtonState(hasActiveFilters) }
        )

        checkoutHandler = CheckoutHandler(
            activity = this,
            basketManager = basketManager,
            currencyManager = currencyManager,
            bitcoinPriceWorker = bitcoinPriceWorker
        )
    }

    private fun initializeAdapters() {
        itemsAdapter = SelectionItemsAdapter(
            context = this,
            basketManager = basketManager,
            mainScrollView = mainScrollView,
            onQuantityChanged = { refreshBasket() },
            onQuantityAnimation = { quantityView -> animationHandler.animateQuantityChange(quantityView) }
        )

        basketAdapter = SelectionBasketAdapter(
            currencyManager = currencyManager,
            onItemRemoved = { itemId -> handleItemRemoved(itemId) }
        )
    }

    private fun setupRecyclerViews() {
        itemsRecyclerView.layoutManager = LinearLayoutManager(this)
        itemsRecyclerView.adapter = itemsAdapter

        basketRecyclerView.layoutManager = LinearLayoutManager(this)
        basketRecyclerView.adapter = basketAdapter
    }

    private fun setupClickListeners() {
        scanButton.setOnClickListener {
            val intent = Intent(this, CheckoutScannerActivity::class.java)
            scannerLauncher.launch(intent)
        }

        clearFiltersButton.setOnClickListener {
            clearAllFilters()
        }

        clearBasketButton.setOnClickListener {
            showClearBasketDialog()
        }

        checkoutButton.setOnClickListener {
            // Save basket before checkout if not already saved, or update existing
            val basketId = ensureBasketSaved()
            if (basketId != null) {
                checkoutHandler.savedBasketId = basketId
            }
            checkoutHandler.proceedToCheckout()
        }
        
        saveButton.setOnClickListener {
            saveCurrentBasket()
        }
        
        savedBasketsButton.setOnClickListener {
            openSavedBaskets()
        }

        // Setup search focus listener to show/hide category chips
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && searchHandler.hasCategories()) {
                showCategoryChips()
            }
        }

        // Category badge click to remove category filter
        categoryBadge.setOnClickListener {
            selectCategory(null)
        }
        
        // ----- Basket Expand/Collapse: Single tap target on header -----
        basketHeader.setOnClickListener {
            animationHandler.toggleBasketExpansion()
            updateBottomSpacer()
        }
    }

    // ----- Saved Baskets -----
    
    /**
     * Ensure basket is saved before checkout.
     * - If editing existing basket: update and return its ID
     * - If new basket: save silently and return new ID
     * @return The basket ID, or null if basket is empty
     */
    private fun ensureBasketSaved(): String? {
        if (basketManager.getBasketItems().isEmpty()) return null
        
        val existingBasket = savedBasketManager.getCurrentEditingBasket()
        
        return if (existingBasket != null) {
            // Update existing basket
            savedBasketManager.saveCurrentBasket(existingBasket.name, basketManager)
            existingBasket.id
        } else {
            // Create new basket silently (no dialog, just save with timestamp name)
            val basket = savedBasketManager.saveCurrentBasket(null, basketManager)
            basket.id
        }
    }
    
    private fun loadSavedBasket(basketId: String) {
        if (savedBasketManager.loadBasketForEditing(basketId, basketManager)) {
            itemsAdapter.syncQuantitiesFromBasket()
            refreshBasket()
            updateEditingState()
        }
    }
    
    private fun openSavedBaskets() {
        val intent = Intent(this, SavedBasketsActivity::class.java)
        savedBasketsLauncher.launch(intent)
    }
    
    private fun saveCurrentBasket() {
        if (savedBasketManager.isEditingExistingBasket()) {
            // Update existing basket
            val basket = savedBasketManager.getCurrentEditingBasket()
            savedBasketManager.saveCurrentBasket(basket?.name, basketManager)
            savedBasketManager.clearEditingState()
            basketManager.clearBasket()
            itemsAdapter.clearAllQuantities()
            refreshBasket()
            finish()
        } else {
            // Show dialog for new basket name
            showSaveBasketDialog()
        }
    }
    
    private fun showSaveBasketDialog() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_Numo_Dialog)
            .setView(R.layout.dialog_save_basket)
            .create()

        // Configure window for centered dialog
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Ensure keyboard adjusts properly
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.setOnShowListener {
            val editText = dialog.findViewById<EditText>(R.id.basket_name_input)
            val saveButton = dialog.findViewById<View>(R.id.save_button)
            val closeButton = dialog.findViewById<View>(R.id.close_button)
            val presetsContainer = dialog.findViewById<com.google.android.flexbox.FlexboxLayout>(R.id.preset_names_container)

            // Setup preset chips
            setupPresetChips(presetsContainer, editText)

            saveButton?.setOnClickListener {
                val name = editText?.text.toString().trim().takeIf { it.isNotEmpty() }
                savedBasketManager.saveCurrentBasket(name, basketManager)
                savedBasketManager.clearEditingState()
                basketManager.clearBasket()
                itemsAdapter.clearAllQuantities()
                refreshBasket()
                dialog.dismiss()
                finish()
            }

            closeButton?.setOnClickListener {
                dialog.dismiss()
            }

            // Show keyboard for input
            editText?.requestFocus()
            editText?.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }

        dialog.show()
    }
    
    private fun updateEditingState() {
        val isEditing = savedBasketManager.isEditingExistingBasket()
        val basket = savedBasketManager.getCurrentEditingBasket()
        val hasItems = basketManager.getBasketItems().isNotEmpty()
        
        // Update title
        if (isEditing && basket != null) {
            val index = savedBasketManager.getBasketIndex(basket.id)
            val displayName = basket.getDisplayName(index)
            toolbarTitle.text = getString(R.string.item_selection_editing_basket, displayName)
        } else {
            toolbarTitle.text = getString(R.string.item_selection_toolbar_title)
        }
        
        // Show/hide save button whenever there are items in the basket
        saveButtonContainer.visibility = if (hasItems) View.VISIBLE else View.GONE
    }
    
    private fun handleBackPress() {
        if (savedBasketManager.isEditingExistingBasket() && basketManager.getBasketItems().isNotEmpty()) {
            // Ask to save changes
            AlertDialog.Builder(this)
                .setTitle(R.string.item_selection_save_basket)
                .setMessage("Save changes to this basket?")
                .setPositiveButton(R.string.common_save) { _, _ ->
                    saveCurrentBasket()
                }
                .setNegativeButton("Discard") { _, _ ->
                    savedBasketManager.clearEditingState()
                    basketManager.clearBasket()
                    finish()
                }
                .setNeutralButton(R.string.common_cancel, null)
                .show()
        } else {
            savedBasketManager.clearEditingState()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPress()
    }

    // ----- Actions -----

    private fun refreshBasket() {
        basketUIHandler.refreshBasket()
        updateBottomSpacer()
    }

    /**
     * Ensure the scrollable content leaves enough space at the bottom so it
     * doesn't get covered by the basket/checkout container.
     */
    private fun updateBottomSpacer() {
        // Run after layout so we have accurate heights
        bottomBasketCheckoutContainer.post {
            val hasBasketOrCheckout =
                animationHandler.isBasketSectionVisible() || animationHandler.isCheckoutContainerVisible()

            val targetHeight = if (hasBasketOrCheckout) {
                max(bottomBasketCheckoutContainer.height, originalBottomSpacerHeight)
            } else {
                originalBottomSpacerHeight
            }

            val params = bottomSpacer.layoutParams
            if (params.height != targetHeight) {
                params.height = targetHeight
                bottomSpacer.layoutParams = params
            }
        }
    }

    private fun handleItemRemoved(itemId: String) {
        basketManager.removeItem(itemId)
        itemsAdapter.resetItemQuantity(itemId)
        refreshBasket()
    }

    private fun showClearBasketDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.item_selection_dialog_clear_basket_title)
            .setMessage(R.string.item_selection_dialog_clear_basket_message)
            .setPositiveButton(R.string.item_selection_dialog_clear_basket_positive) { _, _ ->
                // Reset to collapsed state before clearing (for next time basket appears)
                animationHandler.resetToCollapsedState()
                basketManager.clearBasket()
                itemsAdapter.clearAllQuantities()
                refreshBasket()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // ----- Category Filtering -----

    /**
     * Build and populate the category chips from available categories.
     */
    private fun buildCategoryChips() {
        categoryChipsContainer.removeAllViews()
        categoryChipViews.clear()

        val categories = searchHandler.getCategories()
        if (categories.isEmpty()) return

        val inflater = LayoutInflater.from(this)
        val chipSpacingH = resources.getDimensionPixelSize(R.dimen.space_s)
        val chipSpacingV = resources.getDimensionPixelSize(R.dimen.space_xs)

        categories.forEach { category ->
            val chip = inflater.inflate(R.layout.item_category_chip, categoryChipsContainer, false) as TextView
            chip.text = category
            chip.isSelected = false

            // Add margin between chips using FlexboxLayout.LayoutParams
            val params = FlexboxLayout.LayoutParams(
                FlexboxLayout.LayoutParams.WRAP_CONTENT,
                FlexboxLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, chipSpacingH, chipSpacingV)
            }
            chip.layoutParams = params

            chip.setOnClickListener {
                val isCurrentlySelected = chip.isSelected
                if (isCurrentlySelected) {
                    selectCategory(null)
                } else {
                    selectCategory(category)
                }
            }

            categoryChipsContainer.addView(chip)
            categoryChipViews[category] = chip
        }
    }

    /**
     * Show category chips with animation.
     */
    private fun showCategoryChips() {
        if (!searchHandler.hasCategories()) return

        buildCategoryChips()
        updateCategoryChipSelection()

        if (categoryChipsContainer.visibility == View.VISIBLE) return

        categoryChipsContainer.alpha = 0f
        categoryChipsContainer.translationY = -20f
        categoryChipsContainer.visibility = View.VISIBLE

        val fadeIn = ObjectAnimator.ofFloat(categoryChipsContainer, "alpha", 0f, 1f)
        val slideDown = ObjectAnimator.ofFloat(categoryChipsContainer, "translationY", -20f, 0f)

        AnimatorSet().apply {
            playTogether(fadeIn, slideDown)
            duration = 200
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    /**
     * Hide category chips with animation.
     */
    private fun hideCategoryChips() {
        if (categoryChipsContainer.visibility != View.VISIBLE) return

        val fadeOut = ObjectAnimator.ofFloat(categoryChipsContainer, "alpha", 1f, 0f)
        val slideUp = ObjectAnimator.ofFloat(categoryChipsContainer, "translationY", 0f, -20f)

        AnimatorSet().apply {
            playTogether(fadeOut, slideUp)
            duration = 150
            interpolator = DecelerateInterpolator()
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    categoryChipsContainer.visibility = View.GONE
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {}
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
            start()
        }
    }

    /**
     * Select a category to filter by.
     */
    private fun selectCategory(category: String?) {
        searchHandler.setSelectedCategory(category)
        updateCategoryBadge(category)
        updateCategoryChipSelection()
    }

    /**
     * Update the category badge in the search bar.
     */
    private fun updateCategoryBadge(category: String?) {
        if (category != null) {
            categoryBadge.text = category
            categoryBadge.visibility = View.VISIBLE

            // Animate badge appearance
            categoryBadge.alpha = 0f
            categoryBadge.scaleX = 0.8f
            categoryBadge.scaleY = 0.8f

            val fadeIn = ObjectAnimator.ofFloat(categoryBadge, "alpha", 0f, 1f)
            val scaleX = ObjectAnimator.ofFloat(categoryBadge, "scaleX", 0.8f, 1f)
            val scaleY = ObjectAnimator.ofFloat(categoryBadge, "scaleY", 0.8f, 1f)

            AnimatorSet().apply {
                playTogether(fadeIn, scaleX, scaleY)
                duration = 150
                interpolator = DecelerateInterpolator()
                start()
            }
        } else {
            categoryBadge.visibility = View.GONE
        }
    }

    /**
     * Update the visual selection state of category chips.
     */
    private fun updateCategoryChipSelection() {
        val selectedCategory = searchHandler.getSelectedCategory()

        categoryChipViews.forEach { (category, chip) ->
            val isSelected = category == selectedCategory
            chip.isSelected = isSelected

            if (isSelected) {
                chip.setTextColor(ContextCompat.getColor(this, R.color.color_bg_white))
            } else {
                chip.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary))
            }
        }
    }

    /**
     * Update the scan/clear button visibility based on filter state.
     */
    private fun updateFilterButtonState(hasActiveFilters: Boolean) {
        if (hasActiveFilters) {
            if (scanButton.visibility == View.VISIBLE) {
                // Cross-fade transition
                scanButton.animate()
                    .alpha(0f)
                    .setDuration(100)
                    .withEndAction {
                        scanButton.visibility = View.GONE
                        clearFiltersButton.visibility = View.VISIBLE
                        clearFiltersButton.alpha = 0f
                        clearFiltersButton.animate()
                            .alpha(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
            }
        } else {
            if (clearFiltersButton.visibility == View.VISIBLE) {
                // Cross-fade transition
                clearFiltersButton.animate()
                    .alpha(0f)
                    .setDuration(100)
                    .withEndAction {
                        clearFiltersButton.visibility = View.GONE
                        scanButton.visibility = View.VISIBLE
                        scanButton.alpha = 0f
                        scanButton.animate()
                            .alpha(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
            }
        }
    }

    /**
     * Clear all active filters.
     */
    private fun clearAllFilters() {
        searchHandler.clearAllFilters()
        updateCategoryBadge(null)
        updateCategoryChipSelection()
        hideCategoryChips()
        searchInput.clearFocus()
        
        // Hide keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ----- Preset Names Chips -----

    /**
     * Setup preset name chips in the save basket dialog.
     * When clicked, the preset name fills the input field.
     */
    private fun setupPresetChips(
        container: com.google.android.flexbox.FlexboxLayout?,
        editText: EditText?
    ) {
        if (container == null || editText == null) return

        val basketNamesManager = BasketNamesManager.getInstance(this)
        val presets = basketNamesManager.getPresetNames()

        if (presets.isEmpty()) {
            container.visibility = View.GONE
            return
        }

        container.removeAllViews()
        container.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        val chipSpacingH = resources.getDimensionPixelSize(R.dimen.space_xs)
        val chipSpacingV = resources.getDimensionPixelSize(R.dimen.space_xs)

        presets.forEach { name ->
            val chip = inflater.inflate(R.layout.item_basket_name_chip, container, false) as TextView
            chip.text = name

            // Add margin between chips
            val params = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT,
                com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, chipSpacingH, chipSpacingV)
            }
            chip.layoutParams = params

            chip.setOnClickListener {
                editText.setText(name)
                editText.setSelection(name.length)
            }

            container.addView(chip)
        }
    }
}
