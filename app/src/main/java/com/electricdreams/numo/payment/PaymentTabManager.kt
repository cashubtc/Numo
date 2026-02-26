package com.electricdreams.numo.payment

import android.content.res.Resources
import android.view.View
import android.widget.TextView
import com.electricdreams.numo.R

/**
 * Manages the payment method tab UI (Cashu vs Lightning).
 *
 * Handles visual state switching between tabs and visibility of QR containers.
 */
class PaymentTabManager(
    private val cashuTab: TextView,
    private val lightningTab: TextView,
    private val cashuQrContainer: View,
    private val lightningQrContainer: View,
    private val cashuQrImageView: View,
    private val lightningQrImageView: View,
    private val resources: Resources,
    private val theme: Resources.Theme
) {
    /**
     * Callback for tab selection events.
     */
    interface TabSelectionListener {
        /** Called when the Lightning tab is selected */
        fun onLightningTabSelected()
        
        /** Called when the Cashu tab is selected */
        fun onCashuTabSelected()
    }

    enum class Tab { CASHU, LIGHTNING }

    private var listener: TabSelectionListener? = null
    private var isLightningSelected = false

    /**
     * Set up tab click listeners.
     * 
     * @param listener Callback for tab selection events
     */
    fun setup(listener: TabSelectionListener) {
        this.listener = listener
        
        // Default: show Cashu (Nostr) QR
        selectCashuTab()

        lightningTab.setOnClickListener { selectLightningTab() }
        cashuTab.setOnClickListener { selectCashuTab() }
    }

    /**
     * Select the Lightning tab.
     */
    fun selectLightningTab() {
        if (isLightningSelected) return
        isLightningSelected = true
        
        // Visual state
        lightningTab.setTextColor(resources.getColor(R.color.color_bg_white, theme))
        lightningTab.setBackgroundResource(R.drawable.bg_button_primary_green)
        cashuTab.setTextColor(resources.getColor(R.color.color_text_secondary, theme))
        cashuTab.setBackgroundResource(android.R.color.transparent)

        // QR visibility
        lightningQrContainer.visibility = View.VISIBLE
        lightningQrImageView.visibility = View.VISIBLE
        cashuQrContainer.visibility = View.INVISIBLE
        cashuQrImageView.visibility = View.INVISIBLE

        listener?.onLightningTabSelected()
    }

    /**
     * Select the Cashu tab.
     */
    fun selectCashuTab() {
        isLightningSelected = false
        
        // Visual state
        cashuTab.setTextColor(resources.getColor(R.color.color_bg_white, theme))
        cashuTab.setBackgroundResource(R.drawable.bg_button_primary_green)
        lightningTab.setTextColor(resources.getColor(R.color.color_text_secondary, theme))
        lightningTab.setBackgroundResource(android.R.color.transparent)

        // QR visibility
        cashuQrContainer.visibility = View.VISIBLE
        cashuQrImageView.visibility = View.VISIBLE
        lightningQrContainer.visibility = View.INVISIBLE
        lightningQrImageView.visibility = View.INVISIBLE

        // Notify listener that Cashu tab is now selected
        listener?.onCashuTabSelected()
    }

    /**
     * Disable a tab (e.g. when BTCPay returns no cashuPR, disable [Tab.CASHU]).
     * Greys it out, removes its click listener, and auto-selects the other tab.
     */
    fun disableTab(tab: Tab) {
        val view = if (tab == Tab.CASHU) cashuTab else lightningTab
        view.isEnabled = false
        view.alpha = 0.35f
        view.setOnClickListener(null)
        if (tab == Tab.CASHU) selectLightningTab() else selectCashuTab()
    }

    /**
     * Check if Lightning tab is currently visible/selected.
     */
    fun isLightningTabSelected(): Boolean = isLightningSelected
}

