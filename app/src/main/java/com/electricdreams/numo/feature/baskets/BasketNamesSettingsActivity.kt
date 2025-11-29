package com.electricdreams.numo.feature.baskets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R

/**
 * Settings activity for configuring preset basket names.
 * 
 * Features:
 * - Add custom basket names (e.g., "Table 1", "John's Order")
 * - Remove individual names
 * - Clear all names
 * 
 * Apple-like design with clean UI and smooth interactions.
 */
class BasketNamesSettingsActivity : AppCompatActivity() {
    
    private lateinit var basketNamesManager: BasketNamesManager
    private lateinit var namesContainer: LinearLayout
    private lateinit var namesHeader: TextView
    private lateinit var namesCard: LinearLayout
    private lateinit var namesList: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var addNameButton: View
    private lateinit var clearAllButton: View
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_basket_names_settings)
        
        basketNamesManager = BasketNamesManager.getInstance(this)
        
        initViews()
        refreshNamesList()
    }
    
    private fun initViews() {
        // Back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        
        // Container views
        namesContainer = findViewById(R.id.names_container)
        namesHeader = findViewById(R.id.names_header)
        namesCard = findViewById(R.id.names_card)
        namesList = findViewById(R.id.names_list)
        emptyState = findViewById(R.id.empty_state)
        
        // Add name button
        addNameButton = findViewById(R.id.add_name_button)
        addNameButton.setOnClickListener { showAddNameDialog() }
        
        // Clear all button
        clearAllButton = findViewById(R.id.clear_all_button)
        clearAllButton.setOnClickListener { showClearAllConfirmation() }
    }
    
    private fun refreshNamesList() {
        namesList.removeAllViews()
        
        val names = basketNamesManager.getPresetNames()
        
        if (names.isEmpty()) {
            // Show empty state
            namesHeader.visibility = View.GONE
            namesCard.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            clearAllButton.visibility = View.GONE
        } else {
            // Show names list
            namesHeader.visibility = View.VISIBLE
            namesCard.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            clearAllButton.visibility = View.VISIBLE
            
            val inflater = LayoutInflater.from(this)
            
            names.forEachIndexed { index, name ->
                val itemView = inflater.inflate(R.layout.item_basket_name_preset, namesList, false)
                bindNameItem(itemView, index, name, names.size)
                namesList.addView(itemView)
                
                // Add divider between items (not after last)
                if (index < names.size - 1) {
                    addDivider()
                }
            }
        }
        
        // Update add button visibility
        addNameButton.visibility = if (basketNamesManager.canAddMore()) View.VISIBLE else View.GONE
    }
    
    private fun bindNameItem(view: View, index: Int, name: String, totalCount: Int) {
        val nameText = view.findViewById<TextView>(R.id.preset_name)
        val deleteButton = view.findViewById<ImageButton>(R.id.delete_button)
        
        nameText.text = name
        
        // Make the row clickable to edit
        view.setOnClickListener { showEditNameDialog(index, name) }
        
        // Delete button
        deleteButton.setOnClickListener { 
            showDeleteConfirmation(name)
        }
    }
    
    private fun addDivider() {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (0.5f * resources.displayMetrics.density).toInt()
            ).apply {
                marginStart = (16 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(resources.getColor(R.color.color_divider, theme))
        }
        namesList.addView(divider)
    }
    
    private fun showAddNameDialog() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_Numo_Dialog)
            .setView(R.layout.dialog_add_basket_name)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.setOnShowListener {
            val editText = dialog.findViewById<EditText>(R.id.name_input)
            val saveButton = dialog.findViewById<View>(R.id.save_button)
            val closeButton = dialog.findViewById<View>(R.id.close_button)

            saveButton?.setOnClickListener {
                val name = editText?.text.toString().trim()
                
                if (name.isBlank()) {
                    Toast.makeText(this, R.string.basket_names_error_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                if (basketNamesManager.addPresetName(name)) {
                    refreshNamesList()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, R.string.basket_names_error_duplicate, Toast.LENGTH_SHORT).show()
                }
            }

            closeButton?.setOnClickListener {
                dialog.dismiss()
            }

            // Show keyboard
            editText?.requestFocus()
            editText?.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }

        dialog.show()
    }
    
    private fun showEditNameDialog(index: Int, currentName: String) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_Numo_Dialog)
            .setView(R.layout.dialog_edit_basket_name)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        dialog.setOnShowListener {
            val editText = dialog.findViewById<EditText>(R.id.name_input)
            val saveButton = dialog.findViewById<View>(R.id.save_button)
            val closeButton = dialog.findViewById<View>(R.id.close_button)

            // Pre-fill
            editText?.setText(currentName)
            editText?.setSelection(currentName.length)

            saveButton?.setOnClickListener {
                val name = editText?.text.toString().trim()
                
                if (name.isBlank()) {
                    Toast.makeText(this, R.string.basket_names_error_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                basketNamesManager.updatePresetName(index, name)
                refreshNamesList()
                dialog.dismiss()
            }

            closeButton?.setOnClickListener {
                dialog.dismiss()
            }

            // Show keyboard
            editText?.requestFocus()
            editText?.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 300)
        }

        dialog.show()
    }
    
    private fun showDeleteConfirmation(name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.basket_names_dialog_delete_title)
            .setMessage(getString(R.string.basket_names_dialog_delete_message, name))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                basketNamesManager.removePresetName(name)
                refreshNamesList()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
    
    private fun showClearAllConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.basket_names_dialog_clear_all_title)
            .setMessage(R.string.basket_names_dialog_clear_all_message)
            .setPositiveButton(R.string.basket_names_dialog_clear_all_confirm) { _, _ ->
                basketNamesManager.clearAll()
                refreshNamesList()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
