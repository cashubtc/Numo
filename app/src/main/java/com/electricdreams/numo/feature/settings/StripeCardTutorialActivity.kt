package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityStripeCardTutorialBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

class StripeCardTutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStripeCardTutorialBinding

    private val slides = listOf(
        Slide(
            R.drawable.img_stripe_create_key,
            R.string.stripe_tutorial_key_title,
            R.string.stripe_tutorial_key_body,
            imageHeightDp = 240,
        ),
        Slide(
            R.drawable.img_stripe_create_location,
            R.string.stripe_tutorial_location_title,
            R.string.stripe_tutorial_location_body,
        ),
        Slide(
            R.drawable.img_numo_card_settings,
            R.string.stripe_tutorial_connect_title,
            R.string.stripe_tutorial_connect_body,
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStripeCardTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        binding.topBarBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.tutorialPager.adapter = SlideAdapter()
        binding.tutorialPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updatePage(position)
            },
        )
        binding.tutorialBackButton.setOnClickListener {
            if (binding.tutorialPager.currentItem == 0) finish()
            else binding.tutorialPager.currentItem -= 1
        }
        binding.tutorialNextButton.setOnClickListener {
            if (binding.tutorialPager.currentItem == slides.lastIndex) finish()
            else binding.tutorialPager.currentItem += 1
        }
        onBackPressedDispatcher.addCallback(this) {
            if (binding.tutorialPager.currentItem == 0) finish()
            else binding.tutorialPager.currentItem -= 1
        }
        updatePage(binding.tutorialPager.currentItem)
    }

    private fun updatePage(position: Int) {
        binding.tutorialProgress.text = getString(
            R.string.stripe_tutorial_progress,
            position + 1,
            slides.size,
        )
        binding.tutorialBackButton.setText(
            if (position == 0) R.string.stripe_tutorial_cancel else R.string.stripe_tutorial_back,
        )
        binding.tutorialNextButton.setText(
            if (position == slides.lastIndex) R.string.stripe_tutorial_done
            else R.string.stripe_tutorial_next,
        )
    }

    private data class Slide(
        @DrawableRes val imageRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int,
        val imageHeightDp: Int = 400,
    )

    private inner class SlideAdapter : RecyclerView.Adapter<SlideViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_stripe_tutorial_slide, parent, false)
            return SlideViewHolder(view)
        }

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            val slide = slides[position]
            holder.image.setImageResource(slide.imageRes)
            (holder.image.parent as View).layoutParams.height =
                (slide.imageHeightDp * holder.image.resources.displayMetrics.density).toInt()
            holder.title.setText(slide.titleRes)
            holder.body.setText(slide.bodyRes)
            ViewCompat.setAccessibilityHeading(holder.title, true)
        }

        override fun getItemCount(): Int = slides.size
    }

    private class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.tutorial_slide_image)
        val title: TextView = view.findViewById(R.id.tutorial_slide_title)
        val body: TextView = view.findViewById(R.id.tutorial_slide_body)
    }
}
