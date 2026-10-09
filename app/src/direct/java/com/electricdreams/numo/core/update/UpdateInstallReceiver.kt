package com.electricdreams.numo.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateController.getInstance(context).onInstallStatus(intent)
    }
}
