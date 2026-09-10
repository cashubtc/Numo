package com.electricdreams.numo.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout

import com.electricdreams.numo.R

/** A full-window settings surface with a centered, readable content column. */
class SettingsPageLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        setBackgroundResource(R.color.settings_background)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.settings_content_max_width)
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxWidth
        } else {
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val params = child.layoutParams as LayoutParams
            params.width = minOf(maxWidth, availableWidth - params.leftMargin - params.rightMargin)
                .coerceAtLeast(0)
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
