# Numo Developer & Agent Guidelines

This repository contains the source code for the Numo Android application. These guidelines serve as the definitive guide for both human developers and autonomous AI coding agents to ensure consistency, quality, and maintainability across the codebase.

## 1. Project Overview & Tech Stack
- **Platform:** Android (Min SDK 24, Target SDK 34, Java 17).
- **Language:** Kotlin (primary), Java (legacy/interop).
- **Architecture:** MVVM/MVC mix utilizing Android Navigation Components and ViewBinding. No Jetpack Compose.
- **Networking & Data:** OkHttp3, Jackson (JSON/CBOR), Gson.
- **Domain Specifics:** Cashu SDK (`org.cashudevkit:cdk-kotlin`), custom Cashu Java SDK, BouncyCastle for crypto.
- **Hardware/UI Features:** CameraX + ML Kit (Barcode Scanning), ZXing (QR Generation), NFC APIs.

## 2. Build & Execution Commands

### Building the App
```bash
# Build the standard Debug APK
./gradlew assembleDebug

# Build the Release APK (requires signing configs if set up)
./gradlew assembleRelease
```
*Output APK location: `app/build/outputs/apk/debug/app-debug.apk`*

### Linting
```bash
# Run Android Lint checks (see app/lint.xml for exclusions)
./gradlew lintDebug
```
*Note: There is no strict auto-formatter configured (like ktlint or spotless) in the build script. Follow standard IDE formatting.*

## 3. Testing Commands & Guidelines

Numo relies heavily on unit testing, specifically leveraging Robolectric for Android framework mocking and Mockito-Kotlin for dependency mocking.

### Running Tests
**Run all unit tests:**
```bash
./gradlew testDebugUnitTest
```

**Run all tests in a specific class:**
```bash
./gradlew testDebugUnitTest --tests "com.electricdreams.numo.core.model.ItemTest"
```

**Run a single, specific test method (CRITICAL FOR AGENTS):**
Use quotes around the class and method name. If the method uses Kotlin backticks for spaces, include the spaces exactly as they appear in the code (without the backticks in the CLI command).
```bash
./gradlew testDebugUnitTest --tests "com.electricdreams.numo.core.model.ItemTest.net and gross price with VAT disabled"
```

### Writing Tests
- **Framework:** JUnit 4 (`@Test`, `@Before`).
- **Mocking:** Use Mockito (`org.mockito.kotlin.mock`, `whenever`, `verify`). Avoid `ByteBuddy` conflicts by keeping versions aligned.
- **Android APIs:** Annotate test classes with `@RunWith(RobolectricTestRunner::class)` when accessing Android specific classes (`Context`, `Intent`).
- **Coroutines:** Use `kotlinx-coroutines-test` (`runTest`).
- **Network:** Use `MockWebServer` for intercepting OkHttp calls.
- **Naming:** Use backticks for descriptive, human-readable test names in Kotlin:
  ```kotlin
  @Test
  fun `given zero balance, when mint requested, then show error`() = runTest { ... }
  ```
- **Location:** Unit tests in `app/src/test/java/com/electricdreams/numo/`.

## 4. Code Style & Formatting

- **Indentation:** 4 spaces (never tabs).
- **Line Length:** ~100 characters max, optimize for readability.
- **Imports:**
  - Use explicit imports (avoid `import com.foo.*`).
  - Group Android/Java/Kotlin imports separately from project-specific imports if possible.
  - Remove unused imports before committing.

### Naming Conventions
- **Classes/Interfaces/Objects:** `PascalCase` (e.g., `PaymentRequestActivity`).
- **Functions/Properties:** `camelCase` (e.g., `calculateTotalBalance`, `mintManager`).
- **Constants:** `SCREAMING_SNAKE_CASE` (e.g., `EXTRA_INVOICE_ID`).
- **Layouts/Resources:** `snake_case` (e.g., `activity_main.xml`, `ic_payment_success.xml`).
- **IDs:** `snake_case` (e.g., `btn_confirm_payment`).

## 5. Architecture & Best Practices

### UI & View Binding
- **ViewBinding:** Enabled (`viewBinding = true`).
  - **Preferred:** Always use ViewBinding for new Activities and Fragments to avoid null pointer exceptions.
  - **Legacy:** `findViewById` is used in some legacy code. Do not mix both in the same class unless refactoring.
- **Navigation:** Use the Navigation Component (`findNavController()`) for transitions between Fragments.

### Kotlin Idioms & Coroutines
- **Null Safety:** Avoid the double-bang operator (`!!`) at all costs. Use safe calls (`?.`), Elvis (`?:`), or explicit null checks.
- **Coroutines:** Never use `GlobalScope`.
  - In Activities: `lifecycleScope.launch { ... }`
  - In Fragments: `viewLifecycleOwner.lifecycleScope.launch { ... }`
  - For ViewModels (if used): `viewModelScope.launch { ... }`
- **Dispatchers:** Use `Dispatchers.IO` for disk reads, network requests, and heavy crypto. Shift to `Dispatchers.Main` for UI updates.
- **Singleton Pattern:** Use `getInstance(context)` pattern for managers/helpers if not using dependency injection (e.g., `MintManager.getInstance(this)`).

### Error Handling & Logging
- **Try/Catch:** Wrap risky operations (networking, JSON parsing, Cashu CDK calls, NFC APIs).
- **Avoid Swallowing Exceptions:** At minimum, log the exception.
- **Logging:** Use `android.util.Log` with a class-specific `TAG` defined in a companion object at the bottom of the class.
  ```kotlin
  companion object {
      private const val TAG = "MintService"
  }
  ```
  Use `Log.d(TAG, ...)` for debug info and `Log.e(TAG, "msg", exception)` for errors.
- **User Feedback:** Use Android `Toast` or `Snackbar` for actionable user errors visible on the UI.

## 6. File System & Paths
- **Source Code:** `app/src/main/java/com/electricdreams/numo/`
- **Resources:** `app/src/main/res/`
- **Manifest:** `app/src/main/AndroidManifest.xml`
- **Tests:** `app/src/test/java/com/electricdreams/numo/`

## 7. Workflow & Operational Directives for Agents

1. **Information Gathering First:** NEVER assume a class name, layout ID, or project structure. Use `glob` to find files and `grep` to find specific usages or definitions before writing code.
2. **Absolute Paths:** When using the `read` or `write` tools, always construct full absolute paths using the workspace root (e.g., `/home/lollerfirst/AndroidStudioProjects/numo/app/src/main/java/...`).
3. **Verify Dependencies:** Before adding a new library, verify if an existing one (e.g., Jackson vs Gson) already solves the problem. Check `app/build.gradle.kts`.
4. **Self-Correction Loop:** If you modify logic, immediately run the corresponding unit test. If a test doesn't exist, create a fast, localized unit test before verifying manually or via UI.
5. **Build Before Finish:** Ensure the app builds (`./gradlew assembleDebug`) before declaring a task complete.
6. **No Hallucinated Tooling:** Do not attempt to run `ktlint`, `detekt`, or `spotless` unless explicitly configured in the project. Rely on `./gradlew lintDebug` and compilation checks.