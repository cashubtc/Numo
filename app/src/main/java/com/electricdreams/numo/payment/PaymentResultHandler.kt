package com.electricdreams.numo.payment

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.PaymentFailureActivity

/**
 * Handles payment success and error scenarios.
 */
class PaymentResultHandler(
    private val activity: AppCompatActivity,
    private val bitcoinPriceWorker: BitcoinPriceWorker?
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Handle successful payment - records to history and delegates to callback */
    fun handlePaymentSuccess(
        token: String, 
        amount: Long, 
        isUsdInputMode: Boolean,
        onComplete: (String, Long) -> Unit
    ) {
        val (entryUnit, enteredAmount) = if (isUsdInputMode) {
            val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
            if (price > 0) {
                val fiatValue = bitcoinPriceWorker?.satoshisToFiat(amount) ?: 0.0
                "USD" to (fiatValue * 100).toLong()
            } else { 
                "USD" to amount 
            }
        } else { 
            "sat" to amount 
        }
        
        val bitcoinPrice = bitcoinPriceWorker?.getCurrentPrice()?.takeIf { it > 0 }
        
        val mintUrl = extractMintUrlFromToken(token)
        PaymentsHistoryActivity.addToHistory(
            activity, 
            token, 
            amount, 
            "sat", 
            entryUnit, 
            enteredAmount, 
            bitcoinPrice, 
            mintUrl, 
            null
        )
        
        // Check for auto-withdrawal after successful payment (runs in background)
        AutoWithdrawManager.getInstance(activity).onPaymentReceived(token, mintUrl)
        
        // Delegate to callback for unified success handling (feedback + screen)
        mainHandler.post {
            onComplete(token, amount)
        }
    }

    /**
     * Handle payment error in a centralized way.
     *
     * This implementation:
     * - Invokes the caller's completion callback so any local cleanup can run
     * - Shows a brief toast with the low-level error message
     * - Navigates to the global [PaymentFailureActivity], which will present
     *   a dedicated failure UI and allow the user to retry the latest
     *   pending payment.
     */
    fun handlePaymentError(message: String, onComplete: () -> Unit) {
        mainHandler.post {
            onComplete()

            // Brief, inline feedback for context
            Toast.makeText(activity, "Payment error: $message", Toast.LENGTH_LONG).show()

            // Global failure screen with explicit recovery actions
            val intent = Intent(activity, PaymentFailureActivity::class.java)
            activity.startActivity(intent)
        }
    }

    /** Extract mint URL from token string */
    private fun extractMintUrlFromToken(tokenString: String?): String? = try {
        if (!tokenString.isNullOrEmpty()) {
            com.cashujdk.nut00.Token.decode(tokenString).mint
        } else {
            null
        }
    } catch (_: Exception) { 
        null 
    }
}
