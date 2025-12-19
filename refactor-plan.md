# PaymentRequestActivity Refactor Plan

## Current Responsibilities
1. Intent parsing & pending payment creation
2. View initialization & tab UI wiring
3. Payment mode orchestration (Cashu/Nostr, Lightning, HCE)
4. Tip display logic
5. HCE service interaction
6. Payment result handling & history updates
7. Basket archiving & AutoWithdraw
8. Share & close interactions

## Proposed Modules
1. **PaymentRequestIntentData** (data class)
   - Parses intent extras (amount, tips, resume info, basket)
   - Provides derived properties (hasTip, formatted base amount)

2. **PaymentRequestInitializer** (helper)
   - Handles BitcoinPriceWorker init
   - Creates pending payment + stores ID
   - Returns PendingPaymentContext used later

3. **PaymentModeCoordinator**
   - Owns PaymentTabManager, Nostr handler, Lightning handler, HCE switching
   - Provides callbacks to Activity via interface

4. **PaymentResultCoordinator** (wrap showPaymentSuccess etc.)
   - Uses PaymentResultHandler for persistence
   - Handles UI feedback, vibration, sound, finishing Activity, basket archiving

## Activity After Refactor (~<300 lines)
- onCreate(): obtain intent data, init views, delegate to coordinators
- Implements small callback interfaces from coordinators
- Exposes minimal helper methods (update status text, show toasts)

## Refactor Steps
1. Create `PaymentRequestIntentData` to move extras parsing logic.
2. Create `PendingPaymentRegistrar` to handle `createPendingPayment()`.
3. Create `PaymentHceManager` for HCE switching/setup.
4. Create `PaymentResultCoordinator` for success/error/cancel flows.
5. Update activity to use new classes; slim down to orchestration only.
6. Ensure unit responsibility per class < 300 lines.
