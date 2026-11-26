package com.electricdreams.numo.payment

import android.animation.LayoutTransition
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.electricdreams.numo.R

/**
 * Manages the Bitcoin (unified Cashu + Lightning) and optional Card checkout tabs.
 *
 * Legacy Cashu/Lightning-only tabs remain as layout inputs but are hidden and cannot be selected.
 */
class PaymentTabManager(
    private val unifiedTab: LinearLayout,
    private val cashuTab: LinearLayout,
    private val lightningTab: LinearLayout,
    
    private val unifiedTabText: TextView,
    private val cashuTabText: TextView,
    private val lightningTabText: TextView,

    private val unifiedTabIcon: TextView,
    private val cashuTabIcon: ImageView,
    private val lightningTabIcon: ImageView,

    private val unifiedQrContainer: View,
    private val cashuQrContainer: View,
    private val lightningQrContainer: View,
    
    private val unifiedQrImageView: View,
    private val unifiedLoadingSpinner: View,
    private val lightningLoadingSpinner: View,
    private val cashuLoadingSpinner: View,
    private val cashuQrImageView: View,
    private val lightningQrImageView: View,
    
    private val resources: Resources,
    private val theme: Resources.Theme,

    /** Optional Card (Stripe Tap to Pay) tab; hidden unless [setCardTabAvailable] enables it. */
    private val cardTab: LinearLayout? = null,
    private val cardTabText: TextView? = null,
    private val cardTabIcon: ImageView? = null,
    private val cardContainer: View? = null,
) {
    enum class PaymentTab {
        UNIFIED, CASHU, LIGHTNING,

        CARD,
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
     * Set up tab click listeners.
     */
    fun setup(listener: TabSelectionListener) {
        this.listener = listener

        // Enable layout transitions for smooth text appearance/disappearance
        val container = unifiedTab.parent as? ViewGroup
        container?.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
        }
        
        cashuTab.visibility = View.GONE
        lightningTab.visibility = View.GONE
        cashuTab.setOnClickListener(null)
        lightningTab.setOnClickListener(null)

        reorderTabs()

        // Bitcoin uses the unified Cashu + Lightning request and is always the initial method.
        selectTab(PaymentTab.UNIFIED)

        unifiedTab.setOnClickListener { selectTab(PaymentTab.UNIFIED) }
        cardTab?.setOnClickListener { selectTab(PaymentTab.CARD) }
    }

    /** Shows or hides the Card tab. Hiding it while selected falls back to Bitcoin. */
    fun setCardTabAvailable(available: Boolean) {
        val tab = cardTab ?: return
        tab.visibility = if (available) View.VISIBLE else View.GONE
        if (!available && currentTab == PaymentTab.CARD) {
            selectTab(PaymentTab.UNIFIED)
        }
    }

    fun reorderTabs() {
        val container = unifiedTab.parent as? ViewGroup ?: return
        container.removeView(unifiedTab)
        container.removeView(cashuTab)
        container.removeView(lightningTab)
        cardTab?.let { container.removeView(it) }

        container.addView(unifiedTab)
        cardTab?.let { container.addView(it) }
    }

    fun selectTab(tab: PaymentTab) {
        if (tab == PaymentTab.CASHU || tab == PaymentTab.LIGHTNING) {
            selectTab(PaymentTab.UNIFIED)
            return
        }
        if (currentTab == tab) return
        currentTab = tab

        val primaryBg = R.drawable.bg_button_primary_green
        val transparentBg = android.R.color.transparent
        val whiteColor = resources.getColor(R.color.color_bg_white, theme)
        val secondaryColor = resources.getColor(R.color.color_text_secondary, theme)

        // Reset all
        unifiedTab.setBackgroundResource(transparentBg)
        cashuTab.setBackgroundResource(transparentBg)
        lightningTab.setBackgroundResource(transparentBg)
        cardTab?.setBackgroundResource(transparentBg)
        
        unifiedTabText.visibility = View.GONE
        cashuTabText.visibility = View.GONE
        lightningTabText.visibility = View.GONE
        cardTabText?.visibility = View.GONE
        
        unifiedTabIcon.visibility = View.VISIBLE
        cashuTabIcon.visibility = View.VISIBLE
        lightningTabIcon.visibility = View.VISIBLE
        cardTabIcon?.visibility = View.VISIBLE

        unifiedTabText.setTextColor(secondaryColor)
        cashuTabText.setTextColor(secondaryColor)
        lightningTabText.setTextColor(secondaryColor)
        cardTabText?.setTextColor(secondaryColor)

        unifiedTabIcon.setTextColor(secondaryColor)
        cashuTabIcon.setColorFilter(secondaryColor)
        lightningTabIcon.setColorFilter(secondaryColor)
        cardTabIcon?.setColorFilter(secondaryColor)

        unifiedQrContainer.visibility = View.INVISIBLE
        cashuQrContainer.visibility = View.INVISIBLE
        lightningQrContainer.visibility = View.INVISIBLE
        cardContainer?.visibility = View.INVISIBLE

        // Set selected
        when (tab) {
            PaymentTab.UNIFIED -> {
                unifiedTab.setBackgroundResource(primaryBg)
                unifiedTabText.visibility = View.VISIBLE
                unifiedTabText.setTextColor(whiteColor)
                unifiedTabIcon.visibility = View.GONE
                
                unifiedQrContainer.visibility = View.VISIBLE
            }
            PaymentTab.CASHU -> {
                cashuTab.setBackgroundResource(primaryBg)
                cashuTabText.visibility = View.VISIBLE
                cashuTabText.setTextColor(whiteColor)
                cashuTabIcon.visibility = View.GONE
                
                cashuQrContainer.visibility = View.VISIBLE
            }
            PaymentTab.LIGHTNING -> {
                lightningTab.setBackgroundResource(primaryBg)
                lightningTabText.visibility = View.VISIBLE
                lightningTabText.setTextColor(whiteColor)
                lightningTabIcon.visibility = View.GONE
                
                lightningQrContainer.visibility = View.VISIBLE
            }
            PaymentTab.CARD -> {
                cardTab?.setBackgroundResource(primaryBg)
                cardTabText?.visibility = View.VISIBLE
                cardTabText?.setTextColor(whiteColor)
                cardTabIcon?.visibility = View.GONE

                cardContainer?.visibility = View.VISIBLE
            }
        }

        listener?.onTabSelected(tab)
    }

    fun getCurrentTab(): PaymentTab = currentTab ?: PaymentTab.UNIFIED

    /**
     * Disable a legacy tab (e.g. when BTCPay returns no cashuPR, disable [Tab.CASHU]).
     * Greys it out, removes its click listener, and selects the unified Bitcoin tab.
     */
    fun disableTab(tab: Tab) {
        val view = if (tab == Tab.CASHU) cashuTab else lightningTab
        view.isEnabled = false
        view.alpha = 0.35f
        view.setOnClickListener(null)
        selectTab(PaymentTab.UNIFIED)
    }

    /**
     * Check if Lightning tab is currently visible/selected.
     */
    fun isLightningTabSelected(): Boolean = currentTab == PaymentTab.LIGHTNING
}
