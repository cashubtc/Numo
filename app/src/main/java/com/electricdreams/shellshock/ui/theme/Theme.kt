package com.electricdreams.shellshock.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = CashGreen,
    onPrimary = White,
    primaryContainer = LightCashGreen,
    onPrimaryContainer = Gray900,
    secondary = AccentCyan,
    onSecondary = White,
    tertiary = AccentBlue,
    background = White,
    onBackground = Gray900,
    surface = White,
    onSurface = Gray900,
    surfaceVariant = Gray100,
    onSurfaceVariant = Gray700,
    error = ErrorRed,
    outline = Gray200
)

private val DarkColorScheme = darkColorScheme(
    primary = CashGreen,
    onPrimary = White,
    primaryContainer = CashGreen,
    onPrimaryContainer = White,
    secondary = AccentCyan,
    onSecondary = White,
    background = Gray900,
    onBackground = White,
    surface = Gray900,
    onSurface = White,
    surfaceVariant = Gray700,
    onSurfaceVariant = Gray200,
    error = ErrorRed,
    outline = Gray700
)

@Composable
fun CashAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Disable dynamic color to stick to brand
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb() // Cash App often has colored status bar on main screen
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CashAppTypography,
        content = content
    )
}