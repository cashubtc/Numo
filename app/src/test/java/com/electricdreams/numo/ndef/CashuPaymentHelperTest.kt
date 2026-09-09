package com.electricdreams.numo.ndef

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.ndef.CashuPaymentHelper.extractCashuToken
import com.electricdreams.numo.ndef.CashuPaymentHelper.isCashuPaymentRequest
import com.electricdreams.numo.ndef.CashuPaymentHelper.isCashuToken

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure tests for the deterministic helpers in [CashuPaymentHelper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CashuPaymentHelperTest {

    @Test
    fun `isCashuToken recognizes cashuA prefix`() {
        assertTrue(isCashuToken("cashuA123"))
        // Implementation is case-sensitive (expects lower-case prefix)
        assertFalse(isCashuToken("CASHUaXYZ"))
        assertFalse(isCashuToken(null))
        assertFalse(isCashuToken("not-a-token"))
    }

    @Test
    fun `isCashuPaymentRequest recognizes creqA prefix`() {
        assertTrue(isCashuPaymentRequest("creqA123"))
        assertFalse(isCashuPaymentRequest(null))
        assertFalse(isCashuPaymentRequest("cashuA123"))
    }

    @Test
    fun `extractCashuToken returns full token when input is just the token`() {
        val tokenString = "cashuAabcdefg12345"
        // When the whole string is a token, helper returns it unchanged
        val token = extractCashuToken(tokenString)

        assertEquals(tokenString, token)
    }

    @Test
    fun `extractCashuToken stops at whitespace or delimiters`() {
        val variants = listOf(
            "before cashuAabc123 after",
            "json: \"cashuAabc123\" end",
            "html: <span>cashuAabc123</span>",
        )

        variants.forEach { text ->
            val token = extractCashuToken(text)
            assertEquals("cashuAabc123", token)
        }
    }

    @Test
    fun `extractCashuToken returns null when no token is present`() {
        val text = "there is no token here"

        val token = extractCashuToken(text)

        assertNull(token)
    }

    @Test
    fun `payment request carries explicit custom unit amount and mints`() {
        val generated = CashuPaymentHelper.createPaymentRequest(
            amount = 42L,
            unit = "POINTS",
            description = "Arcade credit",
            allowedMints = listOf("https://mint.example"),
        )

        assertNotNull(generated)
        val encoded = generated?.original.orEmpty()
        val payload = android.util.Base64.decode(
            encoded.removePrefix("creqA"),
            android.util.Base64.URL_SAFE or
                android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING,
        )
        val cbor = com.upokecenter.cbor.CBORObject.DecodeFromBytes(payload)
        assertEquals("points", cbor["u"].AsString())
        assertEquals(42L, cbor["a"].AsInt64())
        assertEquals("https://mint.example", cbor["m"][0].AsString())
    }

    @Test
    fun `reserved unit cannot create a payment request`() {
        assertNull(
            CashuPaymentHelper.createPaymentRequest(
                amount = 1L,
                unit = "auth",
                description = null,
                allowedMints = null,
            ),
        )
    }

    @Test
    fun `unknown mint acceptance requires a compatible destination and an unscoped asset`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = MintManager.getInstance(context)
        manager.setMintChangeListener(null)
        manager.getAllowedMints().toList().forEach { manager.removeMint(it) }
        val mint = "https://destination.example"
        manager.addMint(mint)
        manager.setMintUnits(mint, listOf("sat", "points"))
        manager.setMintInfo(mint, """{"nuts":{"4":{"methods":[
            {"method":"bolt12","unit":"sat"}, {"method":"bolt11","unit":"points"}
        ]}}}""")
        assertFalse(CashuPaymentHelper.supportsUnknownMintSwap(context, "sat"))
        assertFalse(CashuPaymentHelper.supportsUnknownMintSwap(context, "points"))
        manager.setMintInfo(mint, """{"nuts":{"4":{"methods":[
            {"method":"bolt11","unit":"sat"}
        ]}}}""")
        assertTrue(CashuPaymentHelper.supportsUnknownMintSwap(context, "sat"))
        assertFalse(CashuPaymentHelper.supportsUnknownMintSwap(context, "auth"))
    }

}
