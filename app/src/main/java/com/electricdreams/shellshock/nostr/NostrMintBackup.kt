package com.electricdreams.shellshock.nostr

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Nostr Mint Backup - NUT-XX Implementation
 * 
 * Backs up the wallet's mint list as encrypted Nostr events (kind 30078).
 * Uses NIP-44 v2 encryption with keys derived from the wallet mnemonic.
 * 
 * Key derivation:
 *   1. Generate 64-byte seed from mnemonic using BIP39
 *   2. Domain separator: "cashu-mint-backup"
 *   3. private_key = SHA256(seed || domain_separator)
 */
object NostrMintBackup {

    private const val TAG = "NostrMintBackup"
    private const val EVENT_KIND = 30078 // NIP-78 addressable event
    private const val D_TAG_VALUE = "mint-list"
    private const val CLIENT_NAME = "shellshock"
    private const val DOMAIN_SEPARATOR = "cashu-mint-backup"

    // Nostr relays for backup
    private val BACKUP_RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://nostr.mom"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // secp256k1 curve parameters
    private val SECP256K1_PARAMS = SECNamedCurves.getByName("secp256k1")
    private val SECP256K1 = ECDomainParameters(
        SECP256K1_PARAMS.curve, SECP256K1_PARAMS.g,
        SECP256K1_PARAMS.n, SECP256K1_PARAMS.h
    )

    /**
     * Backup data structure that gets encrypted.
     */
    data class MintBackupData(
        val mints: List<String>,
        val timestamp: Long
    )

    /**
     * Result of a backup publish attempt.
     */
    data class BackupResult(
        val success: Boolean,
        val eventId: String?,
        val successfulRelays: List<String>,
        val failedRelays: List<String>,
        val error: String?
    )

    /**
     * Derive backup keypair from wallet mnemonic.
     * 
     * @param mnemonic The 12-word seed phrase
     * @return Pair of (privateKeyBytes, publicKeyHex)
     */
    fun deriveBackupKeys(mnemonic: String): Pair<ByteArray, String> {
        // Step 1: Generate 64-byte seed from mnemonic using BIP39 (PBKDF2)
        val seed = mnemonicToSeed(mnemonic)
        
        // Step 2: Concatenate seed with domain separator
        val domainBytes = DOMAIN_SEPARATOR.toByteArray(StandardCharsets.UTF_8)
        val combined = ByteArray(seed.size + domainBytes.size)
        System.arraycopy(seed, 0, combined, 0, seed.size)
        System.arraycopy(domainBytes, 0, combined, seed.size, domainBytes.size)
        
        // Step 3: SHA256 of combined data = private key
        val privateKey = sha256(combined)
        
        // Step 4: Derive public key (x-only, 32 bytes)
        val d = BigInteger(1, privateKey)
        val Q = SECP256K1.g.multiply(d).normalize()
        val pubX = Q.affineXCoord.encoded // 32 bytes
        
        Log.d(TAG, "Derived backup pubkey: ${bytesToHex(pubX)}")
        
        return Pair(privateKey, bytesToHex(pubX))
    }

    /**
     * Create and publish a mint backup event.
     * 
     * @param mnemonic The wallet mnemonic for key derivation
     * @param mints List of mint URLs to backup
     * @param callback Optional callback with result
     */
    fun publishMintBackup(
        mnemonic: String,
        mints: List<String>,
        callback: ((BackupResult) -> Unit)? = null
    ) {
        scope.launch {
            try {
                Log.d(TAG, "Starting mint backup for ${mints.size} mints")
                
                // Derive keys
                val (privateKey, publicKeyHex) = deriveBackupKeys(mnemonic)
                val publicKeyBytes = hexToBytes(publicKeyHex)
                
                // Create backup data
                val timestamp = System.currentTimeMillis() / 1000
                val backupData = MintBackupData(mints = mints, timestamp = timestamp)
                val backupJson = gson.toJson(backupData)
                
                Log.d(TAG, "Backup data: $backupJson")
                
                // Encrypt with NIP-44 v2 (self-encryption: same key for both sides)
                val conversationKey = Nip44.getConversationKey(privateKey, publicKeyBytes)
                val encryptedContent = Nip44.encrypt(backupJson, conversationKey)
                
                Log.d(TAG, "Encrypted content length: ${encryptedContent.length}")
                
                // Create Nostr event
                val event = createEvent(
                    kind = EVENT_KIND,
                    content = encryptedContent,
                    pubkey = publicKeyHex,
                    tags = listOf(
                        listOf("d", D_TAG_VALUE),
                        listOf("client", CLIENT_NAME)
                    ),
                    createdAt = timestamp
                )
                
                // Compute event ID
                val eventId = computeEventId(event)
                event.id = eventId
                
                // Sign event with Schnorr signature
                val signature = signSchnorr(privateKey, hexToBytes(eventId))
                event.sig = bytesToHex(signature)
                
                Log.d(TAG, "Created event with ID: $eventId")
                
                // Publish to all relays
                val successfulRelays = ConcurrentHashMap.newKeySet<String>()
                val failedRelays = ConcurrentHashMap.newKeySet<String>()
                val latch = java.util.concurrent.CountDownLatch(BACKUP_RELAYS.size)
                
                for (relayUrl in BACKUP_RELAYS) {
                    publishToRelay(relayUrl, event, eventId,
                        onSuccess = {
                            successfulRelays.add(relayUrl)
                            Log.d(TAG, "✅ Published backup to $relayUrl (event: $eventId)")
                            latch.countDown()
                        },
                        onFailure = { error ->
                            failedRelays.add(relayUrl)
                            Log.e(TAG, "❌ Failed to publish backup to $relayUrl: $error")
                            latch.countDown()
                        }
                    )
                }
                
                // Wait for all relays with timeout
                latch.await(30, TimeUnit.SECONDS)
                
                val result = BackupResult(
                    success = successfulRelays.isNotEmpty(),
                    eventId = eventId,
                    successfulRelays = successfulRelays.toList(),
                    failedRelays = failedRelays.toList(),
                    error = if (successfulRelays.isEmpty()) "Failed to publish to any relay" else null
                )
                
                Log.d(TAG, "Mint backup complete: ${successfulRelays.size}/${BACKUP_RELAYS.size} relays successful")
                Log.d(TAG, "Event ID: $eventId")
                Log.d(TAG, "Successful relays: ${successfulRelays.joinToString(", ")}")
                if (failedRelays.isNotEmpty()) {
                    Log.w(TAG, "Failed relays: ${failedRelays.joinToString(", ")}")
                }
                
                callback?.invoke(result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Mint backup failed", e)
                callback?.invoke(BackupResult(
                    success = false,
                    eventId = null,
                    successfulRelays = emptyList(),
                    failedRelays = BACKUP_RELAYS,
                    error = e.message
                ))
            }
        }
    }

    private fun createEvent(
        kind: Int,
        content: String,
        pubkey: String,
        tags: List<List<String>>,
        createdAt: Long
    ): NostrEvent {
        return NostrEvent().apply {
            this.kind = kind
            this.content = content
            this.pubkey = pubkey
            this.tags = tags.map { it.toMutableList() }
            this.created_at = createdAt
        }
    }

    private fun computeEventId(event: NostrEvent): String {
        // NIP-01: id = sha256([0, pubkey, created_at, kind, tags, content])
        val arr = JsonArray()
        arr.add(JsonPrimitive(0))
        arr.add(JsonPrimitive(event.pubkey ?: ""))
        arr.add(JsonPrimitive(event.created_at))
        arr.add(JsonPrimitive(event.kind))
        
        val tagsArray = JsonArray()
        event.tags?.forEach { tag ->
            val t = JsonArray()
            tag.forEach { v -> t.add(JsonPrimitive(v ?: "")) }
            tagsArray.add(t)
        }
        arr.add(tagsArray)
        arr.add(JsonPrimitive(event.content ?: ""))
        
        val jsonBytes = arr.toString().toByteArray(StandardCharsets.UTF_8)
        return bytesToHex(sha256(jsonBytes))
    }

    private fun publishToRelay(
        relayUrl: String,
        event: NostrEvent,
        eventId: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val request = Request.Builder().url(relayUrl).build()
            
            okHttpClient.newWebSocket(request, object : WebSocketListener() {
                private var sentEvent = false
                
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Connected to $relayUrl, sending event...")
                    
                    // Send EVENT message
                    val eventJson = JsonArray().apply {
                        add("EVENT")
                        add(gson.toJsonTree(event))
                    }
                    webSocket.send(eventJson.toString())
                    sentEvent = true
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val arr = gson.fromJson(text, JsonArray::class.java)
                        if (arr.size() >= 2) {
                            val type = arr[0].asString
                            when (type) {
                                "OK" -> {
                                    val receivedEventId = arr[1].asString
                                    val accepted = arr[2].asBoolean
                                    if (receivedEventId == eventId && accepted) {
                                        onSuccess()
                                    } else {
                                        val message = if (arr.size() > 3) arr[3].asString else "rejected"
                                        onFailure("Event not accepted: $message")
                                    }
                                    webSocket.close(1000, "done")
                                }
                                "NOTICE" -> {
                                    Log.w(TAG, "NOTICE from $relayUrl: ${arr[1].asString}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing response from $relayUrl", e)
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onFailure(t.message ?: "connection failed")
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!sentEvent) {
                        onFailure("connection closed before event sent")
                    }
                }
            })
        } catch (e: Exception) {
            onFailure(e.message ?: "failed to connect")
        }
    }

    /**
     * BIP39 mnemonic to seed (simplified implementation using PBKDF2-HMAC-SHA512).
     */
    private fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val salt = "mnemonic$passphrase".toByteArray(StandardCharsets.UTF_8)
        val mnemonicBytes = mnemonic.toByteArray(StandardCharsets.UTF_8)
        
        // PBKDF2-HMAC-SHA512 with 2048 iterations, 64-byte output
        return pbkdf2HmacSha512(mnemonicBytes, salt, 2048, 64)
    }

    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        
        val hLen = 64 // SHA-512 output length
        val l = (dkLen + hLen - 1) / hLen
        val dk = ByteArray(dkLen)
        
        for (i in 1..l) {
            val block = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, block, 0, salt.size)
            block[salt.size] = ((i shr 24) and 0xFF).toByte()
            block[salt.size + 1] = ((i shr 16) and 0xFF).toByte()
            block[salt.size + 2] = ((i shr 8) and 0xFF).toByte()
            block[salt.size + 3] = (i and 0xFF).toByte()
            
            var u = mac.doFinal(block)
            val t = u.copyOf()
            
            for (j in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            
            val offset = (i - 1) * hLen
            val len = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, dk, offset, len)
        }
        
        return dk
    }

    /**
     * BIP-340 Schnorr signature.
     */
    private fun signSchnorr(privateKey: ByteArray, message: ByteArray): ByteArray {
        val d = BigInteger(1, privateKey)
        val n = SECP256K1.n
        
        // Get public key point
        val P = SECP256K1.g.multiply(d).normalize()
        val px = P.affineXCoord.encoded
        
        // If P.y is odd, negate d
        val dNeg = if (P.affineYCoord.toBigInteger().testBit(0)) n.subtract(d) else d
        
        // Generate deterministic k using RFC 6979
        val aux = ByteArray(32)
        SecureRandom().nextBytes(aux)
        
        // t = bytes(d xor sha256(tag_aux || aux))
        val tagAux = sha256("BIP0340/aux".toByteArray(StandardCharsets.UTF_8))
        val auxHash = sha256(tagAux + tagAux + aux)
        val t = ByteArray(32)
        for (i in 0..31) {
            t[i] = (to32Bytes(dNeg)[i].toInt() xor auxHash[i].toInt()).toByte()
        }
        
        // k' = sha256(tag_nonce || tag_nonce || t || px || m) mod n
        val tagNonce = sha256("BIP0340/nonce".toByteArray(StandardCharsets.UTF_8))
        val kPrimeBytes = sha256(tagNonce + tagNonce + t + px + message)
        var kPrime = BigInteger(1, kPrimeBytes).mod(n)
        if (kPrime == BigInteger.ZERO) {
            throw IllegalStateException("k' is zero")
        }
        
        // R = k' * G
        val R = SECP256K1.g.multiply(kPrime).normalize()
        
        // If R.y is odd, negate k'
        val k = if (R.affineYCoord.toBigInteger().testBit(0)) n.subtract(kPrime) else kPrime
        
        val rx = R.affineXCoord.encoded
        
        // e = sha256(tag_challenge || tag_challenge || rx || px || m) mod n
        val tagChallenge = sha256("BIP0340/challenge".toByteArray(StandardCharsets.UTF_8))
        val eBytes = sha256(tagChallenge + tagChallenge + rx + px + message)
        val e = BigInteger(1, eBytes).mod(n)
        
        // s = (k + e * d') mod n
        val s = k.add(e.multiply(dNeg)).mod(n)
        
        // signature = bytes(R.x) || bytes(s)
        val sig = ByteArray(64)
        System.arraycopy(rx, 0, sig, 0, 32)
        System.arraycopy(to32Bytes(s), 0, sig, 32, 32)
        
        return sig
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun to32Bytes(v: BigInteger): ByteArray {
        val src = v.toByteArray()
        if (src.size == 32) return src
        val out = ByteArray(32)
        if (src.size > 32) {
            System.arraycopy(src, src.size - 32, out, 0, 32)
        } else {
            System.arraycopy(src, 0, out, 32 - src.size, src.size)
        }
        return out
    }

    private fun bytesToHex(data: ByteArray): String {
        return data.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
