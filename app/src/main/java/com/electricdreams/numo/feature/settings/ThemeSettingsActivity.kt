package com.electricdreams.numo.feature.settings

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.materialswitch.MaterialSwitch

import com.electricdreams.numo.R
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.databinding.ActivityThemeSettingsBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

class ThemeSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeSettingsBinding

    companion object {
        const val PREF_THEME = "app_theme"
        const val THEME_OBSIDIAN = "obsidian"
        const val THEME_BITCOIN_ORANGE = "bitcoin_orange"
        const val THEME_GREEN = "green"
        const val THEME_WHITE = "white"
        private const val KEY_DARK_MODE = "darkMode"
    }

    private lateinit var themeRadioGroup: RadioGroup
    private lateinit var radioObsidian: RadioButton
    private lateinit var radioBitcoinOrange: RadioButton
    private lateinit var radioGreen: RadioButton
    private lateinit var radioWhite: RadioButton
    private lateinit var darkModeSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        binding.topBar.onNavClick { finish() }

        // Initialize dark mode switch
        darkModeSwitch = binding.darkModeSwitch
        val prefs = PreferenceStore.app(this)
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        darkModeSwitch.isChecked = isDarkMode

        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.putBoolean(KEY_DARK_MODE, isChecked)
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // Initialize theme radio buttons
        themeRadioGroup = binding.themeRadioGroup
        radioObsidian = binding.radioObsidian
        radioBitcoinOrange = binding.radioBitcoinOrange
        radioGreen = binding.radioGreen
        radioWhite = binding.radioWhite

        setSelectedTheme(getCurrentTheme())
        binding.themePreview.setTheme(getSelectedTheme(), animate = false)

        themeRadioGroup.setOnCheckedChangeListener { _, _ ->
            val selectedTheme = getSelectedTheme()
            saveTheme(selectedTheme)
            binding.themePreview.setTheme(selectedTheme)
        }

    }

    private fun getCurrentTheme(): String {
        return PreferenceStore.app(this)
            .getString(PREF_THEME, THEME_GREEN) ?: THEME_GREEN
    }

    private fun setSelectedTheme(theme: String) {
        when (theme) {
            THEME_BITCOIN_ORANGE -> radioBitcoinOrange.isChecked = true
            THEME_GREEN -> radioGreen.isChecked = true
            THEME_WHITE -> radioWhite.isChecked = true
            THEME_OBSIDIAN -> radioObsidian.isChecked = true
            else -> radioGreen.isChecked = true
        }
    }

    private fun getSelectedTheme(): String {
        val selectedId = themeRadioGroup.checkedRadioButtonId
        return when (selectedId) {
            R.id.radio_bitcoin_orange -> THEME_BITCOIN_ORANGE
            R.id.radio_green -> THEME_GREEN
            R.id.radio_white -> THEME_WHITE
            else -> THEME_OBSIDIAN
        }
    }

    private fun saveTheme(theme: String) {
        PreferenceStore.app(this).putString(PREF_THEME, theme)
    }
}
