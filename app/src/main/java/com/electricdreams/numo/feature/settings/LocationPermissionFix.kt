package com.electricdreams.numo.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for the location permission Stripe needs. Android stops showing its dialog once the
 * merchant has refused for good, and then answers at once; only in that case does this open
 * the app's settings page instead. A dialog that was merely dismissed is asked again next time.
 *
 * Create it as a property of the activity, so it registers before the activity starts.
 *
 * @param onLeaveForSettings called just before the app's settings page opens.
 * @param onAnswered called when the dialog was shown and answered, either way.
 */
internal class LocationPermissionFix(
    private val activity: ComponentActivity,
    private val onLeaveForSettings: () -> Unit = {},
    private val onAnswered: () -> Unit,
) {

    private var askedAt = 0L

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val answeredWithoutDialog = SystemClock.elapsedRealtime() - askedAt < DIALOG_MIN_MS
        if (!granted && answeredWithoutDialog) openAppSettings() else onAnswered()
    }

    fun ask() {
        askedAt = SystemClock.elapsedRealtime()
        launcher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    private fun openAppSettings() {
        try {
            onLeaveForSettings()
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the app's settings page", e)
            onAnswered()
        }
    }

    companion object {
        private const val TAG = "LocationPermissionFix"

        /**
         * A refusal faster than a dialog can be read and answered came from Android itself.
         * Set generously for slow tills: guessing "no dialog" wrongly only opens the settings
         * page, while guessing "dialog" wrongly would leave a button that appears to do nothing.
         */
        private const val DIALOG_MIN_MS = 800L
    }
}
