package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintIconCache
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.ui.adapter.MintsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL

class MintsSettingsActivity : AppCompatActivity(), 
    MintsAdapter.MintRemoveListener,
    MintsAdapter.LightningMintSelectedListener,
    MintsAdapter.WithdrawListener {

    companion object {
        private const val TAG = "MintsSettingsActivity"
    }

    private lateinit var mintsRecyclerView: RecyclerView
    private lateinit var mintsAdapter: MintsAdapter
    private lateinit var newMintEditText: EditText
    private lateinit var addMintButton: Button
    private lateinit var addMintLoading: View
    private lateinit var resetMintsButton: View
    private lateinit var mintManager: MintManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mints_settings)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        // Initialize the mint icon cache
        MintIconCache.initialize(this)
        
        mintManager = MintManager.getInstance(this)

        mintsRecyclerView = findViewById(R.id.mints_recycler_view)
        newMintEditText = findViewById(R.id.new_mint_edit_text)
        addMintButton = findViewById(R.id.add_mint_button)
        addMintLoading = findViewById(R.id.add_mint_loading)
        resetMintsButton = findViewById(R.id.reset_mints_button)

        mintsRecyclerView.layoutManager = LinearLayoutManager(this)

        mintsAdapter = MintsAdapter(
            this,
            mintManager.getAllowedMints(), 
            this,
            this,
            mintManager.getPreferredLightningMint(),
            this
        )
        mintsRecyclerView.adapter = mintsAdapter
        
        // Check for mints that need refresh (older than 24 hours) or have no info
        refreshStaleMintsInfo()

        addMintButton.setOnClickListener { addNewMint() }
        resetMintsButton.setOnClickListener { resetMintsToDefaults() }

        newMintEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addNewMint()
                true
            } else {
                false
            }
        }

        // Load balances for all mints
        loadMintBalances()
    }

    override fun onResume() {
        super.onResume()
        // Refresh balances when returning to the activity
        loadMintBalances()
    }

    private fun loadMintBalances() {
        lifecycleScope.launch {
            val balances = withContext(Dispatchers.IO) {
                CashuWalletManager.getAllMintBalances()
            }
            Log.d(TAG, "Loaded ${balances.size} mint balances")
            mintsAdapter.setAllBalances(balances)
        }
    }

    /**
     * Refresh mint info for mints that are stale (older than 24 hours) or have no info.
     * Also downloads/refreshes icons for any mints with icon URLs.
     */
    private fun refreshStaleMintsInfo() {
        lifecycleScope.launch {
            val mintsToRefresh = mintManager.getMintsNeedingRefresh()
            
            if (mintsToRefresh.isEmpty()) {
                Log.d(TAG, "All mint info is fresh, no refresh needed")
                return@launch
            }
            
            Log.d(TAG, "Refreshing mint info for ${mintsToRefresh.size} mints")
            
            for (mintUrl in mintsToRefresh) {
                fetchAndStoreMintInfo(mintUrl, forceIconRefresh = true)
            }
            
            // Refresh adapter to show updated names and icons
            mintsAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Fetch mint info for all mints that don't have info stored yet.
     */
    private fun fetchAllMintInfo() {
        lifecycleScope.launch {
            for (mintUrl in mintManager.getAllowedMints()) {
                // Skip if we already have info for this mint
                if (mintManager.getMintInfo(mintUrl) != null) continue
                
                fetchAndStoreMintInfo(mintUrl)
            }
            // Refresh adapter to show names
            mintsAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Fetch and store mint info for a single mint.
     * Also downloads the mint icon if available.
     * 
     * @param mintUrl The mint URL to fetch info for
     * @param forceIconRefresh If true, re-download the icon even if cached
     */
    private suspend fun fetchAndStoreMintInfo(mintUrl: String, forceIconRefresh: Boolean = false) {
        withContext(Dispatchers.IO) {
            val info = CashuWalletManager.fetchMintInfo(mintUrl)
            if (info != null) {
                val json = CashuWalletManager.mintInfoToJson(info)
                mintManager.setMintInfo(mintUrl, json)
                
                // Update the refresh timestamp
                mintManager.setMintRefreshTimestamp(mintUrl)
                
                Log.d(TAG, "Stored mint info for $mintUrl: name=${info.name}, iconUrl=${info.iconUrl}")
                
                // Download and cache the icon if available
                val iconUrl = info.iconUrl
                if (!iconUrl.isNullOrEmpty()) {
                    if (forceIconRefresh) {
                        // Force re-download
                        MintIconCache.downloadAndCacheIcon(mintUrl, iconUrl)
                    } else {
                        // Only download if not already cached
                        MintIconCache.getOrDownloadIcon(mintUrl, iconUrl)
                    }
                }
            } else {
                Log.w(TAG, "Could not fetch mint info for $mintUrl")
            }
        }
    }

    private fun addNewMint() {
        val mintUrl = newMintEditText.text.toString().trim()
        if (TextUtils.isEmpty(mintUrl)) {
            Toast.makeText(this, "Please enter a valid mint URL", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading spinner and disable button
        addMintLoading.visibility = View.VISIBLE
        addMintButton.isEnabled = false
        newMintEditText.isEnabled = false

        // Validate the mint URL before adding
        lifecycleScope.launch {
            val isValid = validateMintUrl(mintUrl)
            if (!isValid) {
                // Hide loading spinner and re-enable button
                addMintLoading.visibility = View.GONE
                addMintButton.isEnabled = true
                newMintEditText.isEnabled = true

                Toast.makeText(
                    this@MintsSettingsActivity,
                    getString(R.string.mints_settings_error_invalid_url),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val added = mintManager.addMint(mintUrl)
            if (added) {
                mintsAdapter.updateMints(mintManager.getAllowedMints())
                mintsAdapter.setPreferredLightningMint(mintManager.getPreferredLightningMint())
                newMintEditText.setText("")
                
                // Fetch mint info first, then update UI
                fetchAndStoreMintInfo(mintUrl)
                
                // Load balances and notify adapter after mint info is fetched
                loadMintBalances()
                mintsAdapter.notifyDataSetChanged()
            } else {
                Toast.makeText(
                    this@MintsSettingsActivity,
                    getString(R.string.mints_settings_error_already_exists),
                    Toast.LENGTH_SHORT
                ).show()
            }
            
            // Hide loading spinner and re-enable button
            addMintLoading.visibility = View.GONE
            addMintButton.isEnabled = true
            newMintEditText.isEnabled = true
        }
    }

    private fun resetMintsToDefaults() {
        mintManager.resetToDefaults()
        mintsAdapter.updateMints(mintManager.getAllowedMints())
        mintsAdapter.setPreferredLightningMint(mintManager.getPreferredLightningMint())
        Toast.makeText(
            this,
            getString(R.string.mints_settings_toast_reset_defaults),
            Toast.LENGTH_SHORT
        ).show()
        // Reload all balances
        loadMintBalances()
    }

    override fun onMintRemoved(mintUrl: String) {
        if (mintManager.removeMint(mintUrl)) {
            mintsAdapter.updateMints(mintManager.getAllowedMints())
            // Update preferred Lightning mint in adapter (may have changed if removed mint was preferred)
            mintsAdapter.setPreferredLightningMint(mintManager.getPreferredLightningMint())
        }
    }

    override fun onLightningMintSelected(mintUrl: String) {
        if (mintManager.setPreferredLightningMint(mintUrl)) {
            mintsAdapter.setPreferredLightningMint(mintUrl)
            Toast.makeText(
                this,
                getString(R.string.mints_settings_toast_lightning_mint_selected),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onWithdrawClicked(mintUrl: String, balance: Long) {
        val intent = android.content.Intent(this, com.electricdreams.numo.feature.settings.WithdrawLightningActivity::class.java)
        intent.putExtra("mint_url", mintUrl)
        intent.putExtra("balance", balance)
        startActivity(intent)
    }

    /**
     * Validate a mint URL by checking if the /v1/info endpoint returns a 200 status.
     * Normalizes the URL before checking.
     */
    private suspend fun validateMintUrl(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Normalize the URL
            var normalizedUrl = rawUrl.trim()
            
            // Add https:// if no protocol specified
            if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
                normalizedUrl = "https://$normalizedUrl"
            }
            
            // Remove trailing slash if present
            if (normalizedUrl.endsWith("/")) {
                normalizedUrl = normalizedUrl.dropLast(1)
            }
            
            // Construct the info endpoint URL
            val infoUrl = "$normalizedUrl/v1/info"
            
            Log.d(TAG, "Validating mint URL: $infoUrl")
            
            // Make HTTP GET request
            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url(infoUrl)
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            val isValid = response.isSuccessful && response.code == 200
            
            Log.d(TAG, "Mint validation result: $isValid (status code: ${response.code})")
            response.close()
            
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error validating mint URL: ${e.message}", e)
            false
        }
    }
}
