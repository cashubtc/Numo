package com.electricdreams.shellshock.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.shellshock.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { finish() }

        findViewById<View>(R.id.currency_settings_item).setOnClickListener {
            startActivity(Intent(this, CurrencySettingsActivity::class.java))
        }

        findViewById<View>(R.id.mints_settings_item).setOnClickListener {
            startActivity(Intent(this, MintsSettingsActivity::class.java))
        }

        findViewById<View>(R.id.items_settings_item).setOnClickListener {
            startActivity(Intent(this, ItemsSettingsActivity::class.java))
        }
    }
}
