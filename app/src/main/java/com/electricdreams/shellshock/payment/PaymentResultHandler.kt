package com.electricdreams.shellshock.payment

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker
import com.electricdreams.shellshock.feature.history.PaymentsHistoryActivity

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
        
        // Delegate to callback for unified success handling (feedback + screen)
        mainHandler.post {
            onComplete(token, amount)
        }
    }

    /** Handle payment error */
    fun handlePaymentError(message: String, onComplete: () -> Unit) {
        mainHandler.post {
            onComplete()
            android.widget.Toast.makeText(activity, "Payment error: $message", android.widget.Toast.LENGTH_LONG).show()
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
