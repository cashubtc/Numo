package com.electricdreams.numo.feature.items.handlers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Handles all animations for ItemSelectionActivity.
 * 
 * Uses Apple-style fluid animations with spring interpolators for a premium feel.
 * The basket uses height-based animation for smooth expand/collapse transitions.
 */
class SelectionAnimationHandler(
    private val basketSection: CardView,
    private val checkoutContainer: View
) {
    
    // Basket views (set via setBasketViews)
    private var basketHeader: ConstraintLayout? = null
    private var basketExpandableContent: LinearLayout? = null
    private var basketChevron: ImageView? = null
    
    // State
    private var isExpanded: Boolean = false
    private var isAnimating: Boolean = false
    
    // Cached height for animation
    private var expandedContentHeight: Int = 0
    
    companion object {
        // Animation durations
        private const val EXPAND_DURATION = 400L
        private const val COLLAPSE_DURATION = 350L
        private const val BASKET_FADE_IN_DURATION = 350L
        private const val BASKET_FADE_OUT_DURATION = 250L
        private const val CHECKOUT_SHOW_DURATION = 400L
        private const val CHECKOUT_HIDE_DURATION = 200L
        private const val QUANTITY_BOUNCE_DURATION = 100L
    }
    
    // Apple's iOS spring animation curve - critically damped with slight overshoot
    private val appleSpringInterpolator = PathInterpolator(0.175f, 0.885f, 0.32f, 1.1f)
    
    // Smooth ease-out for exits
    private val appleEaseOut = PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)
    
    // iOS-style ease-in-out
    private val appleEaseInOut = PathInterpolator(0.42f, 0.0f, 0.58f, 1.0f)

    /**
     * Set the basket views for expand/collapse animation.
     */
    fun setBasketViews(
        header: ConstraintLayout,
        expandableContent: LinearLayout,
        chevron: ImageView
    ) {
        basketHeader = header
        basketExpandableContent = expandableContent
        basketChevron = chevron
        
        // Initialize state - collapsed
        isExpanded = false
        expandableContent.visibility = View.GONE
        chevron.rotation = 0f
    }
    
    /**
     * Check if basket is currently expanded.
     */
    fun isBasketExpanded(): Boolean = isExpanded
    
    /**
     * Toggle between collapsed and expanded states with smooth Apple-like animation.
     */
    fun toggleBasketExpansion() {
        if (isAnimating) return
        
        if (isExpanded) {
            collapseBasket()
        } else {
            expandBasket()
        }
    }
    
    /**
     * Expand the basket with smooth height animation.
     * 
     * Animation breakdown:
     * 1. Measure content height
     * 2. Set visibility and height to 0
     * 3. Animate height from 0 to measured with spring curve
     * 4. Rotate chevron 180° with spring
     * 5. Fade in content as it expands
     */
    fun expandBasket() {
        if (isExpanded || isAnimating) return
        val content = basketExpandableContent ?: return
        val chevron = basketChevron ?: return
        
        isAnimating = true
        isExpanded = true
        
        // Measure the content height by making it visible briefly
        content.visibility = View.VISIBLE
        content.alpha = 0f
        content.measure(
            View.MeasureSpec.makeMeasureSpec(basketSection.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        expandedContentHeight = content.measuredHeight
        
        // Start with height 0
        val params = content.layoutParams
        params.height = 0
        content.layoutParams = params
        content.alpha = 1f
        
        // Height animation with spring
        val heightAnimator = ValueAnimator.ofInt(0, expandedContentHeight).apply {
            duration = EXPAND_DURATION
            interpolator = appleSpringInterpolator
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                val layoutParams = content.layoutParams
                layoutParams.height = value
                content.layoutParams = layoutParams
            }
        }
        
        // Chevron rotation with spring (0 → 180)
        val chevronAnimator = ObjectAnimator.ofFloat(chevron, View.ROTATION, 0f, 180f).apply {
            duration = EXPAND_DURATION
            interpolator = appleSpringInterpolator
        }
        
        // Content fade in (slightly delayed for polish)
        val fadeAnimator = ObjectAnimator.ofFloat(content, View.ALPHA, 0f, 1f).apply {
            duration = EXPAND_DURATION / 2
            startDelay = EXPAND_DURATION / 6
            interpolator = appleEaseOut
        }
        
        AnimatorSet().apply {
            playTogether(heightAnimator, chevronAnimator, fadeAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Reset to wrap_content so it adapts if items change
                    val finalParams = content.layoutParams
                    finalParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    content.layoutParams = finalParams
                    isAnimating = false
                }
            })
            start()
        }
    }
    
    /**
     * Collapse the basket with smooth height animation.
     * 
     * Animation breakdown:
     * 1. Capture current content height
     * 2. Animate height to 0 with ease-out
     * 3. Rotate chevron back to 0° with spring
     * 4. Fade out content as it collapses
     * 5. Set visibility GONE at end
     */
    fun collapseBasket() {
        if (!isExpanded || isAnimating) return
        val content = basketExpandableContent ?: return
        val chevron = basketChevron ?: return
        
        isAnimating = true
        isExpanded = false
        
        // Capture current height
        val currentHeight = content.height
        
        // Lock height to current value (no longer wrap_content)
        val params = content.layoutParams
        params.height = currentHeight
        content.layoutParams = params
        
        // Height animation (current → 0)
        val heightAnimator = ValueAnimator.ofInt(currentHeight, 0).apply {
            duration = COLLAPSE_DURATION
            interpolator = appleEaseInOut
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                val layoutParams = content.layoutParams
                layoutParams.height = value
                content.layoutParams = layoutParams
            }
        }
        
        // Chevron rotation with spring (180 → 0)
        val chevronAnimator = ObjectAnimator.ofFloat(chevron, View.ROTATION, 180f, 0f).apply {
            duration = COLLAPSE_DURATION
            interpolator = appleSpringInterpolator
        }
        
        // Content fade out (quick at the start)
        val fadeAnimator = ObjectAnimator.ofFloat(content, View.ALPHA, 1f, 0f).apply {
            duration = COLLAPSE_DURATION / 2
            interpolator = appleEaseOut
        }
        
        AnimatorSet().apply {
            playTogether(heightAnimator, chevronAnimator, fadeAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    content.visibility = View.GONE
                    content.alpha = 1f
                    // Reset height to wrap_content for next expansion
                    val finalParams = content.layoutParams
                    finalParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    content.layoutParams = finalParams
                    isAnimating = false
                }
            })
            start()
        }
    }
    
    /**
     * Reset to collapsed state without animation.
     */
    fun resetToCollapsedState() {
        isExpanded = false
        isAnimating = false
        
        basketExpandableContent?.apply {
            visibility = View.GONE
            alpha = 1f
            val params = layoutParams
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams = params
        }
        basketChevron?.rotation = 0f
    }

    /**
     * Smooth fade-in animation for basket section appearing.
     */
    fun animateBasketSectionIn() {
        resetToCollapsedState()
        
        basketSection.visibility = View.VISIBLE
        basketSection.alpha = 0f
        basketSection.translationY = 30f
        basketSection.scaleX = 0.97f
        basketSection.scaleY = 0.97f

        val fadeIn = ObjectAnimator.ofFloat(basketSection, View.ALPHA, 0f, 1f)
        val slideUp = ObjectAnimator.ofFloat(basketSection, View.TRANSLATION_Y, 30f, 0f)
        val scaleX = ObjectAnimator.ofFloat(basketSection, View.SCALE_X, 0.97f, 1f)
        val scaleY = ObjectAnimator.ofFloat(basketSection, View.SCALE_Y, 0.97f, 1f)

        AnimatorSet().apply {
            playTogether(fadeIn, slideUp, scaleX, scaleY)
            duration = BASKET_FADE_IN_DURATION
            interpolator = appleSpringInterpolator
            start()
        }
    }

    /**
     * Smooth fade-out animation for basket section disappearing.
     */
    fun animateBasketSectionOut() {
        val fadeOut = ObjectAnimator.ofFloat(basketSection, View.ALPHA, 1f, 0f)
        val slideDown = ObjectAnimator.ofFloat(basketSection, View.TRANSLATION_Y, 0f, 20f)
        val scaleX = ObjectAnimator.ofFloat(basketSection, View.SCALE_X, 1f, 0.98f)
        val scaleY = ObjectAnimator.ofFloat(basketSection, View.SCALE_Y, 1f, 0.98f)

        AnimatorSet().apply {
            playTogether(fadeOut, slideDown, scaleX, scaleY)
            duration = BASKET_FADE_OUT_DURATION
            interpolator = appleEaseOut
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    basketSection.visibility = View.GONE
                    basketSection.translationY = 0f
                    basketSection.scaleX = 1f
                    basketSection.scaleY = 1f
                    resetToCollapsedState()
                }
            })
            start()
        }
    }

    /**
     * Animate checkout button appearance/disappearance.
     */
    fun animateCheckoutButton(show: Boolean) {
        if (show) {
            animateCheckoutIn()
        } else {
            animateCheckoutOut()
        }
    }

    private fun animateCheckoutIn() {
        checkoutContainer.visibility = View.VISIBLE
        checkoutContainer.alpha = 0f
        checkoutContainer.translationY = 50f
        checkoutContainer.scaleX = 0.94f
        checkoutContainer.scaleY = 0.94f

        val fadeIn = ObjectAnimator.ofFloat(checkoutContainer, View.ALPHA, 0f, 1f)
        val slideUp = ObjectAnimator.ofFloat(checkoutContainer, View.TRANSLATION_Y, 50f, 0f)
        val scaleX = ObjectAnimator.ofFloat(checkoutContainer, View.SCALE_X, 0.94f, 1f)
        val scaleY = ObjectAnimator.ofFloat(checkoutContainer, View.SCALE_Y, 0.94f, 1f)

        AnimatorSet().apply {
            playTogether(fadeIn, slideUp, scaleX, scaleY)
            duration = CHECKOUT_SHOW_DURATION
            interpolator = appleSpringInterpolator
            start()
        }
    }

    private fun animateCheckoutOut() {
        val fadeOut = ObjectAnimator.ofFloat(checkoutContainer, View.ALPHA, 1f, 0f)
        val slideDown = ObjectAnimator.ofFloat(checkoutContainer, View.TRANSLATION_Y, 0f, 30f)
        val scaleX = ObjectAnimator.ofFloat(checkoutContainer, View.SCALE_X, 1f, 0.97f)
        val scaleY = ObjectAnimator.ofFloat(checkoutContainer, View.SCALE_Y, 1f, 0.97f)

        AnimatorSet().apply {
            playTogether(fadeOut, slideDown, scaleX, scaleY)
            duration = CHECKOUT_HIDE_DURATION
            interpolator = appleEaseOut
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    checkoutContainer.visibility = View.GONE
                    checkoutContainer.translationY = 0f
                    checkoutContainer.scaleX = 1f
                    checkoutContainer.scaleY = 1f
                }
            })
            start()
        }
    }

    /**
     * Animate quantity change with a bounce effect.
     */
    fun animateQuantityChange(quantityView: TextView) {
        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(quantityView, View.SCALE_X, 1f, 1.15f),
                ObjectAnimator.ofFloat(quantityView, View.SCALE_Y, 1f, 1.15f)
            )
            duration = QUANTITY_BOUNCE_DURATION
        }
        
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(quantityView, View.SCALE_X, 1.15f, 1f),
                ObjectAnimator.ofFloat(quantityView, View.SCALE_Y, 1.15f, 1f)
            )
            duration = QUANTITY_BOUNCE_DURATION
            interpolator = appleSpringInterpolator
        }
        
        AnimatorSet().apply {
            playSequentially(scaleUp, scaleDown)
            start()
        }
    }

    // ----- Visibility State Helpers -----

    fun isBasketSectionVisible(): Boolean = basketSection.visibility == View.VISIBLE

    fun isCheckoutContainerVisible(): Boolean = checkoutContainer.visibility == View.VISIBLE
}
