package com.electricdreams.numo.feature.btcmap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.electricdreams.numo.R

class BtcMapExplainerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_btcmap_explainer)

        findViewById<Button>(R.id.btn_open_btcmap).setOnClickListener {
            val builder = CustomTabsIntent.Builder()
            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(this, Uri.parse("https://btcmap.org/add-location"))
        }
    }
}
