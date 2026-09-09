package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.electricdreams.numo.core.cashu.CashuWalletManager
import com.electricdreams.numo.core.model.UnitId
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.cashudevkit.Amount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintInfo
import org.cashudevkit.MintMethodSettings
import org.cashudevkit.Nut04Settings
import org.cashudevkit.Nuts
import org.cashudevkit.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MintCapabilitiesCacheTest {
    private lateinit var context: Context
    private lateinit var manager: MintManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = MintManager.getInstance(context)
        manager.setMintChangeListener(null)
        CashuWalletManager::class.java.getDeclaredField("wallet").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun `melt-only metadata with nullable limits is usable from cache`() = runTest {
        val mint = "https://melt-only.example"
        manager.setMintInfo(mint, """{"nuts":{"5":{"methods":[{
            "method":"bolt11","unit":"points","min_amount":null,"max_amount":100
        }],"disabled":false}}}""")
        val capabilities = manager.getMintCapabilities(mint)
        assertNotNull(capabilities.find(UnitId.of("points"), MintOperation.MELT, "bolt11"))
        assertNull(capabilities.find(UnitId.of("points"), MintOperation.MINT, "bolt11"))
    }

    @Test
    fun `successful refresh with disabled empty methods replaces old support`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val mint = server.url("/").toString().removeSuffix("/")
            manager.setMintInfo(mint, """{"nuts":{"4":{"methods":[{
                "method":"bolt11","unit":"sat"
            }]}}}""")
            server.enqueue(MockResponse().setBody(
                """{"nuts":{"4":{"disabled":true,"methods":[]}}}""",
            ))
            server.enqueue(MockResponse().setBody("""{"keysets":[{"unit":"sat"}]}"""))

            val limits = manager.getMintLimits(mint, context, forceRefresh = true)

            assertNotNull(limits)
            assertNull(MintCapabilities(mint, limits).find(UnitId.SAT, MintOperation.MINT, "bolt11"))
            assertNull(manager.getCachedCapabilities(mint)
                .find(UnitId.SAT, MintOperation.MINT, "bolt11"))
        }
    }

    @Test
    fun `selection skips preferred mint with wrong operation or method and respects candidates`() = runTest {
        val preferred = "https://preferred.example"
        val destination = "https://destination.example"
        manager.setMintInfo(preferred, """{"nuts":{
            "4":{"methods":[{"method":"bolt12","unit":"usd"}]},
            "5":{"methods":[{"method":"bolt11","unit":"usd"}]}
        }}""")
        manager.setMintInfo(destination, """{"nuts":{
            "4":{"methods":[{"method":"bolt11","unit":"usd"}]}
        }}""")
        val unit = UnitId.of("usd")
        assertEquals(destination, manager.findPaymentMint(
            unit, MintOperation.MINT, "bolt11", listOf(preferred, destination), preferred,
        )?.mintUrl)
        assertNull(manager.findPaymentMint(
            unit, MintOperation.MINT, "bolt11", listOf(preferred), preferred,
        ))
    }

    @Test
    fun `CDK serialization preserves wire method names and atomic limits`() {
        val info = mock<MintInfo>()
        val nuts = mock<Nuts>()
        whenever(info.nuts).thenReturn(nuts)
        whenever(nuts.nut04).thenReturn(Nut04Settings(
            listOf(
                MintMethodSettings(PaymentMethod.Bolt11, CurrencyUnit.Sat, null,
                    Amount(10uL), Amount(100uL), false),
                MintMethodSettings(PaymentMethod.Custom("bank-transfer"), CurrencyUnit.Usd,
                    null, null, null, false),
            ), false,
        ))
        val json = CashuWalletManager.mintInfoToJson(info)
        val limits = CashuWalletManager.extractMintLimitsFromJson(json)
        val methods = requireNotNull(limits).mintMethods
        assertEquals(listOf("bolt11", "bank-transfer"), methods.map { it.method })
        assertEquals(10L, methods.first().minAmount)
        assertEquals(100L, methods.first().maxAmount)
    }
}
