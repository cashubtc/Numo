# Nostr-Based Payment Flow Implementation Plan

This document describes how to extend the POS checkout flow to support NUT-18 payment requests over Nostr (NIP-17 gift-wrapped DMs, using NIP-44 encryption).

It assumes:
- `nostr-sdk-jvm` is available as a dependency: `implementation("org.rust-nostr:nostr-sdk-jvm:0.44.0")`
- The existing Cashu-JDK (NUT-18 etc.) is in place.
- The existing POS flow (ModernPOSActivity, HCE/NDEF, QR-based PaymentRequest) is working.

---

## 1. High-Level Flow

For each payment request created in the POS app:

1. **Generate an ephemeral Nostr identity** (one-time keypair) using `nostr-sdk-jvm`.
2. **Derive an `nprofile`** (NIP-19) from that identity, including a fixed list of relays.
3. **Embed the `nprofile` into the NUT-18 `PaymentRequest.transport`** with type `"nostr"` and `[["n","17"]]` tag.
4. Display this NUT-18 `PaymentRequest` in the existing QR card on the checkout dialog.
5. **Start a Nostr listener task** bound to this ephemeral identity:
   - Connects to a set of well-known relays:
     - `wss://relay.primal.net`
     - `wss://relay.damus.io`
     - `wss://nos.lol`
     - `wss://nostr.mom`
   - Subscribes to NIP-17 **gift-wrapped** events (kind 14) destined for our ephemeral pubkey.
   - For each gift-wrapped event:
     - Unwraps it to the inner DM event.
     - Decrypts the DM content using **NIP-44**.
     - Interprets the decrypted content as a NUT-18 `PaymentRequestPayload` JSON.
     - Validates the payload and attempts to redeem the contained proofs.
6. On the **first successful redemption**:
   - Stop the Nostr listener.
   - Call `handlePaymentSuccess(token)` in `ModernPOSActivity` with the newly reissued token.
   - Stop/cleanup any HCE/HCE service associated with this payment.
7. If the user **cancels** the payment (or the activity is destroyed):
   - Stop the Nostr listener.
   - Stop/cleanup HCE service.
   - Ignore any late-arriving DMs.

Note: Nostr and NFC/HCE flows run in **parallel**. Either path can complete the payment; the first to succeed wins.

---

## 2. Cashu Side: `PaymentRequestPayload` + `redeemFromPRPayload`

### 2.1. Add NUT-18 PaymentRequestPayload DTO

**File:** `app/src/main/java/com/electricdreams/shellshock/ndef/CashuPaymentHelper.java`

Add imports:

```java
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
```

Add a static Gson instance:

```java
private static final Gson gson = new Gson();
```

Add the DTO inside `CashuPaymentHelper`:

```java
public static class PaymentRequestPayload {
    public String id;      // optional
    public String memo;    // optional
    public String mint;    // required
    public String unit;    // required, expect "sat"
    public List<Proof> proofs; // required
}
```

### 2.2. Implement `redeemFromPRPayload`

Add the method:

```java
public static String redeemFromPRPayload(
        String payloadJson,
        long expectedAmount,
        List<String> allowedMints
) throws RedemptionException {
    if (payloadJson == null) {
        throw new RedemptionException("PaymentRequestPayload JSON is null");
    }

    try {
        PaymentRequestPayload payload =
                gson.fromJson(payloadJson, PaymentRequestPayload.class);
        if (payload == null) {
            throw new RedemptionException("Failed to parse PaymentRequestPayload");
        }

        if (payload.mint == null || payload.mint.isEmpty()) {
            throw new RedemptionException("PaymentRequestPayload is missing mint");
        }
        if (payload.unit == null || !"sat".equals(payload.unit)) {
            throw new RedemptionException("Unsupported unit in PaymentRequestPayload: " + payload.unit);
        }
        if (payload.proofs == null || payload.proofs.isEmpty()) {
            throw new RedemptionException("PaymentRequestPayload contains no proofs");
        }

        String mintUrl = payload.mint;

        // Validate mint against allowed list
        if (allowedMints != null && !allowedMints.isEmpty() && !allowedMints.contains(mintUrl)) {
            throw new RedemptionException("Mint " + mintUrl + " not in allowed list");
        }

        long totalAmount = payload.proofs.stream()
                .mapToLong(p -> p.amount)
                .sum();

        if (totalAmount < expectedAmount) {
            throw new RedemptionException("Insufficient amount in payload proofs: "
                    + totalAmount + " < expected " + expectedAmount);
        }

        Log.d(TAG, "Redeeming PaymentRequestPayload from mint=" + mintUrl
                + " amount=" + totalAmount + " sats (expected=" + expectedAmount + ")");

        CashuHttpClient cashuHttpClient = new CashuHttpClient(new OkHttpClient(), mintUrl);
        GetKeysetsResponse keysetsResponse = cashuHttpClient.getKeysets().join();
        if (keysetsResponse == null || keysetsResponse.keysets == null || keysetsResponse.keysets.isEmpty()) {
            throw new RedemptionException("Failed to get keysets from mint: " + mintUrl);
        }

        final Map<String, Integer> keysetsFeesMap = keysetsResponse.keysets.stream()
                .collect(Collectors.toMap(k -> k.keysetId, k -> k.inputFee));

        // For now, assume proofs are valid for the mint; production code may mirror
        // redeemToken()'s keyset mapping logic if needed.
        List<Proof> receiveProofs = payload.proofs;

        long fee = FeeHelper.ComputeFee(receiveProofs, keysetsFeesMap);

        // Create swap outputs
        String selectedKeysetId = keysetsResponse.keysets
                .stream()
                .filter(k -> k.active)
                .min(Comparator.comparing(k -> k.inputFee))
                .map(k -> k.keysetId)
                .orElseThrow(() -> new RedemptionException("No active keyset found on mint"));
        Log.d(TAG, "Selected keyset ID for new proofs (PRPayload): " + selectedKeysetId);

        List<Long> outputAmounts = createOutputAmounts(totalAmount - fee);

        // Store blinded messages, secrets, and blinding factors for later use
        List<BlindedMessage> blindedMessages = new ArrayList<>();
        List<StringSecret> secrets = new ArrayList<>();
        List<BigInteger> blindingFactors = new ArrayList<>();

        for (Long output : outputAmounts) {
            StringSecret secret = StringSecret.random();
            secrets.add(secret);

            BigInteger blindingFactor = new BigInteger(256, new SecureRandom());
            blindingFactors.add(blindingFactor);

            BlindedMessage blindedMessage = new BlindedMessage(
                    output,
                    selectedKeysetId,
                    pointToHex(computeB_(messageToCurve(secret.getSecret()), blindingFactor), true),
                    Optional.empty()
            );
            blindedMessages.add(blindedMessage);
        }

        CompletableFuture<GetKeysResponse> keysFuture = cashuHttpClient.getKeys(selectedKeysetId);

        // Create swap payload
        PostSwapRequest swapRequest = new PostSwapRequest();
        swapRequest.inputs = receiveProofs.stream()
                .map(p -> new Proof(p.amount, p.keysetId, p.secret, p.c, Optional.empty(), Optional.empty()))
                .collect(Collectors.toList());
        swapRequest.outputs = blindedMessages;

        Log.d(TAG, "Attempting to swap proofs from PaymentRequestPayload");

        PostSwapResponse response = cashuHttpClient.swap(swapRequest).join();
        if (response == null || response.signatures == null || response.signatures.isEmpty()) {
            throw new RedemptionException("No signatures returned from mint during swap (PRPayload)");
        }

        GetKeysResponse keysResponse = keysFuture.join();
        if (keysResponse == null || keysResponse.keysets == null || keysResponse.keysets.isEmpty()) {
            throw new RedemptionException("Failed to get keys from mint (PRPayload)");
        }

        Log.d(TAG, "Successfully swapped and received proofs (PRPayload)");

        List<Proof> proofs = constructAndVerifyProofs(
                response,
                keysResponse.keysets.get(0),
                secrets,
                blindingFactors
        );
        if (proofs.isEmpty()) {
            throw new RedemptionException("Failed to verify proofs from mint (PRPayload)");
        }

        Log.d(TAG, "Successfully constructed and verified proofs (PRPayload)");

        Token newToken = new Token(proofs, payload.unit, mintUrl);
        Log.d(TAG, "PaymentRequestPayload redemption successful! New token created.");
        return newToken.encode();
    } catch (JsonSyntaxException e) {
        throw new RedemptionException("Invalid JSON for PaymentRequestPayload: " + e.getMessage(), e);
    } catch (RedemptionException e) {
        throw e;
    } catch (Exception e) {
        String errorMsg = "PaymentRequestPayload redemption failed: " + e.getMessage();
        Log.e(TAG, errorMsg, e);
        throw new RedemptionException(errorMsg, e);
    }
}
```

This function is the counterpart to `redeemToken`, but for NUT-18 DM payloads.

---

## 3. Nostr Helpers in Kotlin (NIP-17 + NIP-44)

Because `nostr-sdk-jvm` is coroutine-focused and Kotlin-native, the cleanest integration path is via **Kotlin helper classes**, then calling them from Java.

### 3.1. Ephemeral identity + nprofile

**File:** `app/src/main/kotlin/com/electricdreams/shellshock/nostr/NostrPayment.kt`

```kotlin
package com.electricdreams.shellshock.nostr

import android.util.Log
import rust.nostr.sdk.Nip19Profile
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.SecretKey

private const val TAG = "NostrPayment"

/**
 * Simple holder for an ephemeral Nostr identity used for a single payment.
 */
data class EphemeralIdentity(
    val secretKey: SecretKey,
    val publicKey: PublicKey,
    val nprofile: String
)

object NostrHelpers {
    /**
     * Create an ephemeral Nostr identity and corresponding nprofile that includes the
     * provided relays.
     */
    fun createEphemeralIdentity(relays: List<String>): EphemeralIdentity {
        val sk = SecretKey.Companion.generate()
        val pub = sk.toPublicKey()
        Log.i(TAG, "Generated ephemeral Nostr keypair: pubkey=${pub.toHex()}")

        val relayUrls = relays.map { RelayUrl(it) }
        val nip19Profile = Nip19Profile(pub, relayUrls)
        val nprofile = nip19Profile.toBech32()

        Log.i(TAG, "Ephemeral nprofile=$nprofile")
        return EphemeralIdentity(sk, pub, nprofile)
    }
}
```

> Note: method names like `toPublicKey()`, `toHex()`, and the `Nip19Profile` constructor may differ slightly; use Android Studio’s type hints and completions against the real `rust.nostr.sdk.*` classes.

### 3.2. `NostrPaymentListener` (kind 14 + NIP-44)

Extend `NostrPayment.kt` with a listener class that:

- Builds a `Client` from a `ClientBuilder` and `NostrSigner.secretKey(secretKey)`.
- Connects to a fixed set of relays.
- Streams events with a `Filter` for `Kind(14)` and our `PublicKey`.
- For each event:
  - Calls `client.unwrapGiftWrap(event)` to get an `UnwrappedGift` and its inner event.
  - Decrypts the inner event’s content with NIP-44 using the SDK’s signer (e.g. `signer.nip44Decrypt(secretKey, senderPubkey, encryptedContent)` or similar; exact method name to be resolved in IDE).
  - Hands the decrypted content to `CashuPaymentHelper.redeemFromPRPayload`.
- On success: calls `onSuccess(token)` and stops itself.
- On errors: logs extensively and calls `onError(message)` (but the POS may still succeed via NFC).

Skeleton:

```kotlin
class NostrPaymentListener(
    private val secretKey: SecretKey,
    private val expectedAmount: Long,
    private val allowedMints: List<String>,
    private val relays: List<String>,
    private val onSuccess: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var job: Job? = null

    fun start() { /* launch coroutine with Client, Filter, stream events */ }
    fun stop() { /* cancel coroutine & clean up */ }

    private suspend fun handleGiftWrappedEvent(client: Client,
                                               signer: NostrSigner,
                                               event: Event) {
        // unwrap giftwrap, nip44-decrypt, redeemFromPRPayload
    }
}
```

Implementation details are described earlier; the exact method names and signatures must be resolved against the real SDK API.

---

## 4. Integration with `ModernPOSActivity`

**File:** `app/src/main/java/com/electricdreams/shellshock/ModernPOSActivity.java`

### 4.1. Static relays and listener field

```java
// Nostr relays to use for NIP-17 gift-wrapped DMs
private static final String[] NOSTR_RELAYS = new String[] {
    "wss://relay.primal.net",
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://nostr.mom"
};

private NostrPaymentListener nostrListener;
```

### 4.2. Generating the per-payment nprofile and QR PaymentRequest

In `proceedWithUnifiedPayment(long amount)`:

```java
// --- Generate ephemeral Nostr identity for this payment ---
String qrPaymentRequestLocal = null;
EphemeralIdentity ephIdentity = null;
try {
    List<String> relayList = Arrays.asList(NOSTR_RELAYS);
    ephIdentity = NostrHelpers.createEphemeralIdentity(relayList);

    // QR-specific PaymentRequest WITH Nostr transport (NUT-18)
    qrPaymentRequestLocal = CashuPaymentHelper.createPaymentRequestWithNostr(
            amount,
            "Payment of " + amount + " sats",
            allowedMints,
            ephIdentity.getNprofile()
    );
    if (qrPaymentRequestLocal == null) {
        Log.e(TAG, "Failed to create QR payment request with Nostr transport");
    } else {
        Log.d(TAG, "Created QR payment request with Nostr: " + qrPaymentRequestLocal);
    }

    // Start Nostr listener for this ephemeral identity
    if (ephIdentity != null && qrPaymentRequestLocal != null) {
        startNostrListener(ephIdentity.getSecretKey(), amount, allowedMints);
    }
} catch (Exception e) {
    Log.e(TAG, "Error generating ephemeral Nostr identity: " + e.getMessage(), e);
}

final String finalHcePaymentRequest = hcePaymentRequestLocal;
final String finalQrPaymentRequest = qrPaymentRequestLocal;
```

(The rest of the NFC/HCE flow stays exactly as is.)

### 4.3. Starting and stopping the Nostr listener

Add:

```java
private void startNostrListener(rust.nostr.sdk.SecretKey secretKey,
                                long amount,
                                List<String> allowedMints) {
    // Stop any existing listener first
    if (nostrListener != null) {
        nostrListener.stop();
        nostrListener = null;
    }

    List<String> relays = Arrays.asList(NOSTR_RELAYS);
    Log.d(TAG, "Starting NostrPaymentListener on relays: " + relays);

    nostrListener = new NostrPaymentListener(
            secretKey,
            amount,
            allowedMints,
            relays,
            token -> {
                Log.d(TAG, "NostrPaymentListener reported success");
                handlePaymentSuccess(token);
            },
            message -> {
                Log.e(TAG, "NostrPaymentListener error: " + message);
                // NFC/HCE path may still succeed; we only log here.
            }
    );
    nostrListener.start();
}
```

Then ensure you stop the listener in:

- `handlePaymentSuccess`:

  ```java
  if (nostrListener != null) {
      Log.d(TAG, "Stopping NostrPaymentListener due to payment success");
      nostrListener.stop();
      nostrListener = null;
  }
  ```

- `handlePaymentError`:

  ```java
  if (nostrListener != null) {
      Log.d(TAG, "Stopping NostrPaymentListener due to payment error");
      nostrListener.stop();
      nostrListener = null;
  }
  ```

- `onDestroy()`:

  ```java
  if (nostrListener != null) {
      Log.d(TAG, "Stopping NostrPaymentListener in onDestroy");
      nostrListener.stop();
      nostrListener = null;
  }
  ```

- The NFC dialog cancel button and `setOnCancelListener`:

  ```java
  if (nostrListener != null) {
      Log.d(TAG, "Stopping NostrPaymentListener due to user/dialog cancel");
      nostrListener.stop();
      nostrListener = null;
  }
  ```

---

## 5. NIP-17 / NIP-44 Behavior and Logging

- **Event kind**: listen for **kind 14** events (gift-wrapped) addressed to our ephemeral pubkey.
- **Unwrapping**: use `client.unwrapGiftWrap(event)` to extract the inner DM event.
- **Decryption**: use **NIP-44** decryption APIs exposed by `nostr-sdk-jvm` (via `NostrSigner` or equivalent) to decrypt the inner DM content.
- **Payload format**: decrypted content must be a JSON `PaymentRequestPayload` per NUT-18 spec.
- **Validation**: rely on `redeemFromPRPayload` to:
  - verify mint, unit, amount, and fees,
  - swap proofs,
  - reissue a new token.

**Logging**:

- Log at INFO/DEBUG for:
  - ephemeral key generation (`pubkey`, `nprofile`),
  - relay additions, connections, and any errors,
  - each received kind-14 event (`id`, `kind`),
  - unwrapped inner events (`id`, `kind`),
  - decrypted DM payloads (truncated if needed),
  - redemption attempts and results.

- Log at WARN/ERROR for:
  - decryption failures,
  - JSON parse errors,
  - mint and amount mismatches,
  - swap/verifications failures,
  - any Nostr connectivity or subscription issues.

These logs should be visible via `adb logcat` filtered by tags like `NostrPayment`, `NostrPaymentListener`, `CashuPaymentHelper`, and `ModernPOSActivity`.

---

## 6. Open Questions / Things to Verify in IDE

Because this plan is based on decompiled signatures from `nostr-sdk-jvm` rather than official docs, a few method names and signatures must be confirmed in Android Studio:

1. **SecretKey → PublicKey**
   - Confirm the correct way to obtain `PublicKey` from `SecretKey` (e.g. `toPublicKey()` or `publicKey()` via another helper).

2. **Creating NostrSigner from SecretKey**
   - Confirm whether `NostrSigner.secretKey(secretKey)` or another constructor is used.

3. **NIP-44 decryption method**
   - Confirm the exact method name/signature to decrypt DM content using NIP-44 in `nostr-sdk-jvm` (parameters: local secret, remote pubkey, ciphertext, and possibly `Nip44Version`).

4. **Event streaming API**
   - Confirm how to stream events from multiple relays (`streamEventsFrom`, `next`, etc.) and how to iterate over events in each batch.

Once these are resolved in the IDE, implement the concrete Nostr listener logic in `NostrPaymentListener`, wire it into `ModernPOSActivity`, and you’ll have a fully functional NUT-18 + NIP-17 + NIP-44 payment path alongside NFC/HCE.
