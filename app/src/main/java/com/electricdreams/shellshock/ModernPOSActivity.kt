package com.electricdreams.shellshock

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Vibrator
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.shellshock.core.cashu.CashuWalletManager
import com.electricdreams.shellshock.core.worker.BitcoinPriceWorker
import com.electricdreams.shellshock.feature.history.PaymentsHistoryActivity
import com.electricdreams.shellshock.payment.PaymentMethodHandler
import com.electricdreams.shellshock.ui.components.PosUiCoordinator

class ModernPOSActivity : AppCompatActivity(), SatocashWallet.OperationFeedback {

    private var nfcAdapter: android.nfc.NfcAdapter? = null
    private var bitcoinPriceWorker: BitcoinPriceWorker? = null
    private var vibrator: Vibrator? = null
    
    private lateinit var uiCoordinator: PosUiCoordinator

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
        
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        window.setBackgroundDrawableResource(R.color.color_primary_green)
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

    // Lifecycle methods
    override fun onResume() {
        super.onResume()
        
        // Reapply theme when returning from settings
        uiCoordinator.applyTheme()
        
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
            if (resultCode == Activity.RESULT_OK && data != null) {
                val token = data.getStringExtra(PaymentRequestActivity.RESULT_EXTRA_TOKEN)
                val amount = data.getLongExtra(PaymentRequestActivity.RESULT_EXTRA_AMOUNT, 0L)
                val handledInternally =
                    data.getBooleanExtra(PaymentRequestActivity.EXTRA_HANDLED_INTERNALLY, false)

                // If the payment was fully handled inside PaymentRequestActivity
                // (pending entry completed + success UI shown), don't invoke
                // PaymentResultHandler again to avoid duplicate popups/history.
                if (!handledInternally && !token.isNullOrEmpty() && amount > 0) {
                    uiCoordinator.handlePaymentSuccess(token, amount)
                } else {
                    // Optionally reset UI after an externally handled payment
                    // uiCoordinator.resetAfterExternalPayment()
                }
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

    companion object {
        private const val TAG = "ModernPOSActivity"
        private const val PREFS_NAME = "ShellshockPrefs"
        private const val KEY_DARK_MODE = "darkMode"
    }
}
