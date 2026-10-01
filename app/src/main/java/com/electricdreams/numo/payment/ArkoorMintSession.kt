package com.electricdreams.numo.payment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.cashudevkit.Amount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintQuote
import org.cashudevkit.PaymentMethod
import org.cashudevkit.SplitTarget
import org.cashudevkit.WalletInterface

/** One checkout, backed by a CDK custom mint quote persisted in the wallet database. */
class ArkoorMintSession(
    private val wallet: WalletInterface,
    private val pollIntervalMs: Long = 2_000,
    private val nowSeconds: () -> ULong = { (System.currentTimeMillis() / 1_000).toULong() },
) {
    suspend fun receive(
        amountSats: Long,
        existingQuoteId: String? = null,
        onRequestReady: suspend (MintQuote) -> Unit,
        onRetry: suspend (Exception) -> Unit,
    ) {
        require(amountSats > 0) { "Arkoor amount must be positive" }
        val expected = amountSats.toULong()
        val initial = if (existingQuoteId != null) {
            // Load the locally stored quote, preserving its NUT-20 signing key.
            // Never create a replacement quote when resuming a checkout.
            wallet.checkMintQuote(existingQuoteId)
        } else {
            val settings = wallet.fetchMintInfo()?.nuts?.nut04
            check(settings != null && !settings.disabled) { "Minting is disabled" }
            val method = settings.methods.firstOrNull {
                it.method == METHOD && it.unit == CurrencyUnit.Sat
            } ?: error("This mint does not support Arkoor payments in sats")
            method.minAmount?.value?.let { check(expected >= it) { "Below mint minimum: $it sats" } }
            method.maxAmount?.value?.takeIf { it > 0uL }?.let {
                check(expected <= it) { "Above mint maximum: $it sats" }
            }
            wallet.mintQuote(METHOD, Amount(expected), null, null)
        }
        validateQuote(initial, expected)
        onRequestReady(initial)

        while (true) {
            try {
                val quote = wallet.checkMintQuote(initial.id)
                validateQuote(quote, expected)
                // Custom quotes can receive several partial payments. A PAID state
                // alone does not establish that the checkout amount was received.
                if (quote.amountIssued.value >= expected) {
                    // CDK status checks recover interrupted mint operations. Do not
                    // report success while that recovery still owns the quote.
                    if (quote.usedByOperation == null) return
                    delay(pollIntervalMs)
                    continue
                }
                if (quote.amountPaid.value >= expected) {
                    val proofs = wallet.mint(quote.id, SplitTarget.None, null)
                    val minted = proofs.sumOf { it.amount.value }
                    if (quote.amountIssued.value + minted >= expected) return
                } else if (quote.expiry > 0uL && quote.expiry <= nowSeconds()) {
                    error("Arkoor request expired; reopen this payment to check for late funds")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                throw error
            } catch (error: Exception) {
                // A transient status or mint failure must keep the same quote alive.
                onRetry(error)
            }
            delay(pollIntervalMs)
        }
    }

    private fun validateQuote(quote: MintQuote, expected: ULong) {
        check(quote.paymentMethod == METHOD && quote.unit == CurrencyUnit.Sat) {
            "The saved quote is not an Arkoor payment in sats"
        }
        check(quote.amount?.value == expected) { "The quote amount does not match this checkout" }
        check(quote.request.startsWith("ark1") || quote.request.startsWith("tark1")) {
            "The mint did not return an Ark address"
        }
    }

    companion object {
        val METHOD = PaymentMethod.Custom("arkoor")
    }
}
