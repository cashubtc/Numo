package com.electricdreams.numo.feature.pin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R

/**
 * Full-screen PIN entry activity for unlocking protected features.
 * 
 * Features:
 * - Clean, Apple-like UI with numeric keypad
 * - Animated PIN dots with error shake
 * - 3-second cooldown after wrong PIN (keypad disabled, countdown shown)
 * - Lockout display when too many attempts
 * - Option to reset via mnemonic
 */
class PinEntryActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private lateinit var pinDots: PinDotsView
    private lateinit var pinKeypad: PinKeypadView
    private lateinit var errorMessage: TextView
    private lateinit var lockoutOverlay: FrameLayout
    private lateinit var lockoutMessage: TextView
    private lateinit var lockoutResetButton: TextView
    private lateinit var forgotPinButton: TextView
    private lateinit var backButton: ImageButton

    private val enteredPin = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var lockoutUpdateRunnable: Runnable? = null
    private var cooldownTimer: CountDownTimer? = null
    private var isInputDisabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_entry)

        pinManager = PinManager.getInstance(this)
        initViews()
        setupListeners()
        setupCustomization()
        checkLockout()
    }

    private fun initViews() {
        pinDots = findViewById(R.id.pin_dots)
        pinKeypad = findViewById(R.id.pin_keypad)
        errorMessage = findViewById(R.id.error_message)
        lockoutOverlay = findViewById(R.id.lockout_overlay)
        lockoutMessage = findViewById(R.id.lockout_message)
        lockoutResetButton = findViewById(R.id.lockout_reset_button)
        forgotPinButton = findViewById(R.id.forgot_pin_button)
        backButton = findViewById(R.id.back_button)

        val titleView = findViewById<TextView>(R.id.title)
        val subtitleView = findViewById<TextView>(R.id.subtitle)

        intent.getStringExtra(EXTRA_TITLE)?.let { titleView.text = it }
        intent.getStringExtra(EXTRA_SUBTITLE)?.let { subtitleView.text = it }
    }

    private fun setupCustomization() {
        val allowBack = intent.getBooleanExtra(EXTRA_ALLOW_BACK, true)
        backButton.visibility = if (allowBack) View.VISIBLE else View.INVISIBLE
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            setResult(RESULT_CANCELLED)
            finish()
        }

        pinKeypad.setOnKeyListener(object : PinKeypadView.OnKeyListener {
            override fun onDigitPressed(digit: String) {
                // Ignore input if disabled during cooldown
                if (isInputDisabled) return
                
                if (enteredPin.length < PinManager.MAX_PIN_LENGTH) {
                    enteredPin.append(digit)
                    pinDots.addDigit()
                    hideError()

                    // Auto-submit when minimum length reached
                    if (enteredPin.length >= PinManager.MIN_PIN_LENGTH) {
                        // Small delay for visual feedback before validation
                        handler.postDelayed({ validatePin() }, 100)
                    }
                }
            }

            override fun onDeletePressed() {
                // Ignore input if disabled during cooldown
                if (isInputDisabled) return
                
                if (enteredPin.isNotEmpty()) {
                    enteredPin.deleteCharAt(enteredPin.length - 1)
                    pinDots.removeDigit()
                    hideError()
                }
            }
        })

        forgotPinButton.setOnClickListener {
            openPinReset()
        }

        lockoutResetButton.setOnClickListener {
            openPinReset()
        }
    }

    private fun validatePin() {
        val result = pinManager.validatePin(enteredPin.toString())

        when {
            result.success -> {
                pinDots.showSuccess()
                handler.postDelayed({
                    setResult(RESULT_PIN_VERIFIED)
                    finish()
                }, 300)
            }
            result.isLockedOut -> {
                pinDots.showError()
                enteredPin.clear()
                handler.postDelayed({
                    pinDots.clear()
                    showLockout(result.remainingLockoutMs)
                }, 500)
            }
            result.needsDelay -> {
                // Already in cooldown - start cooldown with remaining time
                startCooldown(result.remainingDelayMs)
            }
            else -> {
                // Wrong PIN - show error animation and start 3-second cooldown
                pinDots.showError()
                showError(result.error ?: "Incorrect PIN")
                enteredPin.clear()
                handler.postDelayed({
                    pinDots.clear()
                    // Start 3-second cooldown after wrong PIN
                    startCooldown(PinManager.MIN_ATTEMPT_INTERVAL_MS)
                }, 500)
            }
        }
    }

    /**
     * Start cooldown period with countdown display.
     * Disables keypad and shows countdown until user can try again.
     */
    private fun startCooldown(durationMs: Long) {
        isInputDisabled = true
        pinKeypad.alpha = 0.4f
        
        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer(durationMs, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000.0)
                showError(String.format("Wait %.1fs", seconds))
            }

            override fun onFinish() {
                isInputDisabled = false
                pinKeypad.alpha = 1.0f
                hideError()
            }
        }.start()
    }

    private fun showError(message: String) {
        errorMessage.text = message
        errorMessage.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorMessage.visibility = View.INVISIBLE
    }

    private fun checkLockout() {
        val remainingMs = pinManager.getRemainingLockoutMs()
        if (remainingMs > 0) {
            showLockout(remainingMs)
        }
    }

    private fun showLockout(remainingMs: Long) {
        lockoutOverlay.visibility = View.VISIBLE
        updateLockoutMessage(remainingMs)
        startLockoutCountdown()
    }

    private fun hideLockout() {
        lockoutOverlay.visibility = View.GONE
        lockoutUpdateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun updateLockoutMessage(remainingMs: Long) {
        val minutes = (remainingMs / 60000).toInt()
        val seconds = ((remainingMs % 60000) / 1000).toInt()

        lockoutMessage.text = if (minutes > 0) {
            "Try again in $minutes minute${if (minutes > 1) "s" else ""}"
        } else {
            "Try again in $seconds second${if (seconds > 1) "s" else ""}"
        }
    }

    private fun startLockoutCountdown() {
        lockoutUpdateRunnable = object : Runnable {
            override fun run() {
                val remaining = pinManager.getRemainingLockoutMs()
                if (remaining > 0) {
                    updateLockoutMessage(remaining)
                    handler.postDelayed(this, 1000)
                } else {
                    hideLockout()
                }
            }
        }.also { handler.post(it) }
    }

    private fun openPinReset() {
        val intent = Intent(this, PinResetActivity::class.java)
        startActivityForResult(intent, REQUEST_PIN_RESET)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PIN_RESET && resultCode == Activity.RESULT_OK) {
            // PIN was reset, close this activity with success
            setResult(RESULT_PIN_VERIFIED)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lockoutUpdateRunnable?.let { handler.removeCallbacks(it) }
        cooldownTimer?.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val allowBack = intent.getBooleanExtra(EXTRA_ALLOW_BACK, true)
        if (allowBack) {
            setResult(RESULT_CANCELLED)
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_ALLOW_BACK = "extra_allow_back"
        const val RESULT_PIN_VERIFIED = Activity.RESULT_OK
        const val RESULT_CANCELLED = Activity.RESULT_CANCELED
        private const val REQUEST_PIN_RESET = 1001
    }
}
