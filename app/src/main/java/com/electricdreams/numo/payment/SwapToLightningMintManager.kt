package com.electricdreams.numo.payment

import android.content.Context
import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.data.model.PaymentHistoryEntry
import com.electricdreams.numo.core.dev.WalletLogger
import com.electricdreams.numo.core.update.withPaymentOperation
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.FinalizedMelt
import org.cashudevkit.MeltConfirmOptions
import org.cashudevkit.MeltQuote
import org.cashudevkit.MintUrl
import org.cashudevkit.QuoteState
import org.cashudevkit.Wallet

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
 * 7. Mint the paid quote on the Lightning mint.
 * 8. Keep unresolved quotes available for later confirmation in history.
 */
object SwapToLightningMintManager {

    private const val TAG = "SwapToLightningMintManager"
    private val finalizationMutex = Mutex()
    private val swapMutex = Mutex()
    internal var temporaryWalletFactory:
        (Context, String, String, String) -> Wallet =
        SwapWalletStore::open

    /** Maximum allowed melt fee reserve as a fraction of the quote amount (5%). */
    private const val MAX_FEE_RESERVE_RATIO = 0.05
    private const val MIN_FEE_OVERHEAD = 0.005

    /**
     * Result of attempting to swap a payment from an unknown mint.
     */
    sealed class SwapResult {
        data class Success(
            val lightningMintUrl: String?,
            val amountSats: Long
        ) : SwapResult()

        data class Failure(val errorMessage: String) : SwapResult()

        data class Pending(val paymentId: String) : SwapResult()
    }

    /**
     * Lightweight appContext for a POS payment used to associate Lightning
     * invoices and quotes with the payment history entry.
     */
    data class PaymentContext(
        val paymentId: String?,
        val amountSats: Long,
        val unit: String? = null,
    )

    /**
     * Entry point for swapping a Cashu token from an unknown mint into the
     * merchant's configured Lightning mint.
     *
     * This method performs network and wallet I/O and must be called from a
     * coroutine appContext.
     *
     * @param appContext Android application appContext for accessing MintManager
     *                   and updating payment history.
     * @param proofs The Cashu proofs presented by the payer.
     * @param expectedAmount Amount in satoshis that the POS expects to receive
     *                       for this payment (excluding tip).
     * @param unknownMintUrl The mint URL extracted from the token which is not
     *                       present in the merchant's allowed mints list.
     * @param paymentContext Context describing the POS payment (id, amount).
     */
    suspend fun swapFromUnknownMint(
        appContext: Context,
        proofs: List<org.cashudevkit.Proof>,
        expectedAmount: Long,
        unknownMintUrl: String,
        paymentContext: PaymentContext
    ): SwapResult = withPaymentOperation(Dispatchers.IO) {
        swapMutex.withLock {
            val saved = paymentContext.paymentId?.let {
                PaymentsHistoryActivity.getPaymentEntryById(appContext, it)
            }
            if (saved?.swapToLightningMintJson != null) {
                return@withPaymentOperation SwapResult.Pending(saved.id)
            }
            Log.d(
                TAG,
                "swapFromUnknownMint: start " +
                    "unknownMintUrl=$unknownMintUrl, " +
                    "expectedAmount=$expectedAmount, " +
                    "paymentContext.paymentId=${paymentContext.paymentId}, " +
                    "paymentContext.amountSats=${paymentContext.amountSats}, " +
                    "proofsCount=${proofs.size}"
            )

            // 1) Create a temporary single-mint Wallet and receive the payer's
            //    token so that it holds the proofs we want to melt. Its seed and
            //    database are retained locally for recovery.

            val mintManager = MintManager.getInstance(appContext)
            val paymentUnit = paymentContext.unit ?: mintManager.getPreferredUnit()
            val recoveryId = paymentContext.paymentId ?: java.util.UUID.randomUUID().toString()
            val tempWallet = try {
                temporaryWalletFactory(appContext, recoveryId, unknownMintUrl, paymentUnit)
            } catch (t: Throwable) {
                val msg = "Failed to create temporary wallet for unknown mint: ${'$'}{t.message}"
                Log.e(TAG, msg, t)
                return@withPaymentOperation SwapResult.Failure(msg)
            }
            try {
                tempWallet.keysets(org.cashudevkit.KeysetLoadPolicy.REFRESH)

                Log.d(TAG, "swapFromUnknownMint: temporary wallet created for mint=$unknownMintUrl")

                val wallet = CashuWalletManager.getWallet()
                    ?: run {
                        Log.e(TAG, "swapFromUnknownMint: main wallet not initialized for Lightning mint")
                        return@withPaymentOperation SwapResult.Failure("Wallet not initialized for Lightning mint")
                    }

                Log.d(TAG, "swapFromUnknownMint: main wallet for Lightning mint is available")

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
                    return@withPaymentOperation SwapResult.Failure(msg)
                }

                val lightningMintUrl = mintManager.getPreferredLightningMint(paymentUnit)
                    ?: run {
                        Log.e(TAG, "No preferred Lightning mint configured")
                            return@withPaymentOperation SwapResult.Failure("No Lightning mint configured")
                    }
                Log.d(TAG, "swapFromUnknownMint: preferred Lightning mint is $lightningMintUrl")

                // Check if the preferred lightning mint actually supports bolt11
                val limits = mintManager.getMintLimits(lightningMintUrl, appContext)
                val limitCheck = com.electricdreams.numo.core.util.MintLimitChecker.checkMintLimits(paymentContext.amountSats, limits, paymentUnit)
                if (!limitCheck.isBolt11Supported) {
                    val msg = "Preferred mint does not support Lightning (bolt11). Cannot perform swap."
                    Log.e(TAG, msg)
                    return@withPaymentOperation SwapResult.Failure(msg)
                }
                if (!limitCheck.isValid) {
                    val msg = "Amount ${paymentContext.amountSats} is not within Lightning limits for preferred mint."
                    Log.e(TAG, msg)
                    return@withPaymentOperation SwapResult.Failure(msg)
                }

                Log.d(
                    TAG,
                    "swapFromUnknownMint: requesting initial Lightning mint quote for fee estimation: " +
                        "lightningMintUrl=$lightningMintUrl, amount=$lightningAmount"
                )

                val lightningMintUrlObj = MintUrl(lightningMintUrl)
                val unit = CashuWalletManager.getCurrencyUnit(paymentUnit)
                val lightningWallet = wallet.getWallet(lightningMintUrlObj, unit)
                    ?: run {
                        Log.e(TAG, "Failed to get Lightning wallet for: $lightningMintUrl")
                        return@withPaymentOperation SwapResult.Failure("Failed to get Lightning wallet")
                    }
                val mintQuote = lightningWallet.mintQuote(org.cashudevkit.PaymentMethod.Bolt11, CdkAmount(lightningAmount.toULong()), null, null)

                Log.d(
                    TAG,
                    "swapFromUnknownMint: received initial Lightning mint quote: " +
                        "quoteId=${mintQuote.id}, requestLength=${mintQuote.request.length}"
                )

                var meltQuote: MeltQuote = try {
                    Log.d(TAG, "swapFromUnknownMint: requesting initial melt quote from unknown mint for preliminary Lightning invoice")
                    tempWallet.meltQuote(org.cashudevkit.PaymentMethod.Bolt11, mintQuote.request, null, null)
                } catch (t: Throwable) {
                    val msg = "Failed to request melt quote from unknown mint: ${'$'}{t.message}"
                    Log.e(TAG, msg, t)
                    return@withPaymentOperation SwapResult.Failure(msg)
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
                    return@withPaymentOperation SwapResult.Failure(msg)
                }

                val minOverhead = kotlin.math.ceil(paymentContext.amountSats * MIN_FEE_OVERHEAD).toLong()
                lightningAmount = paymentContext.amountSats - minOverhead - feeReserveEstimate

                Log.d(
                    TAG,
                    "swapFromUnknownMint: adjusted Lightning amount=$lightningAmount after applying " +
                        "minFeeOverheadCeil=$minOverhead and feeReserveEstimate=$feeReserveEstimate"
                )

                if (lightningAmount <= 0L) {
                    val msg = "Adjusted lightning amount is non-positive after fees (overhead=$minOverhead, feeReserve=$feeReserveEstimate, received=${paymentContext.amountSats})"
                    Log.e(TAG, msg)
                    return@withPaymentOperation SwapResult.Failure(msg)
                }

                Log.d(
                    TAG,
                    "swapFromUnknownMint: requesting Lightning mint quote: " +
                        "lightningMintUrl=$lightningMintUrl, mintQuoteAmount=$lightningAmount"
                )

                val finalMintQuote = lightningWallet.mintQuote(org.cashudevkit.PaymentMethod.Bolt11, CdkAmount(lightningAmount.toULong()), null, null)

                val bolt11 = finalMintQuote.request
                Log.d(
                    TAG,
                    "swapFromUnknownMint: using Lightning invoice for swap: " +
                        "quoteId=${finalMintQuote.id}, bolt11Length=${bolt11.length}"
                )

                // 4) Request a melt quote from the unknown mint for this bolt11.
                meltQuote = try {
                    Log.d(TAG, "swapFromUnknownMint: requesting final melt quote from unknown mint using Lightning bolt11 invoice")
                    tempWallet.meltQuote(org.cashudevkit.PaymentMethod.Bolt11, bolt11, null, null)
                } catch (t: Throwable) {
                    val msg = "Failed to request melt quote from unknown mint: ${'$'}{t.message}"
                    Log.e(TAG, msg, t)
                    return@withPaymentOperation SwapResult.Failure(msg)
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
                    return@withPaymentOperation SwapResult.Failure(msg)
                }

                val totalMeltRequired = quoteAmount + feeReserve
                if (totalMeltRequired > paymentContext.amountSats) {
                    val msg = "Unknown-mint melt requires $totalMeltRequired sats but temp wallet balance is ${paymentContext.amountSats}"
                    Log.w(TAG, msg)
                    return@withPaymentOperation SwapResult.Failure(msg)
                }

                var frame = PaymentHistoryEntry.Companion.SwapToLightningMintFrame(
                    unknownMintUrl = unknownMintUrl,
                    meltQuoteId = meltQuote.id,
                    lightningMintUrl = lightningMintUrl,
                    lightningQuoteId = finalMintQuote.id,
                )
                val prepared = try {
                    tempWallet.prepareMeltProofs(meltQuote.id, proofs)
                } catch (exception: Exception) {
                    Log.e(TAG, "Could not prepare swap payment", exception)
                    return@withPaymentOperation SwapResult.Failure(
                        exception.message ?: "Could not prepare swap payment")
                }
                val paymentId = try {
                    val proofAmount = proofs.fold(0uL) { total, proof ->
                        val next = total + proof.amount.value
                        require(next >= total) { "Proof amount overflow" }
                        next
                    }
                    val required = totalMeltRequired.toULong()
                    require(proofAmount >= required &&
                        prepared.inputFeeWithoutSwap().value <= proofAmount - required) {
                        "Cashu proofs do not cover payment and input fees"
                    }
                    PaymentsHistoryActivity.recordPendingSwap(appContext, recoveryId,
                        expectedAmount, paymentUnit, frame)
                } catch (exception: Exception) {
                    try {
                        prepared.cancel()
                    } catch (cleanupException: Exception) {
                        Log.w(TAG, "Could not cancel swap preparation", cleanupException)
                    }
                    Log.e(TAG, "Could not prepare pending swap", exception)
                    return@withPaymentOperation SwapResult.Failure(
                        exception.message ?: "Could not prepare pending payment")
                }
                // 5) Execute melt on unknown mint using the temporary single-mint wallet.
                val finalized: FinalizedMelt = try {
                    Log.d(
                        TAG,
                        "swapFromUnknownMint: executing melt on unknown mint: " +
                            "meltQuoteId=${meltQuote.id}, proofsCount=${proofs.size}, totalMeltRequired=$totalMeltRequired"
                    )

                    prepared.confirmWithOptions(MeltConfirmOptions(skipSwap = true))
                } catch (exception: Exception) {
                    Log.w(TAG, "Swap settlement requires a later status check", exception)
                    return@withPaymentOperation SwapResult.Pending(paymentId)
                }

                if (finalized.state != QuoteState.PAID) {
                    return@withPaymentOperation SwapResult.Pending(paymentId)
                }
                frame = frame.copy(meltPaid = true)
                try {
                    PaymentsHistoryActivity.recordPendingSwap(appContext, paymentId,
                        expectedAmount, paymentUnit, frame)
                } catch (exception: Exception) {
                    Log.w(TAG, "Could not refresh pending swap metadata", exception)
                }
                try {
                    WalletLogger.log("OUT", quoteAmount, unknownMintUrl, "Melt successful during swap")
                    val entry = PaymentsHistoryActivity.getPaymentEntryById(appContext, paymentId)
                    if (entry != null && tryFinalizePendingSwap(appContext, entry)) {
                        return@withPaymentOperation SwapResult.Success(lightningMintUrl, expectedAmount)
                    }
                } catch (exception: Exception) {
                    Log.w(TAG, "Swap confirmation requires a later status check", exception)
                }
                SwapResult.Pending(paymentId)
            } finally {
                try {
                    tempWallet.close()
                } catch (exception: Exception) {
                    Log.w(TAG, "Could not close swap wallet", exception)
                }
            }
        }
    }

    /**
     * Attempts to finalize a pending swap-to-Lightning-mint transaction by checking if the
     * associated Lightning invoice has been paid.
     *
     * @return true if the payment was successfully finalized (minted), false otherwise.
     */
    suspend fun tryFinalizePendingSwap(
        appContext: Context,
        entry: PaymentHistoryEntry
    ): Boolean = withPaymentOperation(Dispatchers.IO) {
        finalizationMutex.withLock {
            val saved = PaymentsHistoryActivity.getPaymentEntryById(appContext, entry.id) ?: entry
            val swapJson = saved.swapToLightningMintJson ?: return@withPaymentOperation false
            try {
                val frame = Gson().fromJson(swapJson,
                    PaymentHistoryEntry.Companion.SwapToLightningMintFrame::class.java)
                val wallet = CashuWalletManager.getWallet() ?: return@withPaymentOperation false
                val unit = CashuWalletManager.getCurrencyUnit(saved.getUnit())
                val mintWallet = wallet.getWallet(MintUrl(frame.lightningMintUrl), unit)
                val quote = mintWallet.checkMintQuote(frame.lightningQuoteId)
                when (quote.state) {
                    QuoteState.PAID -> {
                        val proofs = mintWallet.mint(frame.lightningQuoteId,
                            org.cashudevkit.SplitTarget.None, null)
                        if (proofs.isEmpty()) return@withPaymentOperation false
                    }
                    QuoteState.ISSUED -> Unit
                    else -> {
                        if (!frame.meltPaid) {
                            temporaryWalletFactory(appContext, saved.id, frame.unknownMintUrl,
                                saved.getUnit()).use { it.recoverIncompleteSagas() }
                        }
                        return@withPaymentOperation false
                    }
                }
                PaymentsHistoryActivity.completePendingPayment(
                    context = appContext,
                    paymentId = saved.id,
                    token = "",
                    paymentType = PaymentHistoryEntry.TYPE_LIGHTNING,
                    mintUrl = frame.lightningMintUrl,
                    lightningQuoteId = frame.lightningQuoteId,
                    lightningMintUrl = frame.lightningMintUrl,
                )
                BalanceRefreshBroadcast.send(appContext,
                    "swap_finalized")
                PaymentsHistoryActivity.getPaymentEntryById(appContext, saved.id)?.isCompleted() == true
            } catch (exception: Exception) {
                Log.w(TAG, "Could not confirm pending swap yet", exception)
                false
            }
        }
    }
}
