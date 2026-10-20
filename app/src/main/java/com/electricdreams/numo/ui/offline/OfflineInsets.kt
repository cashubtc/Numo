package com.electricdreams.numo.ui.offline

import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

/**
 * Reports a status bar that is [extraTop] px taller than the real one.
 *
 * Every screen already pads itself for the status bar, so inflating it is how the offline strip
 * pushes content down without any screen knowing the strip exists.
 */
internal fun WindowInsetsCompat.withExtraStatusBarTop(extraTop: Int): WindowInsetsCompat {
    if (extraTop <= 0) return this
    val type = WindowInsetsCompat.Type.statusBars()
    val bars = getInsets(type)
    val stable = getInsetsIgnoringVisibility(type)
    return WindowInsetsCompat.Builder(this)
        .setInsets(type, Insets.of(bars.left, bars.top + extraTop, bars.right, bars.bottom))
        .setInsetsIgnoringVisibility(
            type,
            Insets.of(stable.left, stable.top + extraTop, stable.right, stable.bottom)
        )
        .build()
}
