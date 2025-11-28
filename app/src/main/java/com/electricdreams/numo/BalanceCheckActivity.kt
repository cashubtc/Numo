package com.electricdreams.numo
import com.electricdreams.numo.R

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.electricdreams.numo.core.model.Amount
import kotlin.math.pow

class BalanceCheckActivity : AppCompatActivity() {

    private lateinit var balanceDisplay: TextView
    private lateinit var cardInfoDisplay: TextView
    private var nfcAdapter: NfcAdapter? = null
    private var satocashClient: SatocashNfcClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "============================================")
        Log.d(TAG, "BalanceCheckActivity onCreate() called!")
        Log.d(TAG, "============================================")
        setTheme(R.style.Theme_Numo)
        setContentView(R.layout.activity_balance_check)

        findViewById<View?>(R.id.back_button)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.balance_check_actionbar_title)
        }

        balanceDisplay = findViewById(R.id.balance_display)
        cardInfoDisplay = findViewById(R.id.card_info_display)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        Log.d(TAG, "NFC Adapter available: ${nfcAdapter != null}")

        updateCardInfoDisplay(getString(R.string.balance_check_card_info_tap_card))

        Log.d(TAG, "BalanceCheckActivity onCreate() completed - ready for NFC tap")
    }

    private fun updateBalanceDisplay(balance: Long) {
        mainHandler.post {
            balanceDisplay.text = getString(
                R.string.balance_check_status_balance,
                Amount(balance, Amount.Currency.BTC).toString()
            )
            balanceDisplay.visibility = View.VISIBLE
        }
    }

    private fun updateCardInfoDisplay(info: String) {
        mainHandler.post {
            cardInfoDisplay.text = info
            cardInfoDisplay.visibility = View.VISIBLE
        }
    }

    private fun handleBalanceCheckError(message: String) {
        mainHandler.post {
            balanceDisplay.text = getString(R.string.balance_check_status_error, message)
            balanceDisplay.visibility = View.VISIBLE
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "BalanceCheckActivity onResume() called")
        nfcAdapter?.let { adapter ->
            Log.d(TAG, "Enabling NFC foreground dispatch...")
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE
            )
            val techList = arrayOf(arrayOf(IsoDep::class.java.name))
            adapter.enableForegroundDispatch(this, pendingIntent, null, techList)
            Log.d(TAG, "✅ NFC foreground dispatch enabled for BalanceCheckActivity")
        } ?: Log.e(TAG, "❌ NFC Adapter is null - cannot enable foreground dispatch")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "BalanceCheckActivity onPause() called")
        nfcAdapter?.disableForegroundDispatch(this)
        Log.d(TAG, "✅ NFC foreground dispatch disabled")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "=== NFC onNewIntent triggered ===")
        Log.d(TAG, "Action: ${intent.action}")

        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            Log.d(TAG, "✅ ACTION_TECH_DISCOVERED matched!")
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                Log.d(TAG, "✅ Tag received: $tag")
                Log.d(TAG, "Tag ID: " + android.util.Base64.encodeToString(tag.id, android.util.Base64.NO_WRAP))
                Log.d(TAG, "Technologies: " + java.util.Arrays.toString(tag.techList))

                try {
                    Log.d(TAG, "🔍 Checking if IsoDep available...")
                    val isoDep = IsoDep.get(tag)
                    if (isoDep != null) {
                        Log.d(TAG, "✅ IsoDep detected - proceeding with balance check")
                        checkNfcBalance(tag)
                    } else {
                        Log.e(TAG, "❌ IsoDep not available on this tag")
                        handleBalanceCheckError(getString(R.string.balance_check_error_card_does_not_support_isodep))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error checking tag technologies: ${e.message}")
                    handleBalanceCheckError(getString(R.string.balance_check_error_invalid_nfc_tag))
                }
            } else {
                Log.e(TAG, "❌ Tag was null in ACTION_TECH_DISCOVERED")
            }
        } else {
            Log.d(TAG, "❌ Skipping non-NFC action: ${intent.action}")
        }
    }

    private fun checkNfcBalance(tag: Tag) {
        Log.d(TAG, "=== NFC BALANCE CHECK STARTED (NO PIN REQUIRED) ===")
        Thread {
            try {
                Log.d(TAG, "1. Creating Satocash client...")
                satocashClient = SatocashNfcClient(tag)

                Log.d(TAG, "2. Connecting to NFC card...")
                satocashClient!!.connect()
                Log.d(TAG, "✅ Successfully connected to NFC card")

                Log.d(TAG, "3. Selecting Satocash applet...")
                satocashClient!!.selectApplet(SatocashNfcClient.SATOCASH_AID)
                Log.d(TAG, "✅ Satocash Applet found and selected!")

                Log.d(TAG, "4. Initializing secure channel...")
                satocashClient!!.initSecureChannel()
                Log.d(TAG, "✅ Secure Channel Initialized!")

                Log.d(TAG, "5. Getting accurate card balance (no PIN authentication)...")
                val totalBalance = cardBalance
                Log.d(TAG, "✅ Balance check complete: $totalBalance ₿")

                updateBalanceDisplay(totalBalance)
            } catch (e: java.io.IOException) {
                Log.e(TAG, "❌ NFC Communication Error: ${e.message}", e)
                handleBalanceCheckError("NFC Communication Error: ${e.message}")
            } catch (e: SatocashNfcClient.SatocashException) {
                Log.e(TAG, "❌ Satocash Card Error: ${e.message} (SW: 0x${Integer.toHexString(e.sw)})", e)
                handleBalanceCheckError("Satocash Card Error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected Error: ${e.message}", e)
                handleBalanceCheckError("Error: ${e.message}")
            } finally {
                try {
                    satocashClient?.close()
                    Log.d(TAG, "✅ NFC connection closed.")
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "Error closing NFC connection: ${e.message}")
                }
            }
            Log.d(TAG, "=== NFC BALANCE CHECK COMPLETED ===")
        }.start()
    }

    private val cardBalance: Long
        get() {
            Log.d(TAG, "Getting card balance using getProofInfo (no PIN required)...")
            return try {
                val status = satocashClient!!.status
                Log.d(TAG, "Card status: $status")

                val nbProofsUnspent = status.getOrDefault("nb_proofs_unspent", 0) as Int
                val nbProofsSpent = status.getOrDefault("nb_proofs_spent", 0) as Int
                val totalProofs = nbProofsUnspent + nbProofsSpent

                if (totalProofs == 0) {
                    Log.d(TAG, "No proofs found in card")
                    updateCardInfoDisplay(getString(R.string.balance_check_info_no_proofs))
                    return 0
                }

                Log.d(TAG, "Total proofs in card: $totalProofs ($nbProofsUnspent unspent, $nbProofsSpent spent)")

                val proofStates = satocashClient!!.getProofInfo(
                    SatocashNfcClient.Unit.SAT,
                    SatocashNfcClient.ProofInfoType.METADATA_STATE,
                    0,
                    totalProofs
                )

                Log.d(TAG, "Retrieved state info for ${proofStates.size} proofs")
                Log.d(TAG, "ProofState: $proofStates")

                if (proofStates.isEmpty()) {
                    updateCardInfoDisplay(getString(R.string.balance_check_info_no_state_data))
                    return 0
                }

                val amounts = satocashClient!!.getProofInfo(
                    SatocashNfcClient.Unit.SAT,
                    SatocashNfcClient.ProofInfoType.METADATA_AMOUNT_EXPONENT,
                    0,
                    totalProofs
                )

                if (amounts.isEmpty()) {
                    Log.e(TAG, "Amounts data missing or mismatched")
                    updateCardInfoDisplay(getString(R.string.balance_check_info_inconsistent_proofs))
                    return 0
                }

                var totalBalance = 0L
                var unspentCount = 0

                for (i in proofStates.indices) {
                    val state = proofStates[i]
                    Log.d(TAG, "state: $state")
                    if (state == 1) {
                        unspentCount++
                        val amountExponent = amounts[i]
                        val amount = 2.0.pow(amountExponent.toDouble()).toLong()
                        totalBalance += amount
                        Log.d(TAG, "Proof $i: $amount ₿ (exp=$amountExponent)")
                    }
                }

                Log.d(TAG, "Total balance: $totalBalance ₿ from $unspentCount active proofs")
                updateCardInfoDisplay(
                    "Card has $unspentCount active proofs worth " +
                        Amount(totalBalance, Amount.Currency.BTC).toString()
                )
                totalBalance
            } catch (e: SatocashNfcClient.SatocashException) {
                Log.e(TAG, "Satocash exception: ${e.message}", e)
                mainHandler.post {
                    handleBalanceCheckError("NFC card error: ${e.message} (SW: 0x${Integer.toHexString(e.sw)})")
                }
                0
            } catch (e: java.io.IOException) {
                Log.e(TAG, "IO exception: ${e.message}", e)
                mainHandler.post { handleBalanceCheckError("Communication error: ${e.message}") }
                0
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception: ${e.message}", e)
                mainHandler.post { handleBalanceCheckError("Unexpected error: ${e.message}") }
                0
            }
        }

    companion object {
        private const val TAG = "BalanceCheckActivity"
    }
}
