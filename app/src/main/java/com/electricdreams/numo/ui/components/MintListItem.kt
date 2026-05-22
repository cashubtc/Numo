package com.electricdreams.numo.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintIconCache
import com.electricdreams.numo.core.util.MintManager

/**
 * Clean, simplified mint list item.
 * 
 * Features:
 * - Clean row layout: icon, name, URL, balance, chevron
 * - Tap to open mint details
 * - No selection indicators or expandable buttons
 * - Smooth tap animation
 */
class MintListItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnMintItemListener {
        fun onMintTapped(mintUrl: String)
    }

    // Views
    private val container: View
    private val iconContainer: FrameLayout
    private val mintIcon: ImageView
    private val nameText: TextView
    private val urlText: TextView
    private val balanceText: TextView
    private val chevron: ImageView
    
    private var mintUrl: String = ""
    private var listener: OnMintItemListener? = null
    private var isLastItem: Boolean = false

    init {
        LayoutInflater.from(context).inflate(R.layout.component_mint_list_item, this, true)
        
        container = findViewById(R.id.mint_item_container)
        iconContainer = findViewById(R.id.icon_container)
        mintIcon = findViewById(R.id.mint_icon)
        nameText = findViewById(R.id.mint_name)
        urlText = findViewById(R.id.mint_url)
        balanceText = findViewById(R.id.balance_text)
        chevron = findViewById(R.id.chevron)
        
        setupClickListener()
    }

    private fun setupClickListener() {
        container.setOnClickListener {
            animateTap()
            listener?.onMintTapped(mintUrl)
        }
    }

    fun bind(url: String, balance: Long, isLast: Boolean = false) {
        mintUrl = url
        isLastItem = isLast
        
        // Get mint info
        val mintManager = MintManager.getInstance(context)
        val displayName = mintManager.getMintDisplayName(url)
        val shortUrl = url.removePrefix("https://").removePrefix("http://")
        
        nameText.text = displayName
        urlText.text = shortUrl
        
        val preferredUnit = mintManager.getPreferredUnit()
        val isCustomUnit = preferredUnit.lowercase() != "sat"
        
        if (isCustomUnit) {
            balanceText.text = "$balance $preferredUnit"
        } else {
            balanceText.text = Amount(balance, Amount.Currency.BTC).toString()
        }
        
        // Load icon
        loadIcon(url)
        
        // Add divider if not last item
        updateDivider()
    }

    private fun updateDivider() {
        // Remove any existing divider
        val existingDivider = findViewById<View>(R.id.item_divider)
        existingDivider?.let { (parent as? FrameLayout)?.removeView(it) }
        
        if (!isLastItem) {
            // Add divider view
            val divider = View(context).apply {
                id = R.id.item_divider
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.divider_height)
                ).apply {
                    marginStart = resources.getDimensionPixelSize(R.dimen.mint_item_divider_margin)
                    gravity = android.view.Gravity.BOTTOM
                }
                setBackgroundColor(context.getColor(R.color.color_divider))
            }
            addView(divider)
        }
    }

    private fun loadIcon(url: String) {
        val cachedFile = MintIconCache.getCachedIconFile(url)
        if (cachedFile != null) {
            try {
                val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (bitmap != null) {
                    mintIcon.setImageBitmap(bitmap)
                    mintIcon.clipToOutline = true
                    mintIcon.clearColorFilter()
                    return
                }
            } catch (e: Exception) {
                // Fall through to default
            }
        }
        
        mintIcon.setImageResource(R.drawable.ic_bitcoin)
        mintIcon.setColorFilter(context.getColor(R.color.color_primary))
    }

    private fun animateTap() {
        container.animate()
            .scaleX(0.98f)
            .scaleY(0.98f)
            .setDuration(80)
            .withEndAction {
                container.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    fun animateEntrance(delay: Long) {
        alpha = 0f
        translationY = 20f
        
        animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
        
        // Icon bounce
        iconContainer.scaleX = 0f
        iconContainer.scaleY = 0f
        iconContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay + 80)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
    }

    fun setOnMintItemListener(listener: OnMintItemListener) {
        this.listener = listener
    }

    fun getMintUrl(): String = mintUrl
}
