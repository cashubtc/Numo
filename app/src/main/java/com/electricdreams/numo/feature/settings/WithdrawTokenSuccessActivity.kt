package com.electricdreams.numo.feature.settings

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast

/**
 * Success screen showing the created Cashu token with copy/share options.
 * Following Cash App design guidelines.
 */
class WithdrawTokenSuccessActivity : AppCompatActivity() {

    private lateinit var amountText: TextView
    private lateinit var tokenText: TextView
    private lateinit var tokenContainer: View
    private lateinit var checkmarkCircle: ImageView
    private lateinit var checkmarkIcon: ImageView
    private lateinit var copyButton: Button
    private lateinit var shareButton: Button
    private lateinit var closeButton: Button

    private var tokenString: String = ""
    private var amount: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_token_success)

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

        // Get data from intent
        amount = intent.getLongExtra("amount", 0)
        tokenString = intent.getStringExtra("token") ?: ""

        if (tokenString.isEmpty()) {
            Toast.makeText(this, "No token received", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        displayData()
        setupListeners()
        startAnimations()

        // Refresh balance after withdrawal
        val mintUrl = intent.getStringExtra("mint_url")
        if (!mintUrl.isNullOrEmpty()) {
            BalanceRefreshBroadcast.sendBroadcast(this, "token_withdrawal")
        }
    }

    private fun initViews() {
        amountText = findViewById(R.id.amount_text)
        tokenText = findViewById(R.id.token_text)
        tokenContainer = findViewById(R.id.token_container)
        checkmarkCircle = findViewById(R.id.checkmark_circle)
        checkmarkIcon = findViewById(R.id.checkmark_icon)
        copyButton = findViewById(R.id.copy_button)
        shareButton = findViewById(R.id.share_button)
        closeButton = findViewById(R.id.close_button)
    }

    private fun displayData() {
        // Display amount
        val amountObj = Amount(amount, Amount.Currency.BTC)
        amountText.text = getString(
            R.string.withdraw_token_success_amount,
            amountObj.toString()
        )

        // Display token (truncated for UI, full token is saved)
        val displayToken = if (tokenString.length > 50) {
            tokenString.take(25) + "..." + tokenString.takeLast(20)
        } else {
            tokenString
        }
        tokenText.text = displayToken
    }

    private fun setupListeners() {
        // Copy button
        copyButton.setOnClickListener {
            copyTokenToClipboard()
        }

        // Share button
        shareButton.setOnClickListener {
            shareToken()
        }

        // Close button
        closeButton.setOnClickListener {
            finish()
        }

        // Token container - also copies on tap
        tokenContainer.setOnClickListener {
            copyTokenToClipboard()
        }
    }

    private fun copyTokenToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cashu Token", tokenString)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(
            this,
            getString(R.string.withdraw_token_success_copied),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareToken() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, tokenString)
            putExtra(Intent.EXTRA_SUBJECT, "Cashu Token - $amount sats")
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.withdraw_token_success_share)))
    }

    private fun startAnimations() {
        // Checkmark scale animation
        checkmarkCircle.scaleX = 0f
        checkmarkCircle.scaleY = 0f
        checkmarkIcon.scaleX = 0f
        checkmarkIcon.scaleY = 0f

        // Animate checkmark circle
        checkmarkCircle.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(2f))
            .setStartDelay(300)
            .start()

        // Animate checkmark icon
        checkmarkIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setInterpolator(OvershootInterpolator(3f))
            .setStartDelay(500)
            .start()

        // Fade in amount and token
        amountText.alpha = 0f
        tokenContainer.alpha = 0f
        copyButton.alpha = 0f
        shareButton.alpha = 0f

        amountText.animate()
            .alpha(1f)
            .setStartDelay(600)
            .setDuration(400)
            .start()

        tokenContainer.animate()
            .alpha(1f)
            .setStartDelay(700)
            .setDuration(400)
            .start()

        copyButton.animate()
            .alpha(1f)
            .setStartDelay(800)
            .setDuration(400)
            .start()

        shareButton.animate()
            .alpha(1f)
            .setStartDelay(850)
            .setDuration(400)
            .start()

        // Close button
        closeButton.alpha = 0f
        closeButton.translationY = 20f
        closeButton.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(900)
            .setDuration(400)
            .start()
    }
}
