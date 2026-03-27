package com.electricdreams.numo.feature.onboarding

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R

class ExplainerSlideAdapter : RecyclerView.Adapter<ExplainerSlideAdapter.SlideViewHolder>() {

    private data class Slide(
        val titleRes: Int,
        val bodyRes: Int,
        val illustration: (() -> Drawable)?,
        val animateIn: Boolean = false
    )

    private val slides = listOf(
        Slide(R.string.explainer_slide1_title, R.string.explainer_slide1_body,
            illustration = { TapToGetPaidIllustration() }),
        Slide(R.string.explainer_slide2_title, R.string.explainer_slide2_body,
            illustration = { AutoCustodyIllustration() }, animateIn = true),
        Slide(R.string.explainer_slide3_title, R.string.explainer_slide3_body,
            illustration = null)
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

        slide.illustration?.let {
            holder.illustration.setImageDrawable(it())

            if (slide.animateIn) {
                // Start off-screen below and invisible, animate in elegantly
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

    override fun getItemCount() = slides.size

    class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.slide_title)
        val body: TextView = view.findViewById(R.id.slide_body)
        val illustration: ImageView = view.findViewById(R.id.slide_illustration)
    }
}
