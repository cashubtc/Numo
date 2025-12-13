# Navigation & Status Bar Theming Changes

## Goal

Ensure consistent system chrome behavior across the app:

- **ModernPOSActivity (main POS screen):**
  - Status bar and bottom system navigation bar follow the **selected app theme** (green, obsidian, bitcoin orange, white).
- **All other screens:**
  - Status and navigation bars follow the **system light/dark mode** (white in light mode, dark in dark mode), independent of the POS theme selection.

This also fixes the issue where, on some older devices with the legacy 3‑button navigation bar, the nav bar stayed "Cash App green" even when a different theme was selected.

---

## Summary of Changes

1. **Base themes (`Theme.Numo`)**
   - Change default navigation bar color from transparent to solid white/dark so non‑POS screens follow light/dark mode.

2. **ModernPOSActivity**
   - Stop forcing transparent system bars in `setupWindowSettings()`.
   - Keep only edge‑to‑edge and inset handling there.

3. **ThemeManager** (POS theme application)
   - Make `applyTheme` set **both** status bar and navigation bar to the POS theme background color.
   - Keep system bar icon light/dark behavior based on the POS theme (white vs non‑white).

---

## 1. Base Theme: Navigation Bar Follows Light/Dark

### 1.1 Light Mode Theme (`values/themes.xml`)

**File:** `app/src/main/res/values/themes.xml`

In the base theme (`style name="Theme.Numo"`), update the navigation bar configuration.

#### Before

```xml
<!-- Base Theme (Light Mode) -->
<style name="Theme.Numo" parent="Theme.MaterialComponents.DayNight.NoActionBar">
    <!-- Primary brand color. -->
    <item name="colorPrimary">@color/color_primary_green_light</item>
    <item name="colorPrimaryVariant">@color/color_primary_green</item>
    <item name="colorOnPrimary">@color/color_bg_white</item>
    <!-- Secondary brand color. -->
    <item name="colorSecondary">@color/color_primary_green_light</item>
    <item name="colorSecondaryVariant">@color/color_primary_green</item>
    <item name="colorOnSecondary">@color/color_bg_white</item>
    <!-- Status bar color - matches window background for seamless edge-to-edge -->
    <item name="android:statusBarColor">@color/color_bg_white</item>
    <item name="android:windowLightStatusBar">true</item>
    <!-- Navigation bar: transparent so gesture pill truly floats over content/background -->
    <!-- Content views should handle bottom insets where needed. -->
    <item name="android:navigationBarColor">@android:color/transparent</item>
    <item name="android:windowLightNavigationBar">true</item>
    <!-- Text colors -->
    <item name="android:textColor">@color/color_text_primary</item>
    <!-- Background colors -->
    <item name="android:windowBackground">@color/color_bg_white</item>
    <item name="android:colorBackground">@color/color_bg_white</item>
    <!-- Surface colors -->
    <item name="colorSurface">@color/color_bg_white</item>
    <item name="colorOnSurface">@color/color_text_primary</item>
    <!-- Icon tint -->
    <item name="colorControlNormal">@color/color_text_secondary</item>
</style>
```

#### After

```xml
<!-- Base Theme (Light Mode) -->
<style name="Theme.Numo" parent="Theme.MaterialComponents.DayNight.NoActionBar">
    <!-- Primary brand color. -->
    <item name="colorPrimary">@color/color_primary_green_light</item>
    <item name="colorPrimaryVariant">@color/color_primary_green</item>
    <item name="colorOnPrimary">@color/color_bg_white</item>
    <!-- Secondary brand color. -->
    <item name="colorSecondary">@color/color_primary_green_light</item>
    <item name="colorSecondaryVariant">@color/color_primary_green</item>
    <item name="colorOnSecondary">@color/color_bg_white</item>
    <!-- Status bar color - matches window background for seamless edge-to-edge -->
    <item name="android:statusBarColor">@color/color_bg_white</item>
    <item name="android:windowLightStatusBar">true</item>
    <!-- Navigation bar: solid white so it follows the light theme
         everywhere except the POS screen (which overrides it to use
         the per‑theme background color). -->
    <item name="android:navigationBarColor">@color/color_bg_white</item>
    <item name="android:windowLightNavigationBar">true</item>
    <!-- Text colors -->
    <item name="android:textColor">@color/color_text_primary</item>
    <!-- Background colors -->
    <item name="android:windowBackground">@color/color_bg_white</item>
    <item name="android:colorBackground">@color/color_bg_white</item>
    <!-- Surface colors -->
    <item name="colorSurface">@color/color_bg_white</item>
    <item name="colorOnSurface">@color/color_text_primary</item>
    <!-- Icon tint -->
    <item name="colorControlNormal">@color/color_text_secondary</item>
</style>
```

### 1.2 Dark Mode Theme (`values-night/themes.xml`)

**File:** `app/src/main/res/values-night/themes.xml`

In the dark base theme, likewise switch to a solid dark nav bar.

#### Before

```xml
<!-- Base Theme (Dark Mode) -->
<style name="Theme.Numo" parent="Theme.MaterialComponents.DayNight.NoActionBar">
    <!-- Primary brand color. -->
    <item name="colorPrimary">#FFFFFF</item>
    <item name="colorPrimaryVariant">@color/dark_colorPrimaryDark</item>
    <item name="colorOnPrimary">#000000</item>
    <!-- Secondary brand color. -->
    <item name="colorSecondary">#FFFFFF</item>
    <item name="colorSecondaryVariant">#FFFFFF</item>
    <item name="colorOnSecondary">#000000</item>
    <!-- Status bar color - matches window background for seamless edge-to-edge -->
    <item name="android:statusBarColor">@color/dark_windowBackground</item>
    <item name="android:windowLightStatusBar">false</item>
    <!-- Navigation bar: transparent so gesture pill truly floats over content/background -->
    <!-- Content views should handle bottom insets where needed. -->
    <item name="android:navigationBarColor">@android:color/transparent</item>
    <item name="android:windowLightNavigationBar">false</item>
    <!-- Text colors -->
    <item name="android:textColor">@color/dark_textColorPrimary</item>
    <!-- Background colors -->
    <item name="android:windowBackground">@color/dark_windowBackground</item>
    <item name="android:colorBackground">@color/dark_windowBackground</item>
    <!-- Surface colors -->
    <item name="colorSurface">@color/dark_surfaceColor</item>
    <item name="colorOnSurface">@color/dark_textColorPrimary</item>
    <!-- Custom attributes -->
    <item name="keypadButtonColor">@color/dark_keypadButtonColor</item>
    <item name="keypadButtonTextColor">@color/dark_keypadButtonTextColor</item>
    <item name="keypadButtonPressedColor">@color/dark_keypadButtonPressedColor</item>
    <item name="inputBoxStrokeColor">@color/dark_inputBoxStrokeColor</item>
    <!-- Toolbar theme -->
    <item name="toolbarStyle">@style/Widget.App.Toolbar.Dark</item>
    <!-- Icon tint -->
    <item name="colorControlNormal">@color/dark_iconTint</item>
    <!-- Switch colors -->
    <item name="colorControlActivated">#FFFFFF</item>
</style>
```

#### After

```xml
<!-- Base Theme (Dark Mode) -->
<style name="Theme.Numo" parent="Theme.MaterialComponents.DayNight.NoActionBar">
    <!-- Primary brand color. -->
    <item name="colorPrimary">#FFFFFF</item>
    <item name="colorPrimaryVariant">@color/dark_colorPrimaryDark</item>
    <item name="colorOnPrimary">#000000</item>
    <!-- Secondary brand color. -->
    <item name="colorSecondary">#FFFFFF</item>
    <item name="colorSecondaryVariant">#FFFFFF</item>
    <item name="colorOnSecondary">#000000</item>
    <!-- Status bar color - matches window background for seamless edge-to-edge -->
    <item name="android:statusBarColor">@color/dark_windowBackground</item>
    <item name="android:windowLightStatusBar">false</item>
    <!-- Navigation bar: solid dark so it follows the dark theme
         everywhere except the POS screen (which overrides it to use
         the per‑theme background color). -->
    <item name="android:navigationBarColor">@color/dark_windowBackground</item>
    <item name="android:windowLightNavigationBar">false</item>
    <!-- Text colors -->
    <item name="android:textColor">@color/dark_textColorPrimary</item>
    <!-- Background colors -->
    <item name="android:windowBackground">@color/dark_windowBackground</item>
    <item name="android:colorBackground">@color/dark_windowBackground</item>
    <!-- Surface colors -->
    <item name="colorSurface">@color/dark_surfaceColor</item>
    <item name="colorOnSurface">@color/dark_textColorPrimary</item>
    <!-- Custom attributes -->
    <item name="keypadButtonColor">@color/dark_keypadButtonColor</item>
    <item name="keypadButtonTextColor">@color/dark_keypadButtonTextColor</item>
    <item name="keypadButtonPressedColor">@color/dark_keypadButtonPressedColor</item>
    <item name="inputBoxStrokeColor">@color/dark_inputBoxStrokeColor</item>
    <!-- Toolbar theme -->
    <item name="toolbarStyle">@style/Widget.App.Toolbar.Dark</item>
    <!-- Icon tint -->
    <item name="colorControlNormal">@color/dark_iconTint</item>
    <!-- Switch colors -->
    <item name="colorControlActivated">#FFFFFF</item>
</style>
```

Result: all Activities that rely only on `Theme.Numo` will now have a solid white (light) or dark (night) nav bar, instead of a transparent one that OEMs might tint green.

---

## 2. ModernPOSActivity: System Bars Follow App Theme

### 2.1 Delegate system bar colors to ThemeManager

**File:** `app/src/main/java/com/electricdreams/numo/ui/theme/ThemeManager.kt`

`ThemeManager.applyTheme` is already responsible for applying the POS theme to the main screen. We extend it to set both the status bar and navigation bar to the POS theme background color.

#### Before

```kotlin
// Update status bar and navigation bar colors
activity.window.statusBarColor = backgroundColor

// Let the system navigation bar be fully transparent so the gesture pill
// floats above whatever content/background we're drawing, instead of
// sitting on a solid-colored nav bar.
activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT

// Update status bar appearance based on theme
val windowInsetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
windowInsetsController.isAppearanceLightStatusBars = isWhiteTheme
windowInsetsController.isAppearanceLightNavigationBars = isWhiteTheme
```

#### After

```kotlin
// Update status bar and navigation bar colors so the system chrome
// matches the currently selected POS theme (green, obsidian,
// bitcoin orange, white). This also fixes older 3‑button devices
// where a transparent nav bar could get tinted green by the OEM skin.
val window = activity.window
window.statusBarColor = backgroundColor
window.navigationBarColor = backgroundColor

// Update system bar icon appearance based on theme
val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
windowInsetsController.isAppearanceLightStatusBars = isWhiteTheme
windowInsetsController.isAppearanceLightNavigationBars = isWhiteTheme
```

Notes:

- `backgroundColor` already comes from the saved POS theme (`green`, `obsidian`, `bitcoin_orange`, `white`).
- `isWhiteTheme` is `true` only for the white theme, so only the white theme uses dark status/nav icons; other themes keep light icons.

### 2.2 ModernPOSActivity: only handle edge‑to‑edge and insets

**File:** `app/src/main/java/com/electricdreams/numo/ModernPOSActivity.kt`

`ModernPOSActivity.setupWindowSettings()` currently forces transparent system bars. We want ThemeManager to own the colors, and this method to only handle edge‑to‑edge layout and insets.

#### Before

```kotlin
private fun setupWindowSettings() {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // Use the same resolved background color as ThemeManager so the
    // ModernPOS window background matches the active theme selection
    // (obsidian, green, bitcoin orange, white, etc.). This ensures the
    // navigation pill always floats above the correct themed background
    // instead of a hardcoded "green".
    val bgColor = com.electricdreams.numo.ui.theme.ThemeManager.resolveBackgroundColor(this)
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))

    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT

    WindowInsetsControllerCompat(window, window.decorView).apply {
        // For the dark themes we keep icons light; for white theme the
        // ThemeManager will already have set light/dark appropriately.
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        // Apply top inset so content sits below the status bar and bottom inset so
        // the charge button is never obscured by the system navigation bar or gesture pill.
        v.setPadding(0, insets.top, 0, insets.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
```

#### After

```kotlin
private fun setupWindowSettings() {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // Use the same resolved background color as ThemeManager so the
    // ModernPOS window background matches the active theme selection
    // (obsidian, green, bitcoin orange, white, etc.). This keeps the
    // window background in sync with the POS theme while letting
    // ThemeManager control the actual system bar colors.
    val bgColor = com.electricdreams.numo.ui.theme.ThemeManager.resolveBackgroundColor(this)
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        // Apply top inset so content sits below the status bar and bottom inset so
        // the charge button is never obscured by the system navigation bar or gesture pill.
        v.setPadding(0, insets.top, 0, insets.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
```

Key points:

- We no longer set `window.statusBarColor` or `window.navigationBarColor` here.
- We no longer override icon light/dark here.
- ThemeManager (called from `PosUiCoordinator`) becomes the single source of truth for POS system bar colors.

---

## 3. Behavior After Changes

- **ModernPOSActivity (main POS screen):**
  - Status bar and bottom navigation bar background = POS theme background color (green, obsidian, bitcoin orange, or white).
  - Icons (light/dark) are controlled by ThemeManager based on whether the POS theme is white or not.

- **All other screens (Settings, History, Tips, Onboarding, etc.):**
  - Status and navigation bars use the base theme configuration:
    - White in light mode (`values/themes.xml`).
    - Dark in dark mode (`values-night/themes.xml`).
  - These screens do not depend on the POS theme selection.

- **Old devices with legacy 3‑button navigation bar:**
  - No longer show a permanently green nav bar.
  - For the main POS screen, the nav bar now matches the selected POS theme.
  - For other screens, the nav bar matches the OS light/dark theme.

---

## 4. Testing Checklist

1. **Build:**
   - Run `./gradlew :app:assembleDebug` (or build from Android Studio) to confirm everything compiles.

2. **On a legacy 3‑button navigation device:**
   - Open the main POS screen.
   - Switch POS theme between green, obsidian, bitcoin orange, and white.
   - Confirm the bottom nav bar and status bar backgrounds match the POS theme.
   - Navigate to Settings, Items, History, etc.
   - Confirm the nav bar is white (light mode) or dark (dark mode), independent of POS theme.

3. **On a gesture navigation device (Android 10+):**
   - Verify that system bars still look visually correct across themes and dark/light mode.
   - Confirm icons remain legible (dark icons only on white theme, light icons on colored/dark themes).

---

## 5. Documentation Notes

- `ThemeManager` is now the single place where POS system bar colors are set. Any future POS theme variants should be added via `resolveBackgroundColor(theme)` and the `isWhiteTheme` logic.
- `ModernPOSActivity.setupWindowSettings()` is responsible only for:
  - Enabling edge‑to‑edge via `WindowCompat.setDecorFitsSystemWindows(window, false)`.
  - Applying padding based on `WindowInsetsCompat.Type.systemBars()` so content is not obscured.
- Base theme definitions (`Theme.Numo` in `values` and `values-night`) control the default behavior for all other Activities.
