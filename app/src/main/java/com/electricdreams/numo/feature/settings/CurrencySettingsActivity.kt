package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.util.CurrencyManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Currency

class CurrencySettingsActivity : AppCompatActivity() {

    private lateinit var currencyManager: CurrencyManager
    private lateinit var adapter: CurrencyAdapter
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var clearButton: ImageButton
    private lateinit var emptyStateText: TextView
    
    private var allCurrencies: List<Currency> = emptyList()

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
            currencyManager.setPreferredCurrency(currency.currencyCode)
            // Update UI to show selection
            adapter.submitList(getFilteredCurrencies(searchInput.text.toString()), currency.currencyCode)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadCurrencies() {
        // Load all available java currencies, sort them alphabetically
        allCurrencies = Currency.getAvailableCurrencies()
            .sortedBy { it.currencyCode }
            
        adapter.submitList(allCurrencies, currencyManager.getCurrentCurrency())
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
    
    private fun getFilteredCurrencies(query: String): List<Currency> {
        if (query.isEmpty()) return allCurrencies
        
        val lowerQuery = query.lowercase()
        return allCurrencies.filter {
            it.currencyCode.lowercase().contains(lowerQuery) ||
            it.getDisplayName(java.util.Locale.getDefault()).lowercase().contains(lowerQuery)
        }
    }
}
