# Developer & Agent Guidelines

This repository contains the source code for the Numo Android application. These guidelines are intended for both human developers and AI agents to ensure consistency, quality, and maintainability.

## 1. Project Overview
- **Platform:** Android (Min SDK 24, Target SDK 34)
- **Language:** Kotlin (primary), Java (legacy/interop)
- **Build System:** Gradle (Kotlin DSL)
- **Architecture:** Standard Android MVC/MVVM mix
- **Key Libraries:** AndroidX, Material Components, Coroutines, Jackson/Gson, Cashu SDK (CDK), ZXing

## 2. Operational Commands

### Build
To build the debug APK:
```bash
./gradlew assembleDebug
```
Output APK location: `app/build/outputs/apk/debug/app-debug.apk`

### Testing
**Run all unit tests:**
```bash
./gradlew testDebugUnitTest
```

**Run a specific test class:**
```bash
./gradlew testDebugUnitTest --tests "com.electricdreams.numo.core.model.ItemTest"
```

**Run a single test method:**
```bash
./gradlew testDebugUnitTest --tests "com.electricdreams.numo.core.model.ItemTest.net and gross price with VAT disabled"
```
*Note: Wrap the test name in quotes, especially if it contains spaces (backtick style names).*

### Linting & Formatting
**Run Lint:**
```bash
./gradlew lintDebug
```
Check `app/lint.xml` for specific rules (e.g., ignoring specific library issues).
There is no strict auto-formatter configured (like ktlint) in the build script, so rely on the IDE standard Kotlin style.

## 3. Code Style & Conventions

### General
- **Indentation:** Use 4 spaces for indentation.
- **Line Length:** Aim for ~100 characters, but not strictly enforced if readability is preserved.
- **Imports:**
  - Use explicit imports (avoid `import com.foo.*`).
  - Group Android/Java/Kotlin imports separately from project-specific imports if possible.
  - Remove unused imports.

### Naming Conventions
- **Classes/Interfaces:** PascalCase (e.g., `PaymentRequestActivity`, `TokenHistoryAdapter`).
- **Functions/Methods:** camelCase (e.g., `handlePaymentSuccess`, `startLightningMintFlow`).
- **Variables/Properties:** camelCase (e.g., `paymentAmount`, `cashuQrImageView`).
- **Constants:** SCREAMING_SNAKE_CASE (e.g., `EXTRA_PAYMENT_AMOUNT`, `TAG`).
- **Layout Files:** snake_case (e.g., `activity_payment_request.xml`, `item_token_history.xml`).
- **IDs:** snake_case (e.g., `payment_request_qr`, `close_button`).

### Kotlin Best Practices
- **Null Safety:** Use Kotlin's nullable types (`?`) and safe calls (`?.`) or Elvis operator (`?:`) instead of explicit null checks where possible.
- **Coroutines:**
  - Use `CoroutineScope` (e.g., `lifecycleScope` or `uiScope`) for background tasks.
  - Dispatch IO operations to `Dispatchers.IO` and UI updates to `Dispatchers.Main`.
  - Use `suspend` functions for long-running operations.
- **Singleton Pattern:** Use `getInstance(context)` pattern for managers/helpers (e.g., `MintManager.getInstance(this)`).

### UI & View Binding
- **View Binding:** Enabled in `build.gradle.kts` (`viewBinding = true`).
  - **Preferred:** Use View Binding for new Activities/Fragments to avoid null pointer exceptions.
  - **Legacy:** `findViewById` is widely used in existing code (e.g., `PaymentRequestActivity`). Maintain consistency within a file (don't mix both in the same class unless refactoring).
- **Toasts:** Use `Toast` for short user feedback.

### Logging
- Use `android.util.Log`.
- Define a private const tag at the bottom of the class:
  ```kotlin
  companion object {
      private const val TAG = "ClassName"
  }
  ```
- Use `Log.d(TAG, ...)` for debug info and `Log.e(TAG, "msg", exception)` for errors.

### Error Handling
- Use `try-catch` blocks around risky operations (network, parsing, NFC).
- Log errors appropriately using `Log.e`.
- Show user-friendly error messages (Toasts or status TextViews) when an operation fails visible to the user.

## 4. Testing Guidelines

- **Framework:** JUnit 4 (`org.junit.Test`).
- **Assertions:** `org.junit.Assert` (e.g., `assertEquals`, `assertTrue`).
- **Naming:**
  - Test classes: `ClassNameTest`.
  - Test methods: Use backticks for descriptive names, e.g., `` `net and gross price with VAT disabled` ``.
- **Location:** Unit tests in `app/src/test/java/`.

## 5. File System & Paths
- **Source Code:** `app/src/main/java/com/electricdreams/numo/`
- **Resources:** `app/src/main/res/`
- **Manifest:** `app/src/main/AndroidManifest.xml`
- **Tests:** `app/src/test/java/com/electricdreams/numo/`

## 6. Project Specifics
- **Payments:** The app handles Cashu (Nostr/NFC) and Lightning payments.
- **Workers:** Background tasks (like price fetching) use `Worker` or Singleton helpers.
- **Serialization:** Uses Jackson (`ObjectMapper`) and Gson. Be consistent with the file/module you are working in.

## 7. Workflow for Agents
1.  **Analyze:** Read related files first. Check `build.gradle.kts` for dependencies.
2.  **Plan:** Propose changes. If adding a view, check `res/layout` first.
3.  **Implement:** Follow the coding style above.
4.  **Verify:** Run the specific test case related to your changes. If no test exists, consider adding one in `app/src/test`.
5.  **Build:** Ensure the app builds (`./gradlew assembleDebug`) before finishing.
