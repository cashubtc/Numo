package com.electricdreams.numo.feature.onboarding

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.MintIconCache
import com.electricdreams.numo.nostr.NostrMintDiscovery
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

class MintDiscoveryBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onMintSelected(url: String)
    }

    private var listener: Listener? = null
    private val recommendations = linkedMapOf<String, NostrMintDiscovery.MintRecommendation>()
    private val itemViews = linkedMapOf<String, View>()
    private val profileNames = mutableMapOf<String, String>()
    private val profileRequests = mutableSetOf<String>()
    private val profileSemaphore = Semaphore(6)
    private var searchQuery = ""

    companion object {
        fun newInstance(listener: Listener) = MintDiscoveryBottomSheet().apply {
            this.listener = listener
        }
    }

    override fun getTheme(): Int = R.style.Theme_Numo_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_mint_discovery, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.discovery_retry_button).setOnClickListener {
            discover(view)
        }
        view.findViewById<TextView>(R.id.discovery_search_input)
            .addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(text: Editable?) {
                    searchQuery = text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    applySearch()
                }
            })
        discover(view)
    }

    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        val sheet = sheetDialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return

        sheet.layoutParams = sheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        sheetDialog.behavior.apply {
            setFitToContents(false)
            halfExpandedRatio = 0.6f
            expandedOffset = 0
            skipCollapsed = true
            isDraggable = true
        }
        sheet.post {
            sheetDialog.behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    private fun discover(root: View) {
        val progress = root.findViewById<ProgressBar>(R.id.discovery_progress)
        val status = root.findViewById<TextView>(R.id.discovery_status)
        val retry = root.findViewById<View>(R.id.discovery_retry_button)
        val list = root.findViewById<LinearLayout>(R.id.discovery_list)

        progress.isVisible = true
        status.isVisible = true
        status.setText(R.string.mint_discovery_loading)
        retry.isVisible = false
        list.removeAllViews()
        recommendations.clear()
        itemViews.clear()
        profileNames.clear()
        profileRequests.clear()

        viewLifecycleOwner.lifecycleScope.launch {
            NostrMintDiscovery.discoverFlow().collect { update ->
                if (!isAdded) return@collect
                val visibleUrls = update.mapTo(mutableSetOf()) { it.url }
                recommendations.keys.filterNot { it in visibleUrls }.forEach { url ->
                    recommendations.remove(url)
                    itemViews.remove(url)?.let(list::removeView)
                    profileNames.remove(url)
                }
                update.take(NostrMintDiscovery.MAX_DISCOVERY_RESULTS)
                    .forEachIndexed { index, recommendation ->
                        recommendations[recommendation.url] = recommendation
                        val item = itemViews[recommendation.url] ?: layoutInflater.inflate(
                            R.layout.item_mint_discovery,
                            list,
                            false,
                        ).also {
                            itemViews[recommendation.url] = it
                        }
                        val currentIndex = list.indexOfChild(item)
                        if (currentIndex != index) {
                            if (currentIndex >= 0) list.removeView(item)
                            list.addView(item, index)
                        }
                        bindRecommendation(item, recommendation)
                        loadProfile(recommendation.url)
                    }
                updateStatus(status)
                applySearch()
            }

            progress.isVisible = false
            if (recommendations.isEmpty()) {
                status.setText(R.string.mint_discovery_empty)
                retry.isVisible = true
            } else {
                updateStatus(status)
            }
        }
    }

    private fun updateStatus(status: TextView) {
        status.text = resources.getQuantityString(
            R.plurals.mint_discovery_count,
            recommendations.size,
            recommendations.size,
        )
    }

    private fun loadProfile(url: String) {
        if (profileRequests.size >= NostrMintDiscovery.MAX_DISCOVERY_RESULTS) return
        if (!profileRequests.add(url)) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = profileSemaphore.withPermit {
                NostrMintDiscovery.fetchPublicMintProfile(url)
            }
            result?.name?.let { profileNames[url] = it }
            result?.iconBytes?.let { MintIconCache.cacheIcon(url, it) }
            recommendations[url]?.let { recommendation ->
                itemViews[url]?.let { bindRecommendation(it, recommendation) }
            }
            applySearch()
        }
    }

    private fun applySearch() {
        var visibleCount = 0
        recommendations.forEach { (url, recommendation) ->
            val searchableName = profileNames[url] ?: recommendation.name.orEmpty()
            val matches = searchQuery.isEmpty() ||
                url.lowercase(Locale.ROOT).contains(searchQuery) ||
                searchableName.lowercase(Locale.ROOT).contains(searchQuery)
            itemViews[url]?.isVisible = matches
            if (matches) visibleCount++
        }

        val status = view?.findViewById<TextView>(R.id.discovery_status) ?: return
        if (recommendations.isNotEmpty()) {
            if (searchQuery.isNotEmpty() && visibleCount == 0) {
                status.setText(R.string.mint_discovery_no_matches)
            } else {
                status.text = resources.getQuantityString(
                    R.plurals.mint_discovery_count,
                    visibleCount,
                    visibleCount,
                )
            }
        }
    }

    private fun bindRecommendation(
        view: View,
        recommendation: NostrMintDiscovery.MintRecommendation,
    ) {
        view.findViewById<TextView>(R.id.discovery_mint_name).text =
            profileNames[recommendation.url] ?: recommendation.name ?: recommendation.url
                .removePrefix("https://")
                .removePrefix("http://")
        view.findViewById<TextView>(R.id.discovery_mint_url).text = recommendation.url
            .removePrefix("https://")
            .removePrefix("http://")

        val reviews = view.findViewById<TextView>(R.id.discovery_mint_reviews)
        reviews.text = when {
            recommendation.averageRating != null -> getString(
                R.string.mint_discovery_rating,
                String.format(Locale.getDefault(), "%.1f", recommendation.averageRating),
                recommendation.reviewCount,
            )
            recommendation.reviewCount > 0 -> resources.getQuantityString(
                R.plurals.mint_discovery_recommendations,
                recommendation.reviewCount,
                recommendation.reviewCount,
            )
            else -> getString(R.string.mint_discovery_announced)
        }

        val icon = view.findViewById<ImageView>(R.id.discovery_mint_icon)
        val cachedIcon = MintIconCache.getCachedIconFile(recommendation.url)
        val bitmap = cachedIcon?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bitmap != null) {
            icon.imageTintList = null
            icon.setImageBitmap(bitmap)
            icon.clearColorFilter()
        } else {
            icon.setImageResource(R.drawable.ic_bitcoin)
            icon.setColorFilter(requireContext().getColor(R.color.numo_fluorescent_green))
        }

        view.findViewById<ImageView>(R.id.discovery_add_icon).setOnClickListener {
            listener?.onMintSelected(recommendation.url)
            dismiss()
        }
        view.setOnClickListener {
            listener?.onMintSelected(recommendation.url)
            dismiss()
        }
    }
}
