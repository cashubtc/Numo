package com.electricdreams.numo
import com.electricdreams.numo.R

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Vibrator
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawProgressListener
import com.electricdreams.numo.feature.history.PaymentsHistoryActivity
import com.electricdreams.numo.payment.PaymentMethodHandler
import com.electricdreams.numo.ui.components.PosUiCoordinator

class ModernPOSActivity : AppCompatActivity(), SatocashWallet.OperationFeedback, AutoWithdrawProgressListener {

    private var nfcAdapter: android.nfc.NfcAdapter? = null
    private var bitcoinPriceWorker: BitcoinPriceWorker? = null
    private var vibrator: Vibrator? = null

    private lateinit var uiCoordinator: PosUiCoordinator
    private lateinit var autoWithdrawManager: AutoWithdrawManager

    // Auto-withdraw progress views (Dynamic Island style). These are optional so the
    // app can still run on layouts that don't include the progress indicator.
    private var progressContainer: View? = null
    private var dynamicIsland: View? = null
    private var progressSpinner: View? = null
    private var successIcon: View? = null
    private var errorIcon: View? = null
    private var progressTitle: TextView? = null
    private var progressSubtitle: TextView? = null
    private var progressAmount: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load and apply theme settings
        setupThemeSettings()
        
        // Initialize basic setup
        CashuWalletManager.init(this)
        setContentView(R.layout.activity_modern_pos)

        val paymentAmount = intent.getLongExtra("EXTRA_PAYMENT_AMOUNT", 0L)
        Log.d(TAG, "Created ModernPOSActivity with payment amount from basket: $paymentAmount")

        // Setup window settings
        setupWindowSettings()
        
        // Setup Bitcoin price worker
        setupBitcoinPriceWorker()
        
        // Setup NFC
        setupNfcAdapter()

        // Initialize UI coordinator which handles all UI logic
        uiCoordinator = PosUiCoordinator(this, bitcoinPriceWorker)
        uiCoordinator.initialize()

        // Setup auto-withdraw manager and progress indicator
        setupAutoWithdrawProgress()

        // Handle initial payment amount if provided
        uiCoordinator.handleInitialPaymentAmount(paymentAmount)
    }

    private fun setupThemeSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
        )
    }

    private fun setupWindowSettings() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Use the same resolved background color as ThemeManager so the
        // ModernPOS window background matches the active theme selection
        // (obsidian, green, bitcoin orange, white, etc.). This ensures the
        // navigation pill always floats above the correct themed background
        // instead of a hardcoded "green".
        val bgColor = com.electricdreams.numo.ui.theme.ThemeManager.resolveBackgroundColor(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        WindowInsetsControllerCompat(window, window.decorView).apply {
            // For the dark themes we keep icons light; for white theme the
            // ThemeManager will already have set light/dark appropriately.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, 0)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupBitcoinPriceWorker() {
        bitcoinPriceWorker = BitcoinPriceWorker.getInstance(this).also { worker ->
            worker.setPriceUpdateListener(object : BitcoinPriceWorker.PriceUpdateListener {
                override fun onPriceUpdated(price: Double) {
                    // Delegate to UI coordinator when price updates
                    // This will be handled via the display manager
                }
            })
            worker.start()
        }
    }

    private fun setupNfcAdapter() {
        nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    }

    private fun setupAutoWithdrawProgress() {
        autoWithdrawManager = AutoWithdrawManager.getInstance(this)
        autoWithdrawManager.setProgressListener(this)

        // Initialize progress views (Dynamic Island style). We scope lookups through the
        // included view root to avoid any surprises with the view hierarchy.
        val progressRoot: View? = findViewById(R.id.auto_withdraw_progress)

        if (progressRoot == null) {
            Log.w(TAG, "Auto-withdraw include view not found; Dynamic Island UI disabled for this layout")
            return
        }

        progressContainer = progressRoot.findViewById(R.id.auto_withdraw_progress_container) ?: progressRoot
        dynamicIsland = progressRoot.findViewById(R.id.dynamic_island)
        progressSpinner = progressRoot.findViewById(R.id.progress_spinner)
        successIcon = progressRoot.findViewById(R.id.success_icon)
        errorIcon = progressRoot.findViewById(R.id.error_icon)
        progressTitle = progressRoot.findViewById(R.id.progress_title)
        progressSubtitle = progressRoot.findViewById(R.id.progress_subtitle)
        progressAmount = progressRoot.findViewById(R.id.progress_amount)

        if (progressContainer == null || dynamicIsland == null) {
            Log.w(TAG, "Auto-withdraw progress views not found inside include; Dynamic Island UI disabled for this layout")
            return
        }

        // Initial state: hidden above screen
        progressContainer?.translationY = -200f
    }

    // Lifecycle methods
    override fun onResume() {
        super.onResume()
        
        // Reapply theme when returning from settings
        uiCoordinator.applyTheme()
        
        // Refresh display to update currency formatting when returning from settings
        uiCoordinator.refreshDisplay()
        
        nfcAdapter?.let { adapter ->
            val pendingIntent = PendingIntent.getActivity(
                this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_MUTABLE,
            )
            val techList = arrayOf(arrayOf(android.nfc.tech.IsoDep::class.java.name))
            adapter.enableForegroundDispatch(this, pendingIntent, null, techList)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        uiCoordinator.stopServices()
        bitcoinPriceWorker?.stop()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dialog layout handled by managers
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag: android.nfc.Tag? = intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                uiCoordinator.handleNfcPayment(tag)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PaymentMethodHandler.REQUEST_CODE_PAYMENT) {
            // Hide spinner regardless of result (success, cancel, or error)
            uiCoordinator.hideChargeButtonSpinner()
            
            // PaymentRequestActivity now handles showing PaymentReceivedActivity directly,
            // so we just need to reset the UI here
            if (resultCode == Activity.RESULT_OK) {
                uiCoordinator.resetToInputMode()
            }
        }
    }

    // Menu handling
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_top_up -> { startActivity(Intent(this, TopUpActivity::class.java)); true }
        R.id.action_balance_check -> { startActivity(Intent(this, BalanceCheckActivity::class.java)); true }
        R.id.action_history -> { startActivity(Intent(this, PaymentsHistoryActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    // SatocashWallet.OperationFeedback implementation
    override fun onOperationSuccess() { 
        runOnUiThread { 
            // Feedback handled by PaymentResultHandler
        } 
    }
    
    override fun onOperationError() { 
        runOnUiThread { 
            // Feedback handled by PaymentResultHandler  
        } 
    }

    // AutoWithdrawProgressListener implementation
    override fun onWithdrawStarted(mintUrl: String, amount: Long, lightningAddress: String) {
        runOnUiThread {
            if (progressContainer == null || dynamicIsland == null) return@runOnUiThread
            // Reset to progress state
            dynamicIsland?.setBackgroundResource(R.drawable.bg_dynamic_island)
            progressSpinner?.visibility = View.VISIBLE
            successIcon?.visibility = View.GONE
            errorIcon?.visibility = View.GONE
            
            // Format amount
            val amountFormatted = java.text.NumberFormat.getNumberInstance().format(amount)
            progressTitle?.text = getString(R.string.auto_withdraw_progress_sending)
            progressSubtitle?.text = lightningAddress
            progressAmount?.text = amountFormatted
            
            showProgressIndicator()
        }
    }

    override fun onWithdrawProgress(step: String, detail: String) {
        runOnUiThread {
            progressSubtitle?.text = detail
        }
    }

    override fun onWithdrawCompleted(mintUrl: String, amount: Long, fee: Long) {
        runOnUiThread {
            if (progressContainer == null || dynamicIsland == null) return@runOnUiThread
            // Switch to success state with green background
            dynamicIsland?.setBackgroundResource(R.drawable.bg_dynamic_island_success)
            progressSpinner?.visibility = View.GONE
            successIcon?.visibility = View.VISIBLE
            errorIcon?.visibility = View.GONE
            
            val amountFormatted = java.text.NumberFormat.getNumberInstance().format(amount)
            progressTitle?.text = getString(R.string.auto_withdraw_progress_completed)
            progressSubtitle?.text = "Fee: ${java.text.NumberFormat.getNumberInstance().format(fee)} sats"
            progressAmount?.text = amountFormatted
            
            // Haptic feedback for success
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            
            // Hide after 3 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                hideProgressIndicator()
            }, 3000)
        }
    }

    override fun onWithdrawFailed(mintUrl: String, error: String) {
        runOnUiThread {
            if (progressContainer == null || dynamicIsland == null) return@runOnUiThread
            // Switch to error state with red background
            dynamicIsland?.setBackgroundResource(R.drawable.bg_dynamic_island_error)
            progressSpinner?.visibility = View.GONE
            successIcon?.visibility = View.GONE
            errorIcon?.visibility = View.VISIBLE
            
            progressTitle?.text = getString(R.string.auto_withdraw_progress_failed)
            progressSubtitle?.text = error
            progressAmount?.visibility = View.GONE
            
            // Haptic feedback for error
            vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
            
            // Hide after 5 seconds (longer for errors)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                hideProgressIndicator()
                progressAmount?.visibility = View.VISIBLE
            }, 5000)
        }
    }

    private fun showProgressIndicator() {
        if (progressContainer == null || dynamicIsland == null) return

        progressContainer?.visibility = View.VISIBLE
        
        // Spring-like bounce animation from top
        progressContainer?.animate()?.apply {
            translationY(0f)
            alpha(1f)
            duration = 400
            interpolator = android.view.animation.OvershootInterpolator(0.8f)
            start()
        }
        
        // Scale the island up slightly
        dynamicIsland?.scaleX = 0.9f
        dynamicIsland?.scaleY = 0.9f
        dynamicIsland?.animate()?.apply {
            scaleX(1f)
            scaleY(1f)
            duration = 300
            interpolator = android.view.animation.OvershootInterpolator(1.5f)
            start()
        }
    }

    private fun hideProgressIndicator() {
        if (progressContainer == null || dynamicIsland == null) return
        // Shrink and slide up
        dynamicIsland?.animate()?.apply {
            scaleX(0.8f)
            scaleY(0.8f)
            duration = 200
            start()
        }

        progressContainer?.animate()?.apply {
            translationY(-200f)
            alpha(0f)
            duration = 300
            interpolator = android.view.animation.AccelerateInterpolator()
            withEndAction {
                progressContainer?.visibility = View.GONE
                // Reset scale for next use
                dynamicIsland?.scaleX = 1f
                dynamicIsland?.scaleY = 1f
            }
            start()
        }
    }

    companion object {
        private const val TAG = "ModernPOSActivity"
        private const val PREFS_NAME = "NumoPrefs"
        private const val KEY_DARK_MODE = "darkMode"
    }
}
