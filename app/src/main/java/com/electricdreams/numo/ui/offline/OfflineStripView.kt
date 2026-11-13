package com.electricdreams.numo.ui.offline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.updatePadding
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ViewOfflineStripBinding
import kotlin.math.ceil

/**
 * The dark "Offline ⓘ" layer that sits behind the status bar and above the app.
 *
 * Below the strip it draws two concave fillets, so whatever screen is underneath reads as a card
 * with rounded top corners without that screen having to clip itself.
 */
@SuppressLint("ViewConstructor")
class OfflineStripView(context: Context) : FrameLayout(context) {

    enum class Mode { OFFLINE, BACK_ONLINE }

    private val binding = ViewOfflineStripBinding.inflate(LayoutInflater.from(context), this)
    private val cornerRadius = resources.getDimension(R.dimen.offline_strip_corner_radius)
    private val basePaddingTop = binding.stripContent.paddingTop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.color_offline_strip)
    }
    private val filletPath = Path()
    private var statusBarInset = 0
    private var onStripClick: (() -> Unit)? = null

    var mode: Mode = Mode.OFFLINE
        private set

    /** Height of the strip below the status bar: how far the app content is pushed down. */
    var contentHeight = 0
        private set

    /** 0 = tucked under the status bar, 1 = fully revealed. */
    var reveal = 0f
        set(value) {
            field = value
            translationY = -contentHeight * (1f - value)
            alpha = (value / FADE_PORTION).coerceAtMost(1f)
            invalidate()
        }

    init {
        setWillNotDraw(false)
        // Room below the strip for the fillets; touches there fall through to the app.
        setPadding(0, 0, 0, ceil(cornerRadius).toInt())
        binding.stripContent.setOnClickListener { onStripClick?.invoke() }
        applyMode(Mode.OFFLINE)
    }

    fun setOnStripClickListener(listener: () -> Unit) {
        onStripClick = listener
    }

    /**
     * The real (un-inflated) status bar height, fed by the host. The strip doesn't listen for
     * insets itself: below API 30 siblings receive whatever the screen's own handling returns.
     */
    fun setStatusBarInset(top: Int) {
        if (top == statusBarInset) return
        statusBarInset = top
        binding.stripContent.updatePadding(top = basePaddingTop + top)
    }

    /** Lets TalkBack read the strip before the screen under it, matching what's seen first. */
    fun readBefore(viewId: Int) {
        binding.stripContent.accessibilityTraversalBefore = viewId
    }

    /** Measures the strip at [width] and caches [contentHeight]. Safe to call before layout. */
    fun measureContentHeight(width: Int): Int {
        binding.stripContent.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        contentHeight = binding.stripContent.measuredHeight - statusBarInset
        return contentHeight
    }

    /** [onApplied] runs once the new text is in place, so the strip can be re-measured. */
    fun setMode(newMode: Mode, animate: Boolean, onApplied: () -> Unit = {}) {
        if (newMode == mode) return
        mode = newMode
        val text = binding.stripText
        text.animate().cancel()
        if (!animate) {
            text.alpha = 1f
            applyMode(newMode)
            onApplied()
            return
        }
        text.animate().alpha(0f).setDuration(TEXT_OUT_MS).withEndAction {
            applyMode(newMode)
            onApplied()
            text.animate().alpha(1f).setDuration(TEXT_IN_MS).start()
        }.start()
    }

    private fun applyMode(mode: Mode) {
        val offline = mode == Mode.OFFLINE
        val color = ContextCompat.getColor(
            context,
            if (offline) R.color.color_warning else R.color.color_success_green
        )
        binding.stripTitle.setText(
            if (offline) R.string.offline_strip_title else R.string.offline_strip_back_online_title
        )
        binding.stripSubtitle.setText(
            if (offline) R.string.offline_strip_subtitle
            else R.string.offline_strip_back_online_subtitle
        )
        binding.stripTitle.setTextColor(color)
        binding.stripSubtitle.setTextColor(color)
        // TalkBack announces a pane when it appears and when its title changes
        ViewCompat.setAccessibilityPaneTitle(
            this,
            context.getString(
                R.string.offline_strip_pane_title,
                binding.stripTitle.text,
                binding.stripSubtitle.text
            )
        )

        // Only the offline state leads anywhere; "Back online" is purely informational.
        binding.stripContent.isClickable = offline
        binding.stripContent.isFocusable = offline
        ViewCompat.replaceAccessibilityAction(
            binding.stripContent,
            AccessibilityActionCompat.ACTION_CLICK,
            if (offline) context.getString(R.string.offline_strip_action_details) else null,
            null
        )
    }

    override fun onDraw(canvas: Canvas) {
        val bottom = (height - paddingBottom).toFloat()
        val width = width.toFloat()
        canvas.drawRect(0f, 0f, width, bottom, paint)

        val r = cornerRadius * reveal
        if (r < 1f) return
        filletPath.rewind()
        filletPath.moveTo(0f, bottom)
        filletPath.lineTo(r, bottom)
        filletPath.arcTo(0f, bottom, 2 * r, bottom + 2 * r, 270f, -90f, false)
        filletPath.close()
        filletPath.moveTo(width, bottom)
        filletPath.lineTo(width - r, bottom)
        filletPath.arcTo(width - 2 * r, bottom, width, bottom + 2 * r, 270f, 90f, false)
        filletPath.close()
        canvas.drawPath(filletPath, paint)
    }

    companion object {
        /** Share of the reveal over which the strip fades in: just enough that the status bar
         *  doesn't pop, short enough that the strip lands with the dimmed Charge button. */
        private const val FADE_PORTION = 0.15f
        private const val TEXT_OUT_MS = 120L
        private const val TEXT_IN_MS = 200L
    }
}
