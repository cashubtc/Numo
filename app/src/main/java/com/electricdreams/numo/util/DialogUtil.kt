package com.electricdreams.numo.util

import android.content.Context
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

fun Context.createProgressDialog(message: String): AlertDialog {
    val padding = (20 * resources.displayMetrics.density).toInt()
    
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(padding, padding, padding, padding)
        gravity = android.view.Gravity.CENTER_VERTICAL
        
        addView(ProgressBar(context).apply {
            isIndeterminate = true
        })
        
        addView(TextView(context).apply {
            text = message
            textSize = 16f
            setPadding(padding, 0, 0, 0)
        })
    }
    
    return AlertDialog.Builder(this)
        .setCancelable(false)
        .setView(layout)
        .create()
}
