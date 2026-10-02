package com.electricdreams.numo.payment

import android.content.Context
import android.util.Log
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.MintQuote
import org.cashudevkit.MintUrl
import org.cashudevkit.PaymentMethod
import org.cashudevkit.QuoteState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles Lightning payment flow via mint quote and WebSocket subscription (NUT-17).
 *
 * This class encapsulates:
 * - Creating a mint quote for a Lightning invoice
 * - Subscribing to quote state updates via WebSocket
 * - Minting proofs once the invoice is paid
 *
 * @param preferredMint Optional preferred mint URL for Lightning payments. If null or invalid,
 *                      falls back to the first allowed mint.
 * @param allowedMints List of allowed mint URLs (used as fallback if preferredMint is invalid)
 * @param uiScope Coroutine scope for UI callbacks
 */
class LightningMintHandler(
    private val context: Context,
    private val preferredMint: String?,
    private val allowedMints: List<String>,
    private val uiScope: CoroutineScope,
    // Allows injecting a mock dispatcher for testing
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val quoteSockets: MintQuoteWebSocket = MintQuoteWebSocket(uiScope),
) {
    // Secondary constructor to maintain compatibility
    constructor(
        context: Context,
        preferredMint: String?,
        allowedMints: List<String>,
        uiScope: CoroutineScope
    ) : this(context, preferredMint, allowedMints, uiScope, Dispatchers.IO)
    /**
     * Callback interface for Lightning mint events.
     */
    interface Callback {
        /** Called when a Lightning invoice (BOLT11) is ready for display */
        fun onInvoiceReady(bolt11: String, quoteId: String, mintUrl: String)
        
        /** Called when the Lightning payment is successful and proofs are minted */
        fun onPaymentSuccess()
        
        /** Called when an error occurs in the Lightning flow */
        fun onError(message: String)
    }

    private var mintQuote: MintQuote? = null
    private var currentMintUrl: String? = null
    private var mintJob: Job? = null
    
    /** Only one source may issue proofs at a time. */
    private val mintCalled = AtomicBoolean(false)
    private val mintCompleted = AtomicBoolean(false)

    /** The current mint quote, if any */
    val currentQuote: MintQuote? get() = mintQuote

    /** The current BOLT11 invoice string, if available */
    val currentInvoice: String? get() = mintQuote?.request

    /** The current quote ID, if available */
    val currentQuoteId: String? get() = mintQuote?.id

    /** The mint URL being used for the current quote */
    val mintUrlString: String? get() = currentMintUrl

    /**
     * Start the Lightning mint flow for the specified amount.
     *
     * @param paymentAmount Amount in satoshis to request
     * @param callback Callback for Lightning mint events
     */
    fun start(paymentAmount: Long, callback: Callback) {
        val wallet = CashuWalletManager.getWallet()
        if (wallet == null) {
            Log.w(TAG, "WalletRepository not ready, skipping Lightning")
            callback.onError("Wallet not ready")
            return
        }

        if (allowedMints.isEmpty() && preferredMint == null) {
            Log.w(TAG, "No allowed mints configured, cannot request Lightning mint quote")
            callback.onError("No mints configured")
            return
        }

        // Use preferred mint if set and valid, otherwise fall back to first allowed mint
        val mintUrlStr = if (preferredMint != null && (allowedMints.isEmpty() || allowedMints.contains(preferredMint))) {
            preferredMint
        } else {
            allowedMints.firstOrNull() ?: run {
                Log.e(TAG, "No valid mint available for Lightning")
                callback.onError("No mints configured")
                return
            }
        }
        
        Log.d(TAG, "Using mint for Lightning: $mintUrlStr (preferred: $preferredMint)")
        
        val mintUrl = try {
            MintUrl(mintUrlStr)
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid mint URL for Lightning mint: $mintUrlStr", t)
            callback.onError("Invalid mint URL")
            return
        }

        currentMintUrl = mintUrlStr

        // Reset the mint-called flag for this new payment
        mintCalled.set(false)
        mintCompleted.set(false)

        mintJob?.cancel()
        mintJob = uiScope.launch(ioDispatcher) {
            try {
                // CDK Amount is in minor units of wallet's CurrencyUnit (we constructed wallet in sats)
                val quoteAmount = CdkAmount(paymentAmount.toULong())

                Log.d(TAG, "Requesting Lightning mint quote from ${mintUrl.url} for $paymentAmount sats")
                val unitStr = com.electricdreams.numo.core.util.MintManager.getInstance(context).getPreferredUnit()
                val unit = CashuWalletManager.getCurrencyUnit(unitStr)
                val mintWallet = wallet.getWallet(mintUrl, unit)

                val nut04 = mintWallet.loadMintInfo().nuts.nut04
                val supportsDescription = nut04?.methods?.any {
                    it.method == PaymentMethod.Bolt11 && it.description == true
                } == true
                val description = if (supportsDescription) {
                    context.getString(R.string.payment_request_lightning_description, paymentAmount)
                } else {
                    null
                }
                val quote = mintWallet?.mintQuote(PaymentMethod.Bolt11, quoteAmount, description, null)
                    ?: throw Exception("Failed to get wallet for mint: ${mintUrl.url}")
                mintQuote = quote

                val bolt11 = quote.request
                Log.d(TAG, "Received Lightning mint quote id=${quote.id} bolt11=$bolt11")

                // Notify UI that invoice is ready with full quote info
                launch(Dispatchers.Main) {
                    callback.onInvoiceReady(bolt11, quote.id, mintUrlStr)
                }

                monitorQuote(mintUrl, quote.id, callback)

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Error in Lightning mint flow: ${e.message}", e)
                launch(Dispatchers.Main) {
                    callback.onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Resume monitoring an existing Lightning mint quote.
     * Used when reopening a pending payment from history.
     *
     * @param quoteId The existing quote ID
     * @param mintUrlStr The mint URL as a string
     * @param invoice The BOLT11 invoice string
     * @param callback Callback for Lightning mint events
     */
    fun resume(quoteId: String, mintUrlStr: String, invoice: String, callback: Callback) {
        val wallet = CashuWalletManager.getWallet()
        if (wallet == null) {
            Log.w(TAG, "MultiMintWallet not ready, cannot resume Lightning quote")
            callback.onError("Wallet not ready")
            return
        }

        val mintUrl = try {
            MintUrl(mintUrlStr)
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid mint URL for resume: $mintUrlStr", t)
            callback.onError("Invalid mint URL")
            return
        }

        currentMintUrl = mintUrlStr

        // Reset the mint-called flag for this resumed payment
        mintCalled.set(false)
        mintCompleted.set(false)

        mintJob?.cancel()
        mintJob = uiScope.launch(ioDispatcher) {
            try {
                Log.d(TAG, "Resuming Lightning mint quote monitoring for id=$quoteId")

                // Notify UI that invoice is ready (for display)
                launch(Dispatchers.Main) {
                    callback.onInvoiceReady(invoice, quoteId, mintUrlStr)
                }

                monitorQuote(mintUrl, quoteId, callback)

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Error in resumed Lightning mint flow: ${e.message}", e)
                launch(Dispatchers.Main) {
                    callback.onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Cancel the Lightning mint flow.
     */
    fun cancel() {
        mintJob?.cancel()
        mintJob = null
        mintCalled.set(false)
        mintCompleted.set(false)
    }

    /** Both quote types share the injected connection; polling covers unavailable push updates. */
    private suspend fun monitorQuote(
        mintUrl: MintUrl,
        quoteId: String,
        callback: Callback,
    ) = coroutineScope {
        val wsJob = launch {
            try {
                awaitMintQuotePaid(mintUrl, quoteId)
                tryMintOnce(mintUrl, quoteId, callback, "WebSocket")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Lightning WebSocket monitoring failed", error)
            }
        }
        try {
            pollForQuotePaid(mintUrl, quoteId, callback)
        } finally {
            // A polling win must release only this subscription, leaving Arkoor active.
            wsJob.cancelAndJoin()
        }
    }

    private suspend fun awaitMintQuotePaid(mintUrl: MintUrl, quoteId: String) {
        val subscription = quoteSockets.subscribe(mintUrl.url, "bolt11_mint_quote", quoteId)
        try {
            while (true) {
                val payload = subscription.awaitUpdate(POLL_INTERVAL_MS) ?: continue
                val state = payload.get("state")?.asString ?: continue
                if (state.equals("PAID", ignoreCase = true) ||
                    state.equals("ISSUED", ignoreCase = true)
                ) return
            }
        } finally {
            subscription.close()
        }
    }

    /**
     * Issue proofs once per successful payment, allowing retries after failed issuance.
     * The atomic flag prevents WebSocket and polling from issuing concurrently.
     *
     * @param mintUrl The mint URL
     * @param quoteId The quote ID to mint
     * @param callback Callback for success/error
     * @param source Description of what triggered the mint (for logging)
     * @return true if mint was performed, false if already called by another source
     */
    private suspend fun tryMintOnce(
        mintUrl: MintUrl,
        quoteId: String,
        callback: Callback,
        source: String
    ): Boolean {
        // Atomic check-and-set: only the first caller wins
        if (!mintCalled.compareAndSet(false, true)) {
            Log.d(TAG, "Mint already called by another source, ignoring call from $source")
            return false
        }

        try {
            val wallet = CashuWalletManager.getWallet()
            if (wallet == null) {
                Log.e(TAG, "Wallet not available for minting")
                uiScope.launch(Dispatchers.Main) {
                    callback.onError("Wallet not ready")
                }
                return false
            }

            Log.d(TAG, "Mint quote $quoteId is paid (detected by $source), calling wallet.mint")
            val unitStr = com.electricdreams.numo.core.util.MintManager.getInstance(context).getPreferredUnit()
            val unit = com.electricdreams.numo.core.cashu.CashuWalletManager.getCurrencyUnit(unitStr)
            val mintWallet = wallet.getWallet(mintUrl, unit)
            val proofs = mintWallet?.mint(quoteId, org.cashudevkit.SplitTarget.None, null)
                ?: run {
                    Log.e(TAG, "Failed to get wallet for mint: ${mintUrl.url}")
                    uiScope.launch(Dispatchers.Main) {
                        callback.onError("Wallet not ready")
                    }
                    return false
                }
            Log.d(TAG, "Lightning mint completed with ${proofs.size} proofs ($source)")

            mintCompleted.set(true)
            uiScope.launch(Dispatchers.Main) {
                callback.onPaymentSuccess()
            }
            return true
        } finally {
            if (!mintCompleted.get()) mintCalled.set(false)
        }
    }

    /**
     * Poll for mint quote state until paid or cancelled.
     * Uses checkMintQuote API to query the mint directly.
     *
     * @param mintUrl The mint URL
     * @param quoteId The quote ID to check
     * @param callback Callback for success/error
     */
    private suspend fun pollForQuotePaid(
        mintUrl: MintUrl,
        quoteId: String,
        callback: Callback
    ) {
        val wallet = CashuWalletManager.getWallet() ?: return
        
        Log.d(TAG, "Starting polling for mint quote $quoteId (interval: ${POLL_INTERVAL_MS}ms)")
        
        while (!mintCompleted.get()) {
            try {
                delay(POLL_INTERVAL_MS)
                
                if (mintCompleted.get()) break
                // Do not stop monitoring (and cancel the socket task) during issuance.
                if (mintCalled.get()) continue
                
                Log.v(TAG, "Polling mint quote state for $quoteId")
                
                // Check quote state using checkMintQuote API
                val unitStr = com.electricdreams.numo.core.util.MintManager.getInstance(context).getPreferredUnit()
                val unit = CashuWalletManager.getCurrencyUnit(unitStr)
                val mintWallet = wallet.getWallet(mintUrl, unit)
                    ?: throw Exception("Failed to get wallet for mint: ${mintUrl.url}")
                
                val quote = mintWallet.checkMintQuote(quoteId)
                
                when (quote.state) {
                    QuoteState.PAID, QuoteState.ISSUED -> {
                        Log.d(TAG, "Quote $quoteId is ${quote.state} (detected via polling)")
                        if (tryMintOnce(mintUrl, quoteId, callback, "polling")) break
                    }
                    QuoteState.UNPAID -> {
                        Log.v(TAG, "Quote $quoteId still UNPAID, continuing poll")
                        // Continue polling
                    }
                    else -> {
                        Log.w(TAG, "Quote $quoteId in unexpected state: ${quote.state}")
                        // Continue polling for other states
                    }
                }
            } catch (ce: CancellationException) {
                Log.d(TAG, "Polling cancelled for quote $quoteId")
                throw ce
            } catch (e: Exception) {
                // Log but continue polling - transient errors shouldn't stop us
                Log.w(TAG, "Error polling mint quote $quoteId: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "LightningMintHandler"
        
        /** Polling interval for checking mint quote state (in milliseconds) */
        const val POLL_INTERVAL_MS = 5000L
    }
}
