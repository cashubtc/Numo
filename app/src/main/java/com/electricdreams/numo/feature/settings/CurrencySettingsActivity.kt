package com.electricdreams.numo.feature.settings

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.CurrencyManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Currency

class CurrencySettingsActivity : AppCompatActivity() {

    private lateinit var currencyManager: CurrencyManager
    private lateinit var adapter: CurrencyAdapter
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var clearButton: ImageButton
    private lateinit var emptyStateText: TextView
    
    private var allCurrencies: List<CurrencyWrapper> = emptyList()
    private var hasScrolledToSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        currencyManager = CurrencyManager.getInstance(this)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        recyclerView = findViewById(R.id.currency_recycler_view)
        searchInput = findViewById(R.id.currency_search_input)
        clearButton = findViewById(R.id.clear_search_button)
        emptyStateText = findViewById(R.id.empty_state_text)

        setupRecyclerView()
        loadCurrencies()
        setupSearch()
    }

    private fun setupRecyclerView() {
        adapter = CurrencyAdapter { currency ->
            currencyManager.setPreferredCurrency(currency.code)
            // Update UI to show selection
            adapter.submitList(getFilteredCurrencies(searchInput.text.toString()), currency.code)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadCurrencies() {
        val prefs = getSharedPreferences("CurrencySettings", Context.MODE_PRIVATE)
        val cachedCurrencies = prefs.getStringSet("cached_supported_currencies", null)
        
        val initialSupportedCodes = cachedCurrencies ?: setOf(
            CurrencyManager.CURRENCY_USD, CurrencyManager.CURRENCY_EUR, 
            CurrencyManager.CURRENCY_GBP, CurrencyManager.CURRENCY_JPY, 
            CurrencyManager.CURRENCY_DKK, CurrencyManager.CURRENCY_SEK, 
            CurrencyManager.CURRENCY_NOK, CurrencyManager.CURRENCY_KRW
        )
        
        // Show cached or default immediately
        val standardCurrencies = Currency.getAvailableCurrencies()
            .filter { initialSupportedCodes.contains(it.currencyCode) }
            .map { CurrencyWrapper(it.currencyCode, it) }
            
        // Inject CUP and MLC directly (supported via Yadio API instead of Coinbase)
        // Coinbase provides inaccurate market rates for CUP and uses obsolete CUC instead of MLC.
        allCurrencies = standardCurrencies + listOf(
            CurrencyWrapper(CurrencyManager.CURRENCY_CUP, runCatching { Currency.getInstance(CurrencyManager.CURRENCY_CUP) }.getOrNull()),
            CurrencyWrapper(CurrencyManager.CURRENCY_MLC, runCatching { Currency.getInstance(CurrencyManager.CURRENCY_MLC) }.getOrNull())
        )
            
        val currentList = getFilteredCurrencies(searchInput.text.toString())
        adapter.submitList(currentList, currencyManager.getCurrentCurrency())
        scrollToSelectedCurrencyOnce(currentList)

        // Fetch latest supported currencies from Coinbase API
        lifecycleScope.launch(Dispatchers.IO) {
            val supported = mutableSetOf<String>()
            try {
                val url = URL("https://api.coinbase.com/v2/currencies")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    val dataArray = jsonObject.getJSONArray("data")
                    for (i in 0 until dataArray.length()) {
                        val currencyObj = dataArray.getJSONObject(i)
                        supported.add(currencyObj.getString("id"))
                    }
                    
                    // Always ensure our custom API fallback currencies are included
                    supported.add(CurrencyManager.CURRENCY_KRW)
                    supported.add(CurrencyManager.CURRENCY_JPY)
                    supported.add(CurrencyManager.CURRENCY_CUP)
                    supported.add(CurrencyManager.CURRENCY_MLC)
                    
                    withContext(Dispatchers.Main) {
                        prefs.edit().putStringSet("cached_supported_currencies", supported).apply()
                        
                        val newStandardCurrencies = Currency.getAvailableCurrencies()
                            .filter { supported.contains(it.currencyCode) }
                            .map { CurrencyWrapper(it.currencyCode, it) }
                            
                        // Inject CUP and MLC directly (supported via Yadio API instead of Coinbase)
                        // Coinbase provides inaccurate market rates for CUP and uses obsolete CUC instead of MLC.
                        allCurrencies = newStandardCurrencies + listOf(
                            CurrencyWrapper(CurrencyManager.CURRENCY_CUP, runCatching { Currency.getInstance(CurrencyManager.CURRENCY_CUP) }.getOrNull()),
                            CurrencyWrapper(CurrencyManager.CURRENCY_MLC, runCatching { Currency.getInstance(CurrencyManager.CURRENCY_MLC) }.getOrNull())
                        )
                            
                        val currentList = getFilteredCurrencies(searchInput.text.toString())
                        adapter.submitList(currentList, currencyManager.getCurrentCurrency())
                        scrollToSelectedCurrencyOnce(currentList)
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("CurrencySettings", "Error fetching supported currencies", e)
            }
        }
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                clearButton.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
                val filtered = getFilteredCurrencies(query)
                adapter.submitList(filtered, currencyManager.getCurrentCurrency())
                
                if (filtered.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyStateText.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyStateText.visibility = View.GONE
                }
            }
        })

        clearButton.setOnClickListener {
            searchInput.text = null
        }
    }
    
    private fun getFilteredCurrencies(query: String): List<CurrencyWrapper> {
        val currentCode = currencyManager.getCurrentCurrency()
        
        val filtered = if (query.isEmpty()) {
            allCurrencies
        } else {
            val lowerQuery = query.lowercase()
            allCurrencies.filter {
                it.code.lowercase().contains(lowerQuery) ||
                it.displayName.lowercase().contains(lowerQuery)
            }
        }
        
        // Sort alphabetically
        return filtered.sortedWith { c1, c2 ->
            c1.code.compareTo(c2.code)
        }
    }

    private fun scrollToSelectedCurrencyOnce(list: List<CurrencyWrapper>) {
        if (hasScrolledToSelection || searchInput.text.toString().isNotEmpty()) return
        
        val currentCode = currencyManager.getCurrentCurrency()
        val index = list.indexOfFirst { it.code == currentCode }
        if (index != -1) {
            hasScrolledToSelection = true
            recyclerView.post {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                // Use a small offset so it's not completely flush with the top edge
                layoutManager?.scrollToPositionWithOffset(index, 100)
            }
        }
    }
}
