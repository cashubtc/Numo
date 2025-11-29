package com.electricdreams.numo.feature.baskets

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.SavedBasket
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.SavedBasketManager

/**
 * Activity for viewing and managing saved baskets (tabs/tables).
 */
class SavedBasketsActivity : AppCompatActivity() {

    private lateinit var savedBasketManager: SavedBasketManager
    private lateinit var currencyManager: CurrencyManager

    private lateinit var basketsRecyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var viewArchiveButton: TextView
    private lateinit var adapter: SavedBasketsAdapter

    companion object {
        const val RESULT_BASKET_LOADED = Activity.RESULT_FIRST_USER + 1
        const val EXTRA_BASKET_ID = "basket_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_baskets)

        initializeManagers()
        initializeViews()
        setupRecyclerView()
        loadBaskets()
    }

    override fun onResume() {
        super.onResume()
        loadBaskets()
    }

    private fun initializeManagers() {
        savedBasketManager = SavedBasketManager.getInstance(this)
        currencyManager = CurrencyManager.getInstance(this)
    }

    private fun initializeViews() {
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        basketsRecyclerView = findViewById(R.id.baskets_recycler_view)
        emptyView = findViewById(R.id.empty_view)
        
        // View Archive button in header
        viewArchiveButton = findViewById(R.id.view_archive_button)
        viewArchiveButton.setOnClickListener {
            startActivity(Intent(this, BasketArchiveActivity::class.java))
        }
        updateArchiveButtonVisibility()
    }

    private fun setupRecyclerView() {
        adapter = SavedBasketsAdapter(
            currencyManager = currencyManager,
            onBasketClick = { basket -> loadBasketForEditing(basket) },
            onRenameClick = { basket -> showRenameDialog(basket) },
            onDeleteClick = { basket -> showDeleteConfirmation(basket) }
        )

        basketsRecyclerView.layoutManager = LinearLayoutManager(this)
        basketsRecyclerView.adapter = adapter
    }

    private fun loadBaskets() {
        val baskets = savedBasketManager.getSavedBaskets()
        adapter.updateBaskets(baskets)

        if (baskets.isEmpty()) {
            basketsRecyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            basketsRecyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
        
        // Update archive button visibility
        updateArchiveButtonVisibility()
    }

    private fun loadBasketForEditing(basket: SavedBasket) {
        val intent = Intent().apply {
            putExtra(EXTRA_BASKET_ID, basket.id)
        }
        setResult(RESULT_BASKET_LOADED, intent)
        finish()
    }

    private fun showRenameDialog(basket: SavedBasket) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_Numo_Dialog)
            .setView(R.layout.dialog_rename_basket)
            .create()

        // Configure window for centered dialog
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.setOnShowListener {
            val editText = dialog.findViewById<EditText>(R.id.basket_name_input)
            val saveButton = dialog.findViewById<View>(R.id.save_button)
            val closeButton = dialog.findViewById<View>(R.id.close_button)

            // Pre-fill existing name
            editText?.setText(basket.name ?: "")
            editText?.setSelection(editText.text.length) // Cursor at end

            saveButton?.setOnClickListener {
                val newName = editText?.text.toString().trim().takeIf { it.isNotEmpty() }
                savedBasketManager.updateBasketName(basket.id, newName)
                loadBaskets()
                dialog.dismiss()
            }

            closeButton?.setOnClickListener {
                dialog.dismiss()
            }

            // Show keyboard for input
            editText?.requestFocus()
            editText?.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }

        dialog.show()
    }

    private fun showDeleteConfirmation(basket: SavedBasket) {
        val index = savedBasketManager.getBasketIndex(basket.id)
        val displayName = basket.getDisplayName(index)

        AlertDialog.Builder(this)
            .setTitle(R.string.saved_baskets_delete_title)
            .setMessage(getString(R.string.saved_baskets_delete_message, displayName))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                savedBasketManager.deleteBasket(basket.id)
                loadBaskets()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
    
    private fun updateArchiveButtonVisibility() {
        val archiveCount = savedBasketManager.getArchivedBasketCount()
        viewArchiveButton.visibility = if (archiveCount > 0) View.VISIBLE else View.GONE
    }
}
