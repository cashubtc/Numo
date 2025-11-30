# SwapToLightningMint Design Plan

## 0. Goal / Overview

Allow the POS to **accept ecash from any reachable mint**, even if that mint is _not_ in the merchant's configured allowed‑mints list, by:

1. Detecting that the payer is using an unknown mint.
2. Treating the unknown mint purely as a **temporary source** of liquidity.
3. Melting the payer's ecash at that mint to pay a **Lightning invoice generated from the POS’s configured Lightning mint**.
4. Verifying that the payment succeeded and that the preimage from the unknown mint’s melt matches the Lightning invoice’s payment hash.

The merchant ultimately receives funds on their **Lightning mint** only; the payer’s unknown mint is used only as an intermediate bridge.

This plan is now aligned with the **implemented code** in the app. Where relevant,
we reference concrete classes and methods.

---

## 1. Current Relevant Architecture & Flows

### 1.1. Cashu token validation & redemption

**File:** `app/src/main/java/com/electricdreams/numo/ndef/CashuPaymentHelper.kt`

Key parts:

- `isCashuToken(text: String?)`: checks for `cashuA` / `cashuB` prefix.
- `validateToken(tokenString, expectedAmount, allowedMints)`:
  - Decodes `org.cashudevkit.Token`.
  - Ensures `unit == CurrencyUnit.Sat`.
  - If `allowedMints` is not empty, checks that `token.mintUrl().url` is in `allowedMints`. **Currently rejects tokens from unknown mints.**
  - Checks that `token.value().value >= expectedAmount`.
- `redeemToken(tokenString: String?)`:
  - Gets `MultiMintWallet` via `CashuWalletManager.getWallet()`.
  - Decodes token with `org.cashudevkit.Token.decode(...)`.
  - Derives mint URL and then calls CDK wallet to **receive** the token.

There is also `redeemFromPRPayload(...)` for Nostr/Cashu payloads; it similarly enforces allowed mints and then redeems via a temporary JDK token.

### 1.2. Wallet & mint management (MultiMint + temporary single-mint)

**File:** `app/src/main/java/com/electricdreams/numo/core/cashu/CashuWalletManager.kt`

- Manages the app’s **primary `MultiMintWallet`** and its on-disk `WalletSqliteDatabase`.
- Registers only **allowed mints** when building the wallet (`rebuildWallet`).
- Exposes helpers:
  - `getWallet(): MultiMintWallet?`
  - `getBalanceForMint(mintUrl: String): Long`
  - `getAllMintBalances(): Map<String, Long>`
  - `fetchMintInfo(mintUrl: String): MintInfo?` (wraps `wallet.fetchMintInfo(MintUrl)` – used for reachability checks).
- **New (for SwapToLightningMint):**
  - `suspend fun getTemporaryWalletForMint(unknownMintUrl: String): Wallet`

This new method constructs a **temporary, single-mint `Wallet`** instance that:

- Uses a **fresh random mnemonic** (isolated from the main POS wallet seed).
- Uses an **ephemeral in-memory database** via `WalletDatabaseImpl(NoPointer)`.
- Is bound to a **single mint URL** (`unknownMintUrl`).

This separation implements the design requirement that:

> The other is a temporary single-mint Wallet instantiated with the unknown mint. It must not use the app's main seed phrase.

### 1.3. Lightning mint management

**Files:**

- `app/src/main/java/com/electricdreams/numo/core/util/MintManager.kt`
- `app/src/main/java/com/electricdreams/numo/payment/LightningMintHandler.kt`

`MintManager`:

- Holds the list of **allowed mints** and the **preferred Lightning mint** (single source of truth).
- Ensures that the preferred Lightning mint is always one of the allowed mints.

`LightningMintHandler`:

- Drives the **receive‑over‑Lightning** flow for a selected Lightning mint.
- `start(paymentAmount: Long, callback: Callback)`:
  - Gets wallet (`MultiMintWallet`).
  - Fetches preferred Lightning mint URL from `MintManager`.
  - Calls `wallet.mintQuote(mintUrl, CdkAmount(paymentAmount), memo)`.
  - Yields a BOLT11 invoice + quote ID via `callback.onInvoiceReady(bolt11, quote.id, mintUrlStr)`.
  - Starts WebSocket subscription + polling to wait for quote to be **paid**; then calls `wallet.mintWithMint(mintUrl, quoteId)` and finalizes by crediting proofs to the wallet.

So, this already gives us a good pattern for:

- **Obtaining a Lightning invoice** from the Lightning mint (via mint quote).
- **Monitoring its payment** and then **minting** proofs when paid.

### 1.4. Lightning melts (withdrawals via Lightning) – existing patterns

**Files:**

- `app/src/main/java/com/electricdreams/numo/feature/settings/WithdrawLightningActivity.kt`
- `app/src/main/java/com/electricdreams/numo/feature/settings/WithdrawMeltQuoteActivity.kt`
- `app/src/main/java/com/electricdreams/numo/feature/autowithdraw/AutoWithdrawManager.kt`

These show how we already use the CDK **MultiMintWallet** for melt operations:

- `wallet.meltQuote(MintUrl(mintUrl), invoice, null)` – withdraw to a **Lightning invoice**.
- `wallet.meltLightningAddressQuote(MintUrl(mintUrl), lightningAddress, amountMsat)` – withdraw to a Lightning address.
- `wallet.meltWithMint(MintUrl(mintUrl), meltQuote.id)` – execute the melt.
- `wallet.checkMeltQuote(MintUrl(mintUrl), meltQuote.id)` – get final state; `QuoteState.PAID` means invoice paid.

In the **implemented SwapToLightningMint flow**, we follow a slightly different pattern:

- For the **temporary single-mint `Wallet`** (bound to `unknownMintUrl`):
  - Use `wallet.meltQuote(bolt11, options)` to obtain a melt quote **without** going through MultiMintWallet.
  - Use `wallet.melt(quoteId)` to actually execute the melt.
- For the **primary `MultiMintWallet`**:
  - Use `multiMintWallet.mintQuote(lightningMintUrl, amount, memo)` to obtain the Lightning mint quote.
  - Use `multiMintWallet.checkMeltQuote(MintUrl(unknownMintUrl), meltQuote.id)` to check the canonical state and preimage from the unknown mint.

This pattern is exactly what we need, except the **payer’s mint is potentially not in our allowed list**.

### 1.5. HCE payment flow (NDEF Cashu)

**Files:**

- `app/src/main/java/com/electricdreams/numo/PaymentRequestActivity.kt`
- `app/src/main/java/com/electricdreams/numo/ndef/NdefHostCardEmulationService.java`

Key points:

- `PaymentRequestActivity.setupNdefPayment()`:
  - Configures HCE service with a Cashu payment request (`CashuPaymentHelper.createPaymentRequest`).
  - Registers `CashuPaymentCallback`.
- `NdefHostCardEmulationService` on token receive:
  - Extracts Cashu token from APDU payload using `CashuPaymentHelper.extractCashuToken`.
  - Calls `CashuPaymentHelper.validateToken(cashuToken, expectedAmount, allowedMints)`.
  - If valid, calls `CashuPaymentHelper.redeemToken(cashuToken)`.
  - On success: `onCashuTokenReceived(redeemedToken)` -> `PaymentRequestActivity.handlePaymentSuccess(token)`.

So in current behavior:

- **Unknown mint → `validateToken()` fails → we error out and the payment fails.**

Swap‑to‑Lightning‑mint should kick in right at this decision point.

---

## 2. New Concept: "SwapToLightningMint" Flow

### 2.1. High‑level idea (updated with two-wallet architecture)

When a Cashu token is presented from a mint **not in our allowed list**, we:

1. Use a **temporary single-mint `Wallet`** bound to the unknown mint to **request a melt quote** for our Lightning invoice.
2. Validate that the quoted **fee reserve <= 5%** and that the **quoted amount** covers the expected POS amount.
3. Use our primary **`MultiMintWallet`** to obtain (or reuse) a **Lightning mint quote** and BOLT11 invoice for the POS amount.
4. **Persist a SwapToLightningMint “frame”** on the pending `PaymentHistoryEntry`, tying together:
   - `unknownMintUrl`
   - the unknown-mint `meltQuoteId`
   - `lightningMintUrl`
   - the Lightning-mint `lightningQuoteId`.
5. Execute the **melt** on the unknown mint using the temporary `Wallet`.
6. Use the **MultiMintWallet** to check the canonical melt quote state (`checkMeltQuote`) and obtain `paymentPreimage`.
7. Verify that `SHA256(preimage) == payment_hash(bolt11)`.
8. Once verified, we rely on the existing Lightning mint flow to mint proofs and treat the POS payment as successful.

### 2.2. Responsibilities

- **Payer’s unknown mint**: burns their ecash and pays the Lightning invoice we control.
- **Our Lightning mint**: issues us new ecash that we credit to the merchant wallet.
- **POS app**: orchestrates both sides (invoice generation at Lightning mint + melt at unknown mint) and ensures cryptographic linkage via preimage verification.

---

### 3. Phase‑by‑Phase Design (Per Requirements, updated)

### Phase 1 – Detect Unknown Mint

**Current behavior:**

```kotlin
if (!allowedMints.isNullOrEmpty()) {
    val mintUrl = token.mintUrl().url
    if (!allowedMints.contains(mintUrl)) {
        Log.e(TAG, "Mint not in allowed list: $mintUrl")
        return false
    }
}
```

**Change:** Instead of immediately failing, we:

1. Extract `mintUrl` from the token.
2. If `mintUrl` is **not** in `allowedMints`, we branch into a new flow:
   - `SwapToLightningMintManager.swapFromUnknownMint(token, expectedAmount, mintUrl, allowedMints)` (working name),
   - or a new method inside `CashuPaymentHelper` that encapsulates this behavior.

For `NdefHostCardEmulationService`, we must update the flow so that when `validateToken(...)` indicates "unknown mint" we **do not immediately call `onCashuPaymentError`**, but instead try swapping.

Implementation detail:

- Introduce a structured result type for validation, e.g.:

  ```kotlin
  sealed class TokenValidationResult {
      object InvalidFormat : TokenValidationResult()
      data class ValidKnownMint(val token: org.cashudevkit.Token) : TokenValidationResult()
      data class ValidUnknownMint(val token: org.cashudevkit.Token, val mintUrl: String) : TokenValidationResult()
      data class InsufficientAmount(val required: Long, val actual: Long) : TokenValidationResult()
  }
  ```

- `validateToken(...)` returns `TokenValidationResult` instead of `Boolean`, so the caller can branch to **swap** when it sees `ValidUnknownMint`.

### Phase 2 – Obtain / Reuse Invoice from Lightning Mint (MultiMintWallet)

We need a **Lightning invoice** from our configured **Lightning mint** for `expectedAmount` sats (or possibly `expectedAmount` plus a small buffer if needed).

We already have a robust implementation:

- `LightningMintHandler.start(paymentAmount: Long, callback: Callback)` → uses `wallet.mintQuote(mintUrl, amount, memo)` to get:
  - `quote.id`
  - `quote.request` (BOLT11)

For SwapToLightningMint we need (and have implemented):

- A **programmatic / non‑UI** way to:
  1. Get the Lightning mint URL from `MintManager`.
  2. Request a `mintQuote` for the exact amount we want to receive (`expectedAmount`).
  3. Cache this **per payment** so that:
     - If we get multiple attempts from different unknown mints for the same POS payment, we reuse the same quote and invoice.

Design:

- Introduce a small coordinator class – e.g.

  ```kotlin
  data class LightningMintInvoice(
      val bolt11: String,
      val quoteId: String,
      val lightningMintUrl: String
  )

  object LightningMintInvoiceManager {
      suspend fun getOrCreateInvoiceForPayment(paymentId: String, amountSats: Long): LightningMintInvoice { /* ... */ }
  }
  ```

- This manager can:
  - Check existing `PaymentHistoryEntry` / pending payment data for a stored `lightningQuoteId` and invoice.
  - If not present, use `MultiMintWallet.mintQuote` (via something similar to `LightningMintHandler` but without UI callbacks) to create a new quote; persist `(quoteId, bolt11, mintUrl)` associated with `paymentId`.

This re‑use is aligned with the requirement "or get it if we already fetched it before for this payment" and is implemented by `LightningMintInvoiceManager.getOrCreateInvoiceForPayment`.

### Phase 3 – Request Melt Quote from Unknown Mint (Temporary Wallet)

### Phase 4 – Request Melt Quote from Unknown Mint

Using the **temporary single-mint `Wallet`**:

```kotlin
val tempWallet = CashuWalletManager.getTemporaryWalletForMint(unknownMintUrl)
val meltQuote = tempWallet.meltQuote(bolt11Invoice, null)
```

We then have:

- `meltQuote.amount` – the amount being paid (in sats).
- `meltQuote.feeReserve` – the fee reserve held by the mint.
- `meltQuote.id` – the **local quote ID** used later by `tempWallet.melt(meltQuote.id)`.

This matches your requirement:

> 1. We get a meltQuote from the temp wallet (verify lightning fee reserve does not exceed 5%)

### Phase 4 – Verify Fee Reserve ≤ 5%

We must check that the fee reserve is reasonable:

- Compute `feePercent = feeReserve / amount`.
- Reject if `feePercent > 0.05` (5%).

Pseudo‑code:

```kotlin
val quoteAmount = meltQuote.amount.value.toLong()
val feeReserve = meltQuote.feeReserve.value.toLong()

if (quoteAmount <= 0) {
    throw Exception("Invalid melt quote: amount is zero")
}

val feeRatio = feeReserve.toDouble() / quoteAmount.toDouble()
if (feeRatio > 0.05) {
    throw Exception("Melt fee reserve too high: ${feeRatio * 100}% (max 5%)")
}
```

If rejected, we:

- Surface to the cashier that the payer’s mint wants too high a fee.
- Do **not** proceed with the melt.

### Phase 5 – Persist Swap Frame + Execute Melt and Verify Preimage

#### 5.1. Persist SwapToLightningMint frame on PaymentHistory

We extend `PaymentHistoryEntry` with a JSON field:

```kotlin
@SerializedName("swapToLightningMintJson")
val swapToLightningMintJson: String? = null
```

and a DTO:

```kotlin
data class SwapToLightningMintFrame(
    val unknownMintUrl: String,
    val meltQuoteId: String,
    val lightningMintUrl: String,
    val lightningQuoteId: String,
)
```

This frame is written from `SwapToLightningMintManager` using
`PaymentsHistoryActivity.updatePendingWithLightningInfo`, which now
accepts an optional `swapToLightningMintJson` argument.

#### 5.2. Execute melt via temporary Wallet

With the `meltQuote` obtained from the temporary wallet, we execute:

```kotlin
tempWallet.melt(meltQuote.id)
```

This calls the **single-mint `Wallet.melt(quoteId)`** API, which
actually pays the Lightning invoice using the unknown mint.

#### 5.3. Fetch final melt quote & preimage via MultiMintWallet

After executing the melt, we rely on our **primary MultiMintWallet**
to obtain the canonical final state and preimage for the melt quote:

```kotlin
val mainWallet = CashuWalletManager.getWallet() ?: error("Wallet not initialized")
val finalQuote = mainWallet.checkMeltQuote(MintUrl(unknownMintUrl), meltQuote.id)

if (finalQuote.state != QuoteState.PAID) {
    throw Exception("Unknown-mint melt did not complete: state=${finalQuote.state}")
}

val preimageHex = finalQuote.paymentPreimage
    ?: throw Exception("Unknown-mint melt quote is PAID but has no paymentPreimage")
```

This matches your requirement:

> 4. We execute the melt from the temp wallet.
> 5. We check the payment hash matches sha256(pre-image) with the returned pre-image.

#### 5.4. Obtain preimage from the unknown mint (final quote)

The requirement states:

> When successful, we verify the payment pre-image provided in the melt quote response from the unknown mint we melted from. That pre-image should hash to the image obtained from the bolt11 invoice.

We will need to:

- Confirm via CDK FFI types in `cdk_ffi.kt` how preimages are exposed in `MeltQuote` / `MeltQuoteState` / `Melted` type (e.g. some field like `preimage`, `paymentPreimage` or inside a `LightningPayment` struct).
- Plan: once `finalQuote.state == PAID`, read `finalQuote.preimage` (or equivalent). If not available in `checkMeltQuote`, it may be present on the `melted` result; otherwise, we may have to update CDK or the Kotlin bindings.

#### 5.5. Extract payment hash from the BOLT11 invoice

We must also extract the payment hash (image) from the BOLT11 invoice used in the quote.

Design options:

1. **Use Lightning parsing in CDK** if available.
2. If not, add a small BOLT11 parsing helper (library or custom) on Android side that at least extracts the payment hash.

Either way, we need a utility:

```kotlin
fun getPaymentHashFromInvoice(bolt11: String): ByteArray { /* ... */ }
```

#### 6.4. Verify preimage → payment hash

Once we have:

- `preimage: ByteArray` from the unknown mint, and
- `paymentHash: ByteArray` from `bolt11`,

we compute:

```kotlin
val sha256Digest = MessageDigest.getInstance("SHA-256")
val computedHash = sha256Digest.digest(preimage)

if (!computedHash.contentEquals(paymentHash)) {
    throw Exception("Invalid payment preimage: does not match invoice hash")
}
```

Only if the hashes match and `finalQuote.state == QuoteState.PAID` do we consider the Lightning payment as successfully funded by the unknown mint.

#### 6.5. Ensure that our Lightning mint mints proofs

The overall sequence should be:

1. Lightning mint issues invoice (via `mintQuote`).
2. Unknown mint pays invoice by melting payer’s ecash.
3. The Lightning mint, once it sees the LN payment succeed, marks the quote as **paid**.
4. Our app should:
   - Either let `LightningMintHandler` continue to monitor the quote and call `mintWithMint` when it sees `paid`, or
   - Integrate the swap flow with `LightningMintHandler` so it is the single orchestrator:
     - It already does WebSocket + polling and final `mintWithMint`.

Design decision:

- **Recommended:** reuse `LightningMintHandler` logic rather than replicate it.
- That means our swap flow will:
  - Create or reuse a Lightning mint quote (and attach `LightningMintHandler` to it).
  - Use the `bolt11` from that quote as the `request` for the unknown‑mint melt.
  - Wait until both:
    - Unknown mint melt is `PAID` **and** preimage matches invoice hash.
    - Lightning mint quote is `PAID` and `mintWithMint` has been executed.

**Practical simplification:** For the POS, we may treat "unknown mint melt succeeded and preimage matches hash" as sufficient, trusting that the Lightning mint will deliver proofs or the operator can reconcile later. But for a robust design, we should keep the Lightning‑mint side in sync.

For this first iteration of the plan, we define **required** correctness as:

- Unknown mint melt `PAID` + preimage verification.
- The app then treats the payment as successful and calls `showPaymentSuccess(tokenFromLightningMint, amount)` or similar once minting completes.

---

## 4. Integration Points in Existing Code

### 4.1. In `NdefHostCardEmulationService` / `CashuPaymentHelper`

**Today:**

```java
isValid = CashuPaymentHelper.validateToken(cashuToken, expectedAmount, allowedMints);

if (!isValid) {
    String errorMsg = ...;
    paymentCallback.onCashuPaymentError(errorMsg);
    clearPaymentRequest();
    return;
}

String redeemedToken = CashuPaymentHelper.redeemToken(cashuToken);
paymentCallback.onCashuTokenReceived(redeemedToken);
```

**Planned:**

1. Replace `boolean` validation with structured `TokenValidationResult`.
2. Branch:

   ```kotlin
   when (val result = CashuPaymentHelper.validateTokenDetailed(cashuToken, expectedAmount, allowedMints)) {
       is ValidKnownMint -> {
           val redeemedToken = CashuPaymentHelper.redeemToken(cashuToken)
           callback.onCashuTokenReceived(redeemedToken)
       }
       is ValidUnknownMint -> {
           // Start swap flow
           SwapToLightningMintManager.swapFromUnknownMint(
               cashuToken = cashuToken,
               expectedAmount = expectedAmount,
               unknownMintUrl = result.mintUrl,
               // Provide current paymentId / context so we can reuse Lightning quote
               paymentContext = currentPaymentContext
           ) { swapResult ->
               when (swapResult) {
                   is SwapResult.Success -> callback.onCashuTokenReceived(swapResult.finalToken)
                   is SwapResult.Failure -> callback.onCashuPaymentError(swapResult.errorMessage)
               }
           }
       }
       is InvalidFormat, is InsufficientAmount -> {
           callback.onCashuPaymentError("Invalid or insufficient token")
       }
   }
   ```

### 4.2. `SwapToLightningMintManager` (new component)

**Location suggestion:**

- `app/src/main/java/com/electricdreams/numo/payment/SwapToLightningMintManager.kt`

**Responsibilities:**

- Orchestrate the **6 phases** for a single payment.
- Be **suspend‑friendly** (use coroutines) and UI‑agnostic.
- Expose a high‑level API:

  ```kotlin
  sealed class SwapResult {
      data class Success(
          val finalToken: String,        // token from Lightning mint credited to merchant
          val lightningMintUrl: String,
          val amountSats: Long
      ) : SwapResult()
      data class Failure(val errorMessage: String) : SwapResult()
  }

  object SwapToLightningMintManager {
      suspend fun swapFromUnknownMint(
          cashuToken: String,
          expectedAmount: Long,
          unknownMintUrl: String,
          paymentContext: PaymentContext
      ): SwapResult
  }
  ```

**Internal steps:**

1. Parse token,.confirm amount.
2. Check mint reachability (`CashuWalletManager.fetchMintInfo`).
3. Get or create Lightning mint invoice via `LightningMintInvoiceManager`.
4. Request melt quote from unknown mint.
5. Enforce fee reserve ≤ 5%.
6. Execute melt + `checkMeltQuote`.
7. Extract preimage and verify against invoice payment hash.
8. Ensure Lightning mint has minted proofs (reuse `LightningMintHandler` where feasible).
9. Return `Success(finalTokenFromLightningMint, lightningMintUrl, expectedAmount)`.

### 4.3. Persistence / history

- Extend `PaymentHistoryEntry` if needed to record:
  - `sourceMintUrl` (unknown mint).
  - `lightningMintUrl`.
  - `swapType = "swap_to_lightning_mint"`.
  - `meltQuoteIdFromUnknownMint`.
  - `lightningMintQuoteId`.

This will be useful for debugging and reconciliation.

---

## 5. Edge Cases & Failure Handling

- **Unknown mint unreachable:**
  - `fetchMintInfo` returns `null` → show error: "Payer’s mint is unreachable. Please try another mint or payment method.".

- **Insufficient amount / high fees:**
  - If `meltQuote.amount < expectedAmount` or `feeReserve > 5%`, abort swap with clear message.

- **Melt fails or stays pending:**
  - If `checkMeltQuote` reports `UNPAID` or `PENDING` for too long, surface error / timeout.

- **Preimage mismatch:**
  - Security‑critical: if SHA256(preimage) ≠ payment hash, **treat as failure** and do not mark payment as received.

- **Lightning mint never mints:**
  - Even if unknown mint reports `PAID`, but our Lightning mint quote is never marked `PAID`, we should eventually treat this as a failed swap and inform the operator.

---

## 6. Summary of Required CDK APIs (from `cdk_ffi.kt` / existing usage)

Already used in app and expected to be reused for SwapToLightningMint:

- `MultiMintWallet.fetchMintInfo(MintUrl) : MintInfo?`
- `MultiMintWallet.mintQuote(MintUrl, Amount, memo) : MintQuote`
- `MultiMintWallet.meltQuote(MintUrl, bolt11: String, ?) : MeltQuote`
- `MultiMintWallet.meltWithMint(MintUrl, quoteId: String) : Melted`
- `MultiMintWallet.checkMeltQuote(MintUrl, quoteId: String) : MeltQuote` (with `QuoteState`)

Additionally required (or to be added/verified):

- Accessor for **payment preimage** from melt result or quote.
- (Optional) helper to parse **payment hash from BOLT11** or external library.

---

## 7. Implementation Plan (Step‑By‑Step)

1. **Refactor token validation** (`CashuPaymentHelper.validateToken`) to return structured `TokenValidationResult`.
2. **Add SwapToLightningMintManager** with suspend API and unit tests (pure logic where possible).
3. **Add LightningMintInvoiceManager** to generate/reuse Lightning mint invoices per payment.
4. **Wire into NDEF/HCE flow** in `NdefHostCardEmulationService` to:
   - Defer errors on unknown mints until after attempting swap.
5. **Add preimage / invoice hash utilities**:
   - Implement SHA‑256 check.
   - Implement BOLT11 payment hash extraction.
6. **Extend payment history models** to record swap metadata.
7. **Add robust logging** for all phases 1–6 to aid debugging.
8. **QA flows**:
   - Known mint → existing behavior unchanged.
   - Unknown but reachable mint → swap path taken, melt + Lightning mint succeed.
   - Unknown unreachable mint → clear error, no partial success.
   - High‑fee quote or preimage mismatch → fail safely.

This concludes the initial design plan for **SwapToLightningMint**.


---

## 8. Refinement: Where and How to Fetch the Payment Preimage from CDK

This section refines the plan specifically around **how to obtain the payment preimage from the CDK types** and how to validate it against the BOLT11 invoice’s payment hash.

### 8.1. Relevant CDK Types

From `cdk-kotlin/lib/src/main/kotlin/org/cashudevkit/cdk_ffi.kt` we have two relevant data structures:

#### 8.1.1. `MeltQuote`

```kotlin
/**
 * FFI-compatible MeltQuote
 */
data class MeltQuote (
    /** Quote ID */
    var id: String,
    /** Quote amount */
    var amount: Amount,
    /** Currency unit */
    var unit: CurrencyUnit,
    /** Payment request */
    var request: String,
    /** Fee reserve */
    var feeReserve: Amount,
    /** Quote state */
    var state: QuoteState,
    /** Expiry timestamp */
    var expiry: ULong,
    /** Payment preimage */
    var paymentPreimage: String?,
    /** Payment method */
    var paymentMethod: PaymentMethod,
)
```

Key field for us:

- `paymentPreimage: String?`
  - This is present on the **MeltQuote** itself and is filled when the quote is in the appropriate state (after payment).
  - It is surfaced via both `wallet.meltQuote(...)` / `wallet.checkMeltQuote(...)` and the higher‑level database APIs (`getMeltQuote`).

#### 8.1.2. `MeltQuoteBolt11Response`

```kotlin
/**
 * FFI-compatible MeltQuoteBolt11Response
 */
public interface MeltQuoteBolt11ResponseInterface {
    fun amount(): Amount
    fun expiry(): ULong
    fun feeReserve(): Amount
    fun paymentPreimage(): String?
    fun quote(): String
    fun request(): String?
    fun state(): QuoteState
    fun unit(): CurrencyUnit?
}
```

- Used in `NotificationPayload.MeltQuoteUpdate` to push quote updates (e.g. over WebSockets).
- Also exposes `paymentPreimage(): String?`.

**Conclusion:** We can safely rely on **`MeltQuote.paymentPreimage`** and/or **`MeltQuoteBolt11Response.paymentPreimage()`** as the source of the preimage returned from the mint after a successful melt.

### 8.2. When Exactly is `paymentPreimage` Available?

By design of the protocol and the bindings:

- When we first request a quote via `wallet.meltQuote(...)`, `paymentPreimage` **will usually be `null`** — the Lightning payment has not yet been executed.
- After we execute the melt via `wallet.meltWithMint(MintUrl, quoteId)`, and then call
  - either `wallet.checkMeltQuote(MintUrl, quoteId)`
  - or receive a `NotificationPayload.MeltQuoteUpdate` (which wraps `MeltQuoteBolt11Response`),
  the quote will carry the **final state** (e.g. `QuoteState.PAID`) and a **non‑null `paymentPreimage`** if the Lightning payment was executed.

So the refined rule is:

- **We must always obtain the preimage from the _final_ melt quote** (`checkMeltQuote` or notification), **after** a successful `meltWithMint`.

### 8.3. Data Flow for the Unknown‑Mint Melt Preimage

For the SwapToLightningMint flow, we are concerned with the **unknown payer mint**. The data flow there will be:

1. Request a melt quote:

   ```kotlin
   val initialQuote = wallet.meltQuote(MintUrl(unknownMintUrl), bolt11Invoice, null)
   // initialQuote.paymentPreimage is expected to be null here
   ```

2. Execute the melt:

   ```kotlin
   val melted = wallet.meltWithMint(MintUrl(unknownMintUrl), initialQuote.id)
   // We don't rely on `melted` for preimage; we use the final quote.
   ```

3. Fetch the **final quote** and read `paymentPreimage`:

   ```kotlin
   val finalQuote = wallet.checkMeltQuote(MintUrl(unknownMintUrl), initialQuote.id)

   if (finalQuote.state != QuoteState.PAID) {
       throw Exception("Unknown-mint melt did not complete successfully: state=${finalQuote.state}")
   }

   val preimageHex = finalQuote.paymentPreimage
       ?: throw Exception("No payment preimage present on final melt quote")
   ```

4. Convert the hex-encoded preimage to bytes:

   ```kotlin
   fun hexToBytes(hex: String): ByteArray {
       val len = hex.length
       require(len % 2 == 0) { "Hex string must have even length" }
       return ByteArray(len / 2) { i ->
           val idx = i * 2
           hex.substring(idx, idx + 2).toInt(16).toByte()
       }
   }

   val preimageBytes = hexToBytes(preimageHex)
   ```

**Note:** We assume `paymentPreimage` is hex‑encoded (this is consistent with the rest of the CDK bindings that expose secrets and hashes as hex strings). If the underlying CDK changes to base64 or raw bytes, we would adjust the decoder accordingly, but the plan is to treat it as hex for now.

### 8.4. Alternative Path: Using `MeltQuoteBolt11Response` Notifications

If/when we start using WebSocket subscriptions to track the unknown‑mint melt as well, the notification payload type:

```kotlin
sealed class NotificationPayload {
    data class MeltQuoteUpdate(val quote: MeltQuoteBolt11Response) : NotificationPayload()
    // ...
}
```

will be emitted with a `quote` that exposes:

```kotlin
val preimageHex = quote.paymentPreimage()
```

We can unify handling by **always normalizing notifications into a `MeltQuote`** (or reading the same fields) and then reusing the same hex‑decode and verification logic described above.

For the first iteration, however, **we can rely solely on `wallet.checkMeltQuote`** and not subscribe to notifications for the unknown mint.

### 8.5. Extracting the Payment Hash from BOLT11

To compare the preimage provided by the unknown mint to the hash in the Lightning mint’s invoice, we need the **payment hash** embedded in the BOLT11 invoice.

We do not yet have a helper for this in the app; options:

1. **Use a small BOLT11 parsing library** (preferred for correctness and maintenance).
2. **Implement a minimal internal parser** that:
   - Decodes HRP / data part.
   - Walks TLV records until it finds the `p` tag (payment hash).

For planning purposes, we define an interface:

```kotlin
/**
 * Extract the 32-byte payment hash from a BOLT11 invoice.
 * @throws IllegalArgumentException if the invoice is invalid or contains no payment hash.
 */
fun extractPaymentHashFromBolt11(bolt11: String): ByteArray
```

Implementation details (library vs. manual) can be decided during coding, but the **call site** for the swap flow is clear.

### 8.6. Preimage vs. Payment Hash Verification Logic

Putting the pieces together, the core verification routine used by SwapToLightningMint becomes:

```kotlin
/**
 * Verify that the preimage reported by the unknown mint for a given melt quote
 * matches the payment hash encoded in the BOLT11 invoice.
 */
fun verifyMeltPreimageMatchesInvoice(
    preimageHex: String,
    bolt11Invoice: String
) {
    val preimageBytes = hexToBytes(preimageHex)

    // Compute SHA-256(preimage)
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val computedHash = digest.digest(preimageBytes)

    // Extract payment hash (32 bytes) from the invoice
    val invoiceHash = extractPaymentHashFromBolt11(bolt11Invoice)

    if (!computedHash.contentEquals(invoiceHash)) {
        throw IllegalStateException("Payment preimage does not match invoice payment hash")
    }
}
```

**Security property:** If this check passes:

- The unknown mint’s claimed preimage is **exactly** the secret whose SHA‑256 hash the Lightning mint encoded into the invoice’s payment hash.
- This binds the unknown mint’s reported payment to **the exact invoice** we generated from our Lightning mint.

### 8.7. Updated SwapToLightningMint Flow (Concrete Steps, Implemented)

Putting the pieces together, the implemented flow is:

1. **Temporary Wallet melt quote (unknown mint)**

   ```kotlin
   val tempWallet = CashuWalletManager.getTemporaryWalletForMint(unknownMintUrl)
   val meltQuote = tempWallet.meltQuote(bolt11Invoice, null)
   // Enforce feeReserve / amount <= 5%
   ```

2. **Lightning mint quote via MultiMintWallet**

   ```kotlin
   val lightningInvoiceInfo = LightningMintInvoiceManager.getOrCreateInvoiceForPayment(
       appContext,
       lightningMintUrl,
       paymentContext
   )
   val bolt11 = lightningInvoiceInfo.bolt11
   ```

3. **Persist SwapToLightningMint frame** on `PaymentHistoryEntry`.

4. **Execute melt on temp Wallet**

   ```kotlin
   tempWallet.melt(meltQuote.id)
   ```

5. **Check final melt quote & verify preimage** via `MultiMintWallet`:

   ```kotlin
   val finalQuote = mainWallet.checkMeltQuote(MintUrl(unknownMintUrl), meltQuote.id)
   val preimageHex = finalQuote.paymentPreimage ?: error("no paymentPreimage")
   verifyMeltPreimageMatchesInvoice(preimageHex, bolt11Invoice)
   ```

Only after all of the above succeed do we treat the unknown‑mint melt as
valid and continue with the rest of the SwapToLightningMint flow.

### 8.8. Summary of Preimage Handling Contract

- **Source of truth**: `MeltQuote.paymentPreimage` on the unknown mint’s final quote (via `wallet.checkMeltQuote`).
- **Encoding**: treated as hex string → `ByteArray` via `hexToBytes`.
- **Verification**: SHA‑256(preimage) must equal the BOLT11 invoice’s payment hash from `extractPaymentHashFromBolt11`.
- **Failure behavior**:
  - If `paymentPreimage` is null, or quote is not `PAID`, or hashes differ → **swap fails**; payment is not accepted.

This refinement can now be used as the implementation blueprint for the preimage verification part of the SwapToLightningMint flow.

