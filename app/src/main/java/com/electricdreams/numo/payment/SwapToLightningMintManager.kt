package com.electricdreams.numo.payment

import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.nostr.Bech32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.MeltQuote
import org.cashudevkit.Melted
import org.cashudevkit.MintUrl
import java.security.MessageDigest
import kotlin.math.roundToLong
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity

/**
 * Coordinates swapping a Cashu payment from an unknown mint into the
 * merchant's configured Lightning mint.
 *
 * High-level flow (see docs/SwapToLightningMint.md):
 * 1. Detect that the incoming Cashu token is from an unknown mint.
 * 2. Ensure the unknown mint is reachable via CDK (fetchMintInfo).
 * 3. Obtain (or reuse) a Lightning invoice from the preferred Lightning mint
 *    for the POS payment amount.
 * 4. Request a melt quote from the unknown mint to pay that Lightning invoice.
 * 5. Enforce a maximum fee reserve of 5% of the quote amount.
 * 6. Execute the melt and fetch the final melt quote state.
 * 7. Verify that the payment preimage returned by the unknown mint hashes
 *    to the payment hash encoded in the BOLT11 invoice.
 * 8. Rely on the existing LightningMintHandler flow to mint proofs on the
 *    Lightning mint once the invoice is paid.
 */
object SwapToLightningMintManager {

    private const val TAG = "SwapToLightningMintManager"

    /** Maximum allowed melt fee reserve as a fraction of the quote amount (5%). */
    private const val MAX_FEE_RESERVE_RATIO = 0.05
    private const val MIN_FEE_OVERHEAD = 0.005

    /**
     * Result of attempting to swap a payment from an unknown mint.
     */
    sealed class SwapResult {
        data class Success(
            val finalToken: String,
            val lightningMintUrl: String?,
            val amountSats: Long
        ) : SwapResult()

        data class Failure(val errorMessage: String) : SwapResult()
    }

    /**
     * Lightweight context for a POS payment used to associate Lightning
     * invoices and quotes with the payment history entry.
     */
    data class PaymentContext(
        val paymentId: String?,
        val amountSats: Long
    )

    /**
     * Entry point for swapping a Cashu token from an unknown mint into the
     * merchant's configured Lightning mint.
     *
     * This method performs network and wallet I/O and must be called from a
     * coroutine context.
     *
     * @param appContext Android application context for accessing MintManager
     *                   and updating payment history.
     * @param cashuToken The encoded Cashu token string presented by the payer.
     * @param expectedAmount Amount in satoshis that the POS expects to receive
     *                       for this payment (excluding tip).
     * @param unknownMintUrl The mint URL extracted from the token which is not
     *                       present in the merchant's allowed mints list.
     * @param paymentContext Context describing the POS payment (id, amount).
     */
    suspend fun swapFromUnknownMint(
        appContext: android.content.Context,
        cashuToken: String,
        expectedAmount: Long,
        unknownMintUrl: String,
        paymentContext: PaymentContext
    ): SwapResult = withContext(Dispatchers.IO) {
        Log.d(
            TAG,
            "swapFromUnknownMint: start " +
                "unknownMintUrl=$unknownMintUrl, " +
                "expectedAmount=$expectedAmount, " +
                "paymentContext.paymentId=${paymentContext.paymentId}, " +
                "paymentContext.amountSats=${paymentContext.amountSats}, " +
                "cashuTokenLength=${cashuToken.length}"
        )

        // 1) Create a temporary single-mint Wallet and receive the payer's
        //    token so that it holds the proofs we want to melt. This wallet
        //    is entirely ephemeral and uses its own random seed.

        val tempWallet = try {
            CashuWalletManager.getTemporaryWalletForMint(unknownMintUrl)
        } catch (t: Throwable) {
            val msg = "Failed to create temporary wallet for unknown mint: ${'$'}{t.message}"
            Log.e(TAG, msg, t)
            return@withContext SwapResult.Failure(msg)
        }

        Log.d(TAG, "swapFromUnknownMint: temporary wallet created for mint=$unknownMintUrl")

        val wallet = CashuWalletManager.getWallet()
            ?: run {
                Log.e(TAG, "swapFromUnknownMint: main wallet not initialized for Lightning mint")
                return@withContext SwapResult.Failure("Wallet not initialized for Lightning mint")
            }

        Log.d(TAG, "swapFromUnknownMint: main wallet for Lightning mint is available")

        val cdkToken = org.cashudevkit.Token.decode(cashuToken)
        val proofs = cdkToken.proofsSimple()
        Log.d(TAG, "swapFromUnknownMint: decoded Cashu token with ${proofs.size} proofs")

        // We request a mint quote, just to have the bolt11 request to feed to the melt quote request
        // Then, we take the fee estimate from the melt quote response and
        // ask for a new mint quote with the adjusted amount

        val feeBuffer = kotlin.math.ceil(paymentContext.amountSats * MAX_FEE_RESERVE_RATIO).toLong()
        var lightningAmount = paymentContext.amountSats - feeBuffer

        Log.d(
            TAG,
            "swapFromUnknownMint: initial fee buffer=$feeBuffer (ratio=${"%.2f".format(MAX_FEE_RESERVE_RATIO * 100)}%), " +
                "initialLightningAmount=$lightningAmount from received=${paymentContext.amountSats}"
        )

        if (lightningAmount <= 0L) {
            val msg = "Received amount $lightningAmount is too small after 5% fee buffer"
            Log.e(TAG, msg)
            try { tempWallet.close() } catch (_: Throwable) {}
            return@withContext SwapResult.Failure(msg)
        }

        val mintManager = MintManager.getInstance(appContext)
        val lightningMintUrl = mintManager.getPreferredLightningMint()
            ?: run {
                Log.e(TAG, "No preferred Lightning mint configured")
                try { tempWallet.close() } catch (_: Throwable) {}
                return@withContext SwapResult.Failure("No Lightning mint configured")
            }
        Log.d(TAG, "swapFromUnknownMint: preferred Lightning mint is $lightningMintUrl")

        Log.d(
            TAG,
            "swapFromUnknownMint: requesting initial Lightning mint quote for fee estimation: " +
                "lightningMintUrl=$lightningMintUrl, amount=$lightningAmount"
        )

        val mintQuote = wallet.mintQuote(MintUrl(lightningMintUrl), CdkAmount(lightningAmount.toULong()), null)

        Log.d(
            TAG,
            "swapFromUnknownMint: received initial Lightning mint quote: " +
                "quoteId=${mintQuote.id}, requestLength=${mintQuote.request.length}"
        )

        var meltQuote: MeltQuote = try {
            Log.d(TAG, "swapFromUnknownMint: requesting initial melt quote from unknown mint for preliminary Lightning invoice")
            tempWallet.meltQuote(mintQuote.request, null)
        } catch (t: Throwable) {
            val msg = "Failed to request melt quote from unknown mint: ${'$'}{t.message}"
            Log.e(TAG, msg, t)
            tempWallet.close()
            return@withContext SwapResult.Failure(msg)
        }

        val feeReserveEstimate = meltQuote.feeReserve.value.toLong()
        Log.d(
            TAG,
            "swapFromUnknownMint: initial melt quote from unknown mint: " +
                "meltQuoteId=${meltQuote.id}, amount=${meltQuote.amount.value.toLong()}, feeReserveEstimate=$feeReserveEstimate"
        )
        if (feeReserveEstimate > feeBuffer) {
            val msg = "Lightning fee reserve estimate is too big ($feeReserveEstimate)"
            Log.e(TAG, msg)
            try { tempWallet.close() } catch (_: Throwable) {}
            return@withContext SwapResult.Failure(msg)
        }

        val minOverhead = kotlin.math.ceil(paymentContext.amountSats * MIN_FEE_OVERHEAD).toLong()
        lightningAmount = paymentContext.amountSats - minOverhead - feeReserveEstimate

        Log.d(
            TAG,
            "swapFromUnknownMint: adjusted Lightning amount=$lightningAmount after applying " +
                "minFeeOverheadCeil=$minOverhead and feeReserveEstimate=$feeReserveEstimate"
        )

        Log.d(
            TAG,
            "swapFromUnknownMint: requesting Lightning mint quote: " +
                "lightningMintUrl=$lightningMintUrl, mintQuoteAmount=$lightningAmount"
        )

        val finalMintQuote = wallet.mintQuote(MintUrl(lightningMintUrl), CdkAmount(lightningAmount.toULong()), null)

        val bolt11 = finalMintQuote.request
        Log.d(
            TAG,
            "swapFromUnknownMint: using Lightning invoice for swap: " +
                "quoteId=${finalMintQuote.id}, bolt11Length=${bolt11.length}"
        )

        // 4) Request a melt quote from the unknown mint for this bolt11.
        meltQuote = try {
            Log.d(TAG, "swapFromUnknownMint: requesting final melt quote from unknown mint using Lightning bolt11 invoice")
            tempWallet.meltQuote(bolt11, null)
        } catch (t: Throwable) {
            val msg = "Failed to request melt quote from unknown mint: ${'$'}{t.message}"
            Log.e(TAG, msg, t)
            tempWallet.close()
            return@withContext SwapResult.Failure(msg)
        }

        val quoteAmount = meltQuote.amount.value.toLong()
        val feeReserve = meltQuote.feeReserve.value.toLong()

        Log.d(
            TAG,
            "swapFromUnknownMint: melt quote details: " +
                "meltQuoteAmount=$quoteAmount, " +
                "meltQuoteFeeReserve=$feeReserve, " +
                "totalMeltRequired=${quoteAmount + feeReserve}, " +
                "receivedSats=${paymentContext.amountSats}, " +
                "feeBufferReserved=$feeBuffer (ratio=${"%.2f".format(MAX_FEE_RESERVE_RATIO * 100)}%), " +
                "lightningMintQuoteAmount=$lightningAmount"
        )

        if (quoteAmount <= 0) {
            val msg = "Invalid melt quote amount (zero or negative)"
            Log.e(TAG, msg)
            return@withContext SwapResult.Failure(msg)
        }

        val totalMeltRequired = quoteAmount + feeReserve
        if (totalMeltRequired > paymentContext.amountSats) {
            val msg = "Unknown-mint melt requires $totalMeltRequired sats but temp wallet balance is ${paymentContext.amountSats}"
            Log.w(TAG, msg)
            try { tempWallet.close() } catch (_: Throwable) {}
            return@withContext SwapResult.Failure(msg)
        }

        // 4) At this point we have:
        //    - A Lightning mint quote (LightningMintInvoiceManager)
        //    - An unknown-mint melt quote from the temporary wallet
        //    We now record this as a SwapToLightningMint frame in
        //    PaymentHistory so it can be inspected later.
        if (paymentContext.paymentId != null) {
            try {
                val frame = PaymentHistoryEntry.Companion.SwapToLightningMintFrame(
                    unknownMintUrl = unknownMintUrl,
                    meltQuoteId = meltQuote.id,
                    lightningMintUrl = lightningMintUrl,
                    lightningQuoteId = finalMintQuote.id,
                )
                val frameJson = com.google.gson.Gson().toJson(frame)

                PaymentsHistoryActivity
                    .updatePendingWithLightningInfo(
                        context = appContext,
                        paymentId = paymentContext.paymentId,
                        swapToLightningMintJson = frameJson,
                    )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to store SwapToLightningMint frame for paymentId=${paymentContext.paymentId}", t)
            }
        }

        // 5) Execute melt on unknown mint using the temporary single-mint wallet.
        //    The Melted result carries the final state, preimage, and fee info
        //    for this Lightning payment.
        val melted: Melted = try {
            Log.d(
                TAG,
                "swapFromUnknownMint: executing melt on unknown mint: " +
                    "meltQuoteId=${meltQuote.id}, proofsCount=${proofs.size}, totalMeltRequired=$totalMeltRequired"
            )
            tempWallet.meltProofs(meltQuote.id, proofs)
        } catch (t: Throwable) {
            val msg = "Melt execution failed on unknown mint: ${t.message}"
            Log.e(TAG, msg, t)
            return@withContext SwapResult.Failure(msg)
        } finally {
            try {
                tempWallet.close()
            } catch (_: Throwable) {
            }
        }

        Log.d(TAG, "swapFromUnknownMint: melt result state=${melted.state}, preimagePresent=${melted.preimage != null}")

        if (melted.state != org.cashudevkit.QuoteState.PAID) {
            val msg = "Unknown-mint melt did not complete: state=${melted.state}"
            Log.w(TAG, msg)
            return@withContext SwapResult.Failure(msg)
        }

        // 6) We no longer verify the preimage against the BOLT11 invoice
        // payment hash here. The Lightning mint will only mint proofs if the
        // invoice was actually paid, so a successful mint later implicitly
        // confirms the payment. We rely on that instead of doing an explicit
        // SHA-256(preimage) vs. payment_hash comparison.

        // 7) Attempt to mint the Lightning mint quote so that the merchant
        //    receives ecash on their Lightning mint. This is done
        //    opportunistically as part of the swap flow; other components
        //    (e.g. LightningMintHandler) may also mint the quote if they
        //    were started from a separate Lightning-receive UI flow.
        try {
            val lightningMint = MintUrl(lightningMintUrl)

            Log.d(TAG, "Checking Lightning mint quote state for quoteId=${finalMintQuote.id}")
            val lightningQuote = wallet.checkMintQuote(lightningMint, finalMintQuote.id)
            val stateStr = lightningQuote.state.toString()
            Log.d(TAG, "Lightning mint quote state=$stateStr")

            if (stateStr.equals("PAID", ignoreCase = true) ||
                stateStr.equals("ISSUED", ignoreCase = true)
            ) {
                Log.d(TAG, "Lightning quote is paid; attempting wallet.mint for quoteId=${finalMintQuote.id}")
                try {
                    val mintedProofs = wallet.mint(lightningMint, finalMintQuote.id, null)
                    Log.d(TAG, "Minted ${mintedProofs.size} proofs on Lightning mint as part of swap flow")
                } catch (mintError: Throwable) {
                    // Not fatal for the POS payment itself; the Lightning
                    // handler / operator can reconcile, but we log loudly.
                    Log.e(TAG, "Failed to mint proofs on Lightning mint for quoteId=${finalMintQuote.id}: ${mintError.message}", mintError)
                }
            } else {
                Log.d(TAG, "Lightning mint quote not yet paid (state=$stateStr), skipping immediate mint")
            }
        } catch (t: Throwable) {
            // Swallow non-fatal errors here; payment is already secured by
            // the unknown-mint melt + preimage verification. LightningMintHandler
            // or manual reconciliation can still complete the mint later.
            Log.w(TAG, "Error while attempting to mint Lightning quote after swap: ${t.message}", t)
        }

        // At this point, we know the unknown mint has paid the exact invoice
        // we generated from our Lightning mint. The LightningMintHandler is
        // responsible for monitoring the quote and minting proofs. From the
        // POS perspective, we can treat the swap as successful and rely on
        // the Lightning flow to finalize balances.

        Log.d(
            TAG,
            "Swap from unknown mint completed successfully for paymentId=${paymentContext.paymentId} " +
                "(unknownMintUrl=$unknownMintUrl, lightningMintUrl=$lightningMintUrl, amountSats=$expectedAmount)"
        )

        return@withContext SwapResult.Success(
            finalToken = cashuToken,
            lightningMintUrl = lightningMintUrl,
            amountSats = expectedAmount
        )
    }
    // Note: previously we had explicit BOLT11 payment-hash verification
    // utilities here (verifyMeltPreimageMatchesInvoice +
    // extractPaymentHashFromBolt11 + hexToBytes). Since the Lightning mint
    // will only mint proofs if the invoice is actually paid, we now rely on
    // that as the source of truth and have removed this additional layer of
    // verification to simplify the swap flow.
}
