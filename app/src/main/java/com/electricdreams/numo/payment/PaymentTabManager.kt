package com.electricdreams.numo.payment

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.Resources
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.electricdreams.numo.R

/**
 * Manages the payment method tab UI (Unified vs Cashu vs Lightning).
 *
 * Uses a sliding pill indicator behind text-only tabs.
 */
class PaymentTabManager(
    private val tabContainer: FrameLayout,
    private val tabIndicator: View,

    private val unifiedTab: LinearLayout,
    private val cashuTab: LinearLayout,
    private val lightningTab: LinearLayout,

    private val unifiedTabText: TextView,
    private val cashuTabText: TextView,
    private val lightningTabText: TextView,

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

    interface TabSelectionListener {
        fun onTabSelected(tab: PaymentTab)
    }

    private var listener: TabSelectionListener? = null
    private var currentTab: PaymentTab? = null
    private var previousTab: PaymentTab? = null
    private var isLaidOut = false

    fun setup(listener: TabSelectionListener) {
        this.listener = listener

        unifiedTab.setOnClickListener { selectTab(PaymentTab.UNIFIED) }
        cashuTab.setOnClickListener { selectTab(PaymentTab.CASHU) }
        lightningTab.setOnClickListener { selectTab(PaymentTab.LIGHTNING) }

        // Default selection before layout
        currentTab = PaymentTab.UNIFIED

        tabContainer.post {
            isLaidOut = true
            val tabWidth = unifiedTab.width
            val lp = tabIndicator.layoutParams as FrameLayout.LayoutParams
            lp.width = tabWidth
            tabIndicator.layoutParams = lp

            // Apply the current tab state without animation
            val tab = currentTab ?: PaymentTab.UNIFIED
            currentTab = null // reset so selectTab doesn't short-circuit
            selectTab(tab, animate = false)
        }
    }

    fun selectTab(tab: PaymentTab, animate: Boolean = true) {
        if (currentTab == tab) return
        previousTab = currentTab
        currentTab = tab

        if (!isLaidOut) return

        val whiteColor = resources.getColor(R.color.color_bg_white, theme)
        val secondaryColor = resources.getColor(R.color.color_text_secondary, theme)

        val targetTab = when (tab) {
            PaymentTab.UNIFIED -> unifiedTab
            PaymentTab.CASHU -> cashuTab
            PaymentTab.LIGHTNING -> lightningTab
        }
        val targetX = targetTab.left.toFloat()

        if (animate) {
            ObjectAnimator.ofFloat(tabIndicator, "translationX", targetX).apply {
                duration = 250
                interpolator = FastOutSlowInInterpolator()
                start()
            }
            animateColors(tab, whiteColor, secondaryColor)
        } else {
            tabIndicator.translationX = targetX
            applyColors(tab, whiteColor, secondaryColor)
        }

        // QR container visibility
        unifiedQrContainer.visibility = View.INVISIBLE
        cashuQrContainer.visibility = View.INVISIBLE
        lightningQrContainer.visibility = View.INVISIBLE

        when (tab) {
            PaymentTab.UNIFIED -> unifiedQrContainer.visibility = View.VISIBLE
            PaymentTab.CASHU -> cashuQrContainer.visibility = View.VISIBLE
            PaymentTab.LIGHTNING -> lightningQrContainer.visibility = View.VISIBLE
        }

        listener?.onTabSelected(tab)
    }

    fun getCurrentTab(): PaymentTab = currentTab ?: PaymentTab.UNIFIED

    private fun applyColors(selectedTab: PaymentTab, whiteColor: Int, secondaryColor: Int) {
        unifiedTabText.setTextColor(if (selectedTab == PaymentTab.UNIFIED) whiteColor else secondaryColor)
        cashuTabText.setTextColor(if (selectedTab == PaymentTab.CASHU) whiteColor else secondaryColor)
        lightningTabText.setTextColor(if (selectedTab == PaymentTab.LIGHTNING) whiteColor else secondaryColor)
    }

    private fun animateColors(selectedTab: PaymentTab, whiteColor: Int, secondaryColor: Int) {
        val evaluator = ArgbEvaluator()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val toWhite = evaluator.evaluate(fraction, secondaryColor, whiteColor) as Int
                val toGray = evaluator.evaluate(fraction, whiteColor, secondaryColor) as Int

                // Selected tab fades to white
                when (selectedTab) {
                    PaymentTab.UNIFIED -> unifiedTabText.setTextColor(toWhite)
                    PaymentTab.CASHU -> cashuTabText.setTextColor(toWhite)
                    PaymentTab.LIGHTNING -> lightningTabText.setTextColor(toWhite)
                }

                // Previously selected tab fades to gray
                when (previousTab) {
                    PaymentTab.UNIFIED -> unifiedTabText.setTextColor(toGray)
                    PaymentTab.CASHU -> cashuTabText.setTextColor(toGray)
                    PaymentTab.LIGHTNING -> lightningTabText.setTextColor(toGray)
                    null -> {}
                }
            }
            start()
        }
    }
}
