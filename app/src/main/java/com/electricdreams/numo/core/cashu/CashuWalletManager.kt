package com.electricdreams.numo.core.cashu

import android.content.Context
import android.util.Log
import com.electricdreams.numo.core.util.MintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.MultiMintWallet
import org.cashudevkit.Wallet
import org.cashudevkit.WalletConfig
import org.cashudevkit.WalletDatabaseImpl
import org.cashudevkit.NoPointer
import org.cashudevkit.WalletSqliteDatabase
import org.cashudevkit.generateMnemonic

/**
 * Global owner of the CDK MultiMintWallet and its backing SQLite database.
 *
 * - Initialized from ModernPOSActivity.onCreate().
 * - Re-initialized whenever the allowed mint list changes.
 *
 * The wallet's mnemonic (seed phrase) and SQLite database are both
 * persisted so that balances survive app restarts.
 */
object CashuWalletManager : MintManager.MintChangeListener {

    private const val TAG = "CashuWalletManager"
    private const val PREFS_NAME = "CashuWalletPrefs"
    private const val KEY_MNEMONIC = "wallet_mnemonic"
    private const val DB_FILE_NAME = "cashu_wallet.db"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var database: WalletSqliteDatabase? = null

    @Volatile
    private var wallet: MultiMintWallet? = null

    /** Initialize from ModernPOSActivity. Safe to call multiple times. */
    fun init(context: Context) {
        if (this::appContext.isInitialized) return

        appContext = context.applicationContext
        val mintManager = MintManager.getInstance(appContext)

        // Listen for changes
        mintManager.setMintChangeListener(this)

        // Build initial wallet
        val initialMints = mintManager.getAllowedMints()
        scope.launch {
            rebuildWallet(initialMints)
        }
    }

    /**
     * Get the current wallet's mnemonic (seed phrase).
     * Returns null if wallet hasn't been initialized.
     */
    fun getMnemonic(): String? {
        if (!this::appContext.isInitialized) return null
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MNEMONIC, null)
    }

    /**
     * Restore wallet with a new mnemonic.
     * This will replace the current wallet with one derived from the provided seed phrase.
     * @param newMnemonic The 12-word seed phrase to restore from
     * @param onMintProgress Callback for progress updates: (mintUrl, status, balanceBefore, balanceAfter)
     * @return Map of mint URLs to their balance changes (newBalance - oldBalance)
     */
    suspend fun restoreFromMnemonic(
        newMnemonic: String,
        onMintProgress: suspend (mintUrl: String, status: String, balanceBefore: Long, balanceAfter: Long) -> Unit
    ): Map<String, Pair<Long, Long>> {
        if (!this::appContext.isInitialized) {
            throw IllegalStateException("CashuWalletManager not initialized")
        }

        val mintManager = MintManager.getInstance(appContext)
        val mints = mintManager.getAllowedMints()
        val balanceChanges = mutableMapOf<String, Pair<Long, Long>>()

        // Get balances before restore
        val balancesBefore = mutableMapOf<String, Long>()
        for (mintUrl in mints) {
            balancesBefore[mintUrl] = getBalanceForMint(mintUrl)
        }

        // Close existing wallet
        try {
            wallet?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing wallet during restore", t)
        }

        try {
            database?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing database during restore", t)
        }

        wallet = null
        database = null

        // Delete existing database to start fresh
        val dbFile = appContext.getDatabasePath(DB_FILE_NAME)
        if (dbFile.exists()) {
            dbFile.delete()
            Log.d(TAG, "Deleted existing wallet database")
        }
        // Save new mnemonic
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MNEMONIC, newMnemonic).apply()
        Log.i(TAG, "Saved new mnemonic for restore")

        // Recreate database
        dbFile.apply {
            parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
        }
        val db = WalletSqliteDatabase(dbFile.absolutePath)

        // Create new wallet with restored mnemonic
        val newWallet = MultiMintWallet(
            CurrencyUnit.Sat,
            newMnemonic,
            db,
        )

        // Add mints and restore each one
        val targetProofCount: UInt = 10u
        for (mintUrl in mints) {
            try {
                onMintProgress(mintUrl, "Connecting...", balancesBefore[mintUrl] ?: 0L, 0L)
                
                newWallet.addMint(MintUrl(mintUrl), targetProofCount)
                
                onMintProgress(mintUrl, "Restoring proofs...", balancesBefore[mintUrl] ?: 0L, 0L)
                
                // Use CDK's restore function to recover proofs
                val recoveredAmount = newWallet.restore(MintUrl(mintUrl))
                val newBalance = recoveredAmount.value.toLong()
                val oldBalance = balancesBefore[mintUrl] ?: 0L
                
                balanceChanges[mintUrl] = Pair(oldBalance, newBalance)
                
                onMintProgress(mintUrl, "Complete", oldBalance, newBalance)
                
                Log.d(TAG, "Restored mint $mintUrl: before=$oldBalance, after=$newBalance")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to restore mint $mintUrl", t)
                val oldBalance = balancesBefore[mintUrl] ?: 0L
                balanceChanges[mintUrl] = Pair(oldBalance, 0L)
                onMintProgress(mintUrl, "Failed: ${t.message}", oldBalance, 0L)
            }
        }

        database = db
        wallet = newWallet

        Log.d(TAG, "Wallet restore complete. Restored ${mints.size} mints.")
        return balanceChanges
    }

    /**
     * Create a temporary, single-mint wallet instance for interacting with a
     * mint that is not part of the main MultiMintWallet's allowed-mints set.
     *
     * This is used for swap-to-Lightning flows where we want to:
     *  - keep our main wallet and balances untouched, and
     *  - treat the unknown payer mint purely as a transient liquidity source.
     *
     * The temporary wallet:
     *  - Uses the same global mnemonic (so any resulting proofs can be
     *    understood by CDK), but
     *  - Stores its state in an in-memory SQLite database ("file::memory:")
     *    so it does not interfere with the persistent wallet database.
     */
    suspend fun getTemporaryWalletForMint(unknownMintUrl: String): Wallet {
        if (!this::appContext.isInitialized) {
            throw IllegalStateException("CashuWalletManager not initialized")
        }

        // For the temporary wallet we deliberately use a fresh random mnemonic
        // so that it is completely isolated from the main POS wallet.
        val tempMnemonic = generateMnemonic()

        // Use an in-memory SQLite database for temporary operations so it does
        // not interfere with the persistent wallet database.
        val tempDb = WalletSqliteDatabase.newInMemory()

        val config = WalletConfig(targetProofCount = 10u)

        return Wallet(
            unknownMintUrl,
            CurrencyUnit.Sat,
            tempMnemonic,
            tempDb,
            config
        )
    }

    override fun onMintsChanged(newMints: List<String>) {
        Log.d(TAG, "Mint list changed, rebuilding wallet with ${'$'}{newMints.size} mints")
        scope.launch {
            rebuildWallet(newMints)
        }
    }

    /** Current MultiMintWallet instance, or null if initialization failed or not complete. */
    fun getWallet(): MultiMintWallet? = wallet

    /** Current database instance, mostly for debugging or future use. */
    fun getDatabase(): WalletSqliteDatabase? = database

    /**
     * Get the balance for a specific mint in satoshis.
     */
    suspend fun getBalanceForMint(mintUrl: String): Long {
        val w = wallet ?: return 0L
        return try {
            val balanceMap = w.getBalances()
            balanceMap[mintUrl]?.value?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting balance for mint $mintUrl: ${e.message}", e)
            0L
        }
    }

    /**
     * Get balances for all configured mints.
     * Returns a map of mint URL string to balance in satoshis.
     */
    suspend fun getAllMintBalances(): Map<String, Long> {
        val w = wallet ?: return emptyMap()
        return try {
            val balanceMap = w.getBalances()
            balanceMap.mapValues { (_, amount) -> amount.value.toLong() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting mint balances: ${e.message}", e)
            emptyMap()
        }
    }

    /**
     * Fetch mint info from a mint URL using CDK.
     * Returns the MintInfo object, or null if it cannot be fetched.
     */
    suspend fun fetchMintInfo(mintUrl: String): org.cashudevkit.MintInfo? {
        val w = wallet ?: return null
        return try {
            w.fetchMintInfo(MintUrl(mintUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching mint info for $mintUrl: ${e.message}", e)
            null
        }
    }

    /**
     * Convert MintInfo to JSON string for storage.
     */
    fun mintInfoToJson(info: org.cashudevkit.MintInfo): String {
        val json = org.json.JSONObject()
        try {
            info.name?.let { json.put("name", it) }
            info.description?.let { json.put("description", it) }
            info.descriptionLong?.let { json.put("descriptionLong", it) }
            info.pubkey?.let { json.put("pubkey", it) }
            // Store version as object with name and version fields
            info.version?.let { version ->
                val versionObj = org.json.JSONObject()
                versionObj.put("name", version.name)
                versionObj.put("version", version.version)
                json.put("version", versionObj)
            }
            info.motd?.let { json.put("motd", it) }
            info.iconUrl?.let { json.put("iconUrl", it) }
            // Store contact info as array
            info.contact?.let { contacts ->
                val contactArray = org.json.JSONArray()
                for (contact in contacts) {
                    val contactObj = org.json.JSONObject()
                    contactObj.put("method", contact.method)
                    contactObj.put("info", contact.info)
                    contactArray.put(contactObj)
                }
                json.put("contact", contactArray)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error converting mint info to JSON", e)
        }
        return json.toString()
    }

    /**
     * Parse MintInfo from cached JSON string.
     * Returns a simple data holder or null if parsing fails.
     */
    fun mintInfoFromJson(jsonString: String): CachedMintInfo? {
        return try {
            val json = org.json.JSONObject(jsonString)
            
            // Parse version object
            val versionInfo = if (json.has("version") && !json.isNull("version")) {
                try {
                    val versionObj = json.getJSONObject("version")
                    CachedVersionInfo(
                        name = if (versionObj.has("name") && !versionObj.isNull("name")) versionObj.getString("name") else null,
                        version = if (versionObj.has("version") && !versionObj.isNull("version")) versionObj.getString("version") else null
                    )
                } catch (e: Exception) {
                    // Legacy: version stored as string
                    null
                }
            } else null
            
            // Parse contact array
            val contacts = if (json.has("contact") && !json.isNull("contact")) {
                try {
                    val contactArray = json.getJSONArray("contact")
                    (0 until contactArray.length()).mapNotNull { i ->
                        val contactObj = contactArray.getJSONObject(i)
                        val method = if (contactObj.has("method")) contactObj.getString("method") else null
                        val info = if (contactObj.has("info")) contactObj.getString("info") else null
                        if (method != null && info != null) CachedContactInfo(method, info) else null
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()
            
            CachedMintInfo(
                name = if (json.has("name") && !json.isNull("name")) json.getString("name") else null,
                description = if (json.has("description") && !json.isNull("description")) json.getString("description") else null,
                descriptionLong = if (json.has("descriptionLong") && !json.isNull("descriptionLong")) json.getString("descriptionLong") else null,
                versionInfo = versionInfo,
                motd = if (json.has("motd") && !json.isNull("motd")) json.getString("motd") else null,
                iconUrl = if (json.has("iconUrl") && !json.isNull("iconUrl")) json.getString("iconUrl") else null,
                contact = contacts
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing cached mint info", e)
            null
        }
    }

    /**
     * Simple data class to hold cached version info.
     */
    data class CachedVersionInfo(
        val name: String?,
        val version: String?
    )

    /**
     * Simple data class to hold cached contact info.
     */
    data class CachedContactInfo(
        val method: String,
        val info: String
    )

    /**
     * Simple data class to hold cached mint info.
     */
    data class CachedMintInfo(
        val name: String?,
        val description: String?,
        val descriptionLong: String?,
        val versionInfo: CachedVersionInfo?,
        val motd: String?,
        val iconUrl: String?,
        val contact: List<CachedContactInfo> = emptyList()
    )

    /**
     * Rebuild wallet + database using the provided mint URLs.
     * Runs on our IO coroutine scope.
     */
    private suspend fun rebuildWallet(mints: List<String>) {
        try {
            // Close any previous instances
            try {
                wallet?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing previous wallet", t)
            }

            try {
                database?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing previous DB", t)
            }

            wallet = null
            database = null

            if (mints.isEmpty()) {
                Log.w(TAG, "No allowed mints configured, skipping wallet init")
                return
            }

            // 1) Open or create the on-disk SQLite database.
            val dbFile = appContext.getDatabasePath(DB_FILE_NAME).apply {
                parentFile?.let { parent ->
                    if (!parent.exists()) {
                        parent.mkdirs()
                    }
                }
            }
            val db = WalletSqliteDatabase(dbFile.absolutePath)

            // 2) Load or create the mnemonic (seed phrase).
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var mnemonic = prefs.getString(KEY_MNEMONIC, null)
            if (mnemonic.isNullOrBlank()) {
                mnemonic = generateMnemonic()
                // Persist immediately so the same seed is reused on future launches.
                prefs.edit().putString(KEY_MNEMONIC, mnemonic).apply()
                Log.i(TAG, "Generated and stored new wallet mnemonic")
            } else {
                Log.i(TAG, "Loaded existing wallet mnemonic from preferences")
            }

            // 3) Construct MultiMintWallet in sats.
            val newWallet = MultiMintWallet(
                CurrencyUnit.Sat,
                mnemonic,
                db,
            )

            // 4) Register allowed mints with a default target proof count.
            val targetProofCount: UInt = 10u
            for (url in mints) {
                try {
                    newWallet.addMint(MintUrl(url), targetProofCount)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to add mint to wallet: ${'$'}url", t)
                }
            }

            database = db
            wallet = newWallet

            Log.d(TAG, "Initialized MultiMintWallet with ${'$'}{mints.size} mints; DB=${'$'}{dbFile.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize MultiMintWallet", t)
        }
    }
}
