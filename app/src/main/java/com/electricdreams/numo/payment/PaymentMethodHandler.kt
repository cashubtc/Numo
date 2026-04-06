package com.electricdreams.numo.payment

import android.content.Context
import android.content.Intent
import com.electricdreams.numo.util.startActivityForResultCompat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.tips.TipSelectionActivity
import com.electricdreams.numo.feature.tips.TipsManager
import com.electricdreams.numo.ndef.CashuPaymentHelper
import com.electricdreams.numo.ndef.NdefHostCardEmulationService

/**
 * Handles payment method selection and initiation.
 */
class PaymentMethodHandler(
    private val activity: AppCompatActivity
) {

    /** Show payment method dialog for the specified amount */
    fun showPaymentMethodDialog(amount: Long, formattedAmount: String, checkoutBasketJson: String? = null) {
        val tipsManager = TipsManager.getInstance(activity)
        
        val routing = PaymentRoutingCore.determinePaymentRoute(tipsManager.tipsEnabled)
        val intent = routing.buildIntent(activity, amount, formattedAmount, checkoutBasketJson)
        activity.startActivityForResultCompat(intent, REQUEST_CODE_PAYMENT)
    }

    /** Proceed with NDEF payment (HCE) - preserved but not currently invoked in main flow */
    fun proceedWithNdefPayment(amount: Long, onStatusUpdate: (String) -> Unit, onComplete: () -> Unit) {
        if (!NdefHostCardEmulationService.isHceAvailable(activity)) {
            Toast.makeText(activity, R.string.payment_toast_hce_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        val mintManager = MintManager.getInstance(activity)
        val allowedMints = mintManager.getAllowedMints()
        // When "Accept payments from unknown mints" is enabled we intentionally
        // omit the mints field from the PaymentRequest. Some wallets interpret
        // an explicit mints list as a strict requirement rather than a
        // preference, which would prevent them from paying with other mints
        // even though the POS will accept them via swap.
        val mintsForPaymentRequest =
            if (mintManager.isSwapFromUnknownMintsEnabled()) null else allowedMints

        val paymentRequest = CashuPaymentHelper.createPaymentRequest(amount, "Payment of $amount sats", mintsForPaymentRequest)?.original
            ?: run {
                Toast.makeText(activity, R.string.payment_toast_failed_create_request, Toast.LENGTH_SHORT).show()
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
                        // Delegate error handling to the unified payment
                        // result handler so we show the app-wide failure
                        // screen and allow retry.
                        PaymentResultHandler(activity, null).handlePaymentError(
                            message = "NDEF Payment failed: $errorMessage",
                            onComplete = {},
                        )
                    }
                }

                override fun onNfcReadingStarted() {
                    activity.runOnUiThread {
                        onStatusUpdate("NFC reading started...")
                    }
                }

                override fun onNfcReadingStopped(failedInMiddleOfTransaction: Boolean) {
                    activity.runOnUiThread {
                        onStatusUpdate(if (failedInMiddleOfTransaction) "NFC reading interrupted" else "NFC reading stopped")
                    }
                }
            })
            onStatusUpdate("Waiting for payment...\n\nHold your phone against the paying device")
        } catch (e: Exception) {
            onComplete()
            Toast.makeText(activity, activity.getString(R.string.payment_toast_error_ndef_setup, e.message), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val REQUEST_CODE_PAYMENT = 1001
    }
}
