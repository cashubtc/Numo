package com.electricdreams.numo.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.electricdreams.numo.R

/** Requests precise location, then offers an explicit settings action if it is still denied. */
internal class LocationPermissionFix(
    private val activity: ComponentActivity,
    private val onLeaveForSettings: () -> Unit = {},
    private val onAnswered: () -> Unit,
) {

    private var denied = false

    init {
        activity.savedStateRegistry.registerSavedStateProvider(TAG) {
            Bundle().apply { putBoolean("denied", denied) }
        }
        activity.addOnContextAvailableListener {
            denied = activity.savedStateRegistry.consumeRestoredStateForKey(TAG)?.getBoolean("denied") == true
        }
    }

    val actionLabel: Int
        get() = if (denied) R.string.stripe_card_fix_app_settings else R.string.stripe_card_fix_location

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        denied = grants[Manifest.permission.ACCESS_FINE_LOCATION] != true
        onAnswered()
    }

    fun ask() {
        if (denied) {
            openAppSettings()
            return
        }
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
    }
}
