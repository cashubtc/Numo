package com.electricdreams.numo.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ComponentSettingsRowBinding

/**
 * Standard clickable settings row: optional leading icon, title, optional
 * subtitle, optional trailing value text or chevron. Replaces the ~20
 * hand-rolled LinearLayout rows scattered across the settings screens.
 *
 * Declarative XML:
 *
 *     <com.electricdreams.numo.ui.components.SettingsRowView
 *         android:id="@+id/tips_settings_item"
 *         app:rowIcon="@drawable/ic_tip"
 *         app:rowTitle="@string/settings_item_tips_title" />
 *
 *     <com.electricdreams.numo.ui.components.SettingsRowView
 *         app:rowIcon="@drawable/ic_key"
 *         app:rowTitle="@string/security_settings_backup_mnemonic_title"
 *         app:rowSubtitle="@string/security_settings_backup_mnemonic_subtitle"
 *         app:rowShowChevron="true" />
 *
 * Host activities bind click handlers via the view id (just like the old
 * LinearLayout rows). Dynamic state (value text, icon swap) is controlled
 * programmatically via [setTitle], [setSubtitle], [setTrailingText],
 * [setTrailingIcon].
 */
class SettingsRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val titleView: TextView
    private val subtitleView: TextView
    private val trailingTextView: TextView
    private val trailingIconView: ImageView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPaddingRelative(
            resources.getDimensionPixelSize(R.dimen.margin_screen_horizontal),
            resources.getDimensionPixelSize(R.dimen.row_padding_vertical),
            resources.getDimensionPixelSize(R.dimen.margin_screen_horizontal),
            resources.getDimensionPixelSize(R.dimen.row_padding_vertical),
        )
        isClickable = true
        isFocusable = true
        if (background == null) {
            setBackgroundResource(R.drawable.bg_row_ripple)
        }

        val binding = ComponentSettingsRowBinding.inflate(LayoutInflater.from(context), this)
        iconView = binding.rowIcon
        titleView = binding.rowTitle
        subtitleView = binding.rowSubtitle
        trailingTextView = binding.rowTrailingText
        trailingIconView = binding.rowTrailingIcon
        ViewCompat.setScreenReaderFocusable(this, true)

        context.withStyledAttributes(attrs, R.styleable.SettingsRowView) {
            val iconRes = getResourceId(R.styleable.SettingsRowView_rowIcon, 0)
            if (iconRes != 0) {
                iconView.setImageResource(iconRes)
                iconView.visibility = View.VISIBLE
            }

            val iconBackground = getResourceId(R.styleable.SettingsRowView_rowIconBackground, 0)
            if (iconBackground != 0) {
                iconView.setBackgroundResource(iconBackground)
                val size = resources.getDimensionPixelSize(R.dimen.settings_icon_container_size)
                iconView.layoutParams = iconView.layoutParams.apply {
                    width = size
                    height = size
                }
                val padding = resources.getDimensionPixelSize(R.dimen.settings_icon_padding)
                iconView.setPadding(padding, padding, padding, padding)
                subtitleView.setTextColor(ContextCompat.getColor(context, R.color.m3_on_surface_variant))
            }

            val iconTintRes = getResourceId(R.styleable.SettingsRowView_rowIconTint, 0)
            if (iconTintRes != 0) {
                iconView.imageTintList = ContextCompat.getColorStateList(context, iconTintRes)
            }

            getString(R.styleable.SettingsRowView_rowTitle)?.let { titleView.text = it }

            val titleColorRes = getResourceId(R.styleable.SettingsRowView_rowTitleColor, 0)
            if (titleColorRes != 0) {
                titleView.setTextColor(ContextCompat.getColor(context, titleColorRes))
            }

            getString(R.styleable.SettingsRowView_rowSubtitle)?.let {
                subtitleView.text = it
                subtitleView.visibility = View.VISIBLE
            }

            getString(R.styleable.SettingsRowView_rowTrailingText)?.let {
                trailingTextView.text = it
                trailingTextView.visibility = View.VISIBLE
            }

            val trailingIconRes = getResourceId(R.styleable.SettingsRowView_rowTrailingIcon, 0)
            if (trailingIconRes != 0) {
                trailingIconView.setImageResource(trailingIconRes)
                trailingIconView.visibility = View.VISIBLE
            } else if (getBoolean(R.styleable.SettingsRowView_rowShowChevron, false)) {
                trailingIconView.visibility = View.VISIBLE
            }
        }
    }

    /** Text currently presented to the user, including dynamic setting summaries. */
    val searchableText: String
        get() = listOf(titleView.text, subtitleView.text, trailingTextView.text).joinToString(" ")

    fun setIcon(@DrawableRes res: Int) {
        iconView.setImageResource(res)
        iconView.visibility = View.VISIBLE
    }

    fun setIconTint(tint: ColorStateList?) {
        iconView.imageTintList = tint
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setSubtitle(text: CharSequence?) {
        subtitleView.text = text
        if (text.isNullOrEmpty()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.visibility = View.VISIBLE
        }
    }

    fun setTrailingText(text: CharSequence?) {
        trailingTextView.text = text
        if (text.isNullOrEmpty()) {
            trailingTextView.visibility = View.GONE
        } else {
            trailingTextView.visibility = View.VISIBLE
        }
    }

    fun setTrailingIcon(@DrawableRes res: Int?) {
        if (res == null || res == 0) {
            trailingIconView.visibility = View.GONE
        } else {
            trailingIconView.setImageResource(res)
            trailingIconView.visibility = View.VISIBLE
        }
    }

    fun showChevron(show: Boolean) {
        trailingIconView.visibility = if (show) View.VISIBLE else View.GONE
    }
}
