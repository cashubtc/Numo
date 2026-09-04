package com.electricdreams.numo.feature.items.handlers

import android.widget.Button
import android.widget.TextView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.AssetId
import com.electricdreams.numo.core.model.AtomicAmount
import com.electricdreams.numo.core.model.UnitAmountFormatter
import com.electricdreams.numo.core.util.BasketManager
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.util.NetworkUtils

/**
 * Handles basket UI updates including total display and checkout button text.
 * Works with the unified basket card layout that animates height on expand/collapse.
 */
class BasketUIHandler(
    private val basketManager: BasketManager,
    private val currencyManager: CurrencyManager,
    private val basketTotalView: TextView,
    private val checkoutButton: Button,
    private val animationHandler: SelectionAnimationHandler,
    private val onBasketUpdated: () -> Unit
) {
    
    // Item count view in header (set via setItemCountView)
    private var itemCountView: TextView? = null
    
    /**
     * Set the item count view in the basket header.
     */
    fun setItemCountView(view: TextView) {
        itemCountView = view
    }

    /**
     * Refresh basket UI based on current basket state.
     * Handles visibility animations for basket section and checkout button.
     */
    fun refreshBasket() {
        val basketItems = basketManager.getBasketItems()

        if (basketItems.isEmpty()) {
            // Hide basket section with smooth animation
            if (animationHandler.isBasketSectionVisible()) {
                animationHandler.animateBasketSectionOut()
            }
            // Hide checkout button with animation
            if (animationHandler.isCheckoutContainerVisible()) {
                animationHandler.animateCheckoutButton(false)
            }
        } else {
            // Show basket section with smooth animation
            if (!animationHandler.isBasketSectionVisible()) {
                animationHandler.animateBasketSectionIn()
            }
            // Show checkout button with animation
            if (!animationHandler.isCheckoutContainerVisible()) {
                animationHandler.animateCheckoutButton(true)
            }
            updateCheckoutButton()
        }

        updateBasketTotal()
        onBasketUpdated()
    }

    /**
     * Update the basket total display text.
     * Handles mixed fiat and sats pricing.
     * Updates header total and item count.
     */
    fun updateBasketTotal() {
        val itemCount = basketManager.getTotalItemCount()
        val formattedTotal = if (itemCount == 0) {
            "0.00"
        } else {
            runCatching {
                val grouped = linkedMapOf<AssetId, AtomicAmount>()
                basketManager.getPriceLines(currencyManager.getCurrentCurrency()).forEach { line ->
                    val current = grouped[line.amount.asset] ?: AtomicAmount.zero(line.amount.asset)
                    grouped[line.amount.asset] = current + line.amount
                }
                grouped.values.joinToString(" + ") { amount ->
                    UnitAmountFormatter.formatAsset(amount)
                }
            }.getOrDefault("—")
        }

        // Update the header total display
        basketTotalView.text = formattedTotal
        
        // Update item count in header
        updateItemCount(itemCount)
    }
    
    /**
     * Update the item count text in the basket header.
     */
    private fun updateItemCount(count: Int) {
        itemCountView?.let { view ->
            val context = view.context
            view.text = if (count == 1) {
                context.getString(R.string.item_selection_basket_item_count, count)
            } else {
                context.getString(R.string.item_selection_basket_items_count, count)
            }
        }
    }

    /**
     * Update the checkout button text.
     * Total is already visible in the basket, so button just says "Charge".
     * If no mints are configured, the button is disabled to prevent creating
     * payment requests without any available mint.
     */
    fun updateCheckoutButton() {
        val context = checkoutButton.context
        val mintManager = MintManager.getInstance(context)
        val isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)

        checkoutButton.text = context.getString(R.string.item_selection_charge_button)
        val canCharge = mintManager.hasAnyMints() && isNetworkAvailable
        checkoutButton.isEnabled = canCharge
        checkoutButton.alpha = if (canCharge) 1.0f else 0.5f
    }

    /**
     * Get basket items for adapter updates.
     */
    fun getBasketItems() = basketManager.getBasketItems()
}
