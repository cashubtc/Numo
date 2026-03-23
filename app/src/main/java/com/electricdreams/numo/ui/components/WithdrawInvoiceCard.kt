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
 * A beautiful, reusable card component for Lightning invoice input.
 * 
 * Features:
 * - Elegant card design with icon
 * - Multi-line invoice input
 * - Continue button with state management
 * - Smooth entrance animations
 * - Input validation
 */
class WithdrawInvoiceCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    interface OnContinueListener {
        fun onContinue(invoice: String)
    }

    interface OnScanListener {
        fun onScanClicked()
    }

    private var listener: OnContinueListener? = null
    private var scanListener: OnScanListener? = null
    
    private val invoiceInput: EditText
    private val continueButton: Button
    private val scanButton: Button

    init {
        LayoutInflater.from(context).inflate(R.layout.component_withdraw_invoice_card, this, true)
        
        // Setup card styling
        radius = resources.getDimension(R.dimen.card_corner_radius)
        cardElevation = 0f
        setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        
        // Find views
        invoiceInput = findViewById(R.id.invoice_input)
        continueButton = findViewById(R.id.continue_button)
        scanButton = findViewById(R.id.scan_button)
        
        setupListeners()
    }

    private fun setupListeners() {
        // Input validation
        invoiceInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasInput = !s.isNullOrBlank()
                updateButtonState(hasInput)
            }
        })
        
        // Continue button
        continueButton.setOnClickListener {
            val invoice = invoiceInput.text.toString().trim()
            if (invoice.isNotBlank()) {
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
                                listener?.onContinue(invoice)
                            }
                            .start()
                    }
                    .start()
            }
        }
        
        // Scan button
        scanButton.setOnClickListener {
            scanListener?.onScanClicked()
        }
    }
    
    private fun updateButtonState(enabled: Boolean) {
        continueButton.isEnabled = enabled
    }

    /**
     * Set the listener for continue action
     */
    fun setOnContinueListener(listener: OnContinueListener) {
        this.listener = listener
    }

    /**
     * Set the listener for scan action
     */
    fun setOnScanListener(listener: OnScanListener) {
        this.scanListener = listener
    }
    
    /**
     * Get the current invoice text
     */
    fun getInvoice(): String = invoiceInput.text.toString().trim()
    
    /**
     * Set invoice text (e.g., from QR scan)
     */
    fun setInvoice(invoice: String) {
        invoiceInput.setText(invoice)
    }
    
    /**
     * Clear the input field
     */
    fun clearInput() {
        invoiceInput.text?.clear()
    }
    
    /**
     * Enable or disable the card
     */
    fun setCardEnabled(enabled: Boolean) {
        invoiceInput.isEnabled = enabled
        continueButton.isEnabled = enabled && invoiceInput.text.isNotBlank()
        scanButton.isEnabled = enabled
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
