package com.electricdreams.numo.feature.settings

import android.content.Context
import androidx.core.widget.doAfterTextChanged

import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.databinding.ComponentWithdrawAmountBinding

/**
 * Drives the shared amount entry: a sat amount with a Max shortcut, a live fiat and
 * "available" caption, and an inline error when the amount exceeds what can be sent.
 */
class WithdrawAmountField(
    private val context: Context,
    private val binding: ComponentWithdrawAmountBinding,
    private val onChanged: () -> Unit,
) {

    private val priceWorker = BitcoinPriceWorker.getInstance(context)
    private var available: Long = 0
    private var maxSendable: Long = 0
    private var userEdited = false
    private var settingText = false

    init {
        binding.amountLayout.suffixText = Amount.Currency.BTC.symbol
        binding.maxButton.setOnClickListener { setAmount(maxSendable) }
        binding.amountInput.doAfterTextChanged {
            if (!settingText) userEdited = true
            refreshCaption()
            onChanged()
        }
    }

    /** Sats entered, or 0 when the field is empty or not a number. */
    val value: Long
        get() = binding.amountInput.text?.toString()?.toLongOrNull() ?: 0L

    val isValid: Boolean
        get() = value in 1..available

    /**
     * Updates the balance this amount is drawn from. Until the merchant types their
     * own amount, the field follows [prefill] so it never shows a stale suggestion.
     */
    fun setAvailable(available: Long, maxSendable: Long, prefill: Long = maxSendable) {
        this.available = available
        this.maxSendable = maxSendable.coerceAtLeast(0)
        binding.maxButton.isEnabled = this.maxSendable > 0
        if (!userEdited) {
            setAmount(prefill.coerceAtLeast(0), fromUser = false)
        } else {
            refreshCaption()
        }
    }

    fun setError(message: CharSequence?) {
        binding.amountLayout.error = message
    }

    fun setEnabled(enabled: Boolean) {
        binding.amountLayout.isEnabled = enabled
        binding.maxButton.isEnabled = enabled && maxSendable > 0
    }

    private fun setAmount(sats: Long, fromUser: Boolean = true) {
        settingText = !fromUser
        val text = if (sats > 0) sats.toString() else ""
        binding.amountInput.setText(text)
        binding.amountInput.setSelection(text.length)
        settingText = false
    }

    private fun refreshCaption() {
        val sats = value
        val availableText = Amount(available, Amount.Currency.BTC).toString()
        if (sats > available) {
            binding.amountLayout.error =
                context.getString(R.string.withdraw_amount_too_large, availableText)
            return
        }
        binding.amountLayout.error = null
        val fiat = priceWorker.satoshisToFiat(sats)
        binding.amountLayout.helperText = if (sats > 0 && fiat > 0) {
            context.getString(
                R.string.withdraw_amount_fiat_available,
                priceWorker.formatFiatAmount(fiat),
                availableText
            )
        } else {
            context.getString(R.string.withdraw_from_available, availableText)
        }
    }
}
