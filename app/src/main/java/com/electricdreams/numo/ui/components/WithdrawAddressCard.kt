package com.electricdreams.numo.ui.components

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText

import android.widget.TextView
import com.electricdreams.numo.R
import com.google.android.material.card.MaterialCardView

/**
 * A beautiful, reusable card component for Lightning address input with amount.
 * 
 * Features:
 * - Elegant card design with icon
 * - Lightning address input with validation
 * - Amount input in sats
 * - Pre-fill suggested amount
 * - Continue button with state management
 * - Smooth entrance animations
 */
class WithdrawAddressCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    interface OnContinueListener {
        fun onContinue(address: String, amountSats: Long)
    }

    private var listener: OnContinueListener? = null
    
    private val addressInput: EditText
    private val amountInput: EditText
    private val continueButton: Button

    init {
        LayoutInflater.from(context).inflate(R.layout.component_withdraw_address_card, this, true)
        
        // Setup card styling
        radius = resources.getDimension(R.dimen.card_corner_radius)
        cardElevation = 0f
        setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        
        // Find views
        addressInput = findViewById(R.id.address_input)
        amountInput = findViewById(R.id.amount_input)
        continueButton = findViewById(R.id.continue_button)
        
        setupListeners()
    }

    private fun setupListeners() {
        // Input validation for address
        addressInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateButtonState()
            }
        })
        
        // Input validation for amount
        amountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateButtonState()
            }
        })
        
        // Continue button
        continueButton.setOnClickListener {
            val address = addressInput.text.toString().trim()
            val amountSats = amountInput.text.toString().toLongOrNull()
            
            if (address.isNotBlank() && amountSats != null && amountSats > 0) {
                // Scale animation feedback
                it.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(100)
                    .withEndAction {
                        it.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .withEndAction {
                                listener?.onContinue(address, amountSats)
                            }
                            .start()
                    }
                    .start()
            }
        }
    }
    
    private fun updateButtonState() {
        val hasAddress = !addressInput.text.isNullOrBlank()
        val hasValidAmount = amountInput.text.toString().toLongOrNull()?.let { it > 0 } ?: false

        continueButton.isEnabled = hasAddress && hasValidAmount
    }

    /**
     * Set the listener for continue action
     */
    fun setOnContinueListener(listener: OnContinueListener) {
        this.listener = listener
    }
    
    /**
     * Get the current address
     */
    fun getAddress(): String = addressInput.text.toString().trim()
    
    /**
     * Get the current amount in sats
     */
    fun getAmountSats(): Long? = amountInput.text.toString().toLongOrNull()
    
    /**
     * Set the address (e.g., from preferences)
     */
    fun setAddress(address: String) {
        addressInput.setText(address)
    }
    
    /**
     * Set the suggested amount (e.g., balance - fee buffer)
     */
    fun setSuggestedAmount(amountSats: Long) {
        if (amountSats > 0) {
            amountInput.setText(amountSats.toString())
        }
    }
    
    /**
     * Clear all input fields
     */
    fun clearInputs() {
        addressInput.text?.clear()
        amountInput.text?.clear()
    }
    
    /**
     * Enable or disable the card
     */
    fun setCardEnabled(enabled: Boolean) {
        addressInput.isEnabled = enabled
        amountInput.isEnabled = enabled
        if (enabled) {
            updateButtonState()
        } else {
            continueButton.isEnabled = false
        }
        alpha = if (enabled) 1f else 0.5f
    }
    
    /**
     * Play entrance animation
     */
    fun animateEntrance(delay: Long = 0) {
        alpha = 0f
        translationY = 30f
        animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(350)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
}
