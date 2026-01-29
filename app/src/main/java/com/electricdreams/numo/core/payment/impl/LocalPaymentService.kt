package com.electricdreams.numo.core.payment.impl

import com.electricdreams.numo.core.payment.PaymentData
import com.electricdreams.numo.core.payment.PaymentService
import com.electricdreams.numo.core.payment.PaymentState
import com.electricdreams.numo.core.payment.RedeemResult
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.wallet.QuoteStatus
import com.electricdreams.numo.core.wallet.Satoshis
import com.electricdreams.numo.core.wallet.WalletProvider
import com.electricdreams.numo.core.wallet.WalletResult

/**
 * [PaymentService] backed by the local CDK wallet.
 *
 * Pure delegation — no new business logic. The existing Cashu/Lightning flows
 * in [com.electricdreams.numo.payment.LightningMintHandler] and
 * [com.electricdreams.numo.ndef.CashuPaymentHelper] continue to work unchanged.
 */
class LocalPaymentService(
    private val walletProvider: WalletProvider,
    private val mintManager: MintManager
) : PaymentService {

    override suspend fun createPayment(
        amountSats: Long,
        description: String?
    ): WalletResult<PaymentData> {
        val mintUrl = mintManager.getPreferredLightningMint()
            ?: return WalletResult.Failure(
                com.electricdreams.numo.core.wallet.WalletError.NotInitialized(
                    "No preferred Lightning mint configured"
                )
            )

        return walletProvider.requestMintQuote(
            mintUrl = mintUrl,
            amount = Satoshis(amountSats),
            description = description
        ).map { quote ->
            PaymentData(
                paymentId = quote.quoteId,
                bolt11 = quote.bolt11Invoice,
                cashuPR = null, // Local mode: Numo builds its own creq
                mintUrl = mintUrl,
                expiresAt = quote.expiryTimestamp
            )
        }
    }

    override suspend fun checkPaymentStatus(paymentId: String): WalletResult<PaymentState> {
        val mintUrl = mintManager.getPreferredLightningMint()
            ?: return WalletResult.Failure(
                com.electricdreams.numo.core.wallet.WalletError.NotInitialized(
                    "No preferred Lightning mint configured"
                )
            )

        return walletProvider.checkMintQuote(mintUrl, paymentId).map { status ->
            when (status.status) {
                QuoteStatus.UNPAID -> PaymentState.PENDING
                QuoteStatus.PENDING -> PaymentState.PENDING
                QuoteStatus.PAID -> PaymentState.PAID
                QuoteStatus.ISSUED -> PaymentState.PAID
                QuoteStatus.EXPIRED -> PaymentState.EXPIRED
                QuoteStatus.UNKNOWN -> PaymentState.FAILED
            }
        }
    }

    override suspend fun redeemToken(
        token: String,
        paymentId: String?
    ): WalletResult<RedeemResult> {
        // paymentId is ignored for local mode
        return walletProvider.receiveToken(token).map { result ->
            RedeemResult(
                amount = result.amount,
                proofsCount = result.proofsCount
            )
        }
    }

    override fun isReady(): Boolean = walletProvider.isReady()
}
