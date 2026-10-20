package com.electricdreams.numo.core.util

import android.content.Context
import com.electricdreams.numo.core.network.ConnectivityMonitor
import kotlinx.coroutines.flow.Flow

/** Thin facade over [ConnectivityMonitor] so all callers share one debounced state. */
object NetworkUtils {
    fun isNetworkAvailable(context: Context): Boolean =
        ConnectivityMonitor.getInstance(context).isOnlineNow()

    fun observeNetworkState(context: Context): Flow<Boolean> =
        ConnectivityMonitor.getInstance(context).isOnline
}
