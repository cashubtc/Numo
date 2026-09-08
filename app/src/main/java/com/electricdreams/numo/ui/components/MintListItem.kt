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
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintIconCache
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.databinding.ComponentMintListItemBinding

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

    private val binding = ComponentMintListItemBinding.inflate(LayoutInflater.from(context), this, true)
    private var stackedBalance: Boolean? = null

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

    init {
        
        container = binding.mintItemContainer
        iconContainer = binding.iconContainer
        mintIcon = binding.mintIcon
        nameText = binding.mintName
        urlText = binding.mintUrl
        balanceText = binding.balanceText
        chevron = binding.chevron
        
        setupClickListener()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) -
            resources.getDimensionPixelSize(R.dimen.settings_row_icon_size) -
            resources.getDimensionPixelSize(R.dimen.settings_row_icon_gap) -
            resources.getDimensionPixelSize(R.dimen.space_s) - chevron.layoutParams.width
        val amountWidth = balanceText.paint.measureText(balanceText.text.toString())
        val stack = resources.configuration.fontScale >= 1.3f ||
            amountWidth > availableWidth * 0.4f
        if (stackedBalance != stack) {
            stackedBalance = stack
            ConstraintSet().apply {
                clone(binding.mintTextContainer)
                connect(R.id.mint_name, ConstraintSet.END,
                    if (stack) ConstraintSet.PARENT_ID else R.id.balance_text,
                    if (stack) ConstraintSet.END else ConstraintSet.START)
                clear(R.id.balance_text, ConstraintSet.START)
                clear(R.id.balance_text, ConstraintSet.END)
                connect(R.id.balance_text, ConstraintSet.TOP,
                    if (stack) R.id.mint_url else ConstraintSet.PARENT_ID,
                    if (stack) ConstraintSet.BOTTOM else ConstraintSet.TOP,
                    if (stack) resources.getDimensionPixelSize(R.dimen.settings_row_subtitle_gap) else 0)
                connect(R.id.balance_text, if (stack) ConstraintSet.START else ConstraintSet.END,
                    if (stack) ConstraintSet.PARENT_ID else R.id.chevron, ConstraintSet.START,
                    if (stack) 0 else resources.getDimensionPixelSize(R.dimen.space_s))
                if (stack) {
                    connect(R.id.balance_text, ConstraintSet.END,
                        R.id.chevron, ConstraintSet.START,
                        resources.getDimensionPixelSize(R.dimen.space_s))
                }
                constrainWidth(R.id.balance_text,
                    if (stack) ConstraintSet.MATCH_CONSTRAINT else ConstraintSet.WRAP_CONTENT)
                applyTo(binding.mintTextContainer)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun setupClickListener() {
        container.setOnClickListener {
            animateTap()
            listener?.onMintTapped(mintUrl)
        }
    }

    fun bind(url: String, balance: Long) {
        mintUrl = url
        
        // Get mint info
        val mintManager = MintManager.getInstance(context)
        val displayName = mintManager.getMintDisplayName(url)
        val shortUrl = url.removePrefix("https://").removePrefix("http://")
        
        nameText.text = displayName
        urlText.text = shortUrl
        
        val preferredUnit = mintManager.getPreferredUnit()
        val supportsUnit = mintManager.mintSupportsUnit(url, preferredUnit)
        val lowerUnit = preferredUnit.lowercase()
        val isCustomUnit = lowerUnit != "sat"
        
        container.alpha = 1.0f
        if (!supportsUnit) {
            balanceText.text = context.getString(R.string.mints_unsupported_unit, preferredUnit)
        } else {
            if (isCustomUnit) {
                val currency = Amount.Currency.fromCode(lowerUnit)
                if (currency.symbol != lowerUnit.uppercase()) {
                    val valueToFormat = if (currency.isZeroDecimal()) balance * 100 else balance
                    balanceText.text = Amount(valueToFormat, currency).toString()
                } else {
                    balanceText.text = "$balance $preferredUnit"
                }
            } else {
                balanceText.text = Amount(balance, Amount.Currency.BTC).toString()
            }
        }
        
        // Load icon
        loadIcon(url)
        
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
