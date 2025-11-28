package com.electricdreams.numo.feature.settings

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount

/**
 * Success screen for withdrawal completion
 * Following Cash App design guidelines
 */
class WithdrawSuccessActivity : AppCompatActivity() {

    private lateinit var amountText: TextView
    private lateinit var destinationText: TextView
    private lateinit var checkmarkCircle: ImageView
    private lateinit var checkmarkIcon: ImageView
    private lateinit var closeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_success)

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // Set light status bar icons (since background is white)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = true
        windowInsetsController.isAppearanceLightNavigationBars = true

        // Adjust padding for system bars
        findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { v, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            windowInsets
        }

        // Initialize views
        amountText = findViewById(R.id.amount_text)
        destinationText = findViewById(R.id.destination_text)
        checkmarkCircle = findViewById(R.id.checkmark_circle)
        checkmarkIcon = findViewById(R.id.checkmark_icon)
        closeButton = findViewById(R.id.close_button)

        // Get data from intent
        val amount = intent.getLongExtra("amount", 0)
        val destination = intent.getStringExtra("destination")
            ?: getString(R.string.withdraw_success_destination_fallback)

        // Display data
        val amountObj = Amount(amount, Amount.Currency.BTC)
        amountText.text = getString(
            R.string.withdraw_success_amount,
            amountObj.toString()
        )
        destinationText.text = getString(
            R.string.withdraw_success_destination,
            destination
        )

        // Set up button listener
        closeButton.setOnClickListener {
            finish()
        }

        // Start the checkmark animation after a short delay
        checkmarkIcon.postDelayed({
            animateCheckmark()
        }, 100)
    }

    private fun animateCheckmark() {
        // Simple, elegant success animation
        // 1. Green circle scales in smoothly
        // 2. White checkmark pops in with overshoot

        // Set initial states
        checkmarkCircle.alpha = 0f
        checkmarkCircle.scaleX = 0.3f
        checkmarkCircle.scaleY = 0.3f
        checkmarkCircle.visibility = View.VISIBLE

        checkmarkIcon.alpha = 0f
        checkmarkIcon.scaleX = 0f
        checkmarkIcon.scaleY = 0f
        checkmarkIcon.visibility = View.VISIBLE

        // Animate green circle - smooth scale and fade in
        val circleScaleX = ObjectAnimator.ofFloat(checkmarkCircle, "scaleX", 0.3f, 1f).apply {
            duration = 400
            interpolator = android.view.animation.DecelerateInterpolator(2f)
        }

        val circleScaleY = ObjectAnimator.ofFloat(checkmarkCircle, "scaleY", 0.3f, 1f).apply {
            duration = 400
            interpolator = android.view.animation.DecelerateInterpolator(2f)
        }

        val circleFadeIn = ObjectAnimator.ofFloat(checkmarkCircle, "alpha", 0f, 1f).apply {
            duration = 350
        }

        // Animate white checkmark - pop in with overshoot after circle
        val iconScaleX = ObjectAnimator.ofFloat(checkmarkIcon, "scaleX", 0f, 1f).apply {
            duration = 500
            startDelay = 150
            interpolator = OvershootInterpolator(3f)
        }

        val iconScaleY = ObjectAnimator.ofFloat(checkmarkIcon, "scaleY", 0f, 1f).apply {
            duration = 500
            startDelay = 150
            interpolator = OvershootInterpolator(3f)
        }

        val iconFadeIn = ObjectAnimator.ofFloat(checkmarkIcon, "alpha", 0f, 1f).apply {
            duration = 300
            startDelay = 150
        }

        // Play all animations
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(
            circleScaleX, circleScaleY, circleFadeIn,
            iconScaleX, iconScaleY, iconFadeIn
        )
        animatorSet.start()
    }
}
