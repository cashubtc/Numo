# Minimal Nostr Implementation Progress

This document tracks the incremental implementation of a minimal nostr client inside the Android POS app, without relying on external nostr libraries.

## Overview

Goal: support NUT-18 over nostr using NIP-17 + NIP-44 + NIP-59, with an ephemeral per-payment identity and a background listener that redeems `PaymentRequestPayload` JSON via `CashuPaymentHelper.redeemFromPRPayload`.

Core pieces to implement:

1. Nostr key + NIP-19 identities
2. NIP-01 event representation & signing/verification
3. NIP-44 v2 decrypt + conversation key
4. NIP-59 unwrap (giftwrap 1059 → seal 13 → rumor 14)
5. OkHttp-based websocket client for nostr relays
6. NostrPaymentListener that pipes decrypted payload into Cashu
7. Integration into `ModernPOSActivity` unified payment flow

---

## Step 1 – Keys + NIP-19 identities (COMPLETED)

**Files:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/Bech32.java`
- `app/src/main/java/com/electricdreams/shellshock/nostr/Nip19.java`
- `app/src/main/java/com/electricdreams/shellshock/nostr/NostrKeyPair.java`

---

## Step 2 – NIP-01 Events (COMPLETED)

**File:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/NostrEvent.java`

---

## Step 3 – NIP-44 v2 decrypt + conversation key (COMPLETED)

**File:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/Nip44.java`

---

## Step 4 – NIP-59 unwrap (COMPLETED)

**File:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/Nip59.java`

---

## Step 5 – WebSocket client (COMPLETED)

**File:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/NostrWebSocketClient.java`

---

## Step 6 – NostrPaymentListener + redeemFromPRPayload (COMPLETED)

**Files:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/NostrPaymentListener.java`
- `app/src/main/java/com/electricdreams/shellshock/ndef/CashuPaymentHelper.java` (added `redeemFromPRPayload` + `PaymentRequestPayload`)

Behavior:

- `NostrPaymentListener`:
  - Subscribes to kind 1059 events with `#p=[ephemeral pubkey]` via `NostrWebSocketClient`.
  - Unwraps giftwrap → seal → rumor using `Nip59.unwrapGiftWrappedDm`.
  - Treats `rumor.content` as `PaymentRequestPayload` JSON and calls `CashuPaymentHelper.redeemFromPRPayload`.
  - On success, stops listening and invokes a success callback with the redeemed token.

- `CashuPaymentHelper.redeemFromPRPayload`:
  - Parses JSON into `PaymentRequestPayload { mint, unit, proofs, ... }`.
  - Validates mint/unit/proofs and amount ≥ expectedAmount.
  - Builds a temporary `Token` from proofs and delegates to existing `redeemToken`.

Build status: `./gradlew :app:assembleDebug` ✅

---

## Step 7 – Integration into ModernPOSActivity (COMPLETED)

**File:**

- `app/src/main/java/com/electricdreams/shellshock/ModernPOSActivity.java`

Changes:

- Added imports and fields:
  - `NostrKeyPair`, `NostrPaymentListener`
  - `NOSTR_RELAYS` array with 4 relays
  - `private NostrPaymentListener nostrListener;`

- In `proceedWithUnifiedPayment(long amount)`:
  - Still builds HCE PaymentRequest and starts HCE service when available.
  - **New nostr flow:**
    - Generates an ephemeral `NostrKeyPair eph = NostrKeyPair.generate()`.
    - Builds `relayList = Arrays.asList(NOSTR_RELAYS)`.
    - Derives `nprofile = Nip19.encodeNprofile(eph.getPublicKeyBytes(), relayList)`.
    - Creates QR PaymentRequest with Nostr transport:

      ```java
      String qrPaymentRequestLocal = CashuPaymentHelper.createPaymentRequestWithNostr(
              amount,
              "Payment of " + amount + " sats",
              allowedMints,
              nprofile
      );
      ```

    - On success, starts a new `NostrPaymentListener`:

      ```java
      nostrListener = new NostrPaymentListener(
              eph.getSecretKeyBytes(),
              eph.getHexPub(),
              amount,
              allowedMints,
              relayList,
              token -> runOnUiThread(() -> handlePaymentSuccess(token)),
              (msg, t) -> Log.e(TAG, "NostrPaymentListener error: " + msg, t)
      );
      nostrListener.start();
      ```

- Nostr listener lifecycle:
  - On **payment success** (`handlePaymentSuccess`): stops and clears `nostrListener`.
  - On **payment error** (`handlePaymentError`): stops and clears `nostrListener`.
  - On **activity destroy** (`onDestroy`): stops and clears `nostrListener`.

Build status: `./gradlew :app:assembleDebug` ✅

---

## Next steps / Testing

- Manual tests:
  - Confirm QR still displays correctly with embedded `nprofile` transport.
  - Confirm NFC/HCE path works as before.
  - Using a test Nostr sender, publish a giftwrapped DM (NIP-17 + NIP-59 + NIP-44) targeting the ephemeral pubkey and verify:
    - Listener logs receipt.
    - `redeemFromPRPayload` is called.
    - On valid payload, `handlePaymentSuccess(token)` is triggered and nostr listener stops.

- Possible future improvements:
  - Stronger validation on `PaymentRequestPayload` shape (e.g. allowed keys, replay protection).
  - Timeouts or manual cancel UI for nostr listener.
