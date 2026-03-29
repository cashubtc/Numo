package com.electricdreams.numo.feature.onboarding

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.ui.animation.GradientRingView
import com.google.android.material.imageview.ShapeableImageView

class OnboardingMintAdapter(
    private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onLoadMintIcon(mintUrl: String, iconView: ShapeableImageView)
        fun onResolveMintName(mintUrl: String): String
        fun onMintAcceptedChanged()
        fun onDefaultMintChanged(newDefaultUrl: String)
        fun onAddMintClicked()
        fun onRequestSetDefault(mintUrl: String, mintName: String)
    }

    sealed class ListItem {
        data class Header(val title: String, val acceptedCount: Int) : ListItem()
        data class DefaultHero(val url: String) : ListItem()
        data class Mint(val url: String) : ListItem()
        object Hint : ListItem()
        object AddMint : ListItem()
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_MINT = 1
        private const val VIEW_TYPE_DEFAULT_HERO = 2
        private const val VIEW_TYPE_HINT = 3
        private const val VIEW_TYPE_ADD_MINT = 4
        private const val PAYLOAD_NAME_ONLY = "name_only"
    }

    /** Refresh display names without re-binding icons. */
    fun refreshNames() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_NAME_ONLY)
    }

    private val items = mutableListOf<ListItem>()
    private val mints = mutableListOf<String>()
    val accepted = mutableSetOf<String>()

    private var acceptFromTitle = ""

    fun setHeaderStrings(acceptFromTitle: String) {
        this.acceptFromTitle = acceptFromTitle
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

    @SuppressLint("NotifyDataSetChanged")
    fun addMintAsDefault(url: String) {
        val oldDefault = mints.firstOrNull()
        if (oldDefault != null) {
            accepted.add(oldDefault)
        }
        mints.add(0, url)
        rebuildItems()
        notifyDataSetChanged()
        listener.onDefaultMintChanged(url)
    }

    private fun rebuildItems() {
        items.clear()
        if (mints.isNotEmpty()) {
            items.add(ListItem.DefaultHero(mints[0]))
        }
        items.add(ListItem.Header(acceptFromTitle, accepted.size))
        for (i in 1 until mints.size) {
            items.add(ListItem.Mint(mints[i]))
        }
        items.add(ListItem.Hint)
        items.add(ListItem.AddMint)
    }

    fun getDefaultMintUrl(): String? = mints.firstOrNull()

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

    private var pendingSwapIndex = -1
    private var pendingSwapUrl: String? = null
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    /**
     * Set a mint as the new default with a gradient-ring animation on the hero card.
     */
    fun confirmSetDefault(mintUrl: String) {
        val mintIndex = mints.indexOf(mintUrl)
        if (mintIndex < 1) return

        val hero = recyclerView?.findViewHolderForAdapterPosition(0) as? DefaultHeroViewHolder
        if (hero == null) {
            // Hero not visible — swap immediately without animation
            swapData(mintIndex)
            notifyItemChanged(0)
            return
        }

        pendingSwapIndex = mintIndex
        pendingSwapUrl = mintUrl

        val newName = listener.onResolveMintName(mintUrl)

        // 1. Spin the gradient ring around the avatar
        hero.gradientRing.spin {
            // 3. Ring done — swap the underlying data for the rest of the list
            swapData(pendingSwapIndex)
            pendingSwapIndex = -1
            pendingSwapUrl = null
        }

        // 2. Crossfade icon + name early so new content is settled before ring ends
        val crossfadeDelay = 150L
        val fadeDuration = 150L

        val appleSpring = PathInterpolator(0.175f, 0.885f, 0.32f, 1.1f)

        hero.mintIcon.animate()
            .alpha(0f)
            .setDuration(fadeDuration)
            .setStartDelay(crossfadeDelay)
            .withEndAction {
                listener.onLoadMintIcon(mintUrl, hero.mintIcon)
                // Fade in + settle bounce
                hero.mintIcon.scaleX = 0.92f
                hero.mintIcon.scaleY = 0.92f
                hero.mintIcon.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .setInterpolator(appleSpring)
                    .start()
            }
            .start()

        hero.mintName.animate()
            .alpha(0f)
            .setDuration(fadeDuration)
            .setStartDelay(crossfadeDelay)
            .withEndAction {
                hero.mintName.text = newName
                // Slide in from right + fade
                hero.mintName.translationX = 8f * hero.mintName.resources.displayMetrics.density
                hero.mintName.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(250)
                    .setInterpolator(appleSpring)
                    .start()
            }
            .start()

        hero.mintSubtitle.animate()
            .alpha(0f)
            .setDuration(fadeDuration)
            .setStartDelay(crossfadeDelay)
            .withEndAction {
                hero.mintSubtitle.translationX = 8f * hero.mintSubtitle.resources.displayMetrics.density
                hero.mintSubtitle.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(250)
                    .setInterpolator(appleSpring)
                    .start()
            }
            .start()
    }

    /**
     * Instant swap without animation — used for undo.
     */
    fun swapDefaultInstant(mintUrl: String) {
        val mintIndex = mints.indexOf(mintUrl)
        if (mintIndex < 1) return

        val oldDefault = mints[0]
        accepted.add(oldDefault)
        accepted.remove(mintUrl)
        mints[0] = mintUrl
        mints[mintIndex] = oldDefault

        rebuildItems()
        notifyDataSetChanged()
        listener.onDefaultMintChanged(mintUrl)
    }

    /**
     * Swap data arrays and notify the list rows (not the hero — it's already animated).
     */
    private fun swapData(tappedMintIndex: Int) {
        if (tappedMintIndex < 1 || tappedMintIndex >= mints.size) return

        val oldDefault = mints[0]
        val newDefault = mints[tappedMintIndex]

        accepted.add(oldDefault)
        accepted.remove(newDefault)

        mints[0] = newDefault
        mints[tappedMintIndex] = oldDefault

        val tappedAdapterPos = tappedMintIndex + 1

        rebuildItems()
        notifyItemChanged(1)               // Header count may change
        notifyItemChanged(tappedAdapterPos) // Swapped popular mint row

        listener.onDefaultMintChanged(newDefault)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Header -> VIEW_TYPE_HEADER
        is ListItem.DefaultHero -> VIEW_TYPE_DEFAULT_HERO
        is ListItem.Mint -> VIEW_TYPE_MINT
        is ListItem.Hint -> VIEW_TYPE_HINT
        is ListItem.AddMint -> VIEW_TYPE_ADD_MINT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_onboarding_mint_header, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_DEFAULT_HERO -> {
                val view = inflater.inflate(R.layout.item_onboarding_mint_hero, parent, false)
                DefaultHeroViewHolder(view)
            }
            VIEW_TYPE_HINT -> {
                val view = inflater.inflate(R.layout.item_onboarding_mint_hint, parent, false)
                HintViewHolder(view)
            }
            VIEW_TYPE_ADD_MINT -> {
                val view = inflater.inflate(R.layout.item_onboarding_mint_add, parent, false)
                AddMintViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_onboarding_mint, parent, false)
                MintViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_NAME_ONLY)) {
            // Partial update: refresh names only, skip icon loading
            when (val item = items[position]) {
                is ListItem.DefaultHero -> (holder as DefaultHeroViewHolder).mintName.text = listener.onResolveMintName(item.url)
                is ListItem.Mint -> (holder as MintViewHolder).name.text = listener.onResolveMintName(item.url)
                else -> {}
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        when (val item = items[position]) {
            is ListItem.Header -> {
                val h = holder as HeaderViewHolder
                h.title.text = item.title

                h.count.text = if (item.acceptedCount == 1) {
                    context.getString(R.string.onboarding_mints_count_label_singular, item.acceptedCount)
                } else {
                    context.getString(R.string.onboarding_mints_count_label, item.acceptedCount)
                }

                val lp = h.itemView.layoutParams as RecyclerView.LayoutParams
                lp.topMargin = (20 * density).toInt()
                h.itemView.layoutParams = lp
            }
            is ListItem.DefaultHero -> {
                val h = holder as DefaultHeroViewHolder
                h.mintName.text = listener.onResolveMintName(item.url)

                // Mint icon with rounded corners
                h.mintIcon.setBackgroundColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                h.mintIcon.shapeAppearanceModel = h.mintIcon.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(22f * density)
                    .build()
                listener.onLoadMintIcon(item.url, h.mintIcon)
            }
            is ListItem.Mint -> {
                val h = holder as MintViewHolder

                h.name.text = listener.onResolveMintName(item.url)

                // Icon
                h.icon.setBackgroundColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                h.icon.shapeAppearanceModel = h.icon.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(20f * density)
                    .build()
                listener.onLoadMintIcon(item.url, h.icon)

                val lp = h.itemView.layoutParams as RecyclerView.LayoutParams
                h.itemView.background =
                    ContextCompat.getDrawable(context, R.drawable.bg_mint_item)
                lp.bottomMargin = (8 * density).toInt()

                // Status text and checkbox
                val isAccepted = accepted.contains(item.url)
                updateMintRowState(context, h, isAccepted)

                // Checkbox toggle on row tap
                h.itemView.setOnClickListener {
                    val nowAccepted = !accepted.contains(item.url)
                    if (nowAccepted) accepted.add(item.url) else accepted.remove(item.url)
                    updateMintRowState(context, h, nowAccepted)
                    // Update the header count
                    rebuildItems()
                    notifyItemChanged(1)
                    listener.onMintAcceptedChanged()
                }

                // Long-press to set as default
                h.itemView.setOnLongClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    // Scale pulse: press down then spring back
                    val spring = PathInterpolator(0.175f, 0.885f, 0.32f, 1.1f)
                    view.animate()
                        .scaleX(0.96f).scaleY(0.96f)
                        .setDuration(100)
                        .withEndAction {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            } else {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                            view.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(250)
                                .setInterpolator(spring)
                                .start()
                        }
                        .start()
                    val mintName = listener.onResolveMintName(item.url)
                    listener.onRequestSetDefault(item.url, mintName)
                    true
                }

                h.itemView.layoutParams = lp
            }
            is ListItem.Hint -> {
                // Static text — no binding needed
            }
            is ListItem.AddMint -> {
                val h = holder as AddMintViewHolder
                h.addText.setOnClickListener {
                    listener.onAddMintClicked()
                }
            }
        }

    }

    private fun updateMintRowState(
        context: android.content.Context,
        holder: MintViewHolder,
        isAccepted: Boolean
    ) {
        if (isAccepted) {
            holder.checkbox.setImageResource(R.drawable.ic_checkbox_checked)
            holder.checkbox.setColorFilter(android.graphics.Color.WHITE)
            holder.status.text = context.getString(R.string.onboarding_mints_status_accepting)
            holder.status.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
        } else {
            holder.checkbox.setImageResource(R.drawable.ic_checkbox_unchecked)
            holder.checkbox.setColorFilter(android.graphics.Color.parseColor("#4DFFFFFF"))
            holder.status.text = context.getString(R.string.onboarding_mints_status_not_accepting)
            holder.status.setTextColor(android.graphics.Color.parseColor("#73FFFFFF"))
        }
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.header_title)
        val count: TextView = view.findViewById(R.id.header_count)
    }

    class DefaultHeroViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mintIcon: ShapeableImageView = view.findViewById(R.id.hero_mint_icon)
        val mintName: TextView = view.findViewById(R.id.hero_mint_name)
        val mintSubtitle: TextView = view.findViewById(R.id.hero_mint_subtitle)
        val gradientRing: GradientRingView = view.findViewById(R.id.hero_gradient_ring)
    }

    class MintViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ShapeableImageView = view.findViewById(R.id.mint_icon)
        val name: TextView = view.findViewById(R.id.mint_name)
        val status: TextView = view.findViewById(R.id.mint_status)
        val checkbox: ImageView = view.findViewById(R.id.mint_checkbox)
    }

    class HintViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class AddMintViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val addText: TextView = view.findViewById(R.id.add_mint_text)
    }
}
