package com.electricdreams.numo.feature

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Small helper to enable edge‑to‑edge with sane insets handling so the
 * gesture navigation pill truly floats above content across the app.
 *
 * Usage from an Activity:
 *
 *     enableEdgeToEdgeWithPill(this)
 */
fun enableEdgeToEdgeWithPill(activity: Activity, lightNavIcons: Boolean = true) {
    val window = activity.window

    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT

    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.isAppearanceLightStatusBars = lightNavIcons
    controller.isAppearanceLightNavigationBars = lightNavIcons

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
