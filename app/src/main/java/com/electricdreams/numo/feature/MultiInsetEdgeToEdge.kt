package com.electricdreams.numo.feature

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.R

/**
 * Helper to enable edge‑to‑edge layouts with sane insets handling so the
 * gesture navigation pill (or legacy 3‑button bar) appears to float above
 * the app content.
 *
 * In addition to applying insets, this helper now ensures that the
 * navigation bar background and icon style follow the current light/dark
 * appearance. This fixes older 3‑button navigation devices where the
 * system bar could previously appear as an always‑white strip that did
 * not match the POS dark theme.
 *
 * Usage from an Activity:
 *
 *     enableEdgeToEdgeWithPill(this)
 */
fun enableEdgeToEdgeWithPill(activity: Activity, lightNavIcons: Boolean = true) {
    val window = activity.window

    // Let our layout draw edge‑to‑edge under the status and navigation bars.
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // Determine whether the app is currently in dark mode based on the
    // current UI mode. This aligns with AppCompat's night mode so both
    // modern gesture and legacy 3‑button nav devices behave consistently.
    val isDarkMode = when (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        else -> false
    }

    // Resolve a background color that matches our surface/background
    // colors for the current mode so the nav bar does not appear as a
    // hard‑coded white strip on old devices.
    @ColorInt
    val navBarColor = resolveNavigationBarColor(activity, isDarkMode)

    // Status bar remains transparent so content can draw behind it; the
    // navigation bar uses a themed background color to avoid OEM/wallpaper
    // bleed‑through on legacy devices.
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = navBarColor

    val controller = WindowInsetsControllerCompat(window, window.decorView)
    // Status bar icons: callers still control "light" icons via the
    // original flag, but we automatically disable light icons in dark
    // mode so they remain legible.
    controller.isAppearanceLightStatusBars = lightNavIcons && !isDarkMode
    // Navigation bar icons: follow the actual background/lightness
    // instead of being forced to light‑on‑white.
    controller.isAppearanceLightNavigationBars = !isDarkMode

    val content = activity.findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

        // Only apply top padding so content can visually extend behind the
        // gesture nav pill. Individual scroll views / lists can add their own
        // bottom padding if they need "safe" space for the last items.
        v.setPadding(0, systemBars.top, 0, 0)
        insets
    }
}

@ColorInt
private fun resolveNavigationBarColor(context: Context, isDarkMode: Boolean): Int {
    return if (isDarkMode) {
        // In dark mode we want the nav bar to blend with our dark surface
        // background. color_bg_white is overridden in values‑night to a
        // deep obsidian tone, so we reuse it here.
        ContextCompat.getColor(context, R.color.color_bg_white)
    } else {
        // Light mode: keep using the existing dedicated navigation bar
        // color so OEM tints do not leak through.
        ContextCompat.getColor(context, R.color.navigationBarBackground)
    }
}
