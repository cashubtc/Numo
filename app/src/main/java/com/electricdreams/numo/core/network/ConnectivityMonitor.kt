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
 * "Online" means the default network has internet capability and isn't stuck behind a captive
 * portal. Android's own validation is deliberately not required: it fails on networks that
 * block Google's connectivity check (some countries, corporate firewalls) even though the mint
 * is reachable, and blocking a merchant from charging is worse than an occasional failed charge.
 * Going offline is debounced by [OFFLINE_DEBOUNCE_MS] to ride out network handoffs; coming back
 * online is immediate.
 */
class ConnectivityMonitor private constructor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rawOnline = MutableStateFlow(readCurrentState())

    /** Debounced connectivity used by every UI surface (offline strip, Charge, explainer). */
    val isOnline: StateFlow<Boolean> = rawOnline
        .debounceOffline(OFFLINE_DEBOUNCE_MS)
        // Seeded with the real state so a cold start offline isn't treated as online for 2s
        .stateIn(scope, SharingStarted.Eagerly, rawOnline.value)

    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            rawOnline.value = capabilities.isUsable()
        }

        override fun onLost(network: Network) {
            rawOnline.value = false
        }
    }

    init {
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            callbackRegistered = true
        } catch (e: RuntimeException) {
            // Registration can fail if the app exceeds the per-UID callback limit.
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /** Current connectivity for one-off checks; reads live if the callback never registered. */
    fun isOnlineNow(): Boolean = if (callbackRegistered) isOnline.value else readCurrentState()

    private fun readCurrentState(): Boolean = try {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        capabilities?.isUsable() == true
    } catch (e: RuntimeException) {
        // Some Android 11 builds throw SecurityException here; never block charging on that
        Log.e(TAG, "Failed to read network state", e)
        true
    }

    private fun NetworkCapabilities.isUsable(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)

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
