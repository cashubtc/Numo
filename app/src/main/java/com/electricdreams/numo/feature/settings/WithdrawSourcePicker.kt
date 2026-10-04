package com.electricdreams.numo.feature.settings

import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.electricdreams.numo.R
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.databinding.ComponentWithdrawSourceBinding
import com.electricdreams.numo.ui.components.MintSelectionBottomSheet

/** A mint the merchant can withdraw from, with its current balance in sats. */
data class WithdrawSource(val mintUrl: String, val balance: Long)

/**
 * Drives the shared "From" row. Defaults to the best-funded mint (or the one the
 * caller asked for) and only becomes a picker when more than one mint holds money.
 */
class WithdrawSourcePicker(
    private val activity: AppCompatActivity,
    private val binding: ComponentWithdrawSourceBinding,
    private val onChanged: (WithdrawSource?) -> Unit,
) {

    var current: WithdrawSource? = null
        private set

    private var funded: Map<String, Long> = emptyMap()
    private val mintManager = MintManager.getInstance(activity)

    init {
        binding.sourceRow.setOnClickListener { showPicker() }
    }

    /** Loads balances; keeps the current mint when it still holds money. */
    fun load(preferredMintUrl: String? = current?.mintUrl) {
        activity.lifecycleScope.launch {
            val balances = try {
                withContext(Dispatchers.IO) { CashuWalletManager.getAllMintBalances() }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to load mint balances", e)
                emptyMap()
            }
            funded = balances.filterValues { it > 0 }
            val chosenUrl = preferredMintUrl?.removeSuffix("/")?.takeIf { funded.containsKey(it) }
                ?: funded.maxByOrNull { it.value }?.key
            select(chosenUrl?.let { WithdrawSource(it, funded.getValue(it)) })
        }
    }

    private fun select(source: WithdrawSource?) {
        current = source
        val canChoose = funded.size > 1
        binding.sourceChevron.visibility = if (canChoose) View.VISIBLE else View.GONE
        binding.sourceRow.isClickable = canChoose
        binding.sourceRow.isFocusable = canChoose
        ViewCompat.replaceAccessibilityAction(
            binding.sourceRow,
            AccessibilityActionCompat.ACTION_CLICK,
            if (canChoose) activity.getString(R.string.withdraw_from_change_hint) else null,
            null
        )

        if (source == null) {
            binding.sourceName.text = activity.getString(R.string.withdraw_error_no_source)
            binding.sourceAvailable.visibility = View.GONE
        } else {
            binding.sourceName.text = mintManager.getMintDisplayName(source.mintUrl)
            binding.sourceAvailable.visibility = View.VISIBLE
            binding.sourceAvailable.text = activity.getString(
                R.string.withdraw_from_available,
                Amount(source.balance, Amount.Currency.BTC).toString()
            )
        }
        onChanged(source)
    }

    private fun showPicker() {
        if (funded.size < 2) return
        MintSelectionBottomSheet.newInstance(
            mintBalances = funded,
            selectedMintUrl = current?.mintUrl,
            listener = object : MintSelectionBottomSheet.OnMintSelectedListener {
                override fun onMintSelected(mintUrl: String, balance: Long) {
                    select(WithdrawSource(mintUrl, balance))
                }
            }
        ).show(activity.supportFragmentManager, SHEET_TAG)
    }

    companion object {
        private const val TAG = "WithdrawSourcePicker"
        private const val SHEET_TAG = "WithdrawSourceSheet"
    }
}
