package com.electricdreams.numo.feature.onboarding

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R

class ExplainerSlideAdapter : RecyclerView.Adapter<ExplainerSlideAdapter.SlideViewHolder>() {

    private companion object {
        const val SLIDE_PHONES = 0
        const val SLIDE_CUSTODY = 1
        const val SLIDE_ZERO_FEES = 2
    }

    private data class Slide(
        val titleRes: Int,
        val bodyRes: Int,
        val type: Int
    )

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

        // Reset all views
        holder.illustration.visibility = View.GONE
        holder.phoneLeft.visibility = View.GONE
        holder.phoneRight.visibility = View.GONE

        when (slide.type) {
            SLIDE_PHONES -> {
                // Two real phone images entering from edges
                holder.phoneLeft.visibility = View.VISIBLE
                holder.phoneRight.visibility = View.VISIBLE
                holder.phoneLeft.setImageResource(R.drawable.img_minibits_nfc)
                holder.phoneRight.setImageResource(R.drawable.img_numo_invoice)
            }

            SLIDE_CUSTODY -> {
                holder.illustration.visibility = View.VISIBLE
                holder.illustration.setImageDrawable(AutoCustodyIllustration())
                // Animate in
                holder.illustration.alpha = 0f
                holder.illustration.translationY = 60f
                holder.illustration.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(700)
                    .setStartDelay(300)
                    .setInterpolator(DecelerateInterpolator(2f))
                    .start()
            }

            SLIDE_ZERO_FEES -> {
                // Replace illustration container contents with animated view
                val container = holder.illustrationContainer
                // Remove any previously added ZeroFees views (keep the 3 ImageViews)
                for (i in container.childCount - 1 downTo 0) {
                    val child = container.getChildAt(i)
                    if (child is ZeroFeesIllustration) container.removeViewAt(i)
                }
                val zeroFees = ZeroFeesIllustration(holder.itemView.context)
                zeroFees.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    marginStart = (32 * holder.itemView.resources.displayMetrics.density).toInt()
                    marginEnd = (32 * holder.itemView.resources.displayMetrics.density).toInt()
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
