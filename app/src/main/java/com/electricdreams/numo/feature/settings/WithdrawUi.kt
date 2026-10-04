package com.electricdreams.numo.feature.settings

import android.view.View
import android.widget.Button
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.snackbar.Snackbar

import com.electricdreams.numo.core.model.Amount

/** Shared presentation helpers for the withdraw screens. */
internal object WithdrawUi {

    fun sats(value: Long): String = Amount(value, Amount.Currency.BTC).toString()

    /**
     * Shows work in progress inside the primary button itself: the label changes and
     * a small Material spinner appears, while the button keeps its size and colour so
     * nothing on the screen shifts.
     */
    fun setButtonBusy(button: Button, busy: Boolean, label: CharSequence) {
        button.text = label
        button.isClickable = !busy
        val materialButton = button as? MaterialButton ?: return
        if (busy) {
            val spec = CircularProgressIndicatorSpec(
                button.context,
                null,
                0,
                com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall
            )
            spec.indicatorColors = intArrayOf(button.currentTextColor)
            materialButton.icon = IndeterminateDrawable.createCircularDrawable(button.context, spec)
            materialButton.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            materialButton.iconTint = null
        } else {
            materialButton.icon = null
        }
    }

    /** Transient confirmation that floats above the bottom action area. */
    fun snackbar(anchor: View, message: CharSequence) {
        Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT)
            .setAnchorView(anchor)
            .show()
    }
}
