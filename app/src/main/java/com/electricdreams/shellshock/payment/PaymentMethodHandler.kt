package com.electricdreams.shellshock.payment

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.PaymentRequestActivity
import com.electricdreams.shellshock.core.util.MintManager
import com.electricdreams.shellshock.feature.tips.TipSelectionActivity
import com.electricdreams.shellshock.feature.tips.TipsManager
import com.electricdreams.shellshock.ndef.CashuPaymentHelper
import com.electricdreams.shellshock.ndef.NdefHostCardEmulationService

/**
 * Handles payment method selection and initiation.
 */
class PaymentMethodHandler(
    private val activity: AppCompatActivity
) {

    /** Show payment method dialog for the specified amount */
    fun showPaymentMethodDialog(amount: Long, formattedAmount: String, checkoutBasketJson: String? = null) {
        val tipsManager = TipsManager.getInstance(activity)
        
        if (tipsManager.tipsEnabled) {
            // Route through tip selection activity first
            val intent = Intent(activity, TipSelectionActivity::class.java).apply {
                putExtra(TipSelectionActivity.EXTRA_PAYMENT_AMOUNT, amount)
                putExtra(TipSelectionActivity.EXTRA_FORMATTED_AMOUNT, formattedAmount)
                checkoutBasketJson?.let {
                    putExtra(TipSelectionActivity.EXTRA_CHECKOUT_BASKET_JSON, it)
                }
            }
            activity.startActivityForResult(intent, REQUEST_CODE_PAYMENT)
        } else {
            // Go directly to payment request
            val intent = Intent(activity, PaymentRequestActivity::class.java).apply {
                putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, amount)
                putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, formattedAmount)
                checkoutBasketJson?.let {
                    putExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON, it)
                }
            }
            activity.startActivityForResult(intent, REQUEST_CODE_PAYMENT)
        }
    }

    /** Proceed with NDEF payment (HCE) - preserved but not currently invoked in main flow */
    fun proceedWithNdefPayment(amount: Long, onStatusUpdate: (String) -> Unit, onComplete: () -> Unit) {
        if (!NdefHostCardEmulationService.isHceAvailable(activity)) {
            Toast.makeText(activity, "Host Card Emulation is not available on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val mintManager = MintManager.getInstance(activity)
        val allowedMints = mintManager.getAllowedMints()
        val paymentRequest = CashuPaymentHelper.createPaymentRequest(amount, "Payment of $amount sats", allowedMints)
            ?: run {
                Toast.makeText(activity, "Failed to create payment request", Toast.LENGTH_SHORT).show()
                return
            }

        onStatusUpdate("Initializing Host Card Emulation...")
        activity.startService(Intent(activity, NdefHostCardEmulationService::class.java))

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val service = NdefHostCardEmulationService.getInstance()
            if (service != null) {
                setupNdefPayment(service, paymentRequest, amount, onStatusUpdate, onComplete)
            } else {
                onStatusUpdate("Error: Host Card Emulation service not available")
            }
        }, 1000)
    }

    /** Setup NDEF payment with service */
    private fun setupNdefPayment(
        service: NdefHostCardEmulationService,
        paymentRequest: String,
        amount: Long,
        onStatusUpdate: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        try {
            service.setPaymentRequest(paymentRequest, amount)
            service.setPaymentCallback(object : NdefHostCardEmulationService.CashuPaymentCallback {
                override fun onCashuTokenReceived(token: String) {
                    activity.runOnUiThread {
                        onComplete()
                        // Handle token received - delegate to PaymentResultHandler
                    }
                }

                override fun onCashuPaymentError(errorMessage: String) {
                    activity.runOnUiThread {
                        onComplete()
                        Toast.makeText(activity, "Payment failed: $errorMessage", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onNfcReadingStarted() {
                    activity.runOnUiThread {
                        onStatusUpdate("NFC reading started...")
                    }
                }

                override fun onNfcReadingStopped() {
                    activity.runOnUiThread {
                        onStatusUpdate("NFC reading stopped")
                    }
                }
            })
            onStatusUpdate("Waiting for payment...\n\nHold your phone against the paying device")
        } catch (e: Exception) {
            onComplete()
            Toast.makeText(activity, "Error setting up NDEF payment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val REQUEST_CODE_PAYMENT = 1001
    }
}
