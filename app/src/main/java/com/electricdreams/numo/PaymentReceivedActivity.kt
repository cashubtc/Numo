package com.electricdreams.numo
import com.electricdreams.numo.R

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.AnimatorListenerAdapter
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
import com.cashujdk.nut00.Token
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.payment.PaymentIntentFactory

/**
 * Activity that displays a beautiful success screen when a payment is received
 * Following Cash App design guidelines
 */
class PaymentReceivedActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_FROM_NFC_ANIMATION = "extra_from_nfc_animation"
        private const val TAG = "PaymentReceivedActivity"
    }
    
    private lateinit var amountText: TextView
    private lateinit var checkmarkCircle: ImageView
    private lateinit var checkmarkIcon: ImageView
    private lateinit var closeButton: Button
    private lateinit var viewDetailsButton: Button
    private lateinit var shareIconButton: ImageButton
    private lateinit var closeIconButton: ImageButton
    
    private var tokenString: String? = null
    private var amount: Long = 0
    private var unit: String = "sat"
    private var fromNfcAnimation: Boolean = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_received)
        
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
        amountText = findViewById(R.id.amount_received_text)
        checkmarkCircle = findViewById(R.id.checkmark_circle)
        checkmarkIcon = findViewById(R.id.checkmark_icon)
        closeButton = findViewById(R.id.close_button)
        viewDetailsButton = findViewById(R.id.view_details_button)
        shareIconButton = findViewById(R.id.share_icon_button)
        closeIconButton = findViewById(R.id.close_icon_button)
        
        // Get token from intent
        tokenString = intent.getStringExtra(EXTRA_TOKEN)
        amount = intent.getLongExtra(EXTRA_AMOUNT, 0)
        fromNfcAnimation = intent.getBooleanExtra(EXTRA_FROM_NFC_ANIMATION, false)
        
        // Parse token to extract amount and unit if not provided
        if (amount == 0L && tokenString != null) {
            parseToken(tokenString!!)
        }
        
        // Set up UI
        updateAmountDisplay()
        
        // Set up button listeners
        closeButton.setOnClickListener {
            finish()
        }
        
        viewDetailsButton.setOnClickListener {
            openTransactionDetails()
        }
        
        shareIconButton.setOnClickListener {
            shareToken()
        }
        
        closeIconButton.setOnClickListener {
            finish()
        }
        
        if (fromNfcAnimation) {
            // Start with content hidden so the checkmark can lead the reveal.
            setContentVisibility(alpha = 0f)
            animateCheckmark {
                // After the checkmark, softly reveal the rest of the screen.
                fadeInContent()
            }
        } else {
            // Start the checkmark animation after a short delay.
            checkmarkIcon.postDelayed({
                animateCheckmark()
            }, 100)
        }
    }
    
    private fun parseToken(token: String) {
        try {
            val decodedToken = Token.decode(token)
            
            // Extract unit
            unit = decodedToken.unit
            
            // Calculate total amount from all proofs using the public API
            amount = decodedToken.tokens.stream()
                .mapToLong { t -> 
                    t.getProofsShortId().stream().mapToLong { p -> p.amount }.sum()
                }
                .sum()
            
            Log.d(TAG, "Parsed token: amount=$amount, unit=$unit")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing token: ${e.message}", e)
            // Fallback to provided amount or 0
            unit = "sat"
        }
    }
    
    private fun updateAmountDisplay() {
        val currency = com.electricdreams.numo.core.model.Amount.Currency.fromCode(unit)
        val formattedAmount = com.electricdreams.numo.core.model.Amount(amount, currency).toString()
        
        amountText.text = getString(R.string.payment_received_amount, formattedAmount)
    }
    
    private fun animateCheckmark(onComplete: (() -> Unit)? = null) {
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
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onComplete?.invoke()
            }
        })
        animatorSet.start()
    }

    /**
     * Set the alpha for the content parts we want to reveal after the checkmark.
     */
    private fun setContentVisibility(alpha: Float) {
        amountText.alpha = alpha
        viewDetailsButton.alpha = alpha
        closeButton.alpha = alpha
        shareIconButton.alpha = alpha
        closeIconButton.alpha = alpha
    }

    /**
     * Fade in the content after the success icon is shown.
     */
    private fun fadeInContent() {
        val fadeDuration = 280L
        amountText.animate().alpha(1f).setDuration(fadeDuration).start()
        viewDetailsButton.animate().alpha(1f).setDuration(fadeDuration).start()
        closeButton.animate().alpha(1f).setDuration(fadeDuration).start()
        shareIconButton.animate().alpha(1f).setDuration(fadeDuration).start()
        closeIconButton.animate().alpha(1f).setDuration(fadeDuration).start()
    }
    
    private fun shareToken() {
        if (tokenString.isNullOrEmpty()) {
            Toast.makeText(this, R.string.payment_received_error_no_token, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create intent to share/export the token
        val cashuUri = "cashu:$tokenString"
        
        // Create intent for viewing the URI
        val uriIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(cashuUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        // Create a fallback intent for sharing as text
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cashuUri)
        }
        
        // Combine both intents into a chooser
        val chooserIntent = Intent.createChooser(uriIntent, getString(R.string.payment_received_share_chooser_title))
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
        
        try {
            startActivity(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.payment_received_error_no_share_app, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openTransactionDetails() {
        // Get the most recent payment from history (the one we just received)
        val history = PaymentsHistoryActivity.getPaymentHistory(this)
        val entry = history.lastOrNull()
        
        if (entry == null) {
            Toast.makeText(this, R.string.payment_received_error_no_details, Toast.LENGTH_SHORT).show()
            return
        }
        
        startActivity(
            PaymentIntentFactory.createTransactionDetailIntent(
                context = this,
                entry = entry,
                position = history.size - 1,
            ),
        )
    }
}
