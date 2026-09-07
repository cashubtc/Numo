package com.electricdreams.numo.feature.tips

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

import com.electricdreams.numo.R
import com.electricdreams.numo.databinding.ActivityTipsSettingsBinding
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Settings activity for configuring tip options.
 *
 * Features:
 * - Enable/disable tips
 * - Configure up to 4 preset tip percentages
 * - Reset to defaults (5%, 10%, 15%, 20%)
 */
class TipsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTipsSettingsBinding

    private lateinit var tipsManager: TipsManager
    private lateinit var tipsEnabledSwitch: MaterialSwitch
    private lateinit var presetsContainer: View
    private lateinit var presetsList: LinearLayout
    private lateinit var addPresetButton: View
    private lateinit var resetDefaultsButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTipsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        tipsManager = TipsManager.getInstance(this)

        initViews()
        loadSettings()
    }

    private fun initViews() {
        binding.topBar.onNavClick { finish() }

        // Tips enabled switch
        tipsEnabledSwitch = binding.tipsEnabledSwitch
        tipsEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            tipsManager.tipsEnabled = isChecked
            updatePresetsVisibility(isChecked)
        }

        // Presets container
        presetsContainer = binding.presetsContainer
        presetsList = binding.presetsList

        // Add preset button
        addPresetButton = binding.addPresetButton
        addPresetButton.setOnClickListener { showAddPresetDialog() }

        // Reset defaults button
        resetDefaultsButton = binding.resetDefaultsButton
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

        percentageText.text = getString(R.string.tip_percentage_format, percentage)

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
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.tips_dialog_add_preset_title),
                hint = getString(R.string.tips_dialog_add_preset_hint),
                suffix = "%",
                inputType = InputType.TYPE_CLASS_NUMBER,
                saveText = getString(R.string.tips_dialog_add_preset_positive),
                onSave = { value ->
                    val percentage = value.toIntOrNull()

                    if (percentage == null || percentage !in 1..100) {
                        Toast.makeText(this, R.string.tips_error_invalid_percentage, Toast.LENGTH_SHORT).show()
                        return@InputConfig
                    }

                    if (tipsManager.addPreset(percentage)) {
                        refreshPresetsList()
                    } else {
                        Toast.makeText(this, R.string.tips_error_could_not_add_preset, Toast.LENGTH_SHORT).show()
                    }
                },
                validator = { value ->
                    val percentage = value.toIntOrNull()
                    percentage != null && percentage in 1..100
                }
            )
        )
    }

    private fun showEditPresetDialog(index: Int, currentPercentage: Int) {
        DialogHelper.showInput(
            context = this,
            config = DialogHelper.InputConfig(
                title = getString(R.string.tips_dialog_edit_preset_title),
                initialValue = currentPercentage.toString(),
                suffix = "%",
                inputType = InputType.TYPE_CLASS_NUMBER,
                saveText = getString(R.string.tips_dialog_edit_preset_positive),
                onSave = { value ->
                    val percentage = value.toIntOrNull()

                    if (percentage == null || percentage !in 1..100) {
                        Toast.makeText(this, R.string.tips_error_invalid_percentage, Toast.LENGTH_SHORT).show()
                        return@InputConfig
                    }

                    tipsManager.updatePreset(index, percentage)
                    refreshPresetsList()
                },
                validator = { value ->
                    val percentage = value.toIntOrNull()
                    percentage != null && percentage in 1..100
                }
            )
        )
    }

    private fun deletePreset(percentage: Int) {
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = getString(R.string.tips_dialog_remove_preset_title),
                message = getString(R.string.tips_dialog_remove_preset_message, percentage),
                confirmText = getString(R.string.tips_dialog_remove_preset_positive),
                isDestructive = true,
                onConfirm = {
                    tipsManager.removePreset(percentage)
                    refreshPresetsList()
                }
            )
        )
    }

    private fun showResetConfirmation() {
        DialogHelper.showConfirmation(
            context = this,
            config = DialogHelper.ConfirmationConfig(
                title = getString(R.string.tips_dialog_reset_defaults_title),
                message = getString(R.string.tips_dialog_reset_defaults_message),
                confirmText = getString(R.string.tips_dialog_reset_defaults_positive),
                isDestructive = false,
                onConfirm = {
                    tipsManager.resetToDefaults()
                    refreshPresetsList()
                    Toast.makeText(this, R.string.tips_toast_reset_to_defaults, Toast.LENGTH_SHORT).show()
                }
            )
        )
    }
}
