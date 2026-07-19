package com.electricdreams.numo.feature.withdraw

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.dev.WalletLogger
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.LightningAddressManager
import com.electricdreams.numo.core.util.LnUrlClient
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.settings.WithdrawMeltQuoteActivity
import com.electricdreams.numo.ui.components.KeypadManager
import com.electricdreams.numo.ui.components.NumoTopBar
import com.electricdreams.numo.ui.util.shake
import com.electricdreams.numo.util.getVibrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.MintUrl

/**
 * POS-style keypad screen for choosing how much to withdraw to a Lightning
 * address. Mirrors the amount-entry language of the POS home screen (auto-sizing
 * amount, sat/fiat toggle, keypad) plus a MAX chip and LNURL min/max limits.
 *
 * On Continue it requests a melt quote for the address and hands off to
 * [WithdrawMeltQuoteActivity].
 */
class WithdrawAmountActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WithdrawAmount"
        const val EXTRA_MINT_URL = "mint_url"
        const val EXTRA_BALANCE = "balance"
        const val EXTRA_LIGHTNING_ADDRESS = "lightning_address"
    }

    private lateinit var mintUrl: String
    private lateinit var lightningAddress: String
    private var balance: Long = 0

    private var minSats: Long = 1
    private var maxSats: Long = 0
    private var lnUrlMaxSats: Long = Long.MAX_VALUE

    private val satoshiInput = StringBuilder()
    private val fiatInput = StringBuilder()
    private var isFiatInputMode = false
    private var isLoading = false

    private lateinit var topBar: NumoTopBar
    private lateinit var destinationText: TextView
    private lateinit var amountDisplay: TextView
    private lateinit var secondaryAmountContainer: View
    private lateinit var secondaryAmountDisplay: TextView
    private lateinit var currencySwitchButton: View
    private lateinit var maxChip: TextView
    private lateinit var limitsHelper: TextView
    private lateinit var continueButton: Button
    private lateinit var continueButtonSpinner: ProgressBar
    private lateinit var keypadManager: KeypadManager

    private lateinit var priceWorker: BitcoinPriceWorker
    private lateinit var lightningAddressManager: LightningAddressManager

    private val balanceRefreshReceiver: BroadcastReceiver = BalanceRefreshBroadcast.createReceiver {
        refreshBalance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_withdraw_amount)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        mintUrl = intent.getStringExtra(EXTRA_MINT_URL) ?: ""
        balance = intent.getLongExtra(EXTRA_BALANCE, 0)
        lightningAddress = intent.getStringExtra(EXTRA_LIGHTNING_ADDRESS) ?: ""

        if (mintUrl.isBlank() || lightningAddress.isBlank()) {
            finish()
            return
        }

        priceWorker = BitcoinPriceWorker.getInstance(this)
        lightningAddressManager = LightningAddressManager.getInstance(this)

        initViews()
        updateLimits()
        updateDisplay(animateDigit = false)
        fetchLnUrlLimits()
    }

    private fun initViews() {
        topBar = findViewById(R.id.top_bar)
        destinationText = findViewById(R.id.destination_text)
        amountDisplay = findViewById(R.id.amount_display)
        secondaryAmountContainer = findViewById(R.id.secondary_amount_container)
        secondaryAmountDisplay = findViewById(R.id.secondary_amount_display)
        currencySwitchButton = findViewById(R.id.currency_switch_button)
        maxChip = findViewById(R.id.max_chip)
        limitsHelper = findViewById(R.id.limits_helper)
        continueButton = findViewById(R.id.continue_button)
        continueButtonSpinner = findViewById(R.id.continue_button_spinner)

        topBar.onNavClick { finish() }
        destinationText.text = getString(R.string.withdraw_amount_to, lightningAddress)

        keypadManager = KeypadManager(this, findViewById<GridLayout>(R.id.keypad), R.layout.keypad_button) { label ->
            if (!isLoading) {
                keypadManager.handleKeypadInput(label, satoshiInput, fiatInput, isFiatInputMode)
                updateDisplay(animateDigit = true)
            }
        }

        secondaryAmountContainer.setOnClickListener { toggleInputMode() }
        maxChip.setOnClickListener { fillMaxAmount() }
        continueButton.setOnClickListener { onContinue() }
    }

    private fun updateLimits() {
        val feeBufferedMax = (balance * (1 - WithdrawConstants.FEE_BUFFER_PERCENT)).toLong()
        maxSats = minOf(feeBufferedMax, lnUrlMaxSats).coerceAtLeast(0)
        maxChip.text = getString(
            R.string.withdraw_amount_max_chip,
            Amount(maxSats, Amount.Currency.BTC).toString()
        )
        showLimits()
    }

    private fun showLimits() {
        limitsHelper.setTextColor(getColor(R.color.color_text_tertiary))
        limitsHelper.text = getString(
            R.string.withdraw_amount_limits,
            Amount(minSats, Amount.Currency.BTC).toString(),
            Amount(maxSats, Amount.Currency.BTC).toString()
        )
    }

    /** Fetch LNURL min/max for the address; degrade to local fallbacks on failure. */
    private fun fetchLnUrlLimits() {
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) {
                LnUrlClient.fetchLnUrlDetails(lightningAddress)
            }
            if (details != null) {
                minSats = (details.minSendable / 1000).coerceAtLeast(1)
                lnUrlMaxSats = (details.maxSendable / 1000).coerceAtLeast(1)
                updateLimits()
                updateDisplay(animateDigit = false)
            }
        }
    }

    private fun currentAmountSats(): Long {
        return if (isFiatInputMode) {
            val rawInput = fiatInput.toString().toLongOrNull() ?: 0L
            val currency = currentCurrency()
            val fiatAmount = if (currency.isZeroDecimal()) rawInput.toDouble() else rawInput / 100.0
            fiatToSatoshis(fiatAmount)
        } else {
            satoshiInput.toString().toLongOrNull() ?: 0L
        }
    }

    private fun currentCurrency(): Amount.Currency {
        val currencyCode = CurrencyManager.getInstance(this).getCurrentCurrency()
        return Amount.Currency.fromCode(currencyCode)
    }

    private fun fiatToSatoshis(fiatAmount: Double): Long {
        val price = priceWorker.getCurrentPrice()
        if (price <= 0) return 0L
        return (fiatAmount / price * 100_000_000).toLong()
    }

    private fun hasPriceData(): Boolean = priceWorker.getCurrentPrice() > 0

    private fun toggleInputMode() {
        if (!isFiatInputMode && !hasPriceData()) return

        val currency = currentCurrency()
        if (isFiatInputMode) {
            val sats = currentAmountSats()
            satoshiInput.clear()
            if (sats > 0) satoshiInput.append(sats.toString())
        } else {
            val sats = satoshiInput.toString().toLongOrNull() ?: 0L
            val fiatValue = priceWorker.satoshisToFiat(sats)
            val storedValue = if (currency.isZeroDecimal()) {
                fiatValue.toLong()
            } else {
                (fiatValue * 100).toLong()
            }
            fiatInput.clear()
            if (storedValue > 0) fiatInput.append(storedValue.toString())
        }
        isFiatInputMode = !isFiatInputMode
        updateDisplay(animateDigit = false)
    }

    private fun fillMaxAmount() {
        vibrateClick()
        satoshiInput.clear()
        satoshiInput.append(maxSats.toString())
        if (isFiatInputMode) {
            val currency = currentCurrency()
            val fiatValue = priceWorker.satoshisToFiat(maxSats)
            val storedValue = if (currency.isZeroDecimal()) {
                fiatValue.toLong()
            } else {
                (fiatValue * 100).toLong()
            }
            fiatInput.clear()
            if (storedValue > 0) fiatInput.append(storedValue.toString())
        }
        updateDisplay(animateDigit = true)
    }

    private fun updateDisplay(animateDigit: Boolean) {
        val sats = currentAmountSats()
        val primaryText: String
        val secondaryText: String

        if (isFiatInputMode) {
            val currency = currentCurrency()
            val rawInput = fiatInput.toString().toLongOrNull() ?: 0L
            val fiatCents = if (currency.isZeroDecimal()) rawInput * 100 else rawInput
            val fiatAmount = Amount(fiatCents, currency)
            primaryText = if (fiatAmount.toString().length > 9) fiatAmount.toShortString() else fiatAmount.toString()
            secondaryText = Amount(sats, Amount.Currency.BTC).toShortString()
        } else {
            val satAmount = Amount(sats, Amount.Currency.BTC)
            primaryText = if (satAmount.toString().length > 9) satAmount.toShortString() else satAmount.toString()
            secondaryText = if (hasPriceData()) {
                val fiatValue = priceWorker.satoshisToFiat(sats)
                Amount.fromMajorUnits(fiatValue, currentCurrency()).toShortString()
            } else {
                ""
            }
        }

        if (animateDigit && amountDisplay.text.toString() != primaryText) {
            animateDigitEntry(primaryText)
        } else {
            amountDisplay.text = primaryText
        }
        secondaryAmountDisplay.text = secondaryText
        secondaryAmountContainer.visibility = if (hasPriceData()) View.VISIBLE else View.INVISIBLE

        val valid = sats in minSats..maxSats && sats > 0
        continueButton.alpha = if (valid && !isLoading) 1.0f else 0.5f
    }

    private fun animateDigitEntry(newText: String) {
        amountDisplay.animate().cancel()
        amountDisplay.animate()
            .alpha(0.7f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(80)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .withEndAction {
                amountDisplay.text = newText
                amountDisplay.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                    .start()
            }
            .start()
    }

    private fun vibrateClick() {
        getVibrator()?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        }
    }

    private fun onContinue() {
        if (isLoading) return
        val sats = currentAmountSats()
        if (sats <= 0 || sats < minSats || sats > maxSats) {
            amountDisplay.shake(amplitude = 10f)
            limitsHelper.setTextColor(getColor(R.color.color_error))
            limitsHelper.text = getString(
                R.string.withdraw_amount_limits,
                Amount(minSats, Amount.Currency.BTC).toString(),
                Amount(maxSats, Amount.Currency.BTC).toString()
            )
            return
        }

        setLoading(true)
        showLimits()

        lifecycleScope.launch {
            try {
                val wallet = CashuWalletManager.getWallet()
                    ?: throw Exception(getString(R.string.withdraw_lightning_error_wallet_not_initialized))

                val amountMsat = sats * 1000
                val unit = MintManager.getInstance(this@WithdrawAmountActivity).getPreferredUnit()
                val mintWallet = wallet.getWallet(MintUrl(mintUrl), CashuWalletManager.getCurrencyUnit(unit))
                    ?: throw Exception("Failed to get wallet for mint: $mintUrl")
                val meltQuote = withContext(Dispatchers.IO) {
                    mintWallet.meltLightningAddressQuote(lightningAddress, org.cashudevkit.Amount(amountMsat.toULong()))
                }
                WalletLogger.log("OUT", meltQuote.amount.value.toLong(), mintUrl, "Lightning address melt quote requested")

                withContext(Dispatchers.Main) {
                    setLoading(false)

                    val totalRequired = meltQuote.amount.value.toLong() + meltQuote.feeReserve.value.toLong()
                    if (totalRequired > balance) {
                        amountDisplay.shake(amplitude = 10f)
                        limitsHelper.setTextColor(getColor(R.color.color_error))
                        limitsHelper.text = getString(
                            R.string.withdraw_lightning_error_insufficient_balance,
                            totalRequired,
                            balance,
                            (balance * (1 - WithdrawConstants.FEE_BUFFER_PERCENT)).toLong()
                        )
                        return@withContext
                    }

                    lightningAddressManager.setLightningAddress(lightningAddress)

                    val intent = Intent(this@WithdrawAmountActivity, WithdrawMeltQuoteActivity::class.java)
                    intent.putExtra("mint_url", mintUrl)
                    intent.putExtra("quote_id", meltQuote.id)
                    intent.putExtra("amount", meltQuote.amount.value.toLong())
                    intent.putExtra("fee_reserve", meltQuote.feeReserve.value.toLong())
                    intent.putExtra("invoice", null as String?)
                    intent.putExtra("lightning_address", lightningAddress)
                    intent.putExtra("request", meltQuote.request)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting melt quote for Lightning address", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    amountDisplay.shake(amplitude = 10f)
                    limitsHelper.setTextColor(getColor(R.color.color_error))
                    limitsHelper.text = WithdrawErrorMapper.resolve(this@WithdrawAmountActivity, e.message)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        continueButtonSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        continueButton.text = if (loading) "" else getString(R.string.withdraw_lightning_button_continue)
        continueButton.isEnabled = !loading
        if (!loading) updateDisplay(animateDigit = false)
    }

    private fun refreshBalance() {
        // Without an initialized wallet a refresh would report 0 and clobber
        // the balance passed in via the intent.
        if (CashuWalletManager.getWallet() == null) return
        lifecycleScope.launch {
            try {
                val newBalance = withContext(Dispatchers.IO) {
                    CashuWalletManager.getBalanceForMint(mintUrl)
                }
                withContext(Dispatchers.Main) {
                    if (newBalance != balance) {
                        balance = newBalance
                        updateLimits()
                        updateDisplay(animateDigit = false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing balance", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BalanceRefreshBroadcast.register(this, balanceRefreshReceiver)
    }

    override fun onStop() {
        super.onStop()
        BalanceRefreshBroadcast.unregister(this, balanceRefreshReceiver)
    }

    override fun onResume() {
        super.onResume()
        refreshBalance()
    }
}
