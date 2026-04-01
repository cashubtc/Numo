package com.electricdreams.numo.util

import android.app.Activity
import android.content.Intent

/**
 * Extension to safely start an activity for result,
 * hiding the deprecation warning.
 */
@Suppress("DEPRECATION")
fun Activity.startActivityForResultCompat(intent: Intent, requestCode: Int) {
    this.startActivityForResult(intent, requestCode)
}

/**
 * Extension to safely override pending transitions,
 * hiding the deprecation warning.
 */
@Suppress("DEPRECATION")
fun Activity.overridePendingTransitionCompat(enterAnim: Int, exitAnim: Int) {
    this.overridePendingTransition(enterAnim, exitAnim)
}
