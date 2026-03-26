package com.electricdreams.numo.feature.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R

class ExplainerSlideAdapter : RecyclerView.Adapter<ExplainerSlideAdapter.SlideViewHolder>() {

    private data class Slide(val titleRes: Int, val bodyRes: Int)

    private val slides = listOf(
        Slide(R.string.explainer_slide1_title, R.string.explainer_slide1_body),
        Slide(R.string.explainer_slide2_title, R.string.explainer_slide2_body),
        Slide(R.string.explainer_slide3_title, R.string.explainer_slide3_body)
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
    }

    override fun getItemCount() = slides.size

    class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.slide_title)
        val body: TextView = view.findViewById(R.id.slide_body)
    }
}
