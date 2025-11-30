# Deprecation Modernization Plan

Branch: `chore/modernize-deprecations`
Date: 2025-11-30

## Goals and Principles

- Eliminate deprecation warnings reported by `./gradlew build --warning-mode=all`.
- Prefer modern APIs and patterns instead of suppressing deprecations.
- Preserve existing behavior and UX (especially PIN, onboarding, checkout, and history flows).
- Keep changes incremental and well-scoped per feature area.
- Maintain readability and Apple-like, high-quality UX.

## Inventory of Current Deprecated Usages

Discovered via:

```bash
./gradlew build --warning-mode=all
rg "@Deprecated|@Suppress\("DEPRECATION"\)|deprecated" -n app
```

### 1. Vibration APIs

Files:
- `PinKeypadView.kt`
- `KeypadManager.kt`
- `TipSelectionActivity.kt`

Pattern:
- Use of `@Suppress("DEPRECATION")` around `vibrator.vibrate(duration: Long)` for pre-API 26/29 devices.

Impact:
- Haptic feedback on numeric keypads (PIN, amount entry, tips).

Modernization direction:
- Introduce a small vibration helper that encapsulates API-level branching.
- Prefer `VibrationEffect` on all supported API levels where available.
- Use feature detection (`hasVibrator()`) and gracefully no-op when vibration is unavailable.

### 2. Locale / Language APIs

File:
- `LanguageSettingsActivity.kt`

Pattern:
- Fallback to system locale via deprecated `resources.configuration.locale` for API < 24.

Impact:
- Determining app’s current language when no app-specific locale is set.

Modernization direction:
- Rely primarily on `AppCompatDelegate.getApplicationLocales()`.
- For system fallback, use non-deprecated configuration APIs where possible and centralize locale resolution logic.
- Keep exact mapping logic to supported set (`en` / `es`).

### 3. Activity Result APIs (onActivityResult)

Files:
- `PinEntryActivity.kt`
- `SettingsActivity.kt`
- `TipSelectionActivity.kt`

Pattern:
- Overrides of `onActivityResult` annotated with `@Deprecated("Deprecated in Java")` and direct use of request codes.

Impact:
- PIN reset flow.
- PIN verification flow before entering protected settings.
- Tip flow → payment request flow.

Modernization direction:
- Replace request-code–based result handling with Jetpack Activity Result APIs:
  - `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`.
- Encapsulate mapping from results to domain behavior (e.g., “PIN verified”, “basket updated”) in clear callbacks.
- Ensure back-compat with any callers expecting `Activity.RESULT_OK` / `RESULT_CANCELED` semantics.

### 4. Back Navigation (onBackPressed)

Files:
- `PinEntryActivity.kt`
- `RestoreWalletActivity.kt`
- `OnboardingActivity.kt`
- `ItemListActivity.kt`
- `ItemSelectionActivity.kt`

Pattern:
- Overrides of `onBackPressed()` with custom logic (cooldowns, step navigation, reordering mode, basket editing, etc.).

Impact:
- Critical to UX in multi-step flows and stateful screens.

Modernization direction:
- Migrate to `OnBackPressedDispatcher` with `onBackPressedDispatcher.addCallback(...)`.
- Keep screen-specific state machines, but move them into explicit back callbacks.
- Document behavior for each screen to avoid regressions.

### 5. Public Legacy API in Payment History

File:
- `PaymentsHistoryActivity.kt`

Pattern:
- Public `@Deprecated` overload:
  - `addToHistory(context, token: String, amount: Long)`.

Impact:
- Potential use by other modules or legacy callers.

Modernization direction:
- Keep method signature for binary/source compatibility but:
  - Route internally to the full-parameter overload (already done).
  - Document intended removal timeline in KDoc.
  - Optionally, add a lint note or internal usage search to plan actual removal.

## Detailed Modernization Steps

### A. Extract a Vibration Helper

**Objective:** Centralize all haptic feedback behavior and remove direct deprecated calls + suppressions.

**Proposed helper:** `VibrationHelper` in `ui/util` (or similar shared UI utilities package).

Responsibilities:
- Safe vibro lookup: `getSystemService(Context.VIBRATOR_SERVICE)`.
- API-level branching:
  - API 29+ (`Q`): `VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)`.
  - API 26–28: `VibrationEffect.createOneShot(durationMs, amplitude)`.
  - API < 26: use legacy `vibrate(long)` (in one place, with a single, contained suppression).
- Short, consistent durations across app (e.g., `KEYPAD_CLICK_MS = 15–20ms`).

Usage migration:
- Replace inline `vibrateKeypad()` implementations with calls to `VibrationHelper.vibrateClick(view.context)` or similar.
- Files to update:
  - `PinKeypadView`: remove `@Suppress("DEPRECATION")` and delegate to helper.
  - `KeypadManager`: same as above.
  - `TipSelectionActivity`: same as above.

Testing / verification:
- Manual testing on emulator / device with different API levels if available.
- Confirm no crash when device has no vibrator (use `hasVibrator()` guard in helper).

### B. Modernize Locale Handling in Language Settings

**Objective:** Avoid deprecated `Configuration.locale` while preserving fallback logic.

Steps:
1. Create a small locale utility (e.g., `LocaleUtils`) if not already present.
2. Implement `getSystemPrimaryLocale(context)` using:
   - API 24+: `resources.configuration.locales[0]`.
   - Pre-24: keep `resources.configuration.locale`, but encapsulated inside the utility. If necessary, a single targeted `@Suppress("DEPRECATION")` can live in this utility rather than in UI code.
3. In `LanguageSettingsActivity`:
   - Replace inline `if (Build.VERSION.SDK_INT >= 24) ... else ...` block with a call to `LocaleUtils.getSystemLanguageCode(context)`.
   - Map the returned language code into supported codes (`en` / `es`) exactly as done today.

Benefits:
- Deprecated API usage isolated to a single, well-documented fallback utility.
- Language feature logic easier to maintain and reason about.

### C. Migrate Activity Results to Activity Result APIs

**Objective:** Remove deprecated `onActivityResult` overrides and switch to modern, lifecycle-aware result APIs.

#### C1. PinEntryActivity → PinResetActivity

Current behavior:
- Launches `PinResetActivity` via `startActivityForResult`.
- On `RESULT_OK`, treat PIN as verified and finish with `RESULT_PIN_VERIFIED`.

Plan:
1. Add a `private val pinResetLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ... }` at the top of `PinEntryActivity`.
2. In `openPinReset()`, replace `startActivityForResult(intent, REQUEST_PIN_RESET)` with `pinResetLauncher.launch(intent)`.
3. In the callback, implement current logic:
   - If `result.resultCode == Activity.RESULT_OK` → set result `RESULT_PIN_VERIFIED` and finish.
4. Remove `onActivityResult` override and the `@Deprecated` annotation.
5. Keep request-code constants only where still needed (or remove if unused).

#### C2. SettingsActivity → PinEntryActivity (PIN verification)

Current behavior:
- Uses `startActivityForResult` with `REQUEST_PIN_VERIFY`.
- `onActivityResult` opens the pending destination on successful PIN verification.

Plan:
1. Add `private val pinVerifyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ... }`.
2. In `openProtectedActivity(destination)`, replace `startActivityForResult(intent, REQUEST_PIN_VERIFY)` with `pinVerifyLauncher.launch(intent)`.
3. Inside the launcher callback:
   - If `result.resultCode == Activity.RESULT_OK` → `PinProtectionHelper.markVerified()` and start `pendingDestination`.
4. Remove `onActivityResult` override and `@Deprecated`.
5. Ensure no external callers rely on `REQUEST_PIN_VERIFY` (remove if unused).

#### C3. TipSelectionActivity → PaymentRequestActivity

Current behavior:
- Uses `startActivityForResult` + `onActivityResult` to propagate payment result back.

Plan:
1. Add `private val paymentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ... }`.
2. Replace `startActivityForResult(intent, REQUEST_CODE_PAYMENT)` with `paymentLauncher.launch(intent)` in `proceedToPayment()`.
3. In callback, mirror existing logic:
   - `setResult(result.resultCode, result.data)`.
   - `finish()` and keep transition animations.
4. Remove `onActivityResult` override and `@Deprecated`.

Testing:
- Verify all flows still return the same results as before:
  - Opening protected settings and cancelling PIN.
  - Completing PIN reset and then returning.
  - Completing / cancelling payment from tip screen.

### D. Migrate Back Handling to OnBackPressedDispatcher

**Objective:** Use modern back handling and keep nuanced behavior intact.

General pattern per Activity:
1. Remove `@Deprecated override fun onBackPressed()`.
2. In `onCreate`, add:

   ```kotlin
   onBackPressedDispatcher.addCallback(this) {
       // existing onBackPressed logic goes here
   }
   ```

3. For flows that sometimes want to delegate to "default" behavior (super.onBackPressed), decide explicitly:
   - Either call `activity.finish()` or
   - Disable the callback (`isEnabled = false`) and then call `onBackPressedDispatcher.onBackPressed()` once, if you truly want default system behavior.

Screen-by-screen notes:

- **PinEntryActivity**
  - Current behavior: respects `EXTRA_ALLOW_BACK`, otherwise blocks back.
  - Plan: In callback, if allowBack → set result and `finish()`, else ignore (no-op back).

- **RestoreWalletActivity**
  - Current behavior: delegates to `handleBackPress()` which behaves like a small state machine (ENTER_SEED → finish, REVIEW_MINTS → ENTER_SEED, others: no back).
  - Plan: Move `handleBackPress()` call into dispatcher callback; no need for `super.onBackPressed()`.

- **OnboardingActivity**
  - Current behavior: state-based back (WELCOME → finish, CHOOSE_PATH ↔ WELCOME, ENTER_SEED ↔ CHOOSE_PATH, etc.), no back in loading/success states.
  - Plan: Add a single dispatcher callback that does the `when (currentStep)` logic.

- **ItemListActivity**
  - Current behavior: back exits reordering mode first, then finishes activity.
  - Plan: Back callback replicates same logic, calling `finish()` when not in reordering mode.

- **ItemSelectionActivity**
  - Current behavior: complex, includes basket editing state and "save/discard" dialog.
  - Plan: Use dispatcher callback to call `handleBackPress()`; drop deprecated `onBackPressed()` override.

Benefits:
- Modern, explicit back behavior tied to lifecycle.
- Easier future integration with predictive back / navigation components.

### E. Legacy Payment History API

**Objective:** Keep compatibility but make future removal path explicit.

Current state:
- Legacy overload:

  ```kotlin
  @Deprecated("Use addToHistory with full parameters")
  @JvmStatic
  fun addToHistory(context: Context, token: String, amount: Long)
  ```

Plan:
1. Leave the method in place for now to avoid breaking external callers.
2. Strengthen KDoc:
   - Mark as **legacy** and note that it will be removed in a future major version.
   - Document assumptions about unit/entryUnit (`"sat"`).
3. Search usages within repo:
   - If no internal uses, consider adding a TODO with a target version (e.g., `// TODO: remove in v2.0`).
4. Optionally: add a small wrapper in a public API facade (if one exists) to discourage direct Activity reference.

## Phasing and Risk Management

### Phase 1 – Low-Risk Refactors

- Implement `VibrationHelper` and update all keypad views.
- Extract locale logic into a helper (`LocaleUtils`), update `LanguageSettingsActivity`.
- Keep UI output and timing identical.

### Phase 2 – Activity Results

- Migrate `PinEntryActivity`, `SettingsActivity`, and `TipSelectionActivity` to Activity Result APIs.
- Regression tests:
  - PIN reset flow (success and cancel).
  - Entering mints/withdrawals/settings behind PIN.
  - Tip → payment → back stack.

### Phase 3 – Back Handling

- For each Activity with custom back logic, move to `OnBackPressedDispatcher`.
- Verify navigation and animations still match existing UX.

### Phase 4 – Cleanup and Verification

- Run:

  ```bash
  ./gradlew clean build --warning-mode=all
  ```

- Confirm:
  - No remaining compiler-level deprecation warnings from our code paths.
  - Any remaining warnings come only from third-party deps or intentional, centralized fallbacks.

- Update this document if new deprecations surface or if some deprecations must remain (e.g., unavoidable pre-24 locale API in a single utility).

## Acceptance Criteria

- [ ] `./gradlew build --warning-mode=all` shows no deprecation warnings originating from our app code (outside of a small, documented compatibility helper or two).
- [ ] All PIN, onboarding, settings, restore, items, and tip flows behave identically from a user’s perspective.
- [ ] New helpers (`VibrationHelper`, `LocaleUtils`, etc.) are unit-testable and documented.
- [ ] No `@Suppress("DEPRECATION")` annotations remain in UI classes; any remaining suppressions are localized in well-documented compatibility utilities.
