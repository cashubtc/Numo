package com.electricdreams.shellshock.payment

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.PaymentReceivedActivity
import com.electricdreams.shellshock.R
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

    /** Handle successful payment */
    fun handlePaymentSuccess(
        token: String, 
        amount: Long, 
        isUsdInputMode: Boolean,
        onComplete: () -> Unit
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
        
        playSuccessFeedback()
        
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
        
        mainHandler.post {
            onComplete()
            val successIntent = Intent(activity, PaymentReceivedActivity::class.java).apply {
                putExtra(PaymentReceivedActivity.EXTRA_TOKEN, token)
                putExtra(PaymentReceivedActivity.EXTRA_AMOUNT, amount)
            }
            activity.startActivity(successIntent)
        }
    }

    /** Handle payment error */
    fun handlePaymentError(message: String, onComplete: () -> Unit) {
        mainHandler.post {
            onComplete()
            android.widget.Toast.makeText(activity, "Payment error: $message", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Play success feedback (sound + vibration) */
    private fun playSuccessFeedback() {
        try {
            val mediaPlayer = android.media.MediaPlayer.create(activity, R.raw.success_sound)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (_: Exception) {}
        
        vibrateSuccess()
    }

    /** Vibrate for success */
    private fun vibrateSuccess() {
        val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator?
        vibrator?.let { v -> v.vibrate(PATTERN_SUCCESS, -1) }
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

    companion object {
        private val PATTERN_SUCCESS = longArrayOf(0, 50, 100, 50)
    }
}
