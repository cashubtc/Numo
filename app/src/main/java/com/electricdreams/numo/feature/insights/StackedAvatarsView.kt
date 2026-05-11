package com.electricdreams.numo.feature.insights

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.electricdreams.numo.R
import kotlin.math.min

class StackedAvatarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val avatarSize = dp(40f).toInt()
    private val overlap = dp(14f).toInt()
    private val maxAvatars = 3

    fun setItems(items: List<BasketItemSummary>) {
        removeAllViews()
        val visible = items.take(maxAvatars)
        for ((i, item) in visible.withIndex()) {
            val view = ImageView(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_avatar_placeholder)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = item.itemName
            }
            val lp = LayoutParams(avatarSize, avatarSize).apply {
                leftMargin = i * (avatarSize - overlap)
            }
            addView(view, lp)
            loadAvatarBitmap(view, item.itemImagePath)
        }
        requestLayout()
    }

    private fun loadAvatarBitmap(view: ImageView, path: String?) {
        if (path.isNullOrBlank()) {
            applyPlaceholder(view)
            return
        }
        try {
            val bmp = BitmapFactory.decodeFile(path)
            if (bmp != null) {
                view.setImageBitmap(bmp)
            } else {
                applyPlaceholder(view)
            }
        } catch (e: Exception) {
            applyPlaceholder(view)
        }
    }

    private fun applyPlaceholder(view: ImageView) {
        view.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_image_placeholder))
        view.setColorFilter(ContextCompat.getColor(context, R.color.color_icon_tertiary))
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = min(childCount, maxAvatars)
        val computedWidth = if (count == 0) 0 else avatarSize + (count - 1) * (avatarSize - overlap)
        val widthSpec = MeasureSpec.makeMeasureSpec(computedWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(avatarSize, MeasureSpec.EXACTLY)
        super.onMeasure(widthSpec, heightSpec)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
