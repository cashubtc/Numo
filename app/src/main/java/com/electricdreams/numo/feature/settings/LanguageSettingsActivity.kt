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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class LanguageSettingsActivity : AppCompatActivity() {

    private lateinit var languageRadioGroup: RadioGroup
    private lateinit var radioEnglish: RadioButton
    private lateinit var radioSpanish: RadioButton
    private lateinit var radioPortuguese: RadioButton
    private lateinit var radioKorean: RadioButton
    private lateinit var radioJapanese: RadioButton
    private lateinit var currentLanguageSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        languageRadioGroup = findViewById(R.id.language_radio_group)
        radioEnglish = findViewById(R.id.radio_english)
        radioSpanish = findViewById(R.id.radio_spanish)
        radioPortuguese = findViewById(R.id.radio_portuguese)
        radioKorean = findViewById(R.id.radio_korean)
        radioJapanese = findViewById(R.id.radio_japanese)
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
                "pt" -> "pt"
                "ko" -> "ko"
                "ja" -> "ja"
                else -> "en"
            }
        }

        when (currentLangCode) {
            "es" -> radioSpanish.isChecked = true
            "pt" -> radioPortuguese.isChecked = true
            "ko" -> radioKorean.isChecked = true
            "ja" -> radioJapanese.isChecked = true
            else -> radioEnglish.isChecked = true
        }

        updateSummary(currentLangCode)

        languageRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newCode = when (checkedId) {
                R.id.radio_spanish -> "es"
                R.id.radio_portuguese -> "pt"
                R.id.radio_korean -> "ko"
                R.id.radio_japanese -> "ja"
                else -> "en"
            }
            applyLanguage(newCode)
            updateSummary(newCode)
        }
    }

    private fun applyLanguage(langCode: String) {
        val locales = when (langCode) {
            "es" -> LocaleListCompat.forLanguageTags("es")
            "pt" -> LocaleListCompat.forLanguageTags("pt")
            "ko" -> LocaleListCompat.forLanguageTags("ko")
            "ja" -> LocaleListCompat.forLanguageTags("ja")
            else -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun updateSummary(langCode: String) {
        val name = when (langCode) {
            "es" -> getString(R.string.language_settings_option_spanish)
            "pt" -> getString(R.string.language_settings_option_portuguese)
            "ko" -> getString(R.string.language_settings_option_korean)
            "ja" -> getString(R.string.language_settings_option_japanese)
            else -> getString(R.string.language_settings_option_english)
        }
        currentLanguageSummary.text = getString(R.string.language_settings_summary_current, name)
    }
}
