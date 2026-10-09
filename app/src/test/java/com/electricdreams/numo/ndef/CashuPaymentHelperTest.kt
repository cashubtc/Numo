package com.electricdreams.numo.ndef

import com.electricdreams.numo.ndef.CashuPaymentHelper.extractCashuToken
import com.electricdreams.numo.ndef.CashuPaymentHelper.isCashuPaymentRequest
import com.electricdreams.numo.ndef.CashuPaymentHelper.isCashuToken

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.AppGlobals
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.util.MintManager
import com.electricdreams.numo.payment.SwapToLightningMintManager
import kotlinx.coroutines.runBlocking
import org.cashudevkit.Amount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.Proof
import org.cashudevkit.Wallet
import org.cashudevkit.WalletRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for payment parsing and checkout unit handling in [CashuPaymentHelper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CashuPaymentHelperTest {

    @Test
    fun `cashu proof redemption uses saved checkout unit after preference changes`(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        AppGlobals.init(context)
        val manager = mock<MintManager>()
        whenever(manager.getPreferredUnit()).thenReturn("usd")
        val repository = mock<WalletRepository>()
        val wallet = mock<Wallet>()
        val mintUrl = "https://mint.test"
        whenever(repository.getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)).thenReturn(wallet)
        val proof = mock<Proof>()
        whenever(proof.amount).thenReturn(Amount(1_000u))
        val proofs = listOf(proof)
        val previousWallet = CashuWalletManager.getWallet()
        ReflectionHelpers.setStaticField(MintManager::class.java, "instance", manager)
        ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", repository)

        try {
            val result = CashuPaymentHelper.redeemProofsWithSwap(
                context, proofs, mintUrl, "sat", 1_000L, listOf(mintUrl),
                SwapToLightningMintManager.PaymentContext("saved-payment", 1_000L, unit = "sat"),
            )

            assertEquals("SUCCESS_KNOWN", result)
            verify(repository).getWallet(MintUrl(mintUrl), CurrencyUnit.Sat)
            verify(wallet).receiveProofs(eq(proofs), any(), anyOrNull(), anyOrNull())
        } finally {
            ReflectionHelpers.setStaticField(MintManager::class.java, "instance", null)
            ReflectionHelpers.setStaticField(CashuWalletManager::class.java, "wallet", previousWallet)
        }
    }

    @Test(expected = CashuPaymentHelper.RedemptionException::class)
    fun `cashu redemption rejects proofs in a different checkout unit`(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        CashuPaymentHelper.redeemProofsWithSwap(
            context, emptyList(), "https://mint.test", "usd", 100L, null,
            SwapToLightningMintManager.PaymentContext("saved-payment", 100L, unit = "sat"),
        )
    }

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

}
