package com.electricdreams.numo.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Single source of truth for whether Numo can reach the internet.
 *
 * "Online" means the default network has internet capability **and** Android has validated it,
 * so captive portals and Wi-Fi without upstream count as offline. Going offline is debounced
 * by [OFFLINE_DEBOUNCE_MS] to ride out network handoffs; coming back online is immediate.
 */
class ConnectivityMonitor private constructor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rawOnline = MutableStateFlow(readCurrentState())

    /** Debounced connectivity used by every UI surface (offline strip, Charge, explainer). */
    val isOnline: StateFlow<Boolean> = rawOnline
        .debounceOffline(OFFLINE_DEBOUNCE_MS)
        .stateIn(scope, SharingStarted.Eagerly, true)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            rawOnline.value = capabilities.hasValidatedInternet()
        }

        override fun onLost(network: Network) {
            rawOnline.value = false
        }
    }

    init {
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // Registration can fail if the app exceeds the per-UID callback limit.
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun readCurrentState(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasValidatedInternet()
    }

    private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    companion object {
        private const val TAG = "ConnectivityMonitor"

        /** How long the network must stay down before the app treats itself as offline. */
        const val OFFLINE_DEBOUNCE_MS = 2_000L

        @Volatile
        private var instance: ConnectivityMonitor? = null

        @JvmStatic
        fun getInstance(context: Context): ConnectivityMonitor =
            instance ?: synchronized(this) {
                instance ?: ConnectivityMonitor(context.applicationContext).also { instance = it }
            }
    }
}

/** Emits `false` only after it has persisted for [delayMs]; emits `true` immediately. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.debounceOffline(delayMs: Long): Flow<Boolean> =
    distinctUntilChanged()
        .transformLatest { online ->
            if (!online) delay(delayMs)
            emit(online)
        }
        // A blip shorter than the debounce re-emits the state it interrupted.
        .distinctUntilChanged()
