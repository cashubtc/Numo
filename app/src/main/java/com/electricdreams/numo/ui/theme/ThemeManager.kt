package com.electricdreams.numo.ui.theme

import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore

/**
 * Manages theme application and color management for activities, including
 * coordinating the POS surface background with system UI chrome (status and
 * navigation bars).
 */
class ThemeManager(
    private val activity: AppCompatActivity
) {

    /**
     * Apply the currently selected POS theme to the activity UI and the
     * surrounding system chrome (status and navigation bars).
     */
    fun applyTheme(
        amountDisplay: TextView,
        secondaryAmountDisplay: TextView,
        errorMessage: TextView,
        switchCurrencyButton: android.view.View,
        submitButton: Button
    ) {
        val prefs = PreferenceStore.app(activity)
        val theme = prefs.getString("app_theme", "green") ?: "green"
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        
        // Get the actual root ConstraintLayout from the content view
        val contentView = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val rootLayout = contentView.getChildAt(0) as? androidx.constraintlayout.widget.ConstraintLayout
        
        val isWhiteTheme = (theme == "white")

        // Resolve the primary background color for the current theme so that
        // both the POS activity root and the window background can share it.
        val backgroundColor = resolveBackgroundColor(theme)
        
        // Text color: black ONLY for white theme, white for all other themes
        val textColor = if (isWhiteTheme) {
            android.graphics.Color.parseColor("#0B1215")
        } else {
            android.graphics.Color.WHITE
        }
        
        rootLayout?.setBackgroundColor(backgroundColor)
        
        // Update all text colors
        amountDisplay.setTextColor(textColor)
        secondaryAmountDisplay.setTextColor(textColor)
        errorMessage.setTextColor(textColor)
        
        // Update currency switch icon tint
        (switchCurrencyButton as? ImageButton)?.setColorFilter(textColor)
        
        // Update top navigation icons
        val topIconColor = if (isDarkMode && !isWhiteTheme) {
            android.graphics.Color.WHITE
        } else {
            textColor
        }
        
        activity.findViewById<ImageButton>(R.id.action_catalog)?.setColorFilter(topIconColor)
        activity.findViewById<ImageButton>(R.id.action_history)?.setColorFilter(topIconColor)
        activity.findViewById<ImageButton>(R.id.action_settings)?.setColorFilter(topIconColor)
        
        // Update keypad button colors
        val keypad = activity.findViewById<android.widget.GridLayout>(R.id.keypad)
        for (i in 0 until keypad.childCount) {
            val button = keypad.getChildAt(i) as? Button
            button?.setTextColor(textColor)
        }
        
        // Update status bar and navigation bar colors so the system chrome
        // matches the currently selected POS theme (green, obsidian,
        // bitcoin orange, white). This also fixes older 3-button devices
        // where a transparent nav bar could get tinted by the OEM skin.
        val window = activity.window
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        // Update system bar icon appearance based on theme
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = isWhiteTheme
        windowInsetsController.isAppearanceLightNavigationBars = isWhiteTheme
        
        // Special button handling for different themes
        when (theme) {
            "white" -> {
                submitButton.setBackgroundResource(R.drawable.bg_button_black)
                submitButton.setTextColor(android.graphics.Color.WHITE)
            }
            "obsidian" -> {
                submitButton.setBackgroundResource(R.drawable.bg_button_white)
                submitButton.setTextColor(activity.resources.getColorStateList(R.color.button_text_obsidian, null))
            }
            else -> {
                submitButton.setBackgroundResource(R.drawable.bg_button_charge)
                submitButton.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    companion object {
        private const val KEY_DARK_MODE = "darkMode"

        /**
         * Resolve the background color used by the POS UI based on the saved
         * theme identifier. This keeps ModernPOSActivity's window background
         * in sync with the same color used by ThemeManager for the root view.
         */
        fun resolveBackgroundColor(activity: AppCompatActivity): Int {
            val prefs = PreferenceStore.app(activity)
            val theme = prefs.getString("app_theme", "green") ?: "green"
            return resolveBackgroundColor(theme)
        }

        private fun resolveBackgroundColor(theme: String): Int {
            return when (theme) {
                "obsidian" -> android.graphics.Color.parseColor("#0B1215")
                "bitcoin_orange" -> android.graphics.Color.parseColor("#F7931A")
                "green" -> android.graphics.Color.parseColor("#00C244")
                "white" -> android.graphics.Color.parseColor("#FFFFFF")
                else -> android.graphics.Color.parseColor("#0B1215")
            }
        }
    }
}
