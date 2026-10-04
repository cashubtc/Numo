package com.electricdreams.numo.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.transition.Fade
import androidx.transition.TransitionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.MintUrl
import org.cashudevkit.P2pkLockedProofSendMode
import org.cashudevkit.SendKind
import org.cashudevkit.SendOptions
import org.cashudevkit.SplitTarget
import org.cashudevkit.Token

import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.dev.WalletLogger
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.databinding.ActivityWithdrawEcashBinding
import com.electricdreams.numo.feature.autowithdraw.AutoWithdrawManager
import com.electricdreams.numo.feature.autowithdraw.WithdrawHistoryEntry
import com.electricdreams.numo.ui.components.ConfirmationBottomSheet
import com.electricdreams.numo.ui.util.DialogHelper
import com.electricdreams.numo.ui.util.QrCodeGenerator
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Export as ecash: turns part of the balance into a bearer token the merchant can
 * hand to someone. Creating it is confirmed first because it spends immediately.
 */
class WithdrawEcashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWithdrawEcashBinding
    private lateinit var sourcePicker: WithdrawSourcePicker
    private lateinit var amountField: WithdrawAmountField

    private var token: String? = null
    private var tokenAmount: Long = 0
    private var isCreating = false

    private var animatedQrJob: Job? = null
    private var qrSpeedIndex = DEFAULT_SPEED_INDEX

    private val fullscreenBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullscreenQr(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWithdrawEcashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        amountField = WithdrawAmountField(this, binding.amount) { updatePrimaryEnabled() }
        sourcePicker = WithdrawSourcePicker(this, binding.source) { source ->
            val balance = source?.balance ?: 0L
            amountField.setAvailable(balance, maxSendable = balance)
            updatePrimaryEnabled()
        }

        onBackPressedDispatcher.addCallback(this, fullscreenBack)
        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        binding.primaryButton.setOnClickListener { if (token == null) confirmCreate() else shareToken() }
        binding.copyButton.setOnClickListener { copyToken() }
        binding.tokenQr.setOnClickListener { setFullscreenQr(true) }
        binding.fullscreenClose.setOnClickListener { setFullscreenQr(false) }
        binding.qrSpeedButton.setOnClickListener {
            qrSpeedIndex = (qrSpeedIndex + 1) % QR_SPEEDS.size
            updateSpeedLabel()
        }
        updateSpeedLabel()

        val restoredToken = savedInstanceState?.getString(STATE_TOKEN)
        if (restoredToken != null) {
            showResult(restoredToken, savedInstanceState.getLong(STATE_AMOUNT), animate = false)
        } else {
            sourcePicker.load(intent.getStringExtra(WithdrawLightningActivity.EXTRA_MINT_URL))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        token?.let {
            outState.putString(STATE_TOKEN, it)
            outState.putLong(STATE_AMOUNT, tokenAmount)
        }
    }

    private fun updatePrimaryEnabled() {
        if (token != null) return
        binding.primaryButton.isEnabled = !isCreating && sourcePicker.current != null && amountField.isValid
    }

    private fun confirmCreate() {
        val amount = amountField.value
        val formatted = WithdrawUi.sats(amount)
        ConfirmationBottomSheet.show(
            supportFragmentManager,
            DialogHelper.ConfirmationConfig(
                title = getString(R.string.withdraw_ecash_confirm_title, formatted),
                message = getString(R.string.withdraw_ecash_confirm_message),
                confirmText = getString(R.string.withdraw_ecash_create),
                onConfirm = { createToken(amount) }
            )
        )
    }

    private fun createToken(amountSats: Long) {
        val source = sourcePicker.current ?: return
        if (amountSats <= 0 || amountSats > source.balance) return
        setCreating(true)

        lifecycleScope.launch {
            try {
                val wallet = CashuWalletManager.getWallet()
                    ?: throw IllegalStateException("Wallet not initialized")
                val unit = MintManager.getInstance(this@WithdrawEcashActivity).getPreferredUnit()
                val mintWallet = wallet.getWallet(MintUrl(source.mintUrl), CashuWalletManager.getCurrencyUnit(unit))
                    ?: throw IllegalStateException("No wallet for mint ${source.mintUrl}")

                val options = SendOptions(
                    memo = null,
                    conditions = null,
                    amountSplitTarget = SplitTarget.None,
                    sendKind = SendKind.OnlineTolerance(org.cashudevkit.Amount(0UL)),
                    includeFee = true,
                    maxProofs = null,
                    metadata = emptyMap(),
                    useP2bk = false,
                    p2pkSigningKeys = emptyList(),
                    p2pkLockedProofSendMode = P2pkLockedProofSendMode.SWAP,
                )
                val created = withContext(Dispatchers.IO) {
                    mintWallet.prepareSend(org.cashudevkit.Amount(amountSats.toULong()), options).confirm(null)
                }
                val encoded = created.encode()
                WalletLogger.log("OUT", amountSats, source.mintUrl, "Token created (export)")

                AutoWithdrawManager.getInstance(this@WithdrawEcashActivity).addManualWithdrawalEntry(
                    mintUrl = source.mintUrl,
                    amountSats = amountSats,
                    feeSats = 0L,
                    destination = getString(R.string.withdraw_recent_token),
                    destinationType = "manual_token",
                    status = WithdrawHistoryEntry.STATUS_COMPLETED,
                    token = encoded
                )
                BalanceRefreshBroadcast.send(this@WithdrawEcashActivity, BalanceRefreshBroadcast.REASON_WITHDRAWAL)
                setCreating(false)
                showResult(encoded, amountSats, animate = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating token", e)
                setCreating(false)
                WithdrawUi.snackbar(binding.primaryButton, getString(R.string.withdraw_ecash_error))
            }
        }
    }

    private fun setCreating(creating: Boolean) {
        isCreating = creating
        binding.topBar.setNavEnabled(!creating)
        amountField.setEnabled(!creating)
        binding.source.sourceRow.isEnabled = !creating
        WithdrawUi.setButtonBusy(
            binding.primaryButton,
            creating,
            getString(if (creating) R.string.withdraw_ecash_creating else R.string.withdraw_ecash_create)
        )
        updatePrimaryEnabled()
        if (creating) binding.primaryButton.isEnabled = true
    }

    private fun showResult(encoded: String, amountSats: Long, animate: Boolean) {
        token = encoded
        tokenAmount = amountSats

        if (animate) {
            TransitionManager.beginDelayedTransition(binding.root as ViewGroup, Fade().setDuration(FADE_MS))
        }
        binding.formContainer.visibility = View.GONE
        binding.resultContainer.visibility = View.VISIBLE
        binding.copyButton.visibility = View.VISIBLE
        binding.resultAmount.text = WithdrawUi.sats(amountSats)
        binding.tokenText.text = encoded
        binding.primaryButton.isEnabled = true
        binding.primaryButton.text = getString(R.string.withdraw_ecash_share)
        renderQr(encoded)
    }

    /**
     * Always dark modules on white, in both themes: an inverted code is rejected by
     * many scanners. Tokens too large for one code fall back to an animated QR.
     */
    private fun renderQr(encoded: String) {
        animatedQrJob?.cancel()
        lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.Default) {
                try {
                    QrCodeGenerator.generate(encoded, QR_SIZE_PX, Color.BLACK, Color.WHITE)
                } catch (e: Exception) {
                    Log.d(TAG, "Token too large for a single QR code; animating", e)
                    null
                }
            }
            if (bitmap != null) {
                binding.tokenQr.setImageBitmap(bitmap)
                binding.fullscreenQr.setImageBitmap(bitmap)
                binding.qrSpeedButton.visibility = View.GONE
            } else {
                startAnimatedQr(encoded)
            }
        }
    }

    private fun startAnimatedQr(encoded: String) {
        val encoder = try {
            Token.decode(encoded).urEncoder(null)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to build animated QR", e)
            return
        }
        binding.qrSpeedButton.visibility = View.VISIBLE
        animatedQrJob = lifecycleScope.launch {
            while (isActive) {
                val frame = withContext(Dispatchers.Default) {
                    QrCodeGenerator.generate(encoder.nextPart(), QR_SIZE_PX, Color.BLACK, Color.WHITE, roundedDots = false)
                }
                binding.tokenQr.setImageBitmap(frame)
                binding.fullscreenQr.setImageBitmap(frame)
                delay(QR_SPEEDS[qrSpeedIndex].second)
            }
        }
    }

    private fun updateSpeedLabel() {
        binding.qrSpeedButton.text =
            getString(R.string.withdraw_ecash_qr_speed, getString(QR_SPEEDS[qrSpeedIndex].first))
    }

    private fun setFullscreenQr(show: Boolean) {
        fullscreenBack.isEnabled = show
        val overlay = binding.fullscreenOverlay
        if (show) {
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(FADE_MS).withEndAction(null).start()
        } else {
            overlay.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction { overlay.visibility = View.GONE }
                .start()
        }
    }

    private fun shareToken() {
        val value = token ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        startActivity(Intent.createChooser(send, getString(R.string.withdraw_ecash_share)))
    }

    private fun copyToken() {
        val value = token ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.withdraw_recent_token), value))
        // Android 13+ confirms clipboard writes itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            WithdrawUi.snackbar(binding.primaryButton, getString(R.string.withdraw_cashu_copied))
        }
    }

    companion object {
        private const val TAG = "WithdrawEcash"
        private const val STATE_TOKEN = "token"
        private const val STATE_AMOUNT = "token_amount"
        private const val QR_SIZE_PX = 512
        private const val FADE_MS = 200L
        private const val DEFAULT_SPEED_INDEX = 1
        private val QR_SPEEDS = listOf(
            R.string.withdraw_animated_qr_speed_slow to 250L,
            R.string.withdraw_animated_qr_speed_normal to 100L,
            R.string.withdraw_animated_qr_speed_fast to 50L,
        )
    }
}
