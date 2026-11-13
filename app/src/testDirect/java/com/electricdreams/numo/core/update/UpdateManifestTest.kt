package com.electricdreams.numo.core.update

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateManifestTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val verifier = UpdateManifestVerifier(listOf(keys.public))

    private fun payload() = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("packageName", "com.electricdreams.numo")
        addProperty("channel", "stable")
        addProperty("versionCode", 26)
        addProperty("versionName", "1.10")
        addProperty("minSdk", 24)
        addProperty("apkUrl", "https://github.com/cashubtc/Numo/releases/download/v1.10/numo-v1.10-universal.apk")
        addProperty("size", 1024)
        addProperty("sha256", "a".repeat(64))
        addProperty("releaseNotes", "Fix payments")
    }

    private fun envelope(json: JsonObject): String {
        val bytes = json.toString().toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(UpdateManifestVerifier.SIGNING_CONTEXT.toByteArray())
            update(bytes)
            sign()
        }
        return JsonObject().apply {
            addProperty("payload", Base64.getEncoder().encodeToString(bytes))
            addProperty("signature", Base64.getEncoder().encodeToString(signature))
        }.toString()
    }

    @Test
    fun `only the installed signer can authenticate release metadata`() {
        val signed = envelope(payload())
        assertEquals(26L, verifier.verify(signed).versionCode)
        val foreign = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestVerifier(listOf(foreign.public)).verify(signed)
        }
    }

    @Test
    fun `tampering with payload invalidates the signature`() {
        val json = com.google.gson.JsonParser.parseString(envelope(payload())).asJsonObject
        json.addProperty("payload", Base64.getEncoder().encodeToString("{}".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { verifier.verify(json.toString()) }
    }

    @Test
    fun `signed metadata rejects untrusted destinations channels and malformed fields`() {
        listOf(
            "apkUrl" to "http://github.com/cashubtc/Numo/releases/download/v1/a-universal.apk",
            "apkUrl" to "https://evil.example/a-universal.apk",
            "apkUrl" to "https://github.com/attacker/Numo/releases/download/v1/a-universal.apk",
            "packageName" to "com.attacker.wallet",
            "channel" to "beta",
            "sha256" to "xyz",
        ).forEach { (field, value) ->
            assertThrows(field, IllegalArgumentException::class.java) {
                verifier.verify(envelope(payload().apply { addProperty(field, value) }))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(envelope(payload().apply { addProperty("size", -1) }))
        }
    }
}
