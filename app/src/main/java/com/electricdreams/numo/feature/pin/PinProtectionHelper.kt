package com.electricdreams.numo.feature.pin

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.electricdreams.numo.util.startActivityForResultCompat

/**
 * Helper class to add PIN protection to activities.
 * 
 * Usage in an Activity:
 * 
 * ```kotlin
 * class ProtectedActivity : AppCompatActivity() {
 *     private val pinHelper = PinProtectionHelper(this)
 *     
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         if (pinHelper.requirePinIfEnabled()) return
 *         // ... rest of onCreate
 *     }
 *     
 *     override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
 *         super.onActivityResult(requestCode, resultCode, data)
 *         if (!pinHelper.handleActivityResult(requestCode, resultCode)) {
 *             // ... handle other results
 *         }
 *     }
 * }
 * ```
 */
class PinProtectionHelper(private val activity: Activity) {

    companion object {
        const val REQUEST_PIN_VERIFY = 9999
        private const val KEY_PIN_VERIFIED = "pin_verified"
        
        // Timeout for PIN verification (5 minutes)
        private const val VERIFICATION_TIMEOUT_MS = 5 * 60 * 1000L
        
        // Last successful PIN verification timestamp (app-wide)
        @Volatile
        private var lastVerificationTime: Long = 0
        
        /**
         * Check if a recent verification is still valid.
         */
        fun isRecentlyVerified(): Boolean {
            val elapsed = System.currentTimeMillis() - lastVerificationTime
            return elapsed < VERIFICATION_TIMEOUT_MS
        }
        
        /**
         * Mark PIN as verified (called after successful entry).
         */
        fun markVerified() {
            lastVerificationTime = System.currentTimeMillis()
        }
        
        /**
         * Clear verification (e.g., on app pause for security).
         */
        fun clearVerification() {
            lastVerificationTime = 0
        }
    }

    private val pinManager = PinManager.getInstance(activity)
    private var hasVerifiedPin = false

    /**
     * Call in onCreate to require PIN verification if enabled.
     * @return true if PIN verification was started (activity should return early)
     */
    fun requirePinIfEnabled(): Boolean {
        if (!pinManager.isPinEnabled()) {
            return false
        }

        // Check if recently verified (within timeout)
        if (isRecentlyVerified()) {
            hasVerifiedPin = true
            return false
        }

        // Request PIN verification
        val intent = Intent(activity, PinEntryActivity::class.java).apply {
            putExtra(PinEntryActivity.EXTRA_TITLE, "Enter PIN")
            putExtra(PinEntryActivity.EXTRA_SUBTITLE, "Verify to access this feature")
            putExtra(PinEntryActivity.EXTRA_ALLOW_BACK, true)
        }
        activity.startActivityForResultCompat(intent, REQUEST_PIN_VERIFY)
        return true
    }

    /**
     * Handle activity result for PIN verification.
     * @return true if the result was handled by this helper
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_PIN_VERIFY) {
            return false
        }

        if (resultCode == Activity.RESULT_OK) {
            hasVerifiedPin = true
            markVerified()
        } else {
            // PIN verification failed or cancelled - close the activity
            activity.finish()
        }
        return true
    }

    /**
     * Check if PIN has been verified for this session.
     */
    fun isVerified(): Boolean = hasVerifiedPin || !pinManager.isPinEnabled()
}

/**
 * Extension function to easily check if PIN protection is needed.
 */
fun Activity.requirePinProtection(
    onVerified: () -> Unit,
    onCancelled: () -> Unit = { finish() }
): Boolean {
    val pinManager = PinManager.getInstance(this)
    
    if (!pinManager.isPinEnabled()) {
        onVerified()
        return false
    }
    
    if (PinProtectionHelper.isRecentlyVerified()) {
        onVerified()
        return false
    }
    
    // Need to verify - this should be called with startActivityForResult
    return true
}
