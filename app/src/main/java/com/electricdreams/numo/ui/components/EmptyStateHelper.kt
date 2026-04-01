package com.electricdreams.numo.ui.components

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.electricdreams.numo.R

object EmptyStateHelper {

    fun bind(
        view: View,
        icon: Int,
        title: String,
        description: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        view.findViewById<ImageView>(R.id.empty_state_icon).setImageResource(icon)
        view.findViewById<TextView>(R.id.empty_state_title).text = title
        view.findViewById<TextView>(R.id.empty_state_description).text = description

        val actionBtn = view.findViewById<TextView>(R.id.empty_state_action)
        if (actionLabel != null && onAction != null) {
            actionBtn.text = actionLabel
            actionBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_plus_small, 0, 0, 0)
            actionBtn.visibility = View.VISIBLE
            actionBtn.setOnClickListener { onAction() }
        } else {
            actionBtn.visibility = View.GONE
        }
    }
}
