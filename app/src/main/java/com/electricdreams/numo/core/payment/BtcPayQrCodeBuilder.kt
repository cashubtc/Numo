package com.electricdreams.numo.core.payment

import android.util.Base64
import android.util.Log
import com.upokecenter.cbor.CBORObject

/**
 * Builds the QR code content strings for BTCPay payment tabs.
 *
 * Extracted from PaymentRequestActivity so the logic can be unit- and
 * integration-tested without spinning up the full Activity.
 *
 * Cashu tab QR:   [prepareCashuQrContent] → first element (creqA)
 * Unified tab QR: [buildUnifiedUri] → BIP321 URI containing bech32m creq
 */
object BtcPayQrCodeBuilder {

    private const val TAG = "BtcPayQrCodeBuilder"

    /**
     * Processes a raw creqA from BTCPay and returns the string that goes into
     * the Cashu tab QR code, plus its bech32m equivalent for the Unified tab.
     *
     * - If [rawCashuPR] already contains an amount field ("a") it is returned
     *   unchanged (BTCPay always includes it; this is a safety check).
     * - Otherwise [amount] is injected into the CBOR map.  Transports ("t")
     *   are stripped in both the injection path — they are not needed in the
     *   QR and some wallets reject a payment request that has them.
     *
     * @return (cborCreqA, bech32m) — bech32m is null when CDK conversion fails
     */
    fun prepareCashuQrContent(rawCashuPR: String, amount: Long): Pair<String, String?> {
        val cbor = try {
            val decoded = org.cashudevkit.PaymentRequest.fromString(rawCashuPR)
            if (decoded.amount() != null) {
                rawCashuPR
            } else {
                val map = CBORObject.NewMap()
                decoded.paymentId()?.let { map.Add("i", it) }
                map.Add("a", amount)
                decoded.unit()?.let { u ->
                    map.Add("u", when (u) {
                        is org.cashudevkit.CurrencyUnit.Sat -> "sat"
                        is org.cashudevkit.CurrencyUnit.Msat -> "msat"
                        is org.cashudevkit.CurrencyUnit.Eur -> "eur"
                        is org.cashudevkit.CurrencyUnit.Usd -> "usd"
                        is org.cashudevkit.CurrencyUnit.Custom -> u.unit
                        else -> "sat"
                    })
                }
                decoded.description()?.let { map.Add("d", it) }
                decoded.singleUse()?.let { map.Add("s", it) }
                val mints = decoded.mints()
                if (mints.isNotEmpty()) {
                    val mintsArray = CBORObject.NewArray()
                    mints.forEach { mintsArray.Add(it) }
                    map.Add("m", mintsArray)
                }
                "creqA" + Base64.encodeToString(
                    map.EncodeToBytes(),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "prepareCashuQrContent: could not inject amount: ${e.message}")
            rawCashuPR
        }

        val bech32 = try {
            org.cashudevkit.PaymentRequest.fromString(cbor).toBech32String()
        } catch (e: Exception) {
            Log.w(TAG, "prepareCashuQrContent: could not convert to bech32m: ${e.message}")
            null
        }

        return cbor to bech32
    }

    /**
     * Builds the BIP321 URI that goes into the Unified tab QR code.
     *
     * [cashuCreqOrBech32] should be the bech32m form when available (from
     * [prepareCashuQrContent].second); the raw creqA is accepted as fallback.
     */
    fun buildUnifiedUri(cashuCreqOrBech32: String, bolt11: String): String =
        org.cashudevkit.createBip321Uri(cashuCreqOrBech32, bolt11, null)
}
