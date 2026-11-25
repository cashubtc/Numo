package com.electricdreams.numo.feature.onboarding

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ItemOnboardingTourPageBinding
import com.electricdreams.numo.databinding.ViewOnboardingTourBinding
import com.electricdreams.numo.payment.card.StripeTerminalManager

/** Continuous product story. One lifecycle-owned clock drives scenes and the page crossfade. */
class OnboardingTourView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val binding = ViewOnboardingTourBinding.inflate(LayoutInflater.from(context), this)
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
        as AccessibilityManager
    private data class Page(val title: Int, val body: Int, val duration: Long, val scene: Int?)

    private val pages = listOf(
        Page(R.string.onboarding_tour_welcome_title, R.string.onboarding_tour_welcome_body, 5600L, 0),
        Page(
            R.string.onboarding_card_payments_title,
            if (StripeTerminalManager.isDeviceSupported(context)) R.string.onboarding_card_payments_body
            else R.string.settings_stripe_card_unsupported,
            8800L, null,
        ),
        Page(R.string.onboarding_tour_tap_title, R.string.onboarding_tour_tap_body, 8800L, 1),
        Page(R.string.onboarding_tour_withdraw_title, R.string.onboarding_tour_withdraw_body, 6400L, 2),
        Page(R.string.onboarding_tour_sales_title, R.string.onboarding_tour_sales_body, 6200L, 3),
    )
    private val checkoutPreview = CheckoutPreviewScreens(context)
    private val holders = mutableSetOf<PageHolder>()
    private var clock: ValueAnimator? = null
    private var active = false
    private var elapsed = 0L
    private var scrolling = false
    private var touching = false
    private var automaticPageChange = false
    private var fadeEntrance = true
    private var motionEnabled = motionAllowed()
    private val entranceEase = PathInterpolator(.23f, 1f, .32f, 1f)
    private val accessibilityListener = AccessibilityManager.TouchExplorationStateChangeListener {
        updatePlayback()
    }

    val currentPage: Int get() = binding.tourPager.currentItem
    val isPlaybackRunning: Boolean get() = clock?.isRunning == true

    init {
        binding.tourPager.adapter = object : RecyclerView.Adapter<PageHolder>() {
            override fun getItemCount() = pages.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
                ItemOnboardingTourPageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                holder.page = position
                val page = pages[position]
                holder.binding.tourTitle.setText(page.title)
                holder.binding.tourDescription.setText(page.body)
                holder.binding.stripeMark.isVisible = page.scene == null
                holder.binding.tourScene.isVisible = page.scene != null
                page.scene?.let { scene ->
                    holder.binding.tourScene.checkoutPreview = checkoutPreview
                    holder.binding.tourScene.page = scene
                }
                ViewCompat.setAccessibilityHeading(holder.binding.tourTitle, true)
                holder.binding.tourScene.timeMillis = sceneTime(position)
            }

            override fun onViewAttachedToWindow(holder: PageHolder) {
                holders.add(holder)
                holder.binding.tourScene.timeMillis = sceneTime(holder.page)
            }

            override fun onViewDetachedFromWindow(holder: PageHolder) {
                holders.remove(holder)
            }
        }
        binding.tourPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.acceptButton.setText(
                    if (pages[position].scene == null) R.string.onboarding_card_payments_skip
                    else R.string.onboarding_tour_start
                )
                stopClock()
                elapsed = 0L
                fadeEntrance = automaticPageChange
                render()
                updatePlayback()
            }

            override fun onPageScrollStateChanged(state: Int) {
                scrolling = state != ViewPager2.SCROLL_STATE_IDLE
                updatePlayback()
            }
        })
        // Native pager actions remain available with TalkBack; there is no visible player chrome.
        ViewCompat.addAccessibilityAction(binding.tourPager, context.getString(R.string.onboarding_tour_next)) { _, _ ->
            advancePage()
            true
        }
        render()
    }

    fun setActive(value: Boolean) {
        active = value
        updatePlayback()
    }

    fun previousPage(): Boolean {
        if (currentPage == 0) return false
        stopClock()
        binding.tourPager.setCurrentItem(currentPage - 1, motionEnabled)
        return true
    }

    internal fun advancePage() {
        stopClock()
        automaticPageChange = true
        // The shared clock has already faded out, so wrap directly to the first page.
        binding.tourPager.setCurrentItem((currentPage + 1) % pages.size, false)
        automaticPageChange = false
    }

    fun savePlaybackState(outState: Bundle) {
        outState.putInt("tour_page", currentPage)
        outState.putLong("tour_elapsed", elapsed)
    }

    fun restorePlaybackState(state: Bundle) {
        stopClock()
        binding.tourPager.setCurrentItem(state.getInt("tour_page").coerceIn(0, pages.lastIndex), false)
        // onPageSelected may have started the destination clock before its saved time is restored.
        stopClock()
        elapsed = state.getLong("tour_elapsed").coerceIn(0L, pages[currentPage].duration)
        fadeEntrance = false
        render()
        updatePlayback()
    }

    private fun updatePlayback() {
        motionEnabled = motionAllowed()
        if (!active || !isAttachedToWindow || !hasWindowFocus() || !isShown ||
            scrolling || touching || !motionEnabled
        ) {
            stopClock()
            render()
            return
        }
        if (clock != null) return
        val pageDuration = pages[currentPage].duration
        val startTime = elapsed
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (pageDuration - startTime).coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                elapsed = startTime + (it.animatedFraction * (pageDuration - startTime)).toLong()
                render()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clock = null
                    advancePage()
                }
            })
        }
        clock = animator
        animator.start()
    }

    private fun stopClock() {
        clock?.removeAllListeners()
        clock?.removeAllUpdateListeners()
        clock?.cancel()
        clock = null
    }

    private fun render() {
        holders.forEach { it.binding.tourScene.timeMillis = sceneTime(it.page) }
        binding.tourPager.alpha = if (!motionEnabled || scrolling || touching) 1f else {
            // Asymmetric crossfade: eased, slightly longer entrance; brisk linear exit.
            val entering = if (fadeEntrance) {
                entranceEase.getInterpolation((elapsed / 260f).coerceIn(0f, 1f))
            } else 1f
            val leaving = ((pages[currentPage].duration - elapsed) / 220f).coerceIn(0f, 1f)
            minOf(entering, leaving)
        }
    }

    private fun sceneTime(page: Int): Long =
        if (!motionEnabled) pages[page].duration else if (page != currentPage) 0L else elapsed

    private fun motionAllowed(): Boolean = !accessibility.isTouchExplorationEnabled &&
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.areAnimatorsEnabled()
        else Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) > 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                stopClock()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                post { updatePlayback() }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximumWidth = (600 * resources.displayMetrics.density).toInt()
        val boundedWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(maximumWidth)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(boundedWidth, MeasureSpec.getMode(widthMeasureSpec)),
            heightMeasureSpec,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accessibility.addTouchExplorationStateChangeListener(accessibilityListener)
        updatePlayback()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        stopClock()
        touching = false
        accessibility.removeTouchExplorationStateChangeListener(accessibilityListener)
        super.onDetachedFromWindow()
    }

    private class PageHolder(val binding: ItemOnboardingTourPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var page = 0
    }
}
