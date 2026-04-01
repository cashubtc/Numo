package com.electricdreams.numo.feature.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R

class ExplainerSlideAdapter : RecyclerView.Adapter<ExplainerSlideAdapter.SlideViewHolder>() {

    private companion object {
        const val SLIDE_PHONES = 0
        const val SLIDE_CUSTODY = 1
        const val SLIDE_ZERO_FEES = 2
    }

    private data class Slide(val titleRes: Int, val bodyRes: Int, val type: Int)

    private val slides = listOf(
        Slide(R.string.explainer_slide1_title, R.string.explainer_slide1_body, SLIDE_PHONES),
        Slide(R.string.explainer_slide2_title, R.string.explainer_slide2_body, SLIDE_CUSTODY),
        Slide(R.string.explainer_slide3_title, R.string.explainer_slide3_body, SLIDE_ZERO_FEES)
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_explainer_slide, parent, false)
        return SlideViewHolder(view)
    }

    override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
        val slide = slides[position]
        holder.title.setText(slide.titleRes)
        holder.body.setText(slide.bodyRes)

        val container = holder.illustrationContainer

        // Reset: hide all static views, remove any custom views
        holder.illustration.visibility = View.GONE
        holder.phoneLeft.visibility = View.GONE
        holder.phoneRight.visibility = View.GONE
        // Remove any previously added custom views
        val toRemove = mutableListOf<View>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is AutoCustodyAnimatedView || child is ZeroFeesIllustration) {
                toRemove.add(child)
            }
        }
        toRemove.forEach { container.removeView(it) }

        when (slide.type) {
            SLIDE_PHONES -> {
                holder.phoneLeft.visibility = View.VISIBLE
                holder.phoneRight.visibility = View.VISIBLE
                holder.phoneLeft.setImageResource(R.drawable.img_minibits_nfc)
                holder.phoneRight.setImageResource(R.drawable.img_numo_invoice)

                // Scale margins to screen width so phones stay close on narrow devices
                val screenWidth = holder.itemView.resources.displayMetrics.widthPixels
                val density = holder.itemView.resources.displayMetrics.density
                val inwardMargin = (screenWidth * 0.12f).toInt()   // ~12% of width
                val outwardMargin = -(screenWidth * 0.33f).toInt() // ~33% of width

                (holder.phoneLeft.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.marginStart = outwardMargin
                    it.marginEnd = inwardMargin
                    holder.phoneLeft.layoutParams = it
                }
                (holder.phoneRight.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.marginStart = inwardMargin
                    it.marginEnd = outwardMargin
                    holder.phoneRight.layoutParams = it
                }
            }

            SLIDE_CUSTODY -> {
                val custodyView = AutoCustodyAnimatedView(holder.itemView.context)
                custodyView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                container.addView(custodyView)
            }

            SLIDE_ZERO_FEES -> {
                val dp32 = (32 * holder.itemView.resources.displayMetrics.density).toInt()
                val zeroFees = ZeroFeesIllustration(holder.itemView.context)
                zeroFees.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    marginStart = dp32
                    marginEnd = dp32
                }
                container.addView(zeroFees)
            }
        }
    }

    override fun getItemCount() = slides.size

    class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.slide_title)
        val body: TextView = view.findViewById(R.id.slide_body)
        val illustrationContainer: FrameLayout = view.findViewById(R.id.slide_illustration_container)
        val illustration: ImageView = view.findViewById(R.id.slide_illustration)
        val phoneLeft: ImageView = view.findViewById(R.id.slide_phone_left)
        val phoneRight: ImageView = view.findViewById(R.id.slide_phone_right)
    }
}
