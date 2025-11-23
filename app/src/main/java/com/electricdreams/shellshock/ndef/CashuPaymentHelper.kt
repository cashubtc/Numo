package com.electricdreams.shellshock.ndef

import android.util.Log
import com.cashujdk.api.CashuHttpClient
import com.cashujdk.cryptography.Cashu
import com.cashujdk.nut00.BlindSignature
import com.cashujdk.nut00.BlindedMessage
import com.cashujdk.nut00.ISecret
import com.cashujdk.nut00.InnerToken
import com.cashujdk.nut00.Proof
import com.cashujdk.nut00.StringSecret
import com.cashujdk.nut00.Token
import com.cashujdk.nut01.GetKeysResponse
import com.cashujdk.nut01.KeysetId
import com.cashujdk.nut01.KeysetItemResponse
import com.cashujdk.nut02.FeeHelper
import com.cashujdk.nut02.GetKeysetsResponse
import com.cashujdk.nut03.PostSwapRequest
import com.cashujdk.nut03.PostSwapResponse
import com.cashujdk.nut12.DLEQProof
import com.cashujdk.nut18.PaymentRequest
import com.cashujdk.nut18.Transport
import com.cashujdk.nut18.TransportTag
import com.google.gson.*
import okhttp3.OkHttpClient
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Optional

/**
 * Helper class for Cashu payment-related operations.
 *
 * NOTE: This is intentionally close to the original Java implementation to
 * make future replacement with CDK (Cashu Development Kit) straightforward.
 */
object CashuPaymentHelper {

    private const val TAG = "CashuPaymentHelper"

    /**
     * Create a Cashu payment request for a specific amount.
     * @param amount Amount in sats
     * @param description Optional description for the payment
     * @param allowedMints List of allowed mints (can be null)
     * @return Payment request string (creq...) or null on error
     */
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

                // Random short ID
                val id = java.util.UUID.randomUUID().toString().substring(0, 8)
                this.id = Optional.of(id)

                // Single use
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

    /**
     * Overload without allowed mints.
     */
    @JvmStatic
    fun createPaymentRequest(amount: Long, description: String?): String? =
        createPaymentRequest(amount, description, null)

    /**
     * Create a Cashu payment request that includes a Nostr transport according to NUT-18.
     * For QR-based payments where the sender uses Nostr DM (NIP-17) to deliver ecash.
     */
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

                // Nostr transport as per NUT-18
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

    /** Basic format check for Cashu token. */
    @JvmStatic
    fun isCashuToken(text: String?): Boolean =
        text != null && (text.startsWith("cashuB") || text.startsWith("cashuA"))

    /**
     * Extract a Cashu token from a string that might contain other content.
     */
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

        // Hash-fragment style: #token=cashu...
        if (text.contains("#token=cashu")) {
            Log.i(TAG, "extractCashuToken: Found #token=cashu pattern")
            val tokenStart = text.indexOf("#token=cashu")
            val cashuStart = tokenStart + 7 // "#token="
            var cashuEnd = text.length

            val token = text.substring(cashuStart, cashuEnd)
            Log.i(TAG, "extractCashuToken: Extracted token from URL fragment: $token")
            return token
        }

        // Query-style: ?token=cashu...
        if (text.contains("token=cashu")) {
            Log.i(TAG, "extractCashuToken: Found token=cashu pattern")
            val tokenStart = text.indexOf("token=cashu")
            val cashuStart = tokenStart + 6 // "token="
            var cashuEnd = text.length
            val ampIndex = text.indexOf('&', cashuStart)
            val hashIndex = text.indexOf('#', cashuStart)

            if (ampIndex > cashuStart && ampIndex < cashuEnd) {
                cashuEnd = ampIndex
            }
            if (hashIndex > cashuStart && hashIndex < cashuEnd) {
                cashuEnd = hashIndex
            }

            val token = text.substring(cashuStart, cashuEnd)
            Log.i(TAG, "extractCashuToken: Extracted token from URL parameter: $token")
            return token
        }

        // Fallback: scan for cashuA/cashuB prefix
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

    /** Basic format check for Cashu payment request (creqA...). */
    @JvmStatic
    fun isCashuPaymentRequest(text: String?): Boolean =
        text != null && text.startsWith("creqA")

    /**
     * Validate a Cashu token for a specific amount and against allowed mints.
     */
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
            val token = Token.decode(tokenString)
            if (token.unit != "sat") {
                Log.e(TAG, "Unsupported token unit: ${token.unit}")
                return false
            }

            // Mint check
            if (!allowedMints.isNullOrEmpty()) {
                val mintUrl = token.mint
                if (!allowedMints.contains(mintUrl)) {
                    Log.e(TAG, "Mint not in allowed list: $mintUrl")
                    return false
                } else {
                    Log.d(TAG, "Token mint validated: $mintUrl")
                }
            }

            val tokenAmount = token.tokens
                .map { inner: InnerToken ->
                    inner.proofsShortId.stream().mapToLong { p: Proof -> p.amount }.sum()
                }
                .sum()

            if (tokenAmount < expectedAmount) {
                Log.e(
                    TAG,
                    "Amount was insufficient: $expectedAmount sats required but $tokenAmount sats provided",
                )
                return false
            }

            Log.d(TAG, "Token format validation passed, cryptographic verification pending")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token validation failed: ${e.message}", e)
            false
        }
    }

    /** Overload without mint checking. */
    @JvmStatic
    fun validateToken(tokenString: String?, expectedAmount: Long): Boolean =
        validateToken(tokenString, expectedAmount, null)

    /**
     * Attempt to redeem a Cashu token and get a reissued token.
     * @throws RedemptionException if token redemption fails
     */
    @JvmStatic
    @Throws(RedemptionException::class)
    fun redeemToken(tokenString: String?): String {
        if (!isCashuToken(tokenString)) {
            val errorMsg = "Cannot redeem: Invalid token format"
            Log.e(TAG, errorMsg)
            throw RedemptionException(errorMsg)
        }

        try {
            val token = Token.decode(tokenString)
            val mintUrl = token.mint

            val httpClient = CashuHttpClient(OkHttpClient(), mintUrl)
            val keysetsResponse = httpClient.keysets.join()
                ?: throw RedemptionException("Failed to get keysets from mint: $mintUrl")

            val keysets = keysetsResponse.keysets
            if (keysets == null || keysets.isEmpty()) {
                throw RedemptionException("Failed to get keysets from mint: $mintUrl")
            }

            val fullKeysetIds = keysets.map { it.keysetId }
            val keysetsFeesMap = keysets.associate { it.keysetId to it.inputFee }

            val tokenAmount = token.tokens
                .map { inner: InnerToken ->
                    inner.proofsShortId.stream().mapToLong { p: Proof -> p.amount }.sum()
                }
                .sum()

            val receiveProofs = token.tokens
                .flatMap { inner: InnerToken ->
                    inner.getProofs(fullKeysetIds).toList()
                }

            if (receiveProofs.isEmpty()) {
                throw RedemptionException("No valid proofs found in token")
            }

            val fee = FeeHelper.ComputeFee(receiveProofs, keysetsFeesMap)

            val selectedKeysetId = keysets
                .filter { it.active && it.unit.equals("sat", ignoreCase = true) }
                .minByOrNull { it.inputFee }
                ?.keysetId
                ?: throw RedemptionException("No active keyset found on mint")

            Log.d(TAG, "Selected keyset ID for new proofs: $selectedKeysetId")

            val keysetId = KeysetId().apply { _id = selectedKeysetId }

            val outputAmounts = createOutputAmounts(tokenAmount - fee)

            val blindedMessages = mutableListOf<BlindedMessage>()
            val secrets = mutableListOf<StringSecret>()
            val blindingFactors = mutableListOf<BigInteger>()

            for (output in outputAmounts) {
                val secret = StringSecret.random()
                secrets.add(secret)

                val blindingFactor = BigInteger(256, SecureRandom())
                blindingFactors.add(blindingFactor)

                val bPrime = Cashu.computeB_(
                    Cashu.messageToCurve(secret.secret),
                    blindingFactor,
                )
                val blindedMessage = BlindedMessage(
                    output,
                    selectedKeysetId,
                    Cashu.pointToHex(bPrime, true),
                    Optional.empty(),
                )
                blindedMessages.add(blindedMessage)
            }

            val keysFuture = httpClient.getKeys(selectedKeysetId)

            val swapRequest = PostSwapRequest().apply {
                inputs = receiveProofs.map { p ->
                    Proof(
                        p.amount,
                        p.keysetId,
                        p.secret,
                        p.c,
                        Optional.empty(),
                        Optional.empty(),
                    )
                }
                outputs = blindedMessages
            }

            Log.d(TAG, "Attempting to swap proofs")
            val swapResponse = httpClient.swap(swapRequest).join()
                ?: throw RedemptionException("No signatures returned from mint during swap")

            if (swapResponse.signatures == null || swapResponse.signatures.isEmpty()) {
                throw RedemptionException("No signatures returned from mint during swap")
            }

            val keysResponse = keysFuture.join()
            val returnedKeysets = keysResponse?.keysets
            if (returnedKeysets == null || returnedKeysets.isEmpty()) {
                throw RedemptionException("Failed to get keys from mint")
            }

            Log.d(TAG, "Successfully swapped and received proofs")

            val proofs = constructAndVerifyProofs(
                swapResponse,
                returnedKeysets[0],
                secrets,
                blindingFactors,
            )
            if (proofs.isEmpty()) {
                throw RedemptionException("Failed to verify proofs from mint")
            }

            Log.d(TAG, "Successfully constructed and verified proofs")

            val newToken = Token(proofs, token.unit, mintUrl)
            Log.d(TAG, "Token redemption successful!")
            return newToken.encode()
        } catch (e: RedemptionException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Token redemption failed: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw RedemptionException(errorMsg, e)
        }
    }

    class RedemptionException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    private fun createOutputAmounts(amount: Long): List<Long> {
        val outputs = mutableListOf<Long>()
        var remaining = amount
        var i = 0
        while (remaining > 0) {
            if ((remaining and 1L) == 1L) {
                outputs.add(1L shl i)
            }
            remaining = remaining shr 1
            i++
        }
        return outputs
    }

    private fun constructAndVerifyProofs(
        response: PostSwapResponse,
        keyset: KeysetItemResponse,
        secrets: List<StringSecret>,
        blindingFactors: List<BigInteger>,
    ): List<Proof> {
        val result = mutableListOf<Proof>()
        val signatures = response.signatures ?: emptyList()
        for (i in signatures.indices) {
            val signature = signatures[i]
            val blindingFactor = blindingFactors[i]
            val secret = secrets[i]

            val key: ECPoint = Cashu.hexToPoint(
                keyset.keys[BigInteger.valueOf(signature.amount)] ?: continue,
            )
            val C = Cashu.computeC(Cashu.hexToPoint(signature.c_), blindingFactor, key)

            val verified = Cashu.verifyProof(
                Cashu.messageToCurve(secret.secret),
                blindingFactor,
                C,
                signature.dleq.e,
                signature.dleq.s,
                key,
            )

            if (!verified) {
                Log.e(TAG, "Couldn't verify signature: ${signature.c_}")
            }

            result.add(
                Proof(
                    signature.amount,
                    signature.keysetId,
                    secret,
                    Cashu.pointToHex(C, true),
                    Optional.empty(),
                    Optional.empty(),
                ),
            )
        }
        return result
    }

    /**
     * Parse a NUT-18-like PaymentRequestPayload JSON and attempt redemption by
     * constructing a temporary Token and calling redeemToken.
     */
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

            val tempToken = Token(payload.proofs!!, payload.unit!!, mintUrl)
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

    /**
     * Minimal DTO for PaymentRequestPayload JSON as used in Nostr DMs.
     */
    class PaymentRequestPayload {
        var id: String? = null
        var memo: String? = null
        var mint: String? = null
        var unit: String? = null
        var proofs: MutableList<Proof>? = null

        companion object {
            @JvmField
            val GSON: Gson = GsonBuilder()
                .registerTypeAdapter(Proof::class.java, ProofAdapter())
                .create()
        }

        private class ProofAdapter : JsonDeserializer<Proof> {
            @Throws(JsonParseException::class)
            override fun deserialize(
                json: JsonElement?,
                typeOfT: java.lang.reflect.Type?,
                context: JsonDeserializationContext?,
            ): Proof {
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

                return Proof(
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
}
