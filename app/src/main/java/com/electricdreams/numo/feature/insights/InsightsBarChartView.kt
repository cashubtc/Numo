package com.electricdreams.numo.feature.insights

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R

class InsightsBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val barRadius = dp(8f)
    private val barMinHeightWithData = dp(10f)
    private val emptyBarHeight = dp(2f)
    private val barTopPadding = dp(4f)
    private val labelTopMargin = dp(10f)
    private val labelTextSize = sp(12f)
    private val sideInset = dp(20f)

    private val colorBarMuted = ContextCompat.getColor(context, R.color.color_chip_border)
    private val colorBarAccent = ContextCompat.getColor(context, R.color.color_primary)
    private val colorLabelMuted = ContextCompat.getColor(context, R.color.color_text_tertiary)
    private val colorLabelAccent = ContextCompat.getColor(context, R.color.color_primary)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = labelTextSize
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val rect = RectF()

    private var buckets: List<BucketTotal> = emptyList()
    private var selectedIndex: Int? = null
    private var listener: ((Int?) -> Unit)? = null

    fun setData(buckets: List<BucketTotal>) {
        this.buckets = buckets
        invalidate()
    }

    fun setSelectedIndex(index: Int?) {
        if (selectedIndex == index) return
        selectedIndex = index
        invalidate()
    }

    fun setOnSelectionChanged(listener: (Int?) -> Unit) {
        this.listener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = dp(120f).toInt()
        val h = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (buckets.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val labelHeight = labelPaint.fontMetrics.run { bottom - top }
        val barAreaTop = barTopPadding
        val barAreaBottom = h - labelHeight - labelTopMargin
        val barAreaHeight = barAreaBottom - barAreaTop

        val usableWidth = w - 2 * sideInset
        val slotWidth = usableWidth / 7f
        val barWidth = (slotWidth * 0.55f).coerceAtMost(dp(28f))

        val maxSats = buckets.maxOf { it.totalSats }.coerceAtLeast(1L)

        for (i in 0 until 7) {
            val bucket = buckets[i]
            val cx = sideInset + slotWidth * (i + 0.5f)

            val isSelected = (i == selectedIndex)
            val isCurrentHighlight = bucket.isCurrent && selectedIndex == null

            val barHeight = if (bucket.totalSats <= 0L) {
                emptyBarHeight
            } else {
                val ratio = bucket.totalSats.toDouble() / maxSats.toDouble()
                (ratio * barAreaHeight).toFloat().coerceAtLeast(barMinHeightWithData)
            }

            val barTop = barAreaBottom - barHeight
            val barBottom = barAreaBottom
            rect.set(cx - barWidth / 2f, barTop, cx + barWidth / 2f, barBottom)

            barPaint.color = if (isSelected || isCurrentHighlight) colorBarAccent else colorBarMuted
            canvas.drawRoundRect(rect, barRadius, barRadius, barPaint)

            labelPaint.color = if (isSelected || isCurrentHighlight) colorLabelAccent else colorLabelMuted
            val labelY = h - labelPaint.fontMetrics.descent
            canvas.drawText(bucket.label, cx, labelY, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) {
            return event.action == MotionEvent.ACTION_DOWN
        }
        val w = width.toFloat()
        val usableWidth = w - 2 * sideInset
        val slotWidth = usableWidth / 7f
        val x = event.x
        if (x < sideInset || x > w - sideInset) {
            performClick()
            return true
        }
        val tappedIdx = ((x - sideInset) / slotWidth).toInt().coerceIn(0, 6)
        val newSelection = if (selectedIndex == tappedIdx) null else tappedIdx
        selectedIndex = newSelection
        invalidate()
        listener?.invoke(newSelection)
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
