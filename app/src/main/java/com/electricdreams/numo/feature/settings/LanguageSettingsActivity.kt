package com.electricdreams.numo.feature.settings

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.electricdreams.numo.R

class LanguageSettingsActivity : AppCompatActivity() {

    private lateinit var languageRadioGroup: RadioGroup
    private lateinit var radioEnglish: RadioButton
    private lateinit var radioSpanish: RadioButton
    private lateinit var currentLanguageSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_settings)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        languageRadioGroup = findViewById(R.id.language_radio_group)
        radioEnglish = findViewById(R.id.radio_english)
        radioSpanish = findViewById(R.id.radio_spanish)
        currentLanguageSummary = findViewById(R.id.current_language_summary)

        val appLocales = AppCompatDelegate.getApplicationLocales()
        val currentLangCode = if (!appLocales.isEmpty) {
            appLocales[0]?.language ?: "en"
        } else {
            // Fallback to system default, but map to supported set
            val sys = if (Build.VERSION.SDK_INT >= 24) {
                resources.configuration.locales[0]
            } else {
                @Suppress("DEPRECATION") resources.configuration.locale
            }
            when (sys.language) {
                "es" -> "es"
                else -> "en"
            }
        }

        when (currentLangCode) {
            "es" -> radioSpanish.isChecked = true
            else -> radioEnglish.isChecked = true
        }

        updateSummary(currentLangCode)

        languageRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newCode = when (checkedId) {
                R.id.radio_spanish -> "es"
                else -> "en"
            }
            applyLanguage(newCode)
            updateSummary(newCode)
        }
    }

    private fun applyLanguage(langCode: String) {
        val locales = when (langCode) {
            "es" -> LocaleListCompat.forLanguageTags("es")
            else -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun updateSummary(langCode: String) {
        val name = when (langCode) {
            "es" -> getString(R.string.language_settings_option_spanish)
            else -> getString(R.string.language_settings_option_english)
        }
        currentLanguageSummary.text = getString(R.string.language_settings_summary_current, name)
    }
}
