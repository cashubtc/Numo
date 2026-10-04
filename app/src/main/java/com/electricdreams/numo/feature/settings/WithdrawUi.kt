package com.electricdreams.numo.feature.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.snackbar.Snackbar

import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager

/** Shared presentation helpers for the withdraw screens. */
internal object WithdrawUi {

    private const val TRANSITION_MS = 220L

    fun sats(value: Long): String = Amount(value, Amount.Currency.BTC).toString()

    /**
     * "21 sat available" for a mint the merchant can send from. When another funded mint
     * shares its display name, the mint's host is added so the two can be told apart.
     */
    fun sourceCaption(context: Context, mintUrl: String, balance: Long, fundedMints: Collection<String>): String {
        val mintManager = MintManager.getInstance(context)
        val name = mintManager.getMintDisplayName(mintUrl)
        val ambiguous = fundedMints.any { it != mintUrl && mintManager.getMintDisplayName(it) == name }
        return if (ambiguous) {
            val host = mintUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            context.getString(R.string.withdraw_from_available_host, sats(balance), host)
        } else {
            context.getString(R.string.withdraw_from_available, sats(balance))
        }
    }

    /**
     * Animates the next layout change under [root]: views that appear or disappear fade
     * while everything they push moves with them, together rather than in sequence, so
     * a state change never makes the content below it jump.
     */
    fun animateLayoutChange(root: ViewGroup) {
        val transition = TransitionSet()
            .setOrdering(TransitionSet.ORDERING_TOGETHER)
            .addTransition(Fade())
            .addTransition(ChangeBounds())
            .setDuration(TRANSITION_MS)
            .setInterpolator(FastOutSlowInInterpolator())
        TransitionManager.beginDelayedTransition(root, transition)
    }

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
