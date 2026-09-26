package com.electricdreams.numo.payment

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.transition.MaterialFadeThrough

/**
 * Manages the payment method tab UI (Unified vs Cashu vs Lightning).
 *
 * The tabs are a [MaterialButtonToggleGroup], which owns the checked styling; this class maps
 * the checked button to a [PaymentTab] and swaps the visible QR container.
 */
class PaymentTabManager(
    private val toggleGroup: MaterialButtonToggleGroup,
    private val unifiedTab: MaterialButton,
    private val cashuTab: MaterialButton,
    private val lightningTab: MaterialButton,

    private val unifiedQrContainer: View,
    private val cashuQrContainer: View,
    private val lightningQrContainer: View,
    
    private val unifiedQrImageView: View,
    private val unifiedLoadingSpinner: View,
    private val lightningLoadingSpinner: View,
    private val cashuLoadingSpinner: View,
    private val cashuQrImageView: View,
    private val lightningQrImageView: View
) {
    enum class PaymentTab {
        UNIFIED, CASHU, LIGHTNING
    }

    /**
     * Callback for tab selection events.
     */
    interface TabSelectionListener {
        fun onTabSelected(tab: PaymentTab)
    }

    enum class Tab { CASHU, LIGHTNING }

    private var listener: TabSelectionListener? = null
    private var currentTab: PaymentTab? = null

    /**
     * Set up tab selection and long-press listeners.
     */
    fun setup(listener: TabSelectionListener) {
        this.listener = listener

        // Setup long click listeners to change default payment method
        unifiedTab.setOnLongClickListener {
            setDefaultTab(PaymentTab.UNIFIED)
            true
        }
        cashuTab.setOnLongClickListener {
            setDefaultTab(PaymentTab.CASHU)
            true
        }
        lightningTab.setOnLongClickListener {
            setDefaultTab(PaymentTab.LIGHTNING)
            true
        }
        
        // Reorder tabs based on default payment method
        reorderTabs()

        // Default: show the default payment method
        selectTab(DefaultPaymentMethodManager.getInstance(unifiedTab.context).getDefaultPaymentMethod())

        toggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tab = when (checkedId) {
                unifiedTab.id -> PaymentTab.UNIFIED
                cashuTab.id -> PaymentTab.CASHU
                lightningTab.id -> PaymentTab.LIGHTNING
                else -> return@addOnButtonCheckedListener
            }
            // selectTab() updates currentTab before checking, so programmatic selection lands here
            // with an unchanged tab; only a user tap gets the haptic tick.
            if (tab == currentTab) return@addOnButtonCheckedListener
            group.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    HapticFeedbackConstants.SEGMENT_TICK
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                }
            )
            selectTab(tab)
        }
    }
    
    private fun setDefaultTab(tab: PaymentTab) {
        DefaultPaymentMethodManager.getInstance(unifiedTab.context).setDefaultPaymentMethod(tab)
        reorderTabs()
        selectTab(tab)
        android.widget.Toast.makeText(
            unifiedTab.context, 
            unifiedTab.context.getString(com.electricdreams.numo.R.string.payment_request_default_method_set, tab.name.lowercase().replaceFirstChar { it.uppercase() }), 
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    fun reorderTabs() {
        val defaultTab = DefaultPaymentMethodManager.getInstance(unifiedTab.context).getDefaultPaymentMethod()
        
        toggleGroup.removeView(unifiedTab)
        toggleGroup.removeView(cashuTab)
        toggleGroup.removeView(lightningTab)
        
        when (defaultTab) {
            PaymentTab.UNIFIED -> {
                toggleGroup.addView(unifiedTab)
                toggleGroup.addView(cashuTab)
                toggleGroup.addView(lightningTab)
            }
            PaymentTab.CASHU -> {
                toggleGroup.addView(cashuTab)
                toggleGroup.addView(unifiedTab)
                toggleGroup.addView(lightningTab)
            }
            PaymentTab.LIGHTNING -> {
                toggleGroup.addView(lightningTab)
                toggleGroup.addView(unifiedTab)
                toggleGroup.addView(cashuTab)
            }
        }
    }

    fun selectTab(tab: PaymentTab) {
        if (currentTab == tab) return
        val isInitialSelection = currentTab == null
        currentTab = tab

        toggleGroup.check(buttonFor(tab).id)

        // Fade through between QR codes on a switch; the first one appears without motion
        val qrCard = unifiedQrContainer.parent as? ViewGroup
        if (!isInitialSelection && qrCard != null) {
            TransitionManager.beginDelayedTransition(qrCard, MaterialFadeThrough())
        }

        unifiedQrContainer.visibility = if (tab == PaymentTab.UNIFIED) View.VISIBLE else View.INVISIBLE
        cashuQrContainer.visibility = if (tab == PaymentTab.CASHU) View.VISIBLE else View.INVISIBLE
        lightningQrContainer.visibility = if (tab == PaymentTab.LIGHTNING) View.VISIBLE else View.INVISIBLE

        listener?.onTabSelected(tab)
    }

    private fun buttonFor(tab: PaymentTab): MaterialButton = when (tab) {
        PaymentTab.UNIFIED -> unifiedTab
        PaymentTab.CASHU -> cashuTab
        PaymentTab.LIGHTNING -> lightningTab
    }

    fun getCurrentTab(): PaymentTab = currentTab ?: PaymentTab.UNIFIED

    /**
     * Disable a tab (e.g. when BTCPay returns no cashuPR, disable [Tab.CASHU]).
     * Greys it out via the button's disabled state and auto-selects the other tab.
     */
    fun disableTab(tab: Tab) {
        val view = if (tab == Tab.CASHU) cashuTab else lightningTab
        view.isEnabled = false
        if (tab == Tab.CASHU) selectTab(PaymentTab.LIGHTNING) else selectTab(PaymentTab.CASHU)
    }

    /**
     * Check if Lightning tab is currently visible/selected.
     */
    fun isLightningTabSelected(): Boolean = currentTab == PaymentTab.LIGHTNING
}
