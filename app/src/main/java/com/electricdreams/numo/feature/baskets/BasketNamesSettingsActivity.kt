package com.electricdreams.numo.feature.baskets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityBasketNamesSettingsBinding
import com.electricdreams.numo.ui.components.EmptyStateHelper
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

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

    private lateinit var binding: ActivityBasketNamesSettingsBinding

    private lateinit var basketNamesManager: BasketNamesManager
    private lateinit var namesContainer: LinearLayout
    private lateinit var namesHeader: TextView
    private lateinit var namesCard: LinearLayout
    private lateinit var namesList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var addNameButton: View
    private lateinit var clearAllButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBasketNamesSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        basketNamesManager = BasketNamesManager.getInstance(this)

        initViews()
        refreshNamesList()
    }

    private fun initViews() {
        binding.topBar.onNavClick { finish() }

        // Container views
        namesContainer = binding.namesContainer
        namesHeader = binding.namesHeader
        namesCard = binding.namesCard
        namesList = binding.namesList
        emptyState = binding.emptyState.root

        // Add name button
        addNameButton = binding.addNameButton
        addNameButton.setOnClickListener { showAddNameDialog() }

        // Clear all button
        clearAllButton = binding.clearAllButton
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
            EmptyStateHelper.bind(
                emptyState,
                R.drawable.ic_label,
                getString(R.string.basket_names_settings_empty_title),
                getString(R.string.basket_names_settings_empty_subtitle),
                "+ Add Name"
            ) { showAddNameDialog() }
            clearAllButton.visibility = View.GONE
            addNameButton.visibility = View.GONE
        } else {
            // Show names list
            namesHeader.visibility = View.VISIBLE
            namesCard.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            clearAllButton.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(this)

            names.forEachIndexed { index, name ->
                val itemView = inflater.inflate(R.layout.item_basket_name_preset, namesList, false)
                bindNameItem(itemView, index, name)
                namesList.addView(itemView)

                // Add divider between items (not after last)
                if (index < names.size - 1) {
                    addDivider()
                }
            }

            // Show add button only when items exist and can add more
            addNameButton.visibility = if (basketNamesManager.canAddMore()) View.VISIBLE else View.GONE
        }
    }

    private fun bindNameItem(view: View, index: Int, name: String) {
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
        DialogHelper.showInput(this, DialogHelper.InputConfig(
            title = getString(R.string.basket_names_dialog_add_title),
            description = getString(R.string.basket_names_dialog_add_subtitle),
            hint = getString(R.string.basket_names_dialog_add_hint),
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            saveText = getString(R.string.basket_names_dialog_add_button),
            validator = { value ->
                val name = value.trim()
                when {
                    name.isBlank() -> {
                        Toast.makeText(this, R.string.basket_names_error_empty,
                            Toast.LENGTH_SHORT).show()
                        false
                    }
                    !basketNamesManager.canAddMore() || basketNamesManager.getPresetNames()
                        .any { it.equals(name, ignoreCase = true) } -> {
                        Toast.makeText(this, R.string.basket_names_error_duplicate,
                            Toast.LENGTH_SHORT).show()
                        false
                    }
                    else -> true
                }
            },
            onSave = { name ->
                if (basketNamesManager.addPresetName(name.trim())) refreshNamesList()
            },
        ))
    }

    private fun showEditNameDialog(index: Int, currentName: String) {
        DialogHelper.showInput(this, DialogHelper.InputConfig(
            title = getString(R.string.basket_names_dialog_edit_title),
            description = getString(R.string.basket_names_dialog_edit_subtitle),
            hint = getString(R.string.basket_names_dialog_add_hint),
            initialValue = currentName,
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            saveText = getString(R.string.basket_names_dialog_edit_button),
            validator = { value ->
                if (value.isBlank()) {
                    Toast.makeText(this, R.string.basket_names_error_empty,
                        Toast.LENGTH_SHORT).show()
                    false
                } else {
                    true
                }
            },
            onSave = { name ->
                basketNamesManager.updatePresetName(index, name.trim())
                refreshNamesList()
            },
        ))
    }

    private fun showDeleteConfirmation(name: String) {
        DialogHelper.showConfirmation(this, DialogHelper.ConfirmationConfig(
            title = getString(R.string.basket_names_dialog_delete_title),
            message = getString(R.string.basket_names_dialog_delete_message, name),
            confirmText = getString(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                basketNamesManager.removePresetName(name)
                refreshNamesList()
            }
        ))
    }

    private fun showClearAllConfirmation() {
        DialogHelper.showConfirmation(this, DialogHelper.ConfirmationConfig(
            title = getString(R.string.basket_names_dialog_clear_all_title),
            message = getString(R.string.basket_names_dialog_clear_all_message),
            confirmText = getString(R.string.basket_names_dialog_clear_all_confirm),
            isDestructive = true,
            onConfirm = {
                basketNamesManager.clearAll()
                refreshNamesList()
            }
        ))
    }
}
