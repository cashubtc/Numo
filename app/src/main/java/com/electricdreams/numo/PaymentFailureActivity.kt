package com.electricdreams.numo

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.feature.history.TransactionDetailActivity

/**
 * Activity that displays a payment failure screen when a payment fails or hangs.
 *
 * The UI is intentionally specular to [PaymentReceivedActivity], but uses an
 * error visual language (red circle with a cross icon) and offers explicit
 * recovery actions:
 * - "Try again" reopens the latest pending entry from the payment history as
 *   if the user had tapped on it in the history list.
 * - "Close" simply dismisses the screen.
 */
class PaymentFailureActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PaymentFailureActivity"
    }

    private lateinit var titleText: TextView
    private lateinit var messageText: TextView
    private lateinit var errorCircle: ImageView
    private lateinit var errorIcon: ImageView
    private lateinit var tryAgainButton: Button
    private lateinit var closeButton: Button
    private lateinit var closeIconButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_failure)

        // Enable edge-to-edge, mirroring PaymentReceivedActivity
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

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
        titleText = findViewById(R.id.payment_failure_title)
        messageText = findViewById(R.id.payment_failure_message)
        errorCircle = findViewById(R.id.error_circle)
        errorIcon = findViewById(R.id.error_icon)
        tryAgainButton = findViewById(R.id.try_again_button)
        closeButton = findViewById(R.id.close_button)
        closeIconButton = findViewById(R.id.close_icon_button)

        // Configure buttons
        closeButton.setOnClickListener { finish() }
        closeIconButton.setOnClickListener { finish() }

        tryAgainButton.setOnClickListener {
            handleTryAgain()
        }

        // Start the error animation after a short delay
        errorIcon.postDelayed({
            animateErrorIcon()
        }, 100)
    }

    /**
     * Find the latest pending payment entry and resume it, mirroring the
     * behavior of tapping that entry in [PaymentsHistoryActivity].
     */
    private fun handleTryAgain() {
        val history = PaymentsHistoryActivity.getPaymentHistory(this)
        // Latest pending = newest entry with pending status
        val latestPending: PaymentHistoryEntry? = history
            .filter { it.isPending() }
            .maxByOrNull { it.date.time }

        if (latestPending == null) {
            Toast.makeText(this, R.string.payment_failure_error_no_pending, Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Retrying payment for pending entry id=${latestPending.id}")

        // Reuse same behavior as PaymentsHistoryActivity.resumePendingPayment
        val intent = Intent(this, PaymentRequestActivity::class.java).apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, latestPending.amount)
            putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, latestPending.formattedAmount)
            putExtra(PaymentRequestActivity.EXTRA_RESUME_PAYMENT_ID, latestPending.id)

            latestPending.lightningQuoteId?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_QUOTE_ID, it)
            }
            latestPending.lightningMintUrl?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_MINT_URL, it)
            }
            latestPending.lightningInvoice?.let {
                putExtra(PaymentRequestActivity.EXTRA_LIGHTNING_INVOICE, it)
            }
            latestPending.nostrSecretHex?.let {
                putExtra(PaymentRequestActivity.EXTRA_NOSTR_SECRET_HEX, it)
            }
            latestPending.nostrNprofile?.let {
                putExtra(PaymentRequestActivity.EXTRA_NOSTR_NPROFILE, it)
            }
        }

        startActivity(intent)
        // Close the failure screen so the user returns to the normal payment flow
        finish()
    }

    /**
     * Play a simple, elegant error animation mirroring the success animation
     * from [PaymentReceivedActivity] but using an error visual language.
     */
    private fun animateErrorIcon() {
        // Initial state
        errorCircle.alpha = 0f
        errorCircle.scaleX = 0.3f
        errorCircle.scaleY = 0.3f
        errorCircle.visibility = View.VISIBLE

        errorIcon.alpha = 0f
        errorIcon.scaleX = 0f
        errorIcon.scaleY = 0f
        errorIcon.visibility = View.VISIBLE

        // Circle animation
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

        // Icon animation
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
