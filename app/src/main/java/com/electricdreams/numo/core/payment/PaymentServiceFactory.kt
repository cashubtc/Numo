package com.electricdreams.numo.core.payment

import android.content.Context
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.UnitId
import com.electricdreams.numo.core.payment.impl.BTCPayPaymentService
import com.electricdreams.numo.core.payment.impl.LocalPaymentService
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintManager

/**
 * Creates the appropriate [IPaymentService] based on user settings.
 *
 * When `btcpay_enabled` is true **and** the BTCPay configuration is complete
 * the factory returns a [BTCPayPaymentService]; otherwise it falls back to
 * the [LocalPaymentService] that wraps the existing CDK wallet flow.
 */
object PaymentServiceFactory {

    fun create(context: Context): IPaymentService {
        val unit = MintManager.getInstance(context).getPreferredUnit()
        return create(context, unit)
    }

    /**
     * Create a service for a captured charge unit. BTCPay's current API is sat-denominated, so
     * non-sat payments stay on the local Cashu path instead of being silently re-denominated.
     */
    fun create(
        context: Context,
        paymentUnit: String,
        issuerScope: String? = null,
    ): IPaymentService {
        val prefs = PreferenceStore.app(context)
        val unit = UnitId.of(paymentUnit)

        if (unit.isSat && prefs.getBoolean("btcpay_enabled", false)) {
            val config = BTCPayConfig(
                serverUrl = prefs.getString("btcpay_server_url") ?: "",
                apiKey = prefs.getString("btcpay_api_key") ?: "",
                storeId = prefs.getString("btcpay_store_id") ?: "",
                posAppId = prefs.getString("btcpay_pos_app_id")?.takeIf { it.isNotBlank() },
            )
            if (config.serverUrl.isNotBlank()
                && config.apiKey.isNotBlank()
                && config.storeId.isNotBlank()
            ) {
                return BTCPayPaymentService(config)
            }
        }

        return LocalPaymentService(
            walletProvider = CashuWalletManager.getWalletProvider(unit.value),
            mintManager = MintManager.getInstance(context),
            paymentUnit = unit.value,
            issuerScope = issuerScope,
        )
    }
}
