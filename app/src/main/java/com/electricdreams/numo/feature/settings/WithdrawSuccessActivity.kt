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

import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.databinding.ActivityWithdrawSuccessBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Success screen for withdrawal completion
 * Following Cash App design guidelines
 */
class WithdrawSuccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWithdrawSuccessBinding

    private lateinit var amountText: TextView
    private lateinit var destinationText: TextView
    private lateinit var checkmarkCircle: ImageView
    private lateinit var checkmarkIcon: ImageView
    private lateinit var closeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWithdrawSuccessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        // Initialize views
        amountText = binding.amountText
        destinationText = binding.destinationText
        checkmarkCircle = binding.checkmarkCircle
        checkmarkIcon = binding.checkmarkIcon
        closeButton = binding.closeButton

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

    override fun finish() {
        // Broadcast balance change so other activities refresh their balance displays
        BalanceRefreshBroadcast.send(this, BalanceRefreshBroadcast.REASON_WITHDRAWAL)
        super.finish()
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
