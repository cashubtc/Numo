package com.example.shellshock

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class ImportProofActivity : AppCompatActivity() {

    private val TAG = "ImportProofActivity"
    private lateinit var etProof: EditText
    private lateinit var btnSetToken: Button
    private lateinit var statusTextView: TextView
    private var nfcAdapter: NfcAdapter? = null

    private var satocashWallet: SatocashWallet? = null
    private var satocashClient: SatocashNfcClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_proof)

        etProof = findViewById(R.id.etProof)
        btnSetToken = findViewById(R.id.btnSetToken)
        statusTextView = findViewById(R.id.statusTextView)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            lifecycleScope.launch {
                logStatus("NFC is not available on this device. Cannot flash proofs.")
                btnSetToken.isEnabled = false
            }
        } else {
            lifecycleScope.launch {
                logStatus("NFC available. Enter token or place card to flash directly.")
            }
        }

        btnSetToken.setOnClickListener {
            val proofToken = etProof.text.toString()
            if (proofToken.isNotBlank()) {
                SatocashWallet.pendingProofToken = proofToken
                lifecycleScope.launch {
                    logStatus("Token set. Ready to flash to card: ${proofToken.take(30)}...")
                }
                Toast.makeText(this, "Token set. Now tap a card to import proofs.", Toast.LENGTH_LONG).show()
            } else {
                lifecycleScope.launch {
                    logStatus("Please enter a Cashu proof token first.")
                }
                Toast.makeText(this, "Please enter a Cashu proof token", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_MUTABLE
            )
            val techLists = arrayOf(arrayOf(IsoDep::class.java.name))
            it.enableForegroundDispatch(this, pendingIntent, null, techLists)
            Log.d(TAG, "NFC Foreground dispatch enabled for ImportProofActivity.")
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        Log.d(TAG, "NFC Foreground dispatch disabled for ImportProofActivity.")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            handleNfcImport(intent)
        }
    }

    private fun handleNfcImport(intent: Intent) {
        val tag: Tag? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (tag == null) {
            lifecycleScope.launch {
                logStatus("NFC Tag not found in intent.")
            }
            return
        }

        val tokenToImport = SatocashWallet.pendingProofToken
        if (tokenToImport.isNullOrBlank()) {
            lifecycleScope.launch {
                logStatus("No proof token set to import. Please enter it first or use the main activity to receive.")
            }
            Toast.makeText(this, "No token to import. Enter one or go to Main screen.", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            logStatus("NFC Tag discovered. Attempting to import proofs...")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                satocashClient = SatocashNfcClient(tag)
                satocashClient?.connect()
                satocashWallet = SatocashWallet(satocashClient!!)

                logStatus("Selecting Satocash Applet...")
                satocashClient?.selectApplet(SatocashNfcClient.SATOCASH_AID)
                logStatus("Satocash Applet found and selected!")

                logStatus("Initializing Secure Channel...")
                satocashClient?.initSecureChannel()
                logStatus("Secure Channel Initialized!")

                logStatus("Authenticating PIN (using hardcoded '0000')...")
                val authenticated = satocashWallet?.authenticatePIN("0000")?.join() ?: false

                if (authenticated) {
                    logStatus("PIN Verified. Importing proofs...")
                    val importedCount = satocashWallet?.importProofsFromToken(tokenToImport)?.join() ?: 0
                    logStatus("Successfully imported $importedCount proofs to card!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImportProofActivity, "Imported $importedCount proofs!", Toast.LENGTH_LONG).show()
                        SatocashWallet.pendingProofToken = ""
                    }
                } else {
                    logStatus("PIN authentication failed. Proofs not imported.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImportProofActivity, "PIN authentication failed!", Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                logStatus("Error during import: ${e.message}")
                Log.e(TAG, "Error during NFC proof import: ${e.message}", e)
            } finally {
                try {
                    satocashClient?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing IsoDep connection: ${e.message}", e)
                }
                logStatus("NFC interaction finished. Ready for next action.")
            }
        }
    }

    private suspend fun logStatus(message: String) {
        withContext(Dispatchers.Main) {
            val currentText = statusTextView.text.toString()
            val newText = if (currentText.isBlank()) message else "$currentText\n$message"
            statusTextView.text = newText
            Log.d(TAG, message)
        }
    }
}
