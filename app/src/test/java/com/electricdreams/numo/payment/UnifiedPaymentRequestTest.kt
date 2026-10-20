package com.electricdreams.numo.payment

import java.net.URLDecoder
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedPaymentRequestTest {
    private fun parse(uri: String): Map<String, String> = uri.substringAfter('?')
        .split('&').associate { parameter ->
            val (key, value) = parameter.split('=', limit = 2)
            key.lowercase(Locale.ROOT) to URLDecoder.decode(value, "UTF-8")
        }

    @Test
    fun `unified URI carries independent Lightning and Ark alternatives with BTC amount`() {
        val uri = UnifiedPaymentRequest(330, "creqb1request", "lnbc3300n1invoice", "ark1address")
            .toUri() ?: error("Missing URI")
        assertTrue(uri.startsWith("BITCOIN:?"))
        val params = parse(uri)
        assertEquals("0.0000033", params["amount"])
        assertEquals("ARK1ADDRESS", params["ark"])
        assertEquals("LNBC3300N1INVOICE", params["lightning"])
        assertEquals("CREQB1REQUEST", params["creq"])
        assertFalse(params.containsKey("arkoor"))
    }

    @Test
    fun `no unified payload is exposed until both quote attempts finish`() {
        val request = UnifiedPaymentRequest(330, cashu = "creqb1request",
            lightningPending = true, arkoorPending = true)
        assertNull(request.toUri())
        assertNull(request.copy(lightning = "lnbc1invoice", lightningPending = false).toUri())
        assertNull(request.copy(ark = "ark1address", arkoorPending = false).toUri())
        assertTrue(request.copy(lightning = "lnbc1invoice", ark = "ark1address",
            lightningPending = false, arkoorPending = false).toUri() != null)
    }

    @Test
    fun `a failed method does not remove the other alternatives`() {
        val uri = UnifiedPaymentRequest(330, "creqAAbCd+=&value", ark = "ark1address")
            .toUri() ?: error("Missing URI")
        val params = parse(uri)
        assertEquals("creqAAbCd+=&value", params["creq"])
        assertEquals("ARK1ADDRESS", params["ark"])
        assertFalse(params.containsKey("lightning"))
        assertNull(UnifiedPaymentRequest(330).toUri())
    }

    @Test
    fun `amount is exact for single sats and large values regardless of locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.00000001", parse(UnifiedPaymentRequest(1, ark = "ark1address")
                .toUri() ?: error("Missing URI"))["amount"])
            assertEquals("21000000", parse(UnifiedPaymentRequest(2_100_000_000_000_000,
                ark = "ark1address").toUri() ?: error("Missing URI"))["amount"])
        } finally {
            Locale.setDefault(previous)
        }
    }
}
