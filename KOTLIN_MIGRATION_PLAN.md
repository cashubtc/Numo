# Kotlin Refactor Plan

> Status: Draft – initial plan and first steps

## 1. Goals & Scope

- Refactor the **Shellshock** Android app so that **most application logic is in Kotlin**, while **keeping delicate NFC / low-level payment code in Java**.
- Preserve existing behavior (payments, NFC, smartcard operations, history, items, settings, etc.).
- Improve readability, null-safety, and testability while keeping the migration incremental and shippable.

Out of scope (for this migration):
- Major feature changes or redesigns.
- Large architectural rewrites (e.g. full MVVM + Compose) – can be layered on later.
- Converting **delicate NFC / Satocash / Nostr / NDEF** low-level code to Kotlin – those stay in Java for now.

---

## 2. Current State Snapshot

Based on the current tree under `app/src/main`:

- **Kotlin already present**
  - `app/src/main/java/com/electricdreams/shellshock/PaymentReceivedActivity.kt`
  - `app/src/main/java/com/electricdreams/shellshock/ui/theme/Color.kt`
  - `app/src/main/java/com/electricdreams/shellshock/ui/theme/Theme.kt`
  - `app/src/main/java/com/electricdreams/shellshock/ui/theme/Type.kt`

- **Representative Java files (partial listing)**
  - Core POS UI:
    - `ModernPOSActivity.java`
    - `PaymentRequestActivity.java`
    - `TopUpActivity.java`
    - `BalanceCheckActivity.java`
  - **NFC / Cashu / Satocash core (to remain in Java for now):**
    - `SatocashWallet.java`
    - `SatocashNfcClient.java`
    - `NdefHostCardEmulationService.java`
    - `NdefProcessor.java`
    - `CashuPaymentHelper.java`
  - Domain models & utilities (safe to migrate):
    - `core/model/Item.java`
    - `core/model/BasketItem.java`
    - `core/model/Amount.java`
    - `core/util/ItemManager.java`
    - `core/util/BasketManager.java`
    - `core/util/CurrencyManager.java`
    - `core/util/MintManager.java`
    - `core/data/model/PaymentHistoryEntry.java`
    - `core/data/model/TokenHistoryEntry.java`
  - Background work:
    - `core/worker/BitcoinPriceWorker.java`
  - **Nostr support (to remain in Java for now):**
    - `nostr/*.java` (e.g. `Nip19.java`, `Nip44.java`, `NostrEvent.java`, `NostrWebSocketClient.java`, `NostrKeyPair.java`, `Bech32.java`, `Nip59.java`, `NostrPaymentListener.java`, etc.)
  - UI lists & adapters:
    - `feature/items/*.java` (Item list, selection, basket, etc.)
    - `feature/history/*.java` (payment history, token history, transaction detail)
    - `feature/settings/*.java` (mints, items, currency, top-level settings)
    - `ui/adapter/*.java` (history lists, mints list, token list)

Build is already configured via Gradle Kotlin DSL (`build.gradle.kts` files), so Kotlin is first‑class.

---

## 3. Migration Strategy (High-Level)

1. **Keep migration incremental and buildable at all times.**
   - Avoid a “big bang” rewrite.
   - Convert class-by-class or feature-by-feature.

2. **Start with the least risky layers first.**
   - Core models and utilities.
   - Pure logic / data classes without Android framework dependencies.

3. **Then move into boundary layers and UI.**
   - Background workers.
   - Activities, adapters, and other UI glue.

4. **Explicitly keep delicate low-level code in Java.**
   - Do **not** migrate for now:
     - `SatocashWallet`, `SatocashNfcClient`, `NdefHostCardEmulationService`, `NdefProcessor`, `CashuPaymentHelper`.
     - Any classes under `com.electricdreams.shellshock.nostr`.
   - Treat these as stable, battle-tested Java modules called from Kotlin.

5. **Leverage Java–Kotlin interop.**
   - Kotlin code can call these Java classes directly.
   - Keep their public APIs stable.

6. **Continuously run and test.**
   - Build and run after each small batch of conversions.
   - Add/keep small sanity tests where possible.

---

## 4. Detailed Step-by-Step Plan

### Step 0 – Tooling & Project Preparation

- [ ] Confirm Kotlin Android plugin and stdlib are correctly configured in `app/build.gradle.kts`.
- [ ] Decide on Kotlin style and static analysis:
  - Optional: add **ktlint** or **detekt** (later in the process).
- [ ] Ensure Android Studio’s **Java → Kotlin Converter** is available for manual assists (but expect to hand-tune output).

### Step 1 – Inventory & Prioritization

- [ ] Generate a list of all `*.java` files under `app/src/main/java` (done informally via `rg`).
- [ ] Classify them into categories:
  - Models & DTOs
  - Utilities / managers
  - Background workers
  - NFC / card / network (will remain Java)
  - Nostr (will remain Java)
  - Activities, Adapters, Services
- [ ] Explicitly tag which ones are **"do not convert"** (NFC, Satocash, Nostr, NDEF).
- [ ] Decide migration order for the rest (proposed below).

**Proposed order (for classes we *will* convert):**
1. Core models and domain utilities.
2. Background worker(s).
3. UI: Activities, Fragments (if any), Adapters.

### Step 2 – Convert Core Models & Domain Utilities

_Targets (examples, safe to migrate):_
- `core/model/Amount.java`
- `core/model/Item.java`
- `core/model/BasketItem.java`
- `core/data/model/PaymentHistoryEntry.java`
- `core/data/model/TokenHistoryEntry.java`
- `core/util/ItemManager.java`
- `core/util/BasketManager.java`
- `core/util/CurrencyManager.java`
- `core/util/MintManager.java`

**For each class:**

1. **Convert to Kotlin** (either via IDE or by hand), creating `*.kt` in the same package.
2. Use **idiomatic Kotlin** where safe:
   - Prefer `data class` for simple models (e.g. `Item`, `BasketItem`, history entries).
   - Use Kotlin `val`/`var`, `when`, and nullability for safety.
   - Where implementing `Parcelable`, consider `@Parcelize` (once the plugin is enabled) to reduce boilerplate.
3. Keep the public API compatible where callers rely on it:
   - Same method/function names where practical.
   - Keep `public` properties / methods that are used across packages.
4. Remove (or deprecate, temporarily) the Java versions once the project compiles and runs.
5. Run the app and exercise basic flows that touch these classes (items list, baskets, settings, history views, etc.).

### Step 3 – Convert Background Workers

_Target:_
- `core/worker/BitcoinPriceWorker.java`

Actions:
1. Port the worker to Kotlin, preserving threading and lifecycle behavior.
2. Consider leveraging Kotlin features:
   - Use `suspend` functions and coroutines if appropriate (optional for now).
   - Encapsulate callbacks with higher‑order functions / flows later.
3. Verify price polling works and that consumers (e.g. `ModernPOSActivity`) still behave correctly.

### Step 4 – Convert UI Layer (Activities, Adapters, Settings)

_Targets (examples):_
- Root activities:
  - `ModernPOSActivity.java`
  - `PaymentRequestActivity.java`
  - `TopUpActivity.java`
  - `BalanceCheckActivity.java`
- Feature activities:
  - `feature/items/*.java` (Item list/selection/basket)
  - `feature/history/*.java`
  - `feature/settings/*.java`
- Adapters & UI helpers:
  - `ui/adapter/*.java`

Actions:
1. Convert adapters and simple activities first (history, lists, settings) to build confidence.
2. Then convert **`ModernPOSActivity`** and other complex screens:
   - Use ViewBinding/View references the same way initially.
   - Optionally start extracting some logic into Kotlin helper classes to slim down activities.
   - Keep all calls into `SatocashWallet`, `SatocashNfcClient`, `CashuPaymentHelper`, `NdefHostCardEmulationService`, and Nostr Java code as-is.
3. After each screen’s migration, manually test the corresponding flows:
   - Launch, rotate, NFC interactions if relevant, navigation.

### Step 5 – NFC / Satocash / Nostr / NDEF Code (Remain Java)

For now, treat the following namespaces as **stable Java modules**:

- `com.electricdreams.shellshock.SatocashWallet`
- `com.electricdreams.shellshock.SatocashNfcClient`
- `com.electricdreams.shellshock.ndef.*` (including `NdefHostCardEmulationService`, `NdefProcessor`, `CashuPaymentHelper`)
- `com.electricdreams.shellshock.nostr.*` (NIP helpers, events, keys, WebSocket client, listener, etc.)

Guidelines:
- Do **not** convert these to Kotlin as part of this migration.
- If needed, only apply small, targeted Java refactors (comments, logging, tiny bug fixes).
- Interact with them from Kotlin via their existing public APIs.

### Step 6 – Cleanup & Follow-Ups

- [ ] Remove any remaining Java files once their Kotlin equivalents are stable (excluding the NFC/Satocash/Nostr/NDEF set).
- [ ] Run a full **code style** pass (ktlint / IDE reformat) for Kotlin code.
- [ ] Consider incremental architectural improvements for Kotlin parts:
  - ViewModels, coroutines, Flow, dependency injection.
- [ ] Update documentation:
  - Mention that the codebase is Kotlin-first but retains low-level NFC / payment plumbing in Java for stability.

---

## 5. Immediate Next Actions (This Session)

These are the **concrete starting steps** we should tackle now.

1. **Document this migration plan in the repo** (this file – updated to reflect keeping NFC/Satocash/Nostr/NDEF in Java).
2. **Start with core model & utility conversion** – safest, lowest risk.
   - Candidate 1: `core/model/Amount.java` → `Amount.kt` (already conceptually simple, used for formatting amounts across the app).
   - Candidate 2: `core/model/Item.java` → `Item.kt` (data holder with `Parcelable`).
   - Candidate 3: `core/util/ItemManager.java` → `ItemManager.kt` (logic constrained to JSON, SharedPreferences & file IO).
3. After converting each candidate, build and run the app, fix any interop issues, then delete the Java version (keeping NFC/Satocash/Nostr/NDEF Java code untouched).

---

## 6. Start of Step 2 – Design Notes for First Conversions

### 6.1 `Amount` (core/model/Amount.java → Amount.kt)

Current behavior:
- Holds a `value: long` and a `Currency` enum (BTC, USD, EUR, GBP, JPY).
- `Currency.fromCode(String)` maps unknown fiat codes to USD, and handles `SAT`/`SATS` as BTC.
- `toString()` formats amounts:
  - BTC: `₿<value>` (no decimals, `value` is in sats).
  - Fiat: `value` is in minor units (cents); most currencies to two decimals; JPY no decimals.

Kotlin design:
- Convert `Currency` to a Kotlin `enum class` with property `symbol: String` and a `companion object` for `fromCode`.
- `Amount` becomes an immutable `data class`:

```kotlin
package com.electricdreams.shellshock.core.model

import java.text.NumberFormat
import java.util.Locale

/**
 * Represents a monetary amount with currency.
 * For BTC: [value] is satoshis.
 * For fiat currencies: [value] is minor units (e.g. cents).
 */
data class Amount(
    val value: Long,
    val currency: Currency,
) {
    enum class Currency(val symbol: String) {
        BTC("₿"),
        USD("$"),
        EUR("€"),
        GBP("£"),
        JPY("¥");

        companion object {
            fun fromCode(code: String): Currency = when {
                code.equals("SAT", ignoreCase = true) ||
                    code.equals("SATS", ignoreCase = true) -> BTC
                else -> runCatching { valueOf(code.uppercase(Locale.US)) }
                    .getOrElse { USD }
            }
        }
    }

    override fun toString(): String {
        return when (currency) {
            Currency.BTC -> {
                val formatter = NumberFormat.getNumberInstance(Locale.US)
                "${currency.symbol}${formatter.format(value)}"
            }
            Currency.JPY -> {
                val major = value / 100.0
                String.format(Locale.US, "%s%.0f", currency.symbol, major)
            }
            else -> {
                val major = value / 100.0
                String.format(Locale.US, "%s%.2f", currency.symbol, major)
            }
        }
    }
}
```

Integration notes:
- Existing Java callers like `new Amount(value, Amount.Currency.BTC).toString()` will still work from Java.
- Keep the package name and simple class name identical so imports don’t change.

### 6.2 `Item` (core/model/Item.java → Item.kt)

Conversion goals:
- Use a `data class` to avoid boilerplate.
- Use Kotlin `@Parcelize` for `Parcelable` (once the `kotlin-parcelize` plugin is enabled in Gradle).

Sketch:

```kotlin
@Parcelize
data class Item(
    var id: String? = null,
    var name: String? = null,
    var variationName: String? = null,
    var sku: String? = null,
    var description: String? = null,
    var category: String? = null,
    var gtin: String? = null,
    var price: Double = 0.0,
    var quantity: Int = 0,
    var alertEnabled: Boolean = false,
    var alertThreshold: Int = 0,
    var imagePath: String? = null,
) : Parcelable {
    val displayName: String
        get() = if (!variationName.isNullOrEmpty()) {
            "$name - $variationName"
        } else {
            name.orEmpty()
        }
}
```

Once `Item.kt` is in place and compiles, we can remove `Item.java` and adjust any Java code that relied on the static `CREATOR` field (Kotlin `@Parcelize` generates it automatically).

---

## 7. How to Proceed

1. Review this updated plan and adjust the conversion order if you want to prioritize certain screens (e.g. sales flow UI before settings).
2. When ready, I can:
   - Generate full Kotlin versions of `Amount`, `Item`, and `ItemManager` as real `*.kt` files.
   - Guide you through building and fixing any compiler issues step-by-step.
   - Keep all NFC / Satocash / Nostr / NDEF Java code untouched while wiring it into newer Kotlin UI and domain code.
