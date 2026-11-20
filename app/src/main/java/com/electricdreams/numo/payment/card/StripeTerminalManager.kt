package com.electricdreams.numo.payment.card

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.TerminalApplicationDelegate
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.CaptureMethod
import com.stripe.stripeterminal.external.models.ConnectionConfiguration.TapToPayConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration.TapToPayDiscoveryConfiguration
import com.stripe.stripeterminal.external.models.LocaleConfig
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.PaymentIntentParameters
import com.stripe.stripeterminal.external.models.PaymentIntentStatus
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TapUseCase
import com.stripe.stripeterminal.external.models.TerminalErrorCode
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel
import com.stripe.stripeterminal.taptopay.TapToPay
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin wrapper around the Stripe Terminal SDK for Tap to Pay on Android.
 *
 * Numo talks to Stripe directly with the merchant's restricted key (see
 * [StripeConnectionTokenClient]); there is no Numo backend. PaymentIntents are created
 * by the SDK on the merchant's account and captured automatically.
 *
 * All SDK calls must happen on the main thread. Stripe requires Android 13+ for
 * Tap to Pay, so every entry point is guarded by [isDeviceSupported].
 */
object StripeTerminalManager {

    /**
     * Outcome of a card collection attempt that did not succeed.
     *
     * @property paymentIntent the PaymentIntent of this attempt, if one was created. Pass it
     *   back to [collectPayment] when retrying so the same sale is never charged twice.
     * @property isUnresolved true when Stripe may have charged the card but Numo could not
     *   confirm the outcome, e.g. after a network failure. A retry checks the status first.
     */
    class CardPaymentException(
        message: String,
        val isCancellation: Boolean,
        cause: Throwable? = null,
        val paymentIntent: PaymentIntent? = null,
        val isUnresolved: Boolean = false,
        val reason: FailureReason = FailureReason.OTHER,
    ) : Exception(message, cause)

    /** Failures the merchant can fix in their setup, so a retry would fail the same way. */
    enum class FailureReason {
        /** The amount is below Stripe's minimum charge for the currency. */
        AMOUNT_TOO_SMALL,

        /** The Stripe account cannot take card payments in Numo's currency. */
        CURRENCY_NOT_SUPPORTED,

        OTHER,
    }

    /** Classifies a Stripe API error by its `code` and `param` fields. */
    fun failureReasonFor(code: String?, param: String?): FailureReason = when {
        code == "amount_too_small" -> FailureReason.AMOUNT_TOO_SMALL
        param == "currency" || code == "payment_method_currency_mismatch" ->
            FailureReason.CURRENCY_NOT_SUPPORTED
        else -> FailureReason.OTHER
    }

    /** What a retry must do with an earlier PaymentIntent, based on its status at Stripe. */
    enum class RetryStep {
        /** The card was already charged; record the payment and charge nothing. */
        ALREADY_PAID,

        /** The earlier intent was cancelled, so it can never be charged; start a new one. */
        NEW_INTENT,

        /** Collect a card again on the same intent, e.g. after a decline. */
        COLLECT,

        /** A card was collected but not charged yet; only confirm it. */
        CONFIRM,

        /** The charge may still be in flight; do not touch the card. */
        UNRESOLVED,
    }

    /** Maps a PaymentIntent status to the safe next step. Unknown states never charge. */
    fun retryStepFor(status: PaymentIntentStatus?): RetryStep = when (status) {
        PaymentIntentStatus.SUCCEEDED -> RetryStep.ALREADY_PAID
        PaymentIntentStatus.CANCELED -> RetryStep.NEW_INTENT
        PaymentIntentStatus.REQUIRES_PAYMENT_METHOD -> RetryStep.COLLECT
        PaymentIntentStatus.REQUIRES_CONFIRMATION -> RetryStep.CONFIRM
        else -> RetryStep.UNRESOLVED
    }

    private const val TAG = "StripeTerminalManager"

    private val tokenClient = StripeConnectionTokenClient()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    /**
     * Scope for card collection. It outlives the checkout screen so that a charge Stripe is
     * already confirming still reports back and gets recorded.
     */
    val paymentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * True while Stripe is charging a collected card. Cancelling then would not stop the charge,
     * only Numo's view of it, so callers must let the collection finish.
     */
    @Volatile
    var isCharging = false
        private set

    /** Location the connected reader was registered to; a settings change forces a reconnect. */
    private var connectedLocationId: String? = null

    private val tokenProvider = object : ConnectionTokenProvider {
        override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
            val context = appContext
            if (context == null) {
                callback.onFailure(ConnectionTokenException("Card payments are not initialised"))
                return
            }
            ioScope.launch {
                val config = StripeCardSettings.getInstance(context).loadConfig()
                if (config == null) {
                    callback.onFailure(ConnectionTokenException("Card payments are not configured"))
                    return@launch
                }
                try {
                    callback.onSuccess(tokenClient.createConnectionToken(config))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch Stripe connection token", e)
                    callback.onFailure(
                        ConnectionTokenException(e.message ?: "Failed to fetch connection token", e)
                    )
                }
            }
        }
    }

    /** Stripe Tap to Pay requires Android 13 and an NFC reader. */
    fun isDeviceSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            NfcAdapter.getDefaultAdapter(context) != null

    /** True inside Stripe's dedicated Tap to Pay process, where app initialisation must be skipped. */
    fun isInTapToPayProcess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && TapToPay.isInTapToPayProcess()

    /** Forwards Application.onCreate to the SDK, as Stripe requires. */
    fun onApplicationCreate(application: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            TerminalApplicationDelegate.onCreate(application)
        } catch (e: Exception) {
            Log.e(TAG, "Stripe Terminal application delegate failed", e)
        }
    }

    /**
     * Stripe's production Tap to Pay reader refuses to run in debuggable apps, so debug
     * builds use the simulated reader.
     */
    fun isSimulated(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @MainThread
    private fun ensureInitialized(context: Context): Terminal {
        appContext = context.applicationContext
        if (!Terminal.isInitialized()) {
            Terminal.init(
                context.applicationContext,
                LogLevel.WARNING,
                tokenProvider,
                object : TerminalListener {},
                null,
                LocaleConfig.CardLanguagePreferenceIfAvailable,
            )
        }
        return Terminal.getInstance()
    }

    /** Connects the phone's Tap to Pay reader, reusing an existing connection when possible. */
    @MainThread
    suspend fun ensureReaderConnected(context: Context, config: StripeCardConfig): Reader {
        val terminal = ensureInitialized(context)
        terminal.connectedReader?.let { reader ->
            if (connectedLocationId == config.locationId) return reader
            disconnect(terminal)
        }
        val reader = discoverTapToPayReader(context, terminal)
        val connected = connect(terminal, reader, config.locationId)
        connectedLocationId = config.locationId
        return connected
    }

    private suspend fun discoverTapToPayReader(context: Context, terminal: Terminal): Reader {
        // Stripe disables Tap to Pay without location access; callers request it beforehand.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw CardPaymentException("Location permission is required", isCancellation = false)
        }
        val simulated = isSimulated(context)
        return suspendCancellableCoroutine { continuation ->
            var discovery: Cancelable? = null
            discovery = terminal.discoverReaders(
                TapToPayDiscoveryConfiguration(isSimulated = simulated),
                object : DiscoveryListener {
                    override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                        val reader = readers.firstOrNull() ?: return
                        if (continuation.isActive) continuation.resume(reader)
                    }
                },
                object : Callback {
                    override fun onSuccess() = Unit

                    override fun onFailure(e: TerminalException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                },
            )
            continuation.invokeOnCancellation { discovery?.cancel(NoOpCallback) }
        }
    }

    private suspend fun connect(terminal: Terminal, reader: Reader, locationId: String): Reader =
        suspendCancellableCoroutine { continuation ->
            terminal.connectReader(
                reader,
                TapToPayConnectionConfiguration(
                    TapUseCase.Pay(locationId),
                    true,
                    object : TapToPayReaderListener {},
                ),
                object : ReaderCallback {
                    override fun onSuccess(reader: Reader) {
                        if (continuation.isActive) continuation.resume(reader)
                    }

                    override fun onFailure(e: TerminalException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                },
            )
        }

    /**
     * Charges [amount] with Stripe's Tap to Pay UI and returns the succeeded PaymentIntent.
     *
     * Pass the PaymentIntent from a failed attempt as [previous]. Its status is fetched from
     * Stripe first, and the same intent is reused, so a retry never charges the card twice.
     * Collection can be cancelled by cancelling the calling coroutine until the card has been
     * read; after that [isCharging] is true and the caller must let the charge finish.
     *
     * @throws CardPaymentException when the payment fails, is cancelled or is unresolved.
     */
    @MainThread
    suspend fun collectPayment(
        context: Context,
        amount: CardAmountResolver.CardAmount,
        description: String?,
        metadata: Map<String, String>,
        previous: PaymentIntent? = null,
    ): PaymentIntent {
        val terminal = ensureInitialized(context)
        var intent: PaymentIntent? = previous
        try {
            var current = if (previous == null) {
                createPaymentIntent(terminal, amount, description, metadata)
            } else {
                retrievePaymentIntent(terminal, previous)
            }
            intent = current
            val step = if (previous == null) RetryStep.COLLECT else retryStepFor(current.status)
            Log.d(TAG, "Card payment ${current.id}: status=${current.status}, step=$step")
            when (step) {
                RetryStep.ALREADY_PAID -> return current
                RetryStep.UNRESOLVED -> throw unresolved(current)
                RetryStep.NEW_INTENT -> {
                    current = createPaymentIntent(terminal, amount, description, metadata)
                    intent = current
                    current = collectPaymentMethod(terminal, current)
                }
                RetryStep.COLLECT -> current = collectPaymentMethod(terminal, current)
                RetryStep.CONFIRM -> Unit
            }
            intent = current

            isCharging = true
            val confirmed = try {
                confirmPaymentIntent(terminal, current)
            } finally {
                isCharging = false
            }
            intent = confirmed
            if (confirmed.status != PaymentIntentStatus.SUCCEEDED) throw unresolved(confirmed)
            return confirmed
        } catch (e: TerminalException) {
            val cancelled = e.errorCode == TerminalErrorCode.CANCELED ||
                e.errorCode == TerminalErrorCode.CANCELED_BY_READER
            throw CardPaymentException(
                message = e.errorMessage,
                isCancellation = cancelled,
                cause = e,
                paymentIntent = e.paymentIntent ?: intent,
                reason = failureReasonFor(e.apiError?.code, e.apiError?.param),
            )
        }
    }

    private fun unresolved(intent: PaymentIntent) = CardPaymentException(
        "Card payment did not complete (status: ${intent.status})",
        isCancellation = false,
        paymentIntent = intent,
        isUnresolved = true,
    )

    private suspend fun createPaymentIntent(
        terminal: Terminal,
        amount: CardAmountResolver.CardAmount,
        description: String?,
        metadata: Map<String, String>,
    ): PaymentIntent = suspendCancellableCoroutine { continuation ->
        val params = PaymentIntentParameters.Builder()
            .setAmount(amount.minorUnits)
            .setCurrency(amount.currency)
            .setCaptureMethod(CaptureMethod.Automatic)
            .setMetadata(metadata)
            .apply { description?.let { setDescription(it) } }
            .build()
        terminal.createPaymentIntent(params, continuation.asPaymentIntentCallback())
    }

    /** Fetches the current state of [intent] from Stripe. */
    private suspend fun retrievePaymentIntent(
        terminal: Terminal,
        intent: PaymentIntent,
    ): PaymentIntent {
        val clientSecret = intent.clientSecret ?: throw unresolved(intent)
        return suspendCancellableCoroutine { continuation ->
            terminal.retrievePaymentIntent(clientSecret, continuation.asPaymentIntentCallback())
        }
    }

    /** Shows Stripe's tap screen and reads a card. Cancelling the coroutine cancels it. */
    private suspend fun collectPaymentMethod(
        terminal: Terminal,
        intent: PaymentIntent,
    ): PaymentIntent = suspendCancellableCoroutine { continuation ->
        val cancelable = terminal.collectPaymentMethod(intent, continuation.asPaymentIntentCallback())
        continuation.invokeOnCancellation {
            if (!cancelable.isCompleted) cancelable.cancel(NoOpCallback)
        }
    }

    /** Charges the collected card. This is never cancelled: the charge may already be under way. */
    private suspend fun confirmPaymentIntent(
        terminal: Terminal,
        intent: PaymentIntent,
    ): PaymentIntent = suspendCancellableCoroutine { continuation ->
        terminal.confirmPaymentIntent(intent, continuation.asPaymentIntentCallback())
    }

    private fun kotlinx.coroutines.CancellableContinuation<PaymentIntent>.asPaymentIntentCallback() =
        object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                if (isActive) resume(paymentIntent)
            }

            override fun onFailure(e: TerminalException) {
                if (isActive) resumeWithException(e)
            }
        }

    /**
     * Drops the reader connection and cached credentials, e.g. after the merchant changes
     * their key or location. Safe to call when the SDK was never initialised.
     */
    @MainThread
    fun reset() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!Terminal.isInitialized()) return
        val terminal = Terminal.getInstance()
        disconnect(terminal)
        try {
            terminal.clearCachedCredentials()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Stripe credentials", e)
        }
    }

    private fun disconnect(terminal: Terminal) {
        connectedLocationId = null
        if (terminal.connectedReader == null) return
        terminal.disconnectReader(object : Callback {
            override fun onSuccess() = Unit

            override fun onFailure(e: TerminalException) {
                Log.w(TAG, "Failed to disconnect Tap to Pay reader: ${e.errorMessage}")
            }
        })
    }

    private object NoOpCallback : Callback {
        override fun onSuccess() = Unit

        override fun onFailure(e: TerminalException) {
            Log.w(TAG, "Stripe cancellation failed: ${e.errorMessage}")
        }
    }
}
