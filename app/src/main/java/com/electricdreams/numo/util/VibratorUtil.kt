package com.electricdreams.numo.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Extension to safely get a Vibrator across different Android versions,
 * handling the deprecation of Context.VIBRATOR_SERVICE in API 31.
 */
fun Context.getVibrator(): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = this.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager?
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        this.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    }
}

/**
 * Extension to safely vibrate for a specific duration across Android versions.
 */
fun Vibrator.vibrateCompat(milliseconds: Long) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        this.vibrate(milliseconds)
    }
}

/**
 * Extension to safely vibrate a pattern across Android versions.
 */
fun Vibrator.vibrateCompat(pattern: LongArray, repeat: Int = -1) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        this.vibrate(VibrationEffect.createWaveform(pattern, repeat))
    } else {
        @Suppress("DEPRECATION")
        this.vibrate(pattern, repeat)
    }
}
