package com.electricdreams.numo.payment

import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.MintQuote
import org.cashudevkit.MintUrl
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    private val preferredMint: String?,
    private val allowedMints: List<String>,
    private val uiScope: CoroutineScope,
    // Allows injecting a mock dispatcher for testing
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // Secondary constructor to maintain compatibility
    constructor(
        preferredMint: String?,
        allowedMints: List<String>,
        uiScope: CoroutineScope
    ) : this(preferredMint, allowedMints, uiScope, Dispatchers.IO)
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
    
    /** Atomic flag to ensure mint is only called once (WebSocket vs polling race) */
    private val mintCalled = AtomicBoolean(false)

    // Shared OkHttp client for mint WebSocket connections
    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout, rely on WS pings
            .build()
    }
    
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
            Log.w(TAG, "MultiMintWallet not ready, skipping Lightning")
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

        mintJob?.cancel()
        mintJob = uiScope.launch(ioDispatcher) {
            try {
                // CDK Amount is in minor units of wallet's CurrencyUnit (we constructed wallet in sats)
                val quoteAmount = CdkAmount(paymentAmount.toULong())

                Log.d(TAG, "Requesting Lightning mint quote from ${mintUrl.url} for $paymentAmount sats")
                val quote = wallet.mintQuote(mintUrl, quoteAmount, "Numo POS payment of $paymentAmount sats")
                mintQuote = quote

                val bolt11 = quote.request
                Log.d(TAG, "Received Lightning mint quote id=${quote.id} bolt11=$bolt11")

                // Notify UI that invoice is ready with full quote info
                launch(Dispatchers.Main) {
                    callback.onInvoiceReady(bolt11, quote.id, mintUrlStr)
                }

                // Start both WebSocket subscription and polling in parallel
                // Whichever detects payment first will call tryMintOnce (atomic, only one wins)
                val wsJob = launch {
                    try {
                        Log.d(TAG, "Starting WebSocket subscription for quote ${quote.id}")
                        awaitMintQuotePaid(mintUrl, quote.id)
                        // WebSocket detected quote is paid, attempt to mint (only first caller wins)
                        tryMintOnce(mintUrl, quote.id, callback, "WebSocket")
                    } catch (ce: CancellationException) {
                        Log.d(TAG, "WebSocket subscription cancelled for quote ${quote.id}")
                    } catch (e: Exception) {
                        Log.e(TAG, "WebSocket error for quote ${quote.id}: ${e.message}", e)
                    }
                }

                val pollJob = launch {
                    try {
                        pollForQuotePaid(mintUrl, quote.id, callback)
                    } catch (ce: CancellationException) {
                        Log.d(TAG, "Polling cancelled for quote ${quote.id}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Polling error for quote ${quote.id}: ${e.message}", e)
                    }
                }

                // Wait for both to complete (one will finish first and mint, the other will detect mintCalled)
                wsJob.join()
                pollJob.join()

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

        mintJob?.cancel()
        mintJob = uiScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Resuming Lightning mint quote monitoring for id=$quoteId")

                // Notify UI that invoice is ready (for display)
                launch(Dispatchers.Main) {
                    callback.onInvoiceReady(invoice, quoteId, mintUrlStr)
                }

                // Start both WebSocket subscription and polling in parallel
                // Whichever detects payment first will call tryMintOnce (atomic, only one wins)
                val wsJob = launch {
                    try {
                        Log.d(TAG, "Starting WebSocket subscription for resumed quote $quoteId")
                        awaitMintQuotePaid(mintUrl, quoteId)
                        // WebSocket detected quote is paid, attempt to mint (only first caller wins)
                        tryMintOnce(mintUrl, quoteId, callback, "WebSocket (resume)")
                    } catch (ce: CancellationException) {
                        Log.d(TAG, "WebSocket subscription cancelled for resumed quote $quoteId")
                    } catch (e: Exception) {
                        Log.e(TAG, "WebSocket error for resumed quote $quoteId: ${e.message}", e)
                    }
                }

                val pollJob = launch {
                    try {
                        pollForQuotePaid(mintUrl, quoteId, callback)
                    } catch (ce: CancellationException) {
                        Log.d(TAG, "Polling cancelled for resumed quote $quoteId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Polling error for resumed quote $quoteId: ${e.message}", e)
                    }
                }

                // Wait for both to complete (one will finish first and mint, the other will detect mintCalled)
                wsJob.join()
                pollJob.join()

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
    }

    /**
     * Build the mint's WebSocket URL as `<scheme>/v1/ws` based on the MintUrl.
     *
     * If the mint URL is `https://mint.com` this returns `wss://mint.com/v1/ws`.
     * If it includes a path (e.g. `https://mint.com/Bitcoin`) we append `/v1/ws`
     * after that path: `wss://mint.com/Bitcoin/v1/ws`.
     */
    private fun buildMintWsUrl(mintUrl: MintUrl): String {
        val base = mintUrl.url.removeSuffix("/")
        val wsBase = when {
            base.startsWith("https://", ignoreCase = true) ->
                "wss://" + base.removePrefix("https://")
            base.startsWith("http://", ignoreCase = true) ->
                "ws://" + base.removePrefix("http://")
            base.startsWith("wss://", ignoreCase = true) ||
                base.startsWith("ws://", ignoreCase = true) -> base
            else -> "wss://$base"
        }
        return "$wsBase/v1/ws"
    }

    /**
     * Suspend until the given Lightning mint quote is reported as paid via the
     * mint's WebSocket (NUT-17) subscription.
     *
     * We subscribe with kind = "bolt11_mint_quote" and a single filter: the quote id.
     * On `state == "PAID"` (or `"ISSUED"` if we attached late), we resume.
     *
     * Cancellation of the coroutine will unsubscribe and close the WebSocket,
     * which is how we shut this down when another payment path wins or the user
     * cancels the checkout.
     */
    private suspend fun awaitMintQuotePaid(
        mintUrl: MintUrl,
        quoteId: String
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val wsUrl = buildMintWsUrl(mintUrl)
        Log.d(TAG, "Connecting to mint WebSocket at $wsUrl for quoteId=$quoteId")

        val request = Request.Builder().url(wsUrl).build()
        val gson = Gson()
        val subId = UUID.randomUUID().toString()
        var nextRequestId = 0
        var webSocket: WebSocket? = null

        fun sendUnsubscribe(ws: WebSocket) {
            val params = mapOf("subId" to subId)
            val msg = mapOf(
                "jsonrpc" to "2.0",
                "id" to ++nextRequestId,
                "method" to "unsubscribe",
                "params" to params
            )
            val json = gson.toJson(msg)
            Log.d(TAG, "Sending unsubscribe for subId=$subId: $json")
            ws.send(json)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Mint WebSocket open: $wsUrl")
                webSocket = ws
                val params = mapOf(
                    "kind" to "bolt11_mint_quote",
                    "subId" to subId,
                    "filters" to listOf(quoteId),
                )
                val msg = mapOf(
                    "jsonrpc" to "2.0",
                    "id" to nextRequestId,
                    "method" to "subscribe",
                    "params" to params,
                )
                val json = gson.toJson(msg)
                Log.d(TAG, "Sending subscribe for subId=$subId, quoteId=$quoteId: $json")
                ws.send(json)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.v(TAG, "Mint WS message: $text")
                try {
                    val root = gson.fromJson(text, JsonObject::class.java) ?: return

                    if (root.has("error")) {
                        val errorObj = root.getAsJsonObject("error")
                        val code = errorObj["code"]?.asInt
                        val message = errorObj["message"]?.asString
                        val ex = Exception("Mint WS error code=$code message=$message")
                        if (!cont.isCompleted) {
                            cont.resumeWithException(ex)
                        }
                        ws.close(1000, "error")
                        return
                    }

                    if (root.has("result")) {
                        // subscribe/unsubscribe ACK; nothing to do here for now
                        return
                    }

                    val method = root.get("method")?.asString ?: return
                    if (method != "subscribe") return

                    val params = root.getAsJsonObject("params") ?: return
                    val payload = params.getAsJsonObject("payload") ?: return
                    val state = payload.get("state")?.asString ?: return

                    Log.d(TAG, "Mint quote update for quoteId=$quoteId state=$state")

                    if (state.equals("PAID", ignoreCase = true) ||
                        state.equals("ISSUED", ignoreCase = true)
                    ) {
                        if (!cont.isCompleted) {
                            cont.resume(Unit)
                        }
                        try {
                            sendUnsubscribe(ws)
                        } catch (_: Throwable) {
                        }
                        ws.close(1000, "quote paid")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error parsing mint WS message: ${t.message}", t)
                    if (!cont.isCompleted) {
                        cont.resumeWithException(t)
                    }
                    ws.close(1000, "parse error")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Mint WS failure: ${t.message}", t)
                if (!cont.isCompleted) {
                    cont.resumeWithException(t)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Mint WS closed: code=$code reason=$reason")
                if (!cont.isCompleted && !cont.isCancelled) {
                    cont.resumeWithException(
                        CancellationException("Mint WS closed before quote paid: $code $reason")
                    )
                }
            }
        }

        val ws = wsClient.newWebSocket(request, listener)
        webSocket = ws

        cont.invokeOnCancellation {
            Log.d(TAG, "Coroutine cancelled while waiting for mint quote; closing WS")
            try {
                webSocket?.let { socket ->
                    try {
                        sendUnsubscribe(socket)
                    } catch (_: Throwable) {
                    }
                    socket.close(1000, "cancelled")
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Attempt to mint proofs for the given quote. Uses atomic flag to ensure
     * this is only called once even if both WebSocket and polling detect payment.
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

        val wallet = CashuWalletManager.getWallet()
        if (wallet == null) {
            Log.e(TAG, "Wallet not available for minting")
            uiScope.launch(Dispatchers.Main) {
                callback.onError("Wallet not ready")
            }
            return false
        }

        Log.d(TAG, "Mint quote $quoteId is paid (detected by $source), calling wallet.mint")
        val proofs = wallet.mint(mintUrl, quoteId, null)
        Log.d(TAG, "Lightning mint completed with ${proofs.size} proofs ($source)")

        uiScope.launch(Dispatchers.Main) {
            callback.onPaymentSuccess()
        }
        return true
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
        
        while (!mintCalled.get()) {
            try {
                delay(POLL_INTERVAL_MS)
                
                // Check if mint was already called by WebSocket while we were waiting
                if (mintCalled.get()) {
                    Log.d(TAG, "Mint already called during poll delay, stopping poller")
                    break
                }
                
                Log.v(TAG, "Polling mint quote state for $quoteId")
                val quote = wallet.checkMintQuote(mintUrl, quoteId)
                val stateStr = quote.state.toString()
                
                Log.d(TAG, "Poll result for $quoteId: state=$stateStr")
                
                // Compare state as string (consistent with WebSocket handling)
                if (stateStr.equals("PAID", ignoreCase = true) || 
                    stateStr.equals("ISSUED", ignoreCase = true)) {
                    tryMintOnce(mintUrl, quoteId, callback, "polling")
                    break
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
