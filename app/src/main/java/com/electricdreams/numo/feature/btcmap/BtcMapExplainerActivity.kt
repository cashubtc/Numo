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
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_layout)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Convert 16dp to pixels
            val margin16dp = (16 * resources.displayMetrics.density).toInt()
            
            // Adjust the close button to be below the status bar
            val btnClose = findViewById<android.view.View>(R.id.btn_close)
            val closeParams = btnClose.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            closeParams.topMargin = insets.top + margin16dp
            btnClose.layoutParams = closeParams

            // Ensure the center card remains centered visually between system bars
            val card = findViewById<android.view.View>(R.id.content_card)
            val cardParams = card.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            cardParams.topMargin = insets.top + margin16dp
            cardParams.bottomMargin = insets.bottom + margin16dp
            card.layoutParams = cardParams

            windowInsets
        }

        findViewById<android.view.View>(R.id.btn_close).setOnClickListener {
            finish()
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
