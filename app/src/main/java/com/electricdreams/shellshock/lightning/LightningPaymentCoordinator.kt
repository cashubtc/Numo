package com.electricdreams.shellshock.lightning

import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.ActiveSubscription
import org.cashudevkit.Amount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintQuote
import org.cashudevkit.MintQuoteBolt11Response
import org.cashudevkit.NotificationPayload
import org.cashudevkit.QuoteState
import org.cashudevkit.SendKind
import org.cashudevkit.SendOptions
import org.cashudevkit.SplitTarget
import org.cashudevkit.SubscriptionKind
import org.cashudevkit.SubscribeParams
import org.cashudevkit.Token
import org.cashudevkit.Wallet
import org.cashudevkit.WalletConfig
import org.cashudevkit.WalletDatabase
import org.cashudevkit.WalletSqliteDatabase
import org.cashudevkit.generateMnemonic
import org.json.JSONObject

/**
 * Coordinates Lightning mint quotes created through the CDK Kotlin bindings.
 * Handles invoice creation, subscription updates, and token minting when paid.
 */
class LightningPaymentCoordinator @JvmOverloads constructor(
    private val listener: Listener,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Closeable {

    interface Listener {
        fun onLightningInvoiceLoading()
        fun onLightningInvoiceReady(quote: MintQuote)
        fun onLightningInvoiceStateUpdated(state: QuoteState)
        fun onLightningInvoiceFailed(error: Throwable)
        fun onLightningPaymentSuccess(result: LightningPaymentResult)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var currentJob: Job? = null
    private var activeSubscription: ActiveSubscription? = null
    private var activeWallet: Wallet? = null
    private var activeDatabase: WalletDatabase? = null
    private val isRunning = AtomicBoolean(false)

    fun startNewInvoice(amountSats: Long, description: String, mintUrl: String) {
        cancelCurrentInvoice()
        currentJob = scope.launch {
            isRunning.set(true)
            emitOnMain { listener.onLightningInvoiceLoading() }
            try {
                val db = WalletSqliteDatabase.newInMemory()
                val config = WalletConfig(targetProofCount = 32u)
                val mnemonic = generateMnemonic()
                val wallet = Wallet(
                    mintUrl = mintUrl,
                    unit = CurrencyUnit.Sat,
                    mnemonic = mnemonic,
                    db = db,
                    config = config
                )
                activeDatabase = db
                activeWallet = wallet

                val mintQuote = wallet.mintQuote(
                    amount = Amount(amountSats.toULong()),
                    description = description
                )

                val subscription = wallet.subscribe(
                    params = SubscribeParams(
                        kind = SubscriptionKind.BOLT11_MINT_QUOTE,
                        filters = listOf(mintQuote.id),
                        id = null
                    )
                )
                activeSubscription = subscription

                emitOnMain { listener.onLightningInvoiceReady(mintQuote) }
                emitOnMain { listener.onLightningInvoiceStateUpdated(mintQuote.state) }

                listenForPayments(
                    wallet = wallet,
                    subscription = subscription,
                    initialQuote = mintQuote,
                    amountSats = amountSats
                )
            } catch (ce: CancellationException) {
                Log.d(TAG, "Lightning invoice cancelled")
            } catch (t: Throwable) {
                if (isRunning.get()) {
                    emitOnMain { listener.onLightningInvoiceFailed(t) }
                }
            } finally {
                isRunning.set(false)
                clearResources()
            }
        }
    }

    fun cancelCurrentInvoice() {
        isRunning.set(false)
        currentJob?.cancel()
        currentJob = null
        clearResources()
    }

    override fun close() {
        cancelCurrentInvoice()
        scope.cancel()
    }

    private suspend fun listenForPayments(
        wallet: Wallet,
        subscription: ActiveSubscription,
        initialQuote: MintQuote,
        amountSats: Long
    ) {
        var latestQuote = initialQuote
        try {
            while (currentCoroutineContext().isActive) {
                val payload = subscription.recv()
                val quoteUpdate = (payload as? NotificationPayload.MintQuoteUpdate) ?: continue
                // quoteUpdate.quote is MintQuoteBolt11Response, which has a quote property (MintQuote) and state property
                val bolt11Response = quoteUpdate.quote
                val quote = bolt11Response.quote
                val quoteState = bolt11Response.state
                emitOnMain { listener.onLightningInvoiceStateUpdated(quoteState) }

                if (quoteState == QuoteState.PAID) {
                    // Use initialQuote since we know it's the correct one (we subscribed by ID)
                    val result = finalizePayment(wallet, initialQuote, amountSats)
                    emitOnMain { listener.onLightningPaymentSuccess(result) }
                    return
                }
            }
        } finally {
            try {
                subscription.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun finalizePayment(
        wallet: Wallet,
        quote: MintQuote,
        amountSats: Long
    ): LightningPaymentResult {
        wallet.mint(
            quoteId = quote.id,
            amountSplitTarget = SplitTarget.None,
            spendingConditions = null
        )

        val sendOptions = SendOptions(
            memo = null,
            conditions = null,
            amountSplitTarget = SplitTarget.None,
            sendKind = SendKind.OfflineExact,
            includeFee = true,
            maxProofs = null,
            metadata = emptyMap()
        )

        val preparedSend = wallet.prepareSend(
            amount = Amount(amountSats.toULong()),
            options = sendOptions
        )

        val tokenString = preparedSend.use { prepared ->
            prepared.confirm(null).use(Token::encode)
        }

        val snapshot = quote.toSnapshot()
        return LightningPaymentResult(
            token = tokenString,
            quoteSnapshot = snapshot
        )
    }

    private fun clearResources() {
        try {
            activeSubscription?.close()
        } catch (_: Exception) {
        } finally {
            activeSubscription = null
        }

        try {
            activeWallet?.close()
        } catch (_: Exception) {
        } finally {
            activeWallet = null
        }

        try {
            activeDatabase?.let { db ->
                if (db is Closeable) {
                    db.close()
                }
            }
        } catch (_: Exception) {
        } finally {
            activeDatabase = null
        }
    }

    private suspend fun emitOnMain(block: () -> Unit) {
        withContext(mainDispatcher) { block() }
    }

    companion object {
        private const val TAG = "LightningCoordinator"
    }
}

data class LightningPaymentResult(
    val token: String,
    val quoteSnapshot: LightningQuoteSnapshot
) {
    val bolt11: String? get() = quoteSnapshot.bolt11
    val snapshotJson: String get() = quoteSnapshot.toJson()
    val mintUrl: String? get() = quoteSnapshot.mintUrl
}

data class LightningQuoteSnapshot(
    val id: String,
    val bolt11: String?,
    val mintUrl: String?,
    val amount: Long?,
    val amountIssued: Long?,
    val amountPaid: Long?,
    val state: String,
    val expiry: Long?,
    val paymentMethod: String?
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("id", id)
        bolt11?.let { json.put("bolt11", it) }
        mintUrl?.let { json.put("mintUrl", it) }
        amount?.let { json.put("amount", it) }
        amountIssued?.let { json.put("amountIssued", it) }
        amountPaid?.let { json.put("amountPaid", it) }
        json.put("state", state)
        expiry?.let { json.put("expiry", it) }
        paymentMethod?.let { json.put("paymentMethod", it) }
        return json.toString()
    }
}

private fun MintQuote.toSnapshot(): LightningQuoteSnapshot {
    return LightningQuoteSnapshot(
        id = id,
        bolt11 = request,
        mintUrl = mintUrl?.url,
        amount = amount.toLongValue(),
        amountIssued = amountIssued.toLongValue(),
        amountPaid = amountPaid.toLongValue(),
        state = state.name,
        expiry = expiry?.toLong(),
        paymentMethod = paymentMethod?.javaClass?.simpleName
    )
}

private fun Amount?.toLongValue(): Long? = this?.value?.toLong()

