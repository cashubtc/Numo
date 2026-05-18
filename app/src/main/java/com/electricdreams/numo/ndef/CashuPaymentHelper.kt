package com.electricdreams.numo.ndef

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.payment.SwapToLightningMintManager
import com.google.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.ReceiveOptions
import org.cashudevkit.SplitTarget
import org.cashudevkit.Token as CdkToken
import java.math.BigInteger
import java.util.Optional

/**
 * Helper class for Cashu payment-related operations.
 *
 * Payment request creation is handled via cashu-jdk. Token-level validation
 * and redemption are implemented using the CDK (Cashu Development Kit)
 * MultiMintWallet and Token types.
 */
object CashuPaymentHelper {

    private const val TAG = "CashuPaymentHelper"

    /**
     * Structured result of validating a Cashu token against an expected
     * amount and the merchant's allowed mint list.
     */
        data class GeneratedPaymentRequest(
        val original: String,
        val bech32: String
    )

    sealed class TokenValidationResult {
        /** The provided string was not a valid Cashu token. */
        object InvalidFormat : TokenValidationResult()

        /**
         * The token is structurally valid, from a mint that is in the
         * allowed-mints list, and has sufficient amount.
         */
        data class ValidKnownMint(val token: CdkToken) : TokenValidationResult()

        /**
         * The token is structurally valid and has sufficient amount, but the
         * mint URL is not in the allowed-mints list. This is the entry point
         * for SwapToLightningMint flows.
         */
        data class ValidUnknownMint(
            val token: CdkToken,
            val mintUrl: String
        ) : TokenValidationResult()

        /** The token is valid but does not contain enough value for this payment. */
        data class InsufficientAmount(val required: Long, val actual: Long) : TokenValidationResult()
    }

    // === Payment request creation (cashu-jdk) ===============================

    @JvmStatic
    fun createPaymentRequest(
        amount: Long,
        description: String?,
        allowedMints: List<String>?,
    ): GeneratedPaymentRequest? {
        return try {
            val map = com.upokecenter.cbor.CBORObject.NewMap()
            map.Add("i", java.util.UUID.randomUUID().toString().substring(0, 8))
            map.Add("a", amount)
            map.Add("u", "sat")
            map.Add("d", description ?: "Payment for $amount sats")
            map.Add("s", true)
            if (!allowedMints.isNullOrEmpty()) {
                val mintsArray = com.upokecenter.cbor.CBORObject.NewArray()
                allowedMints.forEach { mintsArray.Add(it) }
                map.Add("m", mintsArray)
            }
            
            val cborBytes = map.EncodeToBytes()
            val encoded = "creqA" + android.util.Base64.encodeToString(cborBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            
            try {
                val cdkRequest = org.cashudevkit.PaymentRequest.fromString(encoded)
                val bech32 = cdkRequest.toBech32String()
                Log.d(TAG, "Converted to bech32: $bech32")
                GeneratedPaymentRequest(encoded, bech32)
            } catch (e: Exception) {
                Log.w(TAG, "Fallback: could not convert to bech32 via CDK: ${e.message}")
                GeneratedPaymentRequest(encoded, encoded)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PaymentRequest", e)
            null
        }
    }

    fun createPaymentRequest(amount: Long, description: String?): GeneratedPaymentRequest? =
        createPaymentRequest(amount, description, null)

    @JvmStatic
    fun createPaymentRequestWithNostr(
        amount: Long,
        description: String?,
        allowedMints: List<String>?,
        nprofile: String,
    ): GeneratedPaymentRequest? {
        return try {
            val map = com.upokecenter.cbor.CBORObject.NewMap()
            map.Add("i", java.util.UUID.randomUUID().toString().substring(0, 8))
            map.Add("a", amount)
            map.Add("u", "sat")
            map.Add("d", description ?: "Payment for $amount sats")
            map.Add("s", true)
            if (!allowedMints.isNullOrEmpty()) {
                val mintsArray = com.upokecenter.cbor.CBORObject.NewArray()
                allowedMints.forEach { mintsArray.Add(it) }
                map.Add("m", mintsArray)
            }
            
            val nostrTransport = com.upokecenter.cbor.CBORObject.NewMap()
            nostrTransport.Add("t", "nostr")
            nostrTransport.Add("a", nprofile)
            val gArrayOuter = com.upokecenter.cbor.CBORObject.NewArray()
            val gArrayInner = com.upokecenter.cbor.CBORObject.NewArray()
            gArrayInner.Add("n")
            gArrayInner.Add("17")
            gArrayOuter.Add(gArrayInner)
            nostrTransport.Add("g", gArrayOuter)
            
            val transportsArray = com.upokecenter.cbor.CBORObject.NewArray()
            transportsArray.Add(nostrTransport)
            map.Add("t", transportsArray)
            
            val cborBytes = map.EncodeToBytes()
            val encoded = "creqA" + android.util.Base64.encodeToString(cborBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

            try {
                val cdkRequest = org.cashudevkit.PaymentRequest.fromString(encoded)
                val bech32 = cdkRequest.toBech32String()
                Log.d(TAG, "Converted Nostr to bech32: $bech32")
                GeneratedPaymentRequest(encoded, bech32)
            } catch (e: Throwable) {
                Log.e(TAG, "Error converting to bech32, returning CREQA format: ${e.message}", e)
                GeneratedPaymentRequest(encoded, encoded)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Nostr payment request: ${e.message}", e)
            null
        }
    }

    // === Token helpers =====================================================

    @JvmStatic
    fun isCashuToken(text: String?): Boolean {
        if (text == null) {
            return false
        }

        return text.startsWith("cashuA") ||
            text.startsWith("cashuB") ||
            // Binary-encoded Cashu tokens produced by CDK encode() after
            // Token.from_raw_bytes(...) use the crawB prefix. Treat them as
            // first-class Cashu tokens for validation and redemption.
            text.startsWith("crawB")
    }

    @JvmStatic
    fun extractCashuToken(text: String?): String? {
        if (text == null) {
            Log.i(TAG, "extractCashuToken: Input text is null")
            return null
        }

        if (isCashuToken(text)) {
            Log.i(TAG, "extractCashuToken: Input is already a Cashu token")
            return text
        }

        Log.i(TAG, "extractCashuToken: Analyzing text: $text")

        if (text.contains("#token=cashu")) {
            Log.i(TAG, "extractCashuToken: Found #token=cashu pattern")
            val tokenStart = text.indexOf("#token=cashu")
            val cashuStart = tokenStart + 7
            val cashuEnd = text.length

            val token = text.substring(cashuStart, cashuEnd)
            Log.i(TAG, "extractCashuToken: Extracted token from URL fragment: $token")
            return token
        }

        if (text.contains("token=cashu")) {
            Log.i(TAG, "extractCashuToken: Found token=cashu pattern")
            val tokenStart = text.indexOf("token=cashu")
            val cashuStart = tokenStart + 6
            var cashuEnd = text.length
            val ampIndex = text.indexOf('&', cashuStart)
            val hashIndex = text.indexOf('#', cashuStart)

            if (ampIndex > cashuStart && ampIndex < cashuEnd) cashuEnd = ampIndex
            if (hashIndex > cashuStart && hashIndex < cashuEnd) cashuEnd = hashIndex

            val token = text.substring(cashuStart, cashuEnd)
            Log.i(TAG, "extractCashuToken: Extracted token from URL parameter: $token")
            return token
        }

        val prefixes = arrayOf("cashuA", "cashuB", "crawB")
        for (prefix in prefixes) {
            val tokenIndex = text.indexOf(prefix)
            if (tokenIndex >= 0) {
                Log.i(TAG, "extractCashuToken: Found $prefix at position $tokenIndex")
                var endIndex = text.length
                for (i in tokenIndex + prefix.length until text.length) {
                    val c = text[i]
                    if (c.isWhitespace() || c == '"' || c == '\'' || c == '<' || c == '>' || c == '&' || c == '#') {
                        endIndex = i
                        break
                    }
                }
                val token = text.substring(tokenIndex, endIndex)
                Log.i(TAG, "extractCashuToken: Extracted token from text: $token")
                return token
            }
        }

        Log.i(TAG, "extractCashuToken: No Cashu token found in text")
        return null
    }

    @JvmStatic
    fun isCashuPaymentRequest(text: String?): Boolean =
        text != null && (text.startsWith("creqA") || text.lowercase().startsWith("creqb"))

    // === Validation using CDK Token ========================================

    /**
     * Legacy boolean validation API kept for backward compatibility.
     * Prefer [validateTokenDetailed] in new code.
     */
    @JvmStatic
    fun validateToken(
        tokenString: String?,
        expectedAmount: Long,
        allowedMints: List<String>?,
    ): Boolean {
        return when (val result = validateTokenDetailed(tokenString, expectedAmount, allowedMints)) {
            is TokenValidationResult.ValidKnownMint -> true
            else -> false
        }
    }

    @JvmStatic
    fun validateToken(tokenString: String?, expectedAmount: Long): Boolean =
        validateToken(tokenString, expectedAmount, null)

    /**
     * Structured validation that distinguishes known vs unknown mints and
     * insufficient amounts.
     */
    @JvmStatic
    fun validateTokenDetailed(
        tokenString: String?,
        expectedAmount: Long,
        allowedMints: List<String>?,
    ): TokenValidationResult {
        if (!isCashuToken(tokenString)) {
            Log.e(TAG, "Invalid token format (not a Cashu token)")
            return TokenValidationResult.InvalidFormat
        }

        return try {
            val token = CdkToken.decode(
                tokenString ?: error("tokenString is null"),
            )

            if (token.unit() != CurrencyUnit.Sat) {
                Log.e(TAG, "Unsupported token unit: ${token.unit()}")
                return TokenValidationResult.InvalidFormat
            }

            val mintUrl = token.mintUrl().url

            if (!allowedMints.isNullOrEmpty()) {
                if (!allowedMints.contains(mintUrl)) {
                    Log.w(TAG, "Token mint is not in allowed list: $mintUrl")
                    // We still validate amount so swap flow can decide whether to proceed
                    val tokenAmount = token.value().value.toLong()
                    if (tokenAmount < expectedAmount) {
                        Log.e(
                            TAG,
                            "Unknown-mint token has insufficient amount: required=$expectedAmount, actual=$tokenAmount",
                        )
                        return TokenValidationResult.InsufficientAmount(expectedAmount, tokenAmount)
                    }
                    return TokenValidationResult.ValidUnknownMint(token, mintUrl)
                } else {
                    Log.d(TAG, "Token mint validated as allowed: $mintUrl")
                }
            }

            val tokenAmount = token.value().value.toLong()

            if (tokenAmount < expectedAmount) {
                Log.e(
                    TAG,
                    "Amount was insufficient: $expectedAmount sats required but $tokenAmount sats provided",
                )
                return TokenValidationResult.InsufficientAmount(expectedAmount, tokenAmount)
            }

            Log.d(TAG, "Token format validation passed using CDK Token; amount=$tokenAmount sats")
            return TokenValidationResult.ValidKnownMint(token)
        } catch (e: Exception) {
            Log.e(TAG, "Token validation failed: ${e.message}", e)
            return TokenValidationResult.InvalidFormat
        }
    }

    // === Redemption using CDK MultiMintWallet ==============================

    @JvmStatic
    @Throws(RedemptionException::class)
    suspend fun redeemToken(tokenString: String?): String = withContext(Dispatchers.IO) {
        if (!isCashuToken(tokenString)) {
            val errorMsg = "Cannot redeem: Invalid token format"
            Log.e(TAG, errorMsg)
            throw RedemptionException(errorMsg)
        }

        try {
            val cdkToken = CdkToken.decode(
                tokenString ?: error("tokenString is null"),
            )

            val mintUrl = cdkToken.mintUrl().url
            val unit = cdkToken.unit().let { tokenUnit ->
                when (tokenUnit) {
                    is CurrencyUnit.Sat -> "sat"
                    is CurrencyUnit.Msat -> "msat"
                    is CurrencyUnit.Eur -> "eur"
                    is CurrencyUnit.Usd -> "usd"
                    is CurrencyUnit.Custom -> tokenUnit.unit
                    else -> "sat"
                }
            }
            if (unit != "sat") {
                throw RedemptionException("Unsupported token unit: $unit")
            }

            val wallet = CashuWalletManager.getWallet()
                ?: throw RedemptionException("CDK wallet not initialized")

            val mintWallet = wallet.getWallet(cdkToken.mintUrl(), cdkToken.unit() ?: CurrencyUnit.Sat)
                ?: throw RedemptionException("Failed to get wallet for mint: ${cdkToken.mintUrl().url}")

            val receiveOptions = org.cashudevkit.ReceiveOptions(
                amountSplitTarget = org.cashudevkit.SplitTarget.None,
                p2pkSigningKeys = emptyList(),
                preimages = emptyList(),
                metadata = emptyMap()
            )

            mintWallet.receive(cdkToken, receiveOptions)

            Log.d(TAG, "Token received via CDK successfully (mintUrl=$mintUrl)")
            // Return the original token instead of sending a new one
            tokenString
        } catch (e: RedemptionException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Token redemption via CDK failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        }
    }

    @Throws(RedemptionException::class)
    private suspend fun redeemProofs(proofs: List<org.cashudevkit.Proof>, mintUrl: String, unit: String) {
        if (unit != "sat") {
            throw RedemptionException("Unsupported token unit: $unit")
        }

        val wallet = CashuWalletManager.getWallet()
            ?: throw RedemptionException("CDK wallet not initialized")

        val mintWallet = wallet.getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)
            ?: throw RedemptionException("Failed to get wallet for mint: $mintUrl")

        val receiveOptions = ReceiveOptions(
            amountSplitTarget = SplitTarget.None,
            p2pkSigningKeys = emptyList(),
            preimages = emptyList(),
            metadata = emptyMap(),
        )

        mintWallet.receiveProofs(proofs, receiveOptions, null, null)
    }

    // === High-level redemption with optional swap-to-Lightning-mint ========

    /**
     * Common logic for redeeming a list of proofs, optionally swapping if the mint is unknown.
     */
    @Throws(RedemptionException::class)
    suspend fun redeemProofsWithSwap(
        appContext: Context,
        proofs: List<org.cashudevkit.Proof>,
        mintUrl: String,
        unit: String,
        expectedAmount: Long,
        allowedMints: List<String>?,
        paymentContext: SwapToLightningMintManager.PaymentContext
    ): String {
        if (unit != "sat") {
            throw RedemptionException("Unsupported token unit: $unit")
        }

        val tokenAmount = proofs.map { it.amount.value.toLong() }.sum()
        if (tokenAmount < expectedAmount) {
            throw RedemptionException(
                "Insufficient amount: required=$expectedAmount, got=$tokenAmount"
            )
        }

        val isAllowed = allowedMints.isNullOrEmpty() || allowedMints.contains(mintUrl)

        if (isAllowed) {
            Log.d(TAG, "Token mint validated as allowed: $mintUrl")
            // Standard Cashu redemption path
            try {
                redeemProofs(proofs, mintUrl, unit)
                return "SUCCESS_KNOWN" // Special marker to indicate known mint success
            } catch (e: Exception) {
                Log.e(TAG, "Failed to redeem proofs for known mint", e)
                throw RedemptionException("Failed to redeem proofs: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Token mint is not in allowed list: $mintUrl")
            val mintManager = MintManager.getInstance(appContext)
            if (!mintManager.isSwapFromUnknownMintsEnabled()) {
                Log.w(TAG, "Token from unknown mint encountered but swap-to-Lightning-mint is disabled - rejecting payment")
                throw RedemptionException("Payments from unknown mints are disabled in Settings   Mints.")
            }

            Log.i(TAG, "Token from unknown mint detected - starting SwapToLightningMint flow")

            val swapResult = SwapToLightningMintManager.swapFromUnknownMint(
                appContext = appContext,
                proofs = proofs,
                expectedAmount = expectedAmount,
                unknownMintUrl = mintUrl,
                paymentContext = paymentContext,
            )

            return when (swapResult) {
                is SwapToLightningMintManager.SwapResult.Success -> {
                    Log.i(TAG, "SwapToLightningMint succeeded for unknown mint token")
                    // Lightning-style: no Cashu token is imported into our wallet
                    ""
                }
                is SwapToLightningMintManager.SwapResult.Failure -> {
                    throw RedemptionException("Swap to Lightning mint failed: ${swapResult.errorMessage}")
                }
            }
        }
    }

    /**
     * High-level redemption entry point used by payment flows (NDEF, Nostr).
     *
     * Behavior:
     * - Validates the token structure and amount against expectedAmount.
     * - If mint is in allowedMints → normal Cashu redemption via WalletRepository.
     * - If mint is *not* in allowedMints but amount is sufficient →
     *   runs the SwapToLightningMint flow and treats Lightning receipt as
     *   payment success (returns empty string as token, Lightning-style).
     *
     * @param appContext Android application context.
     * @param tokenString Encoded Cashu token from payer.
     * @param expectedAmount Amount in sats the POS expects for this payment.
     * @param allowedMints Optional list of allowed mint URLs; if null/empty,
     *                     all mints are treated as allowed.
     * @param paymentContext Context tying this redemption to a payment
     *                       history entry (paymentId, amount).
     * @return Redeemed token string (for pure Cashu) or empty string when the
     *         payment was fulfilled via a Lightning swap.
     */
    @Throws(RedemptionException::class)
    suspend fun redeemTokenWithSwap(
        appContext: Context,
        tokenString: String?,
        expectedAmount: Long,
        allowedMints: List<String>?,
        paymentContext: SwapToLightningMintManager.PaymentContext
    ): String {
        val result = validateTokenDetailed(tokenString, expectedAmount, allowedMints)

        return when (result) {
            is TokenValidationResult.InvalidFormat -> {
                throw RedemptionException("Invalid Cashu token")
            }

            is TokenValidationResult.InsufficientAmount -> {
                throw RedemptionException(
                    "Insufficient amount: required=${result.required}, got=${result.actual}"
                )
            }

            is TokenValidationResult.ValidKnownMint -> {
                val cdkToken = result.token
                val unit = cdkToken.unit().let { tokenUnit ->
                    when (tokenUnit) {
                        is CurrencyUnit.Sat -> "sat"
                        is CurrencyUnit.Msat -> "msat"
                        is CurrencyUnit.Eur -> "eur"
                        is CurrencyUnit.Usd -> "usd"
                        is CurrencyUnit.Custom -> tokenUnit.unit
                        else -> "sat"
                    }
                }
                if (unit != "sat") {
                    throw RedemptionException("Unsupported token unit: $unit")
                }

                val wallet = CashuWalletManager.getWallet()
                    ?: throw RedemptionException("CDK wallet not initialized")

                val mintWallet = wallet.getWallet(cdkToken.mintUrl(), cdkToken.unit() ?: CurrencyUnit.Sat)
                    ?: throw RedemptionException("Failed to get wallet for mint: ${cdkToken.mintUrl().url}")

                val receiveOptions = org.cashudevkit.ReceiveOptions(
                    amountSplitTarget = org.cashudevkit.SplitTarget.None,
                    p2pkSigningKeys = emptyList(),
                    preimages = emptyList(),
                    metadata = emptyMap()
                )

                try {
                    mintWallet.receive(cdkToken, receiveOptions)
                    tokenString ?: "" // SUCCESS!
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to redeem token for known mint", e)
                    throw RedemptionException("Failed to redeem token: ${e.message}", e)
                }
            }

            is TokenValidationResult.ValidUnknownMint -> {
                val cdkToken = result.token
                val unknownMintUrl = cdkToken.mintUrl().url

                Log.i(TAG, "Token from unknown mint detected - fetching keysets and extracting proofs")

                // We instantiate a temp wallet for the unknown mint
                val tempWallet = CashuWalletManager.getTemporaryWalletForMint(unknownMintUrl)
                
                // Fetch keysets so CDK can map short Keyset IDs to full IDs
                val keysets = try {
                    @Suppress("UNCHECKED_CAST")
                    tempWallet.loadMintKeysets() as List<org.cashudevkit.KeySetInfo>
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load keysets for unknown mint", e)
                    throw RedemptionException("Failed to fetch keysets for unknown mint: ${e.message}", e)
                }

                // Now extract proofs using the keysets!
                val proofs = try {
                    cdkToken.proofs(keysets)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract mapped proofs from token", e)
                    throw RedemptionException("Invalid token: could not map keyset IDs", e)
                }
                
                val tokenAmount = proofs.map { it.amount.value.toLong() }.sum()
                if (tokenAmount < expectedAmount) {
                    throw RedemptionException(
                        "Insufficient amount: required=$expectedAmount, got=$tokenAmount"
                    )
                }

                val mintManager = MintManager.getInstance(appContext)
                if (!mintManager.isSwapFromUnknownMintsEnabled()) {
                    Log.w(TAG, "Token from unknown mint encountered but swap-to-Lightning-mint is disabled - rejecting payment")
                    throw RedemptionException("Payments from unknown mints are disabled in Settings   Mints.")
                }

                Log.i(TAG, "Token from unknown mint detected - starting SwapToLightningMint flow")

                val swapResult = SwapToLightningMintManager.swapFromUnknownMint(
                    appContext = appContext,
                    proofs = proofs,
                    expectedAmount = expectedAmount,
                    unknownMintUrl = unknownMintUrl,
                    paymentContext = paymentContext,
                )

                when (swapResult) {
                    is SwapToLightningMintManager.SwapResult.Success -> {
                        Log.i(TAG, "SwapToLightningMint succeeded for unknown mint token")
                        // Lightning-style: no Cashu token is imported into our wallet
                        ""
                    }
                    is SwapToLightningMintManager.SwapResult.Failure -> {
                        throw RedemptionException("Swap to Lightning mint failed: ${swapResult.errorMessage}")
                    }
                }
            }
        }
    }

    // === Redemption from PaymentRequestPayload =============================

    @JvmStatic
    @Throws(RedemptionException::class)
    suspend fun redeemFromPRPayloadWithSwap(
        appContext: Context,
        payloadJson: String?,
        expectedAmount: Long,
        allowedMints: List<String>?,
        paymentContext: SwapToLightningMintManager.PaymentContext
    ): String {
        if (payloadJson == null) {
            throw RedemptionException("PaymentRequestPayload JSON is null")
        }
        try {
            Log.d(TAG, "payloadJson: $payloadJson")
            val payload = org.cashudevkit.PaymentRequestPayload.fromString(payloadJson)

            val mintUrl = payload.mint().url
            val unit = payload.unit().let { tokenUnit ->
                when (tokenUnit) {
                    is CurrencyUnit.Sat -> "sat"
                    is CurrencyUnit.Msat -> "msat"
                    is CurrencyUnit.Eur -> "eur"
                    is CurrencyUnit.Usd -> "usd"
                    is CurrencyUnit.Custom -> tokenUnit.unit
                    else -> "sat"
                }
            }
            
            if (unit != "sat") {
                 throw RedemptionException("Unsupported unit in PaymentRequestPayload: $unit")
            }
            
            val proofs = payload.proofs()

            val swapResult = redeemProofsWithSwap(
                appContext = appContext,
                proofs = proofs,
                mintUrl = mintUrl,
                unit = unit,
                expectedAmount = expectedAmount,
                allowedMints = allowedMints,
                paymentContext = paymentContext,
            )

            // Return the payload JSON so it can be saved in history
            return if (swapResult == "SUCCESS_KNOWN") payloadJson else swapResult
        } catch (e: Exception) {
            if (e is RedemptionException) throw e
            val errorMsg = "PaymentRequestPayload redemption failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        }
    }

    // === Exception type ====================================================

    class RedemptionException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
