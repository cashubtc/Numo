package com.electricdreams.numo.util

import android.view.Window
import android.view.WindowManager

/**
 * Extension to safely set SOFT_INPUT_ADJUST_RESIZE on a window,
 * hiding the deprecation warning.
 */
@Suppress("DEPRECATION")
fun Window.setSoftInputModeResize() {
    this.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
}
