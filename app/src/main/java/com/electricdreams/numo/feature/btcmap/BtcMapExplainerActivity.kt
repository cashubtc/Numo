package com.electricdreams.numo.feature.btcmap

import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.electricdreams.numo.R

class BtcMapExplainerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        // Ensure icons are light on top of the hero image
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        
        setContentView(R.layout.activity_btcmap_explainer)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, 0) // No padding, allow content to be truly edge-to-edge
            windowInsets
        }

        findViewById<Button>(R.id.btn_open_btcmap).setOnClickListener {
            try {
                val builder = CustomTabsIntent.Builder()
                val customTabsIntent = builder.build()
                customTabsIntent.launchUrl(this, Uri.parse("https://btcmap.org/add-location"))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, "No web browser found.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
