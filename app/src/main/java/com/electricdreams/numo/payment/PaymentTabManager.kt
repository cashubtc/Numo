package com.electricdreams.numo.payment

import android.view.View

/**
 * Tracks which bitcoin payment request the checkout screen shows.
 *
 * Checkout always shows the unified (Cashu + Lightning) request. The Cashu-only and
 * Lightning-only methods remain as stored preferences but resolve to the unified one.
 */
class PaymentTabManager(
    private val unifiedQrContainer: View,
    private val cashuQrContainer: View,
    private val lightningQrContainer: View,
) {
    enum class PaymentTab { UNIFIED, CASHU, LIGHTNING }

    /**
     * Callback for tab selection events.
     */
    interface TabSelectionListener {
        fun onTabSelected(tab: PaymentTab)
    }

    enum class Tab { CASHU, LIGHTNING }

    private var listener: TabSelectionListener? = null
    private var currentTab: PaymentTab? = null

    fun setup(listener: TabSelectionListener) {
        this.listener = listener
        selectTab(PaymentTab.UNIFIED)
    }

    fun selectTab(tab: PaymentTab) {
        if (tab != PaymentTab.UNIFIED) {
            selectTab(PaymentTab.UNIFIED)
            return
        }
        if (currentTab == tab) return
        currentTab = tab

        cashuQrContainer.visibility = View.INVISIBLE
        lightningQrContainer.visibility = View.INVISIBLE
        unifiedQrContainer.visibility = View.VISIBLE

        listener?.onTabSelected(tab)
    }

    fun getCurrentTab(): PaymentTab = currentTab ?: PaymentTab.UNIFIED

    /** A legacy method that turned out to be unavailable falls back to the unified request. */
    fun disableTab(@Suppress("UNUSED_PARAMETER") tab: Tab) {
        selectTab(PaymentTab.UNIFIED)
    }
}
