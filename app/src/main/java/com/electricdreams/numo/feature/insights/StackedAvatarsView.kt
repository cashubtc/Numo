package com.electricdreams.numo.feature.insights

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.ThumbnailUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.electricdreams.numo.R

/**
 * An item's picture, or for a basket a small stack of up to three: the first item in front,
 * the next two as cards behind it, each a little smaller and raised so it peeks out above.
 *
 * The view always measures a single thumbnail, so every row's text starts on the same grid
 * whether it holds one item or many. The cards behind draw into the row's top padding, so
 * the row must not clip its children.
 */
class StackedAvatarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val avatarSize = dp(40f).toInt()

    /** How far each card behind rises above the one in front of it */
    private val peek = dp(4f)

    /** How much smaller each card behind is than the one in front of it */
    private val shrinkPerCard = 0.12f

    /** Cards behind fade toward the page colour, so the front one leads */
    private val scrimAlphas = intArrayOf(0, 90, 150)

    private val maxAvatars = 3

    private val cornerRadius = resources.getDimension(R.dimen.radius_s)
    private val pageColor = ContextCompat.getColor(context, R.color.color_bg_white)

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun setItems(items: List<BasketItemSummary>) {
        removeAllViews()
        val visible = items.take(maxAvatars)
        // Back to front, so the first item is added last and drawn on top
        for (depth in visible.indices.reversed()) {
            val item = visible[depth]
            val view = ImageView(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_avatar_placeholder)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = item.itemName
                // The front card speaks for the stack; the ones behind are decoration
                if (depth > 0) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // Shrink toward the top edge, then raise: the top peeks out, the rest hides
                pivotX = avatarSize / 2f
                pivotY = 0f
                val scale = 1f - shrinkPerCard * depth
                scaleX = scale
                scaleY = scale
                translationY = -peek * depth
                foreground = cardForeground(depth)
            }
            addView(view, LayoutParams(avatarSize, avatarSize))
            loadAvatarBitmap(view, item.itemImagePath)
        }
        requestLayout()
    }

    /**
     * A ring in the page colour around every card, over the picture, so stacked cards read as
     * separate cards; plus the fade for the cards behind
     */
    private fun cardForeground(depth: Int): Drawable {
        val ring = GradientDrawable().apply {
            cornerRadius = this@StackedAvatarsView.cornerRadius
            setStroke(dp(2f).toInt(), pageColor)
        }
        if (depth == 0) return ring
        val scrim = GradientDrawable().apply {
            cornerRadius = this@StackedAvatarsView.cornerRadius
            setColor(ColorUtils.setAlphaComponent(pageColor, scrimAlphas[depth]))
        }
        return LayerDrawable(arrayOf(scrim, ring))
    }

    private fun loadAvatarBitmap(view: ImageView, path: String?) {
        if (path.isNullOrBlank()) {
            applyPlaceholder(view)
            return
        }
        try {
            val bmp = BitmapFactory.decodeFile(path)
            if (bmp != null) {
                // Cropped to the thumbnail's exact size (and so its memory), and rounded here as
                // well as by the outline clip, which software drawing ignores
                val thumb = ThumbnailUtils.extractThumbnail(bmp, avatarSize, avatarSize)
                thumb.density = resources.displayMetrics.densityDpi
                view.setImageDrawable(
                    RoundedBitmapDrawableFactory.create(resources, thumb).apply {
                        // The ring's outer edge rounds at radius + half its stroke; match it so
                        // no sliver of photo shows past the ring at the corners
                        cornerRadius = this@StackedAvatarsView.cornerRadius + dp(1f)
                    },
                )
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
        // One thumbnail however many items: the stack grows up into the row's padding,
        // never sideways into the text
        val size = if (childCount == 0) 0 else avatarSize
        val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
