package com.electricdreams.numo.payment

import android.util.Log
import com.electricdreams.numo.ndef.NdefHostCardEmulationService

class PaymentRequestHceManager {

    private enum class HceMode { CASHU, LIGHTNING }

    private var currentMode: HceMode = HceMode.CASHU
    private var hcePaymentRequest: String? = null
    private var lightningInvoice: String? = null

    fun setPaymentRequest(request: String?) {
        hcePaymentRequest = request
    }

    fun setLightningInvoice(invoice: String?) {
        lightningInvoice = invoice
    }

    fun switchToCashu(paymentAmount: Long) {
        val request = hcePaymentRequest ?: run {
            Log.w(TAG, "switchToCashu() called but hcePaymentRequest is null")
            return
        }

        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "switchToCashu(): Switching HCE payload to Cashu request")
                hceService.setPaymentRequest(request, paymentAmount)
                currentMode = HceMode.CASHU
            } else {
                Log.w(TAG, "switchToCashu(): HCE service not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToCashu(): Error while setting HCE Cashu payload: ${e.message}", e)
        }
    }

    fun switchToLightning() {
        val invoice = lightningInvoice ?: run {
            Log.w(TAG, "switchToLightning() called but lightningInvoice is null")
            return
        }
        val payload = "lightning:$invoice"

        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            if (hceService != null) {
                Log.d(TAG, "switchToLightning(): Switching HCE payload to Lightning invoice. payload=$payload")
                hceService.setPaymentRequest(payload, 0L)
                currentMode = HceMode.LIGHTNING
            } else {
                Log.w(TAG, "switchToLightning(): HCE service not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToLightning(): Error while setting HCE Lightning payload: ${e.message}", e)
        }
    }

    fun clear() {
        try {
            val hceService = NdefHostCardEmulationService.getInstance()
            hceService?.let {
                Log.d(TAG, "Clearing HCE service state")
                it.clearPaymentRequest()
                it.setPaymentCallback(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing HCE service: ${e.message}", e)
        }
        currentMode = HceMode.CASHU
        hcePaymentRequest = null
        lightningInvoice = null
    }

    companion object {
        private const val TAG = "PaymentRequestHceManager"
    }
}
