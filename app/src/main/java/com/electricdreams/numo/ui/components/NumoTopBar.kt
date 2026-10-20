package com.electricdreams.numo.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ComponentTopBarBinding

/**
 * Standard Numo top bar: nav icon on start, centered title, optional trailing
 * action icon. Renders with the same height, padding, background, and styling
 * as the hand-rolled ConstraintLayout top bars scattered across the app.
 *
 * Declarative XML:
 *
 *     <com.electricdreams.numo.ui.components.NumoTopBar
 *         android:id="@+id/top_bar"
 *         app:topBarTitle="@string/settings_title"
 *         app:topBarNavIcon="@drawable/ic_close"
 *         app:topBarActionIcon="@drawable/ic_refresh"
 *         app:topBarActionContentDescription="@string/reset" />
 *
 * Activities bind click handlers via [onNavClick] and [onActionClick]. The
 * action button is hidden by default and becomes visible whenever an icon
 * is assigned (XML or programmatic).
 */
class NumoTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val backButton: ImageButton
    private val titleView: TextView
    private val actionButton: ImageButton

    init {
        val binding = ComponentTopBarBinding.inflate(LayoutInflater.from(context), this)
        backButton = binding.topBarBack
        titleView = binding.topBarTitle
        actionButton = binding.topBarAction

        minHeight = resources.getDimensionPixelSize(R.dimen.top_bar_height)
        layoutParams?.height = resources.getDimensionPixelSize(R.dimen.top_bar_height)
        setPadding(
            resources.getDimensionPixelSize(R.dimen.space_l),
            paddingTop,
            resources.getDimensionPixelSize(R.dimen.space_l),
            paddingBottom
        )
        setBackgroundColor(ContextCompat.getColor(context, R.color.color_bg_white))

        context.withStyledAttributes(attrs, R.styleable.NumoTopBar) {
            if (getBoolean(R.styleable.NumoTopBar_topBarSettingsStyle, false)) {
                applySettingsStyle()
            }
            getString(R.styleable.NumoTopBar_topBarTitle)?.let { titleView.text = it }

            val navIconRes = getResourceId(R.styleable.NumoTopBar_topBarNavIcon, 0)
            if (navIconRes != 0) backButton.setImageResource(navIconRes)

            getString(R.styleable.NumoTopBar_topBarNavContentDescription)?.let {
                backButton.contentDescription = it
            }

            val actionIconRes = getResourceId(R.styleable.NumoTopBar_topBarActionIcon, 0)
            if (actionIconRes != 0) {
                actionButton.setImageResource(actionIconRes)
                actionButton.visibility = View.VISIBLE
            }

            getString(R.styleable.NumoTopBar_topBarActionContentDescription)?.let {
                actionButton.contentDescription = it
            }

            val actionTintRes = getResourceId(R.styleable.NumoTopBar_topBarActionTint, 0)
            if (actionTintRes != 0) {
                actionButton.imageTintList = ContextCompat.getColorStateList(context, actionTintRes)
            }
        }
    }

    private fun applySettingsStyle() {
        setBackgroundResource(R.color.settings_background)
        val padding = resources.getDimensionPixelSize(R.dimen.space_s)
        setPadding(padding, padding, padding, padding)
        backButton.setImageResource(R.drawable.ic_arrow_back)
        titleView.setTextAppearance(R.style.Text_SettingsPageTitle)
        titleView.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        titleView.maxLines = 2
        titleView.layoutParams = (titleView.layoutParams as LayoutParams).apply {
            width = 0
            startToStart = LayoutParams.UNSET
            endToEnd = LayoutParams.UNSET
            startToEnd = R.id.top_bar_back
            endToStart = R.id.top_bar_action
            marginStart = padding
            marginEnd = padding
        }
        ViewCompat.setAccessibilityHeading(titleView, true)
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setNavIcon(@DrawableRes res: Int) {
        backButton.setImageResource(res)
    }

    fun onNavClick(block: () -> Unit) {
        backButton.setOnClickListener { block() }
    }

    fun setNavEnabled(enabled: Boolean) {
        backButton.isEnabled = enabled
    }

    fun setActionIcon(@DrawableRes res: Int) {
        actionButton.setImageResource(res)
        actionButton.visibility = View.VISIBLE
    }

    fun setActionTint(tint: ColorStateList?) {
        actionButton.imageTintList = tint
    }

    fun onActionClick(block: () -> Unit) {
        actionButton.setOnClickListener { block() }
    }

    /** Exposed so callers can anchor PopupMenu / tooltip against the action icon. */
    val actionView: View get() = actionButton

    fun hideAction() {
        actionButton.visibility = View.GONE
    }

    fun showAction() {
        actionButton.visibility = View.VISIBLE
    }
}
