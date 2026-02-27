package com.electricdreams.numo

import android.content.Context
import android.content.Intent
import android.nfc.NfcManager
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.databinding.ActivityNfcEnableBinding

class NfcEnableActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNfcEnableBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfcEnableBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsButton.setOnClickListener {
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
