package com.electricdreams.numo.ui.offline

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.electricdreams.numo.PaymentFailureActivity
import com.electricdreams.numo.PaymentReceivedActivity
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.core.network.ConnectivityMonitor
import com.electricdreams.numo.feature.items.BarcodeScannerActivity
import com.electricdreams.numo.feature.items.CheckoutScannerActivity
import com.electricdreams.numo.feature.offline.OfflineExplainerActivity
import com.electricdreams.numo.feature.onboarding.OnboardingActivity
import com.electricdreams.numo.feature.scanner.QRScannerActivity
import com.electricdreams.numo.feature.settings.WithdrawSuccessActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * App-wide offline strip, Bitkey style: one implementation, every screen.
 *
 * Follows [ConnectivityMonitor] and mirrors its state onto an [OfflineStripHost] per started
 * activity. Only the resumed activity animates; the others snap so they're correct when shown.
 *
 * While online it also says when NFC is switched off. No internet wins when both are true: it
 * stops every new charge, NFC only the tapped ones.
 */
class OfflineStripController private constructor(
    private val app: Application
) : Application.ActivityLifecycleCallbacks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Started, non-exempt activities; removed again in [onActivityDestroyed]. */
    private val hosts = HashMap<Activity, OfflineStripHost>()
    private var resumedActivity: WeakReference<Activity>? = null
    private var mode: OfflineStripView.Mode? = null
    private var hideJob: Job? = null

    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshNfc()
    }

    private fun start() {
        app.registerActivityLifecycleCallbacks(this)
        try {
            // Only the system may send this broadcast, so receiving it "exported" lets nobody
            // else in. The not-exported flag would need an extra permission below Android 13.
            ContextCompat.registerReceiver(
                app,
                nfcReceiver,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: Exception) {
            // Each screen still re-reads the NFC state when it resumes.
            Log.w(TAG, "Could not listen for NFC being switched on or off", e)
        }
        _nfcOff.value = isNfcOff()
        if (nfcOff.value) setMode(restingMode())
        scope.launch {
            ConnectivityMonitor.getInstance(app).isOnline.collect(::onConnectivityChanged)
        }
    }

    private fun onConnectivityChanged(online: Boolean) {
        hideJob?.cancel()
        when {
            !online -> setMode(OfflineStripView.Mode.OFFLINE)
            mode != OfflineStripView.Mode.OFFLINE -> Unit
            // The explainer celebrates on its own; a second "Back online" after it closes
            // would only flash, so the screens underneath simply settle back.
            resumedActivity?.get() is OfflineExplainerActivity -> setMode(restingMode())
            else -> {
                setMode(OfflineStripView.Mode.BACK_ONLINE)
                hideJob = scope.launch {
                    delay(BACK_ONLINE_HOLD_MS)
                    setMode(restingMode())
                }
            }
        }
    }

    /** What the strip shows when the connection has nothing to say. */
    private fun restingMode(): OfflineStripView.Mode? =
        if (nfcOff.value) OfflineStripView.Mode.NFC_OFF else null

    /** A device without an NFC chip has nothing to switch on, so it is never "off". */
    private fun isNfcOff(): Boolean = try {
        NfcAdapter.getDefaultAdapter(app)?.isEnabled == false
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the NFC state", e)
        false
    }

    private fun refreshNfc() {
        val off = isNfcOff()
        if (off == nfcOff.value) return
        _nfcOff.value = off
        // The connection states keep the strip until they are done with it.
        if (mode == null || mode == OfflineStripView.Mode.NFC_OFF) setMode(restingMode())
    }

    private fun setMode(newMode: OfflineStripView.Mode?) {
        mode = newMode
        _stripMode.value = newMode
        val resumed = resumedActivity?.get()
        hosts.forEach { (activity, host) -> host.render(newMode, animate = activity === resumed) }
    }

    private fun onStripTapped(activity: Activity) {
        when (mode) {
            OfflineStripView.Mode.OFFLINE -> OfflineExplainerActivity.start(activity)
            OfflineStripView.Mode.NFC_OFF -> try {
                activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            } catch (e: Exception) {
                Log.w(TAG, "Could not open NFC settings", e)
            }
            else -> Unit
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (isExempt(activity)) return
        val host = hosts.getOrPut(activity) {
            OfflineStripHost(activity) { onStripTapped(activity) }.also { it.attach() }
        }
        host.render(mode, animate = false)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        // The broadcast can be missed while the app is in the background.
        refreshNfc()
        hosts[activity]?.onResumed()
    }

    override fun onActivityDestroyed(activity: Activity) {
        hosts.remove(activity)?.detach()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun isExempt(activity: Activity): Boolean {
        if (!activity.javaClass.name.startsWith(APP_PACKAGE)) return true
        if (activity.javaClass in EXEMPT_ACTIVITIES) return true
        // Dialog-style and translucent windows float over another screen that already shows it.
        if (activity.window.isFloating) return true
        val translucent = TypedValue()
        return activity.theme.resolveAttribute(android.R.attr.windowIsTranslucent, translucent, true) &&
            translucent.data != 0
    }

    companion object {
        private const val TAG = "OfflineStripController"
        private const val APP_PACKAGE = "com.electricdreams.numo"

        /** How long "Back online" stays up before the strip tucks away. */
        private const val BACK_ONLINE_HOLD_MS = 2_000L

        private val _nfcOff = MutableStateFlow(false)
        val nfcOff = _nfcOff.asStateFlow()

        private val _stripMode = MutableStateFlow<OfflineStripView.Mode?>(null)

        /** What the strip is showing app-wide; screens that can't host it mirror this instead. */
        val stripMode: StateFlow<OfflineStripView.Mode?> = _stripMode.asStateFlow()

        /**
         * Splash/onboarding, transient result screens, camera scanners, the explainer, and the
         * payment request, which shows its own header pill so the QR never moves mid-scan.
         */
        private val EXEMPT_ACTIVITIES: Set<Class<out Activity>> = setOf(
            OnboardingActivity::class.java,
            PaymentRequestActivity::class.java,
            PaymentReceivedActivity::class.java,
            PaymentFailureActivity::class.java,
            WithdrawSuccessActivity::class.java,
            BarcodeScannerActivity::class.java,
            CheckoutScannerActivity::class.java,
            QRScannerActivity::class.java,
            OfflineExplainerActivity::class.java,
        )

        @Volatile
        private var instance: OfflineStripController? = null

        @JvmStatic
        fun install(app: Application) {
            if (instance != null) return
            synchronized(this) {
                if (instance == null) {
                    instance = OfflineStripController(app).also { it.start() }
                }
            }
        }
    }
}
