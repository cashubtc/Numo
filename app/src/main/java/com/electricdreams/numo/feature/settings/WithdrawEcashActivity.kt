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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.isVisible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashudevkit.FfiException
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
 * hand to someone. Creating it is confirmed first because it spends immediately, and
 * once started it always finishes and is saved, so a token can never be lost.
 */
class WithdrawEcashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWithdrawEcashBinding
    private lateinit var sourcePicker: WithdrawSourcePicker
    private lateinit var amountField: WithdrawAmountField

    private var token: String? = null
    private var tokenAmount: Long = 0
    private var isCreating = false

    private var animatedQrJob: Job? = null
    private var maxProbeJob: Job? = null
    private var qrSpeedIndex = DEFAULT_SPEED_INDEX

    private val backHandler = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // While the token is being created, stay put; afterwards Back closes the QR.
            if (!isCreating) setFullscreenQr(false)
        }
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
            source?.let { probeMaxExportable(it) }
        }

        onBackPressedDispatcher.addCallback(this, backHandler)
        binding.topBar.onNavClick { onBackPressedDispatcher.onBackPressed() }
        binding.primaryButton.setOnClickListener { if (token == null) confirmCreate() else shareToken() }
        binding.copyButton.setOnClickListener { copyToken() }
        binding.tokenQr.setOnClickListener { setFullscreenQr(true) }
        ViewCompat.replaceAccessibilityAction(
            binding.tokenQr,
            AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.withdraw_ecash_qr_enlarge),
            null
        )
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
            sourcePicker.load()
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
        if (isCreating || supportFragmentManager.findFragmentByTag(CONFIRM_SHEET_TAG) != null) return
        val amount = amountField.value
        ConfirmationBottomSheet.show(
            supportFragmentManager,
            DialogHelper.ConfirmationConfig(
                title = getString(R.string.withdraw_ecash_confirm_title, WithdrawUi.sats(amount)),
                message = getString(R.string.withdraw_ecash_confirm_message),
                confirmText = getString(R.string.withdraw_ecash_create),
                onConfirm = { createToken(amount) }
            )
        )
    }

    private fun createToken(amountSats: Long) {
        val source = sourcePicker.current ?: return
        if (isCreating || amountSats <= 0 || amountSats > source.balance) return
        setCreating(true)

        lifecycleScope.launch {
            val encoded = try {
                // Spending and saving the token are one unit: once the mint has swapped the
                // proofs, the token must reach history even if this screen goes away.
                withContext(NonCancellable) { createAndSave(source, amountSats) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error creating token", e)
                setCreating(false)
                if (isInsufficientFunds(e)) {
                    amountField.setError(getString(R.string.withdraw_ecash_error_fee))
                } else {
                    WithdrawUi.snackbar(binding.primaryButton, getString(R.string.withdraw_ecash_error))
                }
                return@launch
            }
            setCreating(false)
            sourcePicker.load()
            showResult(encoded, amountSats, animate = true)
        }
    }

    private suspend fun createAndSave(source: WithdrawSource, amountSats: Long): String {
        val wallet = CashuWalletManager.getWallet() ?: throw IllegalStateException("Wallet not initialized")
        val encoded = withContext(Dispatchers.IO) {
            val unit = MintManager.getInstance(applicationContext).getPreferredUnit()
            val mintWallet = wallet.getWallet(MintUrl(source.mintUrl), CashuWalletManager.getCurrencyUnit(unit))
                ?: throw IllegalStateException("No wallet for mint ${source.mintUrl}")
            mintWallet.prepareSend(org.cashudevkit.Amount(amountSats.toULong()), sendOptions()).confirm(null).encode()
        }
        WalletLogger.log("OUT", amountSats, source.mintUrl, "Token created (export)")
        AutoWithdrawManager.getInstance(applicationContext).addManualWithdrawalEntry(
            mintUrl = source.mintUrl,
            amountSats = amountSats,
            feeSats = 0L,
            destination = getString(R.string.withdraw_recent_token),
            destinationType = "manual_token",
            status = WithdrawHistoryEntry.STATUS_COMPLETED,
            token = encoded
        )
        BalanceRefreshBroadcast.send(applicationContext, BalanceRefreshBroadcast.REASON_WITHDRAWAL)
        return encoded
    }

    /**
     * The mint charges a small per-proof fee on top of a token, so the whole balance can't
     * be exported. Find the largest amount that fits by preparing sends and cancelling them
     * (nothing is spent), stepping down from the full balance.
     */
    private fun probeMaxExportable(source: WithdrawSource) {
        maxProbeJob?.cancel()
        maxProbeJob = lifecycleScope.launch {
            // Every prepared send is cancelled even if the screen closes mid-probe.
            val max = withContext(NonCancellable) { findMaxExportable(source) }
            if (sourcePicker.current == source && token == null) {
                amountField.setAvailable(source.balance, maxSendable = max)
            }
        }
    }

    private suspend fun findMaxExportable(source: WithdrawSource): Long = withContext(Dispatchers.IO) {
        val wallet = CashuWalletManager.getWallet() ?: return@withContext source.balance
        val unit = MintManager.getInstance(applicationContext).getPreferredUnit()
        val mintWallet = wallet.getWallet(MintUrl(source.mintUrl), CashuWalletManager.getCurrencyUnit(unit))
            ?: return@withContext source.balance
        var amount = source.balance
        var step = 1L
        repeat(MAX_PROBES) {
            if (amount <= 0) return@withContext 0L
            val prepared = try {
                mintWallet.prepareSend(org.cashudevkit.Amount(amount.toULong()), sendOptions())
            } catch (e: Exception) {
                null
            }
            if (prepared != null) {
                try {
                    prepared.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to release a probe send", e)
                }
                return@withContext amount
            }
            amount -= step
            step *= 2
        }
        source.balance
    }

    private fun sendOptions() = SendOptions(
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

    private fun isInsufficientFunds(e: Exception): Boolean =
        e is FfiException.Cdk && e.errorMessage.contains("insufficient", ignoreCase = true)

    private fun setCreating(creating: Boolean) {
        isCreating = creating
        backHandler.isEnabled = creating || binding.fullscreenOverlay.isVisible
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

        if (animate) WithdrawUi.animateLayoutChange(binding.root)
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
            // Only cycle frames while the screen is visible.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val frame = withContext(Dispatchers.Default) {
                        QrCodeGenerator.generate(encoder.nextPart(), QR_SIZE_PX, Color.BLACK, Color.WHITE, roundedDots = false)
                    }
                    binding.tokenQr.setImageBitmap(frame)
                    binding.fullscreenQr.setImageBitmap(frame)
                    delay(QR_SPEEDS[qrSpeedIndex].second)
                }
            }
        }
    }

    private fun updateSpeedLabel() {
        binding.qrSpeedButton.text =
            getString(R.string.withdraw_ecash_qr_speed, getString(QR_SPEEDS[qrSpeedIndex].first))
    }

    private fun setFullscreenQr(show: Boolean) {
        val overlay = binding.fullscreenOverlay
        backHandler.isEnabled = show || isCreating
        // The page underneath is out of reach while the QR fills the screen.
        binding.page.importantForAccessibility = if (show) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        if (show) {
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(FADE_MS).withEndAction(null).start()
            binding.fullscreenClose.requestFocus()
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
        private const val CONFIRM_SHEET_TAG = "ConfirmationBottomSheet"
        private const val QR_SIZE_PX = 512
        private const val FADE_MS = 200L
        private const val DEFAULT_SPEED_INDEX = 1
        private const val MAX_PROBES = 8
        private val QR_SPEEDS = listOf(
            R.string.withdraw_animated_qr_speed_slow to 250L,
            R.string.withdraw_animated_qr_speed_normal to 100L,
            R.string.withdraw_animated_qr_speed_fast to 50L,
        )
    }
}
