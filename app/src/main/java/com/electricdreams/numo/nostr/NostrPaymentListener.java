package com.electricdreams.numo.nostr;

import android.util.Log;

import com.electricdreams.numo.AppGlobals;
import com.electricdreams.numo.ndef.CashuPaymentHelper;
import com.electricdreams.numo.payment.SwapToLightningMintManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level Nostr listener for a single payment.
 *
 * Responsibilities:
 *  - Use an ephemeral nostr keypair (secret key) to listen for NIP-17 DMs
 *    delivered as NIP-59 giftwraps (kind 1059) on configured relays.
 *  - For each relevant event, unwrap (NIP-59) and decrypt (NIP-44) to a
 *    kind 14 rumor and treat its content as a PaymentRequestPayload JSON.
 *  - Attempt redemption via CashuPaymentHelper.redeemFromPRPayload.
 *  - On first successful redemption, stop listening and invoke success callback.
 */
public final class NostrPaymentListener {

    private static final String TAG = "NostrPaymentListener";

    private final byte[] secretKey32;
    private final String pubkeyHex;
    private final long expectedAmount;
    private final List<String> allowedMints;
    private final List<String> relays;
    private final SuccessHandler successHandler;
    private final ErrorHandler errorHandler;

    private NostrWebSocketClient client;
    private volatile boolean stopped = false;

    // Track processed giftwrap event IDs so we don't handle the same payment
    // multiple times when it arrives from different relays.
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

    public interface SuccessHandler {
        void onSuccess(String encodedToken);
    }

    public interface ErrorHandler {
        void onError(String message, Throwable t);
    }

    public NostrPaymentListener(byte[] secretKey32,
                                String pubkeyHex,
                                long expectedAmount,
                                List<String> allowedMints,
                                List<String> relays,
                                SuccessHandler successHandler,
                                ErrorHandler errorHandler) {
        if (secretKey32 == null || secretKey32.length != 32) {
            throw new IllegalArgumentException("secretKey32 must be 32 bytes");
        }
        this.secretKey32 = secretKey32;
        this.pubkeyHex = pubkeyHex;
        this.expectedAmount = expectedAmount;
        this.allowedMints = allowedMints;
        this.relays = relays;
        this.successHandler = successHandler;
        this.errorHandler = errorHandler;
    }

    public synchronized void start() {
        if (client != null || stopped) return;
        Log.d(TAG, "Starting NostrPaymentListener for pubkey=" + pubkeyHex
                + " amount=" + expectedAmount + " relays=" + relays);

        client = new NostrWebSocketClient(relays, pubkeyHex, new NostrWebSocketClient.EventHandler() {
            @Override
            public void onEvent(String relayUrl, NostrEvent event) {
                handleEvent(relayUrl, event);
            }

            @Override
            public void onError(String relayUrl, String message, Throwable t) {
                if (errorHandler != null) {
                    errorHandler.onError(message, t);
                }
            }
        });
        client.start();
    }

    public synchronized void stop() {
        stopped = true;
        if (client != null) {
            Log.d(TAG, "Stopping NostrPaymentListener");
            client.stop();
            client = null;
        }
    }

    private void handleEvent(String relayUrl, NostrEvent event) {
        if (stopped) return;
        if (event == null) return;
        if (event.kind != 1059) {
            // Should already be filtered by subscription, but double-check.
            return;
        }
        if (event.id == null || event.id.isEmpty()) {
            Log.w(TAG, "Received kind 1059 event without id from " + relayUrl + "; skipping");
            return;
        }
        // Deduplicate by event ID across all relays. Synchronize so only one
        // thread at a time can perform the check-and-add.
        synchronized (seenEventIds) {
            if (!seenEventIds.add(event.id)) {
                Log.d(TAG, "Ignoring duplicate event id=" + event.id + " from " + relayUrl);
                return;
            }
        }
        try {
            Log.d(TAG, "Received kind 1059 event from " + relayUrl + " id=" + event.id);
            Nip59.UnwrappedDm dm = Nip59.unwrapGiftWrappedDm(event, secretKey32);

            String payloadJson = dm.rumor.content;
            if (payloadJson == null || payloadJson.isEmpty()) {
                Log.w(TAG, "Rumor content is empty; skipping");
                return;
            }

            Log.d(TAG, "Attempting PaymentRequestPayload redemption (with swap) from relay=" + relayUrl);

            // For Nostr, we create a minimal PaymentContext that ties this
            // redemption to the expected amount. There is no explicit
            // paymentId here; higher-level callers can correlate via Nostr
            // metadata if needed.
            SwapToLightningMintManager.PaymentContext paymentContext =
                    new SwapToLightningMintManager.PaymentContext(null, expectedAmount);

            // Call the high-level, swap-aware redemption helper so that
            // incoming ecash from unknown mints can be swapped to the
            // merchant's configured Lightning mint.
            String token = (String) kotlinx.coroutines.BuildersKt.runBlocking(
                    kotlin.coroutines.EmptyCoroutineContext.INSTANCE,
                    (kotlinx.coroutines.CoroutineScope scope, kotlin.coroutines.Continuation<? super String> continuation) -> {
                        try {
                            return CashuPaymentHelper.INSTANCE.redeemFromPRPayloadWithSwap(
                                    AppGlobals.INSTANCE.getAppContext(),
                                    payloadJson,
                                    expectedAmount,
                                    allowedMints,
                                    paymentContext,
                                    continuation
                            );
                        } catch (CashuPaymentHelper.RedemptionException e) {
                            // Surface as a failure inside the coroutine; it will be
                            // rethrown by runBlocking and handled by the outer catch.
                            throw new RuntimeException(e);
                        }
                    }
            );

            // For swap-to-Lightning-mint flows, a successful redemption may
            // legitimately return an empty token string (Lightning-style
            // payment, no Cashu token imported). Treat both non-empty and
            // empty strings as success and let higher layers decide how to
            // handle the result.
            if (token != null) {
                Log.i(TAG, "Redemption (with possible swap) successful via nostr DM; stopping listener. tokenLength=" + token.length());
                stop();
                if (successHandler != null) {
                    successHandler.onSuccess(token);
                }
            } else {
                Log.w(TAG, "Redemption returned null token; ignoring");
            }
        } catch (RuntimeException re) {
            Throwable cause = re.getCause();
            if (cause instanceof CashuPaymentHelper.RedemptionException) {
                CashuPaymentHelper.RedemptionException e = (CashuPaymentHelper.RedemptionException) cause;
                Log.e(TAG, "Redemption error for event from " + relayUrl + ": " + e.getMessage(), e);
                if (errorHandler != null) {
                    errorHandler.onError("PaymentRequestPayload redemption failed", e);
                }
            } else {
                Log.e(TAG, "Unexpected runtime error during Nostr redemption from " + relayUrl + ": " + re.getMessage(), re);
                if (errorHandler != null) {
                    errorHandler.onError("Unexpected error during Nostr redemption", re);
                }
            }
        } catch (CashuPaymentHelper.RedemptionException e) {
            Log.e(TAG, "Redemption error for event from " + relayUrl + ": " + e.getMessage(), e);
            if (errorHandler != null) {
                errorHandler.onError("PaymentRequestPayload redemption failed", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling nostr event from " + relayUrl + ": " + e.getMessage(), e);
            if (errorHandler != null) {
                errorHandler.onError("nostr event handling failed", e);
            }
        }
    }
}
