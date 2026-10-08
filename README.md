# PR #390 emulator screenshots

UI source: `be82cd75831b3db845fad7ecc1559a49098219b2` ([PR #390](https://github.com/cashubtc/Numo/pull/390)).

Captured on a Pixel 5 Android 14 (API 34) emulator at 1080 × 2340. A temporary instrumentation fixture supplies update states, version 1.10, and sample release notes to the production UI. The fixture asserts that the expected app or Android settings window is visible before capture. The app's install-permission dialog and the Android permission screen are opened through the existing button handlers.

The screenshot instrumentation passed; `assembleDebug` and `assembleDebugAndroidTest` succeeded. Live update installation and eligible Google Play test-track completion were not exercised.

This branch hosts documentation images independently of the application branch.
