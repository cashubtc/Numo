package com.electricdreams.numo.feature.onboarding

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R

class ExplainerSlideAdapter : RecyclerView.Adapter<ExplainerSlideAdapter.SlideViewHolder>() {

    private companion object {
        const val TYPE_DRAWABLE = 0
        const val TYPE_ZERO_FEES = 1
    }

    private data class Slide(
        val titleRes: Int,
        val bodyRes: Int,
        val illustration: (() -> Drawable)? = null,
        val animateIn: Boolean = false,
        val customViewType: Int = TYPE_DRAWABLE
    )

    private val slides = listOf(
        Slide(R.string.explainer_slide1_title, R.string.explainer_slide1_body,
            illustration = { TapToGetPaidIllustration() }),
        Slide(R.string.explainer_slide2_title, R.string.explainer_slide2_body,
            illustration = { AutoCustodyIllustration() }, animateIn = true),
        Slide(R.string.explainer_slide3_title, R.string.explainer_slide3_body,
            customViewType = TYPE_ZERO_FEES)
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

        if (slide.customViewType == TYPE_ZERO_FEES) {
            // Add the animated ZeroFeesIllustration view
            holder.illustration.visibility = View.GONE
            holder.illustrationContainer.removeAllViews()
            val zeroFees = ZeroFeesIllustration(holder.itemView.context)
            zeroFees.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            holder.illustrationContainer.addView(zeroFees)
        } else {
            holder.illustration.visibility = View.VISIBLE
            holder.illustrationContainer.removeAllViews()
            holder.illustrationContainer.addView(holder.illustration)

            slide.illustration?.let {
                holder.illustration.setImageDrawable(it())

                if (slide.animateIn) {
                    holder.illustration.alpha = 0f
                    holder.illustration.translationY = 60f
                    holder.illustration.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(700)
                        .setStartDelay(300)
                        .setInterpolator(DecelerateInterpolator(2f))
                        .start()
                } else {
                    holder.illustration.alpha = 1f
                    holder.illustration.translationY = 0f
                }
            }
        }
    }

    override fun getItemCount() = slides.size

    class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.slide_title)
        val body: TextView = view.findViewById(R.id.slide_body)
        val illustrationContainer: FrameLayout = view.findViewById(R.id.slide_illustration_container)
        val illustration: ImageView = view.findViewById(R.id.slide_illustration)
    }
}
