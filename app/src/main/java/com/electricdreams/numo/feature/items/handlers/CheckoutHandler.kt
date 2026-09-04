package com.electricdreams.numo.feature.items.handlers

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.electricdreams.numo.PaymentRequestActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.AssetId
import com.electricdreams.numo.core.model.AtomicAmount
import com.electricdreams.numo.core.model.BasketNormalizationFailureReason
import com.electricdreams.numo.core.model.BasketNormalizationResult
import com.electricdreams.numo.core.model.BasketPriceLine
import com.electricdreams.numo.core.model.BasketPricingEngine
import com.electricdreams.numo.core.model.CheckoutBasket
import com.electricdreams.numo.core.model.UnitAmountFormatter
import com.electricdreams.numo.core.model.UnitConversionRate
import com.electricdreams.numo.core.model.UnitDescriptor
import com.electricdreams.numo.core.model.UnitId
import com.electricdreams.numo.core.util.BasketManager
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker
import com.electricdreams.numo.feature.tips.TipSelectionActivity
import com.electricdreams.numo.feature.tips.TipsManager
import java.math.BigDecimal

/** Handles unit-aware basket normalization and navigation to payment. */
class CheckoutHandler(
    private val activity: Activity,
    private val basketManager: BasketManager,
    private val currencyManager: CurrencyManager,
    private val bitcoinPriceWorker: BitcoinPriceWorker,
) {

    var savedBasketId: String? = null

    private var chargeUnitDialog: AlertDialog? = null

    fun proceedToCheckout() {
        if (basketManager.getTotalItemCount() == 0) {
            Toast.makeText(activity, R.string.pos_toast_basket_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val legacyFiatUnit = currencyManager.getCurrentCurrency()
        val lines = try {
            basketManager.getPriceLines(legacyFiatUnit)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid unit-aware basket price", e)
            showError(R.string.checkout_unit_invalid_item_price)
            return
        } catch (e: ArithmeticException) {
            Log.e(TAG, "Basket price overflow", e)
            showError(R.string.checkout_unit_amount_too_large)
            return
        }

        val engine = BasketPricingEngine(createBitcoinRates(legacyFiatUnit))
        val mintManager = MintManager.getInstance(activity)
        val targetAssets = createTargetAssets(mintManager)
        val chargeableOptions = engine.chargeableTargets(lines, targetAssets)
            .filter { it.amount.value > 0L }
            .sortedWith(
                compareBy<BasketNormalizationResult.Chargeable> {
                    if (it.amount.unit.value == mintManager.getPreferredUnit()) 0 else 1
                }.thenBy { it.amount.unit.value }
                    .thenBy { it.amount.asset.issuerScope.orEmpty() },
            )

        when {
            chargeableOptions.isEmpty() -> showNoChargeableUnit(engine, lines, targetAssets)
            chargeableOptions.size == 1 -> continueCheckout(
                chargeableOptions.single(),
                legacyFiatUnit,
            )
            else -> showChargeUnitSelector(chargeableOptions, legacyFiatUnit)
        }
    }

    private fun createBitcoinRates(legacyFiatUnit: String): List<UnitConversionRate> {
        val bitcoinPrice = bitcoinPriceWorker.getCurrentPrice()
        val fiatUnit = UnitId.ofOrNull(legacyFiatUnit) ?: return emptyList()
        if (bitcoinPrice <= 0.0 || !bitcoinPrice.isFinite()) return emptyList()

        val timestamp = bitcoinPriceWorker.getCurrentPriceTimestamp()
        val expiresAt = if (timestamp > 0L) {
            runCatching { Math.addExact(timestamp, BITCOIN_QUOTE_MAX_AGE_MS) }.getOrNull()
        } else {
            0L
        }
        return runCatching {
            UnitConversionRate.fromBitcoinPrice(
                fiatUnit = fiatUnit,
                fiatPerBitcoin = BigDecimal.valueOf(bitcoinPrice),
                expiresAtMillis = expiresAt,
                provider = "bitcoin-price-worker",
            )
        }.getOrElse { error ->
            Log.e(TAG, "Could not build Bitcoin conversion quote", error)
            emptyList()
        }
    }

    /** Build only targets backed by at least one currently added, compatible mint. */
    private fun createTargetAssets(
        mintManager: MintManager,
    ): List<AssetId> {
        return mintManager.getSupportedChargeAssets()
    }

    private fun showChargeUnitSelector(
        options: List<BasketNormalizationResult.Chargeable>,
        legacyFiatUnit: String,
    ) {
        if (activity.isFinishing || chargeUnitDialog?.isShowing == true) return

        val labels = options.map { option -> formatOption(option.amount) }.toTypedArray()
        chargeUnitDialog = AlertDialog.Builder(activity, R.style.Theme_Numo_Dialog)
            .setTitle(R.string.checkout_charge_unit_title)
            .setMessage(R.string.checkout_charge_unit_message)
            .setItems(labels) { dialog, index ->
                dialog.dismiss()
                continueCheckout(options[index], legacyFiatUnit)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { chargeUnitDialog = null }
                dialog.show()
            }
    }

    private fun formatOption(amount: AtomicAmount): String {
        return UnitAmountFormatter.formatAsset(amount)
    }

    private fun showNoChargeableUnit(
        engine: BasketPricingEngine,
        lines: List<BasketPriceLine>,
        targets: List<AssetId>,
    ) {
        val results = targets.map { engine.normalize(lines, it) }
        val hasOverflow = results.any { it is BasketNormalizationResult.ArithmeticFailure }
        val hasStaleConversion = results
            .filterIsInstance<BasketNormalizationResult.Unsupported>()
            .flatMap { it.failures }
            .any { it.reason == BasketNormalizationFailureReason.STALE_CONVERSION }
        val message = when {
            hasOverflow -> R.string.checkout_unit_amount_too_large
            hasStaleConversion -> R.string.checkout_unit_conversion_stale
            else -> R.string.checkout_unit_conversion_unavailable
        }
        showError(message)
    }

    private fun continueCheckout(
        normalization: BasketNormalizationResult.Chargeable,
        legacyFiatUnit: String,
    ) {
        val chargeAmount = normalization.amount
        if (chargeAmount.value <= 0L) {
            showError(R.string.pos_toast_invalid_amount)
            return
        }

        val formattedAmount = UnitAmountFormatter.format(
            chargeAmount,
            UnitDescriptor.defaultFor(chargeAmount.unit),
        )
        val bitcoinPrice = bitcoinPriceWorker.getCurrentPrice().takeIf { it > 0.0 }
        val checkoutBasket = CheckoutBasket.fromBasketManager(
            basketManager = basketManager,
            currency = legacyFiatUnit,
            bitcoinPrice = bitcoinPrice,
            // Kept populated for backward-compatible receipt readers. New readers use
            // chargeAmountAtomic and chargeUnit.
            totalSatoshis = chargeAmount.value,
            chargeAmount = chargeAmount,
        )
        val checkoutBasketJson = checkoutBasket.toJson()

        Log.d(
            TAG,
            "Captured ${checkoutBasket.items.size} basket items; charge=" +
                "${chargeAmount.value} ${chargeAmount.unit}",
        )

        // Clear only after normalization and, where needed, an explicit unit choice succeeded.
        basketManager.clearBasket()

        val destination = if (TipsManager.getInstance(activity).tipsEnabled) {
            TipSelectionActivity::class.java
        } else {
            PaymentRequestActivity::class.java
        }
        val intent = Intent(activity, destination).apply {
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_AMOUNT, chargeAmount.value)
            putExtra(PaymentRequestActivity.EXTRA_PAYMENT_UNIT, chargeAmount.unit.value)
            chargeAmount.asset.issuerScope?.let {
                putExtra(PaymentRequestActivity.EXTRA_PAYMENT_ISSUER_SCOPE, it)
            }
            putExtra(PaymentRequestActivity.EXTRA_FORMATTED_AMOUNT, formattedAmount)
            putExtra(PaymentRequestActivity.EXTRA_CHECKOUT_BASKET_JSON, checkoutBasketJson)
            savedBasketId?.let { putExtra(PaymentRequestActivity.EXTRA_SAVED_BASKET_ID, it) }
        }
        activity.startActivity(intent)
        activity.finish()
    }

    private fun showError(message: Int) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "CheckoutHandler"
        private const val BITCOIN_QUOTE_MAX_AGE_MS = 5 * 60 * 1_000L
    }
}
