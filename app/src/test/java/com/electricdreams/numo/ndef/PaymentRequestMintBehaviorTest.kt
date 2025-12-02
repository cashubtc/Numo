package com.electricdreams.numo.ndef

import com.cashujdk.nut18.PaymentRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional

/**
 * Tests for mint list behavior in [CashuPaymentHelper] payment requests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PaymentRequestMintBehaviorTest {

    @Test
    fun `createPaymentRequest omits mints when allowedMints is null`() {
        val encoded = CashuPaymentHelper.createPaymentRequest(
            amount = 100L,
            description = "Test",
            allowedMints = null,
        )

        assertFalse("PaymentRequest should have been encoded", encoded.isNullOrBlank())

        val pr = PaymentRequest.decode(encoded)
        assertEquals(Optional.of(100L), pr.amount)
        assertEquals(Optional.of("sat"), pr.unit)
        assertEquals(Optional.of("Test"), pr.description)
        // When no mints are provided, the field must remain empty
        assertEquals(Optional.empty<Array<String>>(), pr.mints)
    }

    @Test
    fun `createPaymentRequest populates mints when list is provided`() {
        val allowedMints = listOf(
            "https://mint.one",
            "https://mint.two",
        )

        val encoded = CashuPaymentHelper.createPaymentRequest(
            amount = 42L,
            description = "With mints",
            allowedMints = allowedMints,
        )

        assertFalse("PaymentRequest should have been encoded", encoded.isNullOrBlank())

        val pr = PaymentRequest.decode(encoded)
        assertEquals(Optional.of(42L), pr.amount)
        assertEquals(Optional.of("sat"), pr.unit)
        assertEquals(Optional.of("With mints"), pr.description)
        assertTrue("Mints field should be present", pr.mints.isPresent)
        assertArrayEquals(allowedMints.toTypedArray(), pr.mints.get())
    }

    @Test
    fun `createPaymentRequestWithNostr omits mints when list is null`() {
        val nprofile = "nprofile1qqqq-test"

        val encoded = CashuPaymentHelper.createPaymentRequestWithNostr(
            amount = 77L,
            description = "Nostr no mints",
            allowedMints = null,
            nprofile = nprofile,
        )

        assertFalse("PaymentRequest should have been encoded", encoded.isNullOrBlank())

        val pr = PaymentRequest.decode(encoded)
        assertEquals(Optional.of(77L), pr.amount)
        assertEquals(Optional.of("sat"), pr.unit)
        assertEquals(Optional.of("Nostr no mints"), pr.description)
        // For Nostr as well, when mints are not provided, keep field empty
        assertEquals(Optional.empty<Array<String>>(), pr.mints)
    }

    @Test
    fun `createPaymentRequestWithNostr populates mints when list is provided`() {
        val nprofile = "nprofile1qqqq-test"
        val allowedMints = listOf(
            "https://mint.alpha",
            "https://mint.beta",
        )

        val encoded = CashuPaymentHelper.createPaymentRequestWithNostr(
            amount = 123L,
            description = "Nostr with mints",
            allowedMints = allowedMints,
            nprofile = nprofile,
        )

        assertFalse("PaymentRequest should have been encoded", encoded.isNullOrBlank())

        val pr = PaymentRequest.decode(encoded)
        assertEquals(Optional.of(123L), pr.amount)
        assertEquals(Optional.of("sat"), pr.unit)
        assertEquals(Optional.of("Nostr with mints"), pr.description)
        assertTrue("Mints field should be present", pr.mints.isPresent)
        assertArrayEquals(allowedMints.toTypedArray(), pr.mints.get())
    }
}
