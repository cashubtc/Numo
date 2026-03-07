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
 * Manages the payment method tab UI (Unified vs Cashu vs Lightning).
 *
 * Handles visual state switching between tabs and visibility of QR containers.
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
    private val theme: Resources.Theme
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

    private var listener: TabSelectionListener? = null
    private var currentTab: PaymentTab? = null

    /**
     * Set up tab click listeners.
     */
    fun setup(listener: TabSelectionListener) {
        this.listener = listener

        // Enable layout transitions for smooth text appearance/disappearance
        (unifiedTab.parent as? ViewGroup)?.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
        }
        
        // Default: show Unified
        selectTab(PaymentTab.UNIFIED)

        unifiedTab.setOnClickListener { selectTab(PaymentTab.UNIFIED) }
        cashuTab.setOnClickListener { selectTab(PaymentTab.CASHU) }
        lightningTab.setOnClickListener { selectTab(PaymentTab.LIGHTNING) }
    }

    fun selectTab(tab: PaymentTab) {
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
        
        unifiedTabText.visibility = View.GONE
        cashuTabText.visibility = View.GONE
        lightningTabText.visibility = View.GONE
        
        unifiedTabIcon.visibility = View.VISIBLE
        cashuTabIcon.visibility = View.VISIBLE
        lightningTabIcon.visibility = View.VISIBLE

        unifiedTabText.setTextColor(secondaryColor)
        cashuTabText.setTextColor(secondaryColor)
        lightningTabText.setTextColor(secondaryColor)

        unifiedTabIcon.setTextColor(secondaryColor)
        cashuTabIcon.setColorFilter(secondaryColor)
        lightningTabIcon.setColorFilter(secondaryColor)

        unifiedQrContainer.visibility = View.INVISIBLE
        cashuQrContainer.visibility = View.INVISIBLE
        lightningQrContainer.visibility = View.INVISIBLE

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
        }

        listener?.onTabSelected(tab)
    }

    fun getCurrentTab(): PaymentTab = currentTab ?: PaymentTab.UNIFIED
}
