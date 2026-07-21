package com.electricdreams.numo.nostr

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/** Signs NIP-01 events with BIP-340 Schnorr signatures. */
object NostrEventSigner {
    private val curveParameters = SECNamedCurves.getByName("secp256k1")
    private val curve = ECDomainParameters(
        curveParameters.curve,
        curveParameters.g,
        curveParameters.n,
        curveParameters.h
    )
    private val secureRandom = SecureRandom()

    fun sign(event: NostrEvent, privateKey: ByteArray): NostrEvent {
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        event.id = event.computeId()
        event.sig = bytesToHex(signSchnorr(privateKey, hexToBytes(event.id)))
        return event
    }

    private fun signSchnorr(privateKey: ByteArray, message: ByteArray): ByteArray {
        val secret = BigInteger(1, privateKey)
        val order = curve.n
        require(secret.signum() > 0 && secret < order) { "Invalid private key" }
        require(message.size == 32) { "Message must be 32 bytes" }

        val publicPoint = curve.g.multiply(secret).normalize()
        val publicX = publicPoint.affineXCoord.encoded
        val normalizedSecret = if (publicPoint.affineYCoord.toBigInteger().testBit(0)) {
            order.subtract(secret)
        } else {
            secret
        }

        val auxiliaryRandom = ByteArray(32).also(secureRandom::nextBytes)
        val auxiliaryTag = sha256("BIP0340/aux".toByteArray(StandardCharsets.UTF_8))
        val auxiliaryHash = sha256(auxiliaryTag + auxiliaryTag + auxiliaryRandom)
        auxiliaryRandom.fill(0)
        val normalizedBytes = to32Bytes(normalizedSecret)
        val nonceInput = ByteArray(32) { index ->
            (normalizedBytes[index].toInt() xor auxiliaryHash[index].toInt()).toByte()
        }
        normalizedBytes.fill(0)
        auxiliaryHash.fill(0)

        val nonceTag = sha256("BIP0340/nonce".toByteArray(StandardCharsets.UTF_8))
        var nonce = BigInteger(
            1,
            sha256(nonceTag + nonceTag + nonceInput + publicX + message)
        ).mod(order)
        nonceInput.fill(0)
        require(nonce != BigInteger.ZERO) { "Invalid zero nonce" }

        val noncePoint = curve.g.multiply(nonce).normalize()
        if (noncePoint.affineYCoord.toBigInteger().testBit(0)) {
            nonce = order.subtract(nonce)
        }
        val nonceX = noncePoint.affineXCoord.encoded
        val challengeTag = sha256("BIP0340/challenge".toByteArray(StandardCharsets.UTF_8))
        val challenge = BigInteger(
            1,
            sha256(challengeTag + challengeTag + nonceX + publicX + message)
        ).mod(order)
        val signatureScalar = nonce.add(challenge.multiply(normalizedSecret)).mod(order)

        return nonceX + to32Bytes(signatureScalar)
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun to32Bytes(value: BigInteger): ByteArray {
        val source = value.toByteArray()
        val result = ByteArray(32)
        val sourceOffset = maxOf(0, source.size - 32)
        val copyLength = minOf(source.size, 32)
        System.arraycopy(source, sourceOffset, result, 32 - copyLength, copyLength)
        return result
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length == 64) { "Event ID must be 32 bytes" }
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(value: ByteArray): String =
        value.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
