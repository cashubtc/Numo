package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.CurrencyManager

class CurrencySettingsActivity : AppCompatActivity() {

    private lateinit var currencyManager: CurrencyManager
    private lateinit var adapter: CurrencyAdapter
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var clearButton: ImageButton
    private lateinit var emptyStateText: TextView
    
    private var allCurrencies: List<CurrencyItem> = emptyList()
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
        adapter = CurrencyAdapter { currencyItem ->
            currencyManager.setPreferredCurrency(currencyItem.currencyCode)
            adapter.submitList(getFilteredCurrencies(searchInput.text.toString()), currencyItem.currencyCode)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadCurrencies() {
        allCurrencies = CurrencyItem.Custom.getAllCurrencies().toList()
            
        val currentList = getFilteredCurrencies(searchInput.text.toString())
        adapter.submitList(currentList, currencyManager.getCurrentCurrency())
        scrollToSelectedCurrencyOnce(currentList)
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
    
    private fun getFilteredCurrencies(query: String): List<CurrencyItem> {
        val filtered = if (query.isEmpty()) {
            allCurrencies
        } else {
            val lowerQuery = query.lowercase()
            allCurrencies.filter { item ->
                item.currencyCode.lowercase().contains(lowerQuery) ||
                when (item) {
                    is CurrencyItem.Standard -> item.currency.getDisplayName(java.util.Locale.getDefault()).lowercase().contains(lowerQuery)
                    is CurrencyItem.Custom -> item.displayName.lowercase().contains(lowerQuery)
                }
            }
        }
        
        return filtered.sortedWith { c1, c2 ->
            c1.currencyCode.compareTo(c2.currencyCode)
        }
    }

    private fun scrollToSelectedCurrencyOnce(list: List<CurrencyItem>) {
        if (hasScrolledToSelection || searchInput.text.toString().isNotEmpty()) return
        
        val currentCode = currencyManager.getCurrentCurrency()
        val index = list.indexOfFirst { it.currencyCode == currentCode }
        if (index != -1) {
            hasScrolledToSelection = true
            recyclerView.post {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                layoutManager?.scrollToPositionWithOffset(index, 100)
            }
        }
    }
}
