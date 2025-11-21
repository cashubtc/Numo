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

**Status:** ✅ Implemented and compiling

**Files:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/Bech32.java`
- `app/src/main/java/com/electricdreams/shellshock/nostr/Nip19.java`
- `app/src/main/java/com/electricdreams/shellshock/nostr/NostrKeyPair.java`

Provides:

- Bech32 encode/decode and bit conversion
- NIP-19 encoders for `nsec`, `npub`, `nprofile`
- BouncyCastle-based secp256k1 keypair with x-only pubkey + NIP-19 wrappers

Build status: `./gradlew :app:assembleDebug` ✅

---

## Step 2 – NIP-01 Events (COMPLETED)

**Status:** ✅ Implemented and compiling

**File:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/NostrEvent.java`

Features:

- Fields: `id`, `pubkey`, `created_at`, `kind`, `tags`, `content`, `sig`.
- `computeId()`:
  - Builds `[0, pubkey, created_at, kind, tags, content]` as a Gson `JsonArray`.
  - Serializes to compact JSON (default Gson, no pretty printing).
  - Computes `sha256` over UTF-8 bytes and returns lowercase hex.
- `verify()`:
  - Checks `id` matches `computeId()`.
  - Parses `pubkey`, `sig` as hex.
  - Verifies a BIP-340 Schnorr signature over secp256k1 using BouncyCastle for EC arithmetic.

Build status: `./gradlew :app:assembleDebug` ✅

---

## Step 3 – NIP-44 v2 decrypt + conversation key (COMPLETED)

**Status:** ✅ Implemented and compiling

**File:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/Nip44.java`

Features:

- `getConversationKey(byte[] priv32, byte[] pubX32)`:
  - Lifts x-only pubkey to curve point with even Y using `liftX`.
  - Computes shared point = `d * P` and takes its x-coordinate (`shared_x`).
  - Uses `HKDFBytesGenerator(SHA256Digest)` with salt `"nip44-v2"` to derive a 32-byte conversation key from `shared_x`.

- `decrypt(String payloadBase64, byte[] conversationKey)`:
  - Parses NIP-44 v2 payload: `version (must be 2)`, `nonce(32)`, `ciphertext`, `mac(32)` from base64.
  - Uses HKDF-expand with SHA-256 and `nonce` as info to derive 76 bytes → `chacha_key(32)`, `chacha_nonce(12)`, `hmac_key(32)`.
  - Computes `HMAC-SHA256(hmac_key, nonce || ciphertext)` and verifies it equals `mac` (constant-time compare).
  - Decrypts ciphertext using `ChaCha7539Engine` (12-byte nonce, counter=0).
  - Removes padding per NIP-44 (2-byte BE length prefix, power-of-two padding) and returns UTF-8 plaintext.

Build status: `./gradlew :app:assembleDebug` ✅

---

## Step 4 – NIP-59 unwrap (COMPLETED)

**Status:** ✅ Implemented and compiling

**File:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/Nip59.java`

Features:

- `UnwrappedDm unwrapGiftWrappedDm(NostrEvent giftwrap, byte[] ourPriv32)`:
  - Validates that the outer event is kind 1059 and passes `giftwrap.verify()`.
  - Derives first conversation key `conv1 = Nip44.getConversationKey(ourPriv32, gwPub)`.
  - Decrypts `giftwrap.content` with `conv1` to JSON for a kind 13 `seal` event; parses and verifies it.
  - Derives second conversation key `conv2 = Nip44.getConversationKey(ourPriv32, seal.pubkey)`.
  - Decrypts `seal.content` with `conv2` to JSON for an inner kind 14 `rumor` event; parses it.
  - Ensures `rumor.kind == 14` and `rumor.pubkey` matches `seal.pubkey` (per NIP-17 requirement).
  - Returns an `UnwrappedDm` holding `giftwrap`, `seal`, and `rumor`, where `rumor.content` is the DM plaintext.

Build status: `./gradlew :app:assembleDebug` ✅

---

## Step 5 – WebSocket client (COMPLETED)

**Status:** ✅ Implemented and compiling

**File:**

- `app/src/main/java/com/electricdreams/shellshock/nostr/NostrWebSocketClient.java`

Features:

- Uses OkHttp's `WebSocket` to connect to a list of relay URLs.
- On `start()`, connects to each relay and, on `onOpen`, sends a `REQ`:
  - `{"kinds":[1059], "#p":["<our pubkey hex>"]}` with a generated `subscriptionId`.
- Parses messages from relays:
  - `EVENT` messages matching our `subscriptionId` are parsed into `NostrEvent` instances and passed to a pluggable `EventHandler`.
  - `NOTICE`, `CLOSED`, and `EOSE` messages are logged for visibility.
- Implements simple reconnect logic per relay with exponential backoff (1s → 2s → ... up to 60s) while `running==true`.
- Supports `start()` / `stop()` lifecycle to begin or halt all relay connections.

Build status: `./gradlew :app:assembleDebug` ✅

---

## Step 6 – NostrPaymentListener (NEXT)

**Planned file(s):**

- `app/src/main/java/com/electricdreams/shellshock/nostr/NostrPaymentListener.java`

Planned features:

- Wraps a `NostrWebSocketClient` instance.
- On each incoming event:
  - Filters for `kind == 1059` and `#p` containing our ephemeral pubkey (already handled at subscription level, but double-check if desired).
  - Uses `Nip59.unwrapGiftWrappedDm(event, ourPriv32)` to obtain the inner kind 14 rumor.
  - Treats `rumor.content` as a JSON `PaymentRequestPayload` and calls `CashuPaymentHelper.redeemFromPRPayload(...)`.
  - On first successful redemption, stops the listener and invokes a success callback with the encoded token.
  - On errors (decrypt failure, parse failure, redemption errors), logs and continues listening for additional DMs.

---

## Step 7 – Integration into ModernPOSActivity (LATER)

- Create ephemeral `NostrKeyPair` per payment.
- Generate `nprofile` over the configured relay list and embed it into the NUT-18 PaymentRequest transport.
- Instantiate and start `NostrPaymentListener` alongside NFC/HCE when starting a unified payment.
- Stop listener on payment success, error, dialog cancel, or activity destruction.
