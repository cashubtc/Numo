package com.electricdreams.numo.core.network

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.MintManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why new charges can't go through right now. [OFFLINE] outranks [MINT_UNREACHABLE]. */
enum class ConnectionProblem { OFFLINE, MINT_UNREACHABLE }

/**
 * Whether the mint that issues new charges answers, for the cases [ConnectivityMonitor] can't see:
 * Wi-Fi with nothing behind it, or the mint itself being down.
 *
 * The mint only counts as unreachable after [FAILURES_BEFORE_UNREACHABLE] probes in a row fail,
 * and a single answer restores it. Probing runs only while the app is on screen and the phone is
 * online; going offline clears the state, so reconnecting starts from a clean slate. This is a
 * warning, not a gate: charges go through the wallet's own HTTP stack, which may still succeed.
 */
class MintReachabilityMonitor internal constructor(
    private val isOnline: StateFlow<Boolean>,
    /** The mint to watch, or null when there is nothing to watch (no mint, or BTCPay checkout). */
    private val mintUrl: () -> String?,
    private val probe: suspend (String) -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val foreground = MutableStateFlow(false)
    private val probeNow = Channel<Unit>(Channel.CONFLATED)
    private val reachable = MutableStateFlow(true)
    private var probedUrl: String? = null
    private var failures = 0

    val isMintReachable: StateFlow<Boolean> = reachable.asStateFlow()

    /** The one thing every surface shows: null when charges should work. */
    val problem: StateFlow<ConnectionProblem?> = combine(isOnline, reachable, ::problemFor)
        .stateIn(scope, SharingStarted.Eagerly, problemFor(isOnline.value, reachable.value))

    init {
        scope.launch { watch() }
    }

    /** A request to the mint just failed: check now instead of waiting for the next tick. */
    fun reportFailure() {
        probeNow.trySend(Unit)
    }

    internal fun setForeground(inForeground: Boolean) {
        foreground.value = inForeground
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watch() {
        combine(isOnline, foreground, ::Pair).collectLatest { (online, inForeground) ->
            if (!online) {
                reset()
            } else if (inForeground) {
                probeUntilCancelled()
            }
        }
    }

    private suspend fun probeUntilCancelled() {
        while (true) {
            val url = mintUrl()
            if (url != probedUrl) {
                reset()
                probedUrl = url
            }
            if (url != null) record(probe(url))
            val wait = when {
                failures == 0 -> HEALTHY_INTERVAL_MS
                reachable.value -> RETRY_INTERVAL_MS
                else -> UNREACHABLE_INTERVAL_MS
            }
            withTimeoutOrNull(wait) { probeNow.receive() }
        }
    }

    private fun record(answered: Boolean) {
        if (answered) {
            failures = 0
            reachable.value = true
        } else {
            failures++
            if (failures >= FAILURES_BEFORE_UNREACHABLE) reachable.value = false
        }
    }

    private fun reset() {
        failures = 0
        reachable.value = true
    }

    private class ForegroundTracker(
        private val monitor: MintReachabilityMonitor
    ) : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            monitor.setForeground(true)
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            monitor.setForeground(started > 0)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        private const val TAG = "MintReachabilityMonitor"

        /** One dropped request is noise; two in a row is a mint the merchant should know about. */
        const val FAILURES_BEFORE_UNREACHABLE = 2
        const val HEALTHY_INTERVAL_MS = 60_000L
        const val RETRY_INTERVAL_MS = 5_000L
        const val UNREACHABLE_INTERVAL_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 5_000L

        @Volatile
        private var instance: MintReachabilityMonitor? = null

        @JvmStatic
        fun getInstance(context: Context): MintReachabilityMonitor =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        /** Starts watching for the life of the process; probes only while a screen is showing. */
        @JvmStatic
        fun install(app: Application) {
            app.registerActivityLifecycleCallbacks(ForegroundTracker(getInstance(app)))
        }

        private fun create(context: Context): MintReachabilityMonitor {
            val client = OkHttpClient.Builder()
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            return MintReachabilityMonitor(
                isOnline = ConnectivityMonitor.getInstance(context).isOnline,
                mintUrl = { watchedMint(context) },
                probe = { url -> mintAnswers(client, url) },
            )
        }

        private fun problemFor(online: Boolean, mintReachable: Boolean): ConnectionProblem? = when {
            !online -> ConnectionProblem.OFFLINE
            !mintReachable -> ConnectionProblem.MINT_UNREACHABLE
            else -> null
        }

        /** Mirrors PaymentServiceFactory: with BTCPay configured, charges never touch the mint. */
        private fun watchedMint(context: Context): String? = try {
            val prefs = PreferenceStore.app(context)
            val usesBtcPay = prefs.getBoolean("btcpay_enabled", false) &&
                listOf("btcpay_server_url", "btcpay_api_key", "btcpay_store_id")
                    .none { prefs.getString(it).isNullOrBlank() }
            if (usesBtcPay) null else MintManager.getInstance(context).getPreferredLightningMint()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to read the mint to watch", e)
            null
        }

        /**
         * True when the mint's info endpoint answers with JSON. A sign-in page that Android didn't
         * flag answers 200 with HTML, which must not pass for the mint.
         */
        internal suspend fun mintAnswers(client: OkHttpClient, mintUrl: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url("${mintUrl.trimEnd('/')}/v1/info").build()
                    client.newCall(request).execute().use { response ->
                        response.code == 200 &&
                            response.peekBody(PEEK_BYTES).string().trimStart().startsWith("{")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.d(TAG, "Mint did not answer: ${e.message}")
                    false
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Mint URL can't be probed", e)
                    false
                }
            }

        private const val PEEK_BYTES = 256L
    }
}
