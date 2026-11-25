package com.electricdreams.numo.ui.components

import android.app.Activity
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.offline.OfflineStripView

/**
 * Sizes the charge screen's keypad so the amount above it is never squeezed.
 *
 * The amount takes the height it needs, room is kept free for the offline strip, and the keypad
 * gets what is left, up to its usual height. Because the strip's room is set aside up front, the
 * keys stay the same size when it slides in and only the space around the amount changes.
 */
internal class PosKeypadSizer(private val activity: Activity) {

    private val content: View = activity.findViewById(android.R.id.content)
    private val keypad: View = activity.findViewById(R.id.keypad)
    private val maxHeight = activity.resources.getDimensionPixelSize(R.dimen.pos_keypad_max_height)
    private val minHeight = activity.resources.getDimensionPixelSize(R.dimen.pos_keypad_min_height)

    fun attach() {
        content.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                // Layout params can't change mid-layout
                content.post(::resize)
            }
        }
        if (content.height > 0) resize()
    }

    private fun resize() {
        if (content.width == 0 || content.height == 0) return
        // The root insets, not the content padding: that already includes the strip when shown
        val bars = ViewCompat.getRootWindowInsets(content)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        val available = content.height - (bars?.top ?: 0) - (bars?.bottom ?: 0)
        val height = fitKeypadHeight(
            available = available,
            aboveAndBelow = chromeHeight() + amountBlockHeight(),
            stripReserve = OfflineStripView.tallestContentHeight(activity, content.width),
            minHeight = minHeight,
            maxHeight = maxHeight,
        )
        if (keypad.layoutParams.height != height) {
            keypad.layoutParams = keypad.layoutParams.apply { this.height = height }
        }
    }

    /** Top bar, the gap under the keypad, and the Charge button with its margin. */
    private fun chromeHeight(): Int {
        val topBar: View = activity.findViewById(R.id.top_navigation)
        val submit: View = activity.findViewById(R.id.submit_button)
        return topBar.layoutParams.height + keypad.verticalMargins() +
            submit.layoutParams.height + submit.verticalMargins()
    }

    /** The amount at its largest text size plus the secondary amount, margins included. */
    private fun amountBlockHeight(): Int {
        val amount: TextView = activity.findViewById(R.id.amount_display)
        val secondary: View = activity.findViewById(R.id.secondary_amount_container)
        val maxTextSize = TextViewCompat.getAutoSizeMaxTextSize(amount).takeIf { it > 0 }
            ?.toFloat() ?: amount.textSize
        val metrics = TextPaint(amount.paint).apply { textSize = maxTextSize }.fontMetricsInt
        val amountHeight = metrics.bottom - metrics.top +
            amount.compoundPaddingTop + amount.compoundPaddingBottom
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        secondary.measure(unspecified, unspecified)
        return amountHeight + amount.verticalMargins() +
            secondary.measuredHeight + secondary.verticalMargins()
    }

    private fun View.verticalMargins(): Int =
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0

    companion object {
        /**
         * The keypad's height in a window with [available] pixels between the system bars:
         * what is left after everything [aboveAndBelow] it and the [stripReserve], kept
         * between [minHeight] and [maxHeight].
         */
        fun fitKeypadHeight(
            available: Int,
            aboveAndBelow: Int,
            stripReserve: Int,
            minHeight: Int,
            maxHeight: Int,
        ): Int = (available - aboveAndBelow - stripReserve).coerceIn(minHeight, maxHeight)
    }
}
