package com.electricdreams.numo.core.bark

import android.content.Context
import android.util.Log
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.prefs.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.bark.*
import java.io.File

object BarkWalletManager {
    private const val TAG = "BarkWalletManager"
    private const val DB_DIR_NAME = "bark_wallet"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()

    @Volatile
    private var walletInstance: Wallet? = null

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        isInitialized = true

        val prefs = PreferenceStore.app(appContext)
        val barkEnabled = prefs.getBoolean("bark_enabled", false)

        if (barkEnabled) {
            scope.launch {
                try {
                    getWallet()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Bark Wallet on startup: ${e.message}", e)
                }
            }
        }
    }

    suspend fun getWallet(): Wallet? = initMutex.withLock {
        if (walletInstance == null) {
            initializeWallet()
        }
        walletInstance
    }

    private suspend fun initializeWallet(): Unit = withContext(Dispatchers.IO) {
        if (walletInstance != null) return@withContext

        val mnemonic = CashuWalletManager.getMnemonic()
        if (mnemonic.isNullOrBlank()) {
            Log.w(TAG, "Cannot initialize Bark Wallet: Mnemonic not available in CashuWalletManager")
            return@withContext
        }

        val dataDir = File(appContext.filesDir, DB_DIR_NAME)
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }

        val prefs = PreferenceStore.app(appContext)
        val networkStr = prefs.getString("bark_network") ?: "SIGNET"

        val network = when (networkStr.uppercase()) {
            "MAINNET" -> Network.BITCOIN
            "TESTNET" -> Network.TESTNET
            else -> Network.SIGNET
        }

        // Automatic fallback configurations for MAINNET and SIGNET
        val defaultServer = if (network == Network.BITCOIN) "https://ark.second.tech" else "https://ark.signet.2nd.dev"
        val defaultEsplora = if (network == Network.BITCOIN) "https://mempool.second.tech/api" else "https://esplora.signet.2nd.dev"

        val serverUrl = prefs.getString("bark_server_url")?.takeIf { it.isNotBlank() } ?: defaultServer
        val esploraUrl = prefs.getString("bark_esplora_url")?.takeIf { it.isNotBlank() } ?: defaultEsplora

        Log.i(TAG, "Initializing Bark Wallet with network=$network, server=$serverUrl, esplora=$esploraUrl")

        val config = Config(
            serverAddress = serverUrl,
            esploraAddress = esploraUrl,
            bitcoindAddress = null,
            bitcoindCookiefile = null,
            bitcoindUser = null,
            bitcoindPass = null,
            network = network,
            vtxoRefreshExpiryThreshold = null,
            vtxoExitMargin = null,
            htlcRecvClaimDelta = null,
            fallbackFeeRate = null,
            roundTxRequiredConfirmations = null,
            daemonFastSyncIntervalSecs = null,
            daemonSlowSyncIntervalSecs = null
        )

        try {
            val wallet = Wallet.create(
                mnemonic = mnemonic,
                config = config,
                datadir = dataDir.absolutePath,
                forceRescan = false
            )
            walletInstance = wallet
            Log.i(TAG, "Bark Wallet successfully created/loaded.")
            
            // Perform an initial sync in background
            scope.launch {
                runSyncAndMaintenance()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Bark Wallet: ${e.message}", e)
            throw e
        }
    }

    suspend fun runSyncAndMaintenance() = withContext(Dispatchers.IO) {
        val wallet = walletInstance ?: return@withContext
        try {
            Log.d(TAG, "Starting Bark Wallet sync...")
            wallet.sync()
            Log.d(TAG, "Starting Bark Wallet maintenance...")
            wallet.maintenance()
            Log.d(TAG, "Bark Wallet sync & maintenance completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync/maintain Bark Wallet: ${e.message}", e)
        }
    }

    fun isEnabled(): Boolean {
        if (!this::appContext.isInitialized) return false
        return PreferenceStore.app(appContext).getBoolean("bark_enabled", false)
    }

    @Synchronized
    fun disableWallet() {
        walletInstance = null
    }
}
