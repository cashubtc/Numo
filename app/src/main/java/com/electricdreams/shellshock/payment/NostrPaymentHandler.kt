package com.electricdreams.shellshock.payment

import android.content.Context
import android.util.Log
import com.electricdreams.shellshock.feature.history.PaymentsHistoryActivity
import com.electricdreams.shellshock.ndef.CashuPaymentHelper
import com.electricdreams.shellshock.nostr.Nip19
import com.electricdreams.shellshock.nostr.NostrKeyPair
import com.electricdreams.shellshock.nostr.NostrPaymentListener

/**
 * Handles Nostr-based payment listening for Cashu over Nostr (NIP-17).
 *
 * This class encapsulates:
 * - Generating or restoring ephemeral Nostr keypairs
 * - Creating payment requests with Nostr transport
 * - Starting/stopping the NIP-17 payment listener
 * - Persisting nostr keys for resume capability
 */
class NostrPaymentHandler(
    private val context: Context,
    private val allowedMints: List<String>
) {
    /**
     * Callback interface for Nostr payment events.
     */
    interface Callback {
        /** Called when a payment request is ready for display */
        fun onPaymentRequestReady(paymentRequest: String)
        
        /** Called when a Cashu token is received via Nostr */
        fun onTokenReceived(token: String)
        
        /** Called when an error occurs */
        fun onError(message: String)
    }

    private var listener: NostrPaymentListener? = null
    private var keyPair: NostrKeyPair? = null
    private var nprofile: String? = null
    private var secretHex: String? = null

    /** The generated/restored nprofile */
    val currentNprofile: String? get() = nprofile

    /** The secret key hex for persistence */
    val currentSecretHex: String? get() = secretHex

    /** The generated payment request string */
    var paymentRequest: String? = null
        private set

    /**
     * Start a new Nostr payment flow with fresh keys.
     *
     * @param paymentAmount Amount in satoshis
     * @param pendingPaymentId Optional ID for updating pending payment record
     * @param callback Callback for payment events
     */
    fun start(
        paymentAmount: Long,
        pendingPaymentId: String?,
        callback: Callback
    ) {
        // Generate new ephemeral keys
        val eph = NostrKeyPair.generate()
        keyPair = eph
        
        val profile = Nip19.encodeNprofile(eph.publicKeyBytes, NOSTR_RELAYS.toList())
        nprofile = profile
        secretHex = eph.hexSec

        Log.d(TAG, "Generated ephemeral nostr pubkey=${eph.hexPub} nprofile=$profile")

        // Store nostr info for future resume
        pendingPaymentId?.let { paymentId ->
            PaymentsHistoryActivity.updatePendingWithNostrInfo(
                context = context,
                paymentId = paymentId,
                nostrSecretHex = eph.hexSec,
                nostrNprofile = profile,
            )
        }

        // Create and start listener
        startListener(paymentAmount, eph, profile, callback)
    }

    /**
     * Resume a Nostr payment flow with stored keys.
     *
     * @param paymentAmount Amount in satoshis
     * @param storedSecretHex Previously stored secret key hex
     * @param storedNprofile Previously stored nprofile
     * @param callback Callback for payment events
     */
    fun resume(
        paymentAmount: Long,
        storedSecretHex: String,
        storedNprofile: String,
        callback: Callback
    ) {
        Log.d(TAG, "Resuming with stored nostr keys")
        
        val eph = NostrKeyPair.fromSecretHex(storedSecretHex)
        keyPair = eph
        nprofile = storedNprofile
        secretHex = storedSecretHex

        // Create and start listener
        startListener(paymentAmount, eph, storedNprofile, callback)
    }

    private fun startListener(
        paymentAmount: Long,
        eph: NostrKeyPair,
        profile: String,
        callback: Callback
    ) {
        val nostrPubHex = eph.hexPub
        val nostrSecret = eph.secretKeyBytes
        val relayList = NOSTR_RELAYS.toList()

        // Create payment request with Nostr transport
        val request = CashuPaymentHelper.createPaymentRequestWithNostr(
            paymentAmount,
            "Payment of $paymentAmount sats",
            allowedMints,
            profile
        )

        if (request == null) {
            Log.e(TAG, "Failed to create payment request with Nostr transport")
            callback.onError("Failed to create payment request")
            return
        }

        paymentRequest = request
        Log.d(TAG, "Created payment request with Nostr: $request")
        callback.onPaymentRequestReady(request)

        // Stop any existing listener
        listener?.stop()

        // Start new listener
        listener = NostrPaymentListener(
            nostrSecret,
            nostrPubHex,
            paymentAmount,
            allowedMints,
            relayList,
            { token -> callback.onTokenReceived(token) },
            { msg, t -> Log.e(TAG, "NostrPaymentListener error: $msg", t) }
        ).also { it.start() }

        Log.d(TAG, "Nostr payment listener started")
    }

    /**
     * Stop the Nostr payment listener.
     */
    fun stop() {
        listener?.let {
            Log.d(TAG, "Stopping NostrPaymentListener")
            it.stop()
        }
        listener = null
    }

    companion object {
        private const val TAG = "NostrPaymentHandler"

        // Nostr relays to use for NIP-17 gift-wrapped DMs
        val NOSTR_RELAYS = arrayOf(
            "wss://relay.primal.net",
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://nostr.mom"
        )
    }
}

