package com.electricdreams.numo.ndef

import android.content.Context
import android.util.Log
import com.cashujdk.nut00.ISecret
import com.cashujdk.nut00.Proof as JdkProof
import com.cashujdk.nut00.StringSecret
import com.cashujdk.nut12.DLEQProof
import com.cashujdk.nut18.PaymentRequest
import com.cashujdk.nut18.Transport
import com.cashujdk.nut18.TransportTag
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.payment.SwapToLightningMintManager
import com.google.gson.*
import kotlinx.coroutines.runBlocking
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.MultiMintReceiveOptions
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
    ): String? {
        return try {
            val paymentRequest = PaymentRequest().apply {
                this.amount = Optional.of(amount)
                unit = Optional.of("sat")
                this.description = Optional.of(
                    description ?: "Payment for $amount sats",
                )

                val id = java.util.UUID.randomUUID().toString().substring(0, 8)
                this.id = Optional.of(id)

                singleUse = Optional.of(true)

                if (!allowedMints.isNullOrEmpty()) {
                    val mintsArray = allowedMints.toTypedArray()
                    mints = Optional.of(mintsArray)
                    Log.d(TAG, "Added ${allowedMints.size} allowed mints to payment request")
                }
            }

            val encoded = paymentRequest.encode()
            Log.d(TAG, "Created payment request: $encoded")
            encoded
        } catch (e: Exception) {
            Log.e(TAG, "Error creating payment request: ${e.message}", e)
            null
        }
    }

    @JvmStatic
    fun createPaymentRequest(amount: Long, description: String?): String? =
        createPaymentRequest(amount, description, null)

    @JvmStatic
    fun createPaymentRequestWithNostr(
        amount: Long,
        description: String?,
        allowedMints: List<String>?,
        nprofile: String,
    ): String? {
        return try {
            val paymentRequest = PaymentRequest().apply {
                this.amount = Optional.of(amount)
                unit = Optional.of("sat")
                this.description = Optional.of(
                    description ?: "Payment for $amount sats",
                )

                val id = java.util.UUID.randomUUID().toString().substring(0, 8)
                this.id = Optional.of(id)

                singleUse = Optional.of(true)

                if (!allowedMints.isNullOrEmpty()) {
                    val mintsArray = allowedMints.toTypedArray()
                    mints = Optional.of(mintsArray)
                    Log.d(TAG, "Added ${allowedMints.size} allowed mints to payment request (Nostr)")
                }

                val nostrTransport = Transport().apply {
                    type = "nostr"
                    target = nprofile

                    val nipTag = TransportTag().apply {
                        key = "n"
                        value = "17" // NIP-17
                    }
                    tags = Optional.of(arrayOf(nipTag))
                }

                transport = Optional.of(arrayOf(nostrTransport))
            }

            val encoded = paymentRequest.encode()
            Log.d(TAG, "Created Nostr payment request: $encoded")
            encoded
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Nostr payment request: ${e.message}", e)
            null
        }
    }

    // === Token helpers =====================================================

    @JvmStatic
    fun isCashuToken(text: String?): Boolean =
        text != null && (text.startsWith("cashuB") || text.startsWith("cashuA"))

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

        val prefixes = arrayOf("cashuA", "cashuB")
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
        text != null && text.startsWith("creqA")

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
    fun redeemToken(tokenString: String?): String {
        if (!isCashuToken(tokenString)) {
            val errorMsg = "Cannot redeem: Invalid token format"
            Log.e(TAG, errorMsg)
            throw RedemptionException(errorMsg)
        }

        try {
            val wallet =
                CashuWalletManager.getWallet()
                    ?: throw RedemptionException("CDK wallet not initialized")

            // Decode incoming token using CDK Token
            val cdkToken = CdkToken.decode(
                tokenString ?: error("tokenString is null"),
            )

            if (cdkToken.unit() != CurrencyUnit.Sat) {
                throw RedemptionException("Unsupported token unit: ${cdkToken.unit()}")
            }

            val mintUrl: MintUrl = cdkToken.mintUrl()

            // Receive into wallet
            val receiveOptions = ReceiveOptions(
                amountSplitTarget = SplitTarget.None,
                p2pkSigningKeys = emptyList(),
                preimages = emptyList(),
                metadata = emptyMap(),
            )
            val mmReceive = MultiMintReceiveOptions(
                allowUntrusted = false,
                transferToMint = null,
                receiveOptions = receiveOptions,
            )

            // Receive into wallet
            runBlocking {
                wallet.receive(cdkToken, mmReceive)
            }

            Log.d(TAG, "Token received via CDK successfully (mintUrl=${mintUrl.url})")
            // Return the original token instead of sending a new one
            return tokenString ?: error("tokenString is null")
        } catch (e: RedemptionException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Token redemption via CDK failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        }
    }

    // === High-level redemption with optional swap-to-Lightning-mint ========

    /**
     * High-level redemption entry point used by payment flows (NDEF, Nostr).
     *
     * Behavior:
     * - Validates the token structure and amount against expectedAmount.
     * - If mint is in allowedMints → normal Cashu redemption via MultiMintWallet.
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
                    "Insufficient amount: required=${'$'}{result.required}, got=${'$'}{result.actual}"
                )
            }

            is TokenValidationResult.ValidKnownMint -> {
                // Standard Cashu redemption path
                redeemToken(tokenString)
            }

            is TokenValidationResult.ValidUnknownMint -> {
                // If swap from unknown mints is disabled, fall back to the
                // legacy behavior of rejecting unknown mints instead of
                // attempting a SwapToLightningMint flow.
                val mintManager = MintManager.getInstance(appContext)
                if (!mintManager.isSwapFromUnknownMintsEnabled()) {
                    Log.w(TAG, "Token from unknown mint encountered but swap-to-Lightning-mint is disabled - rejecting payment")
                    throw RedemptionException("Payments from unknown mints are disabled in Settings  Mints.")
                }

                Log.i(TAG, "Token from unknown mint detected - starting SwapToLightningMint flow")

                val swapResult = SwapToLightningMintManager.swapFromUnknownMint(
                    appContext = appContext,
                    cashuToken = tokenString ?: error("tokenString is null"),
                    expectedAmount = expectedAmount,
                    unknownMintUrl = result.mintUrl,
                    paymentContext = paymentContext,
                )

                return when (swapResult) {
                    is SwapToLightningMintManager.SwapResult.Success -> {
                        Log.i(TAG, "SwapToLightningMint succeeded for unknown mint token")
                        // Lightning-style: no Cashu token is imported into our wallet
                        ""
                    }
                    is SwapToLightningMintManager.SwapResult.Failure -> {
                        throw RedemptionException("Swap to Lightning mint failed: ${'$'}{swapResult.errorMessage}")
                    }
                }
            }
        }
    }

    // === Redemption from PaymentRequestPayload (still cashu-jdk proofs) ====

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
            val payload = PaymentRequestPayload.GSON.fromJson(
                payloadJson,
                PaymentRequestPayload::class.java,
            ) ?: throw RedemptionException("Failed to parse PaymentRequestPayload")

            if (payload.mint.isNullOrEmpty()) {
                throw RedemptionException("PaymentRequestPayload is missing mint")
            }
            if (payload.unit == null || payload.unit != "sat") {
                throw RedemptionException("Unsupported unit in PaymentRequestPayload: ${'$'}{payload.unit}")
            }
            if (payload.proofs.isNullOrEmpty()) {
                throw RedemptionException("PaymentRequestPayload contains no proofs")
            }

            val totalAmount = payload.proofs!!.sumOf { it.amount }
            if (totalAmount < expectedAmount) {
                throw RedemptionException(
                    "Insufficient amount in payload proofs: ${'$'}totalAmount < expected ${'$'}expectedAmount",
                )
            }

            // Build a legacy cashu-jdk Token from proofs, then delegate to the
            // high-level swap-aware redemption path so that unknown mints can be
            // swapped to the merchant's Lightning mint.
            val tempToken = com.cashujdk.nut00.Token(payload.proofs!!, payload.unit!!, payload.mint!!)
            val encoded = tempToken.encode()
            return redeemTokenWithSwap(
                appContext = appContext,
                tokenString = encoded,
                expectedAmount = expectedAmount,
                allowedMints = allowedMints,
                paymentContext = paymentContext,
            )
        } catch (e: JsonSyntaxException) {
            throw RedemptionException("Invalid JSON for PaymentRequestPayload: ${'$'}{e.message}", e)
        } catch (e: JsonIOException) {
            val errorMsg = "PaymentRequestPayload redemption failed: ${'$'}{e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        } catch (e: RedemptionException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "PaymentRequestPayload redemption failed: ${'$'}{e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        }
    }

    class PaymentRequestPayload {
        var id: String? = null
        var memo: String? = null
        var mint: String? = null
        var unit: String? = null
        var proofs: MutableList<JdkProof>? = null

        companion object {
            @JvmField
            val GSON: Gson = GsonBuilder()
                .registerTypeAdapter(JdkProof::class.java, ProofAdapter())
                .create()
        }

        private class ProofAdapter : JsonDeserializer<JdkProof> {
            override fun deserialize(
                json: JsonElement?,
                typeOfT: java.lang.reflect.Type?,
                context: JsonDeserializationContext?,
            ): JdkProof {
                if (json == null || !json.isJsonObject) {
                    throw JsonParseException("Expected object for Proof")
                }

                val obj = json.asJsonObject

                val amount = obj.get("amount").asLong
                val secretStr = obj.get("secret").asString
                val cHex = obj.get("C").asString

                val keysetId = obj.get("id")?.takeIf { !it.isJsonNull }?.asString
                    ?: throw JsonParseException("Proof is missing id/keysetId")

                val secret: ISecret = StringSecret(secretStr)

                var dleq: DLEQProof? = null
                if (obj.has("dleq") && obj.get("dleq").isJsonObject) {
                    val d = obj.getAsJsonObject("dleq")
                    val rStr = d.get("r").asString
                    val sStr = d.get("s").asString
                    val eStr = d.get("e").asString

                    val r = BigInteger(rStr, 16)
                    val s = BigInteger(sStr, 16)
                    val e = BigInteger(eStr, 16)

                    dleq = DLEQProof(s, e, Optional.of(r))
                }

                return JdkProof(
                    amount,
                    keysetId,
                    secret,
                    cHex,
                    Optional.empty(),
                    Optional.ofNullable(dleq),
                )
            }
        }
    }

    // === Exception type ====================================================

    class RedemptionException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
