package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.feature.items.ItemListActivity
import com.electricdreams.numo.feature.tips.TipsSettingsActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        // Language settings (look up by name to avoid compile-time dependency if R is stale)
        val languageItemId = resources.getIdentifier("language_settings_item", "id", packageName)
        findViewById<View?>(languageItemId)?.setOnClickListener {
            startActivity(Intent(this, LanguageSettingsActivity::class.java))
        }

        findViewById<View>(R.id.theme_settings_item).setOnClickListener {
            startActivity(Intent(this, ThemeSettingsActivity::class.java))
        }

        findViewById<View>(R.id.currency_settings_item).setOnClickListener {
            startActivity(Intent(this, CurrencySettingsActivity::class.java))
        }

        findViewById<View>(R.id.mints_settings_item).setOnClickListener {
            startActivity(Intent(this, MintsSettingsActivity::class.java))
        }

        // Go directly to item list (merged with item settings)
        findViewById<View>(R.id.items_settings_item).setOnClickListener {
            startActivity(Intent(this, ItemListActivity::class.java))
        }

        // Tips settings
        findViewById<View>(R.id.tips_settings_item).setOnClickListener {
            startActivity(Intent(this, TipsSettingsActivity::class.java))
        }

        findViewById<View>(R.id.security_settings_item).setOnClickListener {
            startActivity(Intent(this, SecuritySettingsActivity::class.java))
        }

        findViewById<View>(R.id.developer_settings_item).setOnClickListener {
            startActivity(Intent(this, DeveloperSettingsActivity::class.java))
        }
    }
}
