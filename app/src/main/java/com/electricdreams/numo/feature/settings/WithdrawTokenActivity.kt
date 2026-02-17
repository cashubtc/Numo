package com.electricdreams.numo.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.PaymentMethod
import org.cashudevkit.SplitTarget
import org.cashudevkit.Token

/**
 * Premium Apple-like activity for withdrawing balance from a mint as Cashu tokens.
 * 
 * Features:
 * - Beautiful card-based design
 * - Quick amount buttons
 * - Smooth entrance animations
 * - Elegant loading states
 * - Token display with copy/share functionality
 */
class WithdrawTokenActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WithdrawToken"
    }

    private lateinit var mintUrl: String
    private var balance: Long = 0
    private lateinit var mintManager: MintManager

    // Views
    private lateinit var backButton: ImageButton
    private lateinit var balanceCard: MaterialCardView
    private lateinit var mintNameText: TextView
    private lateinit var balanceText: TextView
    private lateinit var amountCard: MaterialCardView
    private lateinit var amountInput: EditText
    private lateinit var withdrawButton: Button
    private lateinit var btnAmount100: Button
    private lateinit var btnAmount500: Button
    private lateinit var btnAmount1000: Button
    private lateinit var btnAmountMax: Button
    private lateinit var loadingOverlay: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_token)

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        mintUrl = intent.getStringExtra("mint_url") ?: ""
        balance = intent.getLongExtra("balance", 0)
        mintManager = MintManager.getInstance(this)

        if (mintUrl.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_token_error_generic, "Invalid mint URL"),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        displayMintInfo()
        startEntranceAnimations()
    }

    private fun initViews() {
        backButton = findViewById(R.id.back_button)
        balanceCard = findViewById(R.id.balance_card)
        mintNameText = findViewById(R.id.mint_name_text)
        balanceText = findViewById(R.id.balance_text)
        amountCard = findViewById(R.id.amount_card)
        amountInput = findViewById(R.id.amount_input)
        withdrawButton = findViewById(R.id.withdraw_button)
        btnAmount100 = findViewById(R.id.btn_amount_100)
        btnAmount500 = findViewById(R.id.btn_amount_500)
        btnAmount1000 = findViewById(R.id.btn_amount_1000)
        btnAmountMax = findViewById(R.id.btn_amount_max)
        loadingOverlay = findViewById(R.id.loading_overlay)
    }

    private fun setupListeners() {
        backButton.setOnClickListener { 
            finish() 
        }

        // Amount input text change listener
        amountInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateAndUpdateButton()
            }
        }

        // Quick amount buttons
        btnAmount100.setOnClickListener { setAmount(100) }
        btnAmount500.setOnClickListener { setAmount(500) }
        btnAmount1000.setOnClickListener { setAmount(1000) }
        btnAmountMax.setOnClickListener { setAmount(balance.toInt()) }

        // Withdraw button
        withdrawButton.setOnClickListener {
            createToken()
        }
    }

    private fun setAmount(amount: Int) {
        amountInput.setText(amount.toString())
        validateAndUpdateButton()
    }

    private fun validateAndUpdateButton() {
        val amountText = amountInput.text?.toString()
        val amount = amountText?.toLongOrNull() ?: 0
        
        val isValid = amount > 0 && amount <= balance
        withdrawButton.isEnabled = isValid
        withdrawButton.alpha = if (isValid) 1f else 0.5f
    }

    private fun displayMintInfo() {
        val displayName = mintManager.getMintDisplayName(mintUrl)
        mintNameText.text = displayName

        val balanceAmount = Amount(balance, Amount.Currency.BTC)
        balanceText.text = balanceAmount.toString()
    }

    private fun startEntranceAnimations() {
        // Balance card slide in from top
        balanceCard.alpha = 0f
        balanceCard.translationY = -40f
        balanceCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Balance text scale
        balanceText.scaleX = 0.8f
        balanceText.scaleY = 0.8f
        balanceText.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(200)
            .setDuration(350)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        // Amount card stagger entrance
        amountCard.alpha = 0f
        amountCard.translationY = 40f
        amountCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(300)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun createToken() {
        val amountText = amountInput.text?.toString()
        val amount = amountText?.toLongOrNull() ?: 0

        if (amount <= 0) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_token_error_invalid_amount),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (amount > balance) {
            Toast.makeText(
                this,
                getString(R.string.withdraw_token_error_insufficient_balance, balance),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val wallet = CashuWalletManager.getWallet()
                if (wallet == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@WithdrawTokenActivity, 
                            getString(R.string.withdraw_token_error_wallet_not_initialized), 
                            Toast.LENGTH_SHORT
                        ).show()
                        setLoading(false)
                    }
                    return@launch
                }

                // Get wallet for the mint
                val mintWallet = wallet.getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)
                if (mintWallet == null) {
                    throw Exception("Failed to get wallet for mint: $mintUrl")
                }

                // Create a mint quote to get tokens from the mint
                // This is similar to how LightningMintHandler works - you create a quote,
                // then "mint" the tokens. However, for token withdrawal we want to
                // extract existing tokens from the wallet.
                
                // For now, create a quote and immediately mint (simulating receiving tokens)
                // TODO: Implement proper token extraction from wallet proofs
                val splitTarget = SplitTarget(
                    CdkAmount(amount.toULong()),
                    CdkAmount(0u)
                )

                // Create a mint quote - this creates a quote for minting tokens
                val mintQuote = withContext(Dispatchers.IO) {
                    mintWallet.mintQuote(
                        PaymentMethod.Cashu,
                        CdkAmount(amount.toULong()),
                        "Numo token withdrawal",
                        null
                    )
                }

                // Now "mint" the tokens - this creates proofs which become our token
                val proofs = withContext(Dispatchers.IO) {
                    mintWallet.mint(mintQuote.id, splitTarget, null)
                }

                // Create a Token from the proofs
                val token = withContext(Dispatchers.IO) {
                    org.cashudevkit.Token.fromProofs(proofs, mintUrl)
                }

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    
                    // The mintQuote returns a Token directly for Cashu method
                    // Launch success activity with the token
                    launchTokenSuccessActivity(token, amount)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating token", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(
                        this@WithdrawTokenActivity,
                        getString(R.string.withdraw_token_error_generic, e.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun launchTokenSuccessActivity(token: Token, amount: Long) {
        val tokenString = token.encode()
        
        val intent = Intent(this, WithdrawTokenSuccessActivity::class.java)
        intent.putExtra("mint_url", mintUrl)
        intent.putExtra("amount", amount)
        intent.putExtra("token", tokenString)
        startActivity(intent)
    }

    private fun setLoading(loading: Boolean) {
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        
        // Animate loading overlay
        if (loading) {
            loadingOverlay.alpha = 0f
            loadingOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        }
        
        // Disable inputs during loading
        amountInput.isEnabled = !loading
        withdrawButton.isEnabled = !loading && (amountInput.text?.toString()?.toLongOrNull() ?: 0) > 0
    }

    override fun onResume() {
        super.onResume()
        // Refresh balance
        refreshBalance()
    }

    /**
     * Refreshes the balance from the wallet and updates the UI.
     */
    private fun refreshBalance() {
        lifecycleScope.launch {
            try {
                val newBalance = withContext(Dispatchers.IO) {
                    CashuWalletManager.getBalanceForMint(mintUrl)
                }
                
                withContext(Dispatchers.Main) {
                    if (newBalance != balance) {
                        balance = newBalance
                        val balanceAmount = Amount(balance, Amount.Currency.BTC)
                        balanceText.text = balanceAmount.toString()
                        validateAndUpdateButton()
                        Log.d(TAG, "Balance updated to: $balance sats")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing balance", e)
            }
        }
    }
}
