package com.electricdreams.numo.ui.offline

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.TypedValue
import com.electricdreams.numo.NfcEnableActivity
import com.electricdreams.numo.PaymentFailureActivity
import com.electricdreams.numo.PaymentReceivedActivity
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
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * App-wide offline strip, Bitkey style: one implementation, every screen.
 *
 * Follows [ConnectivityMonitor] and mirrors its state onto an [OfflineStripHost] per started
 * activity. Only the resumed activity animates; the others snap so they're correct when shown.
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

    private fun start() {
        app.registerActivityLifecycleCallbacks(this)
        scope.launch {
            ConnectivityMonitor.getInstance(app).isOnline.collect(::onConnectivityChanged)
        }
    }

    private fun onConnectivityChanged(online: Boolean) {
        hideJob?.cancel()
        when {
            !online -> setMode(OfflineStripView.Mode.OFFLINE)
            mode == OfflineStripView.Mode.OFFLINE -> {
                setMode(OfflineStripView.Mode.BACK_ONLINE)
                hideJob = scope.launch {
                    delay(BACK_ONLINE_HOLD_MS)
                    setMode(null)
                }
            }
        }
    }

    private fun setMode(newMode: OfflineStripView.Mode?) {
        mode = newMode
        val resumed = resumedActivity?.get()
        hosts.forEach { (activity, host) -> host.render(newMode, animate = activity === resumed) }
    }

    private fun onStripTapped(activity: Activity) {
        if (mode == OfflineStripView.Mode.OFFLINE) OfflineExplainerActivity.start(activity)
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
        private const val APP_PACKAGE = "com.electricdreams.numo"

        /** How long "Back online" stays up before the strip tucks away. */
        private const val BACK_ONLINE_HOLD_MS = 2_000L

        /** Splash/onboarding, transient result screens, camera scanners, and the explainer. */
        private val EXEMPT_ACTIVITIES: Set<Class<out Activity>> = setOf(
            OnboardingActivity::class.java,
            NfcEnableActivity::class.java,
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
