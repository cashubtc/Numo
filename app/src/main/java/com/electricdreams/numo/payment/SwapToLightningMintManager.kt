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
    private const val MIN_FEE_OVERHEAD = 0.01

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
        Log.d(TAG, "Starting swapFromUnknownMint for mint=$unknownMintUrl amount=$expectedAmount sats")

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

        val wallet = CashuWalletManager.getWallet()
                ?: return@withContext SwapResult.Failure("Wallet not initialized for Lightning mint")

        val cdkToken = org.cashudevkit.Token.decode(cashuToken)

        // We request a mint quote, just to have the bolt11 request to feed to the melt quote request
        // Then, we take the fee estimate from the melt quote response and
        // ask for a new mint quote with the adjusted amount

        val feeBuffer = (expectedAmount * MAX_FEE_RESERVE_RATIO).toLong()
        var lightningAmount = expectedAmount - feeBuffer

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

        val mintQuote = wallet.mintQuote(MintUrl(lightningMintUrl), CdkAmount(lightningAmount.toULong()), null)

        var meltQuote: MeltQuote = try {
            tempWallet.meltQuote(mintQuote.request, null)
        } catch (t: Throwable) {
            val msg = "Failed to request melt quote from unknown mint: ${'$'}{t.message}"
            Log.e(TAG, msg, t)
            tempWallet.close()
            return@withContext SwapResult.Failure(msg)
        }

        val feeReserveEstimate = meltQuote.feeReserve.value.toLong()
        if (feeReserveEstimate > feeBuffer) {
            val msg = "Lightning fee reserve estimate is too big ($feeReserveEstimate)"
            Log.e(TAG, msg)
            try { tempWallet.close() } catch (_: Throwable) {}
            return@withContext SwapResult.Failure(msg)
        }

        lightningAmount = expectedAmount - (expectedAmount * MIN_FEE_OVERHEAD).roundToLong() - feeReserveEstimate
        val adjustedPaymentContext = paymentContext.copy(amountSats = lightningAmount)

        Log.d(
            TAG,
            "swapFromUnknownMint: requesting Lightning mint quote: " +
                "lightningMintUrl=$lightningMintUrl, mintQuoteAmount=$lightningAmount"
        )

        val lightningInvoiceInfo = LightningMintInvoiceManager.getOrCreateInvoiceForPayment(
            appContext = appContext,
            lightningMintUrl = lightningMintUrl,
            paymentContext = adjustedPaymentContext
        )

        val bolt11 = lightningInvoiceInfo.bolt11
        Log.d(TAG, "Using Lightning invoice for swap: quoteId=${'$'}{lightningInvoiceInfo.quoteId} bolt11=$bolt11")

        // 4) Request a melt quote from the unknown mint for this bolt11.
        meltQuote = try {
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
                    lightningQuoteId = lightningInvoiceInfo.quoteId,
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
            // TODO: extract the proofs and pass them to `tempWallet.meltProofs` 
            tempWallet.melt(meltQuote.id)
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

        if (melted.state != org.cashudevkit.QuoteState.PAID) {
            val msg = "Unknown-mint melt did not complete: state=${melted.state}"
            Log.w(TAG, msg)
            return@withContext SwapResult.Failure(msg)
        }

        val preimageHex = melted.preimage
            ?: run {
                val msg = "Unknown-mint melt result is PAID but has no preimage"
                Log.e(TAG, msg)
                return@withContext SwapResult.Failure(msg)
            }

        // 6) Verify preimage vs. Lightning invoice payment hash
        try {
            verifyMeltPreimageMatchesInvoice(preimageHex, bolt11)
        } catch (t: Throwable) {
            val msg = "Payment preimage verification failed: ${t.message}"
            Log.e(TAG, msg, t)
            return@withContext SwapResult.Failure(msg)
        }

        // 7) Attempt to mint the Lightning mint quote so that the merchant
        //    receives ecash on their Lightning mint. This is done
        //    opportunistically as part of the swap flow; other components
        //    (e.g. LightningMintHandler) may also mint the quote if they
        //    were started from a separate Lightning-receive UI flow.
        try {
            val wallet = CashuWalletManager.getWallet()
                ?: return@withContext SwapResult.Failure("Wallet not initialized for Lightning mint")

            val lightningMint = org.cashudevkit.MintUrl(lightningMintUrl)

            Log.d(TAG, "Checking Lightning mint quote state for quoteId=${lightningInvoiceInfo.quoteId}")
            val lightningQuote = wallet.checkMintQuote(lightningMint, lightningInvoiceInfo.quoteId)
            val stateStr = lightningQuote.state.toString()
            Log.d(TAG, "Lightning mint quote state=$stateStr")

            if (stateStr.equals("PAID", ignoreCase = true) ||
                stateStr.equals("ISSUED", ignoreCase = true)
            ) {
                Log.d(TAG, "Lightning quote is paid; attempting wallet.mint for quoteId=${lightningInvoiceInfo.quoteId}")
                try {
                    val proofs = wallet.mint(lightningMint, lightningInvoiceInfo.quoteId, null)
                    Log.d(TAG, "Minted ${'$'}{proofs.size} proofs on Lightning mint as part of swap flow")
                } catch (mintError: Throwable) {
                    // Not fatal for the POS payment itself; the Lightning
                    // handler / operator can reconcile, but we log loudly.
                    Log.e(TAG, "Failed to mint proofs on Lightning mint for quoteId=${'$'}{lightningInvoiceInfo.quoteId}: ${'$'}{mintError.message}", mintError)
                }
            } else {
                Log.d(TAG, "Lightning mint quote not yet paid (state=${'$'}stateStr), skipping immediate mint")
            }
        } catch (t: Throwable) {
            // Swallow non-fatal errors here; payment is already secured by
            // the unknown-mint melt + preimage verification. LightningMintHandler
            // or manual reconciliation can still complete the mint later.
            Log.w(TAG, "Error while attempting to mint Lightning quote after swap: ${'$'}{t.message}", t)
        }

        // At this point, we know the unknown mint has paid the exact invoice
        // we generated from our Lightning mint. The LightningMintHandler is
        // responsible for monitoring the quote and minting proofs. From the
        // POS perspective, we can treat the swap as successful and rely on
        // the Lightning flow to finalize balances.

        Log.d(TAG, "Swap from unknown mint completed successfully for paymentId=${paymentContext.paymentId}")

        return@withContext SwapResult.Success(
            finalToken = cashuToken,
            lightningMintUrl = lightningMintUrl,
            amountSats = expectedAmount
        )
    }

    // ---------------------------------------------------------------------
    // BOLT11 + preimage verification utilities
    // ---------------------------------------------------------------------

    /**
     * Verify that the preimage reported by the unknown mint for a given melt
     * quote matches the payment hash encoded in the BOLT11 invoice.
     *
     * @throws IllegalStateException if the hashes do not match or parsing fails.
     */
    fun verifyMeltPreimageMatchesInvoice(
        preimageHex: String,
        bolt11Invoice: String
    ) {
        val preimageBytes = hexToBytes(preimageHex)
        val digest = MessageDigest.getInstance("SHA-256")
        val computedHash = digest.digest(preimageBytes)
        val invoiceHash = extractPaymentHashFromBolt11(bolt11Invoice)

        if (!computedHash.contentEquals(invoiceHash)) {
            throw IllegalStateException("Payment preimage does not match invoice payment hash")
        }
    }

    /**
     * Extract the 32-byte payment hash from a BOLT11 invoice.
     *
     * This implementation performs a minimal BOLT11 parse:
     * - Decodes Bech32 to obtain HRP and 5-bit data part.
     * - Converts 5-bit groups to 8-bit bytes.
     * - Walks the tagged field stream to locate the 'p' tag (payment hash).
     *
     * It intentionally supports only the subset of BOLT11 used for typical
     * Lightning invoices and does not implement every optional tag.
     *
     * @throws IllegalArgumentException if the invoice is invalid or does
     *         not contain a payment hash.
     */
    fun extractPaymentHashFromBolt11(bolt11: String): ByteArray {
        if (bolt11.isBlank()) {
            throw IllegalArgumentException("Empty BOLT11 invoice")
        }

        // 1) Decode Bech32 invoice
        val bech = bolt11.lowercase().removePrefix("lightning:")
        val bechData = Bech32.decode(bech)
        val data5 = bechData.data

        // 2) Convert 5-bit groups to 8-bit bytes
        val dataBytes = Bech32.convertBits(data5, 5, 8, false)
        if (dataBytes.isEmpty()) {
            throw IllegalArgumentException("Invalid BOLT11: empty data payload")
        }

        // 3) Separate the payment data (timestamp + tagged fields) from the
        // signature and recovery id at the end (65 bytes total).
        if (dataBytes.size < 65) {
            throw IllegalArgumentException("Invalid BOLT11: payload too short for signature")
        }
        val payload = dataBytes.copyOf(dataBytes.size - 65)

        // First 7 bytes of payload are the 35-bit timestamp (big-endian).
        if (payload.size <= 7) {
            throw IllegalArgumentException("Invalid BOLT11: payload too short for timestamp")
        }
        var idx = 7 // start after timestamp

        // 4) Iterate tagged fields until we find tag 'p' (payment hash)
        while (idx < payload.size) {
            val tag = payload[idx].toInt().toChar()
            idx += 1
            if (idx + 1 > payload.size) {
                break
            }

            // length is 10 bits stored in 2 bytes (big-endian), measured in 5-bit groups
            val dataLength = ((payload[idx].toInt() and 0xFF) shl 5) or
                (payload[idx + 1].toInt() and 0xFF)
            idx += 2

            val byteLength = (dataLength * 5 + 7) / 8
            if (idx + byteLength > payload.size) {
                break
            }

            if (tag == 'p') {
                val hashBytes = payload.copyOfRange(idx, idx + byteLength)
                if (hashBytes.size != 32) {
                    throw IllegalArgumentException("Invalid BOLT11: payment hash length ${hashBytes.size} != 32")
                }
                return hashBytes
            }

            idx += byteLength
        }

        throw IllegalArgumentException("BOLT11 invoice does not contain a payment hash")
    }

    /** Decode a hex string into a ByteArray. */
    private fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.trim().lowercase()
        if (cleaned.length % 2 != 0) {
            throw IllegalArgumentException("Hex string must have even length")
        }
        val out = ByteArray(cleaned.length / 2)
        var i = 0
        while (i < cleaned.length) {
            val byte = cleaned.substring(i, i + 2).toInt(16)
            out[i / 2] = byte.toByte()
            i += 2
        }
        return out
    }
}
