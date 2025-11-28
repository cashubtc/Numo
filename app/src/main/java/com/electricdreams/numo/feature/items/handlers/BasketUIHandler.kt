package com.electricdreams.numo.feature.items.handlers

import android.view.View
import com.electricdreams.numo.R
import android.widget.Button
import android.widget.TextView
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BasketManager
import com.electricdreams.numo.core.util.CurrencyManager

/**
 * Handles basket UI updates including total display and checkout button text.
 */
class BasketUIHandler(
    private val basketManager: BasketManager,
    private val currencyManager: CurrencyManager,
    private val basketTotalView: TextView,
    private val checkoutButton: Button,
    private val animationHandler: SelectionAnimationHandler,
    private val onBasketUpdated: () -> Unit
) {

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
     */
    fun updateBasketTotal() {
        val itemCount = basketManager.getTotalItemCount()
        val fiatTotal = basketManager.getTotalPrice()
        val satsTotal = basketManager.getTotalSatsDirectPrice()

        val formattedTotal = if (itemCount > 0) {
            val currencyCode = currencyManager.getCurrentCurrency()
            val currency = Amount.Currency.fromCode(currencyCode)

            when {
                fiatTotal > 0 && satsTotal > 0 -> {
                    val fiatAmount = Amount.fromMajorUnits(fiatTotal, currency)
                    val satsAmount = Amount(satsTotal, Amount.Currency.BTC)
                    "$fiatAmount + $satsAmount"
                }
                satsTotal > 0 -> Amount(satsTotal, Amount.Currency.BTC).toString()
                else -> Amount.fromMajorUnits(fiatTotal, currency).toString()
            }
        } else {
            "0.00"
        }

        basketTotalView.text = formattedTotal
    }

    /**
     * Update the checkout button text with current totals.
     */
    fun updateCheckoutButton() {
        val context = checkoutButton.context
        val fiatTotal = basketManager.getTotalPrice()
        val satsTotal = basketManager.getTotalSatsDirectPrice()
        val currencyCode = currencyManager.getCurrentCurrency()
        val currency = Amount.Currency.fromCode(currencyCode)

        val buttonText = when {
            fiatTotal > 0 && satsTotal > 0 -> {
                val fiatAmount = Amount.fromMajorUnits(fiatTotal, currency)
                val satsAmount = Amount(satsTotal, Amount.Currency.BTC)
                context.getString(R.string.basket_charge_fiat_and_sats, fiatAmount.toString(), satsAmount.toString())
            }
            satsTotal > 0 -> {
                val satsAmount = Amount(satsTotal, Amount.Currency.BTC)
                context.getString(R.string.basket_charge_sats_only, satsAmount.toString())
            }
            else -> {
                val fiatAmount = Amount.fromMajorUnits(fiatTotal, currency)
                context.getString(R.string.basket_charge_fiat_only, fiatAmount.toString())
            }
        }

        checkoutButton.text = buttonText
    }

    /**
     * Get basket items for adapter updates.
     */
    fun getBasketItems() = basketManager.getBasketItems()
}
