package com.electricdreams.shellshock.ndef

import android.util.Log
import com.cashujdk.nut18.PaymentRequest
import com.cashujdk.nut18.Transport
import com.cashujdk.nut18.TransportTag
import com.google.gson.*
import kotlinx.coroutines.runBlocking
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

    // === Token helpers ======================================================

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

    @JvmStatic
    fun validateToken(
        tokenString: String?,
        expectedAmount: Long,
        allowedMints: List<String>?,
    ): Boolean {
        if (!isCashuToken(tokenString)) {
            Log.e(TAG, "Invalid token format (not a Cashu token)")
            return false
        }

        return try {
            val token = org.cashudevkit.Token.Companion.decode(
                tokenString ?: error("tokenString is null"),
            )

            if (token.unit() != org.cashudevkit.CurrencyUnit.Sat) {
                Log.e(TAG, "Unsupported token unit: ${token.unit()}")
                return false
            }

            if (!allowedMints.isNullOrEmpty()) {
                val mintUrl = token.mintUrl().url
                if (!allowedMints.contains(mintUrl)) {
                    Log.e(TAG, "Mint not in allowed list: $mintUrl")
                    return false
                } else {
                    Log.d(TAG, "Token mint validated: $mintUrl")
                }
            }

            val tokenAmount = token.value().value.toLong()

            if (tokenAmount < expectedAmount) {
                Log.e(
                    TAG,
                    "Amount was insufficient: $expectedAmount sats required but $tokenAmount sats provided",
                )
                return false
            }

            Log.d(TAG, "Token format validation passed using CDK Token; amount=$tokenAmount sats")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token validation failed: ${e.message}", e)
            false
        }
    }

    @JvmStatic
    fun validateToken(tokenString: String?, expectedAmount: Long): Boolean =
        validateToken(tokenString, expectedAmount, null)

    // === Redemption using CDK MultiMintWallet ===============================

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
                com.electricdreams.shellshock.core.cashu.CashuWalletManager.getWallet()
                    ?: throw RedemptionException("CDK wallet not initialized")

            // Decode incoming token using CDK Token
            val cdkToken = org.cashudevkit.Token.Companion.decode(
                tokenString ?: error("tokenString is null"),
            )

            if (cdkToken.unit() != org.cashudevkit.CurrencyUnit.Sat) {
                throw RedemptionException("Unsupported token unit: ${cdkToken.unit()}")
            }

            val mintUrl: org.cashudevkit.MintUrl = cdkToken.mintUrl()

            // Receive into wallet
            val receiveOptions = org.cashudevkit.ReceiveOptions(
                amountSplitTarget = org.cashudevkit.SplitTarget.None,
                p2pkSigningKeys = emptyList(),
                preimages = emptyList(),
                metadata = emptyMap(),
            )
            val mmReceive = org.cashudevkit.MultiMintReceiveOptions(
                allowUntrusted = false,
                transferToMint = null,
                receiveOptions = receiveOptions,
            )

            val amountReceived: org.cashudevkit.Amount
            runBlocking {
                amountReceived = wallet.receive(cdkToken, mmReceive)
            }

            // Prepare send back out
            val outAmount: org.cashudevkit.Amount = amountReceived

            val sendOptions = org.cashudevkit.SendOptions(
                memo = null,
                conditions = null,
                amountSplitTarget = org.cashudevkit.SplitTarget.None,
                sendKind = org.cashudevkit.SendKind.OfflineExact,
                includeFee = false,
                maxProofs = null,
                metadata = emptyMap(),
            )
            val mmSend = org.cashudevkit.MultiMintSendOptions(
                allowTransfer = false,
                maxTransferAmount = null,
                allowedMints = emptyList(),
                excludedMints = emptyList(),
                sendOptions = sendOptions,
            )

            val outTokenString = runBlocking {
                val prepared: org.cashudevkit.PreparedSend =
                    wallet.prepareSend(mintUrl, outAmount, mmSend)
                val outToken: org.cashudevkit.Token =
                    prepared.confirm("redeemed via CDK")
                outToken.encode()
            }

            Log.d(TAG, "Token redemption via CDK successful")
            return outTokenString
        } catch (e: RedemptionException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Token redemption via CDK failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        }
    }

    // === Redemption from PaymentRequestPayload (still cashu-jdk proofs) ==== 

    @JvmStatic
    @Throws(RedemptionException::class)
    fun redeemFromPRPayload(
        payloadJson: String?,
        expectedAmount: Long,
        allowedMints: List<String>?,
    ): String {
        if (payloadJson == null) {
            throw RedemptionException("PaymentRequestPayload JSON is null")
        }
        try {
            Log.d(TAG, "payloadJson: $payloadJson")
            val payload = PaymentRequestPayload.GSON.fromJson(
                payloadJson,
                PaymentRequestPayload::class.java,
            )
                ?: throw RedemptionException("Failed to parse PaymentRequestPayload")

            if (payload.mint.isNullOrEmpty()) {
                throw RedemptionException("PaymentRequestPayload is missing mint")
            }
            if (payload.unit == null || payload.unit != "sat") {
                throw RedemptionException("Unsupported unit in PaymentRequestPayload: ${payload.unit}")
            }
            if (payload.proofs.isNullOrEmpty()) {
                throw RedemptionException("PaymentRequestPayload contains no proofs")
            }

            val mintUrl = payload.mint!!
            if (!allowedMints.isNullOrEmpty() && !allowedMints.contains(mintUrl)) {
                throw RedemptionException("Mint $mintUrl not in allowed list")
            }

            val totalAmount = payload.proofs!!.sumOf { it.amount }
            if (totalAmount < expectedAmount) {
                throw RedemptionException(
                    "Insufficient amount in payload proofs: $totalAmount < expected $expectedAmount",
                )
            }

            // Build a legacy cashu-jdk Token from proofs, then let CDK wallet
            // handle the redemption via redeemToken(encoded).
            val tempToken = com.cashujdk.nut00.Token(payload.proofs!!, payload.unit!!, mintUrl)
            val encoded = tempToken.encode()
            return redeemToken(encoded)
        } catch (e: JsonSyntaxException) {
            throw RedemptionException("Invalid JSON for PaymentRequestPayload: ${e.message}", e)
        } catch (e: JsonIOException) {
            val errorMsg = "PaymentRequestPayload redemption failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        } catch (e: RedemptionException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "PaymentRequestPayload redemption failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        }
    }

    // === DTO for PaymentRequestPayload ====================================== 

    class PaymentRequestPayload {
        var id: String? = null
        var memo: String? = null
        var mint: String? = null
        var unit: String? = null
        var proofs: MutableList<com.cashujdk.nut00.Proof>? = null

        companion object {
            @JvmField
            val GSON: Gson = GsonBuilder()
                .registerTypeAdapter(com.cashujdk.nut00.Proof::class.java, ProofAdapter())
                .create()
        }

        private class ProofAdapter : JsonDeserializer<com.cashujdk.nut00.Proof> {
            override fun deserialize(
                json: JsonElement?,
                typeOfT: java.lang.reflect.Type?,
                context: JsonDeserializationContext?,
            ): com.cashujdk.nut00.Proof {
                if (json == null || !json.isJsonObject) {
                    throw JsonParseException("Expected object for Proof")
                }

                val obj = json.asJsonObject

                val amount = obj.get("amount").asLong
                val secretStr = obj.get("secret").asString
                val cHex = obj.get("C").asString

                val keysetId = obj.get("id")?.takeIf { !it.isJsonNull }?.asString
                    ?: throw JsonParseException("Proof is missing id/keysetId")

                val secret: com.cashujdk.nut00.ISecret = com.cashujdk.nut00.StringSecret(secretStr)

                var dleq: com.cashujdk.nut12.DLEQProof? = null
                if (obj.has("dleq") && obj.get("dleq").isJsonObject) {
                    val d = obj.getAsJsonObject("dleq")
                    val rStr = d.get("r").asString
                    val sStr = d.get("s").asString
                    val eStr = d.get("e").asString

                    val r = BigInteger(rStr, 16)
                    val s = BigInteger(sStr, 16)
                    val e = BigInteger(eStr, 16)

                    dleq = com.cashujdk.nut12.DLEQProof(s, e, Optional.of(r))
                }

                return com.cashujdk.nut00.Proof(
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

    // === Exception type =====================================================

    class RedemptionException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
