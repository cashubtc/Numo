package com.electricdreams.numo.feature.settings

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.BalanceRefreshBroadcast
import com.electricdreams.numo.databinding.ActivityWithdrawSuccessBinding
import com.electricdreams.numo.ui.util.applySettingsWindowInsets

/**
 * Sent: mirrors the payment-received screen so money leaving reads like money
 * arriving, then adds the fee actually paid and what is left.
 */
class WithdrawSuccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWithdrawSuccessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWithdrawSuccessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsWindowInsets(this, binding.root)

        val amount = intent.getLongExtra(EXTRA_AMOUNT, 0)
        val feePaid = intent.getLongExtra(EXTRA_FEE_PAID, 0)
        val address = intent.getStringExtra(EXTRA_LIGHTNING_ADDRESS)

        binding.amountText.text = getString(R.string.withdraw_success_amount, WithdrawUi.sats(amount))
        binding.destinationText.text = if (address.isNullOrBlank()) {
            getString(R.string.withdraw_review_to_invoice)
        } else {
            getString(R.string.withdraw_review_to, address)
        }
        binding.feeValue.text = WithdrawUi.sats(feePaid)
        loadRemainingBalance()

        binding.doneButton.setOnClickListener { returnToWithdraw() }
        binding.closeIconButton.setOnClickListener { returnToWithdraw() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnToWithdraw()
        })

        if (savedInstanceState == null) {
            BalanceRefreshBroadcast.send(this, BalanceRefreshBroadcast.REASON_WITHDRAWAL)
            binding.checkmarkIcon.postDelayed({ animateCheckmark() }, CHECK_DELAY_MS)
        } else {
            binding.checkmarkCircle.visibility = View.VISIBLE
            binding.checkmarkIcon.visibility = View.VISIBLE
        }
    }

    private fun loadRemainingBalance() {
        // Reserve the row's space up front and fade the value in, so nothing shifts.
        binding.balanceValue.alpha = 0f
        lifecycleScope.launch {
            val total = try {
                withContext(Dispatchers.IO) { CashuWalletManager.getAllMintBalances().values.sum() }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to load remaining balance", e)
                null
            }
            if (total == null) {
                binding.balanceRow.visibility = View.INVISIBLE
                return@launch
            }
            binding.balanceValue.text = WithdrawUi.sats(total)
            binding.balanceValue.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    /** Back on the Withdraw hub, with Send and Review cleared from the back stack. */
    private fun returnToWithdraw() {
        startActivity(
            Intent(this, WithdrawActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    /** Same celebration as payment received: the circle grows, then the check lands. */
    private fun animateCheckmark() {
        val circle = binding.checkmarkCircle
        val icon = binding.checkmarkIcon
        circle.alpha = 0f
        circle.scaleX = 0.3f
        circle.scaleY = 0.3f
        circle.visibility = View.VISIBLE
        icon.alpha = 0f
        icon.scaleX = 0f
        icon.scaleY = 0f
        icon.visibility = View.VISIBLE

        val circleEase = DecelerateInterpolator(2f)
        val iconEase = OvershootInterpolator(3f)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(circle, View.SCALE_X, 0.3f, 1f).setDuration(400).apply { interpolator = circleEase },
                ObjectAnimator.ofFloat(circle, View.SCALE_Y, 0.3f, 1f).setDuration(400).apply { interpolator = circleEase },
                ObjectAnimator.ofFloat(circle, View.ALPHA, 0f, 1f).setDuration(350),
                ObjectAnimator.ofFloat(icon, View.SCALE_X, 0f, 1f).setDuration(500).apply {
                    startDelay = 150
                    interpolator = iconEase
                },
                ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0f, 1f).setDuration(500).apply {
                    startDelay = 150
                    interpolator = iconEase
                },
                ObjectAnimator.ofFloat(icon, View.ALPHA, 0f, 1f).setDuration(300).apply { startDelay = 150 }
            )
            start()
        }
    }

    companion object {
        private const val TAG = "WithdrawSuccess"
        private const val CHECK_DELAY_MS = 100L
        private const val FADE_MS = 200L
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_FEE_PAID = "fee_paid"
        const val EXTRA_LIGHTNING_ADDRESS = "lightning_address"
    }
}
