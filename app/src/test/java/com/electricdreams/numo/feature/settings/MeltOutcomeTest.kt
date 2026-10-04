package com.electricdreams.numo.feature.settings

import java.io.IOException
import org.cashudevkit.FfiException
import org.junit.Assert.assertEquals
import org.junit.Test

class MeltOutcomeTest {

    @Test
    fun `a failed lightning payment is a refusal`() {
        assertEquals(MeltOutcome.REFUSED, MeltOutcome.forError(FfiException.Cdk(20004u, "Payment failed")))
    }

    @Test
    fun `an expired quote is a refusal`() {
        assertEquals(MeltOutcome.REFUSED, MeltOutcome.forCode(20007))
    }

    @Test
    fun `a pending quote stays pending`() {
        assertEquals(MeltOutcome.PENDING, MeltOutcome.forError(FfiException.Cdk(20005u, "Quote is pending")))
    }

    @Test
    fun `spent proofs are not treated as a refusal`() {
        // The proofs may have been spent by this very payment.
        assertEquals(MeltOutcome.UNCONFIRMED, MeltOutcome.forCode(11001))
    }

    @Test
    fun `internal and network errors are unconfirmed`() {
        assertEquals(MeltOutcome.UNCONFIRMED, MeltOutcome.forError(FfiException.Internal("timeout")))
        assertEquals(MeltOutcome.UNCONFIRMED, MeltOutcome.forError(IOException("connection reset")))
    }
}
