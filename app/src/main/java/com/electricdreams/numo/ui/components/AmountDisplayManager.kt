package com.electricdreams.numo.ui.components

import android.content.Context
import android.view.View
import com.electricdreams.numo.R
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker

/**
 * Manages amount display, formatting, and currency animations for the POS interface.
 */
class AmountDisplayManager(
    private val context: Context,
    private val amountDisplay: TextView,
    private val secondaryAmountDisplay: TextView,
    private val switchCurrencyButton: View,
    private val submitButton: Button,
    private val bitcoinPriceWorker: BitcoinPriceWorker?
) {

    var isUsdInputMode: Boolean = false
        private set
    var requestedAmount: Long = 0
        private set

    enum class AnimationType { NONE, DIGIT_ENTRY, CURRENCY_SWITCH }

    /** Initialize input mode from preferences */
    fun initializeInputMode() {
        val prefs = PreferenceStore.app(context)
        isUsdInputMode = prefs.getBoolean(KEY_INPUT_MODE, false)
    }

    /** Check if we can switch to USD (need price data) */
    fun canSwitchToUsd(): Boolean {
        if (!isUsdInputMode) {
            val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
            if (price <= 0) {
                return false
            }
        }
        return true
    }

    /** Toggle between USD and Satoshi input modes */
    fun toggleInputMode(satoshiInput: StringBuilder, fiatInput: StringBuilder): Boolean {
        if (!canSwitchToUsd()) return false

        // Convert current input value to the other unit before switching
        val currentInputStr = getCurrentInput(satoshiInput, fiatInput).toString()
        if (currentInputStr.isNotEmpty()) {
            try {
                if (isUsdInputMode) {
                    // Currently in fiat mode, converting to satoshi mode
                    val fiatCents = currentInputStr.toLong()
                    val fiatAmount = fiatCents / 100.0
                    val satoshis = fiatToSatoshis(fiatAmount)
                    satoshiInput.clear()
                    satoshiInput.append(satoshis.toString())
                } else {
                    // Currently in satoshi mode, converting to fiat mode
                    val satoshis = currentInputStr.toLong()
                    val fiatAmount = bitcoinPriceWorker?.satoshisToFiat(satoshis) ?: 0.0
                    val fiatCents = (fiatAmount * 100).toLong()
                    fiatInput.clear()
                    fiatInput.append(fiatCents.toString())
                }
            } catch (e: NumberFormatException) {
                // If conversion fails, don't change anything
            }
        }

        isUsdInputMode = !isUsdInputMode
        // Persist the input mode preference
        PreferenceStore.app(context).putBoolean(KEY_INPUT_MODE, isUsdInputMode)

        return true
    }

    /** Update the display with current input values and optional animation */
    fun updateDisplay(
        satoshiInput: StringBuilder,
        fiatInput: StringBuilder,
        animationType: AnimationType
    ) {
        val currentInputStr = getCurrentInput(satoshiInput, fiatInput).toString()
        var amountDisplayText: String
        var secondaryDisplayText: String
        var satsValue: Long = 0
        
        // Check if we have Bitcoin price data
        val hasBitcoinPrice = (bitcoinPriceWorker?.getCurrentPrice() ?: 0.0) > 0

        if (isUsdInputMode) {
            // Input mode: fiat, display fiat as primary, sats as secondary
            val fiatCents = if (currentInputStr.isEmpty()) 0L else currentInputStr.toLong()
            val currencyCode = CurrencyManager.getInstance(context).getCurrentCurrency()
            val currency = Amount.Currency.fromCode(currencyCode)
            
            amountDisplayText = Amount(fiatCents, currency).toString()
            
            // Convert fiat to satoshis for secondary display and requestedAmount
            val fiatAmount = fiatCents / 100.0
            satsValue = fiatToSatoshis(fiatAmount)
            secondaryDisplayText = Amount(satsValue, Amount.Currency.BTC).toString()
        } else {
            // Input mode: satoshi, display sats as primary, fiat as secondary
            satsValue = if (currentInputStr.isEmpty()) 0L else currentInputStr.toLong()
            amountDisplayText = formatAmount(currentInputStr)
            
            if (hasBitcoinPrice) {
                // Show fiat conversion with swap icon
                val fiatValue = bitcoinPriceWorker?.satoshisToFiat(satsValue) ?: 0.0
                secondaryDisplayText = bitcoinPriceWorker?.formatFiatAmount(fiatValue)
                    ?: CurrencyManager.getInstance(context).formatCurrencyAmount(0.0)
                switchCurrencyButton.visibility = View.VISIBLE
            } else {
                // No price data - just show "BTC" without swap icon
                secondaryDisplayText = context.getString(R.string.pos_secondary_amount_btc_label)
                switchCurrencyButton.visibility = View.GONE
            }
        }

        // Update secondary amount display with animation when switching currencies
        if (animationType == AnimationType.CURRENCY_SWITCH) {
            // Animate secondary display in opposite direction of main display
            animateSecondaryCurrencySwitch(secondaryDisplayText, isUsdInputMode)
        } else {
            secondaryAmountDisplay.text = secondaryDisplayText
        }

        // Update main amount display
        if (amountDisplay.text.toString() != amountDisplayText) {
            when (animationType) {
                AnimationType.CURRENCY_SWITCH -> animateCurrencySwitch(amountDisplayText, !isUsdInputMode)
                AnimationType.DIGIT_ENTRY -> animateDigitEntry(amountDisplayText)
                AnimationType.NONE -> amountDisplay.text = amountDisplayText
            }
        }

        // Update submit button
        if (satsValue > 0) {
            // Always show a simple, localized "Charge" label without the amount
            submitButton.text = context.getString(R.string.pos_charge_button)
            submitButton.isEnabled = true
            requestedAmount = satsValue
        } else {
            submitButton.text = context.getString(R.string.pos_charge_button)
            submitButton.isEnabled = false
            requestedAmount = 0
        }
    }

    /** Reset requested amount */
    fun resetRequestedAmount() {
        requestedAmount = 0
    }

    /** Get the current input StringBuilder based on input mode */
    fun getCurrentInput(satoshiInput: StringBuilder, fiatInput: StringBuilder): StringBuilder {
        return if (isUsdInputMode) fiatInput else satoshiInput
    }

    /** Show shake animation on amount display */
    fun shakeAmountDisplay() {
        val shake = object : android.view.animation.Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: android.view.animation.Transformation) {
                val offset = (Math.sin(interpolatedTime * Math.PI * 8) * 10).toFloat()
                t.matrix.setTranslate(offset, 0f)
            }
        }.apply {
            duration = 400
        }
        amountDisplay.startAnimation(shake)
    }

    /** Convert fiat amount (in currency units, not cents) to satoshis */
    private fun fiatToSatoshis(fiatAmount: Double): Long {
        val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
        if (price <= 0) return 0L
        
        val btcAmount = fiatAmount / price
        return (btcAmount * 100_000_000).toLong()
    }

    /** Format amount using Amount class */
    private fun formatAmount(amount: String): String = try {
        val value = if (amount.isEmpty()) 0L else amount.toLong()
        Amount(value, Amount.Currency.BTC).toString()
    } catch (_: NumberFormatException) {
        ""
    }

    /** Animate currency switch transition */
    private fun animateCurrencySwitch(newText: String, isUp: Boolean) {
        amountDisplay.animate().cancel()
        if (amountDisplay.alpha == 0f) {
            amountDisplay.alpha = 1f
            amountDisplay.translationY = 0f
        }
        val exitTranslation = if (isUp) -50f else 50f
        val enterStartTranslation = if (isUp) 50f else -50f
        amountDisplay.animate()
            .alpha(0f)
            .translationY(exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                amountDisplay.text = newText
                amountDisplay.translationY = enterStartTranslation
                amountDisplay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** Animate digit entry */
    private fun animateDigitEntry(newText: String) {
        amountDisplay.animate().cancel()
        amountDisplay.animate()
            .alpha(0.7f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(80)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .withEndAction {
                amountDisplay.text = newText
                amountDisplay.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                    .start()
            }
            .start()
    }

    /** Animate secondary currency switch */
    private fun animateSecondaryCurrencySwitch(newText: String, isUp: Boolean) {
        secondaryAmountDisplay.animate().cancel()
        switchCurrencyButton.animate().cancel()
        
        // Convert 2dp to pixels for the icon offset
        val iconOffsetPx = 2f * context.resources.displayMetrics.density
        
        if (secondaryAmountDisplay.alpha == 0f) {
            secondaryAmountDisplay.alpha = 1f
            secondaryAmountDisplay.translationY = 0f
        }
        if (switchCurrencyButton.alpha == 0f) {
            switchCurrencyButton.alpha = 1f
            switchCurrencyButton.translationY = iconOffsetPx
        }
        
        val exitTranslation = if (isUp) -30f else 30f
        val enterStartTranslation = if (isUp) 30f else -30f
        
        // Get current icon translation (should be iconOffsetPx, but handle case where it's not initialized)
        val currentIconTranslation = switchCurrencyButton.translationY
        val baseIconTranslation = if (currentIconTranslation == 0f) iconOffsetPx else currentIconTranslation
        
        // Animate both text and icon together
        secondaryAmountDisplay.animate()
            .alpha(0f)
            .translationY(exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                secondaryAmountDisplay.text = newText
                secondaryAmountDisplay.translationY = enterStartTranslation
                secondaryAmountDisplay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
            
        // Animate icon with same timing, preserving the 2dp offset
        switchCurrencyButton.animate()
            .alpha(0f)
            .translationY(baseIconTranslation + exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                switchCurrencyButton.translationY = baseIconTranslation + enterStartTranslation
                switchCurrencyButton.animate()
                    .alpha(1f)
                    .translationY(iconOffsetPx)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    companion object {
        private const val KEY_INPUT_MODE = "inputMode" // true = fiat, false = satoshi
    }
}
