package com.electricdreams.numo.ndef

import com.electricdreams.numo.ndef.CashuPaymentHelper.PaymentRequestPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PaymentRequestPayloadTest {

    @Test
    fun `GSON parses proofs with secret and dleq`() {
        val json = """
            {
              "mint": "https://mint.example",
              "unit": "sat",
              "proofs": [
                {
                  "amount": 8,
                  "id": "abc123",
                  "secret": "secret-1",
                  "C": "deadbeef",
                  "dleq": { "r": "01", "s": "02", "e": "03" }
                }
              ]
            }
        """.trimIndent()

        val payload = PaymentRequestPayload.GSON.fromJson(json, PaymentRequestPayload::class.java)

        assertEquals("https://mint.example", payload.mint)
        assertEquals("sat", payload.unit)
        assertNotNull(payload.proofs)
        assertEquals(1, payload.proofs!!.size)
        val proof = payload.proofs!![0]
        assertEquals(8, proof.amount)
        assertEquals("abc123", proof.keysetId)
        val secret = proof.secret as com.cashujdk.nut00.StringSecret
        assertEquals("secret-1", secret.secret)
        assertEquals("deadbeef", proof.c)
        assertNotNull(proof.dleq)
    }
    
    @Test(expected = com.google.gson.JsonParseException::class)
    fun `GSON rejects proofs without keyset id`() {
        val json = """
            {
              "mint": "https://mint.example",
              "unit": "sat",
              "proofs": [
                {
                  "amount": 4,
                  "secret": "secret-2",
                  "C": "feedface"
                }
              ]
            }
        """.trimIndent()

        PaymentRequestPayload.GSON.fromJson(json, PaymentRequestPayload::class.java)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `payload parsing fails when proofs array empty`() {
        val json = """
            {
              "mint": "https://mint.example",
              "unit": "sat",
              "proofs": []
            }
        """.trimIndent()
        PaymentRequestPayload.GSON.fromJson(json, PaymentRequestPayload::class.java).apply {
            require(!(proofs == null || proofs!!.isEmpty())) { "Proofs should not be empty" }
        }
    }
}
