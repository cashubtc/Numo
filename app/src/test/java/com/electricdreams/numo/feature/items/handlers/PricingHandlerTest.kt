package com.electricdreams.numo.feature.items.handlers

import android.app.Application
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.electricdreams.numo.R
import com.electricdreams.numo.core.model.AssetId
import com.electricdreams.numo.core.model.AtomicAmount
import com.electricdreams.numo.core.model.UnitId
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.databinding.ActivityItemEntryBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PricingHandlerTest {
    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var binding: ActivityItemEntryBinding
    private lateinit var handler: PricingHandler
    private lateinit var validator: ItemFormValidator
    private val bux = AssetId.mintScoped(UnitId.of("bux"), "https://mint.example")

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_Numo) }
        controller.setup()
        binding = ActivityItemEntryBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        val currencyManager = mock<CurrencyManager>()
        whenever(currencyManager.getCurrentCurrency()).thenReturn("EUR")
        whenever(currencyManager.getCurrentSymbol()).thenReturn("€")
        handler = PricingHandler(
            priceTypeToggle = binding.priceTypeToggle,
            btnPriceFiat = binding.btnPriceFiat,
            btnPriceBitcoin = binding.btnPriceBitcoin,
            priceUnitLayout = binding.priceUnitLayout,
            priceUnitInput = binding.priceUnitInput,
            priceUnitWarning = binding.priceUnitWarning,
            fiatPriceLayout = binding.fiatPriceLayout,
            satsPriceLayout = binding.satsPriceLayout,
            priceInput = binding.itemPriceInput,
            satsInput = binding.itemSatsInput,
            vatSectionCard = binding.vatSectionCard,
            switchVatEnabled = binding.switchVatEnabled,
            vatFieldsContainer = binding.vatFieldsContainer,
            switchPriceIncludesVat = binding.switchPriceIncludesVat,
            vatRateInput = binding.vatRateInput,
            priceBreakdownContainer = binding.priceBreakdownContainer,
            textNetPrice = binding.textNetPrice,
            textVatLabel = binding.textVatLabel,
            textVatAmount = binding.textVatAmount,
            textGrossPrice = binding.textGrossPrice,
            currencyManager = currencyManager,
        )
        handler.initialize()
        handler.setVatFields(false, 0, false)
        binding.itemNameInput.setText("Credits")
        val sku = mock<SkuHandler>()
        whenever(sku.isValid()).thenReturn(true)
        whenever(sku.getSku()).thenReturn("")
        val gtin = mock<GtinHandler>()
        whenever(gtin.isValid()).thenReturn(true)
        whenever(gtin.getGtin()).thenReturn("")
        validator = ItemFormValidator(
            activity, binding.itemNameInput, handler, mock(), sku, gtin,
        )
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun `pasted fractional custom prices are rejected without changing their value`() {
        handler.setAtomicPrice(AtomicAmount(25, bux))
        val input = binding.itemPriceInput
        assertEquals(0, input.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL)
        for (fractional in listOf("12.5", "12,5")) {
            requireNotNull(input.text).replace(0, input.length(), fractional)
            assertEquals(fractional, input.text.toString())
            assertFalse(validator.validate().isValid)
            assertEquals(
                controller.get().getString(R.string.item_entry_error_price_whole_number),
                binding.fiatPriceLayout.error.toString(),
            )
            assertNull(input.error)
            assertThrows(ArithmeticException::class.java) { handler.getEnteredAtomicAmount() }
        }

        input.setText("12")
        assertNull(binding.fiatPriceLayout.error)
        val result = validator.validate()
        assertTrue(result.isValid)
        assertEquals(12L, result.priceAtomic)
        assertEquals("bux", result.priceUnit)
        assertEquals(bux.issuerScope, result.priceIssuerScope)
    }

    @Test
    fun `changing from fiat to custom does not silently truncate a fractional price`() {
        handler.setSelectedPriceAsset(AssetId.global(UnitId.of("eur")))
        binding.itemPriceInput.setText("12.50")
        handler.setSelectedPriceAsset(bux)
        assertEquals("12.50", binding.itemPriceInput.text.toString())
        assertFalse(validator.validate().isValid)

        handler.setSelectedPriceAsset(AssetId.global(UnitId.of("eur")))
        assertNull(binding.fiatPriceLayout.error)
        assertEquals(1250L, handler.getEnteredAtomicAmount().value)
    }

    @Test
    fun `stored custom atomic prices are displayed and saved without rescaling`() {
        handler.setAtomicPrice(AtomicAmount(2500, bux))
        assertEquals("2500", binding.itemPriceInput.text.toString())
        assertEquals(AtomicAmount(2500, bux), handler.getEnteredAtomicAmount())
        assertEquals(2500L, validator.validate().priceAtomic)
    }

    @Test
    fun `VAT overflow shows an error while editing and rejects saving`() {
        handler.setSelectedPriceAsset(bux)
        handler.setVatFields(true, 20, false)
        binding.itemPriceInput.setText(Long.MAX_VALUE.toString())

        assertEquals(View.GONE, binding.priceBreakdownContainer.visibility)
        assertEquals(
            controller.get().getString(R.string.item_entry_error_price_too_large),
            binding.fiatPriceLayout.error.toString(),
        )
        assertFalse(validator.validate().isValid)

        binding.itemPriceInput.setText("100")
        assertEquals(View.VISIBLE, binding.priceBreakdownContainer.visibility)
        assertNull(binding.fiatPriceLayout.error)
        assertEquals(120L, validator.validate().grossPriceAtomic)
    }

    @Test
    fun `standard currencies and stablecoins still accept their defined minor units`() {
        mapOf("eur" to "12.34", "bhd" to "1.234", "usdt" to "12.34").forEach { (unit, value) ->
            handler.setSelectedPriceAsset(AssetId.global(UnitId.of(unit)))
            binding.itemPriceInput.setText(value)
            assertTrue(validator.validate().isValid)
            assertEquals(1234L, handler.getEnteredAtomicAmount().value)
            assertTrue(binding.itemPriceInput.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0)
        }
    }
}
