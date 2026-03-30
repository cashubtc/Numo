package com.electricdreams.numo.feature.onboarding

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
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
        fun onDefaultMintChanged(newDefaultUrl: String)
        fun onAddMintClicked()
        fun onRequestSetDefault(mintUrl: String, mintName: String)
    }

    sealed class ListItem {
        data class Header(val title: String) : ListItem()
        data class DefaultHero(val url: String) : ListItem()
        data class Mint(val url: String) : ListItem()
        object AddMint : ListItem()
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_MINT = 1
        private const val VIEW_TYPE_DEFAULT_HERO = 2
        private const val VIEW_TYPE_ADD_MINT = 4
        private const val PAYLOAD_NAME_ONLY = "name_only"

        // Animation timing constants
        private const val CROSSFADE_DELAY = 150L
        private const val FADE_DURATION = 150L
        private const val FADE_IN_DURATION = 300L
        private const val SLIDE_IN_DURATION = 250L
        private const val MATERIALIZE_DURATION = 300L
        private const val TAP_PRESS_DURATION = 100L
        private const val TAP_RELEASE_DURATION = 250L

        private val SPRING_INTERPOLATOR = PathInterpolator(0.175f, 0.885f, 0.32f, 1.1f)
    }

    /** Refresh display names without re-binding icons. */
    fun refreshNames() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_NAME_ONLY)
    }

    private val items = mutableListOf<ListItem>()
    private val mints = mutableListOf<String>()
    val accepted = mutableSetOf<String>()

    private var headerTitle = ""

    fun setHeaderStrings(headerTitle: String) {
        this.headerTitle = headerTitle
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
        items.add(ListItem.Header(headerTitle))
        for (i in 1 until mints.size) {
            items.add(ListItem.Mint(mints[i]))
        }
        items.add(ListItem.AddMint)
    }

    fun getDefaultMintUrl(): String? = mints.firstOrNull()

    fun getPopularMints(): List<String> {
        return mints.drop(1)
    }

    fun getAllSelectedMints(): Set<String> {
        val result = mutableSetOf<String>()
        mints.firstOrNull()?.let { result.add(it) }
        result.addAll(accepted)
        return result
    }

    private var pendingSwapIndex = -1
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
     * Set a mint as the new default with a gradient-ring animation on the hero card
     * and a synchronized crossfade on the swapped popular-mint row.
     */
    fun confirmSetDefault(mintUrl: String) {
        val mintIndex = mints.indexOf(mintUrl)
        if (mintIndex < 1) return

        // Ignore taps while a swap animation is already in progress
        if (pendingSwapIndex != -1) return

        val hero = recyclerView?.findViewHolderForAdapterPosition(0) as? DefaultHeroViewHolder
        if (hero == null) {
            swapData(mintIndex)
            notifyItemChanged(0)
            return
        }

        val oldDefault = mints[0]
        pendingSwapIndex = mintIndex

        val newName = listener.onResolveMintName(mintUrl)
        val density = hero.itemView.resources.displayMetrics.density

        // 1. Spin the gradient ring around the avatar
        hero.gradientRing.spin {
            swapData(pendingSwapIndex)
            pendingSwapIndex = -1
        }

        // 2. Crossfade hero icon + name (starts after short delay)
        hero.mintIcon.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION)
            .setStartDelay(CROSSFADE_DELAY)
            .withEndAction {
                listener.onLoadMintIcon(mintUrl, hero.mintIcon)
                hero.mintIcon.scaleX = 0.92f
                hero.mintIcon.scaleY = 0.92f
                hero.mintIcon.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(FADE_IN_DURATION)
                    .setInterpolator(SPRING_INTERPOLATOR)
                    .start()
            }
            .start()

        hero.mintName.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION)
            .setStartDelay(CROSSFADE_DELAY)
            .withEndAction {
                hero.mintName.text = newName
                hero.mintName.translationX = 8f * density
                hero.mintName.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(SLIDE_IN_DURATION)
                    .setInterpolator(SPRING_INTERPOLATOR)
                    .start()
            }
            .start()

        hero.mintSubtitle.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION)
            .setStartDelay(CROSSFADE_DELAY)
            .withEndAction {
                hero.mintSubtitle.translationX = 8f * density
                hero.mintSubtitle.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(SLIDE_IN_DURATION)
                    .setInterpolator(SPRING_INTERPOLATOR)
                    .start()
            }
            .start()

        // 3. Dissolve the tapped popular-mint row in sync with the hero
        val tappedAdapterPos = mintIndex + 1
        val mintHolder = recyclerView?.findViewHolderForAdapterPosition(tappedAdapterPos) as? MintViewHolder
        if (mintHolder != null) {
            val dissolveUp = -4f * density   // barely perceptible directional hint
            val materializeFrom = 6f * density

            // Exit: gentle dissolve — fade out with soft upward drift
            mintHolder.icon.animate()
                .alpha(0f).translationY(dissolveUp)
                .setDuration(FADE_DURATION)
                .setStartDelay(CROSSFADE_DELAY)
                .withEndAction {
                    // Brief pause, then materialize the replacement
                    listener.onLoadMintIcon(oldDefault, mintHolder.icon)
                    mintHolder.icon.translationY = materializeFrom
                    mintHolder.icon.scaleX = 0.97f
                    mintHolder.icon.scaleY = 0.97f
                    mintHolder.icon.animate()
                        .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(MATERIALIZE_DURATION)
                        .setStartDelay(30)
                        .setInterpolator(SPRING_INTERPOLATOR)
                        .start()
                }
                .start()

            mintHolder.name.animate()
                .alpha(0f).translationY(dissolveUp)
                .setDuration(FADE_DURATION)
                .setStartDelay(CROSSFADE_DELAY)
                .withEndAction {
                    mintHolder.name.text = listener.onResolveMintName(oldDefault)
                    mintHolder.name.translationY = materializeFrom
                    mintHolder.name.animate()
                        .alpha(1f).translationY(0f)
                        .setDuration(MATERIALIZE_DURATION)
                        .setStartDelay(30)
                        .setInterpolator(SPRING_INTERPOLATOR)
                        .start()
                }
                .start()
        }
    }

    /**
     * Instant swap without animation — used for undo.
     */
    @SuppressLint("NotifyDataSetChanged")
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
     * Swap data arrays only — visual animation is handled by confirmSetDefault.
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
        // Rebind only the swapped row so its click listener picks up the new URL
        notifyItemChanged(tappedAdapterPos)

        listener.onDefaultMintChanged(newDefault)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Header -> VIEW_TYPE_HEADER
        is ListItem.DefaultHero -> VIEW_TYPE_DEFAULT_HERO
        is ListItem.Mint -> VIEW_TYPE_MINT
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
                h.count.setText(R.string.onboarding_mints_tap_hint)

                val lp = h.itemView.layoutParams as RecyclerView.LayoutParams
                lp.topMargin = (20 * density).toInt()
                h.itemView.layoutParams = lp
            }
            is ListItem.DefaultHero -> {
                val h = holder as DefaultHeroViewHolder
                h.mintName.text = listener.onResolveMintName(item.url)

                h.mintIcon.setBackgroundColor(ContextCompat.getColor(h.itemView.context, R.color.color_onboarding_icon_bg))
                h.mintIcon.shapeAppearanceModel = h.mintIcon.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(22f * density)
                    .build()
                listener.onLoadMintIcon(item.url, h.mintIcon)
            }
            is ListItem.Mint -> {
                val h = holder as MintViewHolder

                h.name.text = listener.onResolveMintName(item.url)

                h.icon.setBackgroundColor(ContextCompat.getColor(h.itemView.context, R.color.color_onboarding_icon_bg))
                h.icon.shapeAppearanceModel = h.icon.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(20f * density)
                    .build()
                listener.onLoadMintIcon(item.url, h.icon)

                val lp = h.itemView.layoutParams as RecyclerView.LayoutParams
                h.itemView.background =
                    ContextCompat.getDrawable(context, R.drawable.bg_mint_item)
                lp.bottomMargin = (8 * density).toInt()

                // Reset any residual animation transforms from confirmSetDefault
                h.icon.translationY = 0f
                h.icon.scaleX = 1f
                h.icon.scaleY = 1f
                h.icon.alpha = 1f
                h.name.translationY = 0f
                h.name.alpha = 1f

                h.itemView.setOnClickListener { view ->
                    view.animate()
                        .scaleX(0.96f).scaleY(0.96f)
                        .setDuration(TAP_PRESS_DURATION)
                        .withEndAction {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                            } else {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                            }
                            view.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(TAP_RELEASE_DURATION)
                                .setInterpolator(SPRING_INTERPOLATOR)
                                .start()
                        }
                        .start()
                    val mintName = listener.onResolveMintName(item.url)
                    listener.onRequestSetDefault(item.url, mintName)
                }

                h.itemView.layoutParams = lp
            }
            is ListItem.AddMint -> {
                val h = holder as AddMintViewHolder
                h.addText.setOnClickListener {
                    listener.onAddMintClicked()
                }
            }
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
    }

    class AddMintViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val addText: TextView = view.findViewById(R.id.add_mint_text)
    }
}
