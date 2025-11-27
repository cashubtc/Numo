package com.electricdreams.shellshock.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * A layout that arranges child views in a horizontal flow,
 * wrapping to new lines as needed. Similar to CSS flexbox.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private var horizontalSpacing = 0
    private var verticalSpacing = 0

    fun setSpacing(horizontal: Int, vertical: Int) {
        horizontalSpacing = horizontal
        verticalSpacing = vertical
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var height = 0
        var lineHeight = 0
        var xPos = paddingLeft
        var yPos = paddingTop

        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            child.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (xPos + childWidth > width - paddingRight) {
                // New line
                xPos = paddingLeft
                yPos += lineHeight + verticalSpacing
                lineHeight = 0
            }

            xPos += childWidth + horizontalSpacing
            lineHeight = maxOf(lineHeight, childHeight)
        }

        height = yPos + lineHeight + paddingBottom

        setMeasuredDimension(
            width,
            height
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var lineHeight = 0
        var xPos = paddingLeft
        var yPos = paddingTop

        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (xPos + childWidth > width - paddingRight) {
                // New line
                xPos = paddingLeft
                yPos += lineHeight + verticalSpacing
                lineHeight = 0
            }

            child.layout(xPos, yPos, xPos + childWidth, yPos + childHeight)
            xPos += childWidth + horizontalSpacing
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean {
        return p is MarginLayoutParams
    }
}
