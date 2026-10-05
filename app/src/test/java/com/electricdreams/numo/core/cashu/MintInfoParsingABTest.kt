package com.electricdreams.numo.core.cashu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * A/B regression for the mint-limits parser.
 *
 * A (control): a mint whose cached NUT-04/05 min_amount/max_amount are JSON
 * numbers — the common shape; limits must parse.
 * B (treatment): the same cache with min_amount as the STRING
 * "Amount(value=1)" — the cached shape observed from
 * https://testnut.cashu.space — must not nuke the whole limits parse.
 */
class MintInfoParsingABTest {

    private fun mintInfoJson(minAmount: String): String = """
        {
          "name": "A/B test mint",
          "nuts": {
            "4": {
              "disabled": false,
              "methods": [
                { "method": "bolt11", "unit": "sat", "min_amount": $minAmount, "max_amount": 500000 }
              ]
            },
            "5": {
              "disabled": false,
              "methods": [
                { "method": "bolt11", "unit": "sat", "min_amount": $minAmount, "max_amount": 500000 }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `A numeric min_amount parses into limits`() {
        val info = CashuWalletManager.mintInfoFromJson(mintInfoJson("1"))
        assertNotNull(info)
        assertNotNull(info!!.mintLimits)
        assertEquals(1L, info.mintLimits!!.mintMethods.first().minAmount)
        assertEquals(500000L, info.mintLimits.mintMethods.first().maxAmount)
    }

    @Test
    fun `B string Amount(value=1) must not destroy the limits parse`() {
        val info = CashuWalletManager.mintInfoFromJson(mintInfoJson("\"Amount(value=1)\""))
        assertNotNull(info)
        assertNotNull("limits died on string min_amount — whole NUT-04/05 parse thrown away", info!!.mintLimits)
    }
}
