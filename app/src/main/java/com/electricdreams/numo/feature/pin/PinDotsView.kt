package com.electricdreams.numo.feature.pin

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R

/**
 * A view that displays PIN entry progress as a row of dots.
 * Supports animations for input, delete, error, and success states.
 */
class PinDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val dots = mutableListOf<View>()
    private var maxLength = PinManager.MAX_PIN_LENGTH
    private var currentLength = 0
    private var displayedDotCount = 4 // Start with 4 dots, expand as needed

    enum class State {
        NORMAL, ERROR, SUCCESS
    }

    private var state = State.NORMAL

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
        // Allow dot bounce animations to render fully without being clipped
        // by the LinearLayout bounds. This keeps the visual behavior the same
        // while avoiding the cropped look during scale animations.
        clipToPadding = false
        clipChildren = false
        setupDots()
    }

    private fun setupDots() {
        removeAllViews()
        dots.clear()

        for (i in 0 until displayedDotCount) {
            val dot = View(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_pin_dot_empty)
            }
            val size = (16 * resources.displayMetrics.density).toInt()
            val margin = (8 * resources.displayMetrics.density).toInt()
            val params = LayoutParams(size, size).apply {
                marginStart = if (i == 0) 0 else margin
                marginEnd = if (i == displayedDotCount - 1) 0 else 0
            }
            dot.layoutParams = params
            addView(dot)
            dots.add(dot)
        }
    }

    fun setLength(length: Int) {
        currentLength = length.coerceIn(0, maxLength)

        // Expand dots if needed (beyond 4)
        if (currentLength > displayedDotCount && displayedDotCount < maxLength) {
            displayedDotCount = currentLength
            setupDots()
        }

        updateDotStates()
    }

    fun addDigit() {
        if (currentLength < maxLength) {
            currentLength++

            // Expand dots if needed
            if (currentLength > displayedDotCount && displayedDotCount < maxLength) {
                displayedDotCount = currentLength
                setupDots()
            }

            updateDotStates()
            animateLastDot()
        }
    }

    fun removeDigit() {
        if (currentLength > 0) {
            currentLength--
            updateDotStates()
        }
    }

    fun clear() {
        currentLength = 0
        state = State.NORMAL
        displayedDotCount = 4
        setupDots()
    }

    fun showError() {
        state = State.ERROR
        updateDotStates()
        animateError()
    }

    fun showSuccess() {
        state = State.SUCCESS
        updateDotStates()
        animateSuccess()
    }

    fun resetState() {
        state = State.NORMAL
        updateDotStates()
    }

    private fun updateDotStates() {
        dots.forEachIndexed { index, dot ->
            val drawableRes = when {
                state == State.ERROR && index < currentLength -> R.drawable.bg_pin_dot_error
                index < currentLength -> R.drawable.bg_pin_dot_filled
                else -> R.drawable.bg_pin_dot_empty
            }
            dot.background = ContextCompat.getDrawable(context, drawableRes)
        }
    }

    private fun animateLastDot() {
        if (currentLength > 0 && currentLength <= dots.size) {
            val dot = dots[currentLength - 1]
            val scaleX = ObjectAnimator.ofFloat(dot, "scaleX", 0.5f, 1.2f, 1f)
            val scaleY = ObjectAnimator.ofFloat(dot, "scaleY", 0.5f, 1.2f, 1f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 150
                interpolator = OvershootInterpolator()
                start()
            }
        }
    }

    private fun animateError() {
        // Shake animation
        val shake = ObjectAnimator.ofFloat(this, "translationX", 0f, -15f, 15f, -15f, 15f, -10f, 10f, -5f, 5f, 0f)
        shake.duration = 400
        shake.start()

        // Reset state after animation
        postDelayed({
            state = State.NORMAL
            updateDotStates()
        }, 600)
    }

    private fun animateSuccess() {
        // Scale up briefly then return
        dots.forEachIndexed { index, dot ->
            val scaleX = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.3f, 1f)
            val scaleY = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.3f, 1f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                duration = 300
                startDelay = index * 30L
                start()
            }
        }
    }
}
