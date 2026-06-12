package com.electricdreams.numo.feature.settings

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.bark.BarkWalletManager
import com.electricdreams.numo.core.model.Amount as NumoAmount
import com.electricdreams.numo.feature.enableEdgeToEdgeWithPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.bark.*

class BarkWithdrawActivity : AppCompatActivity() {

    private lateinit var balanceText: TextView
    private lateinit var destinationInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var feeEstimateCard: View
    private lateinit var feeEstimateText: TextView
    private lateinit var estimateButton: Button
    private lateinit var withdrawButton: Button
    private lateinit var loadingIndicator: ProgressBar

    private var spendableBalance: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bark_withdraw)

        enableEdgeToEdgeWithPill(this, lightNavIcons = true)

        initViews()
        setupListeners()
        loadBalance()
    }

    private fun initViews() {
        balanceText = findViewById(R.id.ark_balance_text)
        destinationInput = findViewById(R.id.destination_input)
        amountInput = findViewById(R.id.amount_input)
        feeEstimateCard = findViewById(R.id.fee_estimate_card)
        feeEstimateText = findViewById(R.id.fee_estimate_text)
        estimateButton = findViewById(R.id.estimate_button)
        withdrawButton = findViewById(R.id.withdraw_button)
        loadingIndicator = findViewById(R.id.loading_indicator)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        estimateButton.setOnClickListener {
            estimateFee()
        }

        withdrawButton.setOnClickListener {
            performWithdrawal()
        }
    }

    private fun loadBalance() {
        lifecycleScope.launch {
            try {
                val wallet = BarkWalletManager.getWallet()
                if (wallet != null) {
                    spendableBalance = wallet.balance().spendableSats.toLong()
                    balanceText.text = "$spendableBalance sats"
                }
            } catch (e: Exception) {
                Log.e("BarkWithdraw", "Failed to load Bark balance: ${e.message}", e)
                balanceText.text = "Error"
            }
        }
    }

    private fun estimateFee() {
        val dest = destinationInput.text?.toString()?.trim() ?: ""
        val amountStr = amountInput.text?.toString()?.trim() ?: ""

        if (dest.isBlank()) {
            Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                showLoading(true)
                val wallet = BarkWalletManager.getWallet()
                if (wallet == null) {
                    Toast.makeText(this@BarkWithdrawActivity, "Bark Wallet not initialized", Toast.LENGTH_SHORT).show()
                    showLoading(false)
                    return@launch
                }

                val isInvoice = dest.startsWith("lnbc", ignoreCase = true) || dest.startsWith("lightning:lnbc", ignoreCase = true)
                val cleanDest = if (dest.startsWith("lightning:", ignoreCase = true)) dest.substring(10) else dest

                if (isInvoice) {
                    // Pay Lightning invoice fee estimation
                    val estimate = wallet.estimateLightningSendFee(0UL)
                    val fee = estimate.feeSats.toLong()
                    feeEstimateText.text = "$fee sats"
                    feeEstimateCard.visibility = View.VISIBLE
                    withdrawButton.visibility = View.VISIBLE
                } else {
                    if (amountStr.isBlank()) {
                        Toast.makeText(this@BarkWithdrawActivity, "Amount is required for addresses", Toast.LENGTH_SHORT).show()
                        showLoading(false)
                        return@launch
                    }
                    val amountSats = amountStr.toLongOrNull() ?: 0L
                    if (amountSats <= 0L) {
                        Toast.makeText(this@BarkWithdrawActivity, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                        showLoading(false)
                        return@launch
                    }

                    val isLnAddr = dest.contains("@")
                    if (isLnAddr) {
                        val estimate = wallet.estimateLightningSendFee(amountSats.toULong())
                        val fee = estimate.feeSats.toLong()
                        feeEstimateText.text = "$fee sats"
                        feeEstimateCard.visibility = View.VISIBLE
                        withdrawButton.visibility = View.VISIBLE
                    } else {
                        // On-chain send estimate
                        val estimate = wallet.estimateSendOnchainFee(cleanDest, amountSats.toULong())
                        val fee = estimate.feeSats.toLong()
                        feeEstimateText.text = "$fee sats"
                        feeEstimateCard.visibility = View.VISIBLE
                        withdrawButton.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e("BarkWithdraw", "Fee estimation failed: ${e.message}", e)
                Toast.makeText(this@BarkWithdrawActivity, "Estimation failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun performWithdrawal() {
        val dest = destinationInput.text?.toString()?.trim() ?: ""
        val amountStr = amountInput.text?.toString()?.trim() ?: ""

        if (dest.isBlank()) {
            Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                showLoading(true)
                val wallet = BarkWalletManager.getWallet()
                if (wallet == null) {
                    Toast.makeText(this@BarkWithdrawActivity, "Bark Wallet not initialized", Toast.LENGTH_SHORT).show()
                    showLoading(false)
                    return@launch
                }

                val isInvoice = dest.startsWith("lnbc", ignoreCase = true) || dest.startsWith("lightning:lnbc", ignoreCase = true)
                val cleanDest = if (dest.startsWith("lightning:", ignoreCase = true)) dest.substring(10) else dest

                var finalAmount = 0L

                if (isInvoice) {
                    // Pay Lightning invoice
                    wallet.payLightningInvoice(cleanDest, null)
                    finalAmount = 0L
                } else {
                    if (amountStr.isBlank()) {
                        Toast.makeText(this@BarkWithdrawActivity, "Amount is required for addresses", Toast.LENGTH_SHORT).show()
                        showLoading(false)
                        return@launch
                    }
                    val amountSats = amountStr.toLongOrNull() ?: 0L
                    if (amountSats <= 0L) {
                        Toast.makeText(this@BarkWithdrawActivity, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                        showLoading(false)
                        return@launch
                    }
                    finalAmount = amountSats

                    val isLnAddr = dest.contains("@")
                    if (isLnAddr) {
                        // Pay Lightning address
                        wallet.payLightningAddress(cleanDest, amountSats.toULong(), null)
                    } else {
                        // Send on-chain from Ark balance
                        wallet.sendOnchain(cleanDest, amountSats.toULong())
                    }
                }

                // Success! Force a balance and history sync, then navigate to success screen
                BarkWalletManager.runSyncAndMaintenance()

                withContext(Dispatchers.Main) {
                    val successIntent = Intent(this@BarkWithdrawActivity, WithdrawSuccessActivity::class.java).apply {
                        putExtra("amount", finalAmount)
                        putExtra("destination", dest)
                    }
                    startActivity(successIntent)
                    finish()
                }
            } catch (e: Exception) {
                Log.e("BarkWithdraw", "Withdrawal failed: ${e.message}", e)
                Toast.makeText(this@BarkWithdrawActivity, "Withdrawal failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        estimateButton.isEnabled = !isLoading
        withdrawButton.isEnabled = !isLoading
    }
}
