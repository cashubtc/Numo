package com.electricdreams.numo.payment.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.model.Amount.Currency
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.ui.util.QrCodeGenerator

class PaymentRequestUi(
    private val activity: Activity,
    private val bitcoinPriceWorker: BitcoinPriceWorker?
) {
    private val handler = Handler(Looper.getMainLooper())

    fun updateAmountDisplays(
        largeAmountDisplay: TextView,
        convertedAmountDisplay: TextView,
        formattedAmount: String,
        paymentAmount: Long
    ) {
        largeAmountDisplay.text = formattedAmount
        val isBtcAmount = formattedAmount.startsWith("₿")
        val hasBitcoinPrice = (bitcoinPriceWorker?.getCurrentPrice() ?: 0.0) > 0

        if (!hasBitcoinPrice) {
            convertedAmountDisplay.visibility = View.GONE
            return
        }

        if (isBtcAmount) {
            val fiatValue = bitcoinPriceWorker?.satoshisToFiat(paymentAmount) ?: 0.0
            if (fiatValue > 0) {
                val formattedFiat = bitcoinPriceWorker?.formatFiatAmount(fiatValue)
                    ?: CurrencyManager.getInstance(activity).formatCurrencyAmount(fiatValue)
                convertedAmountDisplay.text = formattedFiat
                convertedAmountDisplay.visibility = View.VISIBLE
            } else {
                convertedAmountDisplay.visibility = View.GONE
            }
        } else {
            if (paymentAmount > 0) {
                val formattedBtc = Amount(paymentAmount, Currency.BTC).toString()
                convertedAmountDisplay.text = formattedBtc
                convertedAmountDisplay.visibility = View.VISIBLE
            } else {
                convertedAmountDisplay.visibility = View.GONE
            }
        }
    }

    fun showStatus(statusText: TextView, messageRes: Int) {
        statusText.visibility = View.VISIBLE
        statusText.setText(messageRes)
    }

    fun showStatus(statusText: TextView, message: String) {
        statusText.visibility = View.VISIBLE
        statusText.text = message
    }

    fun generateCashuQr(cashuQrImageView: ImageView, paymentRequest: String) {
        try {
            val qrBitmap = QrCodeGenerator.generate(paymentRequest, 512)
            cashuQrImageView.setImageBitmap(qrBitmap)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.payment_request_status_error_qr, Toast.LENGTH_SHORT).show()
        }
    }

    fun generateLightningQr(lightningQrImageView: ImageView, bolt11: String) {
        try {
            val qrBitmap = QrCodeGenerator.generate(bolt11, 512)
            lightningQrImageView.setImageBitmap(qrBitmap)
        } catch (_: Exception) {
            // ignored, spinner will hide later
        }
    }

    fun delayFinish(action: () -> Unit) {
        handler.postDelayed(action, 3000)
    }
}
