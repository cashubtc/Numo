package com.electricdreams.numo.feature.insights

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An item's picture, or for a basket a small stack of up to three: the first item in front,
 * the next two as cards behind it, each a little smaller and raised so it peeks out above.
 *
 * The view always measures a single thumbnail, so every row's text starts on the same grid
 * whether it holds one item or many. The cards behind draw into the row's top padding, so
 * the row must not clip its children.
 */
class StackedAvatarsView internal constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int,
    private val loadThumbnail: suspend (String, Int) -> Bitmap?,
) : FrameLayout(context, attrs, defStyle) {

    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0,
    ) : this(context, attrs, defStyle, ItemThumbnailLoader::load)

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
    private var imageScope: CoroutineScope? = null
    private var avatars: List<Pair<ImageView, String?>> = emptyList()

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun setItems(items: List<BasketItemSummary>) {
        imageScope?.cancel()
        imageScope = null
        removeAllViews()
        val visible = items.take(maxAvatars)
        // Back to front, so the first item is added last and drawn on top
        avatars = visible.indices.reversed().map { depth ->
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
            applyPlaceholder(view)
            view to item.itemImagePath
        }
        loadAvatars()
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (imageScope == null) loadAvatars()
    }

    override fun onDetachedFromWindow() {
        imageScope?.cancel()
        imageScope = null
        super.onDetachedFromWindow()
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

    private fun loadAvatars() {
        if (avatars.none { !it.second.isNullOrBlank() }) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        imageScope = scope
        for ((view, path) in avatars) {
            if (path.isNullOrBlank()) continue
            scope.launch {
                val thumbnail = loadThumbnail(path, avatarSize)
                if (thumbnail != null) applyThumbnail(view, thumbnail)
            }
        }
    }

    internal suspend fun awaitThumbnails() {
        imageScope?.coroutineContext?.get(Job)?.children?.toList()?.forEach { it.join() }
    }

    private fun applyThumbnail(view: ImageView, thumbnail: Bitmap) {
        thumbnail.density = resources.displayMetrics.densityDpi
        view.clearColorFilter()
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageDrawable(
            RoundedBitmapDrawableFactory.create(resources, thumbnail).apply {
                // Match the ring's outer rounded edge when drawing in software too.
                cornerRadius = this@StackedAvatarsView.cornerRadius + dp(1f)
            },
        )
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
