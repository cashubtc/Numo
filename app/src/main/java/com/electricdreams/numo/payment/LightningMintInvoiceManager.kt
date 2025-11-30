package com.electricdreams.numo.payment

import android.content.Context
import android.util.Log
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.cashu.CashuWalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cashudevkit.MintUrl
import org.cashudevkit.Amount as CdkAmount

/**
 * Helper responsible for creating or reusing a Lightning mint invoice for
 * a specific POS payment.
 *
 * This class bridges between the high-level LightningMintHandler UI flow
 * and the lower-level MultiMintWallet.mintQuote API so that the
 * SwapToLightningMintManager can participate in the same quote.
 */
object LightningMintInvoiceManager {

    private const val TAG = "LightningMintInvoiceMgr"

    data class LightningMintInvoice(
        val bolt11: String,
        val quoteId: String,
        val lightningMintUrl: String
    )

    /**
     * Get an existing Lightning mint invoice for the given payment if one is
     * already stored in PaymentHistory, otherwise create a new mint quote via
     * the CDK wallet.
     *
     * @param appContext Android application context
     * @param lightningMintUrl The mint URL selected for Lightning receipts
     * @param paymentContext Basic payment info (id + amount)
     */
    suspend fun getOrCreateInvoiceForPayment(
        appContext: Context,
        lightningMintUrl: String,
        paymentContext: SwapToLightningMintManager.PaymentContext
    ): LightningMintInvoice = withContext(Dispatchers.IO) {
        val paymentId = paymentContext.paymentId

        // 1) Try to reuse an existing Lightning invoice from payment history
        if (!paymentId.isNullOrEmpty()) {
            try {
                val existing = findHistoryEntry(appContext, paymentId)
                if (existing != null && existing.lightningInvoice != null &&
                    existing.lightningQuoteId != null && existing.lightningMintUrl != null
                ) {
                    Log.d(
                        TAG,
                        "Reusing existing Lightning invoice for paymentId=$paymentId quoteId=${existing.lightningQuoteId}"
                    )
                    return@withContext LightningMintInvoice(
                        bolt11 = existing.lightningInvoice,
                        quoteId = existing.lightningQuoteId,
                        lightningMintUrl = existing.lightningMintUrl
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to look up existing Lightning invoice for paymentId=$paymentId", t)
            }
        }

        // 2) No existing invoice found; create a new mint quote using CDK
        val wallet = CashuWalletManager.getWallet()
            ?: throw IllegalStateException("Wallet not initialized")

        val mintUrl = try {
            MintUrl(lightningMintUrl)
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid Lightning mint URL: $lightningMintUrl", t)
            throw IllegalArgumentException("Invalid Lightning mint URL")
        }

        val amount = CdkAmount(paymentContext.amountSats.toULong())
        Log.d(TAG, "Requesting new Lightning mint quote from $lightningMintUrl for ${paymentContext.amountSats} sats")

        val quote = wallet.mintQuote(
            mintUrl,
            amount,
            "Numo POS payment of ${paymentContext.amountSats} sats"
        )

        // Persist the new Lightning info on the pending payment entry if we have one
        if (!paymentId.isNullOrEmpty()) {
            try {
                com.electricdreams.numo.feature.history.PaymentsHistoryActivity
                    .updatePendingWithLightningInfo(
                        context = appContext,
                        paymentId = paymentId,
                        lightningInvoice = quote.request,
                        lightningQuoteId = quote.id,
                        lightningMintUrl = lightningMintUrl,
                    )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to update pending payment $paymentId with Lightning info", t)
            }
        }

        LightningMintInvoice(
            bolt11 = quote.request,
            quoteId = quote.id,
            lightningMintUrl = lightningMintUrl
        )
    }

    /**
     * Locate a PaymentHistoryEntry by id using the existing history storage.
     */
    private fun findHistoryEntry(context: Context, paymentId: String): PaymentHistoryEntry? {
        val history = com.electricdreams.numo.feature.history.PaymentsHistoryActivity.getPaymentHistory(context)
        return history.firstOrNull { it.id == paymentId }
    }
}
