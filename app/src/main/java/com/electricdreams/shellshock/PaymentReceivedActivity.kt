package com.electricdreams.shellshock

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
import com.cashujdk.nut00.Token
import java.text.NumberFormat
import java.util.Locale

/**
 * Activity that displays a beautiful success screen when a payment is received
 * Following Cash App design guidelines
 */
class PaymentReceivedActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_AMOUNT = "extra_amount"
        private const val TAG = "PaymentReceivedActivity"
    }
    
    private lateinit var amountText: TextView
    private lateinit var checkmarkCircle: ImageView
    private lateinit var checkmarkIcon: ImageView
    private lateinit var closeButton: Button
    private lateinit var shareIconButton: ImageButton
    private lateinit var closeIconButton: ImageButton
    
    private var tokenString: String? = null
    private var amount: Long = 0
    private var unit: String = "sat"
    
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
        shareIconButton = findViewById(R.id.share_icon_button)
        closeIconButton = findViewById(R.id.close_icon_button)
        
        // Get token from intent
        tokenString = intent.getStringExtra(EXTRA_TOKEN)
        amount = intent.getLongExtra(EXTRA_AMOUNT, 0)
        
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
        
        shareIconButton.setOnClickListener {
            shareToken()
        }
        
        closeIconButton.setOnClickListener {
            finish()
        }
        
        // Start the checkmark animation after a short delay
        checkmarkCircle.postDelayed({
            animateCheckmark()
        }, 100)
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
        val currency = com.electricdreams.shellshock.core.model.Amount.Currency.fromCode(unit)
        val formattedAmount = com.electricdreams.shellshock.core.model.Amount(amount, currency).toString()
        
        amountText.text = "$formattedAmount received."
    }
    
    private fun animateCheckmark() {
        // Animate green circle first
        checkmarkCircle.alpha = 0f
        checkmarkCircle.scaleX = 0f
        checkmarkCircle.scaleY = 0f
        checkmarkCircle.visibility = View.VISIBLE
        
        val circleScaleX = ObjectAnimator.ofFloat(checkmarkCircle, "scaleX", 0f, 1f).apply {
            duration = 500
            interpolator = OvershootInterpolator(2f)
        }
        
        val circleScaleY = ObjectAnimator.ofFloat(checkmarkCircle, "scaleY", 0f, 1f).apply {
            duration = 500
            interpolator = OvershootInterpolator(2f)
        }
        
        val circleFadeIn = ObjectAnimator.ofFloat(checkmarkCircle, "alpha", 0f, 1f).apply {
            duration = 300
        }
        
        val circleAnimatorSet = AnimatorSet()
        circleAnimatorSet.playTogether(circleScaleX, circleScaleY, circleFadeIn)
        
        // Animate white checkmark icon with same timing as circle for cohesive effect
        checkmarkIcon.alpha = 0f
        checkmarkIcon.scaleX = 0f
        checkmarkIcon.scaleY = 0f
        checkmarkIcon.visibility = View.VISIBLE
        
        val iconScaleX = ObjectAnimator.ofFloat(checkmarkIcon, "scaleX", 0f, 1f).apply {
            duration = 500  // Match circle duration
            startDelay = 150  // Start slightly after circle
            interpolator = OvershootInterpolator(2f)  // Match circle interpolator
        }
        
        val iconScaleY = ObjectAnimator.ofFloat(checkmarkIcon, "scaleY", 0f, 1f).apply {
            duration = 500  // Match circle duration
            startDelay = 150
            interpolator = OvershootInterpolator(2f)  // Match circle interpolator
        }
        
        val iconFadeIn = ObjectAnimator.ofFloat(checkmarkIcon, "alpha", 0f, 1f).apply {
            duration = 300  // Match circle fade duration
            startDelay = 150
        }
        
        val iconAnimatorSet = AnimatorSet()
        iconAnimatorSet.playTogether(iconScaleX, iconScaleY, iconFadeIn)
        
        // Play both animations together
        val masterAnimatorSet = AnimatorSet()
        masterAnimatorSet.playTogether(circleAnimatorSet, iconAnimatorSet)
        masterAnimatorSet.start()
    }
    
    private fun shareToken() {
        if (tokenString.isNullOrEmpty()) {
            Toast.makeText(this, "No token to share", Toast.LENGTH_SHORT).show()
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
        val chooserIntent = Intent.createChooser(uriIntent, "Share token")
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
        
        try {
            startActivity(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "No apps available to share this token", Toast.LENGTH_SHORT).show()
        }
    }
}
