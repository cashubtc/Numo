package com.electricdreams.numo.ui.util

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.Window
import com.electricdreams.numo.R
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback

/**
 * A tapped transaction row grows into its details, and the details shrink back into the row:
 * Material's container transform between Activity or Sales and the transaction details.
 * Flat, as the app is: no shadow and no scrim over the list.
 */
object TransactionTransitions {

    private const val EXTRA_TRANSITION_NAME = "transaction_transition_name"

    /** A row's shared-element name; unique per transaction, so a rebound row still matches */
    fun nameFor(transactionId: String): String = "transaction_$transactionId"

    /** In a list whose rows open details. Call before `super.onCreate`. */
    fun prepareList(activity: Activity) {
        activity.window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        activity.setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        // The list stays in place under the growing row, rather than in the transition overlay
        activity.window.sharedElementsUseOverlay = false
    }

    /** Opens [intent] from [row], growing the row into the details when it has a name */
    fun open(activity: Activity, row: View, intent: Intent) {
        val name = row.transitionName
        if (name.isNullOrEmpty() || !row.isAttachedToWindow) {
            activity.startActivity(intent)
            return
        }
        intent.putExtra(EXTRA_TRANSITION_NAME, name)
        val options = ActivityOptions.makeSceneTransitionAnimation(activity, row, name)
        activity.startActivity(intent, options.toBundle())
    }

    /**
     * In the details. Call before `super.onCreate`; [attachDetail] after `setContentView`.
     * Opened any other way (the payment screen, the basket archive) it keeps the default.
     */
    fun prepareDetail(activity: Activity) {
        if (activity.intent.getStringExtra(EXTRA_TRANSITION_NAME) == null) return
        val window = activity.window
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        activity.setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementEnterTransition = transform(activity, entering = true)
        window.sharedElementReturnTransition = transform(activity, entering = false)
    }

    fun attachDetail(activity: Activity) {
        val name = activity.intent.getStringExtra(EXTRA_TRANSITION_NAME) ?: return
        activity.findViewById<View>(android.R.id.content).transitionName = name
    }

    private fun transform(activity: Activity, entering: Boolean) =
        // The theme supplies Material's emphasized easing; the durations are the app's own
        MaterialContainerTransform(activity, entering).apply {
            addTarget(android.R.id.content)
            duration = if (entering) 350L else 300L
            scrimColor = Color.TRANSPARENT
            isElevationShadowEnabled = false
            setAllContainerColors(activity.getColor(R.color.color_bg_white))
        }
}
