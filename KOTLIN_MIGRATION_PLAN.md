# Kotlin Refactor Plan

> Status: In Progress – core domain, history, items/basket, and most utilities migrated

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

**Kotlin modules now in place:**

- **Core models & data:**
  - `core/model/Amount.kt`
  - `core/model/Item.kt`
  - `core/model/BasketItem.kt`
  - `core/data/model/PaymentHistoryEntry.kt`
  - `core/data/model/TokenHistoryEntry.kt`

- **Core managers/utilities:**
  - `core/util/ItemManager.kt`
  - `core/util/BasketManager.kt`
  - `core/util/CurrencyManager.kt`
  - `core/util/MintManager.kt`

- **Worker:**
  - `core/worker/BitcoinPriceWorker.kt`

- **History UI:**
  - `feature/history/PaymentsHistoryActivity.kt`
  - `feature/history/TokenHistoryActivity.kt`
  - `feature/history/TransactionDetailActivity.kt`
  - `ui/adapter/PaymentsHistoryAdapter.kt`
  - `ui/adapter/TokenHistoryAdapter.kt`

- **Items & Basket UI:**
  - `feature/items/ItemListActivity.kt`
  - `feature/items/ItemSelectionActivity.kt`
  - `feature/items/ItemEntryActivity.kt`
  - `feature/items/BasketActivity.kt`

- **Misc UI:**
  - `PaymentReceivedActivity.kt`
  - `ui/theme/Color.kt`
  - `ui/theme/Theme.kt`
  - `ui/theme/Type.kt`

**Still in Java (by design or pending migration):**

- Core POS / flows:
  - `ModernPOSActivity.java`
  - `PaymentRequestActivity.java`
  - `TopUpActivity.java`
  - `BalanceCheckActivity.java`

- Sensitive NFC / Satocash / Nostr / NDEF (to remain Java for now):
  - `SatocashWallet.java`
  - `SatocashNfcClient.java`
  - `NdefHostCardEmulationService.java`
  - `NdefProcessor.java`
  - `CashuPaymentHelper.java`
  - `nostr/*.java` (NIP helpers, events, WebSocket client, etc.)

- Settings (next target):
  - `feature/settings/SettingsActivity.java`
  - `feature/settings/CurrencySettingsActivity.java`
  - `feature/settings/ItemsSettingsActivity.java`
  - `feature/settings/MintsSettingsActivity.java`

Build is configured via Gradle Kotlin DSL (`build.gradle.kts`), Kotlin is first-class, and the project compiles and runs with the above Kotlin components.

---

## 3. Migration Strategy (High-Level)

1. **Keep migration incremental and buildable at all times.**
2. **Migrate low-risk layers first (done):**
   - Core models, managers, worker.
3. **Migrate UI feature-by-feature (in progress):**
   - History UI → done.
   - Items/Basket UI → done.
   - Settings UI → in progress (next step).
   - Remaining activities (TopUp, BalanceCheck, PaymentRequest, ModernPOS) → later.
4. **Keep NFC/Satocash/Nostr/NDEF in Java.**
5. **Continuously test each feature flow after migration.**

---

## 4. Detailed Step-by-Step Plan (Updated)

### Step 2 – Core Models & Domain Utilities (COMPLETED)

**Migrated:**
- `Amount`, `Item`, `BasketItem` → idiomatic Kotlin data classes (with `@Parcelize` where appropriate).
- `PaymentHistoryEntry`, `TokenHistoryEntry` → Kotlin data classes with Gson annotations and null-safe accessors.
- `ItemManager`, `BasketManager`, `CurrencyManager`, `MintManager` → Kotlin singletons with the same public APIs as before.

Notes:
- `PaymentHistoryEntry` now has nullable backing fields for `unit` and `entryUnit` with `getUnit()` and `getEntryUnit()` providing non-null defaults (fixes an NPE reported when opening transaction details).

### Step 3 – Background Worker (COMPLETED)

**Migrated:**
- `BitcoinPriceWorker.java` → `BitcoinPriceWorker.kt`.

Key points:
- Preserved API: `getInstance`, `start()`, `stop()`, `getCurrentPrice()`, `satoshisToFiat()`, etc.
- Continues to cache prices per currency and call Coinbase API.

### Step 4 – UI Layer (Activities, Adapters, Settings)

#### 4.1 History UI (COMPLETED)

Converted to Kotlin:
- `PaymentsHistoryActivity`, `TokenHistoryActivity`, `TransactionDetailActivity`.
- `PaymentsHistoryAdapter`, `TokenHistoryAdapter`.

Behavior preserved:
- History storage in `SharedPreferences` (JSON via Gson).
- Clicks navigate to detailed views and back.
- Delete/clear flows work as before.

#### 4.2 Items & Basket UI (COMPLETED)

Converted to Kotlin:
- `ItemListActivity` – catalog management screen.
- `ItemSelectionActivity` – POS item selection with basket controls.
- `ItemEntryActivity` – add/edit item with image support.
- `BasketActivity` – basket review and checkout.

Behavior preserved:
- Uses `ItemManager`, `BasketManager`, `CurrencyManager`, and `BitcoinPriceWorker` Kotlin APIs.
- Basket flows (add/remove items, quantity controls, proceed to `ModernPOSActivity`) unchanged.

#### 4.3 Settings UI (IN PROGRESS – NEXT)

Targets to migrate next:
- `feature/settings/SettingsActivity.java`
- `feature/settings/CurrencySettingsActivity.java`
- `feature/settings/ItemsSettingsActivity.java`
- `feature/settings/MintsSettingsActivity.java`

Planned Kotlin behavior:
- `SettingsActivity`: simple navigation hub to the three sub-settings screens.
- `CurrencySettingsActivity`: uses `CurrencyManager` Kotlin API (`getCurrentCurrency()`, `setPreferredCurrency()`).
- `ItemsSettingsActivity`: uses `ItemManager` Kotlin API (`getAllItems()`, `clearItems()`, `importItemsFromCsv()`), and launches the now-Kotlin `ItemListActivity`.
- `MintsSettingsActivity`: uses `MintManager` Kotlin API (`getAllowedMints()`, `addMint()`, `removeMint()`, `resetToDefaults()`) and the existing `MintsAdapter` (currently Java, will interop with Kotlin just fine).

Once settings are migrated, all high-level configuration and catalog management will be Kotlin-based.

### Step 5 – NFC / Satocash / Nostr / NDEF (REMAIN IN JAVA)

No change: these remain Java and are consumed from Kotlin.

### Step 6 – Remaining Activities (PLANNED)

After settings:
1. Migrate `TopUpActivity`, `BalanceCheckActivity`, and `PaymentRequestActivity` to Kotlin.
2. Finally, migrate `ModernPOSActivity` to Kotlin, carefully keeping NFC/payment behavior intact and leaning on existing Java NFC/Satocash classes.

---

## 5. Completed Work Log

- [x] Enable `kotlin-parcelize` and set JVM target to 17.
- [x] Migrate core models: `Amount`, `Item`, `BasketItem`.
- [x] Migrate history models: `PaymentHistoryEntry`, `TokenHistoryEntry` (with null-safe units).
- [x] Migrate managers: `ItemManager`, `BasketManager`, `CurrencyManager`, `MintManager`.
- [x] Migrate `BitcoinPriceWorker`.
- [x] Migrate history activities and adapters:
  - `PaymentsHistoryActivity`, `TokenHistoryActivity`, `TransactionDetailActivity`.
  - `PaymentsHistoryAdapter`, `TokenHistoryAdapter`.
- [x] Migrate items/basket activities:
  - `ItemListActivity`, `ItemSelectionActivity`, `ItemEntryActivity`, `BasketActivity`.

All of the above are committed on branch `kotlin-refactor` and the app builds and runs.

---

## 6. Next Immediate Actions

1. **Migrate Settings UI to Kotlin**
   - [ ] `SettingsActivity` → `SettingsActivity.kt` (menu hub for settings screens).
   - [ ] `CurrencySettingsActivity` → `CurrencySettingsActivity.kt` (radio-group based currency selection).
   - [ ] `ItemsSettingsActivity` → `ItemsSettingsActivity.kt` (catalog status, CSV import, clear-all, open `ItemListActivity`).
   - [ ] `MintsSettingsActivity` → `MintsSettingsActivity.kt` (allowed mint list, add/remove/reset).

2. After migration:
   - [ ] Build and run; verify settings flows:
     - Currency changes persist and affect `BitcoinPriceWorker`/display.
     - Items settings correctly reflect catalog count and open item list.
     - Mints settings correctly update allowed mints and interact with `MintManager`.

3. Then proceed to remaining non-NFC activities (TopUp, BalanceCheck, PaymentRequest), and finally `ModernPOSActivity`.
