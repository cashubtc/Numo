package com.electricdreams.numo.feature.onboarding

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.google.android.material.imageview.ShapeableImageView

class OnboardingMintAdapter(
    private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onLoadMintIcon(mintUrl: String, iconView: ShapeableImageView)
        fun onResolveMintName(mintUrl: String): String
        fun onMintAcceptedChanged()
        fun onDefaultMintChanged(newDefaultUrl: String)
    }

    sealed class ListItem {
        data class Header(
            val title: String,
            val subtitle: String,
            val topMarginDp: Int,
            val titleSizeSp: Float,
            val subtitleSizeSp: Float
        ) : ListItem()
        data class Mint(val url: String, val isDefault: Boolean) : ListItem()
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_MINT = 1
    }

    private val items = mutableListOf<ListItem>()
    private val mints = mutableListOf<String>()
    val accepted = mutableSetOf<String>()

    private var defaultSectionTitle = ""
    private var defaultSectionSubtitle = ""
    private var popularSectionTitle = ""
    private var popularSectionSubtitle = ""

    fun setHeaderStrings(
        defTitle: String,
        defSubtitle: String,
        popTitle: String,
        popSubtitle: String
    ) {
        defaultSectionTitle = defTitle
        defaultSectionSubtitle = defSubtitle
        popularSectionTitle = popTitle
        popularSectionSubtitle = popSubtitle
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setMints(defaultUrl: String, popularUrls: List<String>, acceptedUrls: Set<String>) {
        mints.clear()
        mints.add(defaultUrl)
        mints.addAll(popularUrls)
        accepted.clear()
        accepted.addAll(acceptedUrls)
        rebuildItems()
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun addMint(url: String) {
        mints.add(url)
        accepted.add(url)
        rebuildItems()
        notifyDataSetChanged()
    }

    private fun rebuildItems() {
        items.clear()
        items.add(ListItem.Header(
            defaultSectionTitle, defaultSectionSubtitle,
            topMarginDp = 0, titleSizeSp = 22f, subtitleSizeSp = 14f
        ))
        if (mints.isNotEmpty()) {
            items.add(ListItem.Mint(mints[0], isDefault = true))
        }
        items.add(ListItem.Header(
            popularSectionTitle, popularSectionSubtitle,
            topMarginDp = 24, titleSizeSp = 22f, subtitleSizeSp = 14f
        ))
        for (i in 1 until mints.size) {
            items.add(ListItem.Mint(mints[i], isDefault = false))
        }
    }

    fun getDefaultMintUrl(): String {
        return mints.firstOrNull() ?: ""
    }

    fun getPopularMints(): List<String> {
        return mints.drop(1)
    }

    fun getAcceptedMints(): Set<String> = accepted.toSet()

    fun getAllSelectedMints(): Set<String> {
        val result = mutableSetOf<String>()
        // Default mint is always selected
        mints.firstOrNull()?.let { result.add(it) }
        // Plus all accepted popular mints
        result.addAll(accepted)
        return result
    }

    /**
     * Swaps a popular mint with the current default mint.
     * Uses notifyItemMoved + notifyItemChanged for smooth RecyclerView animation.
     */
    private fun swapToDefault(tappedMintIndex: Int) {
        if (tappedMintIndex < 1 || tappedMintIndex >= mints.size) return

        val oldDefault = mints[0]
        val newDefault = mints[tappedMintIndex]

        // Old default becomes popular — add to accepted (was implicitly always on)
        accepted.add(oldDefault)
        // New default loses checkbox — remove from accepted set
        accepted.remove(newDefault)

        // Swap in the data list
        mints[0] = newDefault
        mints[tappedMintIndex] = oldDefault

        // Calculate adapter positions (accounting for headers):
        // [0] = Header "Default Mint"
        // [1] = default mint
        // [2] = Header "Popular Mints"
        // [3..] = popular mints
        val defaultAdapterPos = 1
        val tappedAdapterPos = tappedMintIndex + 2 // +2 for two headers

        // Move tapped item up to default position, old default down to tapped position
        notifyItemMoved(tappedAdapterPos, defaultAdapterPos)
        notifyItemMoved(defaultAdapterPos + 1, tappedAdapterPos)

        // Rebuild items with updated isDefault flags and notify changes
        rebuildItems()
        notifyItemChanged(defaultAdapterPos)
        notifyItemChanged(tappedAdapterPos)

        listener.onDefaultMintChanged(newDefault)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Header -> VIEW_TYPE_HEADER
        is ListItem.Mint -> VIEW_TYPE_MINT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_onboarding_mint_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_onboarding_mint, parent, false)
                MintViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        when (val item = items[position]) {
            is ListItem.Header -> {
                val h = holder as HeaderViewHolder
                h.title.text = item.title
                h.subtitle.text = item.subtitle

                // Apply dynamic text sizes and style
                h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.titleSizeSp)
                h.subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.subtitleSizeSp)

                if (item.titleSizeSp > 16f) {
                    // Large header style (Default Mint section)
                    h.title.letterSpacing = 0f
                    h.title.isAllCaps = false
                    h.title.setTextColor(ContextCompat.getColor(context, R.color.color_text_primary))
                } else {
                    // Small label style (Popular Mints section)
                    h.title.letterSpacing = 0.08f
                    h.title.isAllCaps = false // already uppercase in string resource
                    h.title.setTextColor(ContextCompat.getColor(context, R.color.color_text_secondary))
                }

                val lp = h.itemView.layoutParams as RecyclerView.LayoutParams
                lp.topMargin = (item.topMarginDp * density).toInt()
                h.itemView.layoutParams = lp
            }
            is ListItem.Mint -> {
                val h = holder as MintViewHolder

                h.name.text = listener.onResolveMintName(item.url)

                // Icon
                h.icon.setImageResource(R.drawable.ic_bitcoin)
                h.icon.setColorFilter(ContextCompat.getColor(context, R.color.color_primary))
                h.icon.setBackgroundColor(ContextCompat.getColor(context, R.color.color_bg_tertiary))
                h.icon.shapeAppearanceModel = h.icon.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(21f * density)
                    .build()
                listener.onLoadMintIcon(item.url, h.icon)

                val lp = h.itemView.layoutParams as RecyclerView.LayoutParams

                if (item.isDefault) {
                    h.itemView.background =
                        ContextCompat.getDrawable(context, R.drawable.bg_default_mint_item)
                    lp.bottomMargin = 0

                    h.checkbox.visibility = View.GONE

                    // Filled home icon for default
                    h.star.setImageResource(R.drawable.ic_home_filled)
                    h.star.setOnClickListener(null)
                    h.star.isClickable = false

                    h.itemView.setOnClickListener(null)
                } else {
                    h.itemView.background =
                        ContextCompat.getDrawable(context, R.drawable.bg_mint_item)
                    lp.bottomMargin = (8 * density).toInt()

                    h.checkbox.visibility = View.VISIBLE
                    updateCheckboxState(context, h.checkbox, accepted.contains(item.url))

                    // Outline home icon for popular — tap to promote to default
                    h.star.setImageResource(R.drawable.ic_home_outline)
                    h.star.isClickable = true
                    h.star.setOnClickListener { view ->
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val mintIndex = mints.indexOf(item.url)
                        if (mintIndex > 0) {
                            swapToDefault(mintIndex)
                        }
                    }

                    // Checkbox toggle on row tap
                    h.itemView.setOnClickListener {
                        val nowAccepted = !accepted.contains(item.url)
                        if (nowAccepted) accepted.add(item.url) else accepted.remove(item.url)
                        updateCheckboxState(context, h.checkbox, nowAccepted)
                        listener.onMintAcceptedChanged()
                    }
                }
                h.itemView.layoutParams = lp
            }
        }
    }

    private fun updateCheckboxState(
        context: android.content.Context,
        checkbox: ImageView,
        isSelected: Boolean
    ) {
        if (isSelected) {
            checkbox.setImageResource(R.drawable.ic_checkbox_checked)
            checkbox.setColorFilter(ContextCompat.getColor(context, R.color.color_success_green))
        } else {
            checkbox.setImageResource(R.drawable.ic_checkbox_unchecked)
            checkbox.setColorFilter(ContextCompat.getColor(context, R.color.color_text_tertiary))
        }
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.header_title)
        val subtitle: TextView = view.findViewById(R.id.header_subtitle)
    }

    class MintViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ShapeableImageView = view.findViewById(R.id.mint_icon)
        val name: TextView = view.findViewById(R.id.mint_name)
        val checkbox: ImageView = view.findViewById(R.id.mint_checkbox)
        val star: ImageView = view.findViewById(R.id.mint_star)
    }
}
