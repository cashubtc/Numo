package com.electricdreams.numo.feature.withdraw

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.util.getVibrator
import com.electricdreams.numo.util.vibrateCompat

/**
 * Outcome screen for a failed or pending withdrawal.
 *
 * Mirrors the visual language of [com.electricdreams.numo.PaymentFailureActivity]
 * (animated circle + icon, primary/secondary actions) but is result-driven:
 * the caller launches it for a result and receives [RESULT_RETRY] when the user
 * wants to try again with a fresh quote.
 *
 * Pending mode (a melt that may still settle) uses a warning visual and offers
 * no retry, since retrying a pending Lightning payment risks paying twice.
 */
class WithdrawFailureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DESTINATION = "destination"

        const val MODE_FAILED = "failed"
        const val MODE_PENDING = "pending"

        const val RESULT_RETRY = Activity.RESULT_FIRST_USER

        private val PATTERN_FAILURE = longArrayOf(0, 60, 120, 60)
    }

    private lateinit var titleText: TextView
    private lateinit var messageText: TextView
    private lateinit var destinationText: TextView
    private lateinit var errorCircle: ImageView
    private lateinit var errorIcon: ImageView
    private lateinit var tryAgainButton: Button
    private lateinit var closeButton: Button
    private lateinit var closeIconButton: ImageButton

    private var isPendingMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_failure)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val backgroundColor = ContextCompat.getColor(this, R.color.color_bg_white)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(backgroundColor))

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        val useDarkIcons = ColorUtils.calculateLuminance(backgroundColor) > 0.5
        windowInsetsController.isAppearanceLightStatusBars = useDarkIcons
        windowInsetsController.isAppearanceLightNavigationBars = useDarkIcons

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            windowInsets
        }

        titleText = findViewById(R.id.failure_title)
        messageText = findViewById(R.id.failure_message)
        destinationText = findViewById(R.id.destination_text)
        errorCircle = findViewById(R.id.error_circle)
        errorIcon = findViewById(R.id.error_icon)
        tryAgainButton = findViewById(R.id.try_again_button)
        closeButton = findViewById(R.id.close_button)
        closeIconButton = findViewById(R.id.close_icon_button)

        isPendingMode = intent.getStringExtra(EXTRA_MODE) == MODE_PENDING
        applyMode(intent.getStringExtra(EXTRA_MESSAGE), intent.getStringExtra(EXTRA_DESTINATION))

        closeButton.setOnClickListener { finishWithResult(Activity.RESULT_CANCELED) }
        closeIconButton.setOnClickListener { finishWithResult(Activity.RESULT_CANCELED) }
        tryAgainButton.setOnClickListener { finishWithResult(RESULT_RETRY) }

        getVibrator()?.vibrateCompat(PATTERN_FAILURE)

        errorIcon.postDelayed({ animateOutcomeIcon() }, 100)
    }

    private fun applyMode(message: String?, destination: String?) {
        if (isPendingMode) {
            titleText.text = getString(R.string.withdraw_pending_title)
            messageText.text = message ?: getString(R.string.withdraw_pending_message)
            errorCircle.setColorFilter(ContextCompat.getColor(this, R.color.color_warning))
            errorIcon.setImageResource(R.drawable.ic_pending)
            tryAgainButton.visibility = View.GONE
        } else {
            titleText.text = getString(R.string.withdraw_failure_title)
            messageText.text = if (message.isNullOrBlank()) {
                getString(R.string.withdraw_failure_message_generic)
            } else {
                message
            }
        }

        if (!destination.isNullOrBlank()) {
            destinationText.text = getString(R.string.withdraw_amount_to, destination)
            destinationText.visibility = View.VISIBLE
        }
    }

    private fun finishWithResult(resultCode: Int) {
        setResult(resultCode)
        finish()
    }

    private fun animateOutcomeIcon() {
        errorCircle.alpha = 0f
        errorCircle.scaleX = 0.3f
        errorCircle.scaleY = 0.3f
        errorCircle.visibility = View.VISIBLE

        errorIcon.alpha = 0f
        errorIcon.scaleX = 0f
        errorIcon.scaleY = 0f
        errorIcon.visibility = View.VISIBLE

        val circleScaleX = ObjectAnimator.ofFloat(errorCircle, "scaleX", 0.3f, 1f).apply {
            duration = 400
            interpolator = android.view.animation.DecelerateInterpolator(2f)
        }

        val circleScaleY = ObjectAnimator.ofFloat(errorCircle, "scaleY", 0.3f, 1f).apply {
            duration = 400
            interpolator = android.view.animation.DecelerateInterpolator(2f)
        }

        val circleFadeIn = ObjectAnimator.ofFloat(errorCircle, "alpha", 0f, 1f).apply {
            duration = 350
        }

        val iconScaleX = ObjectAnimator.ofFloat(errorIcon, "scaleX", 0f, 1f).apply {
            duration = 500
            startDelay = 150
            interpolator = OvershootInterpolator(3f)
        }

        val iconScaleY = ObjectAnimator.ofFloat(errorIcon, "scaleY", 0f, 1f).apply {
            duration = 500
            startDelay = 150
            interpolator = OvershootInterpolator(3f)
        }

        val iconFadeIn = ObjectAnimator.ofFloat(errorIcon, "alpha", 0f, 1f).apply {
            duration = 300
            startDelay = 150
        }

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(
            circleScaleX, circleScaleY, circleFadeIn,
            iconScaleX, iconScaleY, iconFadeIn,
        )
        animatorSet.start()
    }
}
