package com.electricdreams.numo

import android.content.Context
import android.content.Intent
import android.nfc.NfcManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class NfcEnableActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_enable)

        findViewById<Button>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        if (isNfcEnabled()) {
            // NFC is enabled, proceed to the main app flow
            // We use FLAG_ACTIVITY_REORDER_TO_FRONT to bring existing ModernPOSActivity to front if it exists
            val intent = Intent(this, com.electricdreams.numo.ModernPOSActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            finish()
        }
    }

    override fun onBackPressed() {
        // Prevent going back without enabling NFC
        // Optionally minimize the app instead
        moveTaskToBack(true)
    }

    private fun isNfcEnabled(): Boolean {
        val nfcManager = getSystemService(Context.NFC_SERVICE) as? NfcManager
        val adapter = nfcManager?.defaultAdapter
        return adapter != null && adapter.isEnabled
    }
}
