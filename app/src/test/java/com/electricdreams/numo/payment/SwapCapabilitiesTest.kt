package com.electricdreams.numo.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.util.MintManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SwapCapabilitiesTest {
    private lateinit var context: Context
    private lateinit var manager: MintManager
    private val source = "https://source.example"
    private val destination = "https://destination.example"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = MintManager.getInstance(context)
        manager.setMintChangeListener(null)
        manager.getAllowedMints().toList().forEach { manager.removeMint(it) }
        manager.addMint(destination)
        manager.setMintUnits(destination, listOf("sat"))
    }

    @Test
    fun `source mint support cannot substitute for melt support`() = runTest {
        manager.setMintInfo(source, """{"nuts":{
            "4":{"methods":[{"method":"bolt11","unit":"sat"}]},
            "5":{"methods":[{"method":"bolt12","unit":"sat"}]}
        }}""")

        assertEquals("Source mint does not support BOLT11 melting for sat", attemptSwap())
    }

    @Test
    fun `destination melt support cannot substitute for mint support`() = runTest {
        manager.setMintInfo(source, """{"nuts":{
            "5":{"methods":[{"method":"bolt11","unit":"sat"}]}
        }}""")
        manager.setMintInfo(destination, """{"nuts":{
            "5":{"methods":[{"method":"bolt11","unit":"sat"}]}
        }}""")

        assertEquals("No configured mint supports BOLT11 minting for sat", attemptSwap())
    }

    @Test
    fun `custom assets retain their issuer restriction independently of capabilities`() = runTest {
        assertEquals("This asset must be paid by its configured issuer", attemptSwap("points"))
    }

    private suspend fun attemptSwap(unit: String = "sat"): String? {
        val result = SwapToLightningMintManager.swapFromUnknownMint(
            context, emptyList(), 1_000L, source,
            SwapToLightningMintManager.PaymentContext(null, 1_000L, unit),
        )
        return (result as? SwapToLightningMintManager.SwapResult.Failure)?.errorMessage
    }
}
