package com.electricdreams.numo.core.payment

import android.content.Context
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.payment.impl.BtcPayPaymentService
import com.electricdreams.numo.core.payment.impl.LocalPaymentService
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintManager

/**
 * Creates the appropriate [PaymentService] based on user settings.
 *
 * When `btcpay_enabled` is true **and** the BTCPay configuration is complete
 * the factory returns a [BtcPayPaymentService]; otherwise it falls back to
 * the [LocalPaymentService] that wraps the existing CDK wallet flow.
 */
object PaymentServiceFactory {

    fun create(context: Context): PaymentService {
        val prefs = PreferenceStore.app(context)

        if (prefs.getBoolean("btcpay_enabled", false)) {
            val config = BtcPayConfig(
                serverUrl = prefs.getString("btcpay_server_url") ?: "",
                apiKey = prefs.getString("btcpay_api_key") ?: "",
                storeId = prefs.getString("btcpay_store_id") ?: ""
            )
            if (config.serverUrl.isNotBlank()
                && config.apiKey.isNotBlank()
                && config.storeId.isNotBlank()
            ) {
                return BtcPayPaymentService(config)
            }
        }

        return LocalPaymentService(
            walletProvider = CashuWalletManager.getWalletProvider(),
            mintManager = MintManager.getInstance(context)
        )
    }
}
