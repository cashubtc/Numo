package com.electricdreams.shellshock.feature.tips

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.electricdreams.shellshock.R

/**
 * Settings activity for configuring tip options.
 * 
 * Features:
 * - Enable/disable tips
 * - Configure up to 4 preset tip percentages
 * - Reset to defaults (5%, 10%, 15%, 20%)
 */
class TipsSettingsActivity : AppCompatActivity() {
    
    private lateinit var tipsManager: TipsManager
    private lateinit var tipsEnabledSwitch: SwitchCompat
    private lateinit var presetsContainer: View
    private lateinit var presetsList: LinearLayout
    private lateinit var addPresetButton: View
    private lateinit var resetDefaultsButton: View
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tips_settings)
        
        tipsManager = TipsManager.getInstance(this)
        
        initViews()
        loadSettings()
    }
    
    private fun initViews() {
        // Back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        
        // Tips enabled switch
        tipsEnabledSwitch = findViewById(R.id.tips_enabled_switch)
        tipsEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            tipsManager.tipsEnabled = isChecked
            updatePresetsVisibility(isChecked)
        }
        
        // Presets container
        presetsContainer = findViewById(R.id.presets_container)
        presetsList = findViewById(R.id.presets_list)
        
        // Add preset button
        addPresetButton = findViewById(R.id.add_preset_button)
        addPresetButton.setOnClickListener { showAddPresetDialog() }
        
        // Reset defaults button
        resetDefaultsButton = findViewById(R.id.reset_defaults_button)
        resetDefaultsButton.setOnClickListener { showResetConfirmation() }
    }
    
    private fun loadSettings() {
        // Load enabled state
        val tipsEnabled = tipsManager.tipsEnabled
        tipsEnabledSwitch.isChecked = tipsEnabled
        updatePresetsVisibility(tipsEnabled)
        
        // Load presets
        refreshPresetsList()
    }
    
    private fun updatePresetsVisibility(tipsEnabled: Boolean) {
        presetsContainer.visibility = if (tipsEnabled) View.VISIBLE else View.GONE
    }
    
    private fun refreshPresetsList() {
        presetsList.removeAllViews()
        
        val presets = tipsManager.getTipPresets()
        val inflater = LayoutInflater.from(this)
        
        presets.forEachIndexed { index, percentage ->
            val itemView = inflater.inflate(R.layout.item_tip_preset, presetsList, false)
            bindPresetItem(itemView, index, percentage, presets.size)
            presetsList.addView(itemView)
            
            // Add divider between items (not after last)
            if (index < presets.size - 1) {
                addDivider()
            }
        }
        
        // Update add button visibility
        addPresetButton.visibility = if (tipsManager.canAddMorePresets()) View.VISIBLE else View.GONE
    }
    
    private fun bindPresetItem(view: View, index: Int, percentage: Int, totalCount: Int) {
        val percentageText = view.findViewById<TextView>(R.id.preset_percentage)
        val deleteButton = view.findViewById<ImageButton>(R.id.delete_button)
        
        percentageText.text = "$percentage%"
        
        // Make the row clickable to edit
        view.setOnClickListener { showEditPresetDialog(index, percentage) }
        
        // Delete button (only show if more than 1 preset)
        deleteButton.visibility = if (totalCount > 1) View.VISIBLE else View.GONE
        deleteButton.setOnClickListener { 
            deletePreset(percentage)
        }
    }
    
    private fun addDivider() {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (0.5f * resources.displayMetrics.density).toInt()
            ).apply {
                marginStart = (56 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(resources.getColor(R.color.color_divider, theme))
        }
        presetsList.addView(divider)
    }
    
    private fun showAddPresetDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Enter percentage (1-100)"
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt()
            )
        }
        
        AlertDialog.Builder(this)
            .setTitle("Add Tip Preset")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val percentageStr = input.text.toString()
                val percentage = percentageStr.toIntOrNull()
                
                if (percentage == null || percentage !in 1..100) {
                    Toast.makeText(this, "Please enter a valid percentage (1-100)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (tipsManager.addPreset(percentage)) {
                    refreshPresetsList()
                } else {
                    Toast.makeText(this, "Could not add preset (may already exist or at max capacity)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showEditPresetDialog(index: Int, currentPercentage: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentPercentage.toString())
            setSelection(text.length)
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt()
            )
        }
        
        AlertDialog.Builder(this)
            .setTitle("Edit Tip Preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val percentageStr = input.text.toString()
                val percentage = percentageStr.toIntOrNull()
                
                if (percentage == null || percentage !in 1..100) {
                    Toast.makeText(this, "Please enter a valid percentage (1-100)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                tipsManager.updatePreset(index, percentage)
                refreshPresetsList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun deletePreset(percentage: Int) {
        AlertDialog.Builder(this)
            .setTitle("Remove Preset")
            .setMessage("Remove $percentage% from tip presets?")
            .setPositiveButton("Remove") { _, _ ->
                tipsManager.removePreset(percentage)
                refreshPresetsList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Reset to Defaults")
            .setMessage("Reset tip presets to default values (5%, 10%, 15%, 20%)?")
            .setPositiveButton("Reset") { _, _ ->
                tipsManager.resetToDefaults()
                refreshPresetsList()
                Toast.makeText(this, "Presets reset to defaults", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
